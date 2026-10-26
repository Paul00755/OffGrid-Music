package com.example.data.local

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class UserPreferencesRepository(private val context: Context) {

    private val safeData: Flow<Preferences> = context.dataStore.data.catch {
        emit(emptyPreferences())
    }

    private object PreferenceKeys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val THEME_MODE = stringPreferencesKey("theme_mode") // "DARK", "LIGHT", "SYSTEM"
        val UI_THEME = stringPreferencesKey("ui_theme") // "MIDNIGHT_OBSIDIAN", "PURE_AMOLED", "CYBERPUNK_NEON", etc.
        val HOME_SCREEN_LAYOUT = stringPreferencesKey("home_screen_layout") // "BENTO_DYNAMIC", "HERO_SPOTLIGHT", "MINIMAL_FEED", "STUDIO_DASHBOARD"
        val NOW_PLAYING_LAYOUT = stringPreferencesKey("now_playing_layout") // "MODERN_BENTO", "VINYL_TURNTABLE", "MINIMAL_GLASS", "NEON_WAVEFORM"
        val PLAYLISTS_LAYOUT = stringPreferencesKey("playlists_layout") // "BENTO_GRID", "MAGAZINE_HERO", "COMPACT_LIST", "GLOW_CARDS"
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val CUSTOM_ACCENT_COLOR = intPreferencesKey("custom_accent_color")
        val DISPLAY_SCALE = stringPreferencesKey("display_scale") // "DEFAULT", "COMPACT", "COMFORT"
        val DYNAMIC_ARTWORK_THEME = booleanPreferencesKey("dynamic_artwork_theme")
        val LYRICS_FETCH_MODE = stringPreferencesKey("lyrics_fetch_mode") // "ALWAYS", "WIFI_ONLY", "OFF"
        val SLEEP_TIMER_MODE = stringPreferencesKey("sleep_timer_mode") // "TIME", "COUNT", "BOTH"
        val RESUME_ON_BLUETOOTH = booleanPreferencesKey("resume_on_bluetooth")
        val PAUSE_ON_HEADSET_UNPLUG = booleanPreferencesKey("pause_on_headset_unplug")
        val REPEAT_MODE = intPreferencesKey("repeat_mode") // 0=OFF, 1=ALL, 2=ONE
        val SHUFFLE_MODE = booleanPreferencesKey("shuffle_mode")
        val LAST_SONG_ID = longPreferencesKey("last_song_id")
        val LAST_POSITION = longPreferencesKey("last_position")
        val LAST_DURATION = longPreferencesKey("last_duration")
        val LAST_QUEUE_IDS = stringPreferencesKey("last_queue_ids")
        val LAST_QUEUE_INDEX = intPreferencesKey("last_queue_index")
        val ADD_TO_QUEUE_POSITION = stringPreferencesKey("add_to_queue_position") // "BOTTOM" or "TOP_NEXT"
        val PLAYLIST_ADD_SONG_POSITION = stringPreferencesKey("playlist_add_song_position") // "BOTTOM" or "TOP"
        val PLAYLIST_VIEW_MODE = stringPreferencesKey("playlist_view_mode") // "GRID" or "LIST"

        // Expanded Customization Settings
        val WAVEFORM_STYLE = stringPreferencesKey("waveform_style") // "SNAKE", "CLASSIC", "SMOOTH_WAVE", "BARS", "DOTS", "MINIMAL"
        val AMOLED_MODE = booleanPreferencesKey("amoled_mode") // Extra Dark Mode
        val ACCENT_COLOR_PRESET = stringPreferencesKey("accent_color_preset") // "PURPLE", "PINK", "CYAN", "GREEN", "ORANGE"
        val CORNER_RADIUS_DP = intPreferencesKey("corner_radius_dp") // 12, 16, 20, 24
        val FONT_SIZE_SCALE = stringPreferencesKey("font_size_scale") // "SMALL", "MEDIUM", "LARGE"
        val ARTWORK_SIZE = stringPreferencesKey("artwork_size") // "SMALL", "MEDIUM", "LARGE"
        val SHOW_ARTIST = booleanPreferencesKey("show_artist")
        val SHOW_ALBUM = booleanPreferencesKey("show_album")
        val SHOW_AUDIO_OUTPUT = booleanPreferencesKey("show_audio_output")
        val MINI_PLAYER_STYLE = stringPreferencesKey("mini_player_style") // "FLOATING", "DOCKED", "MINIMAL"
        val MINI_PLAYER_SHOW_PREVIOUS = booleanPreferencesKey("mini_player_show_previous")
        val MINI_PLAYER_SHOW_NEXT = booleanPreferencesKey("mini_player_show_next")
        val MINI_PLAYER_SHOW_FAVORITE = booleanPreferencesKey("mini_player_show_favorite")
        val GAPLESS_PLAYBACK = booleanPreferencesKey("gapless_playback")
        val CROSSFADE_DURATION_SEC = intPreferencesKey("crossfade_duration_sec") // 0..12
        val LYRICS_AUTO_SCROLL = booleanPreferencesKey("lyrics_auto_scroll")
        val LYRICS_FONT_SIZE = stringPreferencesKey("lyrics_font_size") // "SMALL", "MEDIUM", "LARGE"
        val NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")

        // Audio & Playback Enhancements
        val PAUSE_ON_ZERO_VOLUME = booleanPreferencesKey("pause_on_zero_volume")
        val AUTO_RESUME_ON_VOLUME_INCREASE = booleanPreferencesKey("auto_resume_on_volume_increase")
        val IGNORE_AUDIO_DURATION = intPreferencesKey("ignore_audio_duration") // Minimum duration in seconds
        val MAX_SONG_DURATION = intPreferencesKey("max_song_duration_sec") // Maximum duration in seconds (default 300)
        val FULL_DEVICE_SCAN = booleanPreferencesKey("full_device_scan") // Whether to scan whole device or selected folders
        val FADE_IN_OUT_MS = intPreferencesKey("fade_in_out_ms") // 0..5000 ms (legacy)
        val FADE_IN_MS = intPreferencesKey("fade_in_ms") // 0..5000 ms
        val FADE_OUT_MS = intPreferencesKey("fade_out_ms") // 0..5000 ms
        val SHAKE_TO_SKIP = booleanPreferencesKey("shake_to_skip")
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val AUDIO_BALANCE = floatPreferencesKey("audio_balance") // -1.0f .. 1.0f
        val EQUALIZER_PRESET = stringPreferencesKey("equalizer_preset") // "NORMAL", "BASS_BOOST", "TREBLE_BOOST", "VOCAL", "ROCK", "POP"
        val REDUCE_VOLUME_ON_NOTIFICATION = booleanPreferencesKey("reduce_volume_on_notification")
        val PLAY_DURING_CAMERA = booleanPreferencesKey("play_during_camera")
        val SCROBBLE_THRESHOLD_TYPE = stringPreferencesKey("scrobble_threshold_type") // "SECONDS" or "PERCENTAGE"
        val SCROBBLE_THRESHOLD_VALUE = intPreferencesKey("scrobble_threshold_value") // e.g. 30 (sec or %)
        val HIGH_REFRESH_RATE = booleanPreferencesKey("high_refresh_rate")
        val ALWAYS_SHUFFLE_MODE = booleanPreferencesKey("always_shuffle_mode")
        val AUTOPLAY_ON_APP_STARTUP = booleanPreferencesKey("autoplay_on_app_startup")
        val GESTURE_PLAYBACK_ENABLED = booleanPreferencesKey("gesture_playback_enabled")
        val FETCH_ONLINE_ARTWORK_HIGH_RES = booleanPreferencesKey("fetch_online_artwork_high_res")

        // Offline Caching Preferences (Separate Toggles for Offline Playback)
        val CACHE_LYRICS_ENABLED = booleanPreferencesKey("cache_lyrics_enabled")
        val CACHE_ALBUM_ART_ENABLED = booleanPreferencesKey("cache_album_art_enabled")
        val CACHE_SONG_ART_WORK_ENABLED = booleanPreferencesKey("cache_song_art_work_enabled")

        // Custom Equalizer Settings
        val CUSTOM_EQ_BANDS = stringPreferencesKey("custom_eq_bands")
        val CUSTOM_BASS_BOOST = intPreferencesKey("custom_bass_boost")
        val CUSTOM_VIRTUALIZER = intPreferencesKey("custom_virtualizer")

        // HomeScreen Customization
        val HOME_SECTIONS = stringPreferencesKey("home_sections")

        // Normalize Audio
        val NORMALIZE_AUDIO = booleanPreferencesKey("normalize_audio")

        // Auto Play on Headphone Connection
        val AUTO_PLAY_ON_HEADPHONE_CONNECTION = booleanPreferencesKey("auto_play_on_headphone_connection")

        // Database Storage Tier ("STANDARD", "EXPANDED", "MAXIMUM")
        val DATABASE_STORAGE_TIER = stringPreferencesKey("database_storage_tier")

        // User Account & Profile Preferences
        val SKIP_AUTH_PROMPT = booleanPreferencesKey("skip_auth_prompt")
        val LAST_ACTIVE_UID = stringPreferencesKey("last_active_uid")
        val CUSTOM_DISPLAY_NAME = stringPreferencesKey("custom_display_name")
        val CUSTOM_AVATAR_URI = stringPreferencesKey("custom_avatar_uri")
        val SETTINGS_LAST_UPDATED_AT = longPreferencesKey("settings_last_updated_at")

        // Cloud Backup & Sync Scheduling
        val AUTOMATIC_BACKUP_ENABLED = booleanPreferencesKey("automatic_backup_enabled")
        val BACKUP_FREQUENCY = stringPreferencesKey("backup_frequency") // "DAILY", "WEEKLY", "MONTHLY"
        val LAST_SYNCED_TIMESTAMP = longPreferencesKey("last_synced_timestamp")
        val LAST_BACKUP_TIMESTAMP = longPreferencesKey("last_backup_timestamp")
        val LAST_BACKUP_DATA_HASH = stringPreferencesKey("last_backup_data_hash")

        // Audio & Lyrics Transcription / Translation
        val TRANSCRIBE_TRANSLATE_ENABLED = booleanPreferencesKey("transcribe_translate_enabled")
        val AUDIO_TRANSCRIBE_MODE = stringPreferencesKey("audio_transcribe_mode") // "OFF", "TRANSCRIBE", "TRANSLATE"
        val AUDIO_TRANSCRIBE_TARGET_LANG = stringPreferencesKey("audio_transcribe_target_lang") // "en", "ta", "hi", "ml"
    }

    val isTranscribeTranslateEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.TRANSCRIBE_TRANSLATE_ENABLED] ?: false
    }

    val audioTranscribeMode: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUDIO_TRANSCRIBE_MODE] ?: "OFF"
    }

    val audioTranscribeTargetLang: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUDIO_TRANSCRIBE_TARGET_LANG] ?: "ta"
    }

    suspend fun setTranscribeTranslateEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.TRANSCRIBE_TRANSLATE_ENABLED] = enabled
        }
    }

    suspend fun setAudioTranscribeMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.AUDIO_TRANSCRIBE_MODE] = mode
        }
    }

    suspend fun setAudioTranscribeTargetLang(langCode: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.AUDIO_TRANSCRIBE_TARGET_LANG] = langCode
        }
    }

    val automaticBackupEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUTOMATIC_BACKUP_ENABLED] ?: false
    }

    val backupFrequency: Flow<com.example.data.sync.BackupFrequency> = safeData.map { prefs ->
        com.example.data.sync.BackupFrequency.fromString(prefs[PreferenceKeys.BACKUP_FREQUENCY])
    }

    val lastSyncedTimestamp: Flow<Long> = safeData.map { prefs ->
        prefs[PreferenceKeys.LAST_SYNCED_TIMESTAMP] ?: 0L
    }

    val lastBackupTimestamp: Flow<Long> = safeData.map { prefs ->
        prefs[PreferenceKeys.LAST_BACKUP_TIMESTAMP] ?: 0L
    }

    val lastBackupDataHash: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.LAST_BACKUP_DATA_HASH] ?: ""
    }

    suspend fun setAutomaticBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.AUTOMATIC_BACKUP_ENABLED] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun setBackupFrequency(frequency: com.example.data.sync.BackupFrequency) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.BACKUP_FREQUENCY] = frequency.name
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun setLastSyncedTimestamp(timestamp: Long) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.LAST_SYNCED_TIMESTAMP] = timestamp
        }
    }

    suspend fun setLastBackupTimestamp(timestamp: Long, hash: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.LAST_BACKUP_TIMESTAMP] = timestamp
            if (hash != null) {
                prefs[PreferenceKeys.LAST_BACKUP_DATA_HASH] = hash
            }
        }
    }

    val settingsLastUpdatedAt: Flow<Long> = safeData.map { prefs ->
        prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] ?: 0L
    }

    suspend fun touchSettingsUpdatedAt(timestamp: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = timestamp
        }
    }

    suspend fun exportUserSettings(): com.example.data.sync.CloudUserSettings {
        val prefs = safeData.first()
        val effectiveThemeMode = prefs[PreferenceKeys.THEME_MODE] ?: "DARK"
        val effectiveAmoled = if (effectiveThemeMode.equals("LIGHT", ignoreCase = true)) {
            false
        } else {
            prefs[PreferenceKeys.AMOLED_MODE] ?: false
        }
        return com.example.data.sync.CloudUserSettings(
            themeMode = effectiveThemeMode,
            uiTheme = prefs[PreferenceKeys.UI_THEME] ?: "MIDNIGHT_OBSIDIAN",
            homeScreenLayout = prefs[PreferenceKeys.HOME_SCREEN_LAYOUT] ?: "BENTO_DYNAMIC",
            nowPlayingLayout = prefs[PreferenceKeys.NOW_PLAYING_LAYOUT] ?: "MINIMAL_GLASS",
            playlistsLayout = prefs[PreferenceKeys.PLAYLISTS_LAYOUT] ?: "GLOW_CARDS",
            accentColorPreset = prefs[PreferenceKeys.ACCENT_COLOR_PRESET] ?: "ORANGE",
            customAccentColor = prefs[PreferenceKeys.CUSTOM_ACCENT_COLOR] ?: 0,
            dynamicArtworkTheme = prefs[PreferenceKeys.DYNAMIC_ARTWORK_THEME] ?: true,
            amoledMode = effectiveAmoled,
            cornerRadiusDp = prefs[PreferenceKeys.CORNER_RADIUS_DP] ?: 16,
            fontSizeScale = prefs[PreferenceKeys.FONT_SIZE_SCALE] ?: "MEDIUM",
            artworkSize = prefs[PreferenceKeys.ARTWORK_SIZE] ?: "MEDIUM",
            showArtist = prefs[PreferenceKeys.SHOW_ARTIST] ?: true,
            showAlbum = prefs[PreferenceKeys.SHOW_ALBUM] ?: true,
            showAudioOutput = prefs[PreferenceKeys.SHOW_AUDIO_OUTPUT] ?: true,
            miniPlayerStyle = prefs[PreferenceKeys.MINI_PLAYER_STYLE] ?: "FLOATING",
            miniPlayerShowPrevious = prefs[PreferenceKeys.MINI_PLAYER_SHOW_PREVIOUS] ?: true,
            miniPlayerShowNext = prefs[PreferenceKeys.MINI_PLAYER_SHOW_NEXT] ?: true,
            miniPlayerShowFavorite = prefs[PreferenceKeys.MINI_PLAYER_SHOW_FAVORITE] ?: true,
            waveformStyle = prefs[PreferenceKeys.WAVEFORM_STYLE] ?: "SNAKE",
            lyricsFetchMode = prefs[PreferenceKeys.LYRICS_FETCH_MODE] ?: "WIFI_ONLY",
            lyricsAutoScroll = prefs[PreferenceKeys.LYRICS_AUTO_SCROLL] ?: true,
            lyricsFontSize = prefs[PreferenceKeys.LYRICS_FONT_SIZE] ?: "MEDIUM",
            gaplessPlayback = prefs[PreferenceKeys.GAPLESS_PLAYBACK] ?: true,
            crossfadeDurationSec = prefs[PreferenceKeys.CROSSFADE_DURATION_SEC] ?: 0,
            normalizeAudio = prefs[PreferenceKeys.NORMALIZE_AUDIO] ?: false,
            autoPlayOnHeadphoneConnection = prefs[PreferenceKeys.AUTO_PLAY_ON_HEADPHONE_CONNECTION] ?: false,
            pauseOnZeroVolume = prefs[PreferenceKeys.PAUSE_ON_ZERO_VOLUME] ?: true,
            autoResumeOnVolumeIncrease = prefs[PreferenceKeys.AUTO_RESUME_ON_VOLUME_INCREASE] ?: true,
            fadeInOutMs = prefs[PreferenceKeys.FADE_IN_OUT_MS] ?: 1000,
            fadeInMs = prefs[PreferenceKeys.FADE_IN_MS] ?: 1000,
            fadeOutMs = prefs[PreferenceKeys.FADE_OUT_MS] ?: 1000,
            shakeToSkip = prefs[PreferenceKeys.SHAKE_TO_SKIP] ?: false,
            keepScreenAwake = prefs[PreferenceKeys.KEEP_SCREEN_AWAKE] ?: false,
            audioBalance = prefs[PreferenceKeys.AUDIO_BALANCE] ?: 0f,
            equalizerPreset = prefs[PreferenceKeys.EQUALIZER_PRESET] ?: "NORMAL",
            customEqBands = prefs[PreferenceKeys.CUSTOM_EQ_BANDS] ?: "0.0,0.0,0.0,0.0,0.0",
            customBassBoost = prefs[PreferenceKeys.CUSTOM_BASS_BOOST] ?: 0,
            customVirtualizer = prefs[PreferenceKeys.CUSTOM_VIRTUALIZER] ?: 0,
            reduceVolumeOnNotification = prefs[PreferenceKeys.REDUCE_VOLUME_ON_NOTIFICATION] ?: true,
            playDuringCamera = prefs[PreferenceKeys.PLAY_DURING_CAMERA] ?: false,
            scrobbleThresholdType = prefs[PreferenceKeys.SCROBBLE_THRESHOLD_TYPE] ?: "SECONDS",
            scrobbleThresholdValue = prefs[PreferenceKeys.SCROBBLE_THRESHOLD_VALUE] ?: 30,
            repeatMode = prefs[PreferenceKeys.REPEAT_MODE] ?: 0,
            shuffleMode = prefs[PreferenceKeys.SHUFFLE_MODE] ?: false,
            alwaysShuffleMode = prefs[PreferenceKeys.ALWAYS_SHUFFLE_MODE] ?: false,
            gesturePlaybackEnabled = prefs[PreferenceKeys.GESTURE_PLAYBACK_ENABLED] ?: true,
            highRefreshRate = prefs[PreferenceKeys.HIGH_REFRESH_RATE] ?: true,
            addToQueuePosition = prefs[PreferenceKeys.ADD_TO_QUEUE_POSITION] ?: "BOTTOM",
            playlistAddSongPosition = prefs[PreferenceKeys.PLAYLIST_ADD_SONG_POSITION] ?: "BOTTOM",
            playlistViewMode = prefs[PreferenceKeys.PLAYLIST_VIEW_MODE] ?: "GRID",
            homeSections = prefs[PreferenceKeys.HOME_SECTIONS] ?: "continue_listening,quick_actions,listening_stats,most_played,most_played_playlists,recently_played,recently_added,top_artists,top_albums",
            databaseStorageTier = prefs[PreferenceKeys.DATABASE_STORAGE_TIER] ?: "EXPANDED",
            customDisplayName = prefs[PreferenceKeys.CUSTOM_DISPLAY_NAME],
            customAvatarUri = prefs[PreferenceKeys.CUSTOM_AVATAR_URI],
            ignoreAudioDuration = prefs[PreferenceKeys.IGNORE_AUDIO_DURATION] ?: 0,
            librarySortOption = prefs[stringPreferencesKey("library_sort_option")] ?: "RECENTLY_ADDED",
            librarySortAscending = prefs[booleanPreferencesKey("library_sort_ascending")] ?: true,
            sleepTimerMode = prefs[PreferenceKeys.SLEEP_TIMER_MODE] ?: "TIME",
            resumeOnBluetooth = prefs[PreferenceKeys.RESUME_ON_BLUETOOTH] ?: false,
            pauseOnHeadsetUnplug = prefs[PreferenceKeys.PAUSE_ON_HEADSET_UNPLUG] ?: true,
            notificationEnabled = prefs[PreferenceKeys.NOTIFICATION_ENABLED] ?: true,
            autoplayOnAppStartup = prefs[PreferenceKeys.AUTOPLAY_ON_APP_STARTUP] ?: false,
            fetchOnlineArtworkHighRes = prefs[PreferenceKeys.FETCH_ONLINE_ARTWORK_HIGH_RES] ?: true,
            cacheLyricsEnabled = prefs[PreferenceKeys.CACHE_LYRICS_ENABLED] ?: true,
            cacheAlbumArtEnabled = prefs[PreferenceKeys.CACHE_ALBUM_ART_ENABLED] ?: true,
            cacheSongArtWorkEnabled = prefs[PreferenceKeys.CACHE_SONG_ART_WORK_ENABLED] ?: true,
            transcribeTranslateEnabled = prefs[PreferenceKeys.TRANSCRIBE_TRANSLATE_ENABLED] ?: false,
            audioTranscribeMode = prefs[PreferenceKeys.AUDIO_TRANSCRIBE_MODE] ?: "OFF",
            audioTranscribeTargetLang = prefs[PreferenceKeys.AUDIO_TRANSCRIBE_TARGET_LANG] ?: "ta",
            automaticBackupEnabled = prefs[PreferenceKeys.AUTOMATIC_BACKUP_ENABLED] ?: false,
            backupFrequency = prefs[PreferenceKeys.BACKUP_FREQUENCY] ?: "WEEKLY",
            updatedAt = prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] ?: 0L
        )
    }

    suspend fun importUserSettings(cloud: com.example.data.sync.CloudUserSettings, checkTimestamp: Boolean = true): Boolean {
        var applied = false
        context.dataStore.edit { prefs ->
            val localUpdated = prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] ?: 0L
            if (!checkTimestamp || localUpdated == 0L || cloud.updatedAt > localUpdated) {
                prefs[PreferenceKeys.THEME_MODE] = cloud.themeMode
                prefs[PreferenceKeys.UI_THEME] = cloud.uiTheme
                prefs[PreferenceKeys.HOME_SCREEN_LAYOUT] = cloud.homeScreenLayout
                prefs[PreferenceKeys.NOW_PLAYING_LAYOUT] = cloud.nowPlayingLayout
                prefs[PreferenceKeys.PLAYLISTS_LAYOUT] = cloud.playlistsLayout
                prefs[PreferenceKeys.ACCENT_COLOR_PRESET] = cloud.accentColorPreset
                prefs[PreferenceKeys.CUSTOM_ACCENT_COLOR] = cloud.customAccentColor
                prefs[PreferenceKeys.DYNAMIC_ARTWORK_THEME] = cloud.dynamicArtworkTheme
                // Enforce Extra Dark Mode rule: cannot be ON when Light theme is active
                prefs[PreferenceKeys.AMOLED_MODE] = if (cloud.themeMode.equals("LIGHT", ignoreCase = true)) false else cloud.amoledMode
                prefs[PreferenceKeys.CORNER_RADIUS_DP] = cloud.cornerRadiusDp
                prefs[PreferenceKeys.FONT_SIZE_SCALE] = cloud.fontSizeScale
                prefs[PreferenceKeys.ARTWORK_SIZE] = cloud.artworkSize
                prefs[PreferenceKeys.SHOW_ARTIST] = cloud.showArtist
                prefs[PreferenceKeys.SHOW_ALBUM] = cloud.showAlbum
                prefs[PreferenceKeys.SHOW_AUDIO_OUTPUT] = cloud.showAudioOutput
                prefs[PreferenceKeys.MINI_PLAYER_STYLE] = cloud.miniPlayerStyle
                prefs[PreferenceKeys.MINI_PLAYER_SHOW_PREVIOUS] = cloud.miniPlayerShowPrevious
                prefs[PreferenceKeys.MINI_PLAYER_SHOW_NEXT] = cloud.miniPlayerShowNext
                prefs[PreferenceKeys.MINI_PLAYER_SHOW_FAVORITE] = cloud.miniPlayerShowFavorite
                prefs[PreferenceKeys.WAVEFORM_STYLE] = cloud.waveformStyle
                prefs[PreferenceKeys.LYRICS_FETCH_MODE] = cloud.lyricsFetchMode
                prefs[PreferenceKeys.LYRICS_AUTO_SCROLL] = cloud.lyricsAutoScroll
                prefs[PreferenceKeys.LYRICS_FONT_SIZE] = cloud.lyricsFontSize
                prefs[PreferenceKeys.GAPLESS_PLAYBACK] = cloud.gaplessPlayback
                prefs[PreferenceKeys.CROSSFADE_DURATION_SEC] = cloud.crossfadeDurationSec
                prefs[PreferenceKeys.NORMALIZE_AUDIO] = cloud.normalizeAudio
                prefs[PreferenceKeys.AUTO_PLAY_ON_HEADPHONE_CONNECTION] = cloud.autoPlayOnHeadphoneConnection
                prefs[PreferenceKeys.PAUSE_ON_ZERO_VOLUME] = cloud.pauseOnZeroVolume
                prefs[PreferenceKeys.AUTO_RESUME_ON_VOLUME_INCREASE] = cloud.autoResumeOnVolumeIncrease
                prefs[PreferenceKeys.FADE_IN_OUT_MS] = cloud.fadeInOutMs
                prefs[PreferenceKeys.FADE_IN_MS] = cloud.fadeInMs
                prefs[PreferenceKeys.FADE_OUT_MS] = cloud.fadeOutMs
                prefs[PreferenceKeys.SHAKE_TO_SKIP] = cloud.shakeToSkip
                prefs[PreferenceKeys.KEEP_SCREEN_AWAKE] = cloud.keepScreenAwake
                prefs[PreferenceKeys.AUDIO_BALANCE] = cloud.audioBalance
                prefs[PreferenceKeys.EQUALIZER_PRESET] = cloud.equalizerPreset
                prefs[PreferenceKeys.CUSTOM_EQ_BANDS] = cloud.customEqBands
                prefs[PreferenceKeys.CUSTOM_BASS_BOOST] = cloud.customBassBoost
                prefs[PreferenceKeys.CUSTOM_VIRTUALIZER] = cloud.customVirtualizer
                prefs[PreferenceKeys.REDUCE_VOLUME_ON_NOTIFICATION] = cloud.reduceVolumeOnNotification
                prefs[PreferenceKeys.PLAY_DURING_CAMERA] = cloud.playDuringCamera
                prefs[PreferenceKeys.SCROBBLE_THRESHOLD_TYPE] = cloud.scrobbleThresholdType
                prefs[PreferenceKeys.SCROBBLE_THRESHOLD_VALUE] = cloud.scrobbleThresholdValue
                prefs[PreferenceKeys.REPEAT_MODE] = cloud.repeatMode
                prefs[PreferenceKeys.SHUFFLE_MODE] = cloud.shuffleMode
                prefs[PreferenceKeys.ALWAYS_SHUFFLE_MODE] = cloud.alwaysShuffleMode
                prefs[PreferenceKeys.GESTURE_PLAYBACK_ENABLED] = cloud.gesturePlaybackEnabled
                prefs[PreferenceKeys.HIGH_REFRESH_RATE] = cloud.highRefreshRate
                prefs[PreferenceKeys.ADD_TO_QUEUE_POSITION] = cloud.addToQueuePosition
                prefs[PreferenceKeys.PLAYLIST_ADD_SONG_POSITION] = cloud.playlistAddSongPosition
                prefs[PreferenceKeys.PLAYLIST_VIEW_MODE] = cloud.playlistViewMode
                prefs[PreferenceKeys.HOME_SECTIONS] = cloud.homeSections
                prefs[PreferenceKeys.DATABASE_STORAGE_TIER] = cloud.databaseStorageTier
                if (!cloud.customDisplayName.isNullOrBlank()) {
                    prefs[PreferenceKeys.CUSTOM_DISPLAY_NAME] = cloud.customDisplayName
                }
                if (!cloud.customAvatarUri.isNullOrBlank()) {
                    prefs[PreferenceKeys.CUSTOM_AVATAR_URI] = cloud.customAvatarUri
                }
                prefs[PreferenceKeys.IGNORE_AUDIO_DURATION] = cloud.ignoreAudioDuration
                prefs[stringPreferencesKey("library_sort_option")] = cloud.librarySortOption
                prefs[booleanPreferencesKey("library_sort_ascending")] = cloud.librarySortAscending
                prefs[PreferenceKeys.SLEEP_TIMER_MODE] = cloud.sleepTimerMode
                prefs[PreferenceKeys.RESUME_ON_BLUETOOTH] = cloud.resumeOnBluetooth
                prefs[PreferenceKeys.PAUSE_ON_HEADSET_UNPLUG] = cloud.pauseOnHeadsetUnplug
                prefs[PreferenceKeys.NOTIFICATION_ENABLED] = cloud.notificationEnabled
                prefs[PreferenceKeys.AUTOPLAY_ON_APP_STARTUP] = cloud.autoplayOnAppStartup
                prefs[PreferenceKeys.FETCH_ONLINE_ARTWORK_HIGH_RES] = cloud.fetchOnlineArtworkHighRes
                prefs[PreferenceKeys.CACHE_LYRICS_ENABLED] = cloud.cacheLyricsEnabled
                prefs[PreferenceKeys.CACHE_ALBUM_ART_ENABLED] = cloud.cacheAlbumArtEnabled
                prefs[PreferenceKeys.CACHE_SONG_ART_WORK_ENABLED] = cloud.cacheSongArtWorkEnabled
                prefs[PreferenceKeys.TRANSCRIBE_TRANSLATE_ENABLED] = cloud.transcribeTranslateEnabled
                prefs[PreferenceKeys.AUDIO_TRANSCRIBE_MODE] = cloud.audioTranscribeMode
                prefs[PreferenceKeys.AUDIO_TRANSCRIBE_TARGET_LANG] = cloud.audioTranscribeTargetLang
                prefs[PreferenceKeys.AUTOMATIC_BACKUP_ENABLED] = cloud.automaticBackupEnabled
                prefs[PreferenceKeys.BACKUP_FREQUENCY] = cloud.backupFrequency
                prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = if (cloud.updatedAt > 0L) cloud.updatedAt else System.currentTimeMillis()
                applied = true
            }
        }
        return applied
    }

    val skipAuthPrompt: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.SKIP_AUTH_PROMPT] ?: false
    }

    suspend fun setSkipAuthPrompt(skip: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SKIP_AUTH_PROMPT] = skip
        }
    }

    val customDisplayName: Flow<String?> = safeData.map { prefs ->
        prefs[PreferenceKeys.CUSTOM_DISPLAY_NAME]
    }

    suspend fun setCustomDisplayName(name: String?) {
        context.dataStore.edit { prefs ->
            if (name != null) {
                prefs[PreferenceKeys.CUSTOM_DISPLAY_NAME] = name
            } else {
                prefs.remove(PreferenceKeys.CUSTOM_DISPLAY_NAME)
            }
        }
    }

    val customAvatarUri: Flow<String?> = safeData.map { prefs ->
        prefs[PreferenceKeys.CUSTOM_AVATAR_URI]
    }

    suspend fun setCustomAvatarUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri != null) {
                prefs[PreferenceKeys.CUSTOM_AVATAR_URI] = uri
            } else {
                prefs.remove(PreferenceKeys.CUSTOM_AVATAR_URI)
            }
        }
    }

    val lastActiveUid: Flow<String?> = safeData.map { prefs ->
        prefs[PreferenceKeys.LAST_ACTIVE_UID]
    }

    suspend fun getLastActiveUid(): String? {
        return safeData.first()[PreferenceKeys.LAST_ACTIVE_UID]
    }

    suspend fun setLastActiveUid(uid: String?) {
        context.dataStore.edit { prefs ->
            if (uid != null) {
                prefs[PreferenceKeys.LAST_ACTIVE_UID] = uid
            } else {
                prefs.remove(PreferenceKeys.LAST_ACTIVE_UID)
            }
        }
    }

    suspend fun resetAccountSpecificData() {
        context.dataStore.edit { prefs ->
            prefs.remove(PreferenceKeys.CUSTOM_DISPLAY_NAME)
            prefs.remove(PreferenceKeys.CUSTOM_AVATAR_URI)
            prefs[PreferenceKeys.LAST_SYNCED_TIMESTAMP] = 0L
            prefs[PreferenceKeys.LAST_BACKUP_TIMESTAMP] = 0L
            prefs.remove(PreferenceKeys.LAST_BACKUP_DATA_HASH)
            prefs.remove(PreferenceKeys.LAST_ACTIVE_UID)
        }
    }

    val databaseStorageTier: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.DATABASE_STORAGE_TIER] ?: "EXPANDED"
    }

    suspend fun setDatabaseStorageTier(tier: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.DATABASE_STORAGE_TIER] = tier
        }
    }

    val cacheLyricsEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.CACHE_LYRICS_ENABLED] ?: true
    }

    val cacheAlbumArtEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.CACHE_ALBUM_ART_ENABLED] ?: true
    }

    val cacheSongArtWorkEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.CACHE_SONG_ART_WORK_ENABLED] ?: true
    }

    @Volatile var cachedFetchOnlineArtworkHighRes: Boolean = false
        private set
    @Volatile var cachedCacheAlbumArtEnabled: Boolean = true
        private set
    @Volatile var cachedCacheSongArtWorkEnabled: Boolean = true
        private set
    @Volatile var cachedAutoPlayOnHeadphoneConnection: Boolean = false
    @Volatile var cachedAutoPlayOnAppStartup: Boolean = false

    val customEqBands: Flow<List<Float>> = safeData.map { prefs ->
        val raw = prefs[PreferenceKeys.CUSTOM_EQ_BANDS] ?: "0.0,0.0,0.0,0.0,0.0"
        try {
            raw.split(",").map { it.trim().toFloat() }
        } catch (_: Exception) {
            listOf(0f, 0f, 0f, 0f, 0f)
        }
    }

    val customBassBoost: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.CUSTOM_BASS_BOOST] ?: 0
    }

    val customVirtualizer: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.CUSTOM_VIRTUALIZER] ?: 0
    }

    val fetchOnlineArtworkHighRes: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.FETCH_ONLINE_ARTWORK_HIGH_RES] ?: false
    }

    val alwaysShuffleMode: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.ALWAYS_SHUFFLE_MODE] ?: false
    }

    val gesturePlaybackEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.GESTURE_PLAYBACK_ENABLED] ?: true
    }

    val homeSections: Flow<List<String>> = safeData.map { prefs ->
        val raw = prefs[PreferenceKeys.HOME_SECTIONS] ?: "continue_listening,quick_actions,listening_stats,most_played,most_played_playlists,recently_played,recently_added,top_artists,top_albums"
        raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    suspend fun setHomeSections(sections: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.HOME_SECTIONS] = sections.joinToString(",")
        }
    }

    val highRefreshRate: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.HIGH_REFRESH_RATE] ?: true
    }

    private val bootCachePrefs = context.getSharedPreferences("offgrid_boot_cache", Context.MODE_PRIVATE)

    /**
     * Checks whether this installation is an existing user upgrading from a previous build:
     * - Evaluates ONLY ONCE on initial cold boot (tracked via `initial_install_checked`) so that
     *   granting runtime permissions in Onboarding Step 2 or scanning/filtering folders in Step 3
     *   never prematurely flips `onboarding_completed` to `true` before the user taps Get Started.
     */
    private fun detectExistingInstallation(): Boolean {
        return try {
            if (bootCachePrefs.getBoolean("onboarding_completed", false)) return true
            if (bootCachePrefs.getBoolean("initial_install_checked", false)) {
                return bootCachePrefs.getBoolean("onboarding_completed", false)
            }

            val requiredPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            val hasAudioPermission = ContextCompat.checkSelfPermission(
                context,
                requiredPerm
            ) == PackageManager.PERMISSION_GRANTED

            if (hasAudioPermission) {
                bootCachePrefs.edit()
                    .putBoolean("initial_install_checked", true)
                    .putBoolean("onboarding_completed", true)
                    .apply()
                return true
            }

            if (!bootCachePrefs.getBoolean("db_checked_once", false)) {
                val dbFile = context.getDatabasePath("offgrid_music_db")
                if (dbFile != null && dbFile.exists() && dbFile.length() > 0L) {
                    AppDatabase.healExistingDatabaseBeforeRoomOpen(context)
                } else {
                    bootCachePrefs.edit().putBoolean("db_checked_once", true).apply()
                }
            }

            val upgraded = bootCachePrefs.getBoolean("onboarding_completed", false)
            bootCachePrefs.edit()
                .putBoolean("initial_install_checked", true)
                .putBoolean("onboarding_completed", upgraded)
                .apply()
            upgraded
        } catch (_: Exception) {
            false
        }
    }

    fun isOnboardingCompletedSync(): Boolean {
        return detectExistingInstallation()
    }

    val isOnboardingCompleted: Flow<Boolean> = safeData.map { prefs ->
        val completed = (prefs[PreferenceKeys.ONBOARDING_COMPLETED] == true) || detectExistingInstallation()
        try {
            if (completed && !bootCachePrefs.getBoolean("onboarding_completed", false)) {
                bootCachePrefs.edit()
                    .putBoolean("initial_install_checked", true)
                    .putBoolean("onboarding_completed", true)
                    .apply()
            }
        } catch (_: Exception) {}
        completed
    }

    val themeMode: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.THEME_MODE] ?: "DARK"
    }

    val uiTheme: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.UI_THEME] ?: "MIDNIGHT_OBSIDIAN"
    }

    val displayScale: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.DISPLAY_SCALE] ?: "DEFAULT"
    }

    val homeScreenLayout: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.HOME_SCREEN_LAYOUT] ?: "BENTO_DYNAMIC"
    }

    val nowPlayingLayout: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.NOW_PLAYING_LAYOUT] ?: "MINIMAL_GLASS"
    }

    val playlistsLayout: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.PLAYLISTS_LAYOUT] ?: "GLOW_CARDS"
    }

    val isDynamicArtworkTheme: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.DYNAMIC_ARTWORK_THEME] ?: true
    }

    val customAccentColor: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.CUSTOM_ACCENT_COLOR] ?: 0
    }

    val lyricsFetchMode: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.LYRICS_FETCH_MODE] ?: "WIFI_ONLY"
    }

    val resumeOnBluetooth: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.RESUME_ON_BLUETOOTH] ?: false
    }

    val pauseOnHeadsetUnplug: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.PAUSE_ON_HEADSET_UNPLUG] ?: true
    }

    val repeatMode: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.REPEAT_MODE] ?: 0
    }

    val shuffleMode: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.SHUFFLE_MODE] ?: false
    }

    val lastSongId: Flow<Long> = safeData.map { prefs ->
        prefs[PreferenceKeys.LAST_SONG_ID] ?: bootCachePrefs.getLong("last_song_id", -1L)
    }

    val lastPosition: Flow<Long> = safeData.map { prefs ->
        prefs[PreferenceKeys.LAST_POSITION] ?: bootCachePrefs.getLong("last_position", 0L)
    }

    val lastDuration: Flow<Long> = safeData.map { prefs ->
        prefs[PreferenceKeys.LAST_DURATION] ?: bootCachePrefs.getLong("last_duration", 0L)
    }

    val lastQueueIds: Flow<List<Long>> = safeData.map { prefs ->
        val raw = prefs[PreferenceKeys.LAST_QUEUE_IDS]
            ?: bootCachePrefs.getString("last_queue_ids", "")
            ?: ""
        if (raw.isBlank()) emptyList() else raw.split(",").mapNotNull { it.toLongOrNull() }
    }

    val lastQueueIndex: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.LAST_QUEUE_INDEX] ?: bootCachePrefs.getInt("last_queue_index", 0)
    }

    val addToQueuePosition: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.ADD_TO_QUEUE_POSITION] ?: "BOTTOM"
    }

    val playlistAddSongPosition: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.PLAYLIST_ADD_SONG_POSITION] ?: "BOTTOM"
    }

    val playlistViewMode: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.PLAYLIST_VIEW_MODE] ?: "GRID"
    }

    val waveformStyle: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.WAVEFORM_STYLE] ?: "SNAKE"
    }

    /**
     * Extra Dark Mode (`amoledMode`):
     * Strictly gated by Dark Theme — if `themeMode` is `"LIGHT"`, it always evaluates to `false`.
     */
    val amoledMode: Flow<Boolean> = safeData.map { prefs ->
        val mode = prefs[PreferenceKeys.THEME_MODE] ?: "DARK"
        if (mode.equals("LIGHT", ignoreCase = true)) {
            false
        } else {
            prefs[PreferenceKeys.AMOLED_MODE] ?: false
        }
    }

    val accentColorPreset: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.ACCENT_COLOR_PRESET] ?: "ORANGE"
    }

    val cornerRadiusDp: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.CORNER_RADIUS_DP] ?: 16
    }

    val fontSizeScale: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.FONT_SIZE_SCALE] ?: "MEDIUM"
    }

    val artworkSize: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.ARTWORK_SIZE] ?: "MEDIUM"
    }

    val showArtist: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.SHOW_ARTIST] ?: true
    }

    val showAlbum: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.SHOW_ALBUM] ?: true
    }

    val showAudioOutput: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.SHOW_AUDIO_OUTPUT] ?: true
    }

    val miniPlayerStyle: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.MINI_PLAYER_STYLE] ?: "FLOATING"
    }

    val miniPlayerShowPrevious: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.MINI_PLAYER_SHOW_PREVIOUS] ?: true
    }

    val miniPlayerShowNext: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.MINI_PLAYER_SHOW_NEXT] ?: true
    }

    val miniPlayerShowFavorite: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.MINI_PLAYER_SHOW_FAVORITE] ?: true
    }

    val gaplessPlayback: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.GAPLESS_PLAYBACK] ?: true
    }

    val normalizeAudio: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.NORMALIZE_AUDIO] ?: false
    }

    val autoPlayOnHeadphoneConnection: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUTO_PLAY_ON_HEADPHONE_CONNECTION] ?: false
    }

    val autoPlayOnAppStartup: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUTOPLAY_ON_APP_STARTUP] ?: false
    }

    val crossfadeDurationSec: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.CROSSFADE_DURATION_SEC] ?: 0
    }

    val lyricsAutoScroll: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.LYRICS_AUTO_SCROLL] ?: true
    }

    val lyricsFontSize: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.LYRICS_FONT_SIZE] ?: "MEDIUM"
    }

    val notificationEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.NOTIFICATION_ENABLED] ?: true
    }

    val pauseOnZeroVolume: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.PAUSE_ON_ZERO_VOLUME] ?: true
    }

    val autoResumeOnVolumeIncrease: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUTO_RESUME_ON_VOLUME_INCREASE] ?: true
    }

    val ignoreAudioDuration: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.IGNORE_AUDIO_DURATION] ?: 0
    }

    val maxSongDuration: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.MAX_SONG_DURATION] ?: 300
    }

    val fullDeviceScan: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.FULL_DEVICE_SCAN] ?: false
    }

    val fadeInOutMs: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.FADE_IN_OUT_MS] ?: 1000
    }

    val fadeInMs: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.FADE_IN_MS] ?: prefs[PreferenceKeys.FADE_IN_OUT_MS] ?: 1000
    }

    val fadeOutMs: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.FADE_OUT_MS] ?: prefs[PreferenceKeys.FADE_IN_OUT_MS] ?: 1000
    }

    val shakeToSkip: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.SHAKE_TO_SKIP] ?: false
    }

    val keepScreenAwake: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.KEEP_SCREEN_AWAKE] ?: false
    }

    val audioBalance: Flow<Float> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUDIO_BALANCE] ?: 0f
    }

    val equalizerPreset: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.EQUALIZER_PRESET] ?: "NORMAL"
    }

    val reduceVolumeOnNotification: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.REDUCE_VOLUME_ON_NOTIFICATION] ?: true
    }

    val playDuringCamera: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.PLAY_DURING_CAMERA] ?: false
    }

    val scrobbleThresholdType: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.SCROBBLE_THRESHOLD_TYPE] ?: "SECONDS"
    }

    val scrobbleThresholdValue: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.SCROBBLE_THRESHOLD_VALUE] ?: 30
    }

    suspend fun setScrobbleThresholdType(type: String) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.SCROBBLE_THRESHOLD_TYPE] = type }
    }

    suspend fun setScrobbleThresholdValue(value: Int) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.SCROBBLE_THRESHOLD_VALUE] = value }
    }

    suspend fun setReduceVolumeOnNotification(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.REDUCE_VOLUME_ON_NOTIFICATION] = enabled }
    }

    suspend fun setPlayDuringCamera(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.PLAY_DURING_CAMERA] = enabled }
    }

    suspend fun setPauseOnZeroVolume(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.PAUSE_ON_ZERO_VOLUME] = enabled }
    }

    suspend fun setAutoResumeOnVolumeIncrease(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.AUTO_RESUME_ON_VOLUME_INCREASE] = enabled }
    }

    suspend fun setIgnoreAudioDuration(durationSec: Int) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.IGNORE_AUDIO_DURATION] = durationSec }
    }

    suspend fun setMaxSongDuration(durationSec: Int) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.MAX_SONG_DURATION] = durationSec }
    }

    suspend fun setFullDeviceScan(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.FULL_DEVICE_SCAN] = enabled }
    }

    suspend fun setSongDurationRange(minSec: Int, maxSec: Int) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.IGNORE_AUDIO_DURATION] = minSec
            prefs[PreferenceKeys.MAX_SONG_DURATION] = maxSec
        }
    }

    suspend fun setFadeInOutMs(ms: Int) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.FADE_IN_OUT_MS] = ms
            prefs[PreferenceKeys.FADE_IN_MS] = ms
            prefs[PreferenceKeys.FADE_OUT_MS] = ms
        }
    }

    suspend fun setFadeInMs(ms: Int) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.FADE_IN_MS] = ms }
    }

    suspend fun setFadeOutMs(ms: Int) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.FADE_OUT_MS] = ms }
    }

    suspend fun setShakeToSkip(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.SHAKE_TO_SKIP] = enabled }
    }

    suspend fun setKeepScreenAwake(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.KEEP_SCREEN_AWAKE] = enabled }
    }

    suspend fun setAudioBalance(balance: Float) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.AUDIO_BALANCE] = balance }
    }

    suspend fun setEqualizerPreset(preset: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.EQUALIZER_PRESET] = preset
            val eqPreset = com.example.ui.components.EQ_PRESETS.find { it.id.equals(preset, ignoreCase = true) }
            if (eqPreset != null) {
                prefs[PreferenceKeys.CUSTOM_EQ_BANDS] = eqPreset.bands.joinToString(",")
                prefs[PreferenceKeys.CUSTOM_BASS_BOOST] = eqPreset.bassBoost
                prefs[PreferenceKeys.CUSTOM_VIRTUALIZER] = eqPreset.virtualizer
            }
        }
    }

    suspend fun setWaveformStyle(style: String) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.WAVEFORM_STYLE] = style }
    }

    /**
     * Enables/disables Extra Dark Mode (`amoled_mode`), strictly requiring Dark Theme.
     * If the user is currently in Light Theme, Extra Dark Mode will not turn on.
     */
    suspend fun setAmoledMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val currentThemeMode = prefs[PreferenceKeys.THEME_MODE] ?: "DARK"
            if (currentThemeMode.equals("LIGHT", ignoreCase = true)) {
                prefs[PreferenceKeys.AMOLED_MODE] = false
            } else {
                prefs[PreferenceKeys.AMOLED_MODE] = enabled
            }
        }
    }

    suspend fun setAccentColorPreset(preset: String) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.ACCENT_COLOR_PRESET] = preset }
    }

    suspend fun setCornerRadiusDp(radius: Int) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.CORNER_RADIUS_DP] = radius }
    }

    suspend fun setFontSizeScale(scale: String) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.FONT_SIZE_SCALE] = scale }
    }

    suspend fun setArtworkSize(size: String) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.ARTWORK_SIZE] = size }
    }

    suspend fun setShowArtist(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.SHOW_ARTIST] = show }
    }

    suspend fun setShowAlbum(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.SHOW_ALBUM] = show }
    }

    suspend fun setShowAudioOutput(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.SHOW_AUDIO_OUTPUT] = show }
    }

    suspend fun setMiniPlayerStyle(style: String) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.MINI_PLAYER_STYLE] = style }
    }

    suspend fun setMiniPlayerShowPrevious(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.MINI_PLAYER_SHOW_PREVIOUS] = show }
    }

    suspend fun setMiniPlayerShowNext(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.MINI_PLAYER_SHOW_NEXT] = show }
    }

    suspend fun setMiniPlayerShowFavorite(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.MINI_PLAYER_SHOW_FAVORITE] = show }
    }

    suspend fun setGaplessPlayback(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.GAPLESS_PLAYBACK] = enabled }
    }

    suspend fun setNormalizeAudio(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.NORMALIZE_AUDIO] = enabled }
    }

    suspend fun setAutoPlayOnHeadphoneConnection(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.AUTO_PLAY_ON_HEADPHONE_CONNECTION] = enabled }
    }

    suspend fun setAutoPlayOnAppStartup(enabled: Boolean) {
        cachedAutoPlayOnAppStartup = enabled
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.AUTOPLAY_ON_APP_STARTUP] = enabled }
    }

    suspend fun setCrossfadeDurationSec(sec: Int) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.CROSSFADE_DURATION_SEC] = sec }
    }

    suspend fun setLyricsAutoScroll(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.LYRICS_AUTO_SCROLL] = enabled }
    }

    suspend fun setLyricsFontSize(size: String) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.LYRICS_FONT_SIZE] = size }
    }

    suspend fun setAlwaysShuffleMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.ALWAYS_SHUFFLE_MODE] = enabled }
    }

    suspend fun setGesturePlaybackEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.GESTURE_PLAYBACK_ENABLED] = enabled }
    }

    fun getLyricsSyncOffsetMs(songId: Long): Long {
        if (songId <= 0) return 0L
        return try {
            val sp = context.getSharedPreferences("lyrics_offsets_prefs", Context.MODE_PRIVATE)
            sp.getLong("offset_$songId", 0L)
        } catch (_: Exception) {
            0L
        }
    }

    fun setLyricsSyncOffsetMs(songId: Long, offsetMs: Long) {
        if (songId <= 0) return
        try {
            val sp = context.getSharedPreferences("lyrics_offsets_prefs", Context.MODE_PRIVATE)
            if (offsetMs == 0L) {
                sp.edit().remove("offset_$songId").commit()
            } else {
                sp.edit().putLong("offset_$songId", offsetMs).commit()
            }
        } catch (_: Exception) {}
    }

    suspend fun setNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferenceKeys.NOTIFICATION_ENABLED] = enabled }
    }

    suspend fun setPlaylistViewMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.PLAYLIST_VIEW_MODE] = mode
        }
    }

    val librarySortOption: Flow<String> = safeData.map { prefs ->
        prefs[stringPreferencesKey("library_sort_option")] ?: "RECENTLY_ADDED"
    }

    val librarySortAscending: Flow<Boolean> = safeData.map { prefs ->
        prefs[booleanPreferencesKey("library_sort_ascending")] ?: true
    }

    suspend fun saveLibrarySortOption(option: String, ascending: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("library_sort_option")] = option
            prefs[booleanPreferencesKey("library_sort_ascending")] = ascending
        }
    }

    /**
     * Persists the current playback and Up-Next queue order both in `SharedPreferences` (synchronously/immediately)
     * and in `DataStore` so even if the user closes/swipes away the app milliseconds after reordering the queue,
     * the custom queue order is 100% preserved on next launch.
     */
    suspend fun saveLastPlaybackState(songId: Long, position: Long, queueIds: List<Long>, queueIndex: Int, duration: Long = 0L) {
        val serializedIds = queueIds.joinToString(",")
        try {
            bootCachePrefs.edit().apply {
                putLong("last_song_id", songId)
                putLong("last_position", position)
                putString("last_queue_ids", serializedIds)
                putInt("last_queue_index", queueIndex)
                if (duration > 0L) {
                    putLong("last_duration", duration)
                }
                apply()
            }
        } catch (_: Exception) {}

        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.LAST_SONG_ID] = songId
            prefs[PreferenceKeys.LAST_POSITION] = position
            prefs[PreferenceKeys.LAST_QUEUE_IDS] = serializedIds
            prefs[PreferenceKeys.LAST_QUEUE_INDEX] = queueIndex
            if (duration > 0L) {
                prefs[PreferenceKeys.LAST_DURATION] = duration
            }
        }
    }

    suspend fun setAddToQueuePosition(position: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.ADD_TO_QUEUE_POSITION] = position
        }
    }

    suspend fun setPlaylistAddSongPosition(position: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.PLAYLIST_ADD_SONG_POSITION] = position
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        try {
            bootCachePrefs.edit()
                .putBoolean("initial_install_checked", true)
                .putBoolean("onboarding_completed", completed)
                .apply()
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.ONBOARDING_COMPLETED] = completed
        }
    }

    /**
     * Sets the theme mode ("DARK", "LIGHT", "SYSTEM").
     * If "LIGHT" is selected, Extra Dark Mode (`amoled_mode`) is automatically turned off.
     */
    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.THEME_MODE] = mode
            if (mode.equals("LIGHT", ignoreCase = true)) {
                prefs[PreferenceKeys.AMOLED_MODE] = false
            }
        }
    }

    suspend fun setUiTheme(themeId: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.UI_THEME] = themeId
        }
    }

    suspend fun setDisplayScale(scale: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.DISPLAY_SCALE] = scale
        }
    }

    suspend fun setHomeScreenLayout(layoutId: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.HOME_SCREEN_LAYOUT] = layoutId
        }
    }

    suspend fun setNowPlayingLayout(layoutId: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.NOW_PLAYING_LAYOUT] = layoutId
        }
    }

    suspend fun setPlaylistsLayout(layoutId: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.PLAYLISTS_LAYOUT] = layoutId
        }
    }

    suspend fun setDynamicArtworkTheme(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.DYNAMIC_ARTWORK_THEME] = enabled
        }
    }

    suspend fun setCustomAccentColor(colorInt: Int) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CUSTOM_ACCENT_COLOR] = colorInt
        }
    }

    suspend fun setLyricsFetchMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.LYRICS_FETCH_MODE] = mode
        }
    }

    suspend fun setRepeatMode(mode: Int) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.REPEAT_MODE] = mode
        }
    }

    suspend fun setShuffleMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SHUFFLE_MODE] = enabled
        }
    }

    suspend fun setHighRefreshRate(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.HIGH_REFRESH_RATE] = enabled
        }
    }

    suspend fun setFetchOnlineArtworkHighRes(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.FETCH_ONLINE_ARTWORK_HIGH_RES] = enabled
        }
    }

    suspend fun setCacheLyricsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CACHE_LYRICS_ENABLED] = enabled
        }
    }

    suspend fun setCacheAlbumArtEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CACHE_ALBUM_ART_ENABLED] = enabled
        }
    }

    suspend fun setCacheSongArtWorkEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CACHE_SONG_ART_WORK_ENABLED] = enabled
        }
    }

    suspend fun setCustomEqBands(bands: List<Float>, markAsCustom: Boolean = true) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CUSTOM_EQ_BANDS] = bands.joinToString(",")
            if (markAsCustom) {
                prefs[PreferenceKeys.EQUALIZER_PRESET] = "CUSTOM"
            }
        }
    }

    suspend fun setCustomBassBoost(strength: Int, markAsCustom: Boolean = true) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CUSTOM_BASS_BOOST] = strength
            if (markAsCustom) {
                prefs[PreferenceKeys.EQUALIZER_PRESET] = "CUSTOM"
            }
        }
    }

    suspend fun setCustomVirtualizer(strength: Int, markAsCustom: Boolean = true) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CUSTOM_VIRTUALIZER] = strength
            if (markAsCustom) {
                prefs[PreferenceKeys.EQUALIZER_PRESET] = "CUSTOM"
            }
        }
    }

    suspend fun clearLyricsCache() {
        try {
            val dir = context.cacheDir.resolve("lyrics")
            if (dir.exists()) dir.deleteRecursively()
            context.getSharedPreferences("lyrics_cache_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
    }

    suspend fun clearAlbumArtCache() {
        try {
            val dir = context.cacheDir.resolve("album_art")
            if (dir.exists()) dir.deleteRecursively()
            val imgDir = context.cacheDir.resolve("image_cache")
            if (imgDir.exists()) imgDir.deleteRecursively()
            context.getSharedPreferences("album_artworks_online_cache", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("artist_images_cache", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("album_images_cache", Context.MODE_PRIVATE).edit().clear().apply()
            com.example.ui.components.ArtworkResolutionCache.clear()
            coil.Coil.imageLoader(context).memoryCache?.clear()
        } catch (_: Exception) {}
    }

    suspend fun clearSongArtCache() {
        try {
            val dir = context.cacheDir.resolve("song_artworks")
            if (dir.exists()) dir.deleteRecursively()
            val dir2 = context.cacheDir.resolve("artworks")
            if (dir2.exists()) dir2.deleteRecursively()
            context.getSharedPreferences("song_artworks_online_cache", Context.MODE_PRIVATE).edit().clear().apply()
            com.example.ui.components.ArtworkResolutionCache.clear()
            coil.Coil.imageLoader(context).memoryCache?.clear()
        } catch (_: Exception) {}
    }

    fun getLyricsCacheSizeBytes(): Long {
        var size = 0L
        try {
            val dir = context.cacheDir.resolve("lyrics")
            if (dir.exists()) {
                size += dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
            val dbFile = context.getDatabasePath("offgrid_music_db")
            if (dbFile != null && dbFile.exists()) {
                size += (dbFile.length() / 4).coerceAtLeast(0L)
            }
        } catch (_: Exception) {}
        return size
    }

    fun getAlbumArtCacheSizeBytes(): Long {
        var size = 0L
        try {
            val dir = context.cacheDir.resolve("album_art")
            if (dir.exists()) size += dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val imgDir = context.cacheDir.resolve("image_cache")
            if (imgDir.exists()) size += imgDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {}
        return size
    }

    fun getSongArtCacheSizeBytes(): Long {
        var size = 0L
        try {
            val dir = context.cacheDir.resolve("song_artworks")
            if (dir.exists()) size += dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val dir2 = context.cacheDir.resolve("artworks")
            if (dir2.exists()) size += dir2.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val coversDir = context.filesDir.resolve("song_covers")
            if (coversDir.exists()) size += coversDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {}
        return size
    }

    fun getTotalCacheSizeBytes(): Long {
        var size = 0L
        try {
            context.cacheDir?.let { if (it.exists()) size += it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } }
            context.externalCacheDir?.let { if (it.exists()) size += it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } }
        } catch (_: Exception) {}
        return size
    }

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                fetchOnlineArtworkHighRes.collect { cachedFetchOnlineArtworkHighRes = it }
            } catch (_: Exception) {}
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                cacheAlbumArtEnabled.collect { cachedCacheAlbumArtEnabled = it }
            } catch (_: Exception) {}
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                cacheSongArtWorkEnabled.collect { cachedCacheSongArtWorkEnabled = it }
            } catch (_: Exception) {}
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                autoPlayOnHeadphoneConnection.collect { cachedAutoPlayOnHeadphoneConnection = it }
            } catch (_: Exception) {}
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                autoPlayOnAppStartup.collect { cachedAutoPlayOnAppStartup = it }
            } catch (_: Exception) {}
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: UserPreferencesRepository? = null

        fun getInstance(context: Context): UserPreferencesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserPreferencesRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0L) return "0.0 KB"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
                else -> String.format(java.util.Locale.US, "%.1f KB", kb)
            }
        }
    }
}