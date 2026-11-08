package com.example.jam

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import com.example.data.model.Song
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds a small, tag-independent signature from encoded audio frames. Only the digest is
 * shared with Jam peers; filenames and the full local library are never uploaded.
 */
object JamLibraryFingerprint {
    private const val PREFS_NAME = "jam_audio_fingerprints_v1"
    private const val NO_FINGERPRINT = "!"
    private const val MAX_SAMPLE_BYTES = 192 * 1024
    private const val SAMPLES_PER_WINDOW = 8

    suspend fun forSong(context: Context, song: Song): String? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheKey = cacheKey(song)
        prefs.getString(cacheKey, null)?.let { return@withContext it.takeUnless { value -> value == NO_FINGERPRINT } }
        val fingerprint = runCatching { extract(context, song) }.getOrNull()
        prefs.edit().putString(cacheKey, fingerprint ?: NO_FINGERPRINT).apply()
        fingerprint
    }

    fun cached(context: Context, song: Song): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(cacheKey(song), null)
            ?.takeUnless { it == NO_FINGERPRINT }

    private fun cacheKey(song: Song): String {
        val path = song.path.takeIf(String::isNotBlank)
        val file = path?.let(::File)?.takeIf(File::isFile)
        val source = path ?: song.contentUriString.ifBlank { song.id.toString() }
        val identity = "$source|${song.durationMs}|${file?.length() ?: -1L}|${file?.lastModified() ?: 0L}"
        return "song_${sha256(identity)}"
    }

    private fun extract(context: Context, song: Song): String? {
        if (song.durationMs <= 0L) return null
        val extractor = MediaExtractor()
        try {
            val file = song.path.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)
            if (file != null) extractor.setDataSource(file.absolutePath)
            else {
                val uri: Uri = song.contentUri
                if (uri == Uri.EMPTY) return null
                extractor.setDataSource(context, uri, null)
            }

            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                    ?.startsWith("audio/", ignoreCase = true) == true
            } ?: return null
            extractor.selectTrack(audioTrack)

            val durationMs = song.durationMs.coerceAtLeast(1L)
            val windowsMs = listOf(800L, durationMs / 3L, (durationMs * 2L) / 3L).distinct()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
            var totalBytes = 0
            windowsMs.forEachIndexed { windowIndex, targetMs ->
                extractor.seekTo(targetMs.coerceAtLeast(0L) * 1_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                digest.update("window:$windowIndex".toByteArray(Charsets.UTF_8))
                var framesRead = 0
                while (framesRead < SAMPLES_PER_WINDOW) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size <= 0) break
                    digest.update(buffer.array(), 0, size)
                    totalBytes += size
                    framesRead++
                    if (!extractor.advance()) break
                }
            }
            if (totalBytes < 4_096) return null
            return "af1:${digest.digest().joinToString("") { "%02x".format(it) }}"
        } finally {
            runCatching { extractor.release() }
        }
    }
}

/** Matching keys prefer audio-frame identity and use normalized tags as a lightweight fallback. */
fun jamLibraryMatchKeys(track: JamTrack, audioFingerprint: String?, sessionSalt: String): List<String> {
    val keys = LinkedHashSet<String>()
    audioFingerprint?.takeIf(String::isNotBlank)?.let { keys += "audio:${sha256("$sessionSalt\u001f$it")}" }

    val title = normalizeMatchText(track.title, stripTrailingTags = true)
    val artist = normalizeMatchText(track.artist, stripTrailingTags = false)
    val album = normalizeMatchText(track.album, stripTrailingTags = false)
    if (title.isBlank() || artist.isBlank() || track.durationMs <= 0L) return keys.toList()

    val bucket = track.durationMs / 3_000L
    for (durationBucket in (bucket - 1L).coerceAtLeast(0L)..(bucket + 1L)) {
        keys += "meta:${sha256("$sessionSalt\u001f$title\u001f$artist\u001f$album\u001f$durationBucket")}"
        keys += "meta:${sha256("$sessionSalt\u001f$title\u001f$artist\u001f$durationBucket")}"
    }
    return keys.toList()
}

private val removableTitleSuffixes = setOf(
    "audio", "video", "song", "lyrics", "lyric", "official", "full", "hd", "tamil",
    "telugu", "malayalam", "hindi", "mp3", "mp4", "aac", "flac", "wav", "remastered"
)

private fun normalizeMatchText(value: String, stripTrailingTags: Boolean): String {
    val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\.(mp3|m4a|mp4|aac|flac|ogg|wav)$"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
    if (!stripTrailingTags) return decomposed
    val tokens = decomposed.split(Regex("\\s+")).toMutableList()
    while (tokens.isNotEmpty() && (tokens.last() in removableTitleSuffixes || tokens.last().matches(Regex("\\d{2,4}kbps")))) {
        tokens.removeAt(tokens.lastIndex)
    }
    return tokens.joinToString(" ")
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

data class JamSharedLibraryMatch(
    val song: Song,
    val track: JamTrack,
    val matchKey: String
)

data class JamLibraryIndexStatus(
    val localIndexedTracks: Int = 0,
    val localTotalTracks: Int = 0,
    val indexedParticipants: Int = 0,
    val participantCount: Int = 0,
    val isReady: Boolean = false,
    val error: String? = null
)

data class JamLibraryIndexSnapshot(
    val keysByUid: Map<String, Set<String>> = emptyMap(),
    val readyUids: Set<String> = emptySet()
)
