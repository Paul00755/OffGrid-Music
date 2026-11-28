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

/** Matching keys prefer audio-frame identity and use multi-tier normalized tags for high cross-device recall. */
fun jamLibraryMatchKeys(track: JamTrack, audioFingerprint: String?, sessionSalt: String): List<String> {
    val keys = LinkedHashSet<String>()
    audioFingerprint?.takeIf(String::isNotBlank)?.let { keys += "audio:${sha256("$sessionSalt\u001f$it")}" }

    val title = normalizeJamMatchTitle(track.title)
    if (title.isBlank() || track.durationMs <= 0L) return keys.toList()

    val compactTitle = title.replace(" ", "")
    val artistTokens = extractJamArtistTokens(track.artist)
    val fullArtist = normalizeJamMatchArtist(track.artist)
    val sortedArtist = artistTokens.sorted().joinToString(" ")
    val primaryArtist = artistTokens.firstOrNull().orEmpty()
    val album = normalizeJamMatchText(track.album, stripTrailingTags = true)

    val bucket = track.durationMs / 4_000L
    for (durationBucket in (bucket - 1L).coerceAtLeast(0L)..(bucket + 1L)) {
        if (fullArtist.isNotBlank()) {
            if (album.isNotBlank() && !album.contains("unknown")) {
                keys += "meta:${sha256("$sessionSalt\u001f$title\u001f$fullArtist\u001f$album\u001f$durationBucket")}"
            }
            keys += "meta:${sha256("$sessionSalt\u001f$title\u001f$fullArtist\u001f$durationBucket")}"
        }
        if (sortedArtist.isNotBlank() && sortedArtist != fullArtist) {
            keys += "meta:${sha256("$sessionSalt\u001f$title\u001fsa:$sortedArtist\u001f$durationBucket")}"
        }
        if (primaryArtist.isNotBlank()) {
            keys += "meta:${sha256("$sessionSalt\u001f$title\u001fpa:$primaryArtist\u001f$durationBucket")}"
            if (compactTitle != title && compactTitle.length >= 4) {
                keys += "meta:${sha256("$sessionSalt\u001fct:$compactTitle\u001fpa:$primaryArtist\u001f$durationBucket")}"
            }
        }
    }

    // High-specificity title + tight duration bucket (2.5s) handles devices with Unknown Artist or composer/singer differences
    if (compactTitle.length >= 5) {
        val tightBucket = track.durationMs / 2_500L
        for (tb in (tightBucket - 1L).coerceAtLeast(0L)..(tightBucket + 1L)) {
            keys += "meta:${sha256("$sessionSalt\u001ftd:$compactTitle\u001f$tb")}"
        }
    }
    return keys.toList()
}

private val jamSiteWatermarkRegex = Regex(
    "(?i)\\b(masstamilan|massstamilan|pagalworld|isaimini|starmusiq|kuttyweb|mp3juices|pendujatt|djpunjab|mrjatt|songspk|sensongs|naasongs|jiosaavn|spotify|wynk|gaana|tamildada|tamilprint|wapking|downloadming|djmaza|songsweb|tamilwire|raagtune|pagalnew|mrjatt|highresaudio)\\b(?:\\.[a-z]{2,5})?"
)

private val jamDomainTldRegex = Regex("(?i)\\b[a-z0-9-]+\\.(fm|dev|com|org|net|in|co|cc|info|me|io|xyz|top|live)\\b")
private val jamTrackPrefixRegex = Regex("^\\s*\\d{1,3}\\s*[-._)]\\s+")
private val jamBracketNoiseRegex = Regex(
    "(?i)[(\\[](?:from\\s+[^)\\]]*|official[^)\\]]*|lyric[^)\\]]*|audio[^)\\]]*|video[^)\\]]*|full\\s+song[^)\\]]*|feat\\.?[^)\\]]*|ft\\.?[^)\\]]*|\\d{2,4}\\s*kbps[^)\\]]*|masstamilan[^)\\]]*|pagalworld[^)\\]]*|isaimini[^)\\]]*|starmusiq[^)\\]]*|original\\s+motion[^)\\]]*|remaster(?:ed)?[^)\\]]*|hd|4k|hq|mp3|flac)[)\\]]"
)

private val removableTitleSuffixes = setOf(
    "audio", "video", "song", "songs", "lyrics", "lyric", "official", "full", "hd", "hq", "4k",
    "tamil", "telugu", "malayalam", "hindi", "english", "kannada", "mp3", "mp4", "aac", "flac",
    "wav", "m4a", "ogg", "remastered", "remaster", "version", "single", "original", "kbps",
    "fm", "dev", "com", "org", "net", "in", "indie", "masstamilan", "pagalworld", "isaimini",
    "starmusiq", "naasongs", "sensongs", "jiosaavn", "wynk", "gaana"
)

private val ignoredArtistTokens = setOf(
    "unknown", "artist", "unknown artist", "various", "various artists", "feat", "ft", "featuring",
    "and", "with", "masstamilan", "pagalworld", "isaimini", "starmusiq", "naasongs", "sensongs",
    "fm", "dev", "com", "org", "net", "in", "320kbps", "128kbps", "kbps", "mp3"
)

internal fun normalizeJamMatchTitle(value: String): String {
    val withoutPrefix = value.replace(jamTrackPrefixRegex, "")
    val withoutSites = withoutPrefix
        .replace(jamSiteWatermarkRegex, " ")
        .replace(jamDomainTldRegex, " ")
    val strippedBrackets = withoutSites.replace(jamBracketNoiseRegex, " ")
    val preferred = normalizeJamMatchText(strippedBrackets, stripTrailingTags = true)
    return preferred.ifBlank { normalizeJamMatchText(withoutSites, stripTrailingTags = true) }
}

internal fun normalizeJamMatchArtist(value: String): String {
    val cleaned = value
        .replace(jamSiteWatermarkRegex, " ")
        .replace(jamDomainTldRegex, " ")
    val normalized = normalizeJamMatchText(cleaned, stripTrailingTags = true)
    return if (normalized in ignoredArtistTokens) "" else normalized
}

internal fun extractJamArtistTokens(value: String): List<String> {
    val cleaned = value
        .replace(jamSiteWatermarkRegex, " ")
        .replace(jamDomainTldRegex, " ")
        .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring|with)\\b"), ",")
    val rawParts = cleaned.split(Regex("[,&;/+|]+"))
    val tokens = LinkedHashSet<String>()
    for (part in rawParts) {
        val norm = normalizeJamMatchText(part, stripTrailingTags = true)
        if (norm.isNotBlank() && norm !in ignoredArtistTokens && norm.length >= 2) {
            tokens += norm
        }
    }
    if (tokens.isEmpty()) {
        val whole = normalizeJamMatchArtist(value)
        if (whole.isNotBlank()) tokens += whole
    }
    return tokens.toList()
}

internal fun normalizeJamMatchText(value: String, stripTrailingTags: Boolean): String {
    val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\.(mp3|m4a|mp4|aac|flac|ogg|wav)$"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
    if (!stripTrailingTags || decomposed.isBlank()) return decomposed
    val tokens = decomposed.split(Regex("\\s+")).toMutableList()
    while (tokens.size > 1 && (tokens.last() in removableTitleSuffixes || tokens.last().matches(Regex("\\d{2,4}kbps")))) {
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
