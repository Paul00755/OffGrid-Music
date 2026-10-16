package com.example.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

object AudioTagWriter {

    private const val TAG = "AudioTagWriter"

    /**
     * Updates metadata for an audio file on device storage, updating physical tags (ID3v2 for MP3),
     * writing companion .lrc lyrics files, updating MediaStore, and triggering MediaScanner.
     */
    fun writeAudioTags(
        context: Context,
        songPath: String,
        contentUriString: String?,
        songId: Long,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newArtworkUri: String?,
        newLyrics: String?,
        newGenre: String? = null,
        newYear: Int? = null,
        newTrackNumber: Int? = null
    ): Boolean {
        var success = false
        val scanPaths = mutableListOf<String>()
        if (songPath.isNotBlank()) scanPaths.add(songPath)

        try {
            val audioFile = File(songPath)
            if (audioFile.exists()) {
                val ext = audioFile.extension.lowercase()
                if (ext == "mp3") {
                    writeMp3Tags(
                        file = audioFile,
                        title = newTitle,
                        artist = newArtist,
                        album = newAlbum,
                        genre = newGenre,
                        year = newYear,
                        trackNumber = newTrackNumber,
                        artworkUri = newArtworkUri,
                        lyrics = newLyrics,
                        contentUriString = contentUriString,
                        context = context
                    )
                }

                // Write/update companion .lrc file if lyrics provided
                val lrcPath = updateCompanionLrcFile(audioFile, newLyrics)
                if (lrcPath != null) scanPaths.add(lrcPath)

                // Save companion artwork image directly into local song folder (e.g. song.jpg)
                val companionArtPath = updateCompanionArtworkFile(audioFile, newArtworkUri, context)
                if (companionArtPath != null) scanPaths.add(companionArtPath)

                success = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing physical file tags: ${e.message}", e)
        }

        // Update MediaStore columns
        try {
            updateMediaStoreTags(context, songId, contentUriString, newTitle, newArtist, newAlbum, newGenre, newYear, newTrackNumber)
            success = true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating MediaStore tags: ${e.message}", e)
        }

        // Trigger MediaScanner to re-index the file across Android OS
        try {
            if (scanPaths.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    context,
                    scanPaths.distinct().toTypedArray(),
                    null
                ) { path, uri ->
                    Log.d(TAG, "MediaScanner re-indexed file: $path -> $uri")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaScanner notification failed: ${e.message}", e)
        }

        return success
    }

    private fun updateCompanionArtworkFile(audioFile: File, artworkUri: String?, context: Context): String? {
        try {
            val parentDir = audioFile.parentFile ?: return null
            if (!parentDir.exists() || !parentDir.canWrite()) return null

            val baseName = audioFile.nameWithoutExtension
            val companionArtFile = File(parentDir, "$baseName.jpg")

            if (artworkUri.isNullOrBlank()) {
                if (companionArtFile.exists() && companionArtFile.canWrite()) {
                    companionArtFile.delete()
                }
                return null
            }

            val imageBytes: ByteArray? = when {
                artworkUri.startsWith("http://") || artworkUri.startsWith("https://") -> {
                    val conn = java.net.URL(artworkUri).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.inputStream.use { it.readBytes() }
                }
                artworkUri.startsWith("file://") -> {
                    val p = Uri.parse(artworkUri).path ?: artworkUri.removePrefix("file://")
                    val f = File(p)
                    if (f.exists()) f.readBytes() else null
                }
                File(artworkUri).exists() -> File(artworkUri).readBytes()
                else -> context.contentResolver.openInputStream(Uri.parse(artworkUri))?.use { it.readBytes() }
            }

            if (imageBytes != null && imageBytes.isNotEmpty()) {
                FileOutputStream(companionArtFile).use { fos ->
                    fos.write(imageBytes)
                    fos.flush()
                }
                return companionArtFile.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write companion artwork file: ${e.message}")
        }
        return null
    }

    private fun updateCompanionLrcFile(audioFile: File, lyricsText: String?): String? {
        try {
            val parentDir = audioFile.parentFile ?: return null
            val lrcFile = File(parentDir, "${audioFile.nameWithoutExtension}.lrc")

            if (lyricsText.isNullOrBlank()) {
                if (lrcFile.exists() && lrcFile.canWrite()) {
                    lrcFile.delete()
                }
                return null
            } else {
                FileOutputStream(lrcFile).use { fos ->
                    fos.write(lyricsText.toByteArray(StandardCharsets.UTF_8))
                    fos.flush()
                }
                return lrcFile.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write companion .lrc file: ${e.message}")
        }
        return null
    }

    private fun updateMediaStoreTags(
        context: Context,
        songId: Long,
        contentUriString: String?,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newGenre: String? = null,
        newYear: Int? = null,
        newTrackNumber: Int? = null
    ) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, newTitle)
            put(MediaStore.Audio.Media.ARTIST, newArtist)
            put(MediaStore.Audio.Media.ALBUM, newAlbum)
            if (newYear != null && newYear > 0) put(MediaStore.Audio.Media.YEAR, newYear)
            if (newTrackNumber != null && newTrackNumber > 0) put(MediaStore.Audio.Media.TRACK, newTrackNumber)
            if (!newGenre.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                put(MediaStore.Audio.Media.GENRE, newGenre)
            }
        }

        if (!contentUriString.isNullOrBlank() && contentUriString.startsWith("content://")) {
            try {
                val uri = Uri.parse(contentUriString)
                context.contentResolver.update(uri, values, null, null)
                return
            } catch (_: Exception) {}
        }

        if (songId > 0) {
            try {
                val mediaStoreUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    songId
                )
                context.contentResolver.update(mediaStoreUri, values, null, null)
            } catch (_: Exception) {}
        }
    }

