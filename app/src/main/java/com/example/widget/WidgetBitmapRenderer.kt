package com.example.widget

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import com.example.data.model.Song

object WidgetBitmapRenderer {

    data class WidgetThemeColors(
        val accentColor: Int,
        val containerBgColor: Int,
        val containerBorderColor: Int,
        val iconColor: Int,
        val titleColor: Int,
        val subtitleColor: Int,
        val durationColor: Int
    )

    enum class ButtonIconType {
        PREV,
        PLAY_PAUSE,
        NEXT,
        FAVORITE
    }

    private var cachedSongKey: String? = null
    private var cachedSourceBitmap: Bitmap? = null
    private var cachedColors: WidgetThemeColors? = null

    // Cache rendered bitmaps to avoid GC pressure
    private val buttonBitmapCache = mutableMapOf<String, Bitmap>()
    private val backgroundBitmapCache = mutableMapOf<String, Bitmap>()
    private val artworkBitmapCache = mutableMapOf<String, Bitmap>()

    @Synchronized
    fun getOrLoadSongBitmap(context: Context, song: Song?): Bitmap? {
        if (song == null) {
            clearCache()
            return null
        }
        val key = "${song.id}_${song.displayTitle}_${song.artworkUri ?: song.contentUriString}"
        if (key == cachedSongKey && cachedSourceBitmap != null && !cachedSourceBitmap!!.isRecycled) {
            return cachedSourceBitmap
        }

        clearCache()
        val bmp = loadSongBitmap(context, song)
        cachedSongKey = key
        cachedSourceBitmap = bmp
        cachedColors = computeThemeColors(bmp)
        return bmp
    }

    @Synchronized
    fun getThemeColors(
        songBitmap: Bitmap?,
        isDynamicTheme: Boolean = true,
        manualAccentColor: Int? = null
    ): WidgetThemeColors {
        val colors = computeThemeColors(songBitmap, isDynamicTheme, manualAccentColor)
        cachedColors = colors
        return colors
    }

    @Synchronized
    fun clearCache() {
        cachedSongKey = null
        cachedSourceBitmap = null
        cachedColors = null
        buttonBitmapCache.clear()
        backgroundBitmapCache.clear()
        artworkBitmapCache.clear()
    }

    @Synchronized
    fun invalidateSong(songId: Long) {
        clearCache()
    }

    fun computeThemeColors(
        bitmap: Bitmap?,
        isDynamicTheme: Boolean = true,
        manualAccentColor: Int? = null
    ): WidgetThemeColors {
        val accent = if (isDynamicTheme) {
            val extracted = extractDominantAccentColor(bitmap)
            extracted ?: manualAccentColor ?: Color.parseColor("#FF9100")
        } else {
            manualAccentColor ?: Color.parseColor("#FF9100")
        }
        val r = Color.red(accent)
        val g = Color.green(accent)
        val b = Color.blue(accent)

        // Dark harmonized plum/charcoal container tint matching the screenshot
        val bgR = (r * 0.12f + 14 * 0.88f).toInt().coerceIn(0, 255)
        val bgG = (g * 0.10f + 10 * 0.90f).toInt().coerceIn(0, 255)
        val bgB = (b * 0.12f + 16 * 0.88f).toInt().coerceIn(0, 255)
        val containerBg = Color.rgb(bgR, bgG, bgB)

        // Subtle dark glow border stroke
        val strokeR = (r * 0.35f + 25 * 0.65f).toInt().coerceIn(0, 255)
        val strokeG = (g * 0.30f + 18 * 0.70f).toInt().coerceIn(0, 255)
        val strokeB = (b * 0.35f + 30 * 0.65f).toInt().coerceIn(0, 255)
        val containerBorder = Color.rgb(strokeR, strokeG, strokeB)

        // Deep dark glyph color for high-contrast visibility on accent circle
        val iconColor = Color.rgb(18, 8, 14)

        return WidgetThemeColors(
            accentColor = accent,
            containerBgColor = containerBg,
            containerBorderColor = containerBorder,
            iconColor = iconColor,
            titleColor = accent,
            subtitleColor = Color.parseColor("#B8A2AC"),
            durationColor = Color.parseColor("#E8D0D8")
        )
    }

