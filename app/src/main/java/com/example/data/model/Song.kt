package com.example.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String = "Unknown",
    val durationMs: Long,
    val path: String = "",
    val contentUriString: String,
    val artworkUri: String? = null,
    val albumId: Long = 0L,
    val artistId: Long = 0L,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastPlayed: Long = 0L,
    val playCount: Int = 0,
    val isFavorite: Boolean = false,
    val bitrate: Int = 0,
    val format: String = "MP3",
    val isMissing: Boolean = false,
    val isCorrupted: Boolean = false,
    val isManuallyEdited: Boolean = false
) {
    val contentUri: Uri
        get() = when {
            contentUriString.startsWith("content://") ||
                contentUriString.startsWith("http://") ||
                contentUriString.startsWith("https://") ||
                contentUriString.startsWith("android.resource://") ||
                contentUriString.startsWith("file://") -> Uri.parse(contentUriString)
            path.isNotBlank() && java.io.File(path).exists() -> Uri.fromFile(java.io.File(path))
            contentUriString.isNotBlank() -> Uri.parse(contentUriString)
            path.isNotBlank() -> Uri.fromFile(java.io.File(path))
            else -> Uri.EMPTY
        }

    // Use LazyThreadSafetyMode.PUBLICATION so concurrent access from Main UI and IO threads
    // (e.g., LibraryScreen + ArtworkResolutionCache.preloadWindow + PlaybackManager) never throws
    // a fatal NullPointerException inside UnsafeLazyImpl.initializer!!()
    val displayTitle: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (isManuallyEdited) {
            title
        } else {
            val raw = title.ifBlank { path.substringAfterLast('/').substringBeforeLast('.') }
            com.example.util.TagCleaner.cleanTitle(raw, path.substringAfterLast('/'))
        }
    }

    val displayArtist: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (artist.isBlank() || artist.contains("unknown", ignoreCase = true)) "Unknown Artist" else artist
    }

    val displayAlbum: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (album.isBlank() || album.contains("unknown", ignoreCase = true)) "Unknown Album" else album
    }

    val trackIdentityKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        com.example.ui.components.ArtworkResolutionCache.trackIdentity(id, path)
    }
}

@Immutable
data class Album(
    val id: Long,
    val name: String,
    val artist: String,
    val artworkUri: String? = null,
    val songCount: Int = 0,
    val year: Int = 0
)

@Immutable
data class Artist(
    val id: Long,
    val name: String,
    val songCount: Int = 0,
    val albumCount: Int = 0
)

@Immutable
data class FolderInfo(
    val path: String,
    val name: String,
    val uriString: String,
    val isExcluded: Boolean = false,
    val songCount: Int = 0,
    val lastScanTime: Long = System.currentTimeMillis(),
    val isAccessible: Boolean = true
)

@Immutable
data class Playlist(
    val id: Long,
    val name: String,
    val songCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val artworkUri: String? = null
)

data class ListeningStats(
    val periodName: String,
    val totalDurationMs: Long = 0L,
    val trackCount: Int = 0,
    val uniqueTrackCount: Int = 0
)

@Immutable
data class ArtistStat(
    val name: String,
    val playCount: Int,
    val totalDurationMs: Long
)

@Immutable
data class AlbumStat(
    val name: String,
    val artist: String,
    val artworkUri: String?,
    val playCount: Int
)

data class Lyrics(
    val songId: Long,
    val plainLyrics: String? = null,
    val syncedLyricsJson: String? = null,
    val isCached: Boolean = false,
    val source: String = "local"
) {
    fun parseSyncedLines(): List<Pair<Long, String>> {
        val target = if (!syncedLyricsJson.isNullOrBlank()) {
            syncedLyricsJson
        } else if (!plainLyrics.isNullOrBlank() && plainLyrics.contains(Regex("""\[\d{2}:\d{2}"""))) {
            plainLyrics
        } else {
            return emptyList()
        }
        val result = mutableListOf<Pair<Long, String>>()

        val trimmed = target.trim()
        if (trimmed.startsWith("[")) {
            try {
                val jsonArray = org.json.JSONArray(trimmed)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i)
                    if (obj != null) {
                        val timeMs = obj.optLong("timeMs", -1L)
                        val text = obj.optString("text", "")
                        if (timeMs >= 0) {
                            result.add(Pair(timeMs, if (text.isBlank()) "♪" else text))
                        }
                    }
                }
                if (result.isNotEmpty()) {
                    return result.sortedBy { it.first }
                }
            } catch (_: Exception) {
                // Fallthrough to LrcParser
            }
        }

        val lrcLines = com.example.util.LrcParser.parseLrcToLines(target)
        return lrcLines.map { Pair(it.timeMs, it.text) }
    }
}

data class DailyListeningStat(
    val dayStartMs: Long,
    val dayLabel: String,
    val fullDate: String,
    val durationMs: Long,
    val trackCount: Int,
    val isToday: Boolean = false
)

data class TimeOfDayDistribution(
    val morningDurationMs: Long = 0L,
    val afternoonDurationMs: Long = 0L,
    val eveningDurationMs: Long = 0L,
    val nightDurationMs: Long = 0L
) {
    val totalMs: Long get() = morningDurationMs + afternoonDurationMs + eveningDurationMs + nightDurationMs
    val dominantVibe: String
        get() {
            if (totalMs == 0L) return "Sonic Explorer"
            val max = maxOf(morningDurationMs, afternoonDurationMs, eveningDurationMs, nightDurationMs)
            return when (max) {
                nightDurationMs -> "Night Owl"
                morningDurationMs -> "Early Bird"
                afternoonDurationMs -> "Daytime Groover"
                else -> "Evening Relaxer"
            }
        }
}

data class WrappedData(
    val totalMinutes: Long = 0L,
    val totalTracks: Int = 0,
    val uniqueTracks: Int = 0,
    val activeDaysCount: Int = 0,
    val topSong: Song? = null,
    val topSongPlayCount: Int = 0,
    val topSongDurationMs: Long = 0L,
    val topSongs: List<Song> = emptyList(),
    val topArtists: List<ArtistStat> = emptyList(),
    val topAlbums: List<AlbumStat> = emptyList(),
    val timeOfDay: TimeOfDayDistribution = TimeOfDayDistribution(),
    val listenerArchetype: String = "The Sonic Explorer",
    val archetypeDescription: String = "You listen across deep music journeys with distinct taste."
)