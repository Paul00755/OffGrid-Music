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

    private val bootCachePrefs = context.getSharedPreferences("offgrid_boot_cache", Context.MODE_PRIVATE)

    private fun readBoolCached(key: String, default: Boolean): Boolean {
        return try {
            if (bootCachePrefs.contains("pref_$key")) bootCachePrefs.getBoolean("pref_$key", default) else default
        } catch (_: Exception) {
            default
        }
    }

    private fun writeBoolCached(key: String, value: Boolean, now: Long = System.currentTimeMillis()) {
        try {
            bootCachePrefs.edit()
                .putBoolean("pref_$key", value)
                .putLong("pref_settings_last_updated_at", now)
                .apply()
        } catch (_: Exception) {}
    }

    private fun readIntCached(key: String, default: Int): Int {
        return try {
            if (bootCachePrefs.contains("pref_$key")) bootCachePrefs.getInt("pref_$key", default) else default
        } catch (_: Exception) {
            default
        }
    }

    private fun writeIntCached(key: String, value: Int, now: Long = System.currentTimeMillis()) {
        try {
            bootCachePrefs.edit()
                .putInt("pref_$key", value)
                .putLong("pref_settings_last_updated_at", now)
                .apply()
        } catch (_: Exception) {}
    }

    private fun readFloatCached(key: String, default: Float): Float {
        return try {
            if (bootCachePrefs.contains("pref_$key")) bootCachePrefs.getFloat("pref_$key", default) else default
        } catch (_: Exception) {
            default
        }
    }

    private fun writeFloatCached(key: String, value: Float, now: Long = System.currentTimeMillis()) {
        try {
            bootCachePrefs.edit()
                .putFloat("pref_$key", value)
                .putLong("pref_settings_last_updated_at", now)
                .apply()
        } catch (_: Exception) {}
    }

    private fun readStringCached(key: String, default: String): String {
        return try {
            if (bootCachePrefs.contains("pref_$key")) (bootCachePrefs.getString("pref_$key", default) ?: default) else default
        } catch (_: Exception) {
            default
        }
    }

    private fun writeStringCached(key: String, value: String, now: Long = System.currentTimeMillis()) {
        try {
            bootCachePrefs.edit()
                .putString("pref_$key", value)
                .putLong("pref_settings_last_updated_at", now)
                .apply()
        } catch (_: Exception) {}
    }

    private fun readLongCached(key: String, default: Long): Long {
        return try {
            if (bootCachePrefs.contains("pref_$key")) bootCachePrefs.getLong("pref_$key", default) else default
        } catch (_: Exception) {
            default
        }
    }

    private fun writeLongCached(key: String, value: Long, now: Long = System.currentTimeMillis()) {
        try {
            bootCachePrefs.edit()
                .putLong("pref_$key", value)
                .putLong("pref_settings_last_updated_at", now)
                .apply()
        } catch (_: Exception) {}
    }

    private object PreferenceKeys {
        // App Updates & GitHub Release Notifications
        val UPDATE_NOTIFICATIONS_ENABLED = booleanPreferencesKey("update_notifications_enabled")
        val DISMISSED_UPDATE_TAG = stringPreferencesKey("dismissed_update_tag")
        val LAST_UPDATE_CHECK_TIMESTAMP = longPreferencesKey("last_update_check_timestamp")
        val CACHED_LATEST_RELEASE_JSON = stringPreferencesKey("cached_latest_release_json")
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
        val SLEEP_TIMER_MODE = stringPreferencesKey("sleep_timer_mode") // "TIME", "SONGS"
        val SLEEP_TIMER_FINISH_LAST_SONG = booleanPreferencesKey("sleep_timer_finish_last_song")
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
        val LIBRARY_SORT_OPTION = stringPreferencesKey("library_sort_option")
        val LIBRARY_SORT_ASCENDING = booleanPreferencesKey("library_sort_ascending")

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
        val PLAY_DURING_CAMERA = booleanPreferencesKey("play_during_camera") // Legacy combined key
        val AUTO_PAUSE_CAMERA = booleanPreferencesKey("auto_pause_camera") // Default: false (OFF)
        val AUTO_PAUSE_SYSTEM_AUDIO = booleanPreferencesKey("auto_pause_system_audio") // Default: true (ON)
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

    private fun resolveAutoPauseCamera(prefs: Preferences): Boolean {
        prefs[PreferenceKeys.AUTO_PAUSE_CAMERA]?.let { return it }
        if (bootCachePrefs.contains("pref_auto_pause_camera")) {
            return bootCachePrefs.getBoolean("pref_auto_pause_camera", false)
        }
        // Safe migration rule: never unexpectedly enable camera auto-pause for existing or new users.
        return false
    }

    private fun resolveAutoPauseSystemAudio(prefs: Preferences): Boolean {
        prefs[PreferenceKeys.AUTO_PAUSE_SYSTEM_AUDIO]?.let { return it }
        if (bootCachePrefs.contains("pref_auto_pause_system_audio")) {
            return bootCachePrefs.getBoolean("pref_auto_pause_system_audio", true)
        }
        // Safe migration rule: if legacy PLAY_DURING_CAMERA was explicitly saved,
        // play_during_camera == true meant "do not auto-pause on transient focus loss" -> autoPauseSystemAudio = false;
        // play_during_camera == false meant "auto-pause on transient focus loss" -> autoPauseSystemAudio = true.
        prefs[PreferenceKeys.PLAY_DURING_CAMERA]?.let { legacyPlayDuring ->
            return !legacyPlayDuring
        }
        if (bootCachePrefs.contains("pref_play_during_camera")) {
            return !bootCachePrefs.getBoolean("pref_play_during_camera", false)
        }
        return true
    }

    fun getAutoPauseCameraSync(): Boolean {
        if (bootCachePrefs.contains("pref_auto_pause_camera")) {
            return bootCachePrefs.getBoolean("pref_auto_pause_camera", false)
        }
        return false
    }

    fun getAutoPauseSystemAudioSync(): Boolean {
        if (bootCachePrefs.contains("pref_auto_pause_system_audio")) {
            return bootCachePrefs.getBoolean("pref_auto_pause_system_audio", true)
        }
        if (bootCachePrefs.contains("pref_play_during_camera")) {
            return !bootCachePrefs.getBoolean("pref_play_during_camera", false)
        }
        return true
    }

    /**
     * Performs a safe, one-time migration from the legacy combined `play_during_camera` preference
     * to the two independent `auto_pause_camera` and `auto_pause_system_audio` preferences without
     * overwriting any existing explicit user choice.
     */
    suspend fun migrateLegacyInterruptionSettingsIfNeeded() {
        context.dataStore.edit { prefs ->
            val hasCameraPref = prefs.contains(PreferenceKeys.AUTO_PAUSE_CAMERA) || bootCachePrefs.contains("pref_auto_pause_camera")
            val hasSystemAudioPref = prefs.contains(PreferenceKeys.AUTO_PAUSE_SYSTEM_AUDIO) || bootCachePrefs.contains("pref_auto_pause_system_audio")
            val hasLegacyPref = prefs.contains(PreferenceKeys.PLAY_DURING_CAMERA) || bootCachePrefs.contains("pref_play_during_camera")

            if (hasLegacyPref && (!hasCameraPref || !hasSystemAudioPref)) {
                val legacyPlayDuring = prefs[PreferenceKeys.PLAY_DURING_CAMERA]
                    ?: bootCachePrefs.getBoolean("pref_play_during_camera", false)
                if (!hasCameraPref) {
                    prefs[PreferenceKeys.AUTO_PAUSE_CAMERA] = false
                    bootCachePrefs.edit().putBoolean("pref_auto_pause_camera", false).apply()
                }
                if (!hasSystemAudioPref) {
                    val migratedSystemAudio = !legacyPlayDuring
                    prefs[PreferenceKeys.AUTO_PAUSE_SYSTEM_AUDIO] = migratedSystemAudio
                    bootCachePrefs.edit().putBoolean("pref_auto_pause_system_audio", migratedSystemAudio).apply()
                }
            }
        }
    }

    val isTranscribeTranslateEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.TRANSCRIBE_TRANSLATE_ENABLED] ?: readBoolCached("transcribe_translate_enabled", false)
    }

    val audioTranscribeMode: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUDIO_TRANSCRIBE_MODE] ?: readStringCached("audio_transcribe_mode", "OFF")
    }

    val audioTranscribeTargetLang: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUDIO_TRANSCRIBE_TARGET_LANG] ?: readStringCached("audio_transcribe_target_lang", "ta")
    }

    suspend fun setTranscribeTranslateEnabled(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("transcribe_translate_enabled", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.TRANSCRIBE_TRANSLATE_ENABLED] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setAudioTranscribeMode(mode: String) {
        val now = System.currentTimeMillis()
        writeStringCached("audio_transcribe_mode", mode, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.AUDIO_TRANSCRIBE_MODE] = mode
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setAudioTranscribeTargetLang(langCode: String) {
        val now = System.currentTimeMillis()
        writeStringCached("audio_transcribe_target_lang", langCode, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.AUDIO_TRANSCRIBE_TARGET_LANG] = langCode
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    val automaticBackupEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUTOMATIC_BACKUP_ENABLED] ?: readBoolCached("automatic_backup_enabled", true)
    }

    val backupFrequency: Flow<com.example.data.sync.BackupFrequency> = safeData.map { prefs ->
        com.example.data.sync.BackupFrequency.fromString(
            prefs[PreferenceKeys.BACKUP_FREQUENCY] ?: readStringCached("backup_frequency", "WEEKLY")
        )
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
        val now = System.currentTimeMillis()
        writeBoolCached("automatic_backup_enabled", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.AUTOMATIC_BACKUP_ENABLED] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setBackupFrequency(frequency: com.example.data.sync.BackupFrequency) {
        val now = System.currentTimeMillis()
        writeStringCached("backup_frequency", frequency.name, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.BACKUP_FREQUENCY] = frequency.name
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
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
        prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT]
            ?: bootCachePrefs.getLong("pref_settings_last_updated_at", 0L)
    }

    suspend fun touchSettingsUpdatedAt(timestamp: Long = System.currentTimeMillis()) {
        try {
            bootCachePrefs.edit().putLong("pref_settings_last_updated_at", timestamp).apply()
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = timestamp
        }
    }

    suspend fun exportUserSettings(): com.example.data.sync.CloudUserSettings {
        val prefs = safeData.first()
        val storedThemeMode = prefs[PreferenceKeys.THEME_MODE] ?: readStringCached("theme_mode", "DARK")
        val effectiveThemeMode = if (!storedThemeMode.equals("LIGHT", ignoreCase = true) &&
            (prefs[PreferenceKeys.AMOLED_MODE] == true || storedThemeMode.equals("AMOLED", ignoreCase = true))) "AMOLED" else storedThemeMode
        val resolvedCameraPause = resolveAutoPauseCamera(prefs)
        val resolvedSystemAudioPause = resolveAutoPauseSystemAudio(prefs)
        return com.example.data.sync.CloudUserSettings(
            themeMode = effectiveThemeMode,
            uiTheme = prefs[PreferenceKeys.UI_THEME] ?: readStringCached("ui_theme", "MIDNIGHT_OBSIDIAN"),
            homeScreenLayout = prefs[PreferenceKeys.HOME_SCREEN_LAYOUT] ?: readStringCached("home_screen_layout", "BENTO_DYNAMIC"),
            nowPlayingLayout = prefs[PreferenceKeys.NOW_PLAYING_LAYOUT] ?: readStringCached("now_playing_layout", "MINIMAL_GLASS"),
            playlistsLayout = prefs[PreferenceKeys.PLAYLISTS_LAYOUT] ?: readStringCached("playlists_layout", "GLOW_CARDS"),
            accentColorPreset = prefs[PreferenceKeys.ACCENT_COLOR_PRESET] ?: readStringCached("accent_color_preset", "ORANGE"),
            customAccentColor = prefs[PreferenceKeys.CUSTOM_ACCENT_COLOR] ?: readIntCached("custom_accent_color", 0),
            dynamicArtworkTheme = prefs[PreferenceKeys.DYNAMIC_ARTWORK_THEME] ?: readBoolCached("dynamic_artwork_theme", true),
            amoledMode = false,
            cornerRadiusDp = prefs[PreferenceKeys.CORNER_RADIUS_DP] ?: readIntCached("corner_radius_dp", 16),
            fontSizeScale = prefs[PreferenceKeys.FONT_SIZE_SCALE] ?: readStringCached("font_size_scale", "MEDIUM"),
            artworkSize = prefs[PreferenceKeys.ARTWORK_SIZE] ?: readStringCached("artwork_size", "MEDIUM"),
            showArtist = prefs[PreferenceKeys.SHOW_ARTIST] ?: readBoolCached("show_artist", true),
            showAlbum = prefs[PreferenceKeys.SHOW_ALBUM] ?: readBoolCached("show_album", true),
            showAudioOutput = prefs[PreferenceKeys.SHOW_AUDIO_OUTPUT] ?: readBoolCached("show_audio_output", true),
            miniPlayerStyle = prefs[PreferenceKeys.MINI_PLAYER_STYLE] ?: readStringCached("mini_player_style", "FLOATING"),
            miniPlayerShowPrevious = prefs[PreferenceKeys.MINI_PLAYER_SHOW_PREVIOUS] ?: readBoolCached("mini_player_show_previous", true),
            miniPlayerShowNext = prefs[PreferenceKeys.MINI_PLAYER_SHOW_NEXT] ?: readBoolCached("mini_player_show_next", true),
            miniPlayerShowFavorite = prefs[PreferenceKeys.MINI_PLAYER_SHOW_FAVORITE] ?: readBoolCached("mini_player_show_favorite", true),
            waveformStyle = prefs[PreferenceKeys.WAVEFORM_STYLE] ?: readStringCached("waveform_style", "SNAKE"),
            lyricsFetchMode = prefs[PreferenceKeys.LYRICS_FETCH_MODE] ?: readStringCached("lyrics_fetch_mode", "WIFI_ONLY"),
            lyricsAutoScroll = prefs[PreferenceKeys.LYRICS_AUTO_SCROLL] ?: readBoolCached("lyrics_auto_scroll", true),
            lyricsFontSize = prefs[PreferenceKeys.LYRICS_FONT_SIZE] ?: readStringCached("lyrics_font_size", "MEDIUM"),
            gaplessPlayback = prefs[PreferenceKeys.GAPLESS_PLAYBACK] ?: readBoolCached("gapless_playback", true),
            crossfadeDurationSec = prefs[PreferenceKeys.CROSSFADE_DURATION_SEC] ?: readIntCached("crossfade_duration_sec", 0),
            normalizeAudio = prefs[PreferenceKeys.NORMALIZE_AUDIO] ?: readBoolCached("normalize_audio", false),
            autoPlayOnHeadphoneConnection = prefs[PreferenceKeys.AUTO_PLAY_ON_HEADPHONE_CONNECTION] ?: readBoolCached("auto_play_on_headphone_connection", false),
            pauseOnZeroVolume = prefs[PreferenceKeys.PAUSE_ON_ZERO_VOLUME] ?: readBoolCached("pause_on_zero_volume", true),
            autoResumeOnVolumeIncrease = prefs[PreferenceKeys.AUTO_RESUME_ON_VOLUME_INCREASE] ?: readBoolCached("auto_resume_on_volume_increase", true),
            fadeInOutMs = prefs[PreferenceKeys.FADE_IN_OUT_MS] ?: readIntCached("fade_in_out_ms", 1000),
            fadeInMs = prefs[PreferenceKeys.FADE_IN_MS] ?: prefs[PreferenceKeys.FADE_IN_OUT_MS] ?: readIntCached("fade_in_ms", 1000),
            fadeOutMs = prefs[PreferenceKeys.FADE_OUT_MS] ?: prefs[PreferenceKeys.FADE_IN_OUT_MS] ?: readIntCached("fade_out_ms", 1000),
            shakeToSkip = prefs[PreferenceKeys.SHAKE_TO_SKIP] ?: readBoolCached("shake_to_skip", false),
            keepScreenAwake = prefs[PreferenceKeys.KEEP_SCREEN_AWAKE] ?: readBoolCached("keep_screen_awake", false),
            audioBalance = prefs[PreferenceKeys.AUDIO_BALANCE] ?: readFloatCached("audio_balance", 0f),
            equalizerPreset = prefs[PreferenceKeys.EQUALIZER_PRESET] ?: readStringCached("equalizer_preset", "NORMAL"),
            customEqBands = prefs[PreferenceKeys.CUSTOM_EQ_BANDS] ?: readStringCached("custom_eq_bands", "0.0,0.0,0.0,0.0,0.0"),
            customBassBoost = prefs[PreferenceKeys.CUSTOM_BASS_BOOST] ?: readIntCached("custom_bass_boost", 0),
            customVirtualizer = prefs[PreferenceKeys.CUSTOM_VIRTUALIZER] ?: readIntCached("custom_virtualizer", 0),
            reduceVolumeOnNotification = prefs[PreferenceKeys.REDUCE_VOLUME_ON_NOTIFICATION] ?: readBoolCached("reduce_volume_on_notification", true),
            playDuringCamera = !resolvedSystemAudioPause,
            autoPauseCamera = resolvedCameraPause,
            autoPauseSystemAudio = resolvedSystemAudioPause,
            sleepTimerFinishLastSong = prefs[PreferenceKeys.SLEEP_TIMER_FINISH_LAST_SONG] ?: readBoolCached("sleep_timer_finish_last_song", false),
            scrobbleThresholdType = prefs[PreferenceKeys.SCROBBLE_THRESHOLD_TYPE] ?: readStringCached("scrobble_threshold_type", "SECONDS"),
            scrobbleThresholdValue = prefs[PreferenceKeys.SCROBBLE_THRESHOLD_VALUE] ?: readIntCached("scrobble_threshold_value", 30),
            repeatMode = prefs[PreferenceKeys.REPEAT_MODE] ?: readIntCached("repeat_mode", 0),
            shuffleMode = prefs[PreferenceKeys.SHUFFLE_MODE] ?: readBoolCached("shuffle_mode", false),
            alwaysShuffleMode = prefs[PreferenceKeys.ALWAYS_SHUFFLE_MODE] ?: readBoolCached("always_shuffle_mode", false),
            gesturePlaybackEnabled = prefs[PreferenceKeys.GESTURE_PLAYBACK_ENABLED] ?: readBoolCached("gesture_playback_enabled", true),
            highRefreshRate = prefs[PreferenceKeys.HIGH_REFRESH_RATE] ?: readBoolCached("high_refresh_rate", true),
            addToQueuePosition = prefs[PreferenceKeys.ADD_TO_QUEUE_POSITION] ?: readStringCached("add_to_queue_position", "BOTTOM"),
            playlistAddSongPosition = prefs[PreferenceKeys.PLAYLIST_ADD_SONG_POSITION] ?: readStringCached("playlist_add_song_position", "BOTTOM"),
            playlistViewMode = prefs[PreferenceKeys.PLAYLIST_VIEW_MODE] ?: readStringCached("playlist_view_mode", "GRID"),
            homeSections = prefs[PreferenceKeys.HOME_SECTIONS] ?: readStringCached("home_sections", "continue_listening,quick_actions,listening_stats,most_played,most_played_playlists,recently_played,recently_added,top_artists,top_albums"),
            databaseStorageTier = prefs[PreferenceKeys.DATABASE_STORAGE_TIER] ?: readStringCached("database_storage_tier", "EXPANDED"),
            customDisplayName = prefs[PreferenceKeys.CUSTOM_DISPLAY_NAME],
            customAvatarUri = prefs[PreferenceKeys.CUSTOM_AVATAR_URI],
            ignoreAudioDuration = prefs[PreferenceKeys.IGNORE_AUDIO_DURATION] ?: readIntCached("ignore_audio_duration", 0),
            librarySortOption = prefs[PreferenceKeys.LIBRARY_SORT_OPTION] ?: readStringCached("library_sort_option", "RECENTLY_ADDED"),
            librarySortAscending = prefs[PreferenceKeys.LIBRARY_SORT_ASCENDING] ?: readBoolCached("library_sort_ascending", true),
            sleepTimerMode = prefs[PreferenceKeys.SLEEP_TIMER_MODE] ?: readStringCached("sleep_timer_mode", "TIME"),
            resumeOnBluetooth = prefs[PreferenceKeys.RESUME_ON_BLUETOOTH] ?: readBoolCached("resume_on_bluetooth", false),
            pauseOnHeadsetUnplug = prefs[PreferenceKeys.PAUSE_ON_HEADSET_UNPLUG] ?: readBoolCached("pause_on_headset_unplug", true),
            notificationEnabled = prefs[PreferenceKeys.NOTIFICATION_ENABLED] ?: readBoolCached("notification_enabled", true),
            autoplayOnAppStartup = prefs[PreferenceKeys.AUTOPLAY_ON_APP_STARTUP] ?: readBoolCached("autoplay_on_app_startup", false),
            fetchOnlineArtworkHighRes = prefs[PreferenceKeys.FETCH_ONLINE_ARTWORK_HIGH_RES] ?: readBoolCached("fetch_online_artwork_high_res", false),
            cacheLyricsEnabled = prefs[PreferenceKeys.CACHE_LYRICS_ENABLED] ?: readBoolCached("cache_lyrics_enabled", true),
            cacheAlbumArtEnabled = prefs[PreferenceKeys.CACHE_ALBUM_ART_ENABLED] ?: readBoolCached("cache_album_art_enabled", true),
            cacheSongArtWorkEnabled = prefs[PreferenceKeys.CACHE_SONG_ART_WORK_ENABLED] ?: readBoolCached("cache_song_art_work_enabled", true),
            transcribeTranslateEnabled = prefs[PreferenceKeys.TRANSCRIBE_TRANSLATE_ENABLED] ?: readBoolCached("transcribe_translate_enabled", false),
            audioTranscribeMode = prefs[PreferenceKeys.AUDIO_TRANSCRIBE_MODE] ?: readStringCached("audio_transcribe_mode", "OFF"),
            audioTranscribeTargetLang = prefs[PreferenceKeys.AUDIO_TRANSCRIBE_TARGET_LANG] ?: readStringCached("audio_transcribe_target_lang", "ta"),
            automaticBackupEnabled = prefs[PreferenceKeys.AUTOMATIC_BACKUP_ENABLED] ?: readBoolCached("automatic_backup_enabled", true),
            backupFrequency = prefs[PreferenceKeys.BACKUP_FREQUENCY] ?: readStringCached("backup_frequency", "WEEKLY"),
            updatedAt = prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] ?: bootCachePrefs.getLong("pref_settings_last_updated_at", 0L)
        )
    }

    suspend fun importUserSettings(cloud: com.example.data.sync.CloudUserSettings, checkTimestamp: Boolean = true): Boolean {
        var applied = false
        context.dataStore.edit { prefs ->
            val localUpdated = prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT]
                ?: bootCachePrefs.getLong("pref_settings_last_updated_at", 0L)
            if (!checkTimestamp || localUpdated == 0L || cloud.updatedAt > localUpdated) {
                val migratedThemeMode = if (!cloud.themeMode.equals("LIGHT", ignoreCase = true) && cloud.amoledMode) "AMOLED" else cloud.themeMode
                val resolvedUpdated = if (cloud.updatedAt > 0L) cloud.updatedAt else System.currentTimeMillis()
                prefs[PreferenceKeys.THEME_MODE] = migratedThemeMode
                prefs[PreferenceKeys.UI_THEME] = cloud.uiTheme
                prefs[PreferenceKeys.HOME_SCREEN_LAYOUT] = cloud.homeScreenLayout
                prefs[PreferenceKeys.NOW_PLAYING_LAYOUT] = cloud.nowPlayingLayout
                prefs[PreferenceKeys.PLAYLISTS_LAYOUT] = cloud.playlistsLayout
                prefs[PreferenceKeys.ACCENT_COLOR_PRESET] = cloud.accentColorPreset
                prefs[PreferenceKeys.CUSTOM_ACCENT_COLOR] = cloud.customAccentColor
                prefs[PreferenceKeys.DYNAMIC_ARTWORK_THEME] = cloud.dynamicArtworkTheme
                prefs[PreferenceKeys.AMOLED_MODE] = false
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
                prefs[PreferenceKeys.AUTO_PAUSE_CAMERA] = cloud.autoPauseCamera
                prefs[PreferenceKeys.AUTO_PAUSE_SYSTEM_AUDIO] = cloud.autoPauseSystemAudio
                prefs[PreferenceKeys.SLEEP_TIMER_FINISH_LAST_SONG] = cloud.sleepTimerFinishLastSong
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
                prefs[PreferenceKeys.LIBRARY_SORT_OPTION] = cloud.librarySortOption
                prefs[PreferenceKeys.LIBRARY_SORT_ASCENDING] = cloud.librarySortAscending
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
                prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = resolvedUpdated

                try {
                    bootCachePrefs.edit()
                        .putString("pref_theme_mode", migratedThemeMode)
                        .putString("pref_ui_theme", cloud.uiTheme)
                        .putString("pref_home_screen_layout", cloud.homeScreenLayout)
                        .putString("pref_now_playing_layout", cloud.nowPlayingLayout)
                        .putString("pref_playlists_layout", cloud.playlistsLayout)
                        .putString("pref_accent_color_preset", cloud.accentColorPreset)
                        .putInt("pref_custom_accent_color", cloud.customAccentColor)
                        .putBoolean("pref_dynamic_artwork_theme", cloud.dynamicArtworkTheme)
                        .putInt("pref_corner_radius_dp", cloud.cornerRadiusDp)
                        .putString("pref_font_size_scale", cloud.fontSizeScale)
                        .putString("pref_artwork_size", cloud.artworkSize)
                        .putBoolean("pref_show_artist", cloud.showArtist)
                        .putBoolean("pref_show_album", cloud.showAlbum)
                        .putBoolean("pref_show_audio_output", cloud.showAudioOutput)
                        .putString("pref_mini_player_style", cloud.miniPlayerStyle)
                        .putBoolean("pref_mini_player_show_previous", cloud.miniPlayerShowPrevious)
                        .putBoolean("pref_mini_player_show_next", cloud.miniPlayerShowNext)
                        .putBoolean("pref_mini_player_show_favorite", cloud.miniPlayerShowFavorite)
                        .putString("pref_waveform_style", cloud.waveformStyle)
                        .putString("pref_lyrics_fetch_mode", cloud.lyricsFetchMode)
                        .putBoolean("pref_lyrics_auto_scroll", cloud.lyricsAutoScroll)
                        .putString("pref_lyrics_font_size", cloud.lyricsFontSize)
                        .putBoolean("pref_gapless_playback", cloud.gaplessPlayback)
                        .putInt("pref_crossfade_duration_sec", cloud.crossfadeDurationSec)
                        .putBoolean("pref_normalize_audio", cloud.normalizeAudio)
                        .putBoolean("pref_auto_play_on_headphone_connection", cloud.autoPlayOnHeadphoneConnection)
                        .putBoolean("pref_pause_on_zero_volume", cloud.pauseOnZeroVolume)
                        .putBoolean("pref_auto_resume_on_volume_increase", cloud.autoResumeOnVolumeIncrease)
                        .putInt("pref_fade_in_out_ms", cloud.fadeInOutMs)
                        .putInt("pref_fade_in_ms", cloud.fadeInMs)
                        .putInt("pref_fade_out_ms", cloud.fadeOutMs)
                        .putBoolean("pref_shake_to_skip", cloud.shakeToSkip)
                        .putBoolean("pref_keep_screen_awake", cloud.keepScreenAwake)
                        .putFloat("pref_audio_balance", cloud.audioBalance)
                        .putString("pref_equalizer_preset", cloud.equalizerPreset)
                        .putString("pref_custom_eq_bands", cloud.customEqBands)
                        .putInt("pref_custom_bass_boost", cloud.customBassBoost)
                        .putInt("pref_custom_virtualizer", cloud.customVirtualizer)
                        .putBoolean("pref_reduce_volume_on_notification", cloud.reduceVolumeOnNotification)
                        .putBoolean("pref_play_during_camera", cloud.playDuringCamera)
                        .putBoolean("pref_auto_pause_camera", cloud.autoPauseCamera)
                        .putBoolean("pref_auto_pause_system_audio", cloud.autoPauseSystemAudio)
                        .putBoolean("pref_sleep_timer_finish_last_song", cloud.sleepTimerFinishLastSong)
                        .putString("pref_scrobble_threshold_type", cloud.scrobbleThresholdType)
                        .putInt("pref_scrobble_threshold_value", cloud.scrobbleThresholdValue)
                        .putInt("pref_repeat_mode", cloud.repeatMode)
                        .putBoolean("pref_shuffle_mode", cloud.shuffleMode)
                        .putBoolean("pref_always_shuffle_mode", cloud.alwaysShuffleMode)
                        .putBoolean("pref_gesture_playback_enabled", cloud.gesturePlaybackEnabled)
                        .putBoolean("pref_high_refresh_rate", cloud.highRefreshRate)
                        .putString("pref_add_to_queue_position", cloud.addToQueuePosition)
                        .putString("pref_playlist_add_song_position", cloud.playlistAddSongPosition)
                        .putString("pref_playlist_view_mode", cloud.playlistViewMode)
                        .putString("pref_home_sections", cloud.homeSections)
                        .putString("pref_database_storage_tier", cloud.databaseStorageTier)
                        .putInt("pref_ignore_audio_duration", cloud.ignoreAudioDuration)
                        .putString("pref_library_sort_option", cloud.librarySortOption)
                        .putBoolean("pref_library_sort_ascending", cloud.librarySortAscending)
                        .putString("pref_sleep_timer_mode", cloud.sleepTimerMode)
                        .putBoolean("pref_resume_on_bluetooth", cloud.resumeOnBluetooth)
                        .putBoolean("pref_pause_on_headset_unplug", cloud.pauseOnHeadsetUnplug)
                        .putBoolean("pref_notification_enabled", cloud.notificationEnabled)
                        .putBoolean("pref_autoplay_on_app_startup", cloud.autoplayOnAppStartup)
                        .putBoolean("pref_fetch_online_artwork_high_res", cloud.fetchOnlineArtworkHighRes)
                        .putBoolean("pref_cache_lyrics_enabled", cloud.cacheLyricsEnabled)
                        .putBoolean("pref_cache_album_art_enabled", cloud.cacheAlbumArtEnabled)
                        .putBoolean("pref_cache_song_art_work_enabled", cloud.cacheSongArtWorkEnabled)
                        .putBoolean("pref_transcribe_translate_enabled", cloud.transcribeTranslateEnabled)
                        .putString("pref_audio_transcribe_mode", cloud.audioTranscribeMode)
                        .putString("pref_audio_transcribe_target_lang", cloud.audioTranscribeTargetLang)
                        .putBoolean("pref_automatic_backup_enabled", cloud.automaticBackupEnabled)
                        .putString("pref_backup_frequency", cloud.backupFrequency)
                        .putLong("pref_settings_last_updated_at", resolvedUpdated)
                        .apply()
                } catch (_: Exception) {}
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
        val now = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            if (name != null) {
                prefs[PreferenceKeys.CUSTOM_DISPLAY_NAME] = name
            } else {
                prefs.remove(PreferenceKeys.CUSTOM_DISPLAY_NAME)
            }
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    val customAvatarUri: Flow<String?> = safeData.map { prefs ->
        prefs[PreferenceKeys.CUSTOM_AVATAR_URI]
    }

    suspend fun setCustomAvatarUri(uri: String?) {
        val now = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            if (uri != null) {
                prefs[PreferenceKeys.CUSTOM_AVATAR_URI] = uri
            } else {
                prefs.remove(PreferenceKeys.CUSTOM_AVATAR_URI)
            }
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
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
        prefs[PreferenceKeys.DATABASE_STORAGE_TIER] ?: readStringCached("database_storage_tier", "EXPANDED")
    }
    fun getDatabaseStorageTierSync(): String = readStringCached("database_storage_tier", "EXPANDED")

    suspend fun setDatabaseStorageTier(tier: String) {
        val now = System.currentTimeMillis()
        writeStringCached("database_storage_tier", tier, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.DATABASE_STORAGE_TIER] = tier
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    val cacheLyricsEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.CACHE_LYRICS_ENABLED] ?: readBoolCached("cache_lyrics_enabled", true)
    }
    fun getCacheLyricsEnabledSync(): Boolean = readBoolCached("cache_lyrics_enabled", true)

    val cacheAlbumArtEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.CACHE_ALBUM_ART_ENABLED] ?: readBoolCached("cache_album_art_enabled", true)
    }
    fun getCacheAlbumArtEnabledSync(): Boolean = readBoolCached("cache_album_art_enabled", true)

    val cacheSongArtWorkEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.CACHE_SONG_ART_WORK_ENABLED] ?: readBoolCached("cache_song_art_work_enabled", true)
    }
    fun getCacheSongArtWorkEnabledSync(): Boolean = readBoolCached("cache_song_art_work_enabled", true)

    @Volatile var cachedFetchOnlineArtworkHighRes: Boolean = readBoolCached("fetch_online_artwork_high_res", false)
        private set
    @Volatile var cachedCacheAlbumArtEnabled: Boolean = readBoolCached("cache_album_art_enabled", true)
        private set
    @Volatile var cachedCacheSongArtWorkEnabled: Boolean = readBoolCached("cache_song_art_work_enabled", true)
        private set
    @Volatile var cachedAutoPlayOnHeadphoneConnection: Boolean = readBoolCached("auto_play_on_headphone_connection", false)
    @Volatile var cachedAutoPlayOnAppStartup: Boolean = readBoolCached("autoplay_on_app_startup", false)

    val customEqBands: Flow<List<Float>> = safeData.map { prefs ->
        val raw = prefs[PreferenceKeys.CUSTOM_EQ_BANDS] ?: readStringCached("custom_eq_bands", "0.0,0.0,0.0,0.0,0.0")
        try {
            raw.split(",").map { it.trim().toFloat() }
        } catch (_: Exception) {
            listOf(0f, 0f, 0f, 0f, 0f)
        }
    }
    fun getCustomEqBandsSync(): List<Float> {
        val raw = readStringCached("custom_eq_bands", "0.0,0.0,0.0,0.0,0.0")
        return try {
            raw.split(",").map { it.trim().toFloat() }
        } catch (_: Exception) {
            listOf(0f, 0f, 0f, 0f, 0f)
        }
    }

    val customBassBoost: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.CUSTOM_BASS_BOOST] ?: readIntCached("custom_bass_boost", 0)
    }
    fun getCustomBassBoostSync(): Int = readIntCached("custom_bass_boost", 0)

    val customVirtualizer: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.CUSTOM_VIRTUALIZER] ?: readIntCached("custom_virtualizer", 0)
    }
    fun getCustomVirtualizerSync(): Int = readIntCached("custom_virtualizer", 0)

    val fetchOnlineArtworkHighRes: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.FETCH_ONLINE_ARTWORK_HIGH_RES] ?: readBoolCached("fetch_online_artwork_high_res", false)
    }
    fun getFetchOnlineArtworkHighResSync(): Boolean = readBoolCached("fetch_online_artwork_high_res", false)

    val alwaysShuffleMode: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.ALWAYS_SHUFFLE_MODE] ?: readBoolCached("always_shuffle_mode", false)
    }
    fun getAlwaysShuffleModeSync(): Boolean = readBoolCached("always_shuffle_mode", false)

    val gesturePlaybackEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.GESTURE_PLAYBACK_ENABLED] ?: readBoolCached("gesture_playback_enabled", true)
    }
    fun getGesturePlaybackEnabledSync(): Boolean = readBoolCached("gesture_playback_enabled", true)

    private val defaultHomeSectionsRaw = "continue_listening,quick_actions,listening_stats,most_played,most_played_playlists,recently_played,recently_added,top_artists,top_albums"

    val homeSections: Flow<List<String>> = safeData.map { prefs ->
        val raw = prefs[PreferenceKeys.HOME_SECTIONS] ?: readStringCached("home_sections", defaultHomeSectionsRaw)
        raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
    fun getHomeSectionsSync(): List<String> {
        val raw = readStringCached("home_sections", defaultHomeSectionsRaw)
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    suspend fun setHomeSections(sections: List<String>) {
        val now = System.currentTimeMillis()
        val serialized = sections.joinToString(",")
        writeStringCached("home_sections", serialized, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.HOME_SECTIONS] = serialized
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    val highRefreshRate: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.HIGH_REFRESH_RATE] ?: readBoolCached("high_refresh_rate", true)
    }
    fun getHighRefreshRateSync(): Boolean = readBoolCached("high_refresh_rate", true)

    fun getTranscribeTranslateEnabledSync(): Boolean = readBoolCached("transcribe_translate_enabled", false)
    fun getAudioTranscribeModeSync(): String = readStringCached("audio_transcribe_mode", "OFF")
    fun getAudioTranscribeTargetLangSync(): String = readStringCached("audio_transcribe_target_lang", "ta")
    fun getAutomaticBackupEnabledSync(): Boolean = readBoolCached("automatic_backup_enabled", true)
    fun getBackupFrequencySync(): com.example.data.sync.BackupFrequency =
        com.example.data.sync.BackupFrequency.fromString(readStringCached("backup_frequency", "WEEKLY"))

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
        val savedMode = prefs[PreferenceKeys.THEME_MODE] ?: readStringCached("theme_mode", "DARK")
        if (!savedMode.equals("LIGHT", ignoreCase = true) && prefs[PreferenceKeys.AMOLED_MODE] == true) "AMOLED" else savedMode
    }
    fun getThemeModeSync(): String = readStringCached("theme_mode", "DARK")

    val uiTheme: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.UI_THEME] ?: readStringCached("ui_theme", "MIDNIGHT_OBSIDIAN")
    }
    fun getUiThemeSync(): String = readStringCached("ui_theme", "MIDNIGHT_OBSIDIAN")

    val displayScale: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.DISPLAY_SCALE] ?: readStringCached("display_scale", "DEFAULT")
    }
    fun getDisplayScaleSync(): String = readStringCached("display_scale", "DEFAULT")

    val homeScreenLayout: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.HOME_SCREEN_LAYOUT] ?: readStringCached("home_screen_layout", "BENTO_DYNAMIC")
    }
    fun getHomeScreenLayoutSync(): String = readStringCached("home_screen_layout", "BENTO_DYNAMIC")

    val nowPlayingLayout: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.NOW_PLAYING_LAYOUT] ?: readStringCached("now_playing_layout", "MINIMAL_GLASS")
    }
    fun getNowPlayingLayoutSync(): String = readStringCached("now_playing_layout", "MINIMAL_GLASS")

    val playlistsLayout: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.PLAYLISTS_LAYOUT] ?: readStringCached("playlists_layout", "GLOW_CARDS")
    }
    fun getPlaylistsLayoutSync(): String = readStringCached("playlists_layout", "GLOW_CARDS")

    val isDynamicArtworkTheme: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.DYNAMIC_ARTWORK_THEME] ?: readBoolCached("dynamic_artwork_theme", true)
    }
    fun getDynamicArtworkThemeSync(): Boolean = readBoolCached("dynamic_artwork_theme", true)

    val customAccentColor: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.CUSTOM_ACCENT_COLOR] ?: readIntCached("custom_accent_color", 0)
    }
    fun getCustomAccentColorSync(): Int = readIntCached("custom_accent_color", 0)

    val lyricsFetchMode: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.LYRICS_FETCH_MODE] ?: readStringCached("lyrics_fetch_mode", "WIFI_ONLY")
    }
    fun getLyricsFetchModeSync(): String = readStringCached("lyrics_fetch_mode", "WIFI_ONLY")

    val sleepTimerMode: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.SLEEP_TIMER_MODE] ?: readStringCached("sleep_timer_mode", "TIME")
    }
    fun getSleepTimerModeSync(): String = readStringCached("sleep_timer_mode", "TIME")

    val sleepTimerFinishLastSong: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.SLEEP_TIMER_FINISH_LAST_SONG] ?: readBoolCached("sleep_timer_finish_last_song", false)
    }
    fun getSleepTimerFinishLastSongSync(): Boolean = readBoolCached("sleep_timer_finish_last_song", false)

    suspend fun setSleepTimerMode(mode: String) {
        val now = System.currentTimeMillis()
        writeStringCached("sleep_timer_mode", mode, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SLEEP_TIMER_MODE] = mode
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setSleepTimerFinishLastSong(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("sleep_timer_finish_last_song", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SLEEP_TIMER_FINISH_LAST_SONG] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    val resumeOnBluetooth: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.RESUME_ON_BLUETOOTH] ?: readBoolCached("resume_on_bluetooth", false)
    }
    fun getResumeOnBluetoothSync(): Boolean = readBoolCached("resume_on_bluetooth", false)

    suspend fun setResumeOnBluetooth(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("resume_on_bluetooth", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.RESUME_ON_BLUETOOTH] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    val pauseOnHeadsetUnplug: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.PAUSE_ON_HEADSET_UNPLUG] ?: readBoolCached("pause_on_headset_unplug", true)
    }
    fun getPauseOnHeadsetUnplugSync(): Boolean = readBoolCached("pause_on_headset_unplug", true)

    suspend fun setPauseOnHeadsetUnplug(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("pause_on_headset_unplug", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.PAUSE_ON_HEADSET_UNPLUG] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    val repeatMode: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.REPEAT_MODE] ?: readIntCached("repeat_mode", 0)
    }
    fun getRepeatModeSync(): Int = readIntCached("repeat_mode", 0)

    val shuffleMode: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.SHUFFLE_MODE] ?: readBoolCached("shuffle_mode", false)
    }
    fun getShuffleModeSync(): Boolean = readBoolCached("shuffle_mode", false)

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
        val raw = prefs[PreferenceKeys.ADD_TO_QUEUE_POSITION] ?: readStringCached("add_to_queue_position", "BOTTOM")
        if (raw.equals("TOP_NEXT", ignoreCase = true)) "TOP_NEXT" else "BOTTOM"
    }
    fun getAddToQueuePositionSync(): String {
        val raw = readStringCached("add_to_queue_position", "BOTTOM")
        return if (raw.equals("TOP_NEXT", ignoreCase = true)) "TOP_NEXT" else "BOTTOM"
    }

    val playlistAddSongPosition: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.PLAYLIST_ADD_SONG_POSITION] ?: readStringCached("playlist_add_song_position", "BOTTOM")
    }
    fun getPlaylistAddSongPositionSync(): String = readStringCached("playlist_add_song_position", "BOTTOM")

    val playlistViewMode: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.PLAYLIST_VIEW_MODE] ?: readStringCached("playlist_view_mode", "GRID")
    }
    fun getPlaylistViewModeSync(): String = readStringCached("playlist_view_mode", "GRID")

    val waveformStyle: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.WAVEFORM_STYLE] ?: readStringCached("waveform_style", "SNAKE")
    }
    fun getWaveformStyleSync(): String = readStringCached("waveform_style", "SNAKE")

    /** Compatibility projection for old callers; the saved theme mode is the source of truth. */
    val amoledMode: Flow<Boolean> = themeMode.map { it.equals("AMOLED", ignoreCase = true) }

    val accentColorPreset: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.ACCENT_COLOR_PRESET] ?: readStringCached("accent_color_preset", "ORANGE")
    }
    fun getAccentColorPresetSync(): String = readStringCached("accent_color_preset", "ORANGE")

    val cornerRadiusDp: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.CORNER_RADIUS_DP] ?: readIntCached("corner_radius_dp", 16)
    }
    fun getCornerRadiusDpSync(): Int = readIntCached("corner_radius_dp", 16)

    val fontSizeScale: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.FONT_SIZE_SCALE] ?: readStringCached("font_size_scale", "MEDIUM")
    }
    fun getFontSizeScaleSync(): String = readStringCached("font_size_scale", "MEDIUM")

    val artworkSize: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.ARTWORK_SIZE] ?: readStringCached("artwork_size", "MEDIUM")
    }
    fun getArtworkSizeSync(): String = readStringCached("artwork_size", "MEDIUM")

    val showArtist: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.SHOW_ARTIST] ?: readBoolCached("show_artist", true)
    }
    fun getShowArtistSync(): Boolean = readBoolCached("show_artist", true)

    val showAlbum: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.SHOW_ALBUM] ?: readBoolCached("show_album", true)
    }
    fun getShowAlbumSync(): Boolean = readBoolCached("show_album", true)

    val showAudioOutput: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.SHOW_AUDIO_OUTPUT] ?: readBoolCached("show_audio_output", true)
    }
    fun getShowAudioOutputSync(): Boolean = readBoolCached("show_audio_output", true)

    val miniPlayerStyle: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.MINI_PLAYER_STYLE] ?: readStringCached("mini_player_style", "FLOATING")
    }
    fun getMiniPlayerStyleSync(): String = readStringCached("mini_player_style", "FLOATING")

    val miniPlayerShowPrevious: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.MINI_PLAYER_SHOW_PREVIOUS] ?: readBoolCached("mini_player_show_previous", true)
    }
    fun getMiniPlayerShowPreviousSync(): Boolean = readBoolCached("mini_player_show_previous", true)

    val miniPlayerShowNext: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.MINI_PLAYER_SHOW_NEXT] ?: readBoolCached("mini_player_show_next", true)
    }
    fun getMiniPlayerShowNextSync(): Boolean = readBoolCached("mini_player_show_next", true)

    val miniPlayerShowFavorite: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.MINI_PLAYER_SHOW_FAVORITE] ?: readBoolCached("mini_player_show_favorite", true)
    }
    fun getMiniPlayerShowFavoriteSync(): Boolean = readBoolCached("mini_player_show_favorite", true)

    val gaplessPlayback: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.GAPLESS_PLAYBACK] ?: readBoolCached("gapless_playback", true)
    }
    fun getGaplessPlaybackSync(): Boolean = readBoolCached("gapless_playback", true)

    val normalizeAudio: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.NORMALIZE_AUDIO] ?: readBoolCached("normalize_audio", false)
    }
    fun getNormalizeAudioSync(): Boolean = readBoolCached("normalize_audio", false)

    val autoPlayOnHeadphoneConnection: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUTO_PLAY_ON_HEADPHONE_CONNECTION] ?: readBoolCached("auto_play_on_headphone_connection", false)
    }
    fun getAutoPlayOnHeadphoneConnectionSync(): Boolean = readBoolCached("auto_play_on_headphone_connection", false)

    val autoPlayOnAppStartup: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUTOPLAY_ON_APP_STARTUP] ?: readBoolCached("autoplay_on_app_startup", false)
    }
    fun getAutoPlayOnAppStartupSync(): Boolean = readBoolCached("autoplay_on_app_startup", false)

    val crossfadeDurationSec: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.CROSSFADE_DURATION_SEC] ?: readIntCached("crossfade_duration_sec", 0)
    }
    fun getCrossfadeDurationSecSync(): Int = readIntCached("crossfade_duration_sec", 0)

    val lyricsAutoScroll: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.LYRICS_AUTO_SCROLL] ?: readBoolCached("lyrics_auto_scroll", true)
    }
    fun getLyricsAutoScrollSync(): Boolean = readBoolCached("lyrics_auto_scroll", true)

    val lyricsFontSize: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.LYRICS_FONT_SIZE] ?: readStringCached("lyrics_font_size", "MEDIUM")
    }
    fun getLyricsFontSizeSync(): String = readStringCached("lyrics_font_size", "MEDIUM")

    val notificationEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.NOTIFICATION_ENABLED] ?: readBoolCached("notification_enabled", true)
    }
    fun getNotificationEnabledSync(): Boolean = readBoolCached("notification_enabled", true)

    val pauseOnZeroVolume: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.PAUSE_ON_ZERO_VOLUME] ?: readBoolCached("pause_on_zero_volume", true)
    }
    fun getPauseOnZeroVolumeSync(): Boolean = readBoolCached("pause_on_zero_volume", true)

    val autoResumeOnVolumeIncrease: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUTO_RESUME_ON_VOLUME_INCREASE] ?: readBoolCached("auto_resume_on_volume_increase", true)
    }
    fun getAutoResumeOnVolumeIncreaseSync(): Boolean = readBoolCached("auto_resume_on_volume_increase", true)

    val ignoreAudioDuration: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.IGNORE_AUDIO_DURATION] ?: readIntCached("ignore_audio_duration", 0)
    }
    fun getIgnoreAudioDurationSync(): Int = readIntCached("ignore_audio_duration", 0)

    val maxSongDuration: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.MAX_SONG_DURATION] ?: readIntCached("max_song_duration_sec", 300)
    }
    fun getMaxSongDurationSync(): Int = readIntCached("max_song_duration_sec", 300)

    val fullDeviceScan: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.FULL_DEVICE_SCAN] ?: readBoolCached("full_device_scan", false)
    }
    fun getFullDeviceScanSync(): Boolean = readBoolCached("full_device_scan", false)

    val fadeInOutMs: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.FADE_IN_OUT_MS] ?: readIntCached("fade_in_out_ms", 1000)
    }
    fun getFadeInOutMsSync(): Int = readIntCached("fade_in_out_ms", 1000)

    val fadeInMs: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.FADE_IN_MS] ?: prefs[PreferenceKeys.FADE_IN_OUT_MS] ?: readIntCached("fade_in_ms", readIntCached("fade_in_out_ms", 1000))
    }
    fun getFadeInMsSync(): Int = readIntCached("fade_in_ms", readIntCached("fade_in_out_ms", 1000))

    val fadeOutMs: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.FADE_OUT_MS] ?: prefs[PreferenceKeys.FADE_IN_OUT_MS] ?: readIntCached("fade_out_ms", readIntCached("fade_in_out_ms", 1000))
    }
    fun getFadeOutMsSync(): Int = readIntCached("fade_out_ms", readIntCached("fade_in_out_ms", 1000))

    val shakeToSkip: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.SHAKE_TO_SKIP] ?: readBoolCached("shake_to_skip", false)
    }
    fun getShakeToSkipSync(): Boolean = readBoolCached("shake_to_skip", false)

    val keepScreenAwake: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.KEEP_SCREEN_AWAKE] ?: readBoolCached("keep_screen_awake", false)
    }
    fun getKeepScreenAwakeSync(): Boolean = readBoolCached("keep_screen_awake", false)

    val audioBalance: Flow<Float> = safeData.map { prefs ->
        prefs[PreferenceKeys.AUDIO_BALANCE] ?: readFloatCached("audio_balance", 0f)
    }
    fun getAudioBalanceSync(): Float = readFloatCached("audio_balance", 0f)

    val equalizerPreset: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.EQUALIZER_PRESET] ?: readStringCached("equalizer_preset", "NORMAL")
    }
    fun getEqualizerPresetSync(): String = readStringCached("equalizer_preset", "NORMAL")

    val reduceVolumeOnNotification: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.REDUCE_VOLUME_ON_NOTIFICATION] ?: readBoolCached("reduce_volume_on_notification", true)
    }
    fun getReduceVolumeOnNotificationSync(): Boolean = readBoolCached("reduce_volume_on_notification", true)

    val playDuringCamera: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.PLAY_DURING_CAMERA] ?: readBoolCached("play_during_camera", false)
    }

    val autoPauseCamera: Flow<Boolean> = safeData.map { prefs ->
        resolveAutoPauseCamera(prefs)
    }

    val autoPauseSystemAudio: Flow<Boolean> = safeData.map { prefs ->
        resolveAutoPauseSystemAudio(prefs)
    }

    val scrobbleThresholdType: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.SCROBBLE_THRESHOLD_TYPE] ?: readStringCached("scrobble_threshold_type", "SECONDS")
    }
    fun getScrobbleThresholdTypeSync(): String = readStringCached("scrobble_threshold_type", "SECONDS")

    val scrobbleThresholdValue: Flow<Int> = safeData.map { prefs ->
        prefs[PreferenceKeys.SCROBBLE_THRESHOLD_VALUE] ?: readIntCached("scrobble_threshold_value", 30)
    }
    fun getScrobbleThresholdValueSync(): Int = readIntCached("scrobble_threshold_value", 30)

    suspend fun setScrobbleThresholdType(type: String) {
        val now = System.currentTimeMillis()
        writeStringCached("scrobble_threshold_type", type, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SCROBBLE_THRESHOLD_TYPE] = type
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setScrobbleThresholdValue(value: Int) {
        val now = System.currentTimeMillis()
        writeIntCached("scrobble_threshold_value", value, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SCROBBLE_THRESHOLD_VALUE] = value
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setReduceVolumeOnNotification(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("reduce_volume_on_notification", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.REDUCE_VOLUME_ON_NOTIFICATION] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setPlayDuringCamera(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("play_during_camera", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.PLAY_DURING_CAMERA] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setAutoPauseCamera(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("auto_pause_camera", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.AUTO_PAUSE_CAMERA] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setAutoPauseSystemAudio(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("auto_pause_system_audio", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.AUTO_PAUSE_SYSTEM_AUDIO] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setPauseOnZeroVolume(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("pause_on_zero_volume", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.PAUSE_ON_ZERO_VOLUME] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setAutoResumeOnVolumeIncrease(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("auto_resume_on_volume_increase", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.AUTO_RESUME_ON_VOLUME_INCREASE] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setIgnoreAudioDuration(durationSec: Int) {
        val now = System.currentTimeMillis()
        writeIntCached("ignore_audio_duration", durationSec, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.IGNORE_AUDIO_DURATION] = durationSec
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setMaxSongDuration(durationSec: Int) {
        val now = System.currentTimeMillis()
        writeIntCached("max_song_duration_sec", durationSec, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.MAX_SONG_DURATION] = durationSec
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setFullDeviceScan(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("full_device_scan", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.FULL_DEVICE_SCAN] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setSongDurationRange(minSec: Int, maxSec: Int) {
        val now = System.currentTimeMillis()
        try {
            bootCachePrefs.edit()
                .putInt("pref_ignore_audio_duration", minSec)
                .putInt("pref_max_song_duration_sec", maxSec)
                .putLong("pref_settings_last_updated_at", now)
                .apply()
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.IGNORE_AUDIO_DURATION] = minSec
            prefs[PreferenceKeys.MAX_SONG_DURATION] = maxSec
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setFadeInOutMs(ms: Int) {
        val now = System.currentTimeMillis()
        try {
            bootCachePrefs.edit()
                .putInt("pref_fade_in_out_ms", ms)
                .putInt("pref_fade_in_ms", ms)
                .putInt("pref_fade_out_ms", ms)
                .putLong("pref_settings_last_updated_at", now)
                .apply()
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.FADE_IN_OUT_MS] = ms
            prefs[PreferenceKeys.FADE_IN_MS] = ms
            prefs[PreferenceKeys.FADE_OUT_MS] = ms
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setFadeInMs(ms: Int) {
        val now = System.currentTimeMillis()
        writeIntCached("fade_in_ms", ms, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.FADE_IN_MS] = ms
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setFadeOutMs(ms: Int) {
        val now = System.currentTimeMillis()
        writeIntCached("fade_out_ms", ms, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.FADE_OUT_MS] = ms
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setShakeToSkip(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("shake_to_skip", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SHAKE_TO_SKIP] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setKeepScreenAwake(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("keep_screen_awake", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEEP_SCREEN_AWAKE] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setAudioBalance(balance: Float) {
        val now = System.currentTimeMillis()
        writeFloatCached("audio_balance", balance, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.AUDIO_BALANCE] = balance
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setEqualizerPreset(preset: String) {
        val now = System.currentTimeMillis()
        val eqPreset = com.example.ui.components.EQ_PRESETS.find { it.id.equals(preset, ignoreCase = true) }
        try {
            val editor = bootCachePrefs.edit()
                .putString("pref_equalizer_preset", preset)
                .putLong("pref_settings_last_updated_at", now)
            if (eqPreset != null) {
                editor.putString("pref_custom_eq_bands", eqPreset.bands.joinToString(","))
                editor.putInt("pref_custom_bass_boost", eqPreset.bassBoost)
                editor.putInt("pref_custom_virtualizer", eqPreset.virtualizer)
            }
            editor.apply()
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.EQUALIZER_PRESET] = preset
            if (eqPreset != null) {
                prefs[PreferenceKeys.CUSTOM_EQ_BANDS] = eqPreset.bands.joinToString(",")
                prefs[PreferenceKeys.CUSTOM_BASS_BOOST] = eqPreset.bassBoost
                prefs[PreferenceKeys.CUSTOM_VIRTUALIZER] = eqPreset.virtualizer
            }
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setWaveformStyle(style: String) {
        val now = System.currentTimeMillis()
        writeStringCached("waveform_style", style, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.WAVEFORM_STYLE] = style
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    /** Legacy compatibility entry point; AMOLED is now selected through the single theme mode. */
    suspend fun setAmoledMode(enabled: Boolean) {
        setThemeMode(if (enabled) "AMOLED" else "DARK")
    }

    suspend fun setAccentColorPreset(preset: String) {
        val now = System.currentTimeMillis()
        writeStringCached("accent_color_preset", preset, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.ACCENT_COLOR_PRESET] = preset
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setCornerRadiusDp(radius: Int) {
        val now = System.currentTimeMillis()
        writeIntCached("corner_radius_dp", radius, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CORNER_RADIUS_DP] = radius
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setFontSizeScale(scale: String) {
        val now = System.currentTimeMillis()
        writeStringCached("font_size_scale", scale, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.FONT_SIZE_SCALE] = scale
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setArtworkSize(size: String) {
        val now = System.currentTimeMillis()
        writeStringCached("artwork_size", size, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.ARTWORK_SIZE] = size
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setShowArtist(show: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("show_artist", show, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SHOW_ARTIST] = show
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setShowAlbum(show: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("show_album", show, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SHOW_ALBUM] = show
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setShowAudioOutput(show: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("show_audio_output", show, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SHOW_AUDIO_OUTPUT] = show
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setMiniPlayerStyle(style: String) {
        val now = System.currentTimeMillis()
        writeStringCached("mini_player_style", style, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.MINI_PLAYER_STYLE] = style
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setMiniPlayerShowPrevious(show: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("mini_player_show_previous", show, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.MINI_PLAYER_SHOW_PREVIOUS] = show
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setMiniPlayerShowNext(show: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("mini_player_show_next", show, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.MINI_PLAYER_SHOW_NEXT] = show
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setMiniPlayerShowFavorite(show: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("mini_player_show_favorite", show, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.MINI_PLAYER_SHOW_FAVORITE] = show
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setGaplessPlayback(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("gapless_playback", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.GAPLESS_PLAYBACK] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setNormalizeAudio(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("normalize_audio", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.NORMALIZE_AUDIO] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setAutoPlayOnHeadphoneConnection(enabled: Boolean) {
        val now = System.currentTimeMillis()
        cachedAutoPlayOnHeadphoneConnection = enabled
        writeBoolCached("auto_play_on_headphone_connection", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.AUTO_PLAY_ON_HEADPHONE_CONNECTION] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setAutoPlayOnAppStartup(enabled: Boolean) {
        val now = System.currentTimeMillis()
        cachedAutoPlayOnAppStartup = enabled
        writeBoolCached("autoplay_on_app_startup", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.AUTOPLAY_ON_APP_STARTUP] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setCrossfadeDurationSec(sec: Int) {
        val now = System.currentTimeMillis()
        writeIntCached("crossfade_duration_sec", sec, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CROSSFADE_DURATION_SEC] = sec
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setLyricsAutoScroll(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("lyrics_auto_scroll", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.LYRICS_AUTO_SCROLL] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setLyricsFontSize(size: String) {
        val now = System.currentTimeMillis()
        writeStringCached("lyrics_font_size", size, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.LYRICS_FONT_SIZE] = size
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setAlwaysShuffleMode(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("always_shuffle_mode", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.ALWAYS_SHUFFLE_MODE] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setGesturePlaybackEnabled(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("gesture_playback_enabled", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.GESTURE_PLAYBACK_ENABLED] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
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

    fun getAllLyricsSyncOffsets(): Map<Long, Long> {
        return try {
            context.getSharedPreferences("lyrics_offsets_prefs", Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
                val songId = key.removePrefix("offset_").takeIf { key.startsWith("offset_") }?.toLongOrNull()
                val offset = (value as? Number)?.toLong()
                if (songId != null && songId > 0L && offset != null && offset != 0L) songId to offset else null
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Applies a batch of lyrics offsets with one disk commit; replace mode removes stale offsets. */
    fun applyLyricsSyncOffsets(
        offsetsBySongId: Map<Long, Long>,
        replace: Boolean = false,
        clearSongIds: Set<Long> = emptySet(),
        timestampsBySongId: Map<Long, Long> = emptyMap()
    ) {
        try {
            val prefs = context.getSharedPreferences("lyrics_offsets_prefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (replace) {
                prefs.all.keys.filter { it.startsWith("offset_") }.forEach(editor::remove)
            } else {
                clearSongIds.forEach { songId -> if (songId > 0L) editor.remove("offset_$songId") }
            }
            offsetsBySongId.forEach { (songId, offset) ->
                if (songId <= 0L) return@forEach
                val key = "offset_$songId"
                if (offset == 0L) editor.remove(key) else editor.putLong(key, offset)
                timestampsBySongId[songId]?.let { editor.putLong("offset_updated_$songId", it) }
            }
            editor.commit()
        } catch (_: Exception) {}
    }

    fun setLyricsSyncOffsetMs(songId: Long, offsetMs: Long) {
        if (songId <= 0) return
        try {
            val sp = context.getSharedPreferences("lyrics_offsets_prefs", Context.MODE_PRIVATE)
            val editor = sp.edit().putLong("offset_updated_$songId", System.currentTimeMillis())
            if (offsetMs == 0L) editor.remove("offset_$songId") else editor.putLong("offset_$songId", offsetMs)
            editor.commit()
        } catch (_: Exception) {}
    }

    fun getLyricsSyncOffsetUpdatedAt(songId: Long): Long = try {
        if (songId <= 0) 0L else context.getSharedPreferences("lyrics_offsets_prefs", Context.MODE_PRIVATE)
            .getLong("offset_updated_$songId", 0L)
    } catch (_: Exception) { 0L }

    suspend fun setNotificationEnabled(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("notification_enabled", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.NOTIFICATION_ENABLED] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setPlaylistViewMode(mode: String) {
        val now = System.currentTimeMillis()
        writeStringCached("playlist_view_mode", mode, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.PLAYLIST_VIEW_MODE] = mode
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    val librarySortOption: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.LIBRARY_SORT_OPTION] ?: readStringCached("library_sort_option", "RECENTLY_ADDED")
    }
    fun getLibrarySortOptionSync(): String = readStringCached("library_sort_option", "RECENTLY_ADDED")

    val librarySortAscending: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.LIBRARY_SORT_ASCENDING] ?: readBoolCached("library_sort_ascending", true)
    }
    fun getLibrarySortAscendingSync(): Boolean = readBoolCached("library_sort_ascending", true)

    suspend fun saveLibrarySortOption(option: String, ascending: Boolean) {
        val now = System.currentTimeMillis()
        try {
            bootCachePrefs.edit()
                .putString("pref_library_sort_option", option)
                .putBoolean("pref_library_sort_ascending", ascending)
                .putLong("pref_settings_last_updated_at", now)
                .apply()
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.LIBRARY_SORT_OPTION] = option
            prefs[PreferenceKeys.LIBRARY_SORT_ASCENDING] = ascending
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
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
        val normalized = if (position.equals("TOP_NEXT", ignoreCase = true)) "TOP_NEXT" else "BOTTOM"
        val now = System.currentTimeMillis()
        try {
            bootCachePrefs.edit()
                .putBoolean("pref_add_to_queue_position_explicit_v1", true)
                .putBoolean("migrated_add_to_queue_bottom_default_v1", true)
                .apply()
        } catch (_: Exception) {}
        writeStringCached("add_to_queue_position", normalized, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.ADD_TO_QUEUE_POSITION] = normalized
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setPlaylistAddSongPosition(position: String) {
        val now = System.currentTimeMillis()
        writeStringCached("playlist_add_song_position", position, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.PLAYLIST_ADD_SONG_POSITION] = position
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
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
     * Sets the theme mode ("DARK", "LIGHT", "SYSTEM", "AMOLED").
     * If "LIGHT" is selected, Extra Dark Mode (`amoled_mode`) is automatically turned off.
     */
    suspend fun setThemeMode(mode: String, compatibleUiThemeId: String? = null) {
        val now = System.currentTimeMillis()
        val normalized = mode.uppercase()
        try {
            val editor = bootCachePrefs.edit()
                .putString("pref_theme_mode", normalized)
                .putLong("pref_settings_last_updated_at", now)
            compatibleUiThemeId?.let { editor.putString("pref_ui_theme", it) }
            editor.apply()
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.THEME_MODE] = normalized
            prefs[PreferenceKeys.AMOLED_MODE] = false
            compatibleUiThemeId?.let { prefs[PreferenceKeys.UI_THEME] = it }
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setUiTheme(themeId: String) {
        val now = System.currentTimeMillis()
        writeStringCached("ui_theme", themeId, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.UI_THEME] = themeId
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setDisplayScale(scale: String) {
        val now = System.currentTimeMillis()
        writeStringCached("display_scale", scale, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.DISPLAY_SCALE] = scale
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setHomeScreenLayout(layoutId: String) {
        val now = System.currentTimeMillis()
        writeStringCached("home_screen_layout", layoutId, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.HOME_SCREEN_LAYOUT] = layoutId
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setNowPlayingLayout(layoutId: String) {
        val now = System.currentTimeMillis()
        writeStringCached("now_playing_layout", layoutId, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.NOW_PLAYING_LAYOUT] = layoutId
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setPlaylistsLayout(layoutId: String) {
        val now = System.currentTimeMillis()
        writeStringCached("playlists_layout", layoutId, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.PLAYLISTS_LAYOUT] = layoutId
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setDynamicArtworkTheme(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("dynamic_artwork_theme", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.DYNAMIC_ARTWORK_THEME] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setCustomAccentColor(colorInt: Int) {
        val now = System.currentTimeMillis()
        writeIntCached("custom_accent_color", colorInt, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CUSTOM_ACCENT_COLOR] = colorInt
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setLyricsFetchMode(mode: String) {
        val now = System.currentTimeMillis()
        writeStringCached("lyrics_fetch_mode", mode, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.LYRICS_FETCH_MODE] = mode
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setRepeatMode(mode: Int) {
        val now = System.currentTimeMillis()
        writeIntCached("repeat_mode", mode, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.REPEAT_MODE] = mode
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setShuffleMode(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("shuffle_mode", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.SHUFFLE_MODE] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setHighRefreshRate(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("high_refresh_rate", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.HIGH_REFRESH_RATE] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setFetchOnlineArtworkHighRes(enabled: Boolean) {
        val now = System.currentTimeMillis()
        cachedFetchOnlineArtworkHighRes = enabled
        try {
            bootCachePrefs.edit()
                .putBoolean("pref_fetch_online_artwork_high_res_explicit_v2", true)
                .putBoolean("migrated_prefer_hires_default_off_v2", true)
                .apply()
        } catch (_: Exception) {}
        writeBoolCached("fetch_online_artwork_high_res", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.FETCH_ONLINE_ARTWORK_HIGH_RES] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setCacheLyricsEnabled(enabled: Boolean) {
        val now = System.currentTimeMillis()
        writeBoolCached("cache_lyrics_enabled", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CACHE_LYRICS_ENABLED] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setCacheAlbumArtEnabled(enabled: Boolean) {
        val now = System.currentTimeMillis()
        cachedCacheAlbumArtEnabled = enabled
        writeBoolCached("cache_album_art_enabled", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CACHE_ALBUM_ART_ENABLED] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setCacheSongArtWorkEnabled(enabled: Boolean) {
        val now = System.currentTimeMillis()
        cachedCacheSongArtWorkEnabled = enabled
        writeBoolCached("cache_song_art_work_enabled", enabled, now)
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CACHE_SONG_ART_WORK_ENABLED] = enabled
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setCustomEqBands(bands: List<Float>, markAsCustom: Boolean = true) {
        val now = System.currentTimeMillis()
        val serialized = bands.joinToString(",")
        try {
            val editor = bootCachePrefs.edit()
                .putString("pref_custom_eq_bands", serialized)
                .putLong("pref_settings_last_updated_at", now)
            if (markAsCustom) {
                editor.putString("pref_equalizer_preset", "CUSTOM")
            }
            editor.apply()
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CUSTOM_EQ_BANDS] = serialized
            if (markAsCustom) {
                prefs[PreferenceKeys.EQUALIZER_PRESET] = "CUSTOM"
            }
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setCustomBassBoost(strength: Int, markAsCustom: Boolean = true) {
        val now = System.currentTimeMillis()
        try {
            val editor = bootCachePrefs.edit()
                .putInt("pref_custom_bass_boost", strength)
                .putLong("pref_settings_last_updated_at", now)
            if (markAsCustom) {
                editor.putString("pref_equalizer_preset", "CUSTOM")
            }
            editor.apply()
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CUSTOM_BASS_BOOST] = strength
            if (markAsCustom) {
                prefs[PreferenceKeys.EQUALIZER_PRESET] = "CUSTOM"
            }
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
        }
    }

    suspend fun setCustomVirtualizer(strength: Int, markAsCustom: Boolean = true) {
        val now = System.currentTimeMillis()
        try {
            val editor = bootCachePrefs.edit()
                .putInt("pref_custom_virtualizer", strength)
                .putLong("pref_settings_last_updated_at", now)
            if (markAsCustom) {
                editor.putString("pref_equalizer_preset", "CUSTOM")
            }
            editor.apply()
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.CUSTOM_VIRTUALIZER] = strength
            if (markAsCustom) {
                prefs[PreferenceKeys.EQUALIZER_PRESET] = "CUSTOM"
            }
            prefs[PreferenceKeys.SETTINGS_LAST_UPDATED_AT] = now
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
        val needsHiResDefaultOffMigration = try {
            val alreadyMigrated = bootCachePrefs.getBoolean("migrated_prefer_hires_default_off_v2", false)
            val explicitlySet = bootCachePrefs.getBoolean("pref_fetch_online_artwork_high_res_explicit_v2", false)
            if (!alreadyMigrated) {
                if (!explicitlySet) {
                    cachedFetchOnlineArtworkHighRes = false
                    bootCachePrefs.edit()
                        .putBoolean("pref_fetch_online_artwork_high_res", false)
                        .putBoolean("migrated_prefer_hires_default_off_v2", true)
                        .apply()
                    true
                } else {
                    bootCachePrefs.edit()
                        .putBoolean("migrated_prefer_hires_default_off_v2", true)
                        .apply()
                    false
                }
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
        val needsAddToQueueBottomDefaultMigration = try {
            val alreadyMigrated = bootCachePrefs.getBoolean("migrated_add_to_queue_bottom_default_v1", false)
            val explicitlySet = bootCachePrefs.getBoolean("pref_add_to_queue_position_explicit_v1", false)
            if (!alreadyMigrated) {
                if (!explicitlySet) {
                    bootCachePrefs.edit()
                        .putString("pref_add_to_queue_position", "BOTTOM")
                        .putBoolean("migrated_add_to_queue_bottom_default_v1", true)
                        .apply()
                    true
                } else {
                    bootCachePrefs.edit()
                        .putBoolean("migrated_add_to_queue_bottom_default_v1", true)
                        .apply()
                    false
                }
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                if (needsHiResDefaultOffMigration && !bootCachePrefs.getBoolean("pref_fetch_online_artwork_high_res_explicit_v2", false)) {
                    context.dataStore.edit { prefs ->
                        prefs[PreferenceKeys.FETCH_ONLINE_ARTWORK_HIGH_RES] = false
                    }
                }
                if (needsAddToQueueBottomDefaultMigration && !bootCachePrefs.getBoolean("pref_add_to_queue_position_explicit_v1", false)) {
                    context.dataStore.edit { prefs ->
                        prefs[PreferenceKeys.ADD_TO_QUEUE_POSITION] = "BOTTOM"
                    }
                }
                migrateLegacyInterruptionSettingsIfNeeded()
            } catch (_: Exception) {}
        }
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

    val updateNotificationsEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[PreferenceKeys.UPDATE_NOTIFICATIONS_ENABLED]
            ?: readBoolCached("update_notifications_enabled", true)
    }

    suspend fun setUpdateNotificationsEnabled(enabled: Boolean) {
        writeBoolCached("update_notifications_enabled", enabled)
        context.dataStore.edit { it[PreferenceKeys.UPDATE_NOTIFICATIONS_ENABLED] = enabled }
    }

    fun getUpdateNotificationsEnabledSync(): Boolean = readBoolCached("update_notifications_enabled", true)

    val dismissedUpdateTag: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.DISMISSED_UPDATE_TAG] ?: readStringCached("dismissed_update_tag", "")
    }

    suspend fun setDismissedUpdateTag(tag: String) {
        writeStringCached("dismissed_update_tag", tag)
        context.dataStore.edit { it[PreferenceKeys.DISMISSED_UPDATE_TAG] = tag }
    }

    fun getDismissedUpdateTagSync(): String = readStringCached("dismissed_update_tag", "")

    val lastUpdateCheckTimestamp: Flow<Long> = safeData.map { prefs ->
        prefs[PreferenceKeys.LAST_UPDATE_CHECK_TIMESTAMP] ?: readLongCached("last_update_check_timestamp", 0L)
    }

    suspend fun setLastUpdateCheckTimestamp(timestamp: Long) {
        writeLongCached("last_update_check_timestamp", timestamp)
        context.dataStore.edit { it[PreferenceKeys.LAST_UPDATE_CHECK_TIMESTAMP] = timestamp }
    }

    fun getLastUpdateCheckTimestampSync(): Long = readLongCached("last_update_check_timestamp", 0L)

    val cachedLatestReleaseJson: Flow<String> = safeData.map { prefs ->
        prefs[PreferenceKeys.CACHED_LATEST_RELEASE_JSON] ?: readStringCached("cached_latest_release_json", "")
    }

    suspend fun setCachedLatestReleaseJson(json: String) {
        writeStringCached("cached_latest_release_json", json)
        context.dataStore.edit { it[PreferenceKeys.CACHED_LATEST_RELEASE_JSON] = json }
    }

    fun getCachedLatestReleaseJsonSync(): String = readStringCached("cached_latest_release_json", "")

    suspend fun clearAllForTesting() {
        bootCachePrefs.edit().clear().commit()
        context.dataStore.edit { it.clear() }
    }

    companion object {
        @Volatile
        private var INSTANCE: UserPreferencesRepository? = null

        fun getInstance(context: Context): UserPreferencesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserPreferencesRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun resetInstanceForTesting(context: Context? = null) {
            synchronized(this) {
                INSTANCE = null
                if (context != null) {
                    try {
                        context.getSharedPreferences("offgrid_boot_cache", Context.MODE_PRIVATE).edit().clear().commit()
                        val dsDir = context.filesDir.resolve("datastore")
                        if (dsDir.exists()) dsDir.deleteRecursively()
                    } catch (_: Throwable) {}
                }
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
