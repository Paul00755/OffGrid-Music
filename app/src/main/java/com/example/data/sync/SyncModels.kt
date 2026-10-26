package com.example.data.sync

data class CloudUserSettings(
    val themeMode: String = "DARK",
    val uiTheme: String = "MIDNIGHT_OBSIDIAN",
    val homeScreenLayout: String = "BENTO_DYNAMIC",
    val nowPlayingLayout: String = "MINIMAL_GLASS",
    val playlistsLayout: String = "GLOW_CARDS",
    val accentColorPreset: String = "ORANGE",
    val customAccentColor: Int = 0,
    val dynamicArtworkTheme: Boolean = true,
    val amoledMode: Boolean = false,
    val cornerRadiusDp: Int = 16,
    val fontSizeScale: String = "MEDIUM",
    val artworkSize: String = "MEDIUM",
    val showArtist: Boolean = true,
    val showAlbum: Boolean = true,
    val showAudioOutput: Boolean = true,
    val miniPlayerStyle: String = "FLOATING",
    val miniPlayerShowPrevious: Boolean = true,
    val miniPlayerShowNext: Boolean = true,
    val miniPlayerShowFavorite: Boolean = true,
    val waveformStyle: String = "SNAKE",
    val lyricsFetchMode: String = "WIFI_ONLY",
    val lyricsAutoScroll: Boolean = true,
    val lyricsFontSize: String = "MEDIUM",
    val gaplessPlayback: Boolean = true,
    val crossfadeDurationSec: Int = 0,
    val normalizeAudio: Boolean = false,
    val autoPlayOnHeadphoneConnection: Boolean = false,
    val pauseOnZeroVolume: Boolean = true,
    val autoResumeOnVolumeIncrease: Boolean = true,
    val fadeInOutMs: Int = 1000,
    val fadeInMs: Int = 1000,
    val fadeOutMs: Int = 1000,
    val shakeToSkip: Boolean = false,
    val keepScreenAwake: Boolean = false,
    val audioBalance: Float = 0f,
    val equalizerPreset: String = "NORMAL",
    val customEqBands: String = "0.0,0.0,0.0,0.0,0.0",
    val customBassBoost: Int = 0,
    val customVirtualizer: Int = 0,
    val reduceVolumeOnNotification: Boolean = true,
    // Mirrors UserPreferences: absent means pause during camera/system audio focus loss.
    val playDuringCamera: Boolean = false,
    val scrobbleThresholdType: String = "SECONDS",
    val scrobbleThresholdValue: Int = 30,
    val repeatMode: Int = 0,
    val shuffleMode: Boolean = false,
    val alwaysShuffleMode: Boolean = false,
    val gesturePlaybackEnabled: Boolean = true,
    val highRefreshRate: Boolean = true,
    val addToQueuePosition: String = "BOTTOM",
    val playlistAddSongPosition: String = "BOTTOM",
    val playlistViewMode: String = "GRID",
    val homeSections: String = "continue_listening,quick_actions,listening_stats,most_played,most_played_playlists,recently_played,recently_added,top_artists,top_albums",
    val databaseStorageTier: String = "EXPANDED",
    val customDisplayName: String? = null,
    val customAvatarUri: String? = null,
    val avatarBase64: String? = null,
    val photoUrl: String? = null,
    val ignoreAudioDuration: Int = 0,
    val librarySortOption: String = "RECENTLY_ADDED",
    val librarySortAscending: Boolean = true,
    val sleepTimerMode: String = "TIME",
    val resumeOnBluetooth: Boolean = false,
    val pauseOnHeadsetUnplug: Boolean = true,
    val notificationEnabled: Boolean = true,
    val autoplayOnAppStartup: Boolean = false,
    val fetchOnlineArtworkHighRes: Boolean = true,
    val cacheLyricsEnabled: Boolean = true,
    val cacheAlbumArtEnabled: Boolean = true,
    val cacheSongArtWorkEnabled: Boolean = true,
    val transcribeTranslateEnabled: Boolean = false,
    val audioTranscribeMode: String = "OFF",
    val audioTranscribeTargetLang: String = "ta",
    val automaticBackupEnabled: Boolean = false,
    val backupFrequency: String = "WEEKLY",
    val updatedAt: Long = 0L,
    val clientVersion: Int = 1
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "themeMode" to themeMode,
        "uiTheme" to uiTheme,
        "homeScreenLayout" to homeScreenLayout,
        "nowPlayingLayout" to nowPlayingLayout,
        "playlistsLayout" to playlistsLayout,
        "accentColorPreset" to accentColorPreset,
        "customAccentColor" to customAccentColor,
        "dynamicArtworkTheme" to dynamicArtworkTheme,
        "amoledMode" to amoledMode,
        "cornerRadiusDp" to cornerRadiusDp,
        "fontSizeScale" to fontSizeScale,
        "artworkSize" to artworkSize,
        "showArtist" to showArtist,
        "showAlbum" to showAlbum,
        "showAudioOutput" to showAudioOutput,
        "miniPlayerStyle" to miniPlayerStyle,
        "miniPlayerShowPrevious" to miniPlayerShowPrevious,
        "miniPlayerShowNext" to miniPlayerShowNext,
        "miniPlayerShowFavorite" to miniPlayerShowFavorite,
        "waveformStyle" to waveformStyle,
        "lyricsFetchMode" to lyricsFetchMode,
        "lyricsAutoScroll" to lyricsAutoScroll,
        "lyricsFontSize" to lyricsFontSize,
        "gaplessPlayback" to gaplessPlayback,
        "crossfadeDurationSec" to crossfadeDurationSec,
        "normalizeAudio" to normalizeAudio,
        "autoPlayOnHeadphoneConnection" to autoPlayOnHeadphoneConnection,
        "pauseOnZeroVolume" to pauseOnZeroVolume,
        "autoResumeOnVolumeIncrease" to autoResumeOnVolumeIncrease,
        "fadeInOutMs" to fadeInOutMs,
        "fadeInMs" to fadeInMs,
        "fadeOutMs" to fadeOutMs,
        "shakeToSkip" to shakeToSkip,
        "keepScreenAwake" to keepScreenAwake,
        "audioBalance" to audioBalance,
        "equalizerPreset" to equalizerPreset,
        "customEqBands" to customEqBands,
        "customBassBoost" to customBassBoost,
        "customVirtualizer" to customVirtualizer,
        "reduceVolumeOnNotification" to reduceVolumeOnNotification,
        "playDuringCamera" to playDuringCamera,
        "scrobbleThresholdType" to scrobbleThresholdType,
        "scrobbleThresholdValue" to scrobbleThresholdValue,
        "repeatMode" to repeatMode,
        "shuffleMode" to shuffleMode,
        "alwaysShuffleMode" to alwaysShuffleMode,
        "gesturePlaybackEnabled" to gesturePlaybackEnabled,
        "highRefreshRate" to highRefreshRate,
        "addToQueuePosition" to addToQueuePosition,
        "playlistAddSongPosition" to playlistAddSongPosition,
        "playlistViewMode" to playlistViewMode,
        "homeSections" to homeSections,
        "databaseStorageTier" to databaseStorageTier,
        "customDisplayName" to customDisplayName,
        "customAvatarUri" to customAvatarUri,
        "avatarBase64" to avatarBase64,
        "photoUrl" to photoUrl,
        "ignoreAudioDuration" to ignoreAudioDuration,
        "librarySortOption" to librarySortOption,
        "librarySortAscending" to librarySortAscending,
        "sleepTimerMode" to sleepTimerMode,
        "resumeOnBluetooth" to resumeOnBluetooth,
        "pauseOnHeadsetUnplug" to pauseOnHeadsetUnplug,
        "notificationEnabled" to notificationEnabled,
        "autoplayOnAppStartup" to autoplayOnAppStartup,
        "fetchOnlineArtworkHighRes" to fetchOnlineArtworkHighRes,
        "cacheLyricsEnabled" to cacheLyricsEnabled,
        "cacheAlbumArtEnabled" to cacheAlbumArtEnabled,
        "cacheSongArtWorkEnabled" to cacheSongArtWorkEnabled,
        "transcribeTranslateEnabled" to transcribeTranslateEnabled,
        "audioTranscribeMode" to audioTranscribeMode,
        "audioTranscribeTargetLang" to audioTranscribeTargetLang,
        "automaticBackupEnabled" to automaticBackupEnabled,
        "backupFrequency" to backupFrequency,
        "updatedAt" to updatedAt,
        "clientVersion" to clientVersion
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): CloudUserSettings = CloudUserSettings(
            themeMode = map["themeMode"] as? String ?: "DARK",
            uiTheme = map["uiTheme"] as? String ?: "MIDNIGHT_OBSIDIAN",
            homeScreenLayout = map["homeScreenLayout"] as? String ?: "BENTO_DYNAMIC",
            nowPlayingLayout = map["nowPlayingLayout"] as? String ?: "MINIMAL_GLASS",
            playlistsLayout = map["playlistsLayout"] as? String ?: "GLOW_CARDS",
            accentColorPreset = map["accentColorPreset"] as? String ?: "ORANGE",
            customAccentColor = (map["customAccentColor"] as? Number)?.toInt() ?: 0,
            dynamicArtworkTheme = map["dynamicArtworkTheme"] as? Boolean ?: true,
            amoledMode = map["amoledMode"] as? Boolean ?: false,
            cornerRadiusDp = (map["cornerRadiusDp"] as? Number)?.toInt() ?: 16,
            fontSizeScale = map["fontSizeScale"] as? String ?: "MEDIUM",
            artworkSize = map["artworkSize"] as? String ?: "MEDIUM",
            showArtist = map["showArtist"] as? Boolean ?: true,
            showAlbum = map["showAlbum"] as? Boolean ?: true,
            showAudioOutput = map["showAudioOutput"] as? Boolean ?: true,
            miniPlayerStyle = map["miniPlayerStyle"] as? String ?: "FLOATING",
            miniPlayerShowPrevious = map["miniPlayerShowPrevious"] as? Boolean ?: true,
            miniPlayerShowNext = map["miniPlayerShowNext"] as? Boolean ?: true,
            miniPlayerShowFavorite = map["miniPlayerShowFavorite"] as? Boolean ?: true,
            waveformStyle = map["waveformStyle"] as? String ?: "SNAKE",
            lyricsFetchMode = map["lyricsFetchMode"] as? String ?: "WIFI_ONLY",
            lyricsAutoScroll = map["lyricsAutoScroll"] as? Boolean ?: true,
            lyricsFontSize = map["lyricsFontSize"] as? String ?: "MEDIUM",
            gaplessPlayback = map["gaplessPlayback"] as? Boolean ?: true,
            crossfadeDurationSec = (map["crossfadeDurationSec"] as? Number)?.toInt() ?: 0,
            normalizeAudio = map["normalizeAudio"] as? Boolean ?: false,
            autoPlayOnHeadphoneConnection = map["autoPlayOnHeadphoneConnection"] as? Boolean ?: false,
            pauseOnZeroVolume = map["pauseOnZeroVolume"] as? Boolean ?: true,
            autoResumeOnVolumeIncrease = map["autoResumeOnVolumeIncrease"] as? Boolean ?: true,
            fadeInOutMs = (map["fadeInOutMs"] as? Number)?.toInt() ?: 1000,
            fadeInMs = (map["fadeInMs"] as? Number)?.toInt() ?: 1000,
            fadeOutMs = (map["fadeOutMs"] as? Number)?.toInt() ?: 1000,
            shakeToSkip = map["shakeToSkip"] as? Boolean ?: false,
            keepScreenAwake = map["keepScreenAwake"] as? Boolean ?: false,
            audioBalance = (map["audioBalance"] as? Number)?.toFloat() ?: 0f,
            equalizerPreset = map["equalizerPreset"] as? String ?: "NORMAL",
            customEqBands = map["customEqBands"] as? String ?: "0.0,0.0,0.0,0.0,0.0",
            customBassBoost = (map["customBassBoost"] as? Number)?.toInt() ?: 0,
            customVirtualizer = (map["customVirtualizer"] as? Number)?.toInt() ?: 0,
            reduceVolumeOnNotification = map["reduceVolumeOnNotification"] as? Boolean ?: true,
            playDuringCamera = map["playDuringCamera"] as? Boolean ?: false,
            scrobbleThresholdType = map["scrobbleThresholdType"] as? String ?: "SECONDS",
            scrobbleThresholdValue = (map["scrobbleThresholdValue"] as? Number)?.toInt() ?: 30,
            repeatMode = (map["repeatMode"] as? Number)?.toInt() ?: 0,
            shuffleMode = map["shuffleMode"] as? Boolean ?: false,
            alwaysShuffleMode = map["alwaysShuffleMode"] as? Boolean ?: false,
            gesturePlaybackEnabled = map["gesturePlaybackEnabled"] as? Boolean ?: true,
            highRefreshRate = map["highRefreshRate"] as? Boolean ?: true,
            addToQueuePosition = map["addToQueuePosition"] as? String ?: "BOTTOM",
            playlistAddSongPosition = map["playlistAddSongPosition"] as? String ?: "BOTTOM",
            playlistViewMode = map["playlistViewMode"] as? String ?: "GRID",
            homeSections = map["homeSections"] as? String ?: "continue_listening,quick_actions,listening_stats,most_played,most_played_playlists,recently_played,recently_added,top_artists,top_albums",
            databaseStorageTier = map["databaseStorageTier"] as? String ?: "EXPANDED",
            customDisplayName = map["customDisplayName"] as? String,
            customAvatarUri = map["customAvatarUri"] as? String,
            avatarBase64 = map["avatarBase64"] as? String,
            photoUrl = map["photoUrl"] as? String,
            ignoreAudioDuration = (map["ignoreAudioDuration"] as? Number)?.toInt() ?: 0,
            librarySortOption = map["librarySortOption"] as? String ?: "RECENTLY_ADDED",
            librarySortAscending = map["librarySortAscending"] as? Boolean ?: true,
            sleepTimerMode = map["sleepTimerMode"] as? String ?: "TIME",
            resumeOnBluetooth = map["resumeOnBluetooth"] as? Boolean ?: false,
            pauseOnHeadsetUnplug = map["pauseOnHeadsetUnplug"] as? Boolean ?: true,
            notificationEnabled = map["notificationEnabled"] as? Boolean ?: true,
            autoplayOnAppStartup = map["autoplayOnAppStartup"] as? Boolean ?: false,
            fetchOnlineArtworkHighRes = map["fetchOnlineArtworkHighRes"] as? Boolean ?: true,
            cacheLyricsEnabled = map["cacheLyricsEnabled"] as? Boolean ?: true,
            cacheAlbumArtEnabled = map["cacheAlbumArtEnabled"] as? Boolean ?: true,
            cacheSongArtWorkEnabled = map["cacheSongArtWorkEnabled"] as? Boolean ?: true,
            transcribeTranslateEnabled = map["transcribeTranslateEnabled"] as? Boolean ?: false,
            audioTranscribeMode = map["audioTranscribeMode"] as? String ?: "OFF",
            audioTranscribeTargetLang = map["audioTranscribeTargetLang"] as? String ?: "ta",
            automaticBackupEnabled = map["automaticBackupEnabled"] as? Boolean ?: false,
            backupFrequency = map["backupFrequency"] as? String ?: "WEEKLY",
            updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L,
            clientVersion = (map["clientVersion"] as? Number)?.toInt() ?: 1
        )
    }
}

