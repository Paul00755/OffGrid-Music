package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import com.example.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ArtworkColorExtractor {
    private const val PREFS_NAME = "artwork_palette_cache_v3"
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
        val thumbImgBmp = com.example.ui.components.ArtworkResolutionCache.getThumbnailBitmap(song.id, song.path)
        val manualCover = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
        val cachedMemUri = com.example.ui.components.ArtworkResolutionCache.getMemory(song.id, song.path)
        val cachedDisk = com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, song.id, song.path)
        val resolvedArtKey = manualCover ?: cachedMemUri ?: cachedDisk ?: song.artworkUri ?: ""
        val cacheKey = "song_v3_${song.trackIdentityKey}_${thumbImgBmp != null}_$resolvedArtKey"
        getCachedColor(context, cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                // 1. Try pre-decoded in-memory thumbnail bitmap from ArtworkResolutionCache (exact match with AlbumArtView)
                if (thumbImgBmp != null) {
                    val androidBmp: Bitmap? = try {
                        thumbImgBmp.asAndroidBitmap()
                    } catch (_: Throwable) {
                        null
                    }
                    if (androidBmp != null && !androidBmp.isRecycled) {
                        val color = findProminentColor(androidBmp)
                        if (color != null) {
                            saveCachedColor(context, cacheKey, color)
                            return@withContext color
                        }
                    }
                }

                // 2. Try fast in-memory bitmap from ArtworkManager
                val cachedBmp = ArtworkManager.getCachedNotificationBitmap(song.id, song.path)
                if (cachedBmp != null && !cachedBmp.isRecycled) {
                    val color = findProminentColor(cachedBmp)
                    if (color != null) {
                        saveCachedColor(context, cacheKey, color)
                        return@withContext color
                    }
                }

                // 3. Try per-song manual cover and cached disk cover BEFORE shared MediaStore albumart URI
                val candidateUris = listOfNotNull(manualCover, cachedMemUri, cachedDisk, song.artworkUri).distinct()
                for (uriCandidate in candidateUris) {
                    if (uriCandidate.isNotBlank()) {
                        val uriColor = extractPrimaryColor(context, uriCandidate)
                        if (uriColor != null) {
                            saveCachedColor(context, cacheKey, uriColor)
                            return@withContext uriColor
                        }
                    }
                }

                // 4. Fallback to extracting bytes via ArtworkManager
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
        val uriCacheKey = "uri_v3_$artworkUriStr"
        synchronized(this) {
            if (failedCache.get(uriCacheKey) == true) return null
        }
        getCachedColor(context, uriCacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val bitmap = loadThumbnailBitmap(context, artworkUriStr)
                if (bitmap == null) {
                    synchronized(this@ArtworkColorExtractor) {
                        failedCache.put(uriCacheKey, true)
                    }
                    return@withContext null
                }
                val extractedColor = findProminentColor(bitmap)
                bitmap.recycle()
                if (extractedColor != null) {
                    saveCachedColor(context, uriCacheKey, extractedColor)
                } else {
                    synchronized(this@ArtworkColorExtractor) {
                        failedCache.put(uriCacheKey, true)
                    }
                }
                extractedColor
            } catch (e: Exception) {
                synchronized(this@ArtworkColorExtractor) {
                    failedCache.put(uriCacheKey, true)
                }
                null
            }
        }
    }

    private fun loadThumbnailBitmap(context: Context, uriStr: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            val options = BitmapFactory.Options().apply {
                inSampleSize = 4
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            if (uriStr.startsWith("content://")) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        val thumb = context.contentResolver.loadThumbnail(uri, android.util.Size(128, 128), null)
                        if (thumb != null) return thumb
                    } catch (_: Throwable) {}
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } else if (uriStr.startsWith("file://")) {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath, options)
                    } else null
                } else {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }
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
     * Perceptually accurate prominent color extractor:
     * - Downsamples source bitmaps larger than 64x64 to a 48x48 grid.
     * - Uses 24 Hue Buckets (15 degrees each) with circular neighbor smoothing across the 0°/360° wrap-around
     *   so Pink / Rose / Magenta hues (330°..360° and 0°..20°) are never split in half.
     * - Excludes near-black shadow pixels (which have artificially inflated HSV saturation) from outvoting
     *   pale/pastel background & subject colors (like pale pink, lavender, mint, or peach).
     */
    internal fun findProminentColor(bitmap: Bitmap): Color? {
        val rawWidth = bitmap.width
        val rawHeight = bitmap.height
        if (rawWidth <= 0 || rawHeight <= 0 || bitmap.isRecycled) return null

        val sampleBmp = if (rawWidth > 64 || rawHeight > 64) {
            try {
                Bitmap.createScaledBitmap(bitmap, 48, 48, true)
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

        val numBuckets = 24 // 15 degrees per bucket
        val bucketCount = IntArray(numBuckets)
        val bucketWeight = FloatArray(numBuckets)
        val bucketSumR = DoubleArray(numBuckets)
        val bucketSumG = DoubleArray(numBuckets)
        val bucketSumB = DoubleArray(numBuckets)

        var fallbackR = 0L
        var fallbackG = 0L
        var fallbackB = 0L
        var fallbackCount = 0

        val bottomWatermarkCutoffY = (height * 0.84f).toInt()

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

            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val delta = maxC - minC

            val value = maxC / 255f
            val saturation = if (maxC == 0) 0f else delta.toFloat() / maxC.toFloat()

            // Exclude dark shadow pixels (value < 0.24f) where small RGB differences create huge fake HSV saturation,
            // and exclude neutral grays/whites while keeping pale/pastel colors (saturation >= 0.06f when value >= 0.45f).
            if (value < 0.24f) continue
            val minSatThreshold = if (value >= 0.45f) 0.06f else 0.14f
            if (saturation < minSatThreshold) continue
            if (value > 0.97f && saturation < 0.08f) continue

            var hue = when {
                delta == 0 -> 0f
                maxC == r -> 60f * (((g - b).toFloat() / delta.toFloat()) % 6f)
                maxC == g -> 60f * (((b - r).toFloat() / delta.toFloat()) + 2f)
                else -> 60f * (((r - g).toFloat() / delta.toFloat()) + 4f)
            }
            if (hue < 0f) hue += 360f

            val y = i / width
            val spatialWeight = if (y < bottomWatermarkCutoffY) 1.25f else 0.55f
            val brightnessWeight = when {
                value in 0.45f..0.95f -> 1.65f
                value > 0.95f -> 1.05f
                else -> 0.55f
            }
            // Linear saturation factor so dominant pale/pastel regions (like pale pink covers) are not crushed by saturation^2
            val satWeight = 0.38f + saturation * 0.62f
            val weight = satWeight * brightnessWeight * spatialWeight

            val bucketIdx = ((hue / 15f).toInt()) % numBuckets
            bucketCount[bucketIdx]++
            bucketWeight[bucketIdx] += weight
            bucketSumR[bucketIdx] += r * weight.toDouble()
            bucketSumG[bucketIdx] += g * weight.toDouble()
            bucketSumB[bucketIdx] += b * weight.toDouble()
        }

        // Circular neighbor smoothing across 0°/360° so Pink/Rose/Red hues spanning 345°..15° stay unified
        var winningIdx = -1
        var maxSmoothedWeight = -1f
        for (idx in 0 until numBuckets) {
            if (bucketCount[idx] == 0) continue
            val prevIdx = (idx - 1 + numBuckets) % numBuckets
            val nextIdx = (idx + 1) % numBuckets
            val smoothed = bucketWeight[idx] + (bucketWeight[prevIdx] * 0.55f) + (bucketWeight[nextIdx] * 0.55f)
            if (smoothed > maxSmoothedWeight) {
                maxSmoothedWeight = smoothed
                winningIdx = idx
            }
        }

        val avgR: Int
        val avgG: Int
        val avgB: Int

        if (winningIdx >= 0 && bucketWeight[winningIdx] > 0f) {
            val prevIdx = (winningIdx - 1 + numBuckets) % numBuckets
            val nextIdx = (winningIdx + 1) % numBuckets
            val totalW = bucketWeight[winningIdx].toDouble() +
                (bucketWeight[prevIdx].toDouble() * 0.5) +
                (bucketWeight[nextIdx].toDouble() * 0.5)
            val sumR = bucketSumR[winningIdx] + (bucketSumR[prevIdx] * 0.5) + (bucketSumR[nextIdx] * 0.5)
            val sumG = bucketSumG[winningIdx] + (bucketSumG[prevIdx] * 0.5) + (bucketSumG[nextIdx] * 0.5)
            val sumB = bucketSumB[winningIdx] + (bucketSumB[prevIdx] * 0.5) + (bucketSumB[nextIdx] * 0.5)
            avgR = (sumR / totalW).toInt().coerceIn(0, 255)
            avgG = (sumG / totalW).toInt().coerceIn(0, 255)
            avgB = (sumB / totalW).toInt().coerceIn(0, 255)
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

        // Preserve the extracted hue while ensuring rich, clearly visible theme saturation and brightness
        hsv[1] = hsv[1].coerceIn(0.34f, 0.68f)
        hsv[2] = hsv[2].coerceIn(0.82f, 0.95f)

        val adjustedColorInt = android.graphics.Color.HSVToColor(hsv)
        return Color(adjustedColorInt)
    }
}


