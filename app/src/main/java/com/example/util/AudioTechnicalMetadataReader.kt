package com.example.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Immutable
import com.example.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Immutable
data class AudioTechnicalInfo(
    val formatLabel: String = "",
    val sampleRateHz: Int = 0,
    val bitrateKbps: Int = 0,
    val bitDepth: Int = 0,
    val channelCount: Int = 0
) {
    fun hasAnyInfo(): Boolean {
        return formatLabel.isNotBlank() || sampleRateHz > 0 || bitrateKbps > 0 || bitDepth > 0
    }

    fun formatSummary(): String {
        val parts = mutableListOf<String>()
        if (formatLabel.isNotBlank()) {
            parts.add(formatLabel)
        }
        if (bitDepth > 0) {
            parts.add("$bitDepth-bit")
        }
        if (sampleRateHz > 0) {
            parts.add(formatSampleRate(sampleRateHz))
        }
        if (bitrateKbps > 0) {
            parts.add("$bitrateKbps kbps")
        }
        if (channelCount == 1) {
            parts.add("Mono")
        }
        return parts.joinToString(" • ")
    }

    companion object {
        fun formatSampleRate(hz: Int): String {
            if (hz <= 0) return ""
            val khz = hz / 1000.0
            return if (hz % 1000 == 0) {
                "${hz / 1000} kHz"
            } else {
                String.format(Locale.US, "%.1f kHz", khz)
            }
        }
    }
}

object AudioTechnicalMetadataReader {
    private val cache = LruCache<String, AudioTechnicalInfo>(240)

    fun clearCache() {
        cache.evictAll()
    }

    fun resolveFormatLabel(mime: String?, path: String?, songFormat: String?): String {
        val m = mime?.lowercase(Locale.US).orEmpty()
        return when {
            m.contains("flac") -> "FLAC"
            m.contains("alac") -> "ALAC"
            m.contains("mpeg") || m.contains("mp3") -> "MP3"
            m.contains("mp4a") || m.contains("aac") -> "AAC"
            m.contains("opus") -> "OPUS"
            m.contains("vorbis") -> "OGG"
            m.contains("wav") || m.contains("pcm") || m.contains("raw") -> "WAV"
            m.contains("dsd") -> "DSD"
            else -> {
                val ext = path?.substringAfterLast('.', "")?.uppercase(Locale.US).orEmpty()
                when {
                    ext.isNotBlank() && ext.length <= 5 && !ext.contains('/') -> ext
                    !songFormat.isNullOrBlank() -> songFormat.uppercase(Locale.US)
                    else -> "AUDIO"
                }
            }
        }
    }

