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
import java.util.concurrent.ConcurrentHashMap

object ArtworkColorExtractor {
    private const val PREFS_NAME = "artwork_palette_cache_v1"
    private val colorCache = object : android.util.LruCache<String, Color>(500) {}
    private val failedCache = object : android.util.LruCache<String, Boolean>(250) {}
    private val diskColorMemoryMirror = ConcurrentHashMap<String, Int>()
    @Volatile
    private var isDiskMirrorLoaded = false

    @Synchronized
    fun clearCache() {
        colorCache.evictAll()
        failedCache.evictAll()
        diskColorMemoryMirror.clear()
    }

    private fun ensureDiskMirrorLoaded(context: Context) {
        if (isDiskMirrorLoaded) return
        synchronized(this) {
            if (isDiskMirrorLoaded) return
            isDiskMirrorLoaded = true
            try {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                for ((k, v) in prefs.all) {
                    if (v is Int) {
                        diskColorMemoryMirror[k] = v
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun getCachedColor(context: Context, key: String): Color? {
        synchronized(this) {
            colorCache.get(key)?.let { return it }
        }
        ensureDiskMirrorLoaded(context)
        val argb = diskColorMemoryMirror[key] ?: return null
        val color = Color(argb)
        synchronized(this) {
            colorCache.put(key, color)
        }
        return color
    }

    private fun saveCachedColor(context: Context, key: String, color: Color) {
        val argb = color.toArgb()
        synchronized(this) {
            colorCache.put(key, color)
        }
        if (diskColorMemoryMirror.put(key, argb) != argb) {
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(key, argb)
                    .apply()
            } catch (_: Throwable) {}
        }
    }

    suspend fun extractPrimaryColorForSong(context: Context, song: Song?): Color? {
        if (song == null) return null
        val cacheKey = "song_${song.trackIdentityKey}_${song.artworkUri ?: ""}"
        getCachedColor(context, cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                // 1. Try fast in-memory bitmap from ArtworkManager or ThumbnailBitmapCache
                val cachedBmp = ArtworkManager.getCachedNotificationBitmap(song.id, song.path)
                if (cachedBmp != null && !cachedBmp.isRecycled) {
                    val color = findProminentColor(cachedBmp)
                    if (color != null) {
                        saveCachedColor(context, cacheKey, color)
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
                        saveCachedColor(context, cacheKey, uriColor)
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
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
                    if (bmp != null) {
                        val color = findProminentColor(bmp)
                        bmp.recycle()
                        if (color != null) {
                            saveCachedColor(context, cacheKey, color)
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
        }
        getCachedColor(context, artworkUriStr)?.let { return it }

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
                if (extractedColor != null) {
                    saveCachedColor(context, artworkUriStr, extractedColor)
                } else {
                    synchronized(this@ArtworkColorExtractor) {
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
                inPreferredConfig = Bitmap.Config.RGB_565
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

    /**
     * Ultra-fast prominent color extractor:
     * - Downsamples source bitmaps larger than 64x64 to a 48x48 grid (eliminates 4MB pixel array allocations).
     * - Uses inline pure-Kotlin RGB-to-HSV math inside the pixel loop (zero JNI calls per pixel).
     */
    internal fun findProminentColor(bitmap: Bitmap): Color? {
        val rawWidth = bitmap.width
        val rawHeight = bitmap.height
        if (rawWidth <= 0 || rawHeight <= 0 || bitmap.isRecycled) return null

        val sampleBmp = if (rawWidth > 64 || rawHeight > 64) {
            try {
                Bitmap.createScaledBitmap(bitmap, 48, 48, false)
            } catch (_: Throwable) {
                bitmap
            }
        } else {
            bitmap
        }

        val width = sampleBmp.width
        val height = sampleBmp.height
        val pixels = IntArray(width * height)
        try {
            sampleBmp.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            if (sampleBmp !== bitmap && !sampleBmp.isRecycled) {
                sampleBmp.recycle()
            }
        }

        // 12 Hue Buckets (30 degrees each)
        val bucketCount = IntArray(12)
        val bucketWeight = FloatArray(12)
        val bucketSumR = LongArray(12)
        val bucketSumG = LongArray(12)
        val bucketSumB = LongArray(12)

        var fallbackR = 0L
        var fallbackG = 0L
        var fallbackB = 0L
        var fallbackCount = 0

        for (i in pixels.indices) {
            val colorInt = pixels[i]
            val alpha = (colorInt ushr 24) and 0xFF
            if (alpha < 128) continue

            val r = (colorInt ushr 16) and 0xFF
            val g = (colorInt ushr 8) and 0xFF
            val b = colorInt and 0xFF

            fallbackR += r
            fallbackG += g
            fallbackB += b
            fallbackCount++

            // Inline RGB -> HSV calculation (avoids per-pixel JNI call to android.graphics.Color.colorToHSV)
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val delta = maxC - minC

            val value = maxC / 255f
            val saturation = if (maxC == 0) 0f else delta.toFloat() / maxC.toFloat()

            // Skip dull grays/whites/blacks for hue bucket classification
            if (value < 0.12f || (value > 0.95f && saturation < 0.08f) || saturation < 0.12f) continue

            var hue = when {
                delta == 0 -> 0f
                maxC == r -> 60f * (((g - b).toFloat() / delta.toFloat()) % 6f)
                maxC == g -> 60f * (((b - r).toFloat() / delta.toFloat()) + 2f)
                else -> 60f * (((r - g).toFloat() / delta.toFloat()) + 4f)
            }
            if (hue < 0f) hue += 360f

            val bucketIdx = ((hue / 30f).toInt()).coerceIn(0, 11)
            val weight = saturation * saturation * (if (value in 0.30f..0.85f) 1.5f else 0.8f)

            bucketCount[bucketIdx]++
            bucketWeight[bucketIdx] += weight
            bucketSumR[bucketIdx] += r
            bucketSumG[bucketIdx] += g
            bucketSumB[bucketIdx] += b
        }

        var winningIdx = -1
        var maxWeight = -1f

        for (idx in 0 until 12) {
            if (bucketCount[idx] > 0 && bucketWeight[idx] > maxWeight) {
                maxWeight = bucketWeight[idx]
                winningIdx = idx
            }
        }

        val avgR: Int
        val avgG: Int
        val avgB: Int

        if (winningIdx >= 0 && bucketCount[winningIdx] > 0) {
            val cnt = bucketCount[winningIdx]
            avgR = (bucketSumR[winningIdx] / cnt).toInt().coerceIn(0, 255)
            avgG = (bucketSumG[winningIdx] / cnt).toInt().coerceIn(0, 255)
            avgB = (bucketSumB[winningIdx] / cnt).toInt().coerceIn(0, 255)
        } else if (fallbackCount > 0) {
            avgR = (fallbackR / fallbackCount).toInt().coerceIn(0, 255)
            avgG = (fallbackG / fallbackCount).toInt().coerceIn(0, 255)
            avgB = (fallbackB / fallbackCount).toInt().coerceIn(0, 255)
        } else {
            return null
        }

        val hsv = FloatArray(3)
        val rawColorInt = android.graphics.Color.rgb(avgR, avgG, avgB)
        android.graphics.Color.colorToHSV(rawColorInt, hsv)

        // Convert to clean, soft pastel colors: gentle saturation (0.28f - 0.52f) and high brightness (0.80f - 0.95f)
        hsv[1] = hsv[1].coerceIn(0.28f, 0.52f)
        hsv[2] = hsv[2].coerceIn(0.80f, 0.95f)

        val adjustedColorInt = android.graphics.Color.HSVToColor(hsv)
        return Color(adjustedColorInt)
    }
}

