package com.example.util

import android.content.Context
import android.content.SharedPreferences

data class ManualSongMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val artworkUri: String? = null,
    val lyrics: String? = null
)

/**
 * Authoritative, durable store tracking which songs have had their metadata
 * manually edited by the user. Ensures manual edits are 100% immune to overwriting
 * during library scans, quick scans, startup indexing, and re-indexing.
 */
object ManualTagStore {
    private const val PREFS_NAME = "offgrid_manual_tag_store"
    private const val KEY_PREFIX_ID = "manual_id_"
    private const val KEY_PREFIX_PATH = "manual_path_"
    private const val KEY_DATA_ID = "manual_data_id_"
    private const val KEY_DATA_PATH = "manual_data_path_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun markManuallyEdited(
        context: Context,
        songId: Long,
        path: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
        year: Int? = null,
        trackNumber: Int? = null,
        artworkUri: String? = null,
        lyrics: String? = null
    ) {
        val prefs = getPrefs(context)
        val normPath = normalizePath(path)
        val editor = prefs.edit()
            .putBoolean("$KEY_PREFIX_ID$songId", true)
            .putBoolean("$KEY_PREFIX_PATH$normPath", true)

        val json = org.json.JSONObject().apply {
            if (!title.isNullOrBlank()) put("title", title)
            if (!artist.isNullOrBlank()) put("artist", artist)
            if (!album.isNullOrBlank()) put("album", album)
            if (!genre.isNullOrBlank()) put("genre", genre)
            if (year != null && year > 0) put("year", year)
            if (trackNumber != null && trackNumber > 0) put("track", trackNumber)
            if (!artworkUri.isNullOrBlank()) put("art", artworkUri)
            if (!lyrics.isNullOrBlank()) put("lyrics", lyrics)
        }.toString()

        if (songId > 0) editor.putString("$KEY_DATA_ID$songId", json)
        if (normPath.isNotBlank()) editor.putString("$KEY_DATA_PATH$normPath", json)

        editor.apply()
    }

    fun getManualMetadata(context: Context, songId: Long, path: String): ManualSongMetadata? {
        val prefs = getPrefs(context)
        val jsonStr = (if (songId > 0) prefs.getString("$KEY_DATA_ID$songId", null) else null)
            ?: (if (path.isNotBlank()) prefs.getString("$KEY_DATA_PATH${normalizePath(path)}", null) else null)
            ?: return null

        return try {
            val obj = org.json.JSONObject(jsonStr)
            ManualSongMetadata(
                title = obj.optString("title", ""),
                artist = obj.optString("artist", ""),
                album = obj.optString("album", ""),
                genre = obj.optString("genre", "").takeIf { it.isNotBlank() },
                year = if (obj.has("year")) obj.getInt("year") else null,
                trackNumber = if (obj.has("track")) obj.getInt("track") else null,
                artworkUri = obj.optString("art", "").takeIf { it.isNotBlank() },
                lyrics = obj.optString("lyrics", "").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun isManuallyEdited(context: Context, songId: Long, path: String): Boolean {
        val prefs = getPrefs(context)
        if (songId > 0 && prefs.getBoolean("$KEY_PREFIX_ID$songId", false)) return true
        val normPath = normalizePath(path)
        return if (normPath.isNotBlank()) prefs.getBoolean("$KEY_PREFIX_PATH$normPath", false) else false
    }

    fun clearManualEdit(context: Context, songId: Long, path: String) {
        val prefs = getPrefs(context)
        val normPath = normalizePath(path)
        prefs.edit()
            .remove("$KEY_PREFIX_ID$songId")
            .remove("$KEY_PREFIX_PATH$normPath")
            .remove("$KEY_DATA_ID$songId")
            .remove("$KEY_DATA_PATH$normPath")
            .apply()
    }

    private fun normalizePath(rawPath: String): String {
        return rawPath.trim().lowercase().replace('\\', '/')
    }
}
