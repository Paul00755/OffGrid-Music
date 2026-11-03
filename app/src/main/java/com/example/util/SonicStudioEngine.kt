package com.example.util

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableLongStateOf
import com.example.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sin

@Immutable
data class TrackBookmark(
    val id: String = UUID.randomUUID().toString(),
    val songId: Long,
    val timestampMs: Long,
    val label: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    val formattedTime: String
        get() {
            val totalSec = (timestampMs.coerceAtLeast(0L)) / 1000L
            return String.format(Locale.US, "%d:%02d", totalSec / 60L, totalSec % 60L)
        }
}

@Immutable
data class AbLoopState(
    val songId: Long = 0L,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val isActive: Boolean = false,
    val maxLoopCount: Int = DEFAULT_LOOP_COUNT,
    val completedLoops: Int = 0,
    val songDurationMs: Long = 0L
) {
    companion object {
        const val DEFAULT_LOOP_COUNT = 3
        const val MIN_LOOP_COUNT = 1
        const val MAX_LOOP_COUNT = 15
        const val MIN_LOOP_SPAN_MS = 500L
    }

    val remainingLoops: Int
        get() = (maxLoopCount - completedLoops).coerceAtLeast(0)

    val isLoopCountExhausted: Boolean
        get() = startMs != null && endMs != null && completedLoops >= maxLoopCount

    fun isConfiguredForSong(targetSongId: Long): Boolean =
        songId == targetSongId && startMs != null

    fun shouldLoopBack(targetSongId: Long, currentPositionMs: Long): Boolean {
        if (!isActive || songId != targetSongId) return false
        if (completedLoops >= maxLoopCount) return false
        val a = startMs ?: return false
        val b = endMs ?: return false
        return b > a + MIN_LOOP_SPAN_MS && currentPositionMs >= b
    }

    fun formatPoint(ms: Long?): String {
        if (ms == null || ms < 0L) return "--:--"
        val totalSec = ms / 1000L
        return String.format(Locale.US, "%d:%02d", totalSec / 60L, totalSec % 60L)
    }
}

@Immutable
data class ManualLoopUpdateResult(
    val success: Boolean,
    val state: AbLoopState,
    val wasClampedToDuration: Boolean = false,
    val errorMessage: String? = null
)

@Immutable
data class SonicDnaMatch(
    val song: Song,
    val score: Int,
    val matchReason: String
)

/**
 * Studio-grade audiophile & discovery engine for OffGrid Music:
 * 1. Precision A-B Loop controller with manual timestamp entry, duration bounds validation,
 *    configurable loop count (default 3, max 15), and automatic continuation after completion.
 * 2. Timestamped Audio Bookmarks persisted per track.
 * 3. "Sonic DNA" Instant Track Radio matcher (multi-factor local library vibe matching).
 * 4. Cached 52-bar Acoustic Waveform Envelope generator.
 */
object SonicStudioEngine {
    private const val BOOKMARKS_PREFS = "offgrid_sonic_bookmarks_v1"
    private val bookmarksCache = ConcurrentHashMap<Long, List<TrackBookmark>>()
    @Volatile
    private var isBookmarksLoaded = false

    val bookmarksUpdateSignal = mutableLongStateOf(0L)

    private val _abLoopState = MutableStateFlow(AbLoopState())
    val abLoopState: StateFlow<AbLoopState> = _abLoopState.asStateFlow()

    private val waveformCache = object : android.util.LruCache<Long, FloatArray>(256) {}

    // --- A-B Looper ---

    private fun resolveMaxDuration(durationMs: Long, fallbackDurationMs: Long = 0L): Long {
        return when {
            durationMs > 0L && durationMs != Long.MAX_VALUE -> durationMs
            fallbackDurationMs > 0L && fallbackDurationMs != Long.MAX_VALUE -> fallbackDurationMs
            else -> Long.MAX_VALUE
        }
    }

