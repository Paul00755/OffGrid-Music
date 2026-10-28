package com.example.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class ArtworkCandidate(
    val imageUrl: String,
    val title: String,
    val artist: String,
    val album: String,
    val source: String
)

object OnlineArtistImageFetcher {

    private val artistCache = ConcurrentHashMap<String, String>()
    private val negativeArtistCache = ConcurrentHashMap.newKeySet<String>()
    private val albumCache = ConcurrentHashMap<String, String>()
    private val negativeAlbumCache = ConcurrentHashMap.newKeySet<String>()

    private val songCache = ConcurrentHashMap<String, String>()
    private val negativeSongCache = ConcurrentHashMap.newKeySet<String>()

    private val GENERIC_ALBUM_OR_FOLDER_NAMES = setOf(
        "<unknown>", "unknown", "unknown album", "unknown artist",
        "download", "downloads", "music", "audio", "songs", "tracks", "single", "singles",
        "whatsapp audio", "telegram", "telegram audio", "ringtones", "recordings",
        "notifications", "alarms", "podcasts", "folder", "sdcard", "internal storage"
    )

    private fun isGenericAlbumOrFolder(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower.isBlank() || lower in GENERIC_ALBUM_OR_FOLDER_NAMES || lower.startsWith("track ") || lower.startsWith("audio_")
    }

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "from", "feat", "ft", "official", "audio", "video", "lyrics", "song", "remix", "version"
    )

    private fun normalizeForMatch(raw: String): String {
        return raw.lowercase()
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun significantTokens(normalized: String): Set<String> {
        if (normalized.isBlank()) return emptySet()
        return normalized.split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()
    }

    /**
     * Validates that an online artwork candidate genuinely matches the requested song's metadata.
     * Rejects weak or unrelated results so a song is never assigned another track's artwork.
     */
    fun isValidArtworkMatch(
        requestedTitle: String,
        requestedArtist: String,
        requestedAlbum: String = "",
        candidateTitle: String,
        candidateArtist: String,
        candidateAlbum: String = ""
    ): Boolean {
        val reqTitle = normalizeForMatch(TagCleaner.cleanTitle(requestedTitle, ""))
        val candTitle = normalizeForMatch(candidateTitle)
        if (reqTitle.isBlank() || candTitle.isBlank()) return false

        val reqTitleTokens = significantTokens(reqTitle)
        val candTitleTokens = significantTokens(candTitle)

        val exactOrSubstringTitle = reqTitle == candTitle ||
            (reqTitle.length >= 4 && candTitle.contains(reqTitle)) ||
            (candTitle.length >= 4 && reqTitle.contains(candTitle))

        val sharedTitleTokens = reqTitleTokens.intersect(candTitleTokens)
        val titleTokenScore = if (reqTitleTokens.isNotEmpty() && candTitleTokens.isNotEmpty()) {
            sharedTitleTokens.size.toFloat() / minOf(reqTitleTokens.size, candTitleTokens.size).toFloat()
        } else 0f

        val titleMatches = exactOrSubstringTitle || (sharedTitleTokens.isNotEmpty() && titleTokenScore >= 0.6f)
        if (!titleMatches) return false

        val cleanArtistRaw = TagCleaner.cleanArtist(requestedArtist).let { if (isGenericAlbumOrFolder(it)) "" else it }
        val reqArtist = normalizeForMatch(cleanArtistRaw)
        val candArtist = normalizeForMatch(candidateArtist)

        if (reqArtist.isNotBlank() && candArtist.isNotBlank()) {
            val reqArtistTokens = significantTokens(reqArtist)
            val candArtistTokens = significantTokens(candArtist)
            val artistSubstring = reqArtist == candArtist ||
                (reqArtist.length >= 3 && candArtist.contains(reqArtist)) ||
                (candArtist.length >= 3 && reqArtist.contains(candArtist)) ||
                (candArtist.length >= 3 && reqTitle.contains(candArtist))
            val artistTokenOverlap = reqArtistTokens.isNotEmpty() && reqArtistTokens.intersect(candArtistTokens).isNotEmpty()

            if (!artistSubstring && !artistTokenOverlap) {
                val cleanAlbumRaw = TagCleaner.cleanAlbum(requestedAlbum).let { if (isGenericAlbumOrFolder(it)) "" else it }
                val reqAlbum = normalizeForMatch(cleanAlbumRaw)
                val candAlbum = normalizeForMatch(candidateAlbum)
                val albumMatches = reqAlbum.length >= 4 && candAlbum.length >= 4 &&
                    (reqAlbum == candAlbum || candAlbum.contains(reqAlbum) || reqAlbum.contains(candAlbum))
                if (!albumMatches) return false
            }
        }

        return true
    }

    suspend fun getSongArtworkUrl(
        context: Context,
        title: String,
        artist: String = "",
        album: String = "",
        preferUltraHd: Boolean = true,
        cacheEnabled: Boolean = true,
        songId: Long = 0L,
        filePath: String? = null
    ): String? {
        // 0. If this song has a manually edited cover, never override it with online artwork
        if (songId > 0L && ManualTagStore.isArtworkManuallyEdited(context, songId, filePath.orEmpty())) {
            val manualCover = ManualTagStore.getManualMetadata(context, songId, filePath.orEmpty())?.artworkUri
                ?: com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, songId, filePath)
            if (!manualCover.isNullOrBlank()) {
                return manualCover
            }
        }

        val identityKey = if (songId > 0L && !filePath.isNullOrBlank()) {
            com.example.ui.components.ArtworkResolutionCache.trackIdentity(songId, filePath)
        } else songId.toString()

        val needsHighResUpgrade = preferUltraHd && songId > 0L &&
            !com.example.ui.components.ArtworkResolutionCache.hasHighResArtwork(context, songId, filePath)

        // 1. Check the track-specific cache first (unless upgrading to high-res from a lower-res entry)
        if (songId > 0L && cacheEnabled && !needsHighResUpgrade) {
            val idPrefs = context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
            val savedById = idPrefs.getString(identityKey, null)
            if (!savedById.isNullOrBlank()) {
                return savedById
            }
        }

        val cleanTitle = TagCleaner.cleanTitle(title, "")
        val cleanArtist = TagCleaner.cleanArtist(artist).let { if (isGenericAlbumOrFolder(it)) "" else it }
        val cleanAlbum = TagCleaner.cleanAlbum(album).let { if (isGenericAlbumOrFolder(it)) "" else it }

        // Require a meaningful song title so unrelated tracks in the same folder never share a generic album cover
        if (cleanTitle.isBlank() || isGenericAlbumOrFolder(cleanTitle)) {
            return null
        }

        val resSuffix = if (preferUltraHd) "hd" else "std"
        val cacheKey = if (songId > 0L) {
            "${identityKey}_${cleanTitle}_${cleanArtist}_$resSuffix".lowercase().trim()
        } else {
            "${cleanTitle}_${cleanArtist}_${cleanAlbum}_$resSuffix".lowercase().trim()
        }
        val legacyCacheKey = if (songId > 0L) {
            "${identityKey}_${cleanTitle}_${cleanArtist}".lowercase().trim()
        } else {
            "${cleanTitle}_${cleanArtist}_$cleanAlbum".lowercase().trim()
        }

        if (cacheEnabled) {
            if (negativeSongCache.contains(cacheKey) || (songId > 0L && negativeSongCache.contains("song_$songId"))) return null
            songCache[cacheKey]?.let { return it }

            val prefs = context.getSharedPreferences("song_artworks_online_cache", Context.MODE_PRIVATE)
            val savedUrl = prefs.getString(cacheKey, null)
                ?: if (!preferUltraHd) prefs.getString(legacyCacheKey, null) else prefs.getString(legacyCacheKey, null)?.let { url ->
                    if (url.contains("500x500bb") || url.contains("600x600bb") || url.contains("100x100bb")) {
                        url.replace("500x500bb", "1000x1000bb")
                            .replace("600x600bb", "1000x1000bb")
                            .replace("100x100bb", "1000x1000bb")
                    } else if (url.startsWith("http")) url else null
                }
            if (!savedUrl.isNullOrBlank()) {
                songCache[cacheKey] = savedUrl
                if (songId > 0L) {
                    context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                        .edit().putString(identityKey, savedUrl).apply()
                }
                return savedUrl
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                // 1. Search Query optimization (strictly anchored on the song's own title)
                val queryTerm = when {
                    cleanTitle.isNotBlank() && cleanArtist.isNotBlank() -> "$cleanArtist $cleanTitle"
                    else -> cleanTitle
                }

                fun fetchItunes(term: String): String? {
                    try {
                        val enc = URLEncoder.encode(term, "UTF-8")
                        val itunesTrackUrl = URL("https://itunes.apple.com/search?term=$enc&entity=song&limit=5")
                        val itunesConn = itunesTrackUrl.openConnection() as HttpURLConnection
                        itunesConn.connectTimeout = 3500
                        itunesConn.readTimeout = 3500
                        if (itunesConn.responseCode == 200) {
                            val jsonStr = itunesConn.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(jsonStr)
                            val results = json.optJSONArray("results")
                            if (results != null && results.length() > 0) {
                                for (i in 0 until results.length()) {
                                    val item = results.getJSONObject(i)
                                    val candTitle = item.optString("trackName", "")
                                    val candArtist = item.optString("artistName", "")
                                    val candAlbum = item.optString("collectionName", "")
                                    if (!isValidArtworkMatch(cleanTitle, cleanArtist, cleanAlbum, candTitle, candArtist, candAlbum)) {
                                        continue
                                    }
                                    val pic = item.optString("artworkUrl100")
                                    if (pic.isNotBlank() && pic.startsWith("http")) {
                                        return if (preferUltraHd) {
                                            pic.replace("100x100bb", "1000x1000bb")
                                        } else {
                                            pic.replace("100x100bb", "500x500bb")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    return null
                }

                fun fetchDeezer(term: String): String? {
                    try {
                        val enc = URLEncoder.encode(term, "UTF-8")
                        val deezerTrackUrl = URL("https://api.deezer.com/search/track?q=$enc")
                        val deezerConn = deezerTrackUrl.openConnection() as HttpURLConnection
                        deezerConn.connectTimeout = 3500
                        deezerConn.readTimeout = 3500
                        deezerConn.requestMethod = "GET"
                        if (deezerConn.responseCode == 200) {
                            val jsonStr = deezerConn.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(jsonStr)
                            val data = json.optJSONArray("data")
                            if (data != null && data.length() > 0) {
                                for (i in 0 until minOf(data.length(), 5)) {
                                    val item = data.getJSONObject(i)
                                    val candTitle = item.optString("title", "")
                                    val candArtist = item.optJSONObject("artist")?.optString("name", "").orEmpty()
                                    val albumObj = item.optJSONObject("album")
                                    val candAlbum = albumObj?.optString("title", "").orEmpty()
                                    if (!isValidArtworkMatch(cleanTitle, cleanArtist, cleanAlbum, candTitle, candArtist, candAlbum)) {
                                        continue
                                    }
                                    if (albumObj != null) {
                                        val pic = if (preferUltraHd) {
                                            albumObj.optString("cover_xl")
                                                .ifBlank { albumObj.optString("cover_big") }
                                                .ifBlank { albumObj.optString("cover_medium") }
                                        } else {
                                            albumObj.optString("cover_big")
                                                .ifBlank { albumObj.optString("cover_medium") }
                                                .ifBlank { albumObj.optString("cover_xl") }
                                        }
                                        if (pic.isNotBlank() && pic.startsWith("http")) {
                                            return pic
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    return null
                }

                // Step A: Search iTunes with primary term (Artist + Title or Title)
                var resolvedOnline = fetchItunes(queryTerm)

                // Step B: Search Deezer with primary term (Artist + Title or Title)
                if (resolvedOnline == null) {
                    resolvedOnline = fetchDeezer(queryTerm)
                }

                // Step C: Only if Artist + Title failed, try distinctive multi-word Title alone
                if (resolvedOnline == null && cleanArtist.isNotBlank() && cleanTitle.length > 8 && cleanTitle.contains(" ")) {
                    resolvedOnline = fetchItunes(cleanTitle) ?: fetchDeezer(cleanTitle)
                }

                if (!resolvedOnline.isNullOrBlank()) {
                    if (cacheEnabled) {
                        songCache[cacheKey] = resolvedOnline
                        context.getSharedPreferences("song_artworks_online_cache", Context.MODE_PRIVATE)
                            .edit().putString(cacheKey, resolvedOnline).apply()
                        if (songId > 0L) {
                            context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                                .edit().putString(identityKey, resolvedOnline).apply()
                        }
                    }
                    return@withContext resolvedOnline
                }
            } catch (_: Exception) {}

            if (cacheEnabled) {
                negativeSongCache.add(cacheKey)
            }
            null
        }
    }

    fun updateSongMetadataCacheKeys(
        context: Context,
        songId: Long,
        newTitle: String,
        newArtist: String,
        newAlbum: String
    ) {
        if (songId <= 0L) return
        try {
            val idPrefs = context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
            val cachedUrl = idPrefs.getString(songId.toString(), null)
            if (!cachedUrl.isNullOrBlank()) {
                val cleanTitle = TagCleaner.cleanTitle(newTitle, "")
                val cleanArtist = TagCleaner.cleanArtist(newArtist).let { if (it.equals("Unknown Artist", ignoreCase = true)) "" else it }
                val cleanAlbum = TagCleaner.cleanAlbum(newAlbum).let { if (it.equals("Unknown Album", ignoreCase = true)) "" else it }
                val newKey = "${cleanTitle}_${cleanArtist}_$cleanAlbum".lowercase().trim()
                songCache[newKey] = cachedUrl
                context.getSharedPreferences("song_artworks_online_cache", Context.MODE_PRIVATE)
                    .edit().putString(newKey, cachedUrl).apply()
            }
        } catch (_: Exception) {}
    }

    suspend fun getArtistImageUrl(context: Context, artistName: String): String? {
        val trimmed = artistName.trim()
        if (trimmed.isBlank() || trimmed.equals("<unknown>", ignoreCase = true) || trimmed.equals("Unknown Artist", ignoreCase = true)) {
            return null
        }

        val cacheKey = trimmed.lowercase()
        if (negativeArtistCache.contains(cacheKey)) return null
        artistCache[cacheKey]?.let { return it }

        // Check persistent disk cache in SharedPreferences
        val prefs = context.getSharedPreferences("artist_images_cache", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(cacheKey, null)
        if (!savedUrl.isNullOrBlank()) {
            artistCache[cacheKey] = savedUrl
            return savedUrl
        }

        return withContext(Dispatchers.IO) {
            try {
                // 1. Try Deezer Public API
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                val url = URL("https://api.deezer.com/search/artist?q=$encoded")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    val data = json.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val first = data.getJSONObject(0)
                        val pic = first.optString("picture_big").ifBlank { first.optString("picture_medium") }
                        if (pic.isNotBlank() && pic.startsWith("http")) {
                            artistCache[cacheKey] = pic
                            prefs.edit().putString(cacheKey, pic).apply()
                            return@withContext pic
                        }
                    }
                }

                // 2. Fallback to iTunes Search API
                val itunesUrl = URL("https://itunes.apple.com/search?term=$encoded&entity=album&limit=1")
                val itunesConn = itunesUrl.openConnection() as HttpURLConnection
                itunesConn.connectTimeout = 3000
                itunesConn.readTimeout = 3000
                if (itunesConn.responseCode == 200) {
                    val jsonStr = itunesConn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        var pic = results.getJSONObject(0).optString("artworkUrl100")
                        if (pic.isNotBlank() && pic.startsWith("http")) {
                            pic = pic.replace("100x100bb", "600x600bb")
                            artistCache[cacheKey] = pic
                            prefs.edit().putString(cacheKey, pic).apply()
                            return@withContext pic
                        }
                    }
                }
            } catch (e: Exception) {
                // Network unavailable or timeout
            }
            negativeArtistCache.add(cacheKey)
            null
        }
    }

    suspend fun getAlbumImageUrl(
        context: Context,
        albumName: String,
        artistName: String = "",
        preferUltraHd: Boolean = true,
        cacheEnabled: Boolean = true
    ): String? {
        val trimmedAlbum = albumName.trim()
        val trimmedArtist = artistName.trim()

        if (trimmedAlbum.isBlank() || trimmedAlbum.equals("<unknown>", ignoreCase = true) || trimmedAlbum.equals("Unknown Album", ignoreCase = true)) {
            return null
        }

        val cacheKey = "$trimmedArtist-$trimmedAlbum".lowercase()
        if (cacheEnabled) {
            if (negativeAlbumCache.contains(cacheKey)) return null
            albumCache[cacheKey]?.let { return it }

            // Check persistent disk cache
            val prefs = context.getSharedPreferences("album_images_cache", Context.MODE_PRIVATE)
            val savedUrl = prefs.getString(cacheKey, null)
            if (!savedUrl.isNullOrBlank()) {
                albumCache[cacheKey] = savedUrl
                return savedUrl
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                val searchQuery = if (trimmedArtist.isNotBlank() && !trimmedArtist.contains("unknown", ignoreCase = true)) {
                    "$trimmedArtist $trimmedAlbum"
                } else {
                    trimmedAlbum
                }
                val encoded = URLEncoder.encode(searchQuery, "UTF-8")

                // 1. Try iTunes Search API (Best cover quality, up to 1920x1080)
                val itunesUrl = URL("https://itunes.apple.com/search?term=$encoded&entity=album&limit=1")
                val itunesConn = itunesUrl.openConnection() as HttpURLConnection
                itunesConn.connectTimeout = 3000
                itunesConn.readTimeout = 3000
                if (itunesConn.responseCode == 200) {
                    val jsonStr = itunesConn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        var pic = results.getJSONObject(0).optString("artworkUrl100")
                        if (pic.isNotBlank() && pic.startsWith("http")) {
                            pic = if (preferUltraHd) {
                                pic.replace("100x100bb", "600x600bb")
                            } else {
                                pic.replace("100x100bb", "500x500bb")
                            }
                            if (cacheEnabled) {
                                albumCache[cacheKey] = pic
                                context.getSharedPreferences("album_images_cache", Context.MODE_PRIVATE)
                                    .edit().putString(cacheKey, pic).apply()
                            }
                            return@withContext pic
                        }
                    }
                }

                // 2. Fallback to Deezer API (cover_xl / cover_big)
                val deezerUrl = URL("https://api.deezer.com/search?q=$encoded")
                val conn = deezerUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    val data = json.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val first = data.getJSONObject(0)
                        val albumObj = first.optJSONObject("album")
                        if (albumObj != null) {
                            val pic = albumObj.optString("cover_xl")
                                .ifBlank { albumObj.optString("cover_big") }
                                .ifBlank { albumObj.optString("cover_medium") }
                            if (pic.isNotBlank() && pic.startsWith("http")) {
                                if (cacheEnabled) {
                                    albumCache[cacheKey] = pic
                                    context.getSharedPreferences("album_images_cache", Context.MODE_PRIVATE)
                                        .edit().putString(cacheKey, pic).apply()
                                }
                                return@withContext pic
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Network error or timeout
            }
            if (cacheEnabled) {
                negativeAlbumCache.add(cacheKey)
            }
            null
        }
    }

    suspend fun searchArtworkCandidates(query: String): List<ArtworkCandidate> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<ArtworkCandidate>()
        val seenUrls = mutableSetOf<String>()
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return@withContext emptyList()

        val encoded = try {
            URLEncoder.encode(trimmedQuery, "UTF-8")
        } catch (_: Exception) {
            trimmedQuery
        }

        // 1. Query iTunes Store Search API (Up to 15 candidates with 1920x1080 artwork)
        try {
            val itunesUrl = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=15")
            val conn = itunesUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonStr)
                val results = json.optJSONArray("results")
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        var pic = item.optString("artworkUrl100")
                        if (pic.isNotBlank() && pic.startsWith("http")) {
                            pic = pic.replace("100x100bb", "600x600bb")
                            if (seenUrls.add(pic)) {
                                candidates.add(
                                    ArtworkCandidate(
                                        imageUrl = pic,
                                        title = item.optString("trackName", item.optString("collectionName", "Unknown Track")),
                                        artist = item.optString("artistName", "Unknown Artist"),
                                        album = item.optString("collectionName", "Unknown Album"),
                                        source = "iTunes Ultra-HD"
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Query Deezer Search API
        try {
            val deezerUrl = URL("https://api.deezer.com/search/track?q=$encoded&limit=15")
            val conn = deezerUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonStr)
                val data = json.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        val albumObj = item.optJSONObject("album")
                        val artistObj = item.optJSONObject("artist")
                        if (albumObj != null) {
                            val pic = albumObj.optString("cover_xl")
                                .ifBlank { albumObj.optString("cover_big") }
                                .ifBlank { albumObj.optString("cover_medium") }
                            if (pic.isNotBlank() && pic.startsWith("http")) {
                                if (seenUrls.add(pic)) {
                                    candidates.add(
                                        ArtworkCandidate(
                                            imageUrl = pic,
                                            title = item.optString("title", "Unknown Track"),
                                            artist = artistObj?.optString("name", "Unknown Artist") ?: "Unknown Artist",
                                            album = albumObj.optString("title", "Unknown Album"),
                                            source = "Deezer HD"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        candidates
    }
}