    fun extractDominantAccentColor(bitmap: Bitmap?): Int? {
        if (bitmap == null || bitmap.isRecycled) return null
        try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 24, 24, false)
            var bestColor = Color.parseColor("#FF4B72")
            var maxVibrancy = -1f

            var totalR = 0
            var totalG = 0
            var totalB = 0
            var validCount = 0

            for (x in 0 until scaled.width) {
                for (y in 0 until scaled.height) {
                    val pixel = scaled.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)

                    val max = maxOf(r, g, b)
                    val min = minOf(r, g, b)
                    val saturation = if (max > 0) (max - min).toFloat() / max else 0f
                    val brightness = max / 255f

                    // Seek saturated, lively pixels that aren't too dark or blown out
                    if (brightness in 0.25f..0.92f && saturation > 0.25f) {
                        val vibrancy = saturation * brightness
                        if (vibrancy > maxVibrancy) {
                            maxVibrancy = vibrancy
                            bestColor = pixel
                        }
                    }

                    if (max > 30 && min < 240) {
                        totalR += r
                        totalG += g
                        totalB += b
                        validCount++
                    }
                }
            }

            if (maxVibrancy > 0.15f) {
                return bestColor
            } else if (validCount > 0) {
                return Color.rgb(totalR / validCount, totalG / validCount, totalB / validCount)
            }
        } catch (_: Exception) {}
        return Color.parseColor("#FF4B72")
    }

    fun loadSongBitmap(context: Context, song: Song?): Bitmap? {
        if (song == null) return null

        // 1. Check authoritative in-memory notification bitmap
        val memBmp = com.example.util.ArtworkManager.getCachedNotificationBitmap(song.id)
        if (memBmp != null && !memBmp.isRecycled) return memBmp

        // 2. Check in-memory notification artwork bytes
        val memBytes = com.example.util.ArtworkManager.getCachedNotificationArtwork(song.id)
        if (memBytes != null && memBytes.isNotEmpty()) {
            try {
                val bmp = BitmapFactory.decodeByteArray(memBytes, 0, memBytes.size)
                if (bmp != null) {
                    com.example.util.ArtworkManager.putCachedNotificationBitmap(song.id, bmp)
                    return bmp
                }
            } catch (_: Throwable) {}
        }

        // 3. Resolve effective image URI (manual cover, cached disk, artworkUri)
        val manualCover = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
        val cachedDisk = com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, song.id, song.path)
        val artUriStr = manualCover ?: song.artworkUri ?: cachedDisk

        if (!artUriStr.isNullOrBlank()) {
            try {
                val uri = Uri.parse(artUriStr)
                if (artUriStr.startsWith("content://")) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val thumb = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                            if (thumb != null) {
                                com.example.util.ArtworkManager.putCachedNotificationBitmap(song.id, thumb)
                                return thumb
                            }
                        } catch (_: Exception) {}
                    }
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 1
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        val bmp = BitmapFactory.decodeStream(stream, null, options)
                        if (bmp != null) {
                            com.example.util.ArtworkManager.putCachedNotificationBitmap(song.id, bmp)
                            return bmp
                        }
                    }
                } else if (artUriStr.startsWith("file://") || artUriStr.startsWith("/")) {
                    val path = if (artUriStr.startsWith("file://")) uri.path else artUriStr
                    if (path != null && java.io.File(path).exists()) {
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 1
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        val bmp = BitmapFactory.decodeFile(path, options)
                        if (bmp != null) {
                            com.example.util.ArtworkManager.putCachedNotificationBitmap(song.id, bmp)
                            return bmp
                        }
                    }
                } else if (artUriStr.startsWith("http://") || artUriStr.startsWith("https://")) {
                    try {
                        val url = java.net.URL(artUriStr)
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        conn.inputStream.use { stream ->
                            val bmp = BitmapFactory.decodeStream(stream)
                            if (bmp != null) {
                                com.example.util.ArtworkManager.putCachedNotificationBitmap(song.id, bmp)
                                return bmp
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        // 4. Synchronous extraction via ArtworkManager (embedded tags in file)
        try {
            val rawBytes = com.example.util.ArtworkManager.getNotificationArtworkBytes(
                context,
                song.id,
                song.path,
                song.albumId,
                song.artworkUri
            )
            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
                if (bmp != null) {
                    com.example.util.ArtworkManager.putCachedNotificationBitmap(song.id, bmp)
                    return bmp
                }
            }
        } catch (_: Throwable) {}

        // 5. Fallback: MediaMetadataRetriever
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                var setOk = false
                if (song.contentUriString.isNotBlank()) {
                    try {
                        retriever.setDataSource(context, Uri.parse(song.contentUriString))
                        setOk = true
                    } catch (_: Exception) {}
                }
                if (!setOk && song.path.isNotBlank() && java.io.File(song.path).exists()) {
                    retriever.setDataSource(song.path)
                    setOk = true
                }
                if (setOk) {
                    val artBytes = retriever.embeddedPicture
                    if (artBytes != null) {
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 1
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        val bmp = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)
                        if (bmp != null) {
                            com.example.util.ArtworkManager.putCachedNotificationBitmap(song.id, bmp)
                            bmp
                        } else null
                    } else null
                } else null
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun renderCardBackground(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusDp: Float,
        bgColor: Int,
        borderColor: Int
    ): Bitmap {
        val key = "bg_${widthPx}_${heightPx}_${cornerRadiusDp}_${bgColor}_${borderColor}"
        backgroundBitmapCache[key]?.let { if (!it.isRecycled) return it }

        val density = context.resources.displayMetrics.density
        val radiusPx = cornerRadiusDp * density

        val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val strokeWidth = 1.5f * density
        val halfStroke = strokeWidth / 2f
        val rect = RectF(halfStroke, halfStroke, widthPx - halfStroke, heightPx - halfStroke)

        // Fill background
        paint.style = Paint.Style.FILL
        paint.color = bgColor
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

        // Draw subtle neon glow border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = borderColor
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

        backgroundBitmapCache[key] = bitmap
        return bitmap
    }

    @Synchronized
    fun renderPillBackground(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        bgColor: Int,
        borderColor: Int
    ): Bitmap {
        val key = "pill_${widthPx}_${heightPx}_${bgColor}_${borderColor}"
        backgroundBitmapCache[key]?.let { if (!it.isRecycled) return it }

        val density = context.resources.displayMetrics.density
        val radiusPx = heightPx / 2f

        val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val strokeWidth = 1.5f * density
        val halfStroke = strokeWidth / 2f
        val rect = RectF(halfStroke, halfStroke, widthPx - halfStroke, heightPx - halfStroke)

        paint.style = Paint.Style.FILL
        paint.color = bgColor
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = borderColor
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

        backgroundBitmapCache[key] = bitmap
        return bitmap
    }

    @Synchronized
    fun renderCircularButton(
        context: Context,
        sizePx: Int,
        buttonColor: Int,
        iconType: ButtonIconType,
        isPlaying: Boolean = false,
        isFavorite: Boolean = false,
        iconColor: Int = Color.rgb(18, 8, 14)
    ): Bitmap {
        val key = "btn_${sizePx}_${buttonColor}_${iconType}_${isPlaying}_${isFavorite}_${iconColor}"
        buttonBitmapCache[key]?.let { if (!it.isRecycled) return it }

        val bitmap = Bitmap.createBitmap(sizePx.coerceAtLeast(1), sizePx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val center = sizePx / 2f
        val radius = center - 1f

        // Draw solid circular accent button background
        paint.style = Paint.Style.FILL
        paint.color = buttonColor
        canvas.drawCircle(center, center, radius, paint)

        // Draw centered dark icon
        paint.color = iconColor
        paint.style = Paint.Style.FILL

        when (iconType) {
            ButtonIconType.PLAY_PAUSE -> {
                if (isPlaying) {
                    // Draw Pause Bars ||
                    val barWidth = sizePx * 0.10f
                    val barHeight = sizePx * 0.38f
                    val gap = sizePx * 0.08f
                    val corner = barWidth / 2f

                    val leftBar = RectF(
                        center - gap - barWidth,
                        center - (barHeight / 2f),
                        center - gap,
                        center + (barHeight / 2f)
                    )
                    val rightBar = RectF(
                        center + gap,
                        center - (barHeight / 2f),
                        center + gap + barWidth,
                        center + (barHeight / 2f)
                    )
                    canvas.drawRoundRect(leftBar, corner, corner, paint)
                    canvas.drawRoundRect(rightBar, corner, corner, paint)
                } else {
                    // Draw Play Triangle |>
                    val triSize = sizePx * 0.36f
                    val offsetX = sizePx * 0.04f
                    val path = Path().apply {
                        moveTo(center - (triSize * 0.42f) + offsetX, center - (triSize * 0.55f))
                        lineTo(center + (triSize * 0.58f) + offsetX, center)
                        lineTo(center - (triSize * 0.42f) + offsetX, center + (triSize * 0.55f))
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
            }
            ButtonIconType.NEXT -> {
                // Draw >>|
                val arrowWidth = sizePx * 0.16f
                val arrowHeight = sizePx * 0.34f
                val barWidth = sizePx * 0.06f
                val barHeight = sizePx * 0.34f
                val halfH = arrowHeight / 2f
                val gap = sizePx * 0.02f

                // First arrow
                val path1 = Path().apply {
                    moveTo(center - arrowWidth - gap, center - halfH)
                    lineTo(center - gap, center)
                    lineTo(center - arrowWidth - gap, center + halfH)
                    close()
                }
                canvas.drawPath(path1, paint)

                // Second arrow
                val path2 = Path().apply {
                    moveTo(center, center - halfH)
                    lineTo(center + arrowWidth, center)
                    lineTo(center, center + halfH)
                    close()
                }
                canvas.drawPath(path2, paint)

                // End vertical bar
                val barRect = RectF(
                    center + arrowWidth + gap,
                    center - (barHeight / 2f),
                    center + arrowWidth + gap + barWidth,
                    center + (barHeight / 2f)
                )
                canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, paint)
            }
            ButtonIconType.PREV -> {
                // Draw |<<
                val arrowWidth = sizePx * 0.16f
                val arrowHeight = sizePx * 0.34f
                val barWidth = sizePx * 0.06f
                val barHeight = sizePx * 0.34f
                val halfH = arrowHeight / 2f
                val gap = sizePx * 0.02f

                // Start vertical bar
                val barRect = RectF(
                    center - arrowWidth - gap - barWidth,
                    center - (barHeight / 2f),
                    center - arrowWidth - gap,
                    center + (barHeight / 2f)
                )
                canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, paint)

                // First arrow
                val path1 = Path().apply {
                    moveTo(center, center - halfH)
                    lineTo(center - arrowWidth, center)
                    lineTo(center, center + halfH)
                    close()
                }
                canvas.drawPath(path1, paint)

                // Second arrow
                val path2 = Path().apply {
                    moveTo(center + arrowWidth + gap, center - halfH)
                    lineTo(center + gap, center)
                    lineTo(center + arrowWidth + gap, center + halfH)
                    close()
                }
                canvas.drawPath(path2, paint)
            }
            ButtonIconType.FAVORITE -> {
                if (isFavorite) {
                    // Draw Filled Heart ♥
                    val heartScale = sizePx * 0.38f
                    val path = Path()
                    val x = center
                    val y = center + (heartScale * 0.1f)
                    val w = heartScale * 0.6f
                    val h = heartScale * 0.5f

                    path.moveTo(x, y + h * 0.6f)
                    path.cubicTo(x - w * 1.2f, y - h * 0.2f, x - w * 0.7f, y - h * 1.1f, x, y - h * 0.4f)
                    path.cubicTo(x + w * 0.7f, y - h * 1.1f, x + w * 1.2f, y - h * 0.2f, x, y + h * 0.6f)
                    path.close()
                    canvas.drawPath(path, paint)
                } else {
                    // Draw Music Note ♫
                    paint.textSize = sizePx * 0.44f
                    paint.textAlign = Paint.Align.CENTER
                    val yPos = center - ((paint.descent() + paint.ascent()) / 2f)
                    canvas.drawText("♫", center, yPos, paint)
                }
            }
        }

        buttonBitmapCache[key] = bitmap
        return bitmap
    }

    @Synchronized
    fun renderRoundedArtwork(
        source: Bitmap?,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusDp: Float,
        context: Context
    ): Bitmap {
        val key = "art_${source?.hashCode()}_${widthPx}_${heightPx}_${cornerRadiusDp}"
        artworkBitmapCache[key]?.let { if (!it.isRecycled) return it }

        val density = context.resources.displayMetrics.density
        val radiusPx = cornerRadiusDp * density

        val output = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())

        if (source != null && !source.isRecycled) {
            // Scale center-crop to fill width x height
            val srcW = source.width.toFloat()
            val srcH = source.height.toFloat()
            val scale = maxOf(widthPx / srcW, heightPx / srcH)
            val scaledW = srcW * scale
            val scaledH = srcH * scale
            val dx = (widthPx - scaledW) / 2f
            val dy = (heightPx - scaledH) / 2f

            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(matrix)
            }
            paint.shader = shader
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        } else {
            // Elegant gradient placeholder
            val gradient = LinearGradient(
                0f, 0f, widthPx.toFloat(), heightPx.toFloat(),
                Color.parseColor("#2E101D"), Color.parseColor("#15080E"),
                Shader.TileMode.CLAMP
            )
            paint.shader = gradient
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

            paint.shader = null
            paint.color = Color.parseColor("#FF4B72")
            paint.alpha = 180
            paint.textSize = minOf(widthPx, heightPx) * 0.35f
            paint.textAlign = Paint.Align.CENTER
            val yPos = (heightPx / 2f) - ((paint.descent() + paint.ascent()) / 2f)
            canvas.drawText("♪", widthPx / 2f, yPos, paint)
        }

        artworkBitmapCache[key] = output
        return output
    }

    @Synchronized
    fun renderPillArtwork(
        source: Bitmap?,
        widthPx: Int,
        heightPx: Int,
        rightRadiusDp: Float,
        context: Context
    ): Bitmap {
        val key = "pill_art_${source?.hashCode()}_${widthPx}_${heightPx}_${rightRadiusDp}"
        artworkBitmapCache[key]?.let { if (!it.isRecycled) return it }

        val density = context.resources.displayMetrics.density
        val radiusPx = rightRadiusDp * density

        val output = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Path with rounded right corners only
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(widthPx - radiusPx, 0f)
            quadTo(widthPx.toFloat(), 0f, widthPx.toFloat(), radiusPx)
            lineTo(widthPx.toFloat(), heightPx - radiusPx)
            quadTo(widthPx.toFloat(), heightPx.toFloat(), widthPx - radiusPx, heightPx.toFloat())
            lineTo(0f, heightPx.toFloat())
            close()
        }

        if (source != null && !source.isRecycled) {
            val srcW = source.width.toFloat()
            val srcH = source.height.toFloat()
            val scale = maxOf(widthPx / srcW, heightPx / srcH)
            val scaledW = srcW * scale
            val scaledH = srcH * scale
            val dx = (widthPx - scaledW) / 2f
            val dy = (heightPx - scaledH) / 2f

            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(matrix)
            }
            paint.shader = shader
            canvas.drawPath(path, paint)
        } else {
            val gradient = LinearGradient(
                0f, 0f, widthPx.toFloat(), heightPx.toFloat(),
                Color.parseColor("#2E101D"), Color.parseColor("#15080E"),
                Shader.TileMode.CLAMP
            )
            paint.shader = gradient
            canvas.drawPath(path, paint)

            paint.shader = null
            paint.color = Color.parseColor("#FF4B72")
            paint.alpha = 180
            paint.textSize = heightPx * 0.4f
            paint.textAlign = Paint.Align.CENTER
            val yPos = (heightPx / 2f) - ((paint.descent() + paint.ascent()) / 2f)
            canvas.drawText("♪", widthPx / 2f, yPos, paint)
        }

        artworkBitmapCache[key] = output
        return output
    }
}