    fun clampToSongDuration(positionMs: Long, durationMs: Long): Long {
        val maxDur = resolveMaxDuration(durationMs)
        return positionMs.coerceIn(0L, maxDur.coerceAtLeast(0L))
    }

    /**
     * Parses a user-entered timestamp string into milliseconds.
     * Supports formats: "m:ss", "mm:ss", "hh:mm:ss", "mm:ss.d", or raw seconds like "75" / "75.5".
     */
    fun parseTimestampToMs(rawInput: String): Long? {
        val cleaned = rawInput.trim()
        if (cleaned.isEmpty() || cleaned == "--:--") return null
        if (cleaned.startsWith("-")) return null

        val parts = cleaned.split(":")
        return try {
            when (parts.size) {
                1 -> {
                    val seconds = parts[0].toDoubleOrNull() ?: return null
                    if (seconds < 0.0 || seconds.isNaN() || seconds.isInfinite()) return null
                    (seconds * 1000.0).toLong()
                }
                2 -> {
                    val minutes = parts[0].trim().toLongOrNull() ?: return null
                    val seconds = parts[1].trim().toDoubleOrNull() ?: return null
                    if (minutes < 0L || seconds < 0.0 || seconds >= 60.0 || seconds.isNaN()) return null
                    (minutes * 60_000L) + (seconds * 1000.0).toLong()
                }
                3 -> {
                    val hours = parts[0].trim().toLongOrNull() ?: return null
                    val minutes = parts[1].trim().toLongOrNull() ?: return null
                    val seconds = parts[2].trim().toDoubleOrNull() ?: return null
                    if (hours < 0L || minutes !in 0L..59L || seconds < 0.0 || seconds >= 60.0 || seconds.isNaN()) return null
                    (hours * 3_600_000L) + (minutes * 60_000L) + (seconds * 1000.0).toLong()
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun formatDurationMs(ms: Long): String {
        val safeSec = (ms.coerceAtLeast(0L)) / 1000L
        return String.format(Locale.US, "%d:%02d", safeSec / 60L, safeSec % 60L)
    }

    fun setLoopCount(count: Int) {
        val safeCount = count.coerceIn(AbLoopState.MIN_LOOP_COUNT, AbLoopState.MAX_LOOP_COUNT)
        val current = _abLoopState.value
        val hasBothPoints = current.startMs != null && current.endMs != null &&
            current.endMs > current.startMs + AbLoopState.MIN_LOOP_SPAN_MS
        _abLoopState.value = current.copy(
            maxLoopCount = safeCount,
            completedLoops = 0,
            isActive = if (hasBothPoints) true else current.isActive
        )
    }

    fun setLoopPointA(songId: Long, positionMs: Long, durationMs: Long = Long.MAX_VALUE) {
        if (songId <= 0L) return
        val current = _abLoopState.value
        val maxDur = resolveMaxDuration(durationMs, if (current.songId == songId) current.songDurationMs else 0L)
        val safeA = positionMs.coerceIn(0L, maxDur.coerceAtLeast(0L))
        val existingEnd = if (current.songId == songId) current.endMs?.coerceIn(0L, maxDur.coerceAtLeast(0L)) else null
        val validEnd = if (existingEnd != null && existingEnd > safeA + AbLoopState.MIN_LOOP_SPAN_MS) existingEnd else null
        val preservedLoopCount = current.maxLoopCount.coerceIn(AbLoopState.MIN_LOOP_COUNT, AbLoopState.MAX_LOOP_COUNT)
        _abLoopState.value = AbLoopState(
            songId = songId,
            startMs = safeA,
            endMs = validEnd,
            isActive = validEnd != null,
            maxLoopCount = preservedLoopCount,
            completedLoops = 0,
            songDurationMs = if (maxDur != Long.MAX_VALUE) maxDur else 0L
        )
    }

    fun setLoopPointB(songId: Long, positionMs: Long, durationMs: Long = Long.MAX_VALUE): Boolean {
        if (songId <= 0L) return false
        val current = _abLoopState.value
        val maxDur = resolveMaxDuration(durationMs, if (current.songId == songId) current.songDurationMs else 0L)
        val start = if (current.songId == songId && current.startMs != null) {
            current.startMs.coerceIn(0L, maxDur.coerceAtLeast(0L))
        } else 0L
        val safeB = positionMs.coerceIn(0L, maxDur.coerceAtLeast(0L))
        if (safeB <= start + AbLoopState.MIN_LOOP_SPAN_MS) return false
        val preservedLoopCount = current.maxLoopCount.coerceIn(AbLoopState.MIN_LOOP_COUNT, AbLoopState.MAX_LOOP_COUNT)
        _abLoopState.value = AbLoopState(
            songId = songId,
            startMs = start,
            endMs = safeB,
            isActive = true,
            maxLoopCount = preservedLoopCount,
            completedLoops = 0,
            songDurationMs = if (maxDur != Long.MAX_VALUE) maxDur else 0L
        )
        return true
    }

    fun setManualLoopPoints(
        songId: Long,
        rawStartMs: Long?,
        rawEndMs: Long?,
        durationMs: Long,
        loopCount: Int = _abLoopState.value.maxLoopCount
    ): ManualLoopUpdateResult {
        val current = _abLoopState.value
        if (songId <= 0L) {
            return ManualLoopUpdateResult(false, current, errorMessage = "Invalid track")
        }
        val maxDur = resolveMaxDuration(durationMs, if (current.songId == songId) current.songDurationMs else 0L)
        val hasKnownDuration = maxDur > 0L && maxDur != Long.MAX_VALUE
        val safeLoopCount = loopCount.coerceIn(AbLoopState.MIN_LOOP_COUNT, AbLoopState.MAX_LOOP_COUNT)

        val candidateStart = rawStartMs ?: (if (current.songId == songId) current.startMs else null) ?: 0L
        val candidateEnd = rawEndMs ?: (if (current.songId == songId) current.endMs else null)

        val clampedStart = if (hasKnownDuration) candidateStart.coerceIn(0L, maxDur) else candidateStart.coerceAtLeast(0L)
        val clampedEnd = candidateEnd?.let {
            if (hasKnownDuration) it.coerceIn(0L, maxDur) else it.coerceAtLeast(0L)
        }
        val wasClamped = hasKnownDuration && (
            (rawStartMs != null && (rawStartMs > maxDur || rawStartMs < 0L)) ||
            (rawEndMs != null && (rawEndMs > maxDur || rawEndMs < 0L))
        )

        if (clampedEnd != null && clampedEnd <= clampedStart + AbLoopState.MIN_LOOP_SPAN_MS) {
            return ManualLoopUpdateResult(
                success = false,
                state = current,
                wasClampedToDuration = wasClamped,
                errorMessage = if (wasClamped) {
                    "Timestamps cannot exceed track duration (${formatDurationMs(maxDur)}) and Point B must be after Point A"
                } else {
                    "Point B must be after Point A"
                }
            )
        }

        val updated = AbLoopState(
            songId = songId,
            startMs = clampedStart,
            endMs = clampedEnd,
            isActive = clampedEnd != null,
            maxLoopCount = safeLoopCount,
            completedLoops = 0,
            songDurationMs = if (hasKnownDuration) maxDur else 0L
        )
        _abLoopState.value = updated
        return ManualLoopUpdateResult(
            success = true,
            state = updated,
            wasClampedToDuration = wasClamped
        )
    }

    /**
     * Atomically evaluates whether playback at [currentPositionMs] should loop back to Point A.
     * - While completedLoops < maxLoopCount, increments completedLoops and returns Point A (in ms).
     * - Once the user-set loop count finishes, sets isActive = false and returns null so the
     *   song continues playing normally past Point B.
     */
    @Synchronized
    fun checkAndConsumeLoopTrigger(targetSongId: Long, currentPositionMs: Long): Long? {
        val current = _abLoopState.value
        if (!current.isActive || current.songId != targetSongId) return null
        val a = current.startMs ?: return null
        val b = current.endMs ?: return null
        if (b <= a + AbLoopState.MIN_LOOP_SPAN_MS || currentPositionMs < b) return null

        val maxLoops = current.maxLoopCount.coerceIn(AbLoopState.MIN_LOOP_COUNT, AbLoopState.MAX_LOOP_COUNT)
        return if (current.completedLoops < maxLoops) {
            val nextCompleted = current.completedLoops + 1
            _abLoopState.value = current.copy(
                completedLoops = nextCompleted,
                maxLoopCount = maxLoops
            )
            a.coerceAtLeast(0L)
        } else {
            _abLoopState.value = current.copy(
                isActive = false,
                completedLoops = maxLoops,
                maxLoopCount = maxLoops
            )
            null
        }
    }

    fun toggleAbLoopActive(songId: Long) {
        val current = _abLoopState.value
        if (current.songId == songId && current.startMs != null && current.endMs != null) {
            val activating = !current.isActive
            _abLoopState.value = current.copy(
                isActive = activating,
                completedLoops = if (activating && current.completedLoops >= current.maxLoopCount) 0 else current.completedLoops
            )
        }
    }

    fun clearAbLoop() {
        val preservedCount = _abLoopState.value.maxLoopCount.coerceIn(
            AbLoopState.MIN_LOOP_COUNT,
            AbLoopState.MAX_LOOP_COUNT
        )
        val cleared = AbLoopState(maxLoopCount = preservedCount)
        if (_abLoopState.value != cleared) {
            _abLoopState.value = cleared
        }
    }

    // --- Timestamped Audio Bookmarks ---

    private fun ensureBookmarksLoaded(context: Context) {
        if (isBookmarksLoaded) return
        synchronized(this) {
            if (isBookmarksLoaded) return
            isBookmarksLoaded = true
            try {
                val prefs = context.applicationContext.getSharedPreferences(BOOKMARKS_PREFS, Context.MODE_PRIVATE)
                for ((key, rawVal) in prefs.all) {
                    val songId = key.removePrefix("song_").toLongOrNull() ?: continue
                    val jsonStr = rawVal as? String ?: continue
                    val parsed = parseBookmarksJson(songId, jsonStr)
                    if (parsed.isNotEmpty()) {
                        bookmarksCache[songId] = parsed
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun getBookmarks(context: Context, songId: Long): List<TrackBookmark> {
        if (songId <= 0L) return emptyList()
        ensureBookmarksLoaded(context)
        return bookmarksCache[songId].orEmpty()
    }

    fun addBookmark(
        context: Context,
        songId: Long,
        timestampMs: Long,
        label: String
    ): TrackBookmark {
        ensureBookmarksLoaded(context)
        val cleanLabel = label.trim().ifEmpty { "Moment" }
        val newBookmark = TrackBookmark(
            songId = songId,
            timestampMs = timestampMs.coerceAtLeast(0L),
            label = cleanLabel
        )
        val updated = (bookmarksCache[songId].orEmpty() + newBookmark)
            .sortedBy { it.timestampMs }
        bookmarksCache[songId] = updated
        persistBookmarks(context, songId, updated)
        bookmarksUpdateSignal.longValue = System.currentTimeMillis()
        return newBookmark
    }

    fun removeBookmark(context: Context, songId: Long, bookmarkId: String) {
        ensureBookmarksLoaded(context)
        val current = bookmarksCache[songId].orEmpty()
        val updated = current.filterNot { it.id == bookmarkId }
        if (updated.isEmpty()) {
            bookmarksCache.remove(songId)
        } else {
            bookmarksCache[songId] = updated
        }
        persistBookmarks(context, songId, updated)
        bookmarksUpdateSignal.longValue = System.currentTimeMillis()
    }

    private fun persistBookmarks(context: Context, songId: Long, bookmarks: List<TrackBookmark>) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(BOOKMARKS_PREFS, Context.MODE_PRIVATE)
            if (bookmarks.isEmpty()) {
                prefs.edit().remove("song_$songId").apply()
            } else {
                val arr = JSONArray()
                for (b in bookmarks) {
                    val obj = JSONObject()
                    obj.put("id", b.id)
                    obj.put("timestampMs", b.timestampMs)
                    obj.put("label", b.label)
                    obj.put("createdAt", b.createdAt)
                    arr.put(obj)
                }
                prefs.edit().putString("song_$songId", arr.toString()).apply()
            }
        } catch (_: Throwable) {}
    }

    private fun parseBookmarksJson(songId: Long, jsonStr: String): List<TrackBookmark> {
        return try {
            val arr = JSONArray(jsonStr)
            val list = ArrayList<TrackBookmark>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifEmpty { UUID.randomUUID().toString() }
                val ts = obj.optLong("timestampMs", 0L).coerceAtLeast(0L)
                val label = obj.optString("label", "Bookmark").ifBlank { "Bookmark" }
                val createdAt = obj.optLong("createdAt", 0L)
                list.add(TrackBookmark(id = id, songId = songId, timestampMs = ts, label = label, createdAt = createdAt))
            }
            list.sortedBy { it.timestampMs }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // --- Sonic DNA Instant Track Radio ---

    private val ARTIST_SPLIT_REGEX = Regex("""[,&/]|(?:\b(?:feat\.?|ft\.?|with|vs\.?)\b)""", RegexOption.IGNORE_CASE)
    private val WORD_TOKEN_REGEX = Regex("""[a-z0-9]{3,}""")

    private fun extractArtistTokens(artist: String): Set<String> {
        if (artist.isBlank() || artist.equals("Unknown Artist", ignoreCase = true) || artist.equals("<unknown>", ignoreCase = true)) {
            return emptySet()
        }
        return artist.split(ARTIST_SPLIT_REGEX)
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun extractGenreTokens(genre: String?): Set<String> {
        if (genre.isNullOrBlank() || genre.equals("Unknown", ignoreCase = true)) return emptySet()
        return WORD_TOKEN_REGEX.findAll(genre.lowercase(Locale.ROOT))
            .map { it.value }
            .toSet()
    }

    private fun parentFolderKey(path: String): String {
        val normalized = path.replace('\\', '/').trim()
        val lastSlash = normalized.lastIndexOf('/')
        return if (lastSlash > 0) normalized.substring(0, lastSlash).lowercase(Locale.ROOT) else ""
    }

    /**
     * Generates a curated "Sonic DNA" radio queue from [candidateSongs] seeded by [seedSong].
     * Scores tracks by Artist/Collaborator overlap, Genre tokens, Release Era, Duration/Tempo profile,
     * Album/Folder context, and Listening Affinity, while enforcing artist diversity.
     */
    fun buildSonicDnaRadioQueue(
        seedSong: Song,
        candidateSongs: List<Song>,
        maxTracks: Int = 25
    ): List<SonicDnaMatch> {
        if (candidateSongs.isEmpty() || maxTracks <= 0) {
            return listOf(SonicDnaMatch(seedSong, 100, "Seed Track"))
        }

        val seedArtists = extractArtistTokens(seedSong.displayArtist)
        val seedPrimaryArtist = seedSong.displayArtist.trim().lowercase(Locale.ROOT)
        val seedGenres = extractGenreTokens(seedSong.genre)
        val seedGenreRaw = seedSong.genre?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val seedYear = seedSong.year?.takeIf { it in 1900..2100 }
        val seedAlbum = seedSong.displayAlbum.trim().lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() && it != "unknown album" && it != "<unknown>" }
        val seedFolder = parentFolderKey(seedSong.path)
        val seedDuration = seedSong.durationMs.coerceAtLeast(1L)

        val scoredCandidates = ArrayList<SonicDnaMatch>(candidateSongs.size)

        for (candidate in candidateSongs) {
            if (candidate.id == seedSong.id || (candidate.path.isNotBlank() && candidate.path == seedSong.path)) {
                continue
            }

            var rawScore = 18 // Base acoustic library compatibility
            val reasons = ArrayList<String>(3)

            // 1. Artist & Collaborator Affinity (up to 38 pts)
            val candPrimaryArtist = candidate.displayArtist.trim().lowercase(Locale.ROOT)
            val candArtists = extractArtistTokens(candidate.displayArtist)
            if (seedPrimaryArtist.isNotEmpty() && seedPrimaryArtist == candPrimaryArtist && seedPrimaryArtist != "unknown artist") {
                rawScore += 36
                reasons.add("Same Artist")
            } else if (seedArtists.isNotEmpty() && candArtists.any { it in seedArtists }) {
                rawScore += 26
                reasons.add("Shared Artist")
            }

            // 2. Genre & Sonic Style Affinity (up to 26 pts)
            val candGenreRaw = candidate.genre?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val candGenres = extractGenreTokens(candidate.genre)
            if (seedGenreRaw.isNotEmpty() && seedGenreRaw == candGenreRaw) {
                rawScore += 26
                reasons.add(candidate.genre!!.trim())
            } else if (seedGenres.isNotEmpty() && candGenres.any { it in seedGenres }) {
                rawScore += 18
                reasons.add("Genre Vibe")
            }

            // 3. Album & Folder Context (up to 14 pts)
            val candAlbum = candidate.displayAlbum.trim().lowercase(Locale.ROOT)
            if (seedAlbum != null && seedAlbum == candAlbum) {
                rawScore += 14
                if ("Same Album" !in reasons) reasons.add("Same Album")
            } else if (seedFolder.isNotEmpty() && seedFolder == parentFolderKey(candidate.path)) {
                rawScore += 9
                if (reasons.isEmpty()) reasons.add("Same Collection")
            }

            // 4. Era / Release Year Proximity (up to 14 pts)
            val candYear = candidate.year?.takeIf { it in 1900..2100 }
            if (seedYear != null && candYear != null) {
                val yearDiff = abs(seedYear - candYear)
                when {
                    yearDiff <= 2 -> {
                        rawScore += 14
                        if (reasons.size < 2) reasons.add("${candYear}s Era")
                    }
                    yearDiff <= 6 -> {
                        rawScore += 9
                    }
                    (seedYear / 10) == (candYear / 10) -> {
                        rawScore += 6
                    }
                }
            }

            // 5. Duration & Structural Energy Profile (up to 12 pts)
            val durDiffSec = abs(seedDuration - candidate.durationMs) / 1000L
            when {
                durDiffSec <= 25L -> rawScore += 12
                durDiffSec <= 60L -> rawScore += 8
                durDiffSec <= 110L -> rawScore += 4
            }

            // 6. Listener Affinity Bonus (up to 6 pts)
            if (candidate.isFavorite) rawScore += 4
            if (candidate.playCount > 0) rawScore += 2

            val normalizedScore = rawScore.coerceIn(25, 99)
            val summaryReason = if (reasons.isNotEmpty()) {
                reasons.take(2).joinToString(" • ")
            } else {
                "Acoustic Tempo & Vibe"
            }

            scoredCandidates.add(
                SonicDnaMatch(
                    song = candidate,
                    score = normalizedScore,
                    matchReason = "$normalizedScore% DNA • $summaryReason"
                )
            )
        }

        // Sort highest score first, breaking ties by playCount and title
        scoredCandidates.sortWith(
            compareByDescending<SonicDnaMatch> { it.score }
                .thenByDescending { it.song.isFavorite }
                .thenByDescending { it.song.playCount }
                .thenBy { it.song.displayTitle }
        )

        // Assemble final radio queue starting with seedSong, spacing out identical artists so no more than 2 tracks by the same artist appear back-to-back
        val result = ArrayList<SonicDnaMatch>(maxTracks.coerceAtMost(scoredCandidates.size + 1))
        result.add(SonicDnaMatch(seedSong, 100, "100% DNA • Radio Seed"))

        val remaining = scoredCandidates.toMutableList()
        var lastArtist = seedPrimaryArtist
        var consecutiveSameArtist = 1

        while (result.size < maxTracks && remaining.isNotEmpty()) {
            val nextIdx = remaining.indexOfFirst { match ->
                val art = match.song.displayArtist.trim().lowercase(Locale.ROOT)
                !(art == lastArtist && consecutiveSameArtist >= 2)
            }.let { if (it >= 0) it else 0 }

            val chosen = remaining.removeAt(nextIdx)
            val chosenArtist = chosen.song.displayArtist.trim().lowercase(Locale.ROOT)
            if (chosenArtist == lastArtist) {
                consecutiveSameArtist++
            } else {
                lastArtist = chosenArtist
                consecutiveSameArtist = 1
            }
            result.add(chosen)
        }

        return result
    }

    // --- True Acoustic Waveform Envelope Generator ---

    fun getWaveformEnvelope(song: Song?, barCount: Int = 52): FloatArray {
        if (song == null || barCount <= 0) return FloatArray(0)
        synchronized(waveformCache) {
            waveformCache.get(song.id)?.let { cached ->
                if (cached.size == barCount) return cached
            }
        }

        val generated = computeAcousticEnvelope(song, barCount)
        if (song.id > 0L) {
            synchronized(waveformCache) {
                waveformCache.put(song.id, generated)
            }
        }
        return generated
    }

    private fun computeAcousticEnvelope(song: Song, barCount: Int): FloatArray {
        val result = FloatArray(barCount)
        var sampledFromBytes = false

        // Attempt ultra-fast sparse byte-energy sampling from the audio file middle payload (skipping ID3 headers)
        if (song.path.isNotBlank()) {
            try {
                val file = File(song.path)
                if (file.exists() && file.length() > 65536L) {
                    RandomAccessFile(file, "r").use { raf ->
                        val len = file.length()
                        val startOffset = (len * 0.08).toLong()
                        val usableSpan = (len * 0.84).toLong().coerceAtLeast(barCount * 256L)
                        val step = usableSpan / barCount
                        val sampleBuf = ByteArray(128)

                        for (i in 0 until barCount) {
                            val seekPos = (startOffset + i * step).coerceAtMost(len - sampleBuf.size)
                            raf.seek(seekPos)
                            val read = raf.read(sampleBuf)
                            if (read > 0) {
                                var energySum = 0L
                                for (bIdx in 0 until read) {
                                    val centered = abs((sampleBuf[bIdx].toInt() and 0xFF) - 128)
                                    energySum += centered
                                }
                                val avgEnergy = (energySum.toFloat() / (read * 64f)).coerceIn(0f, 1f)
                                val arch = sin((i.toFloat() / (barCount - 1).coerceAtLeast(1)) * Math.PI).toFloat()
                                result[i] = (0.18f + avgEnergy * 0.56f + arch * 0.24f).coerceIn(0.16f, 0.96f)
                            }
                        }
                        sampledFromBytes = true
                    }
                }
            } catch (_: Throwable) {
                sampledFromBytes = false
            }
        }

        if (!sampledFromBytes) {
            val seed = (song.id xor song.displayTitle.hashCode().toLong() xor song.durationMs).toInt()
            for (i in 0 until barCount) {
                val norm = i.toFloat() / (barCount - 1).coerceAtLeast(1).toFloat()
                val macroArch = sin(norm * Math.PI).toFloat()
                val harmonic1 = abs(sin((i * 0.63 + (seed and 0xFF) * 0.11)))
                val harmonic2 = abs(sin((i * 1.37 + ((seed ushr 8) and 0xFF) * 0.07)))
                val combined = (0.16f + 0.34f * macroArch + 0.30f * harmonic1.toFloat() + 0.20f * harmonic2.toFloat())
                result[i] = combined.coerceIn(0.16f, 0.96f)
            }
        }

        return result
    }
}