data class CloudFavoriteSong(
    val songSignature: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val relativeFilename: String = "",
    val isFavorite: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "songSignature" to songSignature,
        "title" to title,
        "artist" to artist,
        "album" to album,
        "durationMs" to durationMs,
        "relativeFilename" to relativeFilename,
        "isFavorite" to isFavorite,
        "timestamp" to timestamp
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): CloudFavoriteSong = CloudFavoriteSong(
            songSignature = map["songSignature"] as? String ?: "",
            title = map["title"] as? String ?: "",
            artist = map["artist"] as? String ?: "",
            album = map["album"] as? String ?: "",
            durationMs = (map["durationMs"] as? Number)?.toLong() ?: 0L,
            relativeFilename = map["relativeFilename"] as? String ?: "",
            isFavorite = map["isFavorite"] as? Boolean ?: true,
            timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L
        )
    }
}

data class CloudPlaylistSongItem(
    val position: Int = 0,
    val songSignature: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val filename: String = ""
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "position" to position,
        "songSignature" to songSignature,
        "title" to title,
        "artist" to artist,
        "album" to album,
        "durationMs" to durationMs,
        "filename" to filename
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): CloudPlaylistSongItem = CloudPlaylistSongItem(
            position = (map["position"] as? Number)?.toInt() ?: 0,
            songSignature = map["songSignature"] as? String ?: "",
            title = map["title"] as? String ?: "",
            artist = map["artist"] as? String ?: "",
            album = map["album"] as? String ?: "",
            durationMs = (map["durationMs"] as? Number)?.toLong() ?: 0L,
            filename = map["filename"] as? String ?: ""
        )
    }
}

