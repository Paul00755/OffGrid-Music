package com.example.widget

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import com.example.data.model.Song

object WidgetBitmapRenderer {

    private var cachedSongKey: String? = null
    private var cachedSourceBitmap: Bitmap? = null
    private var cachedAccentColor: Int = 0xFF10B981.toInt()
    private var cachedRoundedArt56: Bitmap? = null
    private var cachedRoundedArt60: Bitmap? = null
    private var cachedBlurredBg: Bitmap? = null

    @Synchronized
    fun getOrLoadSongBitmap(context: Context, song: Song?): Bitmap? {
        if (song == null) {
            clearCache()
            return null
        }
        val key = "${song.id}_${song.artworkUri ?: song.contentUriString}"
        if (key == cachedSongKey && cachedSourceBitmap != null) {
            return cachedSourceBitmap
        }

        val bmp = loadSongBitmap(context, song)
        cachedSongKey = key
        cachedSourceBitmap = bmp
        cachedAccentColor = extractDominantAccentColor(bmp)
        cachedRoundedArt56 = null
        cachedRoundedArt60 = null
        cachedBlurredBg = null
        return bmp
    }

    @Synchronized
    fun getCachedAccentColor(): Int = cachedAccentColor

    @Synchronized
    fun getCachedArtwork(): Bitmap? = cachedSourceBitmap

    @Synchronized
    fun getRoundedArtwork(context: Context, source: Bitmap?, sizePx: Int, cornerRadiusDp: Float): Bitmap {
        if (sizePx == 180 && cachedRoundedArt56 != null) return cachedRoundedArt56!!
        if (sizePx == 200 && cachedRoundedArt60 != null) return cachedRoundedArt60!!
        val bmp = createRoundedArtworkBitmap(source, sizePx, cornerRadiusDp, context)
        if (sizePx == 180) cachedRoundedArt56 = bmp
        if (sizePx == 200) cachedRoundedArt60 = bmp
        return bmp
    }

    @Synchronized
    fun getBlurredBackground(context: Context, source: Bitmap?, widthPx: Int, heightPx: Int, cornerRadiusDp: Float): Bitmap {
        if (cachedBlurredBg != null) return cachedBlurredBg!!
        val bmp = createBlurredBackground(source, widthPx, heightPx, cornerRadiusDp, context)
        cachedBlurredBg = bmp
        return bmp
    }

    @Synchronized
    fun clearCache() {
        cachedSongKey = null
        cachedSourceBitmap = null
        cachedAccentColor = 0xFF10B981.toInt()
        cachedRoundedArt56 = null
        cachedRoundedArt60 = null
        cachedBlurredBg = null
    }

    fun loadSongBitmap(context: Context, song: Song?): Bitmap? {
        if (song == null) return null
        val cachedDisk = com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, song.id)
        val artUriStr = song.artworkUri ?: cachedDisk ?: song.contentUriString
        if (artUriStr.isBlank()) return null

