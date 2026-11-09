package com.example.jam

import com.example.BuildConfig
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.auth.AuthManager
import com.example.data.local.AppDatabase
import com.example.data.local.SongEntity
import com.example.util.FirebaseInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates Jam local audio transfers both directly in the active foreground coroutine scope
 * (for 0ms startup latency when votes are accepted) and via WorkManager fallback when backgrounded.
 */
object JamTransferWork {
    private val activeUploads = ConcurrentHashMap.newKeySet<String>()
    private val activeDownloads = ConcurrentHashMap.newKeySet<String>()

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun workManagerOrNull(context: Context): WorkManager? = runCatching {
        WorkManager.getInstance(context.applicationContext)
    }.recoverCatching {
        WorkManager.initialize(context.applicationContext, androidx.work.Configuration.Builder().build())
        WorkManager.getInstance(context.applicationContext)
    }.getOrNull()

    fun enqueueUpload(context: Context, sessionId: String, recommendationId: String, sourceUri: Uri, mimeType: String) {
        val wm = workManagerOrNull(context) ?: return
        val request = OneTimeWorkRequestBuilder<JamUploadWorker>()
            .setInputData(Data.Builder()
                .putString(JamUploadWorker.KEY_SESSION, sessionId)
                .putString(JamUploadWorker.KEY_RECOMMENDATION, recommendationId)
                .putString(JamUploadWorker.KEY_URI, sourceUri.toString())
                .putString(JamUploadWorker.KEY_MIME, mimeType)
                .build())
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        runCatching {
            wm.enqueueUniqueWork(uploadWorkName(sessionId, recommendationId), ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun enqueueDownload(
        context: Context,
        sessionId: String,
        recommendationId: String,
        checksum: String,
        track: JamTrack,
        fileSize: Long,
        contentType: String?
    ) {
        val wm = workManagerOrNull(context) ?: return
        val request = OneTimeWorkRequestBuilder<JamDownloadWorker>()
            .setInputData(Data.Builder()
                .putString(JamDownloadWorker.KEY_SESSION, sessionId)
                .putString(JamDownloadWorker.KEY_RECOMMENDATION, recommendationId)
                .putString(JamDownloadWorker.KEY_CHECKSUM, checksum)
                .putLong(JamDownloadWorker.KEY_FILE_SIZE, fileSize)
                .putString(JamDownloadWorker.KEY_SHARED_TRACK_ID, track.sharedTrackId)
                .putString(JamDownloadWorker.KEY_TITLE, track.title)
                .putString(JamDownloadWorker.KEY_ARTIST, track.artist)
                .putString(JamDownloadWorker.KEY_ALBUM, track.album)
                .putLong(JamDownloadWorker.KEY_DURATION, track.durationMs)
                .putString(JamDownloadWorker.KEY_MIME, contentType.orEmpty())
                .build())
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        runCatching {
            wm.enqueueUniqueWork(downloadWorkName(sessionId, recommendationId), ExistingWorkPolicy.REPLACE, request)
        }
    }

    suspend fun executeUploadDirect(
        context: Context,
        sessionId: String,
        recommendationId: String,
        sourceUri: Uri,
        mimeType: String,
        failOnFinalError: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val key = "$sessionId:$recommendationId"
        if (!activeUploads.add(key)) return@withContext true
        val appCtx = context.applicationContext
        val auth = AuthManager.getInstance(appCtx)
        val firestore = FirebaseInitializer.getFirestore(appCtx) ?: run {
            activeUploads.remove(key)
            return@withContext false
        }
        val repository = JamRepository(firestore, auth, BuildConfig.JAM_INVITE_HOST)
        val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val heartbeatJob = progressScope.launch { keepJamTransferPresence(sessionId, repository, auth) }
        val localFile = File(appCtx.cacheDir, "jam-source-$recommendationId-${System.nanoTime()}.part")
        try {
            var copied = false
            if (sourceUri.scheme?.lowercase() == "file") {
                val src = sourceUri.path?.let(::File)
                if (src != null && src.isFile && src.length() > 0L) {
                    runCatching {
                        src.inputStream().use { source -> localFile.outputStream().use(source::copyTo) }
                        copied = localFile.length() > 0L
                    }
                }
            }
            if (!copied) {
                appCtx.contentResolver.openInputStream(sourceUri)?.use { source -> localFile.outputStream().use(source::copyTo) }
                    ?: throw IllegalStateException("The selected song could not be opened.")
            }
            if (localFile.length() !in 1..JamPeerTransfer.MAX_TRANSFER_BYTES) {
                throw IllegalStateException("This song exceeds the 25 MB transfer limit.")
            }
            val checksum = sha256(localFile)
            repository.preparePeerUpload(sessionId, recommendationId, checksum, localFile.length(), mimeType)
            repository.beginLocalUpload(sessionId, recommendationId)
            runCatching { repository.reportLocalUploadProgress(sessionId, recommendationId, 2) }
            val recommendation = repository.getRecommendation(sessionId, recommendationId)
                ?: throw IllegalStateException("This Jam recommendation is no longer available.")
            val senderUid = auth.currentUser?.uid ?: throw IllegalStateException("Sign in to transfer this song.")
            val recipients = recommendation.eligibleUids.filter {
                it != senderUid && recommendation.votes[it] == JamVote.ACCEPT &&
                    recommendation.downloads[it]?.readyForPlayback != true
            }
            if (recipients.isEmpty()) {
                repository.completeLocalUpload(sessionId, recommendationId)
                return@withContext true
            }
            val lastReported = AtomicInteger(0)
            recipients.forEachIndexed { index, recipientUid ->
                val peerResult = JamPeerTransfer(appCtx, firestore).send(
                    sessionId, recommendationId, senderUid, recipientUid, Uri.fromFile(localFile), mimeType
                ) { perPeer ->
                    val total = ((index * 100 + perPeer) / recipients.size).coerceIn(2, 100)
                    val prev = lastReported.get()
                    if (total == 100 || total - prev >= 4) {
                        lastReported.set(total)
                        progressScope.launch {
                            runCatching { repository.reportLocalUploadProgress(sessionId, recommendationId, total) }
                        }
                    }
                }
                peerResult.file.delete()
            }
            repository.completeLocalUpload(sessionId, recommendationId)
            true
        } catch (error: Throwable) {
            if (failOnFinalError) {
                runCatching { repository.failLocalUpload(sessionId, recommendationId, error.message ?: "Upload failed.") }
            }
            false
        } finally {
            localFile.delete()
            heartbeatJob.cancel()
            progressScope.cancel()
            activeUploads.remove(key)
        }
    }

    suspend fun executeDownloadDirect(
        context: Context,
        sessionId: String,
        recommendationId: String,
        expectedChecksum: String,
        track: JamTrack,
        expectedFileSize: Long,
        contentType: String?,
        failOnFinalError: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val key = "$sessionId:$recommendationId"
        if (!activeDownloads.add(key)) return@withContext true
        val appCtx = context.applicationContext
        val auth = AuthManager.getInstance(appCtx)
        val firestore = FirebaseInitializer.getFirestore(appCtx) ?: run {
            activeDownloads.remove(key)
            return@withContext false
        }
        val repository = JamRepository(firestore, auth, BuildConfig.JAM_INVITE_HOST)
        val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val heartbeatJob = progressScope.launch { keepJamTransferPresence(sessionId, repository, auth) }
        var temporary = File(appCtx.cacheDir, "jam-$recommendationId-${System.nanoTime()}.part")
        try {
            repository.updateDownloadState(sessionId, recommendationId, "DOWNLOADING", 2)
            val recommendation = repository.getRecommendation(sessionId, recommendationId)
                ?: throw IllegalStateException("This Jam recommendation is no longer available.")
            val recipientUid = auth.currentUser?.uid ?: throw IllegalStateException("Sign in to receive this song.")
            temporary.delete()
            val lastReported = AtomicInteger(0)
            val received = JamPeerTransfer(appCtx, firestore).receive(
                sessionId, recommendationId, recommendation.proposerUid, recipientUid,
                expectedFileSize
            ) { percent ->
                val clamped = percent.coerceIn(2, 99)
                val prev = lastReported.get()
                if (clamped - prev >= 4) {
                    lastReported.set(clamped)
                    progressScope.launch {
                        runCatching { repository.updateDownloadState(sessionId, recommendationId, "DOWNLOADING", clamped) }
                    }
                }
            }
            temporary = received.file
            repository.updateDownloadState(sessionId, recommendationId, "VERIFYING_FILE", 100)
            val checksum = sha256(temporary)
            val targetChecksum = expectedChecksum.takeIf(String::isNotBlank) ?: received.sha256
            if (targetChecksum.isNotBlank() && !checksum.equals(targetChecksum, ignoreCase = true)) {
                repository.updateDownloadState(sessionId, recommendationId, "DOWNLOAD_FAILED", 0, error = "File verification failed.")
                temporary.delete()
                return@withContext false
            }
            if (!isPlayableAudio(temporary, track.durationMs)) {
                repository.updateDownloadState(sessionId, recommendationId, "DOWNLOAD_FAILED", 0, error = "The downloaded audio file could not be opened.")
                temporary.delete()
                return@withContext false
            }
            val resolvedMime = contentType?.takeIf(String::isNotBlank) ?: received.mimeType
            JamRecommendationAssetStore.install(appCtx, temporary, recommendationId, checksum, track.copy(checksum = checksum), resolvedMime)
            repository.updateDownloadState(sessionId, recommendationId, "READY_FOR_PLAYBACK", 100, checksum = checksum)
            true
        } catch (error: Throwable) {
            if (failOnFinalError) {
                runCatching { repository.updateDownloadState(sessionId, recommendationId, "DOWNLOAD_FAILED", 0, error = error.message ?: "Download failed.") }
            }
            false
        } finally {
            temporary.delete()
            heartbeatJob.cancel()
            progressScope.cancel()
            activeDownloads.remove(key)
        }
    }

    fun uploadWorkName(sessionId: String, recommendationId: String) = "jam-upload-$sessionId-$recommendationId"
    fun downloadWorkName(sessionId: String, recommendationId: String) = "jam-download-$sessionId-$recommendationId"
}

class JamUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val recommendationId = inputData.getString(KEY_RECOMMENDATION) ?: return Result.failure()
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val mime = inputData.getString(KEY_MIME) ?: "audio/mpeg"
        val isFinalAttempt = runAttemptCount >= MAX_ATTEMPTS
        val ok = JamTransferWork.executeUploadDirect(
            context = applicationContext,
            sessionId = sessionId,
            recommendationId = recommendationId,
            sourceUri = uri,
            mimeType = mime,
            failOnFinalError = isFinalAttempt
        )
        return when {
            ok -> Result.success()
            isFinalAttempt -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        const val KEY_SESSION = "jam_session_id"
        const val KEY_RECOMMENDATION = "jam_recommendation_id"
        const val KEY_URI = "jam_source_uri"
        const val KEY_MIME = "jam_source_mime"
        private const val MAX_ATTEMPTS = 5
    }
}

class JamDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val recommendationId = inputData.getString(KEY_RECOMMENDATION) ?: return Result.failure()
        val expectedChecksum = inputData.getString(KEY_CHECKSUM).orEmpty()
        val track = JamTrack(
            sharedTrackId = inputData.getString(KEY_SHARED_TRACK_ID).orEmpty(),
            title = inputData.getString(KEY_TITLE).orEmpty(),
            artist = inputData.getString(KEY_ARTIST).orEmpty(),
            album = inputData.getString(KEY_ALBUM).orEmpty(),
            durationMs = inputData.getLong(KEY_DURATION, 0L),
            checksum = expectedChecksum.takeIf(String::isNotBlank)
        )
        val isFinalAttempt = runAttemptCount >= MAX_ATTEMPTS
        val ok = JamTransferWork.executeDownloadDirect(
            context = applicationContext,
            sessionId = sessionId,
            recommendationId = recommendationId,
            expectedChecksum = expectedChecksum,
            track = track,
            expectedFileSize = inputData.getLong(KEY_FILE_SIZE, 0L),
            contentType = inputData.getString(KEY_MIME),
            failOnFinalError = isFinalAttempt
        )
        return when {
            ok -> Result.success()
            isFinalAttempt -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        const val KEY_SESSION = "jam_session_id"
        const val KEY_RECOMMENDATION = "jam_recommendation_id"
        const val KEY_CHECKSUM = "jam_expected_checksum"
        const val KEY_FILE_SIZE = "jam_expected_file_size"
        const val KEY_SHARED_TRACK_ID = "jam_shared_track_id"
        const val KEY_TITLE = "jam_shared_title"
        const val KEY_ARTIST = "jam_shared_artist"
        const val KEY_ALBUM = "jam_shared_album"
        const val KEY_DURATION = "jam_shared_duration"
        const val KEY_MIME = "jam_shared_mime"
        private const val MAX_ATTEMPTS = 5
    }
}

private suspend fun keepJamTransferPresence(sessionId: String, repository: JamRepository, auth: AuthManager) {
    while (kotlin.coroutines.coroutineContext.isActive) {
        val user = auth.currentUser ?: return
        runCatching {
            repository.heartbeat(sessionId, user.displayName?.takeIf(String::isNotBlank) ?: "Listener", user.photoUrl?.toString())
        }
        delay(240_000L)
    }
}

private fun isPlayableAudio(file: File, expectedDurationMs: Long): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (expectedDurationMs <= 0L || durationMs <= 0L) {
            hasAudio != "no"
        } else {
            val toleranceMs = maxOf(15_000L, (expectedDurationMs * 0.08).toLong())
            hasAudio != "no" && kotlin.math.abs(durationMs - expectedDurationMs) <= toleranceMs
        }
    } catch (_: Throwable) {
        file.length() > 1024L
    } finally {
        runCatching { retriever.release() }
    }
}

