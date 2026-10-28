package com.example.util

import android.content.Context
import android.content.SharedPreferences

data class ManualSongMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val artworkUri: String? = null,
    val lyrics: String? = null,
    val titleEdited: Boolean = false,
    val artistEdited: Boolean = false,
    val albumEdited: Boolean = false,
    val genreEdited: Boolean = false,
    val yearEdited: Boolean = false,
    val trackEdited: Boolean = false,
    val artworkEdited: Boolean = false,
    val lyricsEdited: Boolean = false,
    val artworkGeneration: Long = 0L
)

/**
 * Authoritative, durable store tracking which songs have had their metadata or artwork
 * manually edited by the user at the individual field level.
 *
 * Ensures:
 * - Case 1 (Manual title edit only): Preserves manual title while keeping artwork eligible for fetch/upgrade.
 * - Case 2 (Manual artwork edit only): Preserves manual artwork while keeping normal title rules active.
 * - Case 3 (Both manually edited): Preserves both manual title and manual artwork independently.
 * - Case 4 (Neither manually edited): Normal metadata and artwork retrieval rules apply.
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

    @Synchronized
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
        lyrics: String? = null,
        titleEdited: Boolean? = null,
        artistEdited: Boolean? = null,
        albumEdited: Boolean? = null,
        genreEdited: Boolean? = null,
        yearEdited: Boolean? = null,
        trackEdited: Boolean? = null,
        artworkEdited: Boolean? = null,
        lyricsEdited: Boolean? = null
    ) {
        val existing = getManualMetadata(context, songId, path)
        val isTitleEd = (titleEdited ?: !title.isNullOrBlank()) || (existing?.titleEdited == true)
        val isArtistEd = (artistEdited ?: !artist.isNullOrBlank()) || (existing?.artistEdited == true)
        val isAlbumEd = (albumEdited ?: !album.isNullOrBlank()) || (existing?.albumEdited == true)
        val isGenreEd = (genreEdited ?: !genre.isNullOrBlank()) || (existing?.genreEdited == true)
        val isYearEd = (yearEdited ?: (year != null && year > 0)) || (existing?.yearEdited == true)
        val isTrackEd = (trackEdited ?: (trackNumber != null && trackNumber > 0)) || (existing?.trackEdited == true)
        val didEditArtNow = artworkEdited ?: !artworkUri.isNullOrBlank()
        val isArtEd = didEditArtNow || (existing?.artworkEdited == true)
        val isLyricsEd = (lyricsEdited ?: !lyrics.isNullOrBlank()) || (existing?.lyricsEdited == true)

        val resolvedTitle = when {
            isTitleEd && !title.isNullOrBlank() -> title
            existing?.titleEdited == true && existing.title.isNotBlank() -> existing.title
            else -> title.orEmpty()
        }
        val resolvedArtist = when {
            isArtistEd && !artist.isNullOrBlank() -> artist
            existing?.artistEdited == true && existing.artist.isNotBlank() -> existing.artist
            else -> artist.orEmpty()
        }
        val resolvedAlbum = when {
            isAlbumEd && !album.isNullOrBlank() -> album
            existing?.albumEdited == true && existing.album.isNotBlank() -> existing.album
            else -> album.orEmpty()
        }
        val resolvedGenre = when {
            isGenreEd && !genre.isNullOrBlank() -> genre
            existing?.genreEdited == true && !existing.genre.isNullOrBlank() -> existing.genre
            else -> genre
        }
        val resolvedYear = when {
            isYearEd && year != null && year > 0 -> year
            existing?.yearEdited == true && existing.year != null && existing.year > 0 -> existing.year
            else -> year
        }
        val resolvedTrack = when {
            isTrackEd && trackNumber != null && trackNumber > 0 -> trackNumber
            existing?.trackEdited == true && existing.trackNumber != null && existing.trackNumber > 0 -> existing.trackNumber
            else -> trackNumber
        }
        val resolvedArt = when {
            didEditArtNow && !artworkUri.isNullOrBlank() -> artworkUri
            existing?.artworkEdited == true && !existing.artworkUri.isNullOrBlank() -> existing.artworkUri
            else -> null
        }
        val resolvedLyrics = when {
            isLyricsEd && !lyrics.isNullOrBlank() -> lyrics
            existing?.lyricsEdited == true && !existing.lyrics.isNullOrBlank() -> existing.lyrics
            else -> lyrics
        }
        val nextArtGen = if (didEditArtNow) {
            (existing?.artworkGeneration ?: 0L) + 1L
        } else {
            existing?.artworkGeneration ?: 0L
        }

        val prefs = getPrefs(context)
        val normPath = normalizePath(path)
        val anyEdited = isTitleEd || isArtistEd || isAlbumEd || isGenreEd || isYearEd || isTrackEd || isArtEd || isLyricsEd
        val editor = prefs.edit()
            .putBoolean("$KEY_PREFIX_ID$songId", anyEdited)
            .putBoolean("$KEY_PREFIX_PATH$normPath", anyEdited)

        val json = org.json.JSONObject().apply {
            if (resolvedTitle.isNotBlank()) put("title", resolvedTitle)
            if (resolvedArtist.isNotBlank()) put("artist", resolvedArtist)
            if (resolvedAlbum.isNotBlank()) put("album", resolvedAlbum)
            if (!resolvedGenre.isNullOrBlank()) put("genre", resolvedGenre)
            if (resolvedYear != null && resolvedYear > 0) put("year", resolvedYear)
            if (resolvedTrack != null && resolvedTrack > 0) put("track", resolvedTrack)
            if (!resolvedArt.isNullOrBlank()) put("art", resolvedArt)
            if (!resolvedLyrics.isNullOrBlank()) put("lyrics", resolvedLyrics)
            put("titleEdited", isTitleEd)
            put("artistEdited", isArtistEd)
            put("albumEdited", isAlbumEd)
            put("genreEdited", isGenreEd)
            put("yearEdited", isYearEd)
            put("trackEdited", isTrackEd)
            put("artworkEdited", isArtEd)
            put("lyricsEdited", isLyricsEd)
            put("artworkGeneration", nextArtGen)
        }.toString()

        if (songId > 0) editor.putString("$KEY_DATA_ID$songId", json)
        if (normPath.isNotBlank()) editor.putString("$KEY_DATA_PATH$normPath", json)

        editor.apply()
    }

    @Synchronized
    fun markArtworkManuallyEdited(
        context: Context,
        songId: Long,
        path: String,
        artworkUri: String
    ) {
        markManuallyEdited(
            context = context,
            songId = songId,
            path = path,
            artworkUri = artworkUri,
            titleEdited = false,
            artistEdited = false,
            albumEdited = false,
            genreEdited = false,
            yearEdited = false,
            trackEdited = false,
            artworkEdited = true,
            lyricsEdited = false
        )
    }

    fun getManualMetadata(context: Context, songId: Long, path: String): ManualSongMetadata? {
        val prefs = getPrefs(context)
        val jsonStr = (if (songId > 0) prefs.getString("$KEY_DATA_ID$songId", null) else null)
            ?: (if (path.isNotBlank()) prefs.getString("$KEY_DATA_PATH${normalizePath(path)}", null) else null)
            ?: return null

        return try {
            val obj = org.json.JSONObject(jsonStr)
            val hasFieldFlags = obj.has("titleEdited") || obj.has("artworkEdited")
            val t = obj.optString("title", "")
            val ar = obj.optString("artist", "")
            val al = obj.optString("album", "")
            val gr = obj.optString("genre", "").takeIf { it.isNotBlank() }
            val yr = if (obj.has("year")) obj.getInt("year") else null
            val tr = if (obj.has("track")) obj.getInt("track") else null
            val art = obj.optString("art", "").takeIf { it.isNotBlank() }
            val lyr = obj.optString("lyrics", "").takeIf { it.isNotBlank() }
            ManualSongMetadata(
                title = t,
                artist = ar,
                album = al,
                genre = gr,
                year = yr,
                trackNumber = tr,
                artworkUri = art,
                lyrics = lyr,
                titleEdited = if (hasFieldFlags) obj.optBoolean("titleEdited", false) else t.isNotBlank(),
                artistEdited = if (hasFieldFlags) obj.optBoolean("artistEdited", false) else ar.isNotBlank(),
                albumEdited = if (hasFieldFlags) obj.optBoolean("albumEdited", false) else al.isNotBlank(),
                genreEdited = if (hasFieldFlags) obj.optBoolean("genreEdited", false) else gr != null,
                yearEdited = if (hasFieldFlags) obj.optBoolean("yearEdited", false) else yr != null,
                trackEdited = if (hasFieldFlags) obj.optBoolean("trackEdited", false) else tr != null,
                artworkEdited = if (hasFieldFlags) obj.optBoolean("artworkEdited", false) else art != null,
                lyricsEdited = if (hasFieldFlags) obj.optBoolean("lyricsEdited", false) else lyr != null,
                artworkGeneration = obj.optLong("artworkGeneration", if (art != null) 1L else 0L)
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

    fun isTitleManuallyEdited(context: Context, songId: Long, path: String): Boolean {
        return getManualMetadata(context, songId, path)?.titleEdited == true
    }

    fun isArtistManuallyEdited(context: Context, songId: Long, path: String): Boolean {
        return getManualMetadata(context, songId, path)?.artistEdited == true
    }

    fun isAlbumManuallyEdited(context: Context, songId: Long, path: String): Boolean {
        return getManualMetadata(context, songId, path)?.albumEdited == true
    }

    fun isTextMetadataManuallyEdited(context: Context, songId: Long, path: String): Boolean {
        val meta = getManualMetadata(context, songId, path) ?: return false
        return meta.titleEdited || meta.artistEdited || meta.albumEdited ||
            meta.genreEdited || meta.yearEdited || meta.trackEdited
    }

    fun isArtworkManuallyEdited(context: Context, songId: Long, path: String): Boolean {
        val meta = getManualMetadata(context, songId, path) ?: return false
        return meta.artworkEdited && !meta.artworkUri.isNullOrBlank()
    }

    fun getArtworkEditGeneration(context: Context, songId: Long, path: String): Long {
        return getManualMetadata(context, songId, path)?.artworkGeneration ?: 0L
    }

    @Synchronized
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

