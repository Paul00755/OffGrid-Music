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
    private const val PREFS_NAME = "artwork_palette_cache_v5"
    private val colorCache = object : android.util.LruCache<String, Color>(500) {}
    private val dualGradientCache = object : android.util.LruCache<String, Pair<Color, Color>>(250) {}
    private val bottomStageColorCache = object : android.util.LruCache<String, Color>(250) {}
    private val failedCache = object : android.util.LruCache<String, Boolean>(250) {}
    private val diskColorMemoryMirror = ConcurrentHashMap<String, Int>()
    @Volatile
    private var isDiskMirrorLoaded = false

    @Synchronized
    fun clearCache() {
        colorCache.evictAll()
        dualGradientCache.evictAll()
        bottomStageColorCache.evictAll()
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
        val cacheKey = "song_v5_${song.trackIdentityKey}_${thumbImgBmp != null}_$resolvedArtKey"
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

    fun getCachedDualGradientForSongSync(context: Context, song: Song?): Pair<Color, Color>? {
        if (song == null) return null
        val thumbImgBmp = com.example.ui.components.ArtworkResolutionCache.getThumbnailBitmap(song.id, song.path)
        val manualCover = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
        val cachedMemUri = com.example.ui.components.ArtworkResolutionCache.getMemory(song.id, song.path)
        val resolvedArtKey = manualCover ?: cachedMemUri ?: song.artworkUri ?: ""
        val cacheKey = "dual_v5_${song.trackIdentityKey}_${thumbImgBmp != null}_$resolvedArtKey"
        synchronized(this) {
            dualGradientCache.get(cacheKey)?.let { return it }
        }
        ensureDiskMirrorLoaded(context)
        val topArgb = diskColorMemoryMirror["${cacheKey}_top"]
        val botArgb = diskColorMemoryMirror["${cacheKey}_bot"]
        if (topArgb != null && botArgb != null) {
            val pair = Color(topArgb) to Color(botArgb)
            synchronized(this) {
                dualGradientCache.put(cacheKey, pair)
            }
            return pair
        }
        return null
    }

    private fun saveCachedDualGradient(context: Context, cacheKey: String, pair: Pair<Color, Color>) {
        val topArgb = pair.first.toArgb()
        val botArgb = pair.second.toArgb()
        synchronized(this) {
            dualGradientCache.put(cacheKey, pair)
        }
        val prevTop = diskColorMemoryMirror.put("${cacheKey}_top", topArgb)
        val prevBot = diskColorMemoryMirror.put("${cacheKey}_bot", botArgb)
        if (prevTop != topArgb || prevBot != botArgb) {
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt("${cacheKey}_top", topArgb)
                    .putInt("${cacheKey}_bot", botArgb)
                    .apply()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Extracts a lush top-to-bottom dual-tone ambient gradient pair from a song's album artwork.
     * Samples the upper region for the top gradient color and lower region / complementary accent
     * for the bottom color, guaranteeing visible richness even on dark or monochrome covers.
     */
    suspend fun extractDualGradientColorsForSong(context: Context, song: Song?): Pair<Color, Color>? {
        if (song == null) return null
        val thumbImgBmp = com.example.ui.components.ArtworkResolutionCache.getThumbnailBitmap(song.id, song.path)
        val manualCover = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
        val cachedMemUri = com.example.ui.components.ArtworkResolutionCache.getMemory(song.id, song.path)
        val cachedDisk = com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, song.id, song.path)
        val resolvedArtKey = manualCover ?: cachedMemUri ?: cachedDisk ?: song.artworkUri ?: ""
        val cacheKey = "dual_v5_${song.trackIdentityKey}_${thumbImgBmp != null}_$resolvedArtKey"
        synchronized(this) {
            dualGradientCache.get(cacheKey)?.let { return it }
        }
        ensureDiskMirrorLoaded(context)
        val diskTop = diskColorMemoryMirror["${cacheKey}_top"]
        val diskBot = diskColorMemoryMirror["${cacheKey}_bot"]
        if (diskTop != null && diskBot != null) {
            val pair = Color(diskTop) to Color(diskBot)
            synchronized(this) { dualGradientCache.put(cacheKey, pair) }
            return pair
        }

        return withContext(Dispatchers.IO) {
            try {
                if (thumbImgBmp != null) {
                    val androidBmp: Bitmap? = try { thumbImgBmp.asAndroidBitmap() } catch (_: Throwable) { null }
                    if (androidBmp != null && !androidBmp.isRecycled) {
                        val pair = findDualGradientColors(androidBmp)
                        if (pair != null) {
                            saveCachedDualGradient(context, cacheKey, pair)
                            return@withContext pair
                        }
                    }
                }
                val cachedBmp = ArtworkManager.getCachedNotificationBitmap(song.id, song.path)
                if (cachedBmp != null && !cachedBmp.isRecycled) {
                    val pair = findDualGradientColors(cachedBmp)
                    if (pair != null) {
                        saveCachedDualGradient(context, cacheKey, pair)
                        return@withContext pair
                    }
                }
                val candidateUris = listOfNotNull(manualCover, cachedMemUri, cachedDisk, song.artworkUri).distinct()
                for (uriCandidate in candidateUris) {
                    if (uriCandidate.isNotBlank()) {
                        val bmp = loadThumbnailBitmap(context, uriCandidate)
                        if (bmp != null) {
                            val pair = findDualGradientColors(bmp)
                            bmp.recycle()
                            if (pair != null) {
                                saveCachedDualGradient(context, cacheKey, pair)
                                return@withContext pair
                            }
                        }
                    }
                }
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
                        val pair = findDualGradientColors(bmp)
                        bmp.recycle()
                        if (pair != null) {
                            saveCachedDualGradient(context, cacheKey, pair)
                            return@withContext pair
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Synchronous 0ms cache lookup for the full-bleed bottom-edge atmospheric stage color.
     */
    fun getCachedBottomStageColorSync(context: Context, song: Song?): Color? {
        if (song == null) return null
        val thumbImgBmp = com.example.ui.components.ArtworkResolutionCache.getThumbnailBitmap(song.id, song.path)
        val manualCover = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
        val cachedMemUri = com.example.ui.components.ArtworkResolutionCache.getMemory(song.id, song.path)
        val resolvedArtKey = manualCover ?: cachedMemUri ?: song.artworkUri ?: ""
        val cacheKey = "bstage_v1_${song.trackIdentityKey}_${thumbImgBmp != null}_$resolvedArtKey"
        synchronized(this) {
            bottomStageColorCache.get(cacheKey)?.let { return it }
        }
        ensureDiskMirrorLoaded(context)
        diskColorMemoryMirror[cacheKey]?.let { argb ->
            val color = Color(argb)
            synchronized(this) { bottomStageColorCache.put(cacheKey, color) }
            return color
        }
        return null
    }

    private fun saveCachedBottomStageColor(context: Context, cacheKey: String, color: Color) {
        val argb = color.toArgb()
        synchronized(this) {
            bottomStageColorCache.put(cacheKey, color)
        }
        val prev = diskColorMemoryMirror.put(cacheKey, argb)
        if (prev != argb) {
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(cacheKey, argb)
                    .apply()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Extracts the authentic atmospheric stage color from the lower third of a song's album cover
     * so full-bleed artwork dissolves seamlessly into the lower controls canvas without muddy
     * hue shifts or harsh cutoffs.
     */
    suspend fun extractBottomStageColorForSong(context: Context, song: Song?): Color? {
        if (song == null) return null
        val thumbImgBmp = com.example.ui.components.ArtworkResolutionCache.getThumbnailBitmap(song.id, song.path)
        val manualCover = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
        val cachedMemUri = com.example.ui.components.ArtworkResolutionCache.getMemory(song.id, song.path)
        val cachedDisk = com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, song.id, song.path)
        val resolvedArtKey = manualCover ?: cachedMemUri ?: cachedDisk ?: song.artworkUri ?: ""
        val cacheKey = "bstage_v1_${song.trackIdentityKey}_${thumbImgBmp != null}_$resolvedArtKey"
        synchronized(this) {
            bottomStageColorCache.get(cacheKey)?.let { return it }
        }
        ensureDiskMirrorLoaded(context)
        diskColorMemoryMirror[cacheKey]?.let { argb ->
            val color = Color(argb)
            synchronized(this) { bottomStageColorCache.put(cacheKey, color) }
            return color
        }

        return withContext(Dispatchers.IO) {
            try {
                if (thumbImgBmp != null) {
                    val androidBmp: Bitmap? = try { thumbImgBmp.asAndroidBitmap() } catch (_: Throwable) { null }
                    if (androidBmp != null && !androidBmp.isRecycled) {
                        val color = findBottomStageColor(androidBmp)
                        if (color != null) {
                            saveCachedBottomStageColor(context, cacheKey, color)
                            return@withContext color
                        }
                    }
                }
                val cachedBmp = ArtworkManager.getCachedNotificationBitmap(song.id, song.path)
                if (cachedBmp != null && !cachedBmp.isRecycled) {
                    val color = findBottomStageColor(cachedBmp)
                    if (color != null) {
                        saveCachedBottomStageColor(context, cacheKey, color)
                        return@withContext color
                    }
                }
                val candidateUris = listOfNotNull(manualCover, cachedMemUri, cachedDisk, song.artworkUri).distinct()
                for (uriCandidate in candidateUris) {
                    if (uriCandidate.isNotBlank()) {
                        val bmp = loadThumbnailBitmap(context, uriCandidate)
                        if (bmp != null) {
                            val color = findBottomStageColor(bmp)
                            bmp.recycle()
                            if (color != null) {
                                saveCachedBottomStageColor(context, cacheKey, color)
                                return@withContext color
                            }
                        }
                    }
                }
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
                        val color = findBottomStageColor(bmp)
                        bmp.recycle()
                        if (color != null) {
                            saveCachedBottomStageColor(context, cacheKey, color)
                            return@withContext color
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun extractPrimaryColor(context: Context, artworkUriStr: String?): Color? {
        if (artworkUriStr.isNullOrBlank()) return null
        val uriCacheKey = "uri_v5_$artworkUriStr"
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

        return findProminentColorInSlice(pixels, width, height, 0, height)
    }

    private fun findProminentColorInSlice(
        pixels: IntArray,
        width: Int,
        totalHeight: Int,
        startRow: Int,
        endRow: Int
    ): Color? {
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

        val bottomWatermarkCutoffY = (totalHeight * 0.84f).toInt()
        val safeStart = startRow.coerceIn(0, totalHeight)
        val safeEnd = endRow.coerceIn(safeStart, totalHeight)

        for (y in safeStart until safeEnd) {
            val rowOffset = y * width
            val spatialWeight = if (y < bottomWatermarkCutoffY) 1.25f else 0.55f
            for (x in 0 until width) {
                val colorInt = pixels[rowOffset + x]
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

                if (value < 0.22f) continue
                // Keep bright pastel blues, pinks, cyans, and lavenders even when gently saturated (e.g. sky backgrounds)
                val minSatThreshold = when {
                    value >= 0.58f -> 0.055f
                    value >= 0.40f -> 0.085f
                    else -> 0.14f
                }
                if (saturation < minSatThreshold) continue
                if (value > 0.985f && saturation < 0.055f) continue

                var hue = when {
                    delta == 0 -> 0f
                    maxC == r -> 60f * (((g - b).toFloat() / delta.toFloat()) % 6f)
                    maxC == g -> 60f * (((b - r).toFloat() / delta.toFloat()) + 2f)
                    else -> 60f * (((r - g).toFloat() / delta.toFloat()) + 4f)
                }
                if (hue < 0f) hue += 360f

                // Coverage-first perceptual weighting:
                // Dominant area (e.g. bright blue sky / teal sea / pastel pink backdrop) must beat small
                // high-saturation rust-orange shirts or human skin-tone patches.
                val brightnessWeight = when {
                    value in 0.45f..0.96f -> 1.65f
                    value > 0.96f -> 1.25f
                    else -> 0.60f
                }
                val satWeight = 0.72f + saturation.coerceAtMost(0.75f) * 0.28f

                // Gently dampen muddy brown / human skin-tone / rust-orange regions (8°..42°, mid-low value)
                // so portraits on blue, green, or violet artwork keep the scene's true dominant hue.
                // Covers that are genuinely warm/orange/gold overall still win cleanly by area.
                val isMuddyBrownOrSkinTone = hue in 8f..42f && value < 0.80f && saturation in 0.18f..0.82f
                val hueBalanceWeight = if (isMuddyBrownOrSkinTone) 0.54f else 1.0f

                val weight = satWeight * brightnessWeight * spatialWeight * hueBalanceWeight

                val bucketIdx = ((hue / 15f).toInt()) % numBuckets
                bucketCount[bucketIdx]++
                bucketWeight[bucketIdx] += weight
                bucketSumR[bucketIdx] += r * weight.toDouble()
                bucketSumG[bucketIdx] += g * weight.toDouble()
                bucketSumB[bucketIdx] += b * weight.toDouble()
            }
        }

        // Circular neighbor smoothing across 0°/360° so Pink/Rose/Red hues spanning 345°..15° stay unified
        var winningIdx = -1
        var maxSmoothedWeight = -1f
        for (idx in 0 until numBuckets) {
            if (bucketCount[idx] == 0) continue
            val prevIdx = (idx - 1 + numBuckets) % numBuckets
            val nextIdx = (idx + 1) % numBuckets
            val smoothed = bucketWeight[idx] + (bucketWeight[prevIdx] * 0.45f) + (bucketWeight[nextIdx] * 0.45f)
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
                (bucketWeight[prevIdx].toDouble() * 0.42) +
                (bucketWeight[nextIdx].toDouble() * 0.42)
            val sumR = bucketSumR[winningIdx] + (bucketSumR[prevIdx] * 0.42) + (bucketSumR[nextIdx] * 0.42)
            val sumG = bucketSumG[winningIdx] + (bucketSumG[prevIdx] * 0.42) + (bucketSumG[nextIdx] * 0.42)
            val sumB = bucketSumB[winningIdx] + (bucketSumB[prevIdx] * 0.42) + (bucketSumB[nextIdx] * 0.42)
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

        // Preserve the extracted hue while shaping into a clean, luminous pastel palette
        hsv[1] = hsv[1].coerceIn(0.28f, 0.48f)
        hsv[2] = hsv[2].coerceIn(0.90f, 0.97f)

        val adjustedColorInt = android.graphics.Color.HSVToColor(hsv)
        return Color(adjustedColorInt)
    }

    internal fun findDualGradientColors(bitmap: Bitmap): Pair<Color, Color>? {
        val rawWidth = bitmap.width
        val rawHeight = bitmap.height
        if (rawWidth <= 1 || rawHeight <= 1 || bitmap.isRecycled) return null

        val scaled = if (rawWidth > 64 || rawHeight > 64) {
            try { Bitmap.createScaledBitmap(bitmap, 48, 48, true) } catch (_: Throwable) { bitmap }
        } else bitmap

        return try {
            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val halfH = (h / 2).coerceAtLeast(1)

            // Sample upper and lower halves directly from the single pixel buffer (zero extra Bitmap allocations)
            val rawTopColor = findProminentColorInSlice(pixels, w, h, 0, halfH)
                ?: findProminentColorInSlice(pixels, w, h, 0, h)
                ?: return null
            val rawBottomColor = findProminentColorInSlice(pixels, w, h, h - halfH, h)
                ?: rawTopColor

            val topHsv = FloatArray(3)
            val botHsv = FloatArray(3)
            android.graphics.Color.colorToHSV(rawTopColor.toArgb(), topHsv)
            android.graphics.Color.colorToHSV(rawBottomColor.toArgb(), botHsv)

            if (topHsv[0] in 20f..48f) {
                topHsv[0] = (topHsv[0] - 6f).coerceAtLeast(12f)
            }
            topHsv[1] = topHsv[1].coerceIn(0.36f, 0.56f)
            topHsv[2] = topHsv[2].coerceIn(0.84f, 0.94f)
            val softTopColor = Color(android.graphics.Color.HSVToColor(topHsv))

            val rawDiff = kotlin.math.abs(topHsv[0] - botHsv[0])
            val hueDiff = minOf(rawDiff, 360f - rawDiff)
            val isTopWarmGoldOrAmber = topHsv[0] in 15f..58f
            val isBotMuddyYellowGreen = botHsv[0] in 58f..102f

            val bottomColor = if (hueDiff < 26f || (isTopWarmGoldOrAmber && isBotMuddyYellowGreen)) {
                val hueShift = if (topHsv[0] in 12f..95f) -28f else 34f
                botHsv[0] = (topHsv[0] + hueShift + 360f) % 360f
                botHsv[1] = (topHsv[1] * 1.08f).coerceIn(0.38f, 0.62f)
                botHsv[2] = (topHsv[2] * 0.98f).coerceIn(0.86f, 0.95f)
                Color(android.graphics.Color.HSVToColor(botHsv))
            } else {
                if (botHsv[0] in 20f..48f) {
                    botHsv[0] = (botHsv[0] - 6f).coerceAtLeast(12f)
                }
                botHsv[1] = botHsv[1].coerceIn(0.38f, 0.62f)
                botHsv[2] = botHsv[2].coerceIn(0.86f, 0.95f)
                Color(android.graphics.Color.HSVToColor(botHsv))
            }

            softTopColor to bottomColor
        } catch (_: Throwable) {
            findProminentColor(bitmap)?.let { it to it }
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    /**
     * Samples the lower 36% of the album artwork (including deep atmospheric shadow tones like
     * twilight indigo, slate navy, plum, forest, and burgundy) without artificial hue-shifting
     * so full-bleed covers dissolve seamlessly into a clean velvety dark lower stage.
     */
    internal fun findBottomStageColor(bitmap: Bitmap): Color? {
        val rawWidth = bitmap.width
        val rawHeight = bitmap.height
        if (rawWidth <= 1 || rawHeight <= 1 || bitmap.isRecycled) return null

        val scaled = if (rawWidth > 64 || rawHeight > 64) {
            try { Bitmap.createScaledBitmap(bitmap, 48, 48, true) } catch (_: Throwable) { bitmap }
        } else bitmap

        return try {
            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)

            val startY = (h * 0.64f).toInt().coerceIn(0, h - 1)
            val numBuckets = 24
            val bucketWeight = FloatArray(numBuckets)
            val bucketSumR = DoubleArray(numBuckets)
            val bucketSumG = DoubleArray(numBuckets)
            val bucketSumB = DoubleArray(numBuckets)
            var sumAllR = 0L
            var sumAllG = 0L
            var sumAllB = 0L
            var allCount = 0
            val hsv = FloatArray(3)

            for (y in startY until h) {
                val rowOffset = y * w
                // Give slightly higher weight to rows closest to the actual bottom seam
                val rowProximity = 0.7f + 0.6f * ((y - startY).toFloat() / (h - startY).coerceAtLeast(1).toFloat())
                for (x in 0 until w) {
                    val pixel = pixels[rowOffset + x]
                    val a = android.graphics.Color.alpha(pixel)
                    if (a < 180) continue
                    val r = android.graphics.Color.red(pixel)
                    val g = android.graphics.Color.green(pixel)
                    val b = android.graphics.Color.blue(pixel)
                    sumAllR += r
                    sumAllG += g
                    sumAllB += b
                    allCount++

                    android.graphics.Color.colorToHSV(pixel, hsv)
                    val hue = hsv[0]
                    val sat = hsv[1]
                    val value = hsv[2]
                    if (value < 0.05f || value > 0.92f || sat < 0.09f) continue

                    val bucketIdx = ((hue / 15f).toInt()).coerceIn(0, numBuckets - 1)
                    // Favor rich atmospheric dark-to-mid shadow tones over warm skin-tone highlights
                    val shadowAffinity = if (value in 0.10f..0.55f) 1.35f else 0.75f
                    val coolClarityBonus = if (hue in 185f..295f) 1.25f else 1.0f
                    val muddyPenalty = if (hue in 14f..48f && sat < 0.36f) 0.55f else 1.0f
                    val weight = rowProximity * shadowAffinity * coolClarityBonus * muddyPenalty * (0.45f + sat)

                    bucketWeight[bucketIdx] += weight
                    bucketSumR[bucketIdx] += r * weight
                    bucketSumG[bucketIdx] += g * weight
                    bucketSumB[bucketIdx] += b * weight
                }
            }

            var winningIdx = -1
            var bestSmoothed = 0f
            for (i in 0 until numBuckets) {
                val prev = (i - 1 + numBuckets) % numBuckets
                val next = (i + 1) % numBuckets
                val smoothed = bucketWeight[i] + bucketWeight[prev] * 0.5f + bucketWeight[next] * 0.5f
                if (smoothed > bestSmoothed) {
                    bestSmoothed = smoothed
                    winningIdx = i
                }
            }

            val baseR: Int
            val baseG: Int
            val baseB: Int
            if (winningIdx >= 0 && bucketWeight[winningIdx] > 0f) {
                val prev = (winningIdx - 1 + numBuckets) % numBuckets
                val next = (winningIdx + 1) % numBuckets
                val totalW = bucketWeight[winningIdx].toDouble() +
                    bucketWeight[prev].toDouble() * 0.45 +
                    bucketWeight[next].toDouble() * 0.45
                baseR = ((bucketSumR[winningIdx] + bucketSumR[prev] * 0.45 + bucketSumR[next] * 0.45) / totalW).toInt().coerceIn(0, 255)
                baseG = ((bucketSumG[winningIdx] + bucketSumG[prev] * 0.45 + bucketSumG[next] * 0.45) / totalW).toInt().coerceIn(0, 255)
                baseB = ((bucketSumB[winningIdx] + bucketSumB[prev] * 0.45 + bucketSumB[next] * 0.45) / totalW).toInt().coerceIn(0, 255)
            } else if (allCount > 0) {
                baseR = (sumAllR / allCount).toInt().coerceIn(0, 255)
                baseG = (sumAllG / allCount).toInt().coerceIn(0, 255)
                baseB = (sumAllB / allCount).toInt().coerceIn(0, 255)
            } else {
                return null
            }

            val outHsv = FloatArray(3)
            android.graphics.Color.colorToHSV(android.graphics.Color.rgb(baseR, baseG, baseB), outHsv)
            // Neutral or muddy desaturated shadows refine cleanly into sleek twilight slate-indigo
            if (outHsv[1] < 0.14f || (outHsv[0] in 15f..46f && outHsv[1] < 0.28f)) {
                outHsv[0] = 222f
                outHsv[1] = 0.36f
            } else {
                // Gently pull pink/magenta dark shadows slightly cooler toward plum-indigo so they stay velvety, never brown
                if (outHsv[0] in 305f..352f && outHsv[2] < 0.45f) {
                    outHsv[0] = (outHsv[0] - 28f).coerceAtLeast(245f)
                }
                outHsv[1] = outHsv[1].coerceIn(0.28f, 0.44f)
            }
            outHsv[2] = 0.25f
            Color(android.graphics.Color.HSVToColor(outHsv))
        } catch (_: Throwable) {
            null
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }
}