private object JamRecommendationAssetStore {
    suspend fun install(context: Context, source: File, recommendationId: String, checksum: String, track: JamTrack, mime: String): SongEntity = withContext(Dispatchers.IO) {
        val extension = when (mime.lowercase()) {
            "audio/mp4" -> "m4a"
            "audio/aac" -> "aac"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> "mp3"
        }
        val directory = File(context.filesDir, "jam_shared").apply { mkdirs() }
        val baseName = "${track.sharedTrackId.take(24)}-${checksum.take(16)}.$extension"
        var target = File(directory, baseName)
        if (target.exists() && sha256(target) != checksum) target = File(directory, "${UUID.randomUUID()}-$baseName")
        if (!target.exists()) {
            val staging = File(directory, "${UUID.randomUUID()}.part")
            source.copyTo(staging, overwrite = false)
            if (sha256(staging) != checksum) {
                staging.delete()
                throw IllegalStateException("The verified audio file changed while it was being saved.")
            }
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
        }
        val db = AppDatabase.getInstance(context)
        val existing = db.songDao().getSongByPath(target.absolutePath)
        if (existing != null) return@withContext existing
        val uriString = Uri.fromFile(target).toString()
        val entity = SongEntity(
            title = track.title,
            artist = track.artist,
            album = track.album,
            genre = "OffGrid Jam",
            durationMs = track.durationMs,
            path = target.absolutePath,
            contentUriString = uriString,
            format = extension.uppercase()
        )
        val id = db.songDao().insertSong(entity)
        entity.copy(id = id)
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
