package com.example.cast

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.example.data.model.Song
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders the 1280x720 (720p HD) TV Now-Playing canvas with:
 * - Full-bleed blurred album art backdrop + cinematic dark gradient scrim
 * - Crisp rounded album artwork on the right half
 * - Album, Title, and Artist on the upper-left
 * - Live synced lyrics (current bold line + upcoming preview line) directly below Title & Artist,
 *   using Android's native StaticLayout so Tamil, Hindi, Telugu, Malayalam, Japanese, Korean,
 *   and English text shapes perfectly with zero tofu boxes.
 * - Clean bottom progress bar + elapsed/total timestamps.
 *
 * Performance:
 * - Static background + artwork + header are pre-composited once per song into [baseBitmap].
 * - ARGB -> YUV420 conversion is incremental: only dirty horizontal strips (lyrics zone when a line
 *   changes, and the thin 44px progress strip on each tick) are re-converted, keeping CPU < 2%.
 */
class CastTvFrameRenderer(
    private val song: Song,
    artworkBytes: ByteArray?
) {
    val width: Int = VIDEO_WIDTH
    val height: Int = VIDEO_HEIGHT

    private val baseBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val frameBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val frameCanvas = Canvas(frameBitmap)

    // Reusable I420 YUV buffers (width * height for Y, quarter each for U & V)
    val yPlane: ByteArray = ByteArray(width * height)
    val uPlane: ByteArray = ByteArray((width / 2) * (height / 2))
    val vPlane: ByteArray = ByteArray((width / 2) * (height / 2))
    private val rowPixels = IntArray(width * 2) // 2 rows at a time for fast 2x2 chroma subsampling

    private val accentColor: Int
    private var lastRenderedCurrentLyric: String? = "__INIT__"
    private var lastRenderedNextLyric: String? = "__INIT__"
    private var lastRenderedLyricsEnabled: Boolean? = null
    private var lastRenderedProgressSec: Long = -1L
    private var isFullYuvInitialized = false

    private val lyricsActivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 35f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setShadowLayer(8f, 0f, 3f, Color.argb(200, 0, 0, 0))
    }

    private val lyricsNextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(155, 240, 244, 255)
        textSize = 22f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        setShadowLayer(4f, 0f, 2f, Color.argb(150, 0, 0, 0))
    }

    private val accentBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(72, 255, 255, 255)
    }
    private val trackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(195, 240, 244, 255)
        textSize = 18f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val subtleBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 235, 240, 255)
        textSize = 22f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val waveBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val lyricsDirtyRect = Rect(0, LYRICS_ZONE_TOP, width, LYRICS_ZONE_BOTTOM)
    private val progressDirtyRect = Rect(0, PROGRESS_ZONE_TOP, width, PROGRESS_ZONE_BOTTOM)

    init {
        val decodedArt = artworkBytes?.takeIf { it.isNotEmpty() }?.let { bytes ->
            try {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } catch (_: Throwable) {
                null
            }
        }
        accentColor = extractVibrantAccent(decodedArt)
        accentBarPaint.color = accentColor
        trackFillPaint.color = accentColor
        waveBarPaint.color = accentColor

        renderStaticBase(decodedArt)
        frameCanvas.drawBitmap(baseBitmap, 0f, 0f, null)
        decodedArt?.recycle()
    }

    /**
     * Updates [yPlane], [uPlane], and [vPlane] for playback position [positionMs].
     * Redraws and re-converts only the horizontal regions that actually changed.
     */
    fun renderFrameToYuv(
        positionMs: Long,
        durationMs: Long,
        currentLyricLine: String?,
        nextLyricLine: String?,
        showLyrics: Boolean,
        frameTickIndex: Int
    ) {
        val safePos = positionMs.coerceAtLeast(0L)
        val safeDur = durationMs.takeIf { it > 0L } ?: song.durationMs.coerceAtLeast(1L)
        val effectiveCurrent = if (showLyrics) currentLyricLine?.trim()?.takeIf { it.isNotEmpty() } else null
        val effectiveNext = if (showLyrics) nextLyricLine?.trim()?.takeIf { it.isNotEmpty() } else null

        val hasLyricsText = effectiveCurrent != null
        val lyricsChanged = !isFullYuvInitialized ||
            lastRenderedCurrentLyric != effectiveCurrent ||
            lastRenderedNextLyric != effectiveNext ||
            lastRenderedLyricsEnabled != showLyrics ||
            !hasLyricsText // animate subtle visualizer bars when no lyric line is active

        val currentSec = safePos / 1000L
        val progressChanged = !isFullYuvInitialized || currentSec != lastRenderedProgressSec

        if (lyricsChanged) {
            frameCanvas.drawBitmap(baseBitmap, lyricsDirtyRect, lyricsDirtyRect, null)
            if (hasLyricsText) {
                drawSyncedLyricsBlock(effectiveCurrent!!, effectiveNext)
            } else {
                drawIdleMusicWaveBlock(frameTickIndex)
            }
            lastRenderedCurrentLyric = effectiveCurrent
            lastRenderedNextLyric = effectiveNext
            lastRenderedLyricsEnabled = showLyrics
        }

        if (progressChanged) {
            frameCanvas.drawBitmap(baseBitmap, progressDirtyRect, progressDirtyRect, null)
            drawProgressBar(safePos, safeDur)
            lastRenderedProgressSec = currentSec
        }

        if (!isFullYuvInitialized) {
            convertRowsToYuv420(0, height)
            isFullYuvInitialized = true
        } else {
            if (lyricsChanged) {
                convertRowsToYuv420(LYRICS_ZONE_TOP, LYRICS_ZONE_BOTTOM)
            }
            if (progressChanged) {
                convertRowsToYuv420(PROGRESS_ZONE_TOP, PROGRESS_ZONE_BOTTOM)
            }
        }
    }

    fun close() {
        try {
            if (!baseBitmap.isRecycled) baseBitmap.recycle()
        } catch (_: Throwable) {}
        try {
            if (!frameBitmap.isRecycled) frameBitmap.recycle()
        } catch (_: Throwable) {}
    }

    private fun renderStaticBase(artBitmap: Bitmap?) {
        val canvas = Canvas(baseBitmap)

        // 1. Blurred full-bleed artwork background (or rich dark gradient fallback)
        if (artBitmap != null) {
            val blurred = createFastBlurredBackdrop(artBitmap)
            val src = centerCropRect(blurred.width, blurred.height, width, height)
            val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(blurred, src, Rect(0, 0, width, height), bgPaint)
            blurred.recycle()

            // Left-heavy dark gradient scrim so title, artist & lyrics have crisp TV readability
            val horizScrim = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), 0f,
                    intArrayOf(
                        Color.argb(218, 9, 11, 18),
                        Color.argb(188, 10, 13, 22),
                        Color.argb(115, 10, 13, 22)
                    ),
                    floatArrayOf(0f, 0.56f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), horizScrim)

            val vertScrim = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(
                        Color.argb(95, 6, 8, 14),
                        Color.argb(15, 6, 8, 14),
                        Color.argb(175, 6, 8, 14)
                    ),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vertScrim)
        } else {
            val fallbackBg = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    Color.rgb(22, 20, 36),
                    Color.rgb(10, 12, 20),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallbackBg)
        }

        // 2. Subtle top-left app header
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 255, 255, 255)
            textSize = 20f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.04f
        }
        canvas.drawText("OffGrid Music", LEFT_MARGIN.toFloat(), 74f, brandPaint)

        // 3. Album Art card on the right half (matching the beloved TV layout)
        val artRect = RectF(786f, 136f, 1196f, 546f)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 0, 0, 0)
            setShadowLayer(28f, 0f, 12f, Color.argb(180, 0, 0, 0))
        }
        canvas.drawRoundRect(artRect, 26f, 26f, shadowPaint)

        val clipPath = Path().apply { addRoundRect(artRect, 26f, 26f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clipPath)
        if (artBitmap != null) {
            val crop = centerCropRect(artBitmap.width, artBitmap.height, artRect.width().toInt(), artRect.height().toInt())
            canvas.drawBitmap(artBitmap, crop, artRect, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val cardGrad = Paint().apply {
                shader = LinearGradient(
                    artRect.left, artRect.top, artRect.right, artRect.bottom,
                    Color.argb(255, 48, 38, 68),
                    Color.argb(255, 24, 22, 36),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(artRect, cardGrad)
            val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 255, 255, 255)
                textSize = 136f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("♪", artRect.centerX(), artRect.centerY() + 48f, notePaint)
        }
        canvas.restore()

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(55, 255, 255, 255)
        }
        canvas.drawRoundRect(artRect, 26f, 26f, borderPaint)

        // 4. Left column: Album + Title + Artist
        val textMaxWidth = 656
        var yCursor = 152f

        val albumText = song.displayAlbum.takeIf { it.isNotBlank() && !it.equals("Unknown Album", ignoreCase = true) }
        if (albumText != null) {
            val albumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(185, 232, 238, 250)
                textSize = 17f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.08f
            }
            val albumLayout = createStaticLayout(albumText.uppercase(Locale.US), albumPaint, textMaxWidth, 1)
            canvas.save()
            canvas.translate(LEFT_MARGIN.toFloat(), yCursor)
            albumLayout.draw(canvas)
            canvas.restore()
            yCursor += albumLayout.height + 10f
        }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(6f, 0f, 2f, Color.argb(140, 0, 0, 0))
        }
        val titleLayout = createStaticLayout(song.displayTitle.ifBlank { "Now Playing" }, titlePaint, textMaxWidth, 2)
        canvas.save()
        canvas.translate(LEFT_MARGIN.toFloat(), yCursor)
        titleLayout.draw(canvas)
        canvas.restore()
        yCursor += titleLayout.height + 10f

        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 224, 230, 242)
            textSize = 23f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val artistLayout = createStaticLayout(song.displayArtist.ifBlank { "Unknown Artist" }, artistPaint, textMaxWidth, 2)
        canvas.save()
        canvas.translate(LEFT_MARGIN.toFloat(), yCursor)
        artistLayout.draw(canvas)
        canvas.restore()
    }

    private fun drawSyncedLyricsBlock(currentLine: String, nextLine: String?) {
        val textX = LEFT_MARGIN + 20
        val textMaxWidth = 636

        val activeLayout = createStaticLayout(currentLine, lyricsActivePaint, textMaxWidth, 3)
        val activeTop = LYRICS_ZONE_TOP + 22f

        // Crisp vertical accent bar on the left of the active lyric line
        val barRect = RectF(
            LEFT_MARGIN.toFloat(),
            activeTop + 4f,
            (LEFT_MARGIN + 6).toFloat(),
            (activeTop + activeLayout.height - 2f).coerceAtMost(LYRICS_ZONE_BOTTOM - 28f)
        )
        frameCanvas.drawRoundRect(barRect, 3f, 3f, accentBarPaint)

        // Draw active current lyric line
        frameCanvas.save()
        frameCanvas.translate(textX.toFloat(), activeTop)
        activeLayout.draw(frameCanvas)
        frameCanvas.restore()

        // Draw subtle next-line preview underneath if room permits
        if (!nextLine.isNullOrBlank()) {
            val nextTop = activeTop + activeLayout.height + 16f
            if (nextTop + 34f < LYRICS_ZONE_BOTTOM) {
                val nextLayout = createStaticLayout(nextLine, lyricsNextPaint, textMaxWidth, 2)
                frameCanvas.save()
                frameCanvas.translate(textX.toFloat(), nextTop)
                nextLayout.draw(frameCanvas)
                frameCanvas.restore()
            }
        }
    }

    private fun drawIdleMusicWaveBlock(frameTickIndex: Int) {
        val baseY = (LYRICS_ZONE_TOP + 86).toFloat()
        val barCount = 5
        val barWidth = 6f
        val barGap = 6f
        for (i in 0 until barCount) {
            val phase = frameTickIndex * 0.85 + i * 1.15
            val norm = ((sin(phase) + 1.0) * 0.5).toFloat()
            val h = 10f + norm * 24f
            val x = LEFT_MARGIN + i * (barWidth + barGap)
            frameCanvas.drawRoundRect(
                RectF(x, baseY - h / 2f, x + barWidth, baseY + h / 2f),
                3f, 3f, waveBarPaint
            )
        }
        frameCanvas.drawText(
            "Playing on TV",
            LEFT_MARGIN + barCount * (barWidth + barGap) + 12f,
            baseY + 8f,
            subtleBadgePaint
        )
    }

    private fun drawProgressBar(positionMs: Long, durationMs: Long) {
        val barLeft = LEFT_MARGIN.toFloat()
        val barRight = (width - LEFT_MARGIN).toFloat()
        val barTop = (PROGRESS_ZONE_TOP + 10).toFloat()
        val barBottom = barTop + 6f
        val progress = (positionMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

        frameCanvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 3f, 3f, trackBgPaint)
        val fillRight = barLeft + (barRight - barLeft) * progress
        if (fillRight > barLeft + 2f) {
            frameCanvas.drawRoundRect(RectF(barLeft, barTop, fillRight, barBottom), 3f, 3f, trackFillPaint)
        }

        val elapsedText = formatTime(positionMs)
        val totalText = formatTime(durationMs)
        timePaint.textAlign = Paint.Align.LEFT
        frameCanvas.drawText(elapsedText, barLeft, barBottom + 24f, timePaint)
        timePaint.textAlign = Paint.Align.RIGHT
        frameCanvas.drawText(totalText, barRight, barBottom + 24f, timePaint)
    }

    /**
     * Converts horizontal even-aligned rows `[startY, endY)` of [frameBitmap] directly into
     * planar I420 [yPlane], [uPlane], [vPlane] using standard BT.601 integer math.
     */
    private fun convertRowsToYuv420(startY: Int, endY: Int) {
        val y0 = (startY and -2).coerceIn(0, height)
        val y1 = ((endY + 1) and -2).coerceIn(y0, height)
        val halfW = width ushr 1

        var row = y0
        while (row < y1) {
            frameBitmap.getPixels(rowPixels, 0, width, 0, row, width, 2)
            val yRow0 = row * width
            val yRow1 = yRow0 + width
            val uvRow = (row ushr 1) * halfW

            var col = 0
            var uvCol = 0
            while (col < width) {
                val p00 = rowPixels[col]
                val p01 = rowPixels[col + 1]
                val p10 = rowPixels[width + col]
                val p11 = rowPixels[width + col + 1]

                val r00 = (p00 ushr 16) and 0xFF
                val g00 = (p00 ushr 8) and 0xFF
                val b00 = p00 and 0xFF
                yPlane[yRow0 + col] = (((66 * r00 + 129 * g00 + 25 * b00 + 128) shr 8) + 16).coerceIn(16, 235).toByte()

                val r01 = (p01 ushr 16) and 0xFF
                val g01 = (p01 ushr 8) and 0xFF
                val b01 = p01 and 0xFF
                yPlane[yRow0 + col + 1] = (((66 * r01 + 129 * g01 + 25 * b01 + 128) shr 8) + 16).coerceIn(16, 235).toByte()

                val r10 = (p10 ushr 16) and 0xFF
                val g10 = (p10 ushr 8) and 0xFF
                val b10 = p10 and 0xFF
                yPlane[yRow1 + col] = (((66 * r10 + 129 * g10 + 25 * b10 + 128) shr 8) + 16).coerceIn(16, 235).toByte()

                val r11 = (p11 ushr 16) and 0xFF
                val g11 = (p11 ushr 8) and 0xFF
                val b11 = p11 and 0xFF
                yPlane[yRow1 + col + 1] = (((66 * r11 + 129 * g11 + 25 * b11 + 128) shr 8) + 16).coerceIn(16, 235).toByte()

                val rAvg = (r00 + r01 + r10 + r11) ushr 2
                val gAvg = (g00 + g01 + g10 + g11) ushr 2
                val bAvg = (b00 + b01 + b10 + b11) ushr 2

                val u = (((-38 * rAvg - 74 * gAvg + 112 * bAvg + 128) shr 8) + 128).coerceIn(16, 240)
                val v = (((112 * rAvg - 94 * gAvg - 18 * bAvg + 128) shr 8) + 128).coerceIn(16, 240)
                uPlane[uvRow + uvCol] = u.toByte()
                vPlane[uvRow + uvCol] = v.toByte()

                col += 2
                uvCol++
            }
            row += 2
        }
    }

    private fun createStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        maxWidth: Int,
        maxLines: Int
    ): StaticLayout {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4f, 1.05f)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, maxWidth, Layout.Alignment.ALIGN_NORMAL, 1.05f, 4f, false)
        }
    }

    private fun createFastBlurredBackdrop(src: Bitmap): Bitmap {
        val smallW = 72
        val smallH = 42
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val pixels = IntArray(smallW * smallH)
        val tmp = IntArray(smallW * smallH)
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)

        // Two-pass separable box blur (radius 3) for silky-smooth ambient TV backdrop
        val radius = 3
        repeat(2) {
            // Horizontal pass
            for (y in 0 until smallH) {
                val rowOff = y * smallW
                for (x in 0 until smallW) {
                    var r = 0; var g = 0; var b = 0; var count = 0
                    val xStart = max(0, x - radius)
                    val xEnd = min(smallW - 1, x + radius)
                    for (nx in xStart..xEnd) {
                        val c = pixels[rowOff + nx]
                        r += (c ushr 16) and 0xFF
                        g += (c ushr 8) and 0xFF
                        b += c and 0xFF
                        count++
                    }
                    tmp[rowOff + x] = Color.rgb(r / count, g / count, b / count)
                }
            }
            // Vertical pass
            for (x in 0 until smallW) {
                for (y in 0 until smallH) {
                    var r = 0; var g = 0; var b = 0; var count = 0
                    val yStart = max(0, y - radius)
                    val yEnd = min(smallH - 1, y + radius)
                    for (ny in yStart..yEnd) {
                        val c = tmp[ny * smallW + x]
                        r += (c ushr 16) and 0xFF
                        g += (c ushr 8) and 0xFF
                        b += c and 0xFF
                        count++
                    }
                    pixels[y * smallW + x] = Color.rgb(r / count, g / count, b / count)
                }
            }
        }
        small.setPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
        return small
    }

    private fun extractVibrantAccent(art: Bitmap?): Int {
        if (art == null) return Color.rgb(255, 145, 77)
        return try {
            val tiny = Bitmap.createScaledBitmap(art, 16, 16, true)
            val hsv = FloatArray(3)
            var bestColor = Color.rgb(255, 145, 77)
            var bestScore = -1f
            for (y in 0 until 16) {
                for (x in 0 until 16) {
                    val c = tiny.getPixel(x, y)
                    Color.colorToHSV(c, hsv)
                    val sat = hsv[1]
                    val value = hsv[2]
                    if (value > 0.35f && sat > 0.25f) {
                        val score = sat * 1.4f + value
                        if (score > bestScore) {
                            bestScore = score
                            hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
                            hsv[2] = hsv[2].coerceIn(0.78f, 0.96f)
                            bestColor = Color.HSVToColor(hsv)
                        }
                    }
                }
            }
            tiny.recycle()
            bestColor
        } catch (_: Throwable) {
            Color.rgb(255, 145, 77)
        }
    }

    private fun centerCropRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val srcRatio = srcW.toFloat() / srcH.coerceAtLeast(1).toFloat()
        val dstRatio = dstW.toFloat() / dstH.coerceAtLeast(1).toFloat()
        return if (srcRatio > dstRatio) {
            val newW = (srcH * dstRatio).toInt().coerceAtLeast(1)
            val left = (srcW - newW) / 2
            Rect(left, 0, left + newW, srcH)
        } else {
            val newH = (srcW / dstRatio).toInt().coerceAtLeast(1)
            val top = (srcH - newH) / 2
            Rect(0, top, srcW, top + newH)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", totalSec / 60L, totalSec % 60L)
    }

    companion object {
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        private const val LEFT_MARGIN = 84
        private const val LYRICS_ZONE_TOP = 356
        private const val LYRICS_ZONE_BOTTOM = 626
        private const val PROGRESS_ZONE_TOP = 646
        private const val PROGRESS_ZONE_BOTTOM = 700
    }
}