data class CloudPlaylist(
    val cloudId: String = "",
    val name: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val artworkUri: String? = null,
    val songs: List<CloudPlaylistSongItem> = emptyList()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "cloudId" to cloudId,
        "name" to name,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "isPinned" to isPinned,
        "artworkUri" to artworkUri,
        "songs" to songs.map { it.toMap() }
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): CloudPlaylist {
            val songsRaw = (map["songs"] as? List<Map<String, Any?>>) ?: emptyList()
            val songItems = songsRaw.map { CloudPlaylistSongItem.fromMap(it) }
            return CloudPlaylist(
                cloudId = map["cloudId"] as? String ?: "",
                name = map["name"] as? String ?: "",
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L,
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L,
                isPinned = map["isPinned"] as? Boolean ?: false,
                artworkUri = map["artworkUri"] as? String,
                songs = songItems
            )
        }
    }
}

data class CloudPlaybackEvent(
    val sessionId: String = "",
    val songSignature: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val listenedDurationMs: Long = 0L,
    val sourceContext: String = "UNKNOWN",
    val completionPercentage: Float = 0f,
    val isQualified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "sessionId" to sessionId,
        "songSignature" to songSignature,
        "title" to title,
        "artist" to artist,
        "album" to album,
        "durationMs" to durationMs,
        "startedAt" to startedAt,
        "endedAt" to endedAt,
        "listenedDurationMs" to listenedDurationMs,
        "sourceContext" to sourceContext,
        "completionPercentage" to completionPercentage,
        "isQualified" to isQualified,
        "createdAt" to createdAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): CloudPlaybackEvent = CloudPlaybackEvent(
            sessionId = map["sessionId"] as? String ?: "",
            songSignature = map["songSignature"] as? String ?: "",
            title = map["title"] as? String ?: "",
            artist = map["artist"] as? String ?: "",
            album = map["album"] as? String ?: "",
            durationMs = (map["durationMs"] as? Number)?.toLong() ?: 0L,
            startedAt = (map["startedAt"] as? Number)?.toLong() ?: 0L,
            endedAt = (map["endedAt"] as? Number)?.toLong() ?: 0L,
            listenedDurationMs = (map["listenedDurationMs"] as? Number)?.toLong() ?: 0L,
            sourceContext = map["sourceContext"] as? String ?: "UNKNOWN",
            completionPercentage = (map["completionPercentage"] as? Number)?.toFloat() ?: 0f,
            isQualified = map["isQualified"] as? Boolean ?: false,
            createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L
        )
    }
}

