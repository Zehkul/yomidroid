package com.yomidroid.ocr

import com.yomidroid.BuildConfig
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
import kotlinx.coroutines.withContext

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

    companion object {
        private const val TAG = "MangaOcrEngine"
        private const val PADDING = 50
        private const val LINE_MARGIN = 10
        // Crop dumps only in debug builds (never release/CI release APKs).
        private val DEBUG_DUMP_CROPS = BuildConfig.DEBUG
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
                    val recStart = SystemClock.elapsedRealtime()
                    val text = inference.recognize(pre.bitmap)
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
        } catch (e: Exception) {
            Log.e(TAG, "detect failed: ${e.message}", e)
            emptyList()
        }
        val detMs = SystemClock.elapsedRealtime() - detStart
        Log.d(TAG, "detect ms=$detMs regions=${rects.size} backend=${config.mangaOcrDetector}")
        return rects
    }

    /**
     * Run RapidOCR for **boxes only**. Classification/recognition are disabled —
     * manga-ocr does recognition. OpenCV needs a software ARGB_8888 bitmap.
     */
    private fun detectWithPaddleOcr(preBitmap: Bitmap): List<Rect> {
        val rapid = rapidEngine ?: return emptyList()
        val bmp = ensureSoftwareArgb8888(preBitmap)
        try {
            // OcrEngine is not thread-safe (shared OrtSession / OpenCV Mats).
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
        if (src.config == Bitmap.Config.ARGB_8888 && src.isMutable) {
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

                val singleColumn = isSingleColumn(cluster)
                if (singleColumn && cluster.size > 1) {
                    val ordered = cluster.sortedBy { it.top }
                    val vertical =
                        ordered.count { OcrUtils.isVerticalLine(it) } * 2 >= ordered.size
                    val slices = splitTextAcrossLines(text, ordered, vertical)
                    if (isSplitReliable(text, ordered, slices)) {
                        for (i in ordered.indices) {
                            val slice = slices[i]
                            if (slice.isBlank()) continue
                            val origBounds = scaleRectDown(ordered[i], scale, originalBitmap)
                            out.add(
                                OcrResult(
                                    slice,
                                    origBounds,
                                    OcrUtils.synthesizeCharBounds(slice, origBounds)
                                )
                            )
                        }
                        continue
                    }
                }

                // Multi-column bubble (or unreliable split): one result for the
                // whole union — manga-ocr is trained on multi-line bubbles.
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
            val smallArea = small.width().toLong() * small.height()
            val isFurigana = rects.any { large ->
                if (large === small) return@any false
                val largeArea = large.width().toLong() * large.height()
                if (largeArea < smallArea * 2L) return@any false
                if (smallArea > largeArea * 0.45) return@any false

                val vOverlap = minOf(small.bottom, large.bottom) - maxOf(small.top, large.top)
                val hOverlap = minOf(small.right, large.right) - maxOf(small.left, large.left)
                val minH = minOf(small.height(), large.height())
                val minW = minOf(small.width(), large.width())

                val besideHorizontally =
                    vOverlap > minH * 0.35f && hOverlap < minW * 0.25f
                val besideVertically =
                    hOverlap > minW * 0.35f && vOverlap < minH * 0.25f

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

        val medianSpan = rects
            .map { maxOf(it.width(), it.height()) }
            .sorted()[rects.size / 2]
            .toFloat()
            .coerceAtLeast(1f)
        // ~one character-box of gap still counts as "same bubble".
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

    /** True when every box shares roughly the same horizontal centre (one column). */
    private fun isSingleColumn(boxes: List<Rect>): Boolean {
        if (boxes.size <= 1) return true
        val centres = boxes.map { (it.left + it.right) / 2f }
        val avgW = boxes.map { it.width() }.average().toFloat().coerceAtLeast(1f)
        val minC = centres.minOrNull() ?: return true
        val maxC = centres.maxOrNull() ?: return true
        return (maxC - minC) <= avgW * 0.9f
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

    /**
     * Split recognised text across line boxes proportional to span on the
     * reading axis. Only used for single-column multi-line bubbles.
     */
    private fun splitTextAcrossLines(
        text: String,
        lines: List<Rect>,
        vertical: Boolean
    ): List<String> {
        if (lines.size <= 1) return listOf(text)
        val spans = lines.map {
            if (vertical) it.height().toFloat() else it.width().toFloat()
        }
        val total = spans.sum().coerceAtLeast(1f)
        val out = mutableListOf<String>()
        var consumed = 0
        for (i in lines.indices) {
            val end = if (i == lines.size - 1) text.length
            else (text.length * spans.take(i + 1).sum() / total).toInt()
                .coerceIn(consumed, text.length)
            out.add(text.substring(consumed, end))
            consumed = end
        }
        return out
    }

    private fun isSplitReliable(
        text: String,
        lines: List<Rect>,
        slices: List<String>
    ): Boolean {
        if (lines.size <= 1) return true
        if (text.length < lines.size) return false
        if (slices.size != lines.size) return false
        if (slices.count { it.isBlank() } * 2 > lines.size) return false
        return true
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
        if (DEBUG_DUMP_CROPS) MangaOcrInference.debugDumpDir = context.getExternalFilesDir("ocr_dump")
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
