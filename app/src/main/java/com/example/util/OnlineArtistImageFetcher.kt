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

    suspend fun getSongArtworkUrl(
        context: Context,
        title: String,
        artist: String = "",
        album: String = "",
        preferUltraHd: Boolean = true,
        cacheEnabled: Boolean = true,
        songId: Long = 0L
    ): String? {
        // 1. Check stable songId-based cache first
        if (songId > 0L && cacheEnabled) {
            val idPrefs = context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
            val savedById = idPrefs.getString(songId.toString(), null)
            if (!savedById.isNullOrBlank()) {
                return savedById
            }
        }

        val cleanTitle = TagCleaner.cleanTitle(title, "")
        val cleanArtist = TagCleaner.cleanArtist(artist).let { if (it.equals("Unknown Artist", ignoreCase = true)) "" else it }
        val cleanAlbum = TagCleaner.cleanAlbum(album).let { if (it.equals("Unknown Album", ignoreCase = true)) "" else it }

        if (cleanTitle.isBlank() && cleanAlbum.isBlank()) {
            return null
        }

        val cacheKey = "${cleanTitle}_${cleanArtist}_$cleanAlbum".lowercase().trim()
        if (cacheEnabled) {
            if (negativeSongCache.contains(cacheKey)) return null
            songCache[cacheKey]?.let { return it }

            val prefs = context.getSharedPreferences("song_artworks_online_cache", Context.MODE_PRIVATE)
            val savedUrl = prefs.getString(cacheKey, null)
            if (!savedUrl.isNullOrBlank()) {
                songCache[cacheKey] = savedUrl
                if (songId > 0L) {
                    context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                        .edit().putString(songId.toString(), savedUrl).apply()
                }
                return savedUrl
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                // 1. Search Query optimization
                val queryTerm = when {
                    cleanTitle.isNotBlank() && cleanArtist.isNotBlank() -> "$cleanArtist $cleanTitle"
                    cleanTitle.isNotBlank() -> cleanTitle
                    cleanAlbum.isNotBlank() && cleanArtist.isNotBlank() -> "$cleanArtist $cleanAlbum"
                    else -> cleanAlbum
                }
                val encoded = URLEncoder.encode(queryTerm, "UTF-8")

                // Step A: iTunes Track Search (Fetch Ultra-HD covers up to 1920x1080)
                val itunesTrackUrl = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=3")
                val itunesConn = itunesTrackUrl.openConnection() as HttpURLConnection
                itunesConn.connectTimeout = 3000
                itunesConn.readTimeout = 3000
                if (itunesConn.responseCode == 200) {
                    val jsonStr = itunesConn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val first = results.getJSONObject(0)
                        var pic = first.optString("artworkUrl100")
                        if (pic.isNotBlank() && pic.startsWith("http")) {
                            pic = if (preferUltraHd) {
                                pic.replace("100x100bb", "600x600bb")
                            } else {
                                pic.replace("100x100bb", "500x500bb")
                            }
                            if (cacheEnabled) {
                                songCache[cacheKey] = pic
                                context.getSharedPreferences("song_artworks_online_cache", Context.MODE_PRIVATE)
                                    .edit().putString(cacheKey, pic).apply()
                                if (songId > 0L) {
                                    context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                                        .edit().putString(songId.toString(), pic).apply()
                                }
                            }
                            return@withContext pic
                        }
                    }
                }

                // Step B: Deezer Track Search (Ultra-HD / cover_xl 1000x1000)
                val deezerTrackUrl = URL("https://api.deezer.com/search/track?q=$encoded")
                val deezerConn = deezerTrackUrl.openConnection() as HttpURLConnection
                deezerConn.connectTimeout = 3000
                deezerConn.readTimeout = 3000
                deezerConn.requestMethod = "GET"
                if (deezerConn.responseCode == 200) {
                    val jsonStr = deezerConn.inputStream.bufferedReader().use { it.readText() }
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
                                    songCache[cacheKey] = pic
                                    context.getSharedPreferences("song_artworks_online_cache", Context.MODE_PRIVATE)
                                        .edit().putString(cacheKey, pic).apply()
                                    if (songId > 0L) {
                                        context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                                            .edit().putString(songId.toString(), pic).apply()
                                    }
                                }
                                return@withContext pic
                            }
                        }
                    }
                }

                // Step C: Fallback to Album search if album name is provided
                if (cleanAlbum.isNotBlank()) {
                    val albumArt = getAlbumImageUrl(context, cleanAlbum, cleanArtist, preferUltraHd, cacheEnabled)
                    if (!albumArt.isNullOrBlank()) {
                        if (cacheEnabled) {
                            songCache[cacheKey] = albumArt
                            context.getSharedPreferences("song_artworks_online_cache", Context.MODE_PRIVATE)
                                .edit().putString(cacheKey, albumArt).apply()
                            if (songId > 0L) {
                                context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                                    .edit().putString(songId.toString(), albumArt).apply()
                            }
                        }
                        return@withContext albumArt
                    }
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