    private fun resolveBitDepthFromFormat(format: MediaFormat, mime: String?): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                android.media.AudioFormat.ENCODING_PCM_8BIT -> return 8
                android.media.AudioFormat.ENCODING_PCM_16BIT -> return 16
                android.media.AudioFormat.ENCODING_PCM_FLOAT -> return 32
                21 /* ENCODING_PCM_24BIT_PACKED */ -> return 24
                22 /* ENCODING_PCM_32BIT */ -> return 32
            }
        }
        // Standard integer key used by several extractors for bits-per-sample
        if (format.containsKey("bits-per-sample")) {
            val bps = runCatching { format.getInteger("bits-per-sample") }.getOrDefault(0)
            if (bps in listOf(8, 16, 24, 32)) return bps
        }
        // Fallback hint for PCM/WAV or lossless formats only when MediaExtractor exposes it
        val m = mime?.lowercase(Locale.US).orEmpty()
        if (m.contains("raw") || m.contains("wav")) {
            return 16
        }
        return 0
    }

    suspend fun readForSong(context: Context, song: Song?): AudioTechnicalInfo {
        if (song == null) return AudioTechnicalInfo()
        val key = "${song.id}_${song.path}_${song.bitrate}"
        cache.get(key)?.let { return it }

        val result = withContext(Dispatchers.IO) {
            var mime: String? = null
            var sampleRateHz = 0
            var bitrateKbps = if (song.bitrate > 0) normalizeBitrateToKbps(song.bitrate) else 0
            var bitDepth = 0
            var channelCount = 0
            var fileSizeBytes = 0L

            val extractor = MediaExtractor()
            try {
                val sourceSet = configureExtractorSource(context, extractor, song)
                if (sourceSet) {
                    for (i in 0 until extractor.trackCount) {
                        val trackFormat = extractor.getTrackFormat(i)
                        val trackMime = trackFormat.getString(MediaFormat.KEY_MIME)
                        if (trackMime?.startsWith("audio/") == true) {
                            mime = trackMime
                            if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRateHz = runCatching { trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(0)
                            }
                            if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channelCount = runCatching { trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(0)
                            }
                            if (trackFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                                val rawBps = runCatching { trackFormat.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrDefault(0)
                                val parsedKbps = normalizeBitrateToKbps(rawBps)
                                if (parsedKbps > 0) bitrateKbps = parsedKbps
                            }
                            bitDepth = resolveBitDepthFromFormat(trackFormat, trackMime)
                            break
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { extractor.release() }
            }

            if (bitrateKbps <= 0 || sampleRateHz <= 0) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (configureRetrieverSource(context, retriever, song)) {
                        if (bitrateKbps <= 0) {
                            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                            bitrateKbps = normalizeBitrateToKbps(raw)
                        }
                        if (mime.isNullOrBlank()) {
                            mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sampleRateHz <= 0) {
                            val sr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                            if (sr > 0) sampleRateHz = sr
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && bitDepth <= 0) {
                            val bits = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
                            if (bits > 0) bitDepth = bits
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { retriever.release() }
                }
            }

            if (bitrateKbps <= 0 && song.durationMs > 1000L) {
                fileSizeBytes = resolveFileSizeBytes(context, song)
                if (fileSizeBytes > 0L) {
                    val computed = ((fileSizeBytes * 8L) / song.durationMs).toInt() // bits / ms == kbps
                    if (computed > 0) bitrateKbps = computed
                }
            }

            val formatLabel = resolveFormatLabel(mime, song.path, song.format)
            AudioTechnicalInfo(
                formatLabel = formatLabel,
                sampleRateHz = sampleRateHz,
                bitrateKbps = bitrateKbps,
                bitDepth = bitDepth,
                channelCount = channelCount
            )
        }

        cache.put(key, result)
        return result
    }

    internal fun normalizeBitrateToKbps(rawValue: Int): Int {
        if (rawValue <= 0) return 0
        // MediaMetadataRetriever & MediaFormat report bps (e.g., 320000), while some Song models store kbps (320)
        return if (rawValue > 4000) rawValue / 1000 else rawValue
    }

    private fun configureExtractorSource(context: Context, extractor: MediaExtractor, song: Song): Boolean {
        val path = song.path
        if (path.startsWith("content://") || path.startsWith("file://")) {
            extractor.setDataSource(context, Uri.parse(path), null)
            return true
        }
        val file = File(path)
        if (file.exists() && file.canRead()) {
            extractor.setDataSource(file.absolutePath)
            return true
        }
        if (song.id > 0L) {
            val contentUri = Uri.withAppendedPath(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id.toString())
            extractor.setDataSource(context, contentUri, null)
            return true
        }
        return false
    }

    private fun configureRetrieverSource(context: Context, retriever: MediaMetadataRetriever, song: Song): Boolean {
        val path = song.path
        if (path.startsWith("content://") || path.startsWith("file://")) {
            retriever.setDataSource(context, Uri.parse(path))
            return true
        }
        val file = File(path)
        if (file.exists() && file.canRead()) {
            retriever.setDataSource(file.absolutePath)
            return true
        }
        if (song.id > 0L) {
            val contentUri = Uri.withAppendedPath(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id.toString())
            retriever.setDataSource(context, contentUri)
            return true
        }
        return false
    }

    private fun resolveFileSizeBytes(context: Context, song: Song): Long {
        val path = song.path
        if (!path.startsWith("content://") && !path.startsWith("file://")) {
            val f = File(path)
            if (f.exists()) return f.length()
        }
        return try {
            val uri = if (path.startsWith("content://") || path.startsWith("file://")) {
                Uri.parse(path)
            } else {
                Uri.withAppendedPath(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id.toString())
            }
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }
}
