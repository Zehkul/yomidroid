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
        // DEBUG: save every crop fed to manga-ocr (+ result) to getExternalFilesDir("ocr_dump")
        private const val DEBUG_DUMP_CROPS = true
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
        val rects = when (config.mangaOcrDetector) {
            MangaOcrDetector.PADDLE_OCR -> detectWithPaddleOcr(preBitmap)
            MangaOcrDetector.ML_KIT_HYBRID -> detectWithMlKit(preBitmap)
        }
        val detMs = SystemClock.elapsedRealtime() - detStart
        Log.d(TAG, "detect ms=$detMs regions=${rects.size} backend=${config.mangaOcrDetector}")
        return rects
    }

    private fun detectWithPaddleOcr(preBitmap: Bitmap): List<Rect> {
        val rapid = rapidEngine ?: return emptyList()
        val det = rapid.detect(
            bmp = preBitmap,
            scaleUp = false,
            maxSideLen = config.mangaOcrMaxSideLen,
            padding = PADDING,
            boxScoreThresh = 0.5f,
            boxThresh = 0.3f,
            unClipRatio = 1.6f,
            doCls = true,
            mostCls = true
        )
        return det.detResults.mapNotNull { d ->
            val r = pointsToRect(d.points)
            if (r.width() <= 0 || r.height() <= 0) null else r
        }
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
        // Drop furigana / ruby (small boxes beside a larger main-text box) before
        // clustering so they do not warp column grouping or text splits.
        val cleaned = dropFurigana(preRects)
        val clusters = clusterRects(cleaned)
        Log.d(
            TAG,
            "clusters=${clusters.size} from rects=${preRects.size} " +
                "afterFuriganaFilter=${cleaned.size}"
        )

        return clusters.flatMap { cluster ->
            val union = unionRect(cluster)
            val vertical = cluster.count { OcrUtils.isVerticalLine(it) } * 2 >= cluster.size
            val ordered = if (vertical) {
                // Top → bottom within a column
                cluster.sortedBy { it.top }
            } else {
                cluster.sortedBy { it.top }
            }
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
                    "recognize ms=$recMs chars=${text.length} " +
                        "linesInCluster=${ordered.size} vertical=$vertical"
                )
                if (text.isBlank()) return@flatMap emptyList<OcrResult>()

                val slices = splitTextAcrossLines(text, ordered, vertical)
                if (!isSplitReliable(text, ordered, slices)) {
                    // Unreliable proportional split → one result for the whole bubble.
                    val origBounds = scaleRectDown(union, scale, originalBitmap)
                    return@flatMap listOf(
                        OcrResult(text, origBounds, OcrUtils.synthesizeCharBounds(text, origBounds))
                    )
                }

                slices.mapIndexed { i, slice ->
                    if (slice.isBlank()) return@mapIndexed null
                    val origBounds = scaleRectDown(ordered[i], scale, originalBitmap)
                    OcrResult(slice, origBounds, OcrUtils.synthesizeCharBounds(slice, origBounds))
                }.filterNotNull()
            } finally {
                if (crop !== preBitmap) crop.recycle()
            }
        }
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

    private fun unionRect(rects: List<Rect>): Rect {
        val out = Rect(rects[0])
        for (i in 1 until rects.size) out.union(rects[i])
        return out
    }

    /**
     * Drop small detector boxes that sit beside a clearly larger neighbour —
     * typical furigana / ruby annotations. Keeps main body text for clustering.
     */
    private fun dropFurigana(rects: List<Rect>): List<Rect> {
        if (rects.size <= 1) return rects
        return rects.filter { small ->
            val smallArea = small.width().toLong() * small.height()
            val isFurigana = rects.any { large ->
                if (large === small) return@any false
                val largeArea = large.width().toLong() * large.height()
                // Require a clearly larger neighbour
                if (largeArea < smallArea * 2L) return@any false
                if (smallArea > largeArea * 0.45) return@any false

                val vOverlap = minOf(small.bottom, large.bottom) - maxOf(small.top, large.top)
                val hOverlap = minOf(small.right, large.right) - maxOf(small.left, large.left)
                val minH = minOf(small.height(), large.height())
                val minW = minOf(small.width(), large.width())

                // Beside horizontally (vertical-column furigana): strong vertical
                // overlap, little or no horizontal overlap, small is to the side.
                val besideHorizontally =
                    vOverlap > minH * 0.35f &&
                        hOverlap < minW * 0.25f

                // Above/below (horizontal-line furigana): strong horizontal overlap
                val besideVertically =
                    hOverlap > minW * 0.35f &&
                        vOverlap < minH * 0.25f

                besideHorizontally || besideVertically
            }
            !isFurigana
        }
    }

    /**
     * Group detector boxes into bubbles / columns.
     * Vertical-dominant pages: cluster by column (horizontal proximity), order
     * columns right-to-left, lines top-to-bottom within a column.
     * Horizontal-dominant: cluster by vertical stacking (previous behaviour).
     */
    private fun clusterRects(rects: List<Rect>): List<List<Rect>> {
        if (rects.isEmpty()) return emptyList()
        val verticalDominant =
            rects.count { OcrUtils.isVerticalLine(it) } * 2 >= rects.size

        val groups = mutableListOf<MutableList<Rect>>()
        val sorted = if (verticalDominant) {
            // Process top-to-bottom so columns grow naturally
            rects.sortedWith(compareBy({ it.top }, { -it.right }))
        } else {
            rects.sortedBy { it.top }
        }

        for (r in sorted) {
            val match = groups.firstOrNull { g ->
                g.any {
                    if (verticalDominant) canClusterColumn(it, r) else canClusterRow(it, r)
                }
            }
            if (match != null) match.add(r) else groups.add(mutableListOf(r))
        }

        return if (verticalDominant) {
            // Columns right → left (manga reading order)
            groups
                .map { g -> g.sortedBy { it.top } }
                .sortedByDescending { g -> g.maxOf { it.right } }
        } else {
            groups.map { g -> g.sortedBy { it.top } }
        }
    }

    /** Same column: centres close horizontally, stacked with a modest vertical gap. */
    private fun canClusterColumn(a: Rect, b: Rect): Boolean {
        val cxA = (a.left + a.right) / 2f
        val cxB = (b.left + b.right) / 2f
        val avgW = (a.width() + b.width()) / 2f
        val hOverlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val sameColumn =
            kotlin.math.abs(cxA - cxB) <= avgW * 0.75f ||
                hOverlap >= minOf(a.width(), b.width()) * 0.3f
        if (!sameColumn) return false
        val gap = when {
            a.bottom <= b.top -> b.top - a.bottom
            b.bottom <= a.top -> a.top - b.bottom
            else -> 0
        }
        return gap <= maxOf(a.height(), b.height()) * 1.5f
    }

    /** Same horizontal bubble: left-right overlap, stacked with a modest vertical gap. */
    private fun canClusterRow(a: Rect, b: Rect): Boolean {
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val narrower = minOf(a.width(), b.width())
        if (narrower <= 0 || overlap < narrower * 0.3f) return false
        val gap = when {
            a.bottom <= b.top -> b.top - a.bottom
            b.bottom <= a.top -> a.top - b.bottom
            else -> 0
        }
        return gap <= maxOf(a.height(), b.height()) * 1.5f
    }

    /**
     * Split recognised text across line boxes proportional to span on the
     * reading axis (height for vertical columns, width for horizontal lines).
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

    /**
     * Proportional splits are approximate. Fall back to a single union result
     * when the slice looks empty or far too short relative to the number of lines.
     */
    private fun isSplitReliable(
        text: String,
        lines: List<Rect>,
        slices: List<String>
    ): Boolean {
        if (lines.size <= 1) return true
        if (text.length < lines.size) return false
        if (slices.size != lines.size) return false
        // More than half the lines empty → split failed
        if (slices.count { it.isBlank() } * 2 > lines.size) return false
        return true
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
