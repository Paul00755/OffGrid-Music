package com.example.data.sync

import com.example.data.model.Song
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Status of cloud backup operation.
 */
sealed class BackupStatus {
    object Idle : BackupStatus()
    object BackingUp : BackupStatus()
    data class Success(val timestamp: Long) : BackupStatus()
    data class Error(val message: String) : BackupStatus()
}

/**
 * Backup frequency options for automatic background sync.
 */
enum class BackupFrequency(val displayName: String, val intervalDays: Long) {
    DAILY("Daily", 1L),
    WEEKLY("Weekly", 7L),
    MONTHLY("Monthly", 30L);

    companion object {
        fun fromString(value: String?): BackupFrequency {
            return when (value?.uppercase(Locale.ROOT)) {
                "DAILY" -> DAILY
                "MONTHLY" -> MONTHLY
                else -> WEEKLY
            }
        }
    }
}

/**
 * Profile information snapshot for backup.
 */
data class BackupProfileData(
    val uid: String = "",
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val customDisplayName: String? = null,
    val customAvatarUri: String? = null,
    val avatarBase64: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "uid" to uid,
        "email" to email,
        "displayName" to displayName,
        "photoUrl" to photoUrl,
        "customDisplayName" to customDisplayName,
        "customAvatarUri" to customAvatarUri,
        "avatarBase64" to avatarBase64
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): BackupProfileData = BackupProfileData(
            uid = map["uid"] as? String ?: "",
            email = map["email"] as? String,
            displayName = map["displayName"] as? String,
            photoUrl = map["photoUrl"] as? String,
            customDisplayName = map["customDisplayName"] as? String,
            customAvatarUri = map["customAvatarUri"] as? String,
            avatarBase64 = map["avatarBase64"] as? String
        )
    }
}

/**
 * Aggregated statistics summary for backup and instant restoration.
 */
