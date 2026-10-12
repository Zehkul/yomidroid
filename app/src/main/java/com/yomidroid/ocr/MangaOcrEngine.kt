package com.yomidroid.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.benjaminwan.ocrlibrary.OcrEngine as RapidEngine
import com.benjaminwan.ocrlibrary.models.DetPoint
import com.yomidroid.config.MangaOcrCropScaling
import com.yomidroid.config.MangaOcrDetector
import com.yomidroid.config.OcrConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job

/**
 * OCR engine wrapping the manga-ocr ONNX recognizer behind a configurable
 * detection + preprocessing pipeline. Detection can be PaddleOCR (default,
 * trained for documents/scenes) or ML Kit (hybrid mode — better for screen
 * text). Recognition is always manga-ocr, optionally in whole-bubble mode.
 *
 * Pipeline: bitmap → optional preprocess (sigmoid + invert + upscale) →
 * detect → recognize (per-line or per-bubble). All result rects are mapped
 * back to the original input bitmap's coordinate space.
 */
class MangaOcrEngine(
    private val context: Context,
    private val config: OcrConfig
) : OcrEngine {

    override val engineId = "manga_ocr"
    override val displayName = "Manga OCR"
    override val requiresNetwork = false

    private var rapidEngine: RapidEngine? = null
    private var mlKitEngine: MlKitOcrEngine? = null
    private var mangaOcrInference: MangaOcrInference? = null
    private var isInitialized = false
    /** Serialize recognition so aborted scans cannot pile up on the decoder. */
    private val recognizeMutex = Mutex()

    companion object {
        private const val TAG = "MangaOcrEngine"
        private const val PADDING = 50
        private const val LINE_MARGIN = 10
    }

    override suspend fun processImage(bitmap: Bitmap): Result<List<OcrResult>> {
        return withContext(Dispatchers.Default) {
            try {
                if (!isInitialized) initialize()
                val totalStart = SystemClock.elapsedRealtime()

                val pre = preprocessIfEnabled(bitmap)
                val results = try {
                    runPipeline(pre.bitmap, pre.scale, bitmap)
                } finally {
                    if (pre.bitmap !== bitmap) pre.bitmap.recycle()
                }

                val totalMs = SystemClock.elapsedRealtime() - totalStart
                Log.d(
                    TAG,
                    "total ms=$totalMs lines=${results.size} " +
                        "detector=${config.mangaOcrDetector} " +
                        "mode=${if (config.mangaOcrWholeBubbleMode) "bubble" else "line"} " +
                        "scaling=${config.mangaOcrCropScaling} " +
                        "preprocess=${config.mangaOcrPreprocess}"
                )
                Result.success(results)
            } catch (e: Exception) {
                Log.e(TAG, "Manga OCR failed: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Recognize a user-selected crop directly — no text detector.
     * The entire bitmap is treated as a single text region (Kaku-style).
     * Optional preprocess still runs; the result's bounds cover the full crop.
     */
    suspend fun processDirectRegion(bitmap: Bitmap): Result<List<OcrResult>> {
        return withContext(Dispatchers.Default) {
            try {
                if (!isInitialized) initialize()
                val totalStart = SystemClock.elapsedRealtime()

                val inference = mangaOcrInference
                    ?: return@withContext Result.failure(IllegalStateException("Manga OCR not initialized"))
                inference.bilinearScaling =
                    config.mangaOcrCropScaling == MangaOcrCropScaling.BILINEAR

                val pre = preprocessIfEnabled(bitmap)
                try {
                    ensureActive()
                    val job = coroutineContext[Job]
                    val recStart = SystemClock.elapsedRealtime()
                    val text = recognizeMutex.withLock {
                        ensureActive()
                        inference.recognize(pre.bitmap) { job?.isActive != false }
                    }
                    ensureActive()
                    val recMs = SystemClock.elapsedRealtime() - recStart
                    Log.d(
                        TAG,
                        "direct-region ms=${SystemClock.elapsedRealtime() - totalStart} " +
                            "recognize ms=$recMs chars=${text.length} " +
                            "input=${bitmap.width}x${bitmap.height}"
                    )
                    if (text.isBlank()) {
                        Result.success(emptyList())
                    } else {
                        val bounds = Rect(0, 0, bitmap.width, bitmap.height)
                        Result.success(
                            listOf(
                                OcrResult(
                                    text,
                                    bounds,
                                    OcrUtils.synthesizeCharBounds(text, bounds)
                                )
                            )
                        )
                    }
                } finally {
                    if (pre.bitmap !== bitmap) pre.bitmap.recycle()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Manga OCR direct region failed: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    private data class Pre(val bitmap: Bitmap, val scale: Float, val inverted: Boolean)

    private fun preprocessIfEnabled(source: Bitmap): Pre {
        if (!config.mangaOcrPreprocess) return Pre(source, 1f, false)
        val preStart = SystemClock.elapsedRealtime()
        val r = OcrPreprocessor.preprocess(source)
        val preMs = SystemClock.elapsedRealtime() - preStart
        Log.d(TAG, "preprocess ms=$preMs scale=${r.scale} inverted=${r.inverted}")
        return Pre(r.bitmap, r.scale, r.inverted)
    }

    private suspend fun runPipeline(
        preBitmap: Bitmap,
        scale: Float,
        originalBitmap: Bitmap
    ): List<OcrResult> {
        val preRects = detect(preBitmap)
        if (preRects.isEmpty()) return emptyList()

        val inference = mangaOcrInference ?: return emptyList()
        inference.bilinearScaling = config.mangaOcrCropScaling == MangaOcrCropScaling.BILINEAR

        return if (config.mangaOcrWholeBubbleMode) {
            recognizeAsBubbles(inference, preBitmap, preRects, scale, originalBitmap)
        } else {
            recognizeAsLines(inference, preBitmap, preRects, scale, originalBitmap)
        }
    }

    private suspend fun detect(preBitmap: Bitmap): List<Rect> {
        val detStart = SystemClock.elapsedRealtime()
        val rects = try {
            when (config.mangaOcrDetector) {
                MangaOcrDetector.PADDLE_OCR -> {
                    try {
                        detectWithPaddleOcr(preBitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "PaddleOCR detect failed, falling back to ML Kit: ${e.message}", e)
                        detectWithMlKit(preBitmap)
                    }
                }
                MangaOcrDetector.ML_KIT_HYBRID -> detectWithMlKit(preBitmap)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "detect failed: ${e.message}", e)
            emptyList()
        }
        val detMs = SystemClock.elapsedRealtime() - detStart
        Log.d(TAG, "detect ms=$detMs regions=${rects.size} backend=${config.mangaOcrDetector}")
        return rects
    }

    /**
     * Paddle detection returns boxes only (no recognition). Recognition is always
     * done by manga-ocr downstream.
     */
    private fun detectWithPaddleOcr(preBitmap: Bitmap): List<Rect> {
        val rapid = rapidEngine ?: return emptyList()
        // Hardware / non-ARGB frames crash or mis-detect in the native pipeline.
        val bmp = ensureSoftwareArgb8888(preBitmap)
        try {
            // OcrEngine is not thread-safe; serialize native detect calls.
            val det = synchronized(rapid) {
                rapid.detect(
                    bmp = bmp,
                    scaleUp = false,
                    maxSideLen = config.mangaOcrMaxSideLen,
                    padding = PADDING,
                    boxScoreThresh = 0.5f,
                    boxThresh = 0.3f,
                    unClipRatio = 1.6f,
                    doCls = false,
                    mostCls = false
                )
            }
            return det.detResults.mapNotNull { d ->
                val r = pointsToRect(d.points)
                if (r.width() <= 0 || r.height() <= 0) null else r
            }
        } finally {
            if (bmp !== preBitmap) bmp.recycle()
        }
    }

    private fun ensureSoftwareArgb8888(src: Bitmap): Bitmap {
        if (src.isRecycled) {
            throw IllegalArgumentException("Bitmap is recycled")
        }
        // Hardware bitmaps need a software copy; already-ARGB software frames are fine.
        if (src.config == Bitmap.Config.ARGB_8888) {
            return src
        }
        return src.copy(Bitmap.Config.ARGB_8888, /* mutable */ true)
            ?: throw IllegalStateException("Failed to copy bitmap to ARGB_8888")
    }

    private suspend fun detectWithMlKit(preBitmap: Bitmap): List<Rect> {
        val ml = mlKitEngine ?: return emptyList()
        val res = ml.processImage(preBitmap).getOrNull() ?: return emptyList()
        return res.map { it.lineBounds }
    }

    private fun recognizeAsLines(
        inference: MangaOcrInference,
        preBitmap: Bitmap,
        preRects: List<Rect>,
        scale: Float,
        originalBitmap: Bitmap
    ): List<OcrResult> = preRects.mapNotNull { preRect ->
        val expanded = expand(preRect, preBitmap)
        val crop = Bitmap.createBitmap(
            preBitmap, expanded.left, expanded.top, expanded.width(), expanded.height()
        )
        try {
            val recStart = SystemClock.elapsedRealtime()
            val text = inference.recognize(crop)
            val recMs = SystemClock.elapsedRealtime() - recStart
            Log.d(TAG, "recognize ms=$recMs chars=${text.length}")
            if (text.isBlank()) return@mapNotNull null

            // Report the detector box, not the padded crop — LINE_MARGIN is only
            // for recognition input; including it stretched the indicator/grid.
            val origBounds = scaleRectDown(preRect, scale, originalBitmap)
            OcrResult(text, origBounds, OcrUtils.synthesizeCharBounds(text, origBounds))
        } finally {
            if (crop !== preBitmap) crop.recycle()
        }
    }

    private fun recognizeAsBubbles(
        inference: MangaOcrInference,
        preBitmap: Bitmap,
        preRects: List<Rect>,
        scale: Float,
        originalBitmap: Bitmap
    ): List<OcrResult> {
        val cleaned = dropFurigana(preRects)
        val clusters = clusterIntoBubbles(cleaned)
        Log.d(
            TAG,
            "bubbles=${clusters.size} from rects=${preRects.size} " +
                "afterFuriganaFilter=${cleaned.size} " +
                "sizes=${clusters.map { it.size }}"
        )

        val out = mutableListOf<OcrResult>()
        for (cluster in clusters) {
            val union = unionRect(cluster)
            val expanded = expand(union, preBitmap)
            val crop = Bitmap.createBitmap(
                preBitmap, expanded.left, expanded.top, expanded.width(), expanded.height()
            )
            try {
                val recStart = SystemClock.elapsedRealtime()
                val text = inference.recognize(crop)
                val recMs = SystemClock.elapsedRealtime() - recStart
                Log.d(
                    TAG,
                    "bubble recognize ms=$recMs chars=${text.length} " +
                        "boxes=${cluster.size} crop=${crop.width}x${crop.height}"
                )
                if (text.isBlank()) continue

                // One OcrResult per bubble. Per-line slicing was for char-grid
                // hit-testing; BubbleHitOverlay looks up whole-bubble text.
                val origBounds = scaleRectDown(union, scale, originalBitmap)
                out.add(
                    OcrResult(text, origBounds, OcrUtils.synthesizeCharBounds(text, origBounds))
                )
            } finally {
                if (crop !== preBitmap) crop.recycle()
            }
        }
        return out
    }

    /**
     * Drop small detector boxes that sit beside a clearly larger neighbour —
     * typical furigana / ruby annotations.
     */
    private fun dropFurigana(rects: List<Rect>): List<Rect> {
        if (rects.size <= 1) return rects
        return rects.filter { small ->
            val smallThick = minOf(small.width(), small.height()).toFloat()
            val isFurigana = rects.any { large ->
                if (large === small) return@any false
                val largeThick = minOf(large.width(), large.height()).toFloat()
                if (largeThick < 1f || smallThick > largeThick * 0.6f) return@any false

                val vOverlap = minOf(small.bottom, large.bottom) - maxOf(small.top, large.top)
                val hOverlap = minOf(small.right, large.right) - maxOf(small.left, large.left)
                val vGap = when {
                    small.bottom <= large.top -> large.top - small.bottom
                    large.bottom <= small.top -> small.top - large.bottom
                    else -> 0
                }
                val hGap = when {
                    small.right <= large.left -> large.left - small.right
                    large.right <= small.left -> small.left - large.right
                    else -> 0
                }
                val besideHorizontally =
                    hGap >= 0 && hGap < largeThick * 0.75f &&
                        vOverlap > minOf(small.height(), large.height()) * 0.4f
                val besideVertically =
                    vGap >= 0 && vGap < largeThick * 0.75f &&
                        hOverlap > minOf(small.width(), large.width()) * 0.4f
                besideHorizontally || besideVertically
            }
            !isFurigana
        }
    }

    /**
     * Group detector boxes into speech-bubble regions by 2D proximity.
     * Side-by-side vertical columns inside one bubble merge; distant bubbles do not.
     */
    private fun clusterIntoBubbles(rects: List<Rect>): List<List<Rect>> {
        if (rects.isEmpty()) return emptyList()

        // Cross-axis thickness ≈ one character, not the long side of a line.
        val medianSpan = rects
            .map { minOf(it.width(), it.height()) }
            .sorted()[rects.size / 2]
            .toFloat()
            .coerceAtLeast(1f)
        // ~one character of gap still counts as "same bubble".
        val maxGap = medianSpan * 1.25f

        val groups = mutableListOf<MutableList<Rect>>()
        val sorted = rects.sortedWith(compareBy({ it.top }, { -it.right }))
        for (r in sorted) {
            val match = groups.firstOrNull { g -> g.any { canClusterBubble(it, r, maxGap) } }
            if (match != null) match.add(r) else groups.add(mutableListOf(r))
        }

        // Union-find style: merge groups that touch via any member pair.
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in groups.indices) {
                for (j in i + 1 until groups.size) {
                    val a = groups[i]
                    val b = groups[j]
                    if (a.any { ra -> b.any { rb -> canClusterBubble(ra, rb, maxGap) } }) {
                        a.addAll(b)
                        groups.removeAt(j)
                        merged = true
                        break@outer
                    }
                }
            }
        }

        return groups.map { g ->
            val vertical = g.count { OcrUtils.isVerticalLine(it) } * 2 >= g.size
            if (vertical) {
                g.sortedWith(compareByDescending<Rect> { it.right }.thenBy { it.top })
            } else {
                g.sortedBy { it.top }
            }
        }.sortedByDescending { g -> g.maxOf { it.right } }
    }

    private fun canClusterBubble(a: Rect, b: Rect, maxGap: Float): Boolean {
        val gapX = when {
            a.right < b.left -> (b.left - a.right).toFloat()
            b.right < a.left -> (a.left - b.right).toFloat()
            else -> 0f
        }
        val gapY = when {
            a.bottom < b.top -> (b.top - a.bottom).toFloat()
            b.bottom < a.top -> (a.top - b.bottom).toFloat()
            else -> 0f
        }
        return gapX <= maxGap && gapY <= maxGap
    }

    private fun unionRect(rects: List<Rect>): Rect {
        val out = Rect(rects[0])
        for (i in 1 until rects.size) out.union(rects[i])
        return out
    }

    private fun expand(r: Rect, bitmap: Bitmap): Rect = Rect(
        (r.left - LINE_MARGIN).coerceAtLeast(0),
        (r.top - LINE_MARGIN).coerceAtLeast(0),
        (r.right + LINE_MARGIN).coerceAtMost(bitmap.width),
        (r.bottom + LINE_MARGIN).coerceAtMost(bitmap.height)
    )

    private fun scaleRectDown(r: Rect, scale: Float, original: Bitmap): Rect {
        if (scale == 1f) return Rect(r)
        return Rect(
            (r.left / scale).toInt().coerceIn(0, original.width),
            (r.top / scale).toInt().coerceIn(0, original.height),
            (r.right / scale).toInt().coerceIn(0, original.width),
            (r.bottom / scale).toInt().coerceIn(0, original.height)
        )
    }

    private fun initialize() {
        val modelManager = MangaOcrModelManager.getInstance(context)
        if (!modelManager.isModelReady()) {
            throw IllegalStateException("Manga OCR models not downloaded. Please download from OCR Settings.")
        }

        Log.d(TAG, "Initializing MangaOcrEngine config=$config")
        rapidEngine = RapidEngine(context)
        mlKitEngine = MlKitOcrEngine()
        mangaOcrInference = MangaOcrInference(
            encoderPath = modelManager.getEncoderPath(),
            decoderPath = modelManager.getDecoderPath(),
            vocabPath = modelManager.getVocabPath(),
            bilinearScaling = config.mangaOcrCropScaling == MangaOcrCropScaling.BILINEAR
        )
        isInitialized = true
        Log.d(TAG, "MangaOcrEngine initialized successfully")
    }

    private fun pointsToRect(points: List<DetPoint>): Rect {
        if (points.isEmpty()) return Rect()
        val xs = points.map { it.x - PADDING }
        val ys = points.map { it.y - PADDING }
        return Rect(
            maxOf(xs.minOrNull() ?: 0, 0),
            maxOf(ys.minOrNull() ?: 0, 0),
            xs.maxOrNull() ?: 0,
            ys.maxOrNull() ?: 0
        )
    }

    override fun close() {
        mangaOcrInference?.close()
        mangaOcrInference = null
        rapidEngine?.close()
        rapidEngine = null
        mlKitEngine?.close()
        mlKitEngine = null
        isInitialized = false
        Log.d(TAG, "MangaOcrEngine closed")
    }
}