        try {
            val uri = Uri.parse(artUriStr)
            if (artUriStr.startsWith("content://")) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val thumb = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                        if (thumb != null) return thumb
                    } catch (_: Exception) {}
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { 
                        inSampleSize = 1
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bmp = BitmapFactory.decodeStream(stream, null, options)
                    if (bmp != null) return bmp
                }
            } else if (artUriStr.startsWith("file://") || artUriStr.startsWith("/")) {
                val path = if (artUriStr.startsWith("file://")) uri.path else artUriStr
                if (path != null) {
                    val options = BitmapFactory.Options().apply { 
                        inSampleSize = 1
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bmp = BitmapFactory.decodeFile(path, options)
                    if (bmp != null) return bmp
                }
            } else if (artUriStr.startsWith("http://") || artUriStr.startsWith("https://")) {
                try {
                    val url = java.net.URL(artUriStr)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.inputStream.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        if (bmp != null) return bmp
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Fallback: Try MediaMetadataRetriever
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(song.contentUriString))
                val artBytes = retriever.embeddedPicture
                if (artBytes != null) {
                    val options = BitmapFactory.Options().apply { 
                        inSampleSize = 1
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)
                } else null
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        } catch (_: Exception) {
            null
        }
    }

    fun extractDominantAccentColor(bitmap: Bitmap?): Int {
        if (bitmap == null) return 0xFF10B981.toInt() // Default emerald green accent
        try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 16, 16, false)
            var totalR = 0
            var totalG = 0
            var totalB = 0
            var count = 0

            for (x in 0 until scaled.width) {
                for (y in 0 until scaled.height) {
                    val pixel = scaled.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)

                    val max = maxOf(r, g, b)
                    val min = minOf(r, g, b)
                    if (max > 40 && min < 235 && (max - min) > 12) {
                        totalR += r
                        totalG += g
                        totalB += b
                        count++
                    }
                }
            }
            if (count > 0) {
                return Color.rgb(totalR / count, totalG / count, totalB / count)
            }
        } catch (_: Exception) {}
        return 0xFF10B981.toInt()
    }

    fun createRoundedArtworkBitmap(source: Bitmap?, sizePx: Int, cornerRadiusDp: Float, context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val radiusPx = cornerRadiusDp * density

        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())

        if (source != null) {
            val scaled = Bitmap.createScaledBitmap(source, sizePx, sizePx, true)
            paint.shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        } else {
            // Gradient fallback
            val gradient = LinearGradient(
                0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
                Color.parseColor("#374151"), Color.parseColor("#111827"), Shader.TileMode.CLAMP
            )
            paint.shader = gradient
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

            paint.shader = null
            paint.color = Color.parseColor("#9CA3AF")
            paint.textSize = sizePx * 0.45f
            paint.textAlign = Paint.Align.CENTER
            val yPos = (sizePx / 2f) - ((paint.descent() + paint.ascent()) / 2f)
            canvas.drawText("♪", sizePx / 2f, yPos, paint)
        }
        return output
    }

    fun createBlurredBackground(source: Bitmap?, widthPx: Int, heightPx: Int, cornerRadiusDp: Float, context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val radiusPx = cornerRadiusDp * density

        val output = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())

        if (source != null) {
            // Draw scaled down and stretched artwork to simulate smooth blur
            val smallSize = 32
            val smallBmp = Bitmap.createScaledBitmap(source, smallSize, smallSize, true)
            val scaledShader = BitmapShader(
                Bitmap.createScaledBitmap(smallBmp, widthPx, heightPx, true),
                Shader.TileMode.CLAMP,
                Shader.TileMode.CLAMP
            )
            paint.shader = scaledShader
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

            // Dark vignette overlay
            paint.shader = null
            paint.color = Color.parseColor("#B30A0E17")
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        } else {
            paint.color = Color.parseColor("#E60F172A")
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        }
        return output
    }

    private var cachedDiscBaseKey: String? = null
    private var cachedDiscBaseBmp: Bitmap? = null

    @Synchronized
    fun createCircularWidgetBitmap(
        context: Context,
        sourceArtwork: Bitmap?,
        progressRatio: Float,
        accentColor: Int,
        sizePx: Int = 220
    ): Bitmap {
        val center = sizePx / 2f
        val strokeWidthPx = (sizePx * 0.038f).coerceIn(6f, 14f)
        val radius = center - (strokeWidthPx / 2f) - 2f

        val discKey = "${sourceArtwork?.hashCode()}_${sizePx}"
        var baseBmp = if (cachedDiscBaseKey == discKey && cachedDiscBaseBmp != null && !cachedDiscBaseBmp!!.isRecycled) {
            cachedDiscBaseBmp
        } else {
            val base = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val baseCanvas = Canvas(base)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                isDither = true
            }

            // 1. Dark Base Disc Background
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#12141C")
            baseCanvas.drawCircle(center, center, radius, paint)

            // 2. Artwork Disc / Texture with Radial Dark Vignette
            if (sourceArtwork != null && !sourceArtwork.isRecycled) {
                try {
                    val smallArt = Bitmap.createScaledBitmap(sourceArtwork, sizePx, sizePx, true)
                    val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        isFilterBitmap = true
                        isDither = true
                        shader = BitmapShader(smallArt, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    }
                    baseCanvas.drawCircle(center, center, radius, artPaint)

                    val vignetteGradient = RadialGradient(
                        center, center, radius,
                        intArrayOf(Color.parseColor("#590A0C14"), Color.parseColor("#F20A0C14")),
                        floatArrayOf(0.25f, 1.0f),
                        Shader.TileMode.CLAMP
                    )
                    val vigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = vignetteGradient
                    }
                    baseCanvas.drawCircle(center, center, radius, vigPaint)
                } catch (_: Exception) {}
            } else {
                val gradient = RadialGradient(
                    center, center, radius,
                    intArrayOf(Color.parseColor("#2A2E3D"), Color.parseColor("#10131A")),
                    floatArrayOf(0.2f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                paint.shader = gradient
                baseCanvas.drawCircle(center, center, radius, paint)
            }

            // Inactive background ring track on base
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidthPx
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.parseColor("#4DFFFFFF")
            val ringRect = RectF(center - radius, center - radius, center + radius, center + radius)
            baseCanvas.drawArc(ringRect, 0f, 360f, false, paint)

            cachedDiscBaseKey = discKey
            cachedDiscBaseBmp = base
            base
        }

        // Create working output bitmap and draw base
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (baseBmp != null && !baseBmp.isRecycled) {
            canvas.drawBitmap(baseBmp, 0f, 0f, null)
        }

        // Active progress arc
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = true
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            strokeCap = Paint.Cap.ROUND
            color = if (accentColor != 0) accentColor else Color.parseColor("#10B981")
        }
        val ringRect = RectF(center - radius, center - radius, center + radius, center + radius)
        val sweepAngle = (progressRatio.coerceIn(0f, 1f) * 360f)
        if (sweepAngle > 0f) {
            canvas.drawArc(ringRect, -90f, sweepAngle, false, paint)
        }

        return bitmap
    }

    private var cachedWaveformProgress: Float = -1f
    private var cachedWaveformColor: Int = -1
    private var cachedWaveformBmp: Bitmap? = null

    // Generates a rich, seekable audio waveform progress bar bitmap for widgets
    @Synchronized
    fun renderWaveformBarBitmap(
        context: Context,
        widthPx: Int = 420,
        heightPx: Int = 36,
        progressRatio: Float = 0f,
        accentColor: Int = 0xFFFF9100.toInt(),
        barCount: Int = 38
    ): Bitmap {
        val clampedProgress = progressRatio.coerceIn(0f, 1f)
        if (cachedWaveformBmp != null &&
            cachedWaveformBmp!!.width == widthPx &&
            cachedWaveformBmp!!.height == heightPx &&
            cachedWaveformColor == accentColor &&
            kotlin.math.abs(cachedWaveformProgress - clampedProgress) < 0.008f
        ) {
            return cachedWaveformBmp!!
        }

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val totalSpacing = (barCount - 1) * 2f
        val barWidth = ((widthPx - totalSpacing) / barCount).coerceAtLeast(3.5f)
        val centerY = heightPx / 2f

        val harmonicPattern = floatArrayOf(
            0.30f, 0.45f, 0.65f, 0.42f, 0.78f, 0.92f, 0.55f, 0.85f,
            0.62f, 0.98f, 0.72f, 0.48f, 0.86f, 1.00f, 0.68f, 0.42f,
            0.75f, 0.90f, 0.58f, 0.80f, 0.94f, 0.52f, 0.84f, 0.66f,
            0.92f, 0.60f, 0.42f, 0.78f, 0.96f, 0.68f, 0.46f, 0.82f,
            0.64f, 0.45f, 0.32f, 0.22f
        )

        val currentActiveWidth = widthPx * clampedProgress

        for (i in 0 until barCount) {
            val x = i * (barWidth + 2f)
            val patternHeight = harmonicPattern[i % harmonicPattern.size]
            val barHeight = ((heightPx - 4f) * patternHeight).coerceAtLeast(4f)
            val top = centerY - (barHeight / 2f)
            val bottom = centerY + (barHeight / 2f)

            val isPlayed = (x + barWidth / 2f) <= currentActiveWidth
            if (isPlayed) {
                paint.color = if (accentColor != 0) accentColor else 0xFF8B5CF6.toInt()
                paint.alpha = 245
            } else {
                paint.color = Color.WHITE
                paint.alpha = 50
            }
            val cornerRadius = barWidth / 2f
            canvas.drawRoundRect(x, top, x + barWidth, bottom, cornerRadius, cornerRadius, paint)
        }

        // Seeker cursor line at playback position
        if (currentActiveWidth > 2f && currentActiveWidth < widthPx - 2f) {
            paint.color = Color.WHITE
            paint.alpha = 255
            val thumbWidth = 5f
            val thumbHeight = heightPx * 0.92f
            val thumbTop = centerY - (thumbHeight / 2f)
            canvas.drawRoundRect(
                currentActiveWidth - (thumbWidth / 2f),
                thumbTop,
                currentActiveWidth + (thumbWidth / 2f),
                thumbTop + thumbHeight,
                2.5f,
                2.5f,
                paint
            )
        }

        cachedWaveformProgress = clampedProgress
        cachedWaveformColor = accentColor
        cachedWaveformBmp = bitmap
        return bitmap
    }
}
