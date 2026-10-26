package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ArtworkColorExtractor {
    private val colorCache = object : android.util.LruCache<String, Color>(150) {}
    private val failedCache = object : android.util.LruCache<String, Boolean>(150) {}

    @Synchronized
    fun clearCache() {
        colorCache.evictAll()
        failedCache.evictAll()
    }

    suspend fun extractPrimaryColorForSong(context: Context, song: Song?): Color? {
        if (song == null) return null
        val cacheKey = "song_${com.example.ui.components.ArtworkResolutionCache.trackIdentity(song.id, song.path)}_${song.artworkUri ?: ""}"
        synchronized(this) {
            colorCache.get(cacheKey)?.let { return it }
        }

        return withContext(Dispatchers.IO) {
            try {
                // 1. Try fast in-memory bitmap from ArtworkManager
                val cachedBmp = ArtworkManager.getCachedNotificationBitmap(song.id, song.path)
                if (cachedBmp != null && !cachedBmp.isRecycled) {
                    val color = findProminentColor(cachedBmp)
                    if (color != null) {
                        synchronized(this@ArtworkColorExtractor) {
                            colorCache.put(cacheKey, color)
                        }
                        return@withContext color
                    }
                }

                // 2. Try URI extraction if artworkUri is present
                val manualCover = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
                val cachedDisk = com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, song.id, song.path)
                val effectiveUri = manualCover ?: song.artworkUri ?: cachedDisk

                if (!effectiveUri.isNullOrBlank()) {
                    val uriColor = extractPrimaryColor(context, effectiveUri)
                    if (uriColor != null) {
                        synchronized(this@ArtworkColorExtractor) {
                            colorCache.put(cacheKey, uriColor)
                        }
                        return@withContext uriColor
                    }
                }

                // 3. Fallback to extracting bytes via ArtworkManager
                val rawBytes = ArtworkManager.getNotificationArtworkBytes(
                    context,
                    song.id,
                    song.path,
                    song.albumId,
                    song.artworkUri
                )
                if (rawBytes != null && rawBytes.isNotEmpty()) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
                    if (bmp != null) {
                        val color = findProminentColor(bmp)
                        bmp.recycle()
                        if (color != null) {
                            synchronized(this@ArtworkColorExtractor) {
                                colorCache.put(cacheKey, color)
                            }
                            return@withContext color
                        }
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun extractPrimaryColor(context: Context, artworkUriStr: String?): Color? {
        if (artworkUriStr.isNullOrBlank()) return null
        synchronized(this) {
            if (failedCache.get(artworkUriStr) == true) return null
            colorCache.get(artworkUriStr)?.let { return it }
        }

        return withContext(Dispatchers.IO) {
            try {
                val bitmap = loadThumbnailBitmap(context, artworkUriStr)
                if (bitmap == null) {
                    synchronized(this@ArtworkColorExtractor) {
                        failedCache.put(artworkUriStr, true)
                    }
                    return@withContext null
                }
                val extractedColor = findProminentColor(bitmap)
                bitmap.recycle()
                synchronized(this@ArtworkColorExtractor) {
                    if (extractedColor != null) {
                        colorCache.put(artworkUriStr, extractedColor)
                    } else {
                        failedCache.put(artworkUriStr, true)
                    }
                }
                extractedColor
            } catch (e: Exception) {
                synchronized(this@ArtworkColorExtractor) {
                    failedCache.put(artworkUriStr, true)
                }
                null
            }
        }
    }

    private fun loadThumbnailBitmap(context: Context, uriStr: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            val options = BitmapFactory.Options().apply {
                inSampleSize = 8
            }
            if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } else {
                val file = File(uriStr)
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.absolutePath, options)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun findProminentColor(bitmap: Bitmap): Color? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val hsv = FloatArray(3)

        // 12 Hue Buckets (30 degrees each)
        class HueBucket {
            var count = 0
            var totalWeight = 0f
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
        }

        val buckets = Array(12) { HueBucket() }
        var fallbackR = 0L
        var fallbackG = 0L
        var fallbackB = 0L
        var fallbackCount = 0

        for (i in pixels.indices step 2) {
            val colorInt = pixels[i]
            val alpha = (colorInt shr 24) and 0xFF
            if (alpha < 128) continue

            val r = (colorInt shr 16) and 0xFF
            val g = (colorInt shr 8) and 0xFF
            val b = colorInt and 0xFF

            fallbackR += r
            fallbackG += g
            fallbackB += b
            fallbackCount++

            android.graphics.Color.colorToHSV(colorInt, hsv)
            val hue = hsv[0] // 0.0 .. 360.0
            val saturation = hsv[1] // 0.0 .. 1.0
            val value = hsv[2] // 0.0 .. 1.0

            // Skip dull grays/whites/blacks for hue bucket classification
            if (value < 0.12f || (value > 0.95f && saturation < 0.08f) || saturation < 0.12f) continue

            val bucketIdx = ((hue / 30f).toInt()).coerceIn(0, 11)
            // Weight bucket by saturation and medium-high brightness
            val weight = saturation * saturation * (if (value in 0.30f..0.85f) 1.5f else 0.8f)

            val bucket = buckets[bucketIdx]
            bucket.count++
            bucket.totalWeight += weight
            bucket.sumR += r
            bucket.sumG += g
            bucket.sumB += b
        }

        var winningBucket: HueBucket? = null
        var maxWeight = -1f

        for (b in buckets) {
            if (b.count > 0 && b.totalWeight > maxWeight) {
                maxWeight = b.totalWeight
                winningBucket = b
            }
        }

        val avgR: Int
        val avgG: Int
        val avgB: Int

        if (winningBucket != null && winningBucket.count > 0) {
            avgR = (winningBucket.sumR / winningBucket.count).toInt().coerceIn(0, 255)
            avgG = (winningBucket.sumG / winningBucket.count).toInt().coerceIn(0, 255)
            avgB = (winningBucket.sumB / winningBucket.count).toInt().coerceIn(0, 255)
        } else if (fallbackCount > 0) {
            avgR = (fallbackR / fallbackCount).toInt().coerceIn(0, 255)
            avgG = (fallbackG / fallbackCount).toInt().coerceIn(0, 255)
            avgB = (fallbackB / fallbackCount).toInt().coerceIn(0, 255)
        } else {
            return null
        }

        val rawColorInt = android.graphics.Color.rgb(avgR, avgG, avgB)
        android.graphics.Color.colorToHSV(rawColorInt, hsv)

        // Convert to clean, soft pastel colors: gentle saturation (0.28f - 0.52f) and high brightness (0.80f - 0.95f)
        // Eliminates murky/dark colors during dynamic theming and ensures clean UI with high text legibility
        hsv[1] = hsv[1].coerceIn(0.28f, 0.52f)
        hsv[2] = hsv[2].coerceIn(0.80f, 0.95f)

        val adjustedColorInt = android.graphics.Color.HSVToColor(hsv)
        return Color(adjustedColorInt)
    }
}
