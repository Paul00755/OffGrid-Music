package com.example.data.sync

import com.example.data.local.SongEntity
import com.example.data.model.Song
import com.example.util.TagCleaner
import java.security.MessageDigest

object SongIdentityHelper {

    private val signatureCache = object : android.util.LruCache<Long, String>(3000) {}

    /**
     * Computes a stable, device-independent song signature.
     * Uses normalized title, artist, album, and duration bucket (seconds).
     */
    fun computeSignature(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        filename: String = ""
    ): String {
        val cleanTitle = TagCleaner.cleanTitle(title, filename).trim().lowercase()
        val cleanArtist = TagCleaner.cleanArtist(artist).trim().lowercase()
        val cleanAlbum = TagCleaner.cleanAlbum(album).trim().lowercase()
        val durationSec = (durationMs / 1000L).coerceAtLeast(0L)
        val cleanFilename = filename.substringAfterLast('/').substringBeforeLast('.').trim().lowercase()

        val rawKey = "$cleanTitle|$cleanArtist|$cleanAlbum|$durationSec|$cleanFilename"
        return "sig_${sha256(rawKey).take(32)}"
    }

    fun computeSignature(song: Song): String {
        if (song.id > 0L) {
            synchronized(signatureCache) {
                signatureCache.get(song.id)?.let { return it }
            }
        }
        val filename = song.path.substringAfterLast('/')
        val sig = computeSignature(song.title, song.artist, song.album, song.durationMs, filename)
        if (song.id > 0L) {
            synchronized(signatureCache) { signatureCache.put(song.id, sig) }
        }
        return sig
    }

    fun computeSignature(song: SongEntity): String {
        if (song.id > 0L) {
            synchronized(signatureCache) {
                signatureCache.get(song.id)?.let { return it }
            }
        }
        val filename = song.path.substringAfterLast('/')
        val sig = computeSignature(song.title, song.artist, song.album, song.durationMs, filename)
        if (song.id > 0L) {
            synchronized(signatureCache) { signatureCache.put(song.id, sig) }
        }
        return sig
    }

    /**
     * Determines whether a local song matches a cloud song record.
     * Uses a multi-stage fallback matching algorithm:
     * 1. Exact signature match
     * 2. Normalized Title + Artist + Album match
     * 3. Normalized Title + Artist match (for tracks where album is unknown or generic)
     * 4. Normalized Filename + Duration match (within 3 seconds)
     */
    fun matches(
        song: SongEntity,
        cloudSignature: String,
        cloudTitle: String,
        cloudArtist: String,
        cloudAlbum: String,
        cloudDurationMs: Long,
        cloudFilename: String = ""
    ): Boolean {
        // 1. Direct signature equality
        val localSig = computeSignature(song)
        if (localSig.equals(cloudSignature, ignoreCase = true)) {
            return true
        }

        val localFilename = song.path.substringAfterLast('/')
        val localCleanTitle = TagCleaner.cleanTitle(song.title, localFilename).trim().lowercase()
        val cloudCleanTitle = TagCleaner.cleanTitle(cloudTitle, cloudFilename).trim().lowercase()

        val localCleanArtist = TagCleaner.cleanArtist(song.artist).trim().lowercase()
        val cloudCleanArtist = TagCleaner.cleanArtist(cloudArtist).trim().lowercase()

        val localCleanAlbum = TagCleaner.cleanAlbum(song.album).trim().lowercase()
        val cloudCleanAlbum = TagCleaner.cleanAlbum(cloudAlbum).trim().lowercase()

        val durationDiffSec = Math.abs((song.durationMs - cloudDurationMs) / 1000L)

        // Title and artist must match strongly
        val isTitleMatch = localCleanTitle.isNotBlank() && localCleanTitle == cloudCleanTitle
        val isArtistMatch = localCleanArtist.isNotBlank() && (
                localCleanArtist == cloudCleanArtist ||
                        localCleanArtist == "unknown artist" ||
                        cloudCleanArtist == "unknown artist"
                )

        if (isTitleMatch && isArtistMatch) {
            // Check album match or close duration
            val isAlbumMatch = localCleanAlbum == cloudCleanAlbum ||
                    localCleanAlbum == "unknown album" ||
                    cloudCleanAlbum == "unknown album"
            if (isAlbumMatch && durationDiffSec <= 5L) {
                return true
            }
            if (durationDiffSec <= 3L) {
                return true
            }
        }

        // 4. Filename + duration match
        if (cloudFilename.isNotBlank() && localFilename.isNotBlank()) {
            val localBase = localFilename.substringBeforeLast('.').trim().lowercase()
            val cloudBase = cloudFilename.substringBeforeLast('.').trim().lowercase()
            if (localBase.isNotBlank() && localBase == cloudBase && durationDiffSec <= 3L) {
                return true
            }
        }

        return false
    }

    private fun sha256(input: String): String {
        return try {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            input.hashCode().toString()
        }
    }
}