data class CloudLyricsOffset(
    val songSignature: String = "",
    val offsetMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "songSignature" to songSignature,
        "offsetMs" to offsetMs,
        "updatedAt" to updatedAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): CloudLyricsOffset = CloudLyricsOffset(
            songSignature = map["songSignature"] as? String ?: "",
            offsetMs = (map["offsetMs"] as? Number)?.toLong() ?: 0L,
            updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L
        )
    }
}

/** Stable playlist-play history record; Room playlist IDs are never sent to Firestore. */
data class CloudPlaylistPlayEvent(
    val eventId: String = "",
    val playlistCloudId: String = "",
    val timestamp: Long = 0L
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "eventId" to eventId,
        "playlistCloudId" to playlistCloudId,
        "timestamp" to timestamp
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): CloudPlaylistPlayEvent = CloudPlaylistPlayEvent(
            eventId = map["eventId"] as? String ?: "",
            playlistCloudId = map["playlistCloudId"] as? String ?: "",
            timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L
        )
    }
}

data class CloudFolderConfig(
    val path: String = "",
    val name: String = "",
    val uriString: String = "",
    val isExcluded: Boolean = false,
    val songCount: Int = 0
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "path" to path,
        "name" to name,
        "uriString" to uriString,
        "isExcluded" to isExcluded,
        "songCount" to songCount
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): CloudFolderConfig = CloudFolderConfig(
            path = map["path"] as? String ?: "",
            name = map["name"] as? String ?: "",
            uriString = map["uriString"] as? String ?: "",
            isExcluded = map["isExcluded"] as? Boolean ?: false,
            songCount = (map["songCount"] as? Number)?.toInt() ?: 0
        )
    }
}

data class CloudRecentSearch(
    val query: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "query" to query,
        "timestamp" to timestamp
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): CloudRecentSearch = CloudRecentSearch(
            query = map["query"] as? String ?: "",
            timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L
        )
    }
}

data class CloudSmartPlaylist(
    val name: String = "",
    val filterType: String = "ALL",
    val rulesJson: String = "{}",
    val sortOrder: String = "DESC",
    val limit: Int = 50,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "name" to name,
        "filterType" to filterType,
        "rulesJson" to rulesJson,
        "sortOrder" to sortOrder,
        "limit" to limit,
        "createdAt" to createdAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): CloudSmartPlaylist = CloudSmartPlaylist(
            name = map["name"] as? String ?: "",
            filterType = map["filterType"] as? String ?: "ALL",
            rulesJson = map["rulesJson"] as? String ?: "{}",
            sortOrder = map["sortOrder"] as? String ?: "DESC",
            limit = (map["limit"] as? Number)?.toInt() ?: 50,
            createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L
        )
    }
}

sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    data class Synced(val timestamp: Long) : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}
