package com.example.scanner

import com.example.data.model.Song
import java.io.File

data class M3uImportResult(
    val playlistName: String,
    val totalEntries: Int,
    val matchedSongs: List<Song>,
    val missingPaths: List<String>
)

object M3uParser {

    fun parseM3uContent(playlistName: String, content: String, allSongs: List<Song>): M3uImportResult {
        if (content.isBlank() || content.take(200).contains('\u0000')) {
            // Binary or empty content detected, prevent crash and return empty result safely
            return M3uImportResult(playlistName, 0, emptyList(), emptyList())
        }

        val lines = try {
            content.lines()
        } catch (_: Exception) {
            return M3uImportResult(playlistName, 0, emptyList(), emptyList())
        }
        val totalEntries = mutableListOf<String>()
        var lastExtInfTitle: String? = null

        val matchedSongs = mutableListOf<Song>()
        val missingPaths = mutableListOf<String>()

        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("#EXTINF:")) {
                lastExtInfTitle = line.substringAfter("#EXTINF:").substringAfter(",")
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                totalEntries.add(line)

                val fileName = line.substringAfterLast('/').substringAfterLast('\\')
                val titleWithoutExt = fileName.substringBeforeLast('.')

                // Strategy 1: Match exact contentUriString or exact path
                var matched: Song? = allSongs.find {
                    it.contentUriString.equals(line, ignoreCase = true) ||
                    it.path.equals(line, ignoreCase = true)
                }

                // Strategy 2: Match normalized relative path or filename
                if (matched == null) {
                    matched = allSongs.find {
                        it.path.endsWith(fileName, ignoreCase = true) ||
                        (it.path.isNotBlank() && line.endsWith(File(it.path).name, ignoreCase = true))
                    }
                }

                // Strategy 3: Match title by filename
                if (matched == null && titleWithoutExt.isNotBlank()) {
                    matched = allSongs.find {
                        it.title.equals(titleWithoutExt, ignoreCase = true) ||
                        it.displayTitle.equals(titleWithoutExt, ignoreCase = true)
                    }
                }

                // Strategy 4: Fallback to EXTINF metadata
                if (matched == null && !lastExtInfTitle.isNullOrBlank()) {
                    val extTitle = lastExtInfTitle!!
                    val artistPart = extTitle.substringBefore(" - ", "").trim()
                    val titlePart = extTitle.substringAfter(" - ", extTitle).trim()

                    matched = allSongs.find { song ->
                        if (artistPart.isNotBlank()) {
                            song.artist.equals(artistPart, ignoreCase = true) &&
                            song.title.equals(titlePart, ignoreCase = true)
                        } else {
                            song.title.equals(titlePart, ignoreCase = true) ||
                            extTitle.contains(song.title, ignoreCase = true)
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
        }

        return M3uImportResult(
            playlistName = playlistName,
            totalEntries = totalEntries.size,
            matchedSongs = matchedSongs,
            missingPaths = missingPaths
        )
    }

    fun parseM3u(file: File, allSongs: List<Song>): M3uImportResult {
        return parseM3uContent(file.nameWithoutExtension, file.readText(), allSongs)
    }

    fun exportM3u(playlistName: String, songs: List<Song>): String {
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#PLAYLIST:$playlistName")
        songs.forEach { song ->
            val durationSec = song.durationMs / 1000
            sb.appendLine("#EXTINF:$durationSec,${song.displayArtist} - ${song.displayTitle}")
            val exportPath = song.path.ifBlank { song.contentUriString }
            sb.appendLine(exportPath)
        }
        return sb.toString()
    }
}
