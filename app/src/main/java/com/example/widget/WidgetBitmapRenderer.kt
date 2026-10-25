package com.example.widget

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import com.example.data.model.Song
import kotlin.math.sin

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

    private val buttonBitmapCache = mutableMapOf<String, Bitmap>()
    private val backgroundBitmapCache = mutableMapOf<String, Bitmap>()
    private val artworkBitmapCache = mutableMapOf<String, Bitmap>()
    private val progressBitmapCache = mutableMapOf<String, Bitmap>()

    @Synchronized
    fun getOrLoadSongBitmap(context: Context, song: Song?): Bitmap? {
        if (song == null) {
            clearCache()
            return null
        }
        val manualCover = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
        val key = "${song.id}_${song.displayTitle}_${manualCover ?: song.artworkUri ?: song.contentUriString}"
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
        progressBitmapCache.clear()
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

        // Deep charcoal-navy card surface subtly harmonized with accent
        val bgR = (r * 0.09f + 22 * 0.91f).toInt().coerceIn(0, 255)
        val bgG = (g * 0.09f + 25 * 0.91f).toInt().coerceIn(0, 255)
        val bgB = (b * 0.11f + 34 * 0.89f).toInt().coerceIn(0, 255)
        val containerBg = Color.rgb(bgR, bgG, bgB)

        val strokeR = (r * 0.32f + 42 * 0.68f).toInt().coerceIn(0, 255)
        val strokeG = (g * 0.30f + 46 * 0.70f).toInt().coerceIn(0, 255)
        val strokeB = (b * 0.35f + 58 * 0.65f).toInt().coerceIn(0, 255)
        val containerBorder = Color.rgb(strokeR, strokeG, strokeB)

        val iconColor = Color.rgb(18, 12, 20)

        return WidgetThemeColors(
            accentColor = accent,
            containerBgColor = containerBg,
            containerBorderColor = containerBorder,
            iconColor = iconColor,
            titleColor = Color.WHITE,
            subtitleColor = Color.parseColor("#9AA2B1"),
            durationColor = Color.parseColor("#8D95A5")
        )
    }

    fun extractDominantAccentColor(bitmap: Bitmap?): Int? {
        if (bitmap == null || bitmap.isRecycled) return null
        try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 24, 24, false)
            var bestColor = Color.parseColor("#FF6D3F")
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

                    if (brightness in 0.30f..0.95f && saturation > 0.25f) {
                        val vibrancy = saturation * brightness
                        if (vibrancy > maxVibrancy) {
                            maxVibrancy = vibrancy
                            bestColor = pixel
                        }
                    }

                    if (max > 35 && min < 240) {
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
        return Color.parseColor("#FF6D3F")
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

        // 3. Resolve effective per-song image URI (manual cover or cached disk cover)
        val manualCover = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
        val cachedDisk = com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, song.id, song.path)
        val artUriStr = manualCover ?: cachedDisk

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
                }
            } catch (_: Exception) {}
        }

        // 4. Synchronous extraction via ArtworkManager (strictly per-song embedded tags)
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

        // 5. Fallback: MediaMetadataRetriever embedded picture
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

        val strokeWidth = 1.2f * density
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
    fun renderFullBleedArtworkBackground(
        context: Context,
        source: Bitmap?,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusDp: Float,
        accentColor: Int
    ): Bitmap {
        val key = "full_art_bg_${source?.hashCode()}_${widthPx}_${heightPx}_${cornerRadiusDp}_${accentColor}"
        backgroundBitmapCache[key]?.let { if (!it.isRecycled) return it }

        val density = context.resources.displayMetrics.density
        val radiusPx = cornerRadiusDp * density

        val output = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())

        if (source != null && !source.isRecycled) {
            val srcW = source.width.toFloat()
            val srcH = source.height.toFloat()
            val scale = maxOf(widthPx / srcW, heightPx / srcH)
            val dx = (widthPx - srcW * scale) / 2f
            val dy = (heightPx - srcH * scale) / 2f

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
            val gradient = LinearGradient(
                0f, 0f, widthPx.toFloat(), heightPx.toFloat(),
                Color.parseColor("#2A2F42"), Color.parseColor("#141722"),
                Shader.TileMode.CLAMP
            )
            paint.shader = gradient
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        }

        // Smooth bottom dark gradient vignette for legible white typography and controls
        val vignetteShader = LinearGradient(
            0f, 0f, 0f, heightPx.toFloat(),
            intArrayOf(
                Color.argb(35, 8, 10, 16),
                Color.argb(110, 10, 12, 20),
                Color.argb(225, 10, 12, 18),
                Color.argb(245, 8, 10, 16)
            ),
            floatArrayOf(0f, 0.38f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = vignetteShader
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

        // Subtle outer border
        val strokeWidth = 1.2f * density
        val half = strokeWidth / 2f
        val borderRect = RectF(half, half, widthPx - half, heightPx - half)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = Color.argb(75, 255, 255, 255)
        canvas.drawRoundRect(borderRect, radiusPx, radiusPx, paint)

        backgroundBitmapCache[key] = output
        return output
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
            val srcW = source.width.toFloat()
            val srcH = source.height.toFloat()
            val scale = maxOf(widthPx / srcW, heightPx / srcH)
            val dx = (widthPx - srcW * scale) / 2f
            val dy = (heightPx - srcH * scale) / 2f

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
            val gradient = LinearGradient(
                0f, 0f, widthPx.toFloat(), heightPx.toFloat(),
                Color.parseColor("#2E2438"), Color.parseColor("#171A26"),
                Shader.TileMode.CLAMP
            )
            paint.shader = gradient
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

            paint.shader = null
            paint.color = Color.parseColor("#FF6D3F")
            paint.alpha = 200
            paint.textSize = minOf(widthPx, heightPx) * 0.36f
            paint.textAlign = Paint.Align.CENTER
            val yPos = (heightPx / 2f) - ((paint.descent() + paint.ascent()) / 2f)
            canvas.drawText("♪", widthPx / 2f, yPos, paint)
        }

        artworkBitmapCache[key] = output
        return output
    }

    @Synchronized
    fun renderLeftBleedArtwork(
        source: Bitmap?,
        widthPx: Int,
        heightPx: Int,
        leftCornerRadiusDp: Float,
        context: Context
    ): Bitmap {
        val key = "left_bleed_art_${source?.hashCode()}_${widthPx}_${heightPx}_${leftCornerRadiusDp}"
        artworkBitmapCache[key]?.let { if (!it.isRecycled) return it }

        val density = context.resources.displayMetrics.density
        val radiusPx = leftCornerRadiusDp * density

        val output = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Rounded on top-left and bottom-left corners, flush on right edge
        val radii = floatArrayOf(
            radiusPx, radiusPx, // top-left
            0f, 0f,             // top-right
            0f, 0f,             // bottom-right
            radiusPx, radiusPx  // bottom-left
        )
        val path = Path().apply {
            addRoundRect(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), radii, Path.Direction.CW)
        }

        if (source != null && !source.isRecycled) {
            val srcW = source.width.toFloat()
            val srcH = source.height.toFloat()
            val scale = maxOf(widthPx / srcW, heightPx / srcH)
            val dx = (widthPx - srcW * scale) / 2f
            val dy = (heightPx - srcH * scale) / 2f

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
                Color.parseColor("#2E2438"), Color.parseColor("#171A26"),
                Shader.TileMode.CLAMP
            )
            paint.shader = gradient
            canvas.drawPath(path, paint)

            paint.shader = null
            paint.color = Color.parseColor("#FF6D3F")
            paint.alpha = 200
            paint.textSize = minOf(widthPx, heightPx) * 0.36f
            paint.textAlign = Paint.Align.CENTER
            val yPos = (heightPx / 2f) - ((paint.descent() + paint.ascent()) / 2f)
            canvas.drawText("♪", widthPx / 2f, yPos, paint)
        }

        artworkBitmapCache[key] = output
        return output
    }

    @Synchronized
    fun renderStraightProgressBar(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        progressRatio: Float,
        accentColor: Int,
        trackColor: Int = Color.argb(85, 255, 255, 255)
    ): Bitmap {
        val quantizedRatio = ((progressRatio.coerceIn(0f, 1f) * 200f).toInt()) / 200f
        val key = "prog_straight_${widthPx}_${heightPx}_${quantizedRatio}_${accentColor}_${trackColor}"
        progressBitmapCache[key]?.let { if (!it.isRecycled) return it }

        if (progressBitmapCache.size > 40) {
            progressBitmapCache.clear()
        }

        val density = context.resources.displayMetrics.density
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val centerY = heightPx / 2f
        val barHeight = (3.2f * density).coerceAtMost(heightPx * 0.35f)
        val thumbRadius = (4.8f * density).coerceAtMost(heightPx * 0.44f)
        val startX = thumbRadius + 2f
        val endX = (widthPx - thumbRadius - 2f).coerceAtLeast(startX + 1f)
        val thumbX = startX + (endX - startX) * quantizedRatio

        // Inactive background track
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = barHeight
        paint.color = trackColor
        canvas.drawLine(startX, centerY, endX, centerY, paint)

        // Active accent progress fill
        paint.color = accentColor
        canvas.drawLine(startX, centerY, thumbX, centerY, paint)

        // Subtle outer glow on thumb
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(75, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawCircle(thumbX, centerY, thumbRadius * 1.35f, paint)

        // Solid circular thumb
        paint.color = accentColor
        canvas.drawCircle(thumbX, centerY, thumbRadius, paint)

        progressBitmapCache[key] = bmp
        return bmp
    }

    @Synchronized
    fun renderWaveformProgressBar(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        progressRatio: Float,
        accentColor: Int,
        trackColor: Int = Color.argb(85, 255, 255, 255)
    ): Bitmap {
        val quantizedRatio = ((progressRatio.coerceIn(0f, 1f) * 200f).toInt()) / 200f
        val key = "prog_wave_${widthPx}_${heightPx}_${quantizedRatio}_${accentColor}_${trackColor}"
        progressBitmapCache[key]?.let { if (!it.isRecycled) return it }

        if (progressBitmapCache.size > 40) {
            progressBitmapCache.clear()
        }

        val density = context.resources.displayMetrics.density
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val centerY = heightPx / 2f
        val strokeW = (2.6f * density).coerceAtLeast(2f)
        val thumbRadius = (5.2f * density).coerceAtMost(heightPx * 0.42f)
        val startX = thumbRadius + 2f
        val endX = (widthPx - thumbRadius - 2f).coerceAtLeast(startX + 1f)
        val thumbX = startX + (endX - startX) * quantizedRatio

        // Draw flat inactive line from thumbX to endX
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = strokeW
        paint.color = trackColor
        if (thumbX < endX) {
            canvas.drawLine(thumbX, centerY, endX, centerY, paint)
        }

        // Draw squiggly sine-wave path from startX to thumbX
        val amplitude = (heightPx * 0.27f).coerceAtLeast(3f)
        val wavelength = (18f * density).coerceAtLeast(12f)
        val wavePath = Path()
        var x = startX
        wavePath.moveTo(startX, centerY)
        while (x <= thumbX) {
            val phase = ((x - startX) / wavelength) * (2.0 * Math.PI)
            val y = centerY + (sin(phase) * amplitude).toFloat()
            wavePath.lineTo(x, y)
            x += 2f
        }
        val thumbPhase = ((thumbX - startX) / wavelength) * (2.0 * Math.PI)
        val thumbY = if (quantizedRatio > 0.01f) {
            centerY + (sin(thumbPhase) * amplitude).toFloat()
        } else {
            centerY
        }

        // Outer neon glow on active wave
        paint.color = Color.argb(70, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        paint.strokeWidth = strokeW * 2.4f
        canvas.drawPath(wavePath, paint)

        // Crisp active wave stroke
        paint.color = accentColor
        paint.strokeWidth = strokeW
        canvas.drawPath(wavePath, paint)

        // Glowing orb thumb at wave tip
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(95, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawCircle(thumbX, thumbY, thumbRadius * 1.45f, paint)

        paint.color = accentColor
        canvas.drawCircle(thumbX, thumbY, thumbRadius, paint)

        progressBitmapCache[key] = bmp
        return bmp
    }

    @Synchronized
    fun renderFavoriteChipButton(
        sizePx: Int,
        isFavorite: Boolean,
        accentColor: Int
    ): Bitmap {
        val key = "fav_chip_${sizePx}_${isFavorite}_${accentColor}"
        buttonBitmapCache[key]?.let { if (!it.isRecycled) return it }

        val bitmap = Bitmap.createBitmap(sizePx.coerceAtLeast(1), sizePx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val center = sizePx / 2f
        val radius = center - 1f

        // Translucent dark circular pill backdrop matching the 4-widget reference
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(125, 36, 40, 52)
        canvas.drawCircle(center, center, radius, paint)

        val heartScale = sizePx * 0.42f
        val path = Path()
        val x = center
        val y = center + (heartScale * 0.08f)
        val w = heartScale * 0.62f
        val h = heartScale * 0.52f

        path.moveTo(x, y + h * 0.62f)
        path.cubicTo(x - w * 1.25f, y - h * 0.22f, x - w * 0.72f, y - h * 1.12f, x, y - h * 0.38f)
        path.cubicTo(x + w * 0.72f, y - h * 1.12f, x + w * 1.25f, y - h * 0.22f, x, y + h * 0.62f)
        path.close()

        if (isFavorite) {
            paint.style = Paint.Style.FILL
            paint.color = accentColor
            canvas.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (sizePx * 0.065f).coerceAtLeast(1.8f)
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.WHITE
            canvas.drawPath(path, paint)
        }

        buttonBitmapCache[key] = bitmap
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
        iconColor: Int = Color.WHITE
    ): Bitmap {
        val key = "btn_${sizePx}_${buttonColor}_${iconType}_${isPlaying}_${isFavorite}_${iconColor}"
        buttonBitmapCache[key]?.let { if (!it.isRecycled) return it }

        val bitmap = Bitmap.createBitmap(sizePx.coerceAtLeast(1), sizePx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val center = sizePx / 2f

        when (iconType) {
            ButtonIconType.PLAY_PAUSE -> {
                // Soft outer glow halo around the circular Play/Pause button
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(65, Color.red(buttonColor), Color.green(buttonColor), Color.blue(buttonColor))
                canvas.drawCircle(center, center, center - 1f, paint)

                // Solid accent circle
                val innerRadius = center * 0.84f
                paint.color = buttonColor
                canvas.drawCircle(center, center, innerRadius, paint)

                // Crisp white Play/Pause glyph inside
                paint.color = Color.WHITE
                if (isPlaying) {
                    val barWidth = sizePx * 0.085f
                    val barHeight = sizePx * 0.32f
                    val gap = sizePx * 0.065f
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
                    val triSize = sizePx * 0.30f
                    val offsetX = sizePx * 0.03f
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
                // Clean white skip-next icon (▷|) matching the 4-widget reference
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (sizePx * 0.065f).coerceAtLeast(2f)
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND

                val triW = sizePx * 0.24f
                val halfH = sizePx * 0.20f
                val path = Path().apply {
                    moveTo(center - triW * 0.65f, center - halfH)
                    lineTo(center + triW * 0.35f, center)
                    lineTo(center - triW * 0.65f, center + halfH)
                    close()
                }
                canvas.drawPath(path, paint)

                val barX = center + triW * 0.58f
                canvas.drawLine(barX, center - halfH, barX, center + halfH, paint)
            }
            ButtonIconType.PREV -> {
                // Clean white skip-previous icon (|◁) matching the 4-widget reference
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (sizePx * 0.065f).coerceAtLeast(2f)
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND

                val triW = sizePx * 0.24f
                val halfH = sizePx * 0.20f
                val barX = center - triW * 0.58f
                canvas.drawLine(barX, center - halfH, barX, center + halfH, paint)

                val path = Path().apply {
                    moveTo(center + triW * 0.65f, center - halfH)
                    lineTo(center - triW * 0.35f, center)
                    lineTo(center + triW * 0.65f, center + halfH)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ButtonIconType.FAVORITE -> {
                return renderFavoriteChipButton(sizePx, isFavorite, buttonColor)
            }
        }

        buttonBitmapCache[key] = bitmap
        return bitmap
    }
}
