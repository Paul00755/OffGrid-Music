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
    val customAvatarUri: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "uid" to uid,
        "email" to email,
        "displayName" to displayName,
        "photoUrl" to photoUrl,
        "customDisplayName" to customDisplayName,
        "customAvatarUri" to customAvatarUri
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): BackupProfileData = BackupProfileData(
            uid = map["uid"] as? String ?: "",
            email = map["email"] as? String,
            displayName = map["displayName"] as? String,
            photoUrl = map["photoUrl"] as? String,
            customDisplayName = map["customDisplayName"] as? String,
            customAvatarUri = map["customAvatarUri"] as? String
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
    val timeOfDayDistribution: Map<String, Long> = emptyMap(),
    val topArtistsSummary: List<String> = emptyList()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "totalPlayCount" to totalPlayCount,
        "totalListeningTimeMs" to totalListeningTimeMs,
        "uniqueTracksPlayed" to uniqueTracksPlayed,
        "mostPlayedArtist" to mostPlayedArtist,
        "mostPlayedAlbum" to mostPlayedAlbum,
        "timeOfDayDistribution" to timeOfDayDistribution,
        "topArtistsSummary" to topArtistsSummary
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): BackupStatisticsSummary = BackupStatisticsSummary(
            totalPlayCount = (map["totalPlayCount"] as? Number)?.toLong() ?: 0L,
            totalListeningTimeMs = (map["totalListeningTimeMs"] as? Number)?.toLong() ?: 0L,
            uniqueTracksPlayed = (map["uniqueTracksPlayed"] as? Number)?.toInt() ?: 0,
            mostPlayedArtist = map["mostPlayedArtist"] as? String,
            mostPlayedAlbum = map["mostPlayedAlbum"] as? String,
            timeOfDayDistribution = (map["timeOfDayDistribution"] as? Map<String, Any?>)?.mapValues {
                (it.value as? Number)?.toLong() ?: 0L
            } ?: emptyMap(),
            topArtistsSummary = (map["topArtistsSummary"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
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
    val version: Int = 1,
    val backupTimestamp: Long = System.currentTimeMillis(),
    val backupDateFormatted: String = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(backupTimestamp)),
    val dataHash: String = "",
    val profile: BackupProfileData = BackupProfileData(),
    val settings: CloudUserSettings = CloudUserSettings(),
    val favoritesCount: Int = 0,
    val favorites: List<CloudFavoriteSong> = emptyList(),
    val playlistsCount: Int = 0,
    val playlists: List<CloudPlaylist> = emptyList(),
    val historyCount: Int = 0,
    val historySample: List<CloudPlaybackEvent> = emptyList(),
    val statistics: BackupStatisticsSummary = BackupStatisticsSummary(),
    val storyCardData: BackupStoryCardData = BackupStoryCardData()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "version" to version,
        "backupTimestamp" to backupTimestamp,
        "backupDateFormatted" to backupDateFormatted,
        "dataHash" to dataHash,
        "profile" to profile.toMap(),
        "settings" to settings.toMap(),
        "favoritesCount" to favoritesCount,
        "favorites" to favorites.map { it.toMap() },
        "playlistsCount" to playlistsCount,
        "playlists" to playlists.map { it.toMap() },
        "historyCount" to historyCount,
        "historySample" to historySample.map { it.toMap() },
        "statistics" to statistics.toMap(),
        "storyCardData" to storyCardData.toMap()
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): UserBackupPayload = UserBackupPayload(
            version = (map["version"] as? Number)?.toInt() ?: 1,
            backupTimestamp = (map["backupTimestamp"] as? Number)?.toLong() ?: 0L,
            backupDateFormatted = map["backupDateFormatted"] as? String ?: "",
            dataHash = map["dataHash"] as? String ?: "",
            profile = (map["profile"] as? Map<String, Any?>)?.let { BackupProfileData.fromMap(it) } ?: BackupProfileData(),
            settings = (map["settings"] as? Map<String, Any?>)?.let { CloudUserSettings.fromMap(it) } ?: CloudUserSettings(),
            favoritesCount = (map["favoritesCount"] as? Number)?.toInt() ?: 0,
            favorites = (map["favorites"] as? List<Map<String, Any?>>)?.map { CloudFavoriteSong.fromMap(it) } ?: emptyList(),
            playlistsCount = (map["playlistsCount"] as? Number)?.toInt() ?: 0,
            playlists = (map["playlists"] as? List<Map<String, Any?>>)?.map { CloudPlaylist.fromMap(it) } ?: emptyList(),
            historyCount = (map["historyCount"] as? Number)?.toInt() ?: 0,
            historySample = (map["historySample"] as? List<Map<String, Any?>>)?.map { CloudPlaybackEvent.fromMap(it) } ?: emptyList(),
            statistics = (map["statistics"] as? Map<String, Any?>)?.let { BackupStatisticsSummary.fromMap(it) } ?: BackupStatisticsSummary(),
            storyCardData = (map["storyCardData"] as? Map<String, Any?>)?.let { BackupStoryCardData.fromMap(it) } ?: BackupStoryCardData()
        )
    }
}
