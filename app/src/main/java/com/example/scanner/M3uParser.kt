package com.example.scanner

import com.example.data.model.Song
import com.example.util.TagCleaner
import java.io.File

data class M3uImportResult(
    val playlistName: String,
    val totalEntries: Int,
    val matchedSongs: List<Song>,
    val missingPaths: List<String>
)

object M3uParser {

    /**
     * High-speed O(1) indexed M3U / M3U8 parser.
     * Pre-indexes `allSongs` once per playlist import instead of running 4 linear `O(N)` scans per line,
     * making multi-playlist imports across 1,000+ library tracks instantaneous and zero-lag.
     */
    fun parseM3uContent(playlistName: String, content: String, allSongs: List<Song>): M3uImportResult {
        val cleanContent = content.removePrefix("\uFEFF")
        if (cleanContent.isBlank() || cleanContent.take(200).contains('\u0000')) {
            return M3uImportResult(playlistName, 0, emptyList(), emptyList())
        }

        val lines = try {
            cleanContent.lines()
        } catch (_: Exception) {
            return M3uImportResult(playlistName, 0, emptyList(), emptyList())
        }

        // Build O(1) lookup maps once for allSongs
        val byExactPath = HashMap<String, Song>(allSongs.size * 2)
        val byExactUri = HashMap<String, Song>(allSongs.size * 2)
        val byFileName = HashMap<String, Song>(allSongs.size * 2)
        val byStem = HashMap<String, Song>(allSongs.size * 2)
        val byCleanTitle = HashMap<String, Song>(allSongs.size * 2)
        val byArtistAndTitle = HashMap<String, Song>(allSongs.size * 2)

        for (song in allSongs) {
            if (song.path.isNotBlank()) {
                val normPath = song.path.replace('\\', '/').trim().lowercase()
                byExactPath.putIfAbsent(normPath, song)
                val fName = normPath.substringAfterLast('/')
                if (fName.isNotBlank()) {
                    byFileName.putIfAbsent(fName, song)
                    val stem = fName.substringBeforeLast('.').trim()
                    if (stem.isNotBlank()) {
                        byStem.putIfAbsent(stem, song)
                    }
                }
            }
            if (song.contentUriString.isNotBlank()) {
                byExactUri.putIfAbsent(song.contentUriString.trim().lowercase(), song)
            }
            val rawTitleLower = song.title.trim().lowercase()
            if (rawTitleLower.isNotBlank()) {
                byCleanTitle.putIfAbsent(rawTitleLower, song)
            }
            val dispTitleLower = song.displayTitle.trim().lowercase()
            if (dispTitleLower.isNotBlank()) {
                byCleanTitle.putIfAbsent(dispTitleLower, song)
            }
            val artistTitleKey = "${song.displayArtist.trim().lowercase()}|$dispTitleLower"
            byArtistAndTitle.putIfAbsent(artistTitleKey, song)
            val rawArtistTitleKey = "${song.artist.trim().lowercase()}|$rawTitleLower"
            byArtistAndTitle.putIfAbsent(rawArtistTitleKey, song)
        }

        var resolvedPlaylistName = playlistName
        val totalEntries = mutableListOf<String>()
        var lastExtInfTitle: String? = null

        val matchedSongs = mutableListOf<Song>()
        val missingPaths = mutableListOf<String>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#PLAYLIST:", ignoreCase = true)) {
                val declaredName = line.substringAfter(":").trim()
                if (declaredName.isNotBlank() && (resolvedPlaylistName.isBlank() || resolvedPlaylistName.startsWith("Playlist_"))) {
                    resolvedPlaylistName = declaredName
                }
                continue
            }

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                lastExtInfTitle = line.substringAfter("#EXTINF:", "").substringAfter(",")
                continue
            }

            if (line.startsWith("#")) continue

            totalEntries.add(line)

            val decodedLine = try {
                if (line.contains('%')) java.net.URLDecoder.decode(line, "UTF-8") else line
            } catch (_: Exception) {
                line
            }
            val normLine = decodedLine.removePrefix("file://").replace('\\', '/').trim()
            val normLineLower = normLine.lowercase()
            val fileName = normLine.substringAfterLast('/')
            val fileNameLower = fileName.lowercase()
            val titleWithoutExt = fileName.substringBeforeLast('.').trim()
            val titleWithoutExtLower = titleWithoutExt.lowercase()

            // Strategy 1: Exact path or content URI
            var matched: Song? = byExactPath[normLineLower]
                ?: byExactUri[line.lowercase()]
                ?: byExactUri[normLineLower]

            // Strategy 2: Exact filename or relative suffix match
            if (matched == null && fileNameLower.isNotBlank()) {
                matched = byFileName[fileNameLower]
            }

            // Strategy 3: Filename stem / cleaned title match
            if (matched == null && titleWithoutExtLower.isNotBlank()) {
                matched = byStem[titleWithoutExtLower]
                    ?: byCleanTitle[titleWithoutExtLower]
                    ?: byCleanTitle[TagCleaner.cleanTitle(titleWithoutExt, fileName).trim().lowercase()]
            }

            // Strategy 4: EXTINF Artist - Title metadata fallback
            if (matched == null && !lastExtInfTitle.isNullOrBlank()) {
                val extTitle = lastExtInfTitle!!.trim()
                val artistPart = extTitle.substringBefore(" - ", "").trim().lowercase()
                val titlePart = extTitle.substringAfter(" - ", extTitle).trim().lowercase()
                val cleanTitlePart = TagCleaner.cleanTitle(titlePart, "").trim().lowercase()

                if (artistPart.isNotBlank()) {
                    matched = byArtistAndTitle["$artistPart|$titlePart"]
                        ?: byArtistAndTitle["$artistPart|$cleanTitlePart"]
                }
                if (matched == null) {
                    matched = byCleanTitle[titlePart]
                        ?: byCleanTitle[cleanTitlePart]
                        ?: allSongs.firstOrNull { s ->
                            s.displayTitle.equals(titlePart, ignoreCase = true) ||
                                (titlePart.length >= 4 && s.displayTitle.contains(titlePart, ignoreCase = true))
                        }
                }
            }

            if (matched != null) {
                matchedSongs.add(matched)
            } else {
                missingPaths.add(line)
            }
            lastExtInfTitle = null
        }

        return M3uImportResult(
            playlistName = resolvedPlaylistName.ifBlank { playlistName },
            totalEntries = totalEntries.size,
            matchedSongs = matchedSongs,
            missingPaths = missingPaths
        )
    }

    fun parseM3u(file: File, allSongs: List<Song>): M3uImportResult {
        return parseM3uContent(file.nameWithoutExtension, file.readText(), allSongs)
    }

    /**
     * Exports the playlist in the exact order of `songs` passed in (preserving custom user ordering).
     */
    fun exportM3u(playlistName: String, songs: List<Song>): String {
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#PLAYLIST:$playlistName")
        songs.forEach { song ->
            val durationSec = (song.durationMs / 1000L).coerceAtLeast(0L)
            sb.appendLine("#EXTINF:$durationSec,${song.displayArtist} - ${song.displayTitle}")
            val exportPath = song.path.ifBlank { song.contentUriString }
            sb.appendLine(exportPath)
        }
        return sb.toString()
    }
}