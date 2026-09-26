package com.example.service

import android.media.MediaMetadataRetriever
import com.example.data.local.LyricsDao
import com.example.data.local.LyricsEntity
import com.example.data.model.Lyrics
import com.example.data.model.Song
import com.example.util.TagCleaner
import com.example.util.TransliterationUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class LyricsCandidate(
    val id: String,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Int,
    val plainLyrics: String?,
    val syncedLyricsRaw: String?,
    val source: String,
    val isSynced: Boolean = !syncedLyricsRaw.isNullOrBlank() && com.example.util.LrcParser.parseLrcToLines(syncedLyricsRaw).isNotEmpty()
)

class LyricsFetcher(
    private val lyricsDao: LyricsDao,
    private val context: android.content.Context? = null
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private fun writeLyricsFileCache(songId: Long, plain: String?, syncedJson: String?) {
        try {
            val ctx = context ?: return
            val dir = ctx.cacheDir.resolve("lyrics")
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "$songId.lrc")
            val content = if (!syncedJson.isNullOrBlank()) {
                com.example.util.LrcParser.formatSyncedJsonToLrc(syncedJson) ?: plain ?: ""
            } else {
                plain ?: ""
            }
            if (content.isNotBlank()) {
                file.writeText(content, Charsets.UTF_8)
            }
        } catch (_: Exception) {}
    }

    suspend fun saveSelectedLyrics(
        songId: Long,
        plainLyrics: String?,
        syncedLyricsRaw: String?,
        source: String
    ): Lyrics = withContext(Dispatchers.IO) {
        val parsedSynced = if (!syncedLyricsRaw.isNullOrBlank()) {
            val (_, json) = com.example.util.LrcParser.parseLrcContent(syncedLyricsRaw)
            json ?: syncedLyricsRaw
        } else null

        val entity = LyricsEntity(
            songId = songId,
            plainLyrics = plainLyrics,
            syncedLyricsJson = parsedSynced,
            isCached = true,
            source = source
        )
        lyricsDao.insertLyrics(entity)
        writeLyricsFileCache(songId, entity.plainLyrics, entity.syncedLyricsJson)
        entity.toDomainModel()
    }

    /**
     * Search available lyrics across multiple providers concurrently:
     * - LRCLIB (international open database)
     * - JioSaavn (premier source for Tamil, South Asian, and new releases)
     * - NetEase Cloud Music (massive catalog of synced LRC lyrics)
     */
    suspend fun searchAvailableLyrics(query: String): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val cleanQ = cleanSearchQuery(query)
        if (cleanQ.isBlank()) return@withContext emptyList()

        coroutineScope {
            val lrclibDef = async { searchLrclibCandidates(cleanQ) }
            val jioSaavnDef = async { searchJioSaavnCandidates(cleanQ) }
            val netEaseDef = async { searchNetEaseCandidates(cleanQ) }

            val lrclibResults = try { lrclibDef.await() } catch (_: Exception) { emptyList() }
            val jioSaavnResults = try { jioSaavnDef.await() } catch (_: Exception) { emptyList() }
            val netEaseResults = try { netEaseDef.await() } catch (_: Exception) { emptyList() }

            val combined = mutableListOf<LyricsCandidate>()
            combined.addAll(lrclibResults)
            combined.addAll(jioSaavnResults)
            combined.addAll(netEaseResults)

            // Deduplicate by text signature while preserving distinct sources
            val seen = mutableSetOf<String>()
            val deduped = mutableListOf<LyricsCandidate>()
            for (c in combined) {
                val key = "${c.source}:${c.trackName.lowercase()}:${c.artistName.lowercase()}:${c.isSynced}"
                if (seen.add(key)) {
                    deduped.add(c)
                }
            }

            // Sort: Synced lyrics first, Tamil script matches prominently, then by track name
            deduped.sortedWith(
                compareByDescending<LyricsCandidate> { it.isSynced }
                    .thenByDescending { isTamilText(it.plainLyrics ?: "") || isTamilText(it.trackName) }
                    .thenBy { it.trackName.lowercase() }
            )
        }
    }

    private fun searchLrclibCandidates(cleanQ: String): List<LyricsCandidate> {
        val results = mutableListOf<LyricsCandidate>()
        try {
            val qEnc = URLEncoder.encode(cleanQ, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$qEnc"
            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val arr = JSONArray(bodyStr)
                    for (i in 0 until minOf(arr.length(), 10)) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("id", "lrc_$i")
                        val trackName = obj.optString("trackName", "Unknown Track")
                        val artistName = obj.optString("artistName", "Unknown Artist")
                        val albumName = obj.optString("albumName", "")
                        val duration = obj.optInt("duration", 0)
                        val plain = obj.optString("plainLyrics", null)
                        val synced = obj.optString("syncedLyrics", null)

                        if (!plain.isNullOrBlank() || !synced.isNullOrBlank()) {
                            val isSyncedLyrics = !synced.isNullOrBlank() && com.example.util.LrcParser.parseLrcToLines(synced).isNotEmpty()
                            results.add(
                                LyricsCandidate(
                                    id = id,
                                    trackName = trackName,
                                    artistName = artistName,
                                    albumName = albumName,
                                    durationSec = duration,
                                    plainLyrics = plain,
                                    syncedLyricsRaw = synced,
                                    source = if (isSyncedLyrics) "LRCLIB (Synced)" else "LRCLIB",
                                    isSynced = isSyncedLyrics
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun searchJioSaavnCandidates(cleanQ: String): List<LyricsCandidate> {
        val results = mutableListOf<LyricsCandidate>()
        try {
            val qEnc = URLEncoder.encode(cleanQ, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&n=6&p=1&_marker=0&ctx=android&q=$qEnc"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android; Mobile)").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val arr = json.optJSONArray("results")
                    if (arr != null) {
                        for (i in 0 until minOf(arr.length(), 6)) {
                            val obj = arr.getJSONObject(i)
                            val songId = obj.optString("id", "")
                            val trackName = unescapeHtml(obj.optString("title", "Unknown Track"))
                            val moreInfo = obj.optJSONObject("more_info")
                            val artistName = unescapeHtml(moreInfo?.optString("singers", obj.optString("primary_artists", "Unknown Artist")) ?: "Unknown Artist")
                            val albumName = unescapeHtml(obj.optString("album", ""))
                            val hasLyrics = obj.optBoolean("has_lyrics", false) ||
                                    obj.optString("has_lyrics") == "true" ||
                                    moreInfo?.optBoolean("has_lyrics", false) == true ||
                                    moreInfo?.optString("has_lyrics") == "true"

                            if (songId.isNotBlank() && hasLyrics) {
                                val fetched = fetchJioSaavnLyricsById(songId)
                                if (fetched != null) {
                                    val (plain, synced) = fetched
                                    val isTamil = isTamilText(plain ?: "") || isTamilText(trackName) || isTamilText(artistName)
                                    val isSyncedLyrics = !synced.isNullOrBlank()
                                    results.add(
                                        LyricsCandidate(
                                            id = "saavn_$songId",
                                            trackName = trackName,
                                            artistName = artistName,
                                            albumName = albumName,
                                            durationSec = obj.optInt("duration", 0),
                                            plainLyrics = plain,
                                            syncedLyricsRaw = synced,
                                            source = when {
                                                isSyncedLyrics && isTamil -> "JioSaavn (Tamil Synced)"
                                                isSyncedLyrics -> "JioSaavn (Synced)"
                                                isTamil -> "JioSaavn (Tamil)"
                                                else -> "JioSaavn"
                                            },
                                            isSynced = isSyncedLyrics
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun searchNetEaseCandidates(cleanQ: String): List<LyricsCandidate> {
        val results = mutableListOf<LyricsCandidate>()
        try {
            val qEnc = URLEncoder.encode(cleanQ, "UTF-8")
            val url = "https://music.163.com/api/search/get/web?s=$qEnc&type=1&offset=0&total=true&limit=6"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val resultObj = json.optJSONObject("result")
                    val songsArr = resultObj?.optJSONArray("songs")
                    if (songsArr != null) {
                        for (i in 0 until minOf(songsArr.length(), 6)) {
                            val songObj = songsArr.getJSONObject(i)
                            val songId = songObj.optLong("id", 0L)
                            if (songId <= 0L) continue
                            val trackName = songObj.optString("name", "Unknown Track")
                            val artistsArr = songObj.optJSONArray("artists")
                            val artistName = artistsArr?.optJSONObject(0)?.optString("name", "Unknown Artist") ?: "Unknown Artist"
                            val albumObj = songObj.optJSONObject("album")
                            val albumName = albumObj?.optString("name", "") ?: ""
                            val durationSec = songObj.optInt("duration", 0) / 1000

                            val lyricsPair = fetchNetEaseLyricsById(songId)
                            if (lyricsPair != null) {
                                val (plain, synced) = lyricsPair
                                val isSyncedLyrics = !synced.isNullOrBlank()
                                results.add(
                                    LyricsCandidate(
                                        id = "ne_$songId",
                                        trackName = trackName,
                                        artistName = artistName,
                                        albumName = albumName,
                                        durationSec = durationSec,
                                        plainLyrics = plain,
                                        syncedLyricsRaw = synced,
                                        source = if (isSyncedLyrics) "NetEase Cloud (Synced)" else "NetEase Cloud",
                                        isSynced = isSyncedLyrics
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    suspend fun getOrFetchLyrics(song: Song): Lyrics = withContext(Dispatchers.IO) {
        // 1. Check database cache - if we already have synced lyrics, return them immediately
        val cached = lyricsDao.getLyricsForSong(song.id)
        if (cached != null && !cached.syncedLyricsJson.isNullOrBlank()) {
            return@withContext cached.toDomainModel()
        }

        var fallbackPlain: String? = cached?.plainLyrics
        var fallbackSource: String = cached?.source ?: "none"

        // 2. Search for local .lrc file in the same folder as the audio file
        try {
            val localLrcFile = com.example.util.LrcParser.findLocalLrcFile(song.path)
            if (localLrcFile != null && localLrcFile.exists()) {
                val rawBytes = localLrcFile.readBytes()
                var rawContent = String(rawBytes, java.nio.charset.StandardCharsets.UTF_8)
                if (rawContent.contains('\u0000')) {
                    rawContent = String(rawBytes, java.nio.charset.Charset.forName("GBK"))
                }
                val content = rawContent.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
                val (plain, synced) = com.example.util.LrcParser.parseLrcContent(content)
                if (!synced.isNullOrBlank()) {
                    val entity = LyricsEntity(
                        songId = song.id,
                        plainLyrics = plain ?: fallbackPlain,
                        syncedLyricsJson = synced,
                        isCached = true,
                        source = "Local LRC (${localLrcFile.name})"
                    )
                    lyricsDao.insertLyrics(entity)
                    writeLyricsFileCache(song.id, entity.plainLyrics, entity.syncedLyricsJson)
                    return@withContext entity.toDomainModel()
                } else if (!plain.isNullOrBlank()) {
                    fallbackPlain = plain
                    fallbackSource = "Local LRC (${localLrcFile.name})"
                }
            }
        } catch (_: Exception) {}

        // 3. Check for embedded lyrics in audio file tags
        val embedded = extractEmbeddedLyrics(song.path)
        if (embedded != null) {
            val (plain, synced) = com.example.util.LrcParser.parseLrcContent(embedded)
            if (!synced.isNullOrBlank()) {
                val entity = LyricsEntity(
                    songId = song.id,
                    plainLyrics = plain ?: fallbackPlain,
                    syncedLyricsJson = synced,
                    isCached = true,
                    source = "Embedded Tag"
                )
                lyricsDao.insertLyrics(entity)
                writeLyricsFileCache(song.id, entity.plainLyrics, entity.syncedLyricsJson)
                return@withContext entity.toDomainModel()
            } else if (!plain.isNullOrBlank()) {
                fallbackPlain = plain
                fallbackSource = "Embedded Tag"
            }
        }

        val rawTitle = song.title.ifBlank { song.path.substringAfterLast('/').substringBeforeLast('.') }
        val cleanTitle = TagCleaner.cleanTitle(rawTitle, song.path.substringAfterLast('/'))
        val cleanArtist = TagCleaner.cleanArtist(song.artist).let { if (it.equals("Unknown Artist", ignoreCase = true)) "" else it }
        val primaryArtist = extractPrimaryArtist(cleanArtist)
        val cleanAlbum = if (song.album.equals("Unknown Album", ignoreCase = true)) "" else song.album
        val durationSec = song.durationMs / 1000

        val isTamilSong = isTamilText(cleanTitle) || isTamilText(cleanArtist) || isTamilText(cleanAlbum) ||
                isTamilArtist(cleanArtist) || cleanTitle.contains("tamil", ignoreCase = true)

        // 4. Try fetching from LRCLIB API - prioritizing synced lyrics
        var lrclibResult: Pair<String?, String?>? = null

        // 4a. Direct LRCLIB get with exact tags + duration
        val directResult = fetchDirectLrclib(cleanTitle, primaryArtist.ifBlank { cleanArtist }, cleanAlbum, durationSec)
        if (directResult?.second?.isNotBlank() == true) {
            lrclibResult = directResult
        }

        // 4b. Direct LRCLIB get without album/duration restriction
        if (lrclibResult == null) {
            val directNoAlbum = fetchDirectLrclib(cleanTitle, primaryArtist.ifBlank { cleanArtist }, "", 0)
            if (directNoAlbum?.second?.isNotBlank() == true) {
                lrclibResult = directNoAlbum
            }
        }

        // 4c. Search LRCLIB by track + artist
        if (lrclibResult == null) {
            val searchResult = fetchSearchLrclib(cleanTitle, primaryArtist.ifBlank { cleanArtist }, durationSec)
            if (searchResult?.second?.isNotBlank() == true) {
                lrclibResult = searchResult
            } else if (lrclibResult == null) {
                lrclibResult = searchResult
            }
        }

        // 4d. Search LRCLIB by track name and full artist query
        if (lrclibResult?.second.isNullOrBlank() && cleanArtist.isNotBlank()) {
            val searchFull = fetchQueryLrclib("$cleanTitle $cleanArtist", durationSec)
            if (searchFull?.second?.isNotBlank() == true) {
                lrclibResult = searchFull
            } else if (lrclibResult == null) {
                lrclibResult = searchFull
            }
        }

        // 4e. Search LRCLIB by pure title query
        if (lrclibResult?.second.isNullOrBlank()) {
            val searchTitle = fetchQueryLrclib(cleanTitle, durationSec)
            if (searchTitle?.second?.isNotBlank() == true) {
                lrclibResult = searchTitle
            } else if (lrclibResult == null) {
                lrclibResult = searchTitle
            }
        }

        // If LRCLIB gave us verified synced lyrics, cache and return immediately!
        if (lrclibResult?.second?.isNotBlank() == true) {
            val (plain, rawSynced) = lrclibResult
            val parsedSynced = if (!rawSynced.isNullOrBlank()) {
                val (_, json) = com.example.util.LrcParser.parseLrcContent(rawSynced)
                json ?: rawSynced
            } else null

            val entity = LyricsEntity(
                songId = song.id,
                plainLyrics = plain ?: fallbackPlain,
                syncedLyricsJson = parsedSynced,
                isCached = true,
                source = "Online (LRCLIB - Synced)"
            )
            lyricsDao.insertLyrics(entity)
            writeLyricsFileCache(song.id, entity.plainLyrics, entity.syncedLyricsJson)
            return@withContext entity.toDomainModel()
        }

        // 5. Query NetEase Cloud Music (provides massive open library of Synced LRC lyrics)
        val netEaseResult = fetchNetEaseLyrics(cleanTitle, primaryArtist.ifBlank { cleanArtist }, durationSec)
        if (netEaseResult?.second?.isNotBlank() == true) {
            val (plain, rawSynced) = netEaseResult
            val (_, json) = com.example.util.LrcParser.parseLrcContent(rawSynced!!)
            val entity = LyricsEntity(
                songId = song.id,
                plainLyrics = plain ?: lrclibResult?.first ?: fallbackPlain,
                syncedLyricsJson = json ?: rawSynced,
                isCached = true,
                source = "Online (NetEase - Synced)"
            )
            lyricsDao.insertLyrics(entity)
            writeLyricsFileCache(song.id, entity.plainLyrics, entity.syncedLyricsJson)
            return@withContext entity.toDomainModel()
        }

        // 6. Query JioSaavn (Premier catalog for Tamil songs in Tamil script/synced & newly released tracks)
        val jioSaavnResult = fetchJioSaavnLyrics(cleanTitle, primaryArtist.ifBlank { cleanArtist }, cleanAlbum, isTamilSong)
        if (jioSaavnResult?.second?.isNotBlank() == true) {
            val (plain, rawSynced) = jioSaavnResult
            val (_, json) = com.example.util.LrcParser.parseLrcContent(rawSynced!!)
            val isTamil = isTamilText(plain ?: "") || isTamilSong
            val entity = LyricsEntity(
                songId = song.id,
                plainLyrics = plain ?: lrclibResult?.first ?: fallbackPlain,
                syncedLyricsJson = json ?: rawSynced,
                isCached = true,
                source = if (isTamil) "Online (JioSaavn - Tamil Synced)" else "Online (JioSaavn - Synced)"
            )
            lyricsDao.insertLyrics(entity)
            writeLyricsFileCache(song.id, entity.plainLyrics, entity.syncedLyricsJson)
            return@withContext entity.toDomainModel()
        }

        // 7. Query Lyrist API (indexes newly released songs, singles, and hot drops)
        var lyristResult: Pair<String?, String?>? = null
        if (lrclibResult?.first.isNullOrBlank() && jioSaavnResult?.first.isNullOrBlank()) {
            lyristResult = fetchLyristLyrics(cleanTitle, primaryArtist.ifBlank { cleanArtist })
        }

        // 8. Fallback to lyrics.ovh if still no lyrics found
        var ovhResult: Pair<String?, String?>? = null
        if (lrclibResult?.first.isNullOrBlank() && jioSaavnResult?.first.isNullOrBlank() && lyristResult?.first.isNullOrBlank() && cleanArtist.isNotBlank()) {
            ovhResult = fetchOvhLyrics(cleanTitle, primaryArtist.ifBlank { cleanArtist })
        }

        // Prioritize final result:
        // If Tamil song and JioSaavn provided native Tamil script lyrics, prefer them!
        val isJioSaavnTamil = jioSaavnResult?.first?.let { isTamilText(it) } == true
        val bestPlain = when {
            isTamilSong && isJioSaavnTamil -> jioSaavnResult?.first
            !lrclibResult?.first.isNullOrBlank() -> lrclibResult?.first
            !jioSaavnResult?.first.isNullOrBlank() -> jioSaavnResult?.first
            !netEaseResult?.first.isNullOrBlank() -> netEaseResult?.first
            !lyristResult?.first.isNullOrBlank() -> lyristResult?.first
            !ovhResult?.first.isNullOrBlank() -> ovhResult?.first
            else -> fallbackPlain
        }

        val bestSource = when {
            isTamilSong && isJioSaavnTamil -> "Online (JioSaavn - Tamil)"
            !lrclibResult?.first.isNullOrBlank() -> "Online (LRCLIB)"
            !jioSaavnResult?.first.isNullOrBlank() -> "Online (JioSaavn)"
            !netEaseResult?.first.isNullOrBlank() -> "Online (NetEase)"
            !lyristResult?.first.isNullOrBlank() -> "Online (Lyrist - Recent)"
            !ovhResult?.first.isNullOrBlank() -> "Online (Lyrics.ovh)"
            else -> fallbackSource
        }

        if (!bestPlain.isNullOrBlank()) {
            val entity = LyricsEntity(
                songId = song.id,
                plainLyrics = bestPlain,
                syncedLyricsJson = null,
                isCached = true,
                source = bestSource
            )
            lyricsDao.insertLyrics(entity)
            writeLyricsFileCache(song.id, entity.plainLyrics, entity.syncedLyricsJson)
            return@withContext entity.toDomainModel()
        }

        // Fallback: Return empty model
        val fallback = LyricsEntity(
            songId = song.id,
            plainLyrics = null,
            syncedLyricsJson = null,
            isCached = false,
            source = "none"
        )
        return@withContext fallback.toDomainModel()
    }

    private fun extractEmbeddedLyrics(audioPath: String): String? {
        if (audioPath.isBlank()) return null
        return try {
            val file = java.io.File(audioPath)
            if (!file.exists() || !file.canRead()) return null
            // Check if audio file tags or sidecar cache has lyrics
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanSearchQuery(q: String): String {
        return q.replace(Regex("""(?i)\(?(?:official|music|lyric|video|audio|remastered|remaster|hd|4k|hq|deluxe|explicit|clean|live|mono|stereo|from\s*['"][^'"]+['"])\)?"""), "")
            .replace(Regex("""(?i)\[(?:official|music|lyric|video|audio|remastered|remaster|hd|4k|hq|deluxe|explicit|clean|live|mono|stereo|tamil)\]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isTamilText(text: String): Boolean {
        return text.any { it in '\u0B80'..'\u0BFF' }
    }

    private fun isTamilArtist(artist: String): Boolean {
        val lower = artist.lowercase()
        val tamilArtists = listOf(
            "anirudh", "a.r. rahman", "ar rahman", "yuvan", "shankar mahadevan",
            "sid sriram", "harris jayaraj", "santhosh narayanan", "ilaiyaraaja", "ilayaraja",
            "dhibu ninan", "gv prakash", "g.v. prakash", "d. imman", "imman", "hiphop tamizha",
            "dhanush", "vijay", "spb", "s. p. balasubrahmanyam", "s. janaki", "k. s. chithra", "chithra",
            "shreya ghoshal", "jonita gandhi", "pradeep kumar", "sean roldan", "sam c.s.", "sam cs"
        )
        return tamilArtists.any { lower.contains(it) }
    }

    private fun unescapeHtml(html: String): String {
        return html
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</p>"""), "\n\n")
            .replace(Regex("""(?i)<[^>]+>"""), "")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun extractPrimaryArtist(artist: String): String {
        if (artist.isBlank()) return ""
        val delimiters = listOf(" feat. ", " feat ", " ft. ", " ft ", " featuring ", " & ", " / ", " x ", " X ", " with ", " With ")
        var primary = artist
        for (d in delimiters) {
            if (primary.contains(d, ignoreCase = true)) {
                val idx = primary.indexOf(d, ignoreCase = true)
                if (idx > 0) {
                    primary = primary.substring(0, idx).trim()
                }
            }
        }
        return primary.trim()
    }

    // -------------------------------------------------------------
    // Provider 1: LRCLIB
    // -------------------------------------------------------------
    private fun fetchDirectLrclib(title: String, artist: String, album: String, durationSec: Long): Pair<String?, String?>? {
        return try {
            val titleEnc = URLEncoder.encode(title, "UTF-8")
            val artistEnc = URLEncoder.encode(artist, "UTF-8")
            val cleanAlbum = if (album.equals("Unknown Album", ignoreCase = true)) "" else album
            val albumEnc = URLEncoder.encode(cleanAlbum, "UTF-8")

            var url = "https://lrclib.net/api/get?track_name=$titleEnc"
            if (artistEnc.isNotBlank()) url += "&artist_name=$artistEnc"
            if (cleanAlbum.isNotBlank()) url += "&album_name=$albumEnc"
            if (durationSec > 0) url += "&duration=$durationSec"

            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val plain = json.optString("plainLyrics", null)
                    val synced = json.optString("syncedLyrics", null)
                    if (!plain.isNullOrBlank() || !synced.isNullOrBlank()) {
                        return Pair(plain, synced)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchSearchLrclib(title: String, artist: String, durationSec: Long): Pair<String?, String?>? {
        return try {
            val titleEnc = URLEncoder.encode(title, "UTF-8")
            val artistEnc = URLEncoder.encode(artist, "UTF-8")
            var url = "https://lrclib.net/api/search?track_name=$titleEnc"
            if (artistEnc.isNotBlank()) url += "&artist_name=$artistEnc"

            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val arr = JSONArray(bodyStr)
                    var bestMatch: Pair<String?, String?>? = null
                    var bestScore = -1

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val plain = obj.optString("plainLyrics", null)
                        val synced = obj.optString("syncedLyrics", null)
                        val dur = obj.optInt("duration", 0)

                        if (!plain.isNullOrBlank() || !synced.isNullOrBlank()) {
                            var score = 0
                            if (!synced.isNullOrBlank()) score += 1000
                            if (durationSec > 0 && dur > 0) {
                                val diff = abs(durationSec - dur)
                                if (diff <= 3) score += 50
                                else if (diff <= 10) score += 20
                            }
                            if (score > bestScore) {
                                bestScore = score
                                bestMatch = Pair(plain, synced)
                            }
                        }
                    }
                    if (bestMatch != null) return bestMatch
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchQueryLrclib(query: String, durationSec: Long): Pair<String?, String?>? {
        return try {
            val cleanQ = cleanSearchQuery(query)
            val qEnc = URLEncoder.encode(cleanQ, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$qEnc"

            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val arr = JSONArray(bodyStr)
                    var bestMatch: Pair<String?, String?>? = null
                    var bestScore = -1

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val plain = obj.optString("plainLyrics", null)
                        val synced = obj.optString("syncedLyrics", null)
                        val dur = obj.optInt("duration", 0)

                        if (!plain.isNullOrBlank() || !synced.isNullOrBlank()) {
                            var score = 0
                            if (!synced.isNullOrBlank()) score += 1000
                            if (durationSec > 0 && dur > 0) {
                                val diff = abs(durationSec - dur)
                                if (diff <= 3) score += 50
                                else if (diff <= 10) score += 20
                            }
                            if (score > bestScore) {
                                bestScore = score
                                bestMatch = Pair(plain, synced)
                            }
                        }
                    }
                    if (bestMatch != null) return bestMatch
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------
    // Provider 2: NetEase Cloud Music (Synced LRC)
    // -------------------------------------------------------------
    private fun fetchNetEaseLyrics(title: String, artist: String, durationSec: Long): Pair<String?, String?>? {
        return try {
            val query = if (artist.isNotBlank()) "$title $artist" else title
            val qEnc = URLEncoder.encode(query, "UTF-8")
            val url = "https://music.163.com/api/search/get/web?s=$qEnc&type=1&offset=0&total=true&limit=5"

            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val resultObj = json.optJSONObject("result")
                    val songsArr = resultObj?.optJSONArray("songs")
                    if (songsArr != null && songsArr.length() > 0) {
                        for (i in 0 until minOf(songsArr.length(), 4)) {
                            val songObj = songsArr.getJSONObject(i)
                            val songId = songObj.optLong("id", 0L)
                            if (songId > 0L) {
                                val pair = fetchNetEaseLyricsById(songId)
                                if (pair?.second?.isNotBlank() == true) {
                                    return pair
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchNetEaseLyricsById(songId: Long): Pair<String?, String?>? {
        return try {
            val url = "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val lrcObj = json.optJSONObject("lrc")
                    val lrcLyric = lrcObj?.optString("lyric", null)
                    if (!lrcLyric.isNullOrBlank()) {
                        val (plain, synced) = com.example.util.LrcParser.parseLrcContent(lrcLyric)
                        if (!synced.isNullOrBlank() || !plain.isNullOrBlank()) {
                            return Pair(plain, synced)
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------
    // Provider 3: JioSaavn (Premier Tamil & Recent Indian Releases)
    // -------------------------------------------------------------
    private fun fetchJioSaavnLyrics(title: String, artist: String, album: String, isTamilHint: Boolean): Pair<String?, String?>? {
        val searchQueries = mutableListOf<String>()
        if (isTamilHint || isTamilText(title) || isTamilText(artist)) {
            if (album.isNotBlank()) searchQueries.add("$title $album")
            if (artist.isNotBlank()) searchQueries.add("$title $artist")
            searchQueries.add(title)
            val variants = TransliterationUtil.normalizeSearchQuery(title)
            for (v in variants) {
                if (v != title.lowercase()) searchQueries.add(v)
            }
        } else {
            if (artist.isNotBlank()) searchQueries.add("$title $artist")
            if (album.isNotBlank()) searchQueries.add("$title $album")
            searchQueries.add(title)
        }

        for (q in searchQueries.distinct()) {
            val result = fetchJioSaavnByQuery(q)
            if (result != null) return result
        }
        return null
    }

    private fun fetchJioSaavnByQuery(query: String): Pair<String?, String?>? {
        return try {
            val qEnc = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&n=5&p=1&_marker=0&ctx=android&q=$qEnc"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android; Mobile)").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val arr = json.optJSONArray("results")
                    if (arr != null && arr.length() > 0) {
                        for (i in 0 until minOf(arr.length(), 4)) {
                            val obj = arr.getJSONObject(i)
                            val songId = obj.optString("id", "")
                            val moreInfo = obj.optJSONObject("more_info")
                            val hasLyrics = obj.optBoolean("has_lyrics", false) ||
                                    obj.optString("has_lyrics") == "true" ||
                                    moreInfo?.optBoolean("has_lyrics", false) == true ||
                                    moreInfo?.optString("has_lyrics") == "true"

                            if (songId.isNotBlank() && hasLyrics) {
                                val lyrics = fetchJioSaavnLyricsById(songId)
                                if (lyrics != null) return lyrics
                            }
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchJioSaavnLyricsById(songId: String): Pair<String?, String?>? {
        return try {
            val url = "https://www.jiosaavn.com/api.php?__call=lyrics.getLyrics&ctx=android&_format=json&_marker=0&lyrics_id=$songId"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android; Mobile)").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val rawLyrics = json.optString("lyrics", null)
                    if (!rawLyrics.isNullOrBlank()) {
                        val cleaned = unescapeHtml(rawLyrics)
                        if (cleaned.isNotBlank()) {
                            // Check if synced timestamps [mm:ss] exist in the body
                            if (cleaned.contains(Regex("""\[\d{2}:\d{2}"""))) {
                                val (plain, synced) = com.example.util.LrcParser.parseLrcContent(cleaned)
                                return Pair(plain ?: cleaned, synced)
                            }
                            return Pair(cleaned, null)
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------
    // Provider 4: Lyrist (Newly released songs & singles)
    // -------------------------------------------------------------
    private fun fetchLyristLyrics(title: String, artist: String): Pair<String?, String?>? {
        return try {
            val titleEnc = URLEncoder.encode(title, "UTF-8")
            val artistEnc = URLEncoder.encode(artist, "UTF-8")
            val url = if (artistEnc.isNotBlank()) {
                "https://lyrist.vercel.app/api/$titleEnc/$artistEnc"
            } else {
                "https://lyrist.vercel.app/api/$titleEnc"
            }

            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val lyrics = json.optString("lyrics", null)
                    if (!lyrics.isNullOrBlank()) {
                        val cleaned = unescapeHtml(lyrics)
                        if (cleaned.contains(Regex("""\[\d{2}:\d{2}"""))) {
                            val (plain, synced) = com.example.util.LrcParser.parseLrcContent(cleaned)
                            return Pair(plain ?: cleaned, synced)
                        }
                        return Pair(cleaned.trim(), null)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------
    // Provider 5: Lyrics.ovh (Plain Fallback)
    // -------------------------------------------------------------
    private fun fetchOvhLyrics(title: String, artist: String): Pair<String?, String?>? {
        return try {
            val artistEnc = URLEncoder.encode(artist, "UTF-8")
            val titleEnc = URLEncoder.encode(title, "UTF-8")
            val url = "https://api.lyrics.ovh/v1/$artistEnc/$titleEnc"

            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val lyrics = json.optString("lyrics", null)
                    if (!lyrics.isNullOrBlank()) {
                        return Pair(lyrics.trim(), null)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