data class BackupStatisticsSummary(
    val totalPlayCount: Long = 0L,
    val totalListeningTimeMs: Long = 0L,
    val uniqueTracksPlayed: Int = 0,
    val mostPlayedArtist: String? = null,
    val mostPlayedAlbum: String? = null,
    val allTimeTopSong: String? = null,
    val timeOfDayDistribution: Map<String, Long> = emptyMap(),
    val topArtistsSummary: List<String> = emptyList(),
    val topSongsSummary: List<String> = emptyList(),
    val topAlbumsSummary: List<String> = emptyList()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "totalPlayCount" to totalPlayCount,
        "totalListeningTimeMs" to totalListeningTimeMs,
        "uniqueTracksPlayed" to uniqueTracksPlayed,
        "mostPlayedArtist" to mostPlayedArtist,
        "mostPlayedAlbum" to mostPlayedAlbum,
        "allTimeTopSong" to allTimeTopSong,
        "timeOfDayDistribution" to timeOfDayDistribution,
        "topArtistsSummary" to topArtistsSummary,
        "topSongsSummary" to topSongsSummary,
        "topAlbumsSummary" to topAlbumsSummary
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): BackupStatisticsSummary = BackupStatisticsSummary(
            totalPlayCount = (map["totalPlayCount"] as? Number)?.toLong() ?: 0L,
            totalListeningTimeMs = (map["totalListeningTimeMs"] as? Number)?.toLong() ?: 0L,
            uniqueTracksPlayed = (map["uniqueTracksPlayed"] as? Number)?.toInt() ?: 0,
            mostPlayedArtist = map["mostPlayedArtist"] as? String,
            mostPlayedAlbum = map["mostPlayedAlbum"] as? String,
            allTimeTopSong = map["allTimeTopSong"] as? String,
            timeOfDayDistribution = (map["timeOfDayDistribution"] as? Map<String, Any?>)?.mapValues {
                (it.value as? Number)?.toLong() ?: 0L
            } ?: emptyMap(),
            topArtistsSummary = (map["topArtistsSummary"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            topSongsSummary = (map["topSongsSummary"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            topAlbumsSummary = (map["topAlbumsSummary"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        )
    }
}

/**
 * Story Card and Wrapped data required for instant restoration.
 */
data class BackupStoryCardData(
    val lastGeneratedPeriod: String = "ALL_TIME",
    val listeningPersona: String = "Night Owl",
    val topGenreOrStyle: String = "Eclectic",
    val totalMinutesListened: Long = 0L,
    val storyHighlights: List<String> = emptyList()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "lastGeneratedPeriod" to lastGeneratedPeriod,
        "listeningPersona" to listeningPersona,
        "topGenreOrStyle" to topGenreOrStyle,
        "totalMinutesListened" to totalMinutesListened,
        "storyHighlights" to storyHighlights
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): BackupStoryCardData = BackupStoryCardData(
            lastGeneratedPeriod = map["lastGeneratedPeriod"] as? String ?: "ALL_TIME",
            listeningPersona = map["listeningPersona"] as? String ?: "Night Owl",
            topGenreOrStyle = map["topGenreOrStyle"] as? String ?: "Eclectic",
            totalMinutesListened = (map["totalMinutesListened"] as? Number)?.toLong() ?: 0L,
            storyHighlights = (map["storyHighlights"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        )
    }
}

/**
 * Complete latest backup payload representation stored at `users/{uid}/backup/latest`.
 */
data class UserBackupPayload(
    val version: Int = 2,
    val ownerUid: String = "",
    val snapshotComplete: Boolean = false,
    val historyStoredSeparately: Boolean = false,
    val backupTimestamp: Long = System.currentTimeMillis(),
    val backupDateFormatted: String = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(backupTimestamp)),
    val dataHash: String = "",
    val profile: BackupProfileData = BackupProfileData(),
    val settings: CloudUserSettings = CloudUserSettings(),
    val favoritesCount: Int = 0,
    val favorites: List<CloudFavoriteSong> = emptyList(),
    val playlistsCount: Int = 0,
    val playlists: List<CloudPlaylist> = emptyList(),
    val foldersCount: Int = 0,
    val folders: List<CloudFolderConfig> = emptyList(),
    val recentSearchesCount: Int = 0,
    val recentSearches: List<CloudRecentSearch> = emptyList(),
    val smartPlaylistsCount: Int = 0,
    val smartPlaylists: List<CloudSmartPlaylist> = emptyList(),
    val lyricsOffsetsCount: Int = 0,
    val lyricsOffsets: List<CloudLyricsOffset> = emptyList(),
    val lyricsStoredSeparately: Boolean = false,
    val lyricsSnapshotId: String = "",
    val lyricsCount: Int = 0,
    val playlistPlayEventCount: Int = 0,
    val playlistPlayEventsStoredSeparately: Boolean = false,
    val historyCount: Int = 0,
    val historySample: List<CloudPlaybackEvent> = emptyList(),
    val statistics: BackupStatisticsSummary = BackupStatisticsSummary(),
    val storyCardData: BackupStoryCardData = BackupStoryCardData()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "version" to version,
        "ownerUid" to ownerUid,
        "snapshotComplete" to snapshotComplete,
        "historyStoredSeparately" to historyStoredSeparately,
        "backupTimestamp" to backupTimestamp,
        "backupDateFormatted" to backupDateFormatted,
        "dataHash" to dataHash,
        "profile" to profile.toMap(),
        "settings" to settings.toMap(),
        "favoritesCount" to favoritesCount,
        "favorites" to favorites.map { it.toMap() },
        "playlistsCount" to playlistsCount,
        "playlists" to playlists.map { it.toMap() },
        "foldersCount" to foldersCount,
        "folders" to folders.map { it.toMap() },
        "recentSearchesCount" to recentSearchesCount,
        "recentSearches" to recentSearches.map { it.toMap() },
        "smartPlaylistsCount" to smartPlaylistsCount,
        "smartPlaylists" to smartPlaylists.map { it.toMap() },
        "lyricsOffsetsCount" to lyricsOffsetsCount,
        "lyricsOffsets" to lyricsOffsets.map { it.toMap() },
        "lyricsStoredSeparately" to lyricsStoredSeparately,
        "lyricsSnapshotId" to lyricsSnapshotId,
        "lyricsCount" to lyricsCount,
        "playlistPlayEventCount" to playlistPlayEventCount,
        "playlistPlayEventsStoredSeparately" to playlistPlayEventsStoredSeparately,
        "historyCount" to historyCount,
        "historySample" to historySample.map { it.toMap() },
        "statistics" to statistics.toMap(),
        "storyCardData" to storyCardData.toMap()
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): UserBackupPayload = UserBackupPayload(
            version = (map["version"] as? Number)?.toInt() ?: 1,
            ownerUid = map["ownerUid"] as? String ?: "",
            snapshotComplete = map["snapshotComplete"] as? Boolean ?: false,
            historyStoredSeparately = map["historyStoredSeparately"] as? Boolean ?: false,
            backupTimestamp = (map["backupTimestamp"] as? Number)?.toLong() ?: 0L,
            backupDateFormatted = map["backupDateFormatted"] as? String ?: "",
            dataHash = map["dataHash"] as? String ?: "",
            profile = (map["profile"] as? Map<String, Any?>)?.let { BackupProfileData.fromMap(it) } ?: BackupProfileData(),
            settings = (map["settings"] as? Map<String, Any?>)?.let { CloudUserSettings.fromMap(it) } ?: CloudUserSettings(),
            favoritesCount = (map["favoritesCount"] as? Number)?.toInt() ?: 0,
            favorites = (map["favorites"] as? List<Map<String, Any?>>)?.map { CloudFavoriteSong.fromMap(it) } ?: emptyList(),
            playlistsCount = (map["playlistsCount"] as? Number)?.toInt() ?: 0,
            playlists = (map["playlists"] as? List<Map<String, Any?>>)?.map { CloudPlaylist.fromMap(it) } ?: emptyList(),
            foldersCount = (map["foldersCount"] as? Number)?.toInt() ?: 0,
            folders = (map["folders"] as? List<Map<String, Any?>>)?.map { CloudFolderConfig.fromMap(it) } ?: emptyList(),
            recentSearchesCount = (map["recentSearchesCount"] as? Number)?.toInt() ?: 0,
            recentSearches = (map["recentSearches"] as? List<Map<String, Any?>>)?.map { CloudRecentSearch.fromMap(it) } ?: emptyList(),
            smartPlaylistsCount = (map["smartPlaylistsCount"] as? Number)?.toInt() ?: 0,
            smartPlaylists = (map["smartPlaylists"] as? List<Map<String, Any?>>)?.map { CloudSmartPlaylist.fromMap(it) } ?: emptyList(),
            lyricsOffsetsCount = (map["lyricsOffsetsCount"] as? Number)?.toInt() ?: 0,
            lyricsOffsets = (map["lyricsOffsets"] as? List<Map<String, Any?>>)?.map { CloudLyricsOffset.fromMap(it) } ?: emptyList(),
            lyricsStoredSeparately = map["lyricsStoredSeparately"] as? Boolean ?: false,
            lyricsSnapshotId = map["lyricsSnapshotId"] as? String ?: "",
            lyricsCount = (map["lyricsCount"] as? Number)?.toInt() ?: 0,
            playlistPlayEventCount = (map["playlistPlayEventCount"] as? Number)?.toInt() ?: 0,
            playlistPlayEventsStoredSeparately = map["playlistPlayEventsStoredSeparately"] as? Boolean ?: false,
            historyCount = (map["historyCount"] as? Number)?.toInt() ?: 0,
            historySample = (map["historySample"] as? List<Map<String, Any?>>)?.map { CloudPlaybackEvent.fromMap(it) } ?: emptyList(),
            statistics = (map["statistics"] as? Map<String, Any?>)?.let { BackupStatisticsSummary.fromMap(it) } ?: BackupStatisticsSummary(),
            storyCardData = (map["storyCardData"] as? Map<String, Any?>)?.let { BackupStoryCardData.fromMap(it) } ?: BackupStoryCardData()
        )

        /** Reject incomplete, malformed, or cross-account snapshots before applying any local state. */
        fun isCompleteSnapshot(map: Map<String, Any?>, expectedUid: String): Boolean {
            val version = (map["version"] as? Number)?.toInt() ?: return false
            if (version !in 1..3 || expectedUid.isBlank()) return false
            val profile = map["profile"] as? Map<*, *> ?: return false
            val payloadUid = (map["ownerUid"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: (profile["uid"] as? String).orEmpty()
            if (payloadUid.isNotBlank() && payloadUid != expectedUid) return false
            if (version >= 2 && (map["ownerUid"] != expectedUid || profile["uid"] != expectedUid)) return false
            if (version >= 2 && ((map["snapshotComplete"] as? Boolean) != true || payloadUid != expectedUid)) return false
            if ((map["dataHash"] as? String).isNullOrBlank()) return false
            if (map["settings"] !is Map<*, *>) return false
            if (map["statistics"] !is Map<*, *> || map["storyCardData"] !is Map<*, *>) return false

            fun listCountMatches(field: String, countField: String): Boolean {
                val list = map[field] as? List<*> ?: return false
                val count = (map[countField] as? Number)?.toInt() ?: return false
                return count >= 0 && count == list.size
            }

            if (!listCountMatches("favorites", "favoritesCount") ||
                !listCountMatches("playlists", "playlistsCount") ||
                !listCountMatches("folders", "foldersCount") ||
                !listCountMatches("recentSearches", "recentSearchesCount") ||
                !listCountMatches("smartPlaylists", "smartPlaylistsCount")) return false
            if (version >= 2 && !listCountMatches("lyricsOffsets", "lyricsOffsetsCount")) return false
            if (version >= 2 && ((map["playlistPlayEventCount"] as? Number)?.toInt()?.let { it >= 0 } != true ||
                    (map["playlistPlayEventsStoredSeparately"] as? Boolean) != true)) return false
            if (version >= 3 && ((map["lyricsStoredSeparately"] as? Boolean) != true ||
                    (map["lyricsSnapshotId"] as? String).isNullOrBlank() ||
                    (map["lyricsCount"] as? Number)?.toInt()?.let { it >= 0 } != true)) return false

            fun maps(field: String): List<Map<*, *>>? =
                (map[field] as? List<*>)?.map { it as? Map<*, *> ?: return null }

            val favorites = maps("favorites") ?: return false
            if (favorites.any { (it["songSignature"] as? String).isNullOrBlank() }) return false
            if (favorites.map { it["songSignature"] }.distinct().size != favorites.size) return false

            val playlists = maps("playlists") ?: return false
            if (playlists.any { playlist ->
                    (playlist["name"] as? String).isNullOrBlank() ||
                        (version >= 2 && (playlist["cloudId"] as? String).isNullOrBlank()) ||
                        (playlist["songs"] as? List<*>)?.any { song ->
                            val songMap = song as? Map<*, *> ?: return@any true
                            (songMap["songSignature"] as? String).isNullOrBlank() ||
                                (songMap["position"] as? Number) == null
                        } != false
                }) return false
            if (version >= 2 && playlists.map { it["cloudId"] }.distinct().size != playlists.size) return false

            if (maps("recentSearches")?.any { (it["query"] as? String).isNullOrBlank() } != false) return false
            if (maps("smartPlaylists")?.any { (it["name"] as? String).isNullOrBlank() } != false) return false
            if (version >= 2 && maps("lyricsOffsets")?.any {
                    (it["songSignature"] as? String).isNullOrBlank() || (it["offsetMs"] as? Number) == null
                } != false) return false

            val history = map["historySample"] as? List<*> ?: return false
            val historyCount = (map["historyCount"] as? Number)?.toInt() ?: return false
            if (historyCount < history.size || (version == 1 && historyCount != history.size)) return false
            val historyIds = history.map { event ->
                val eventMap = event as? Map<*, *> ?: return false
                (eventMap["sessionId"] as? String)?.takeIf { it.isNotBlank() } ?: return false
            }
            if (historyIds.distinct().size != historyIds.size) return false
            if (version >= 2 && (map["historyStoredSeparately"] as? Boolean) != true) return false
            return true
        }
    }
}