    private fun writeMp3Tags(
        file: File,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        year: Int?,
        trackNumber: Int?,
        artworkUri: String?,
        lyrics: String?,
        contentUriString: String?,
        context: Context
    ) {
        val fileLength = file.length()
        if (fileLength < 10) return

        var audioStartOffset = 0L
        var audioEndOffset = fileLength

        // 1. Check existing ID3v2 header
        FileInputStream(file).use { fis ->
            val header = ByteArray(10)
            val read = fis.read(header)
            if (read == 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                        ((header[7].toInt() and 0x7F) shl 14) or
                        ((header[8].toInt() and 0x7F) shl 7) or
                        (header[9].toInt() and 0x7F)
                audioStartOffset = 10L + tagSize
            }
        }

        // 2. Check existing ID3v1 tag at the end (128 bytes)
        if (fileLength >= 128) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(fileLength - 128)
                val id3v1Header = ByteArray(3)
                raf.readFully(id3v1Header)
                if (id3v1Header[0] == 'T'.code.toByte() && id3v1Header[1] == 'A'.code.toByte() && id3v1Header[2] == 'G'.code.toByte()) {
                    audioEndOffset = fileLength - 128
                }
            }
        }

        if (audioStartOffset >= audioEndOffset) {
            audioStartOffset = 0L
            audioEndOffset = fileLength
        }

        // 3. Build new ID3v2.3 tag
        val id3v2Bytes = buildId3v23Tag(title, artist, album, genre, year, trackNumber, artworkUri, lyrics, context, file.absolutePath)

        // 4. Build ID3v1 tag
        val id3v1Bytes = buildId3v1Tag(title, artist, album, year, trackNumber)

        // 5. Write to temporary file
        val tempFile = File(file.parentFile, "${file.name}.tmp_${System.currentTimeMillis()}")
        var writeSuccess = false

        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(id3v2Bytes)

                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(audioStartOffset)
                    val buffer = ByteArray(64 * 1024)
                    var bytesRemaining = audioEndOffset - audioStartOffset
                    while (bytesRemaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                        val bytesRead = raf.read(buffer, 0, toRead)
                        if (bytesRead <= 0) break
                        fos.write(buffer, 0, bytesRead)
                        bytesRemaining -= bytesRead
                    }
                }

                fos.write(id3v1Bytes)
                fos.flush()
            }

            // Replace original file
            if (tempFile.exists() && tempFile.length() > id3v2Bytes.size) {
                if (file.delete()) {
                    writeSuccess = tempFile.renameTo(file)
                } else {
                    // Try direct stream copy
                    FileInputStream(tempFile).use { src ->
                        FileOutputStream(file).use { dst ->
                            src.copyTo(dst)
                            writeSuccess = true
                        }
                    }
                    tempFile.delete()
                }
            }

            // Fallback via Scoped Storage ContentResolver if file was not writable directly
            if (!writeSuccess && !contentUriString.isNullOrBlank() && tempFile.exists()) {
                try {
                    val uri = Uri.parse(contentUriString)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { dst ->
                        FileInputStream(tempFile).use { src ->
                            src.copyTo(dst)
                            writeSuccess = true
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed replacing MP3 with updated tags: ${e.message}", e)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }

        if (!writeSuccess) {
            Log.w(TAG, "Could not rewrite MP3 directly, updated MediaStore metadata.")
        }
    }

    private fun buildId3v23Tag(
        title: String,
        artist: String,
        album: String,
        genre: String? = null,
        year: Int? = null,
        trackNumber: Int? = null,
        artworkUri: String?,
        lyrics: String?,
        context: Context,
        fallbackFilePath: String = ""
    ): ByteArray {
        val framesStream = ByteArrayOutputStream()

        // TIT2 (Title)
        if (title.isNotBlank()) {
            writeTextFrame(framesStream, "TIT2", title)
        }

        // TPE1 (Artist)
        if (artist.isNotBlank()) {
            writeTextFrame(framesStream, "TPE1", artist)
        }

        // TALB (Album)
        if (album.isNotBlank()) {
            writeTextFrame(framesStream, "TALB", album)
        }

        // TCON (Genre)
        if (!genre.isNullOrBlank()) {
            writeTextFrame(framesStream, "TCON", genre)
        }

        // TYER (Year)
        if (year != null && year > 0) {
            writeTextFrame(framesStream, "TYER", year.toString())
        }

        // TRCK (Track Number)
        if (trackNumber != null && trackNumber > 0) {
            writeTextFrame(framesStream, "TRCK", trackNumber.toString())
        }

        // USLT (Lyrics)
        if (!lyrics.isNullOrBlank()) {
            writeLyricsFrame(framesStream, lyrics)
        }

        // APIC (Artwork)
        writeArtworkFrame(framesStream, artworkUri, context, fallbackFilePath)

        val framesBytes = framesStream.toByteArray()
        val tagSize = framesBytes.size

        // Build 10-byte ID3v2.3 header
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 3 // ID3v2.3
        header[4] = 0 // Revision 0
        header[5] = 0 // Flags

        // Syncsafe integer for tagSize (excluding 10-byte header)
        header[6] = ((tagSize shr 21) and 0x7F).toByte()
        header[7] = ((tagSize shr 14) and 0x7F).toByte()
        header[8] = ((tagSize shr 7) and 0x7F).toByte()
        header[9] = (tagSize and 0x7F).toByte()

        val fullTagStream = ByteArrayOutputStream(10 + tagSize)
        fullTagStream.write(header)
        fullTagStream.write(framesBytes)
        return fullTagStream.toByteArray()
    }

    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val frameBody = ByteArray(1 + textBytes.size)
        frameBody[0] = 0x03.toByte() // UTF-8 encoding
        System.arraycopy(textBytes, 0, frameBody, 1, textBytes.size)

        writeFrame(out, frameId, frameBody)
    }

    private fun writeLyricsFrame(out: ByteArrayOutputStream, lyrics: String) {
        val lyricsBytes = lyrics.toByteArray(StandardCharsets.UTF_8)
        val bodyStream = ByteArrayOutputStream()
        bodyStream.write(0x03) // UTF-8 encoding
        bodyStream.write("eng".toByteArray(StandardCharsets.ISO_8859_1)) // Language
        bodyStream.write(0x00) // Content descriptor terminator (empty description)
        bodyStream.write(lyricsBytes)

        writeFrame(out, "USLT", bodyStream.toByteArray())
    }

    private fun writeArtworkFrame(
        out: ByteArrayOutputStream,
        artworkUri: String?,
        context: Context,
        fallbackFilePath: String = ""
    ) {
        try {
            var imageBytes: ByteArray? = null
            var mimeType = "image/jpeg"

            if (!artworkUri.isNullOrBlank()) {
                imageBytes = when {
                    artworkUri.startsWith("http://") || artworkUri.startsWith("https://") -> {
                        val url = java.net.URL(artworkUri)
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.inputStream.use { it.readBytes() }
                    }
                    artworkUri.startsWith("file://") -> {
                        val path = Uri.parse(artworkUri).path ?: artworkUri.removePrefix("file://")
                        val file = File(path)
                        if (file.exists()) file.readBytes() else context.contentResolver.openInputStream(Uri.parse(artworkUri))?.use { it.readBytes() }
                    }
                    File(artworkUri).exists() -> {
                        File(artworkUri).readBytes()
                    }
                    else -> {
                        context.contentResolver.openInputStream(Uri.parse(artworkUri))?.use { it.readBytes() }
                    }
                }
                if (artworkUri.endsWith(".png", ignoreCase = true)) {
                    mimeType = "image/png"
                }
            }

            if ((imageBytes == null || imageBytes.isEmpty()) && fallbackFilePath.isNotBlank() && File(fallbackFilePath).exists()) {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(fallbackFilePath)
                    imageBytes = mmr.embeddedPicture
                } catch (_: Exception) {
                } finally {
                    try { mmr.release() } catch (_: Throwable) {}
                }
            }

            if (imageBytes == null || imageBytes.isEmpty()) return

            val bodyStream = ByteArrayOutputStream()
            bodyStream.write(0x00) // ISO-8859-1 for MIME
            bodyStream.write(mimeType.toByteArray(StandardCharsets.ISO_8859_1))
            bodyStream.write(0x00) // Null terminator
            bodyStream.write(0x03) // Picture type: Cover (front)
            bodyStream.write(0x00) // Empty description terminator
            bodyStream.write(imageBytes)

            writeFrame(out, "APIC", bodyStream.toByteArray())
        } catch (_: Exception) {}
    }

    private fun writeFrame(out: ByteArrayOutputStream, frameId: String, frameData: ByteArray) {
        val idBytes = frameId.toByteArray(StandardCharsets.ISO_8859_1)
        val frameSize = frameData.size

        // 4 bytes ID
        out.write(idBytes, 0, 4)
        // 4 bytes Size (Standard big-endian 32-bit int in ID3v2.3)
        out.write((frameSize shr 24) and 0xFF)
        out.write((frameSize shr 16) and 0xFF)
        out.write((frameSize shr 8) and 0xFF)
        out.write(frameSize and 0xFF)
        // 2 bytes Flags
        out.write(0x00)
        out.write(0x00)
        // Frame Data
        out.write(frameData)
    }

    private fun buildId3v1Tag(
        title: String,
        artist: String,
        album: String,
        year: Int? = null,
        trackNumber: Int? = null
    ): ByteArray {
        val tag = ByteArray(128)
        // "TAG" (3 bytes)
        tag[0] = 'T'.code.toByte()
        tag[1] = 'A'.code.toByte()
        tag[2] = 'G'.code.toByte()

        // Title (30 bytes)
        writeFixedString(tag, 3, 30, title)
        // Artist (30 bytes)
        writeFixedString(tag, 33, 30, artist)
        // Album (30 bytes)
        writeFixedString(tag, 63, 30, album)
        // Year (4 bytes)
        writeFixedString(tag, 93, 4, if (year != null && year > 0) year.toString() else "")
        // Comment (28 bytes) + Track flag (1 byte = 0) + Track # (1 byte) + Genre (1 byte = 255)
        writeFixedString(tag, 97, 28, "OffGrid Music")
        tag[125] = 0 // Track flag
        tag[126] = (trackNumber ?: 0).coerceIn(0, 255).toByte()
        tag[127] = 255.toByte() // Unknown genre

        return tag
    }

    private fun writeFixedString(target: ByteArray, offset: Int, length: Int, str: String) {
        val bytes = str.toByteArray(StandardCharsets.ISO_8859_1)
        val count = minOf(bytes.size, length)
        System.arraycopy(bytes, 0, target, offset, count)
        for (i in count until length) {
            target[offset + i] = 0
        }
    }
}
