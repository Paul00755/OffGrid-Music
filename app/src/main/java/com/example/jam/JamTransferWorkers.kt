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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** WorkManager wrappers keep audio transfer resumable while the app is backgrounded or recreated. */
object JamTransferWork {
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueueUpload(context: Context, sessionId: String, recommendationId: String, sourceUri: Uri, mimeType: String) {
        val request = OneTimeWorkRequestBuilder<JamUploadWorker>()
            .setInputData(Data.Builder()
                .putString(JamUploadWorker.KEY_SESSION, sessionId)
                .putString(JamUploadWorker.KEY_RECOMMENDATION, recommendationId)
                .putString(JamUploadWorker.KEY_URI, sourceUri.toString())
                .putString(JamUploadWorker.KEY_MIME, mimeType)
                .build())
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uploadWorkName(sessionId, recommendationId), ExistingWorkPolicy.KEEP, request)
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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(downloadWorkName(sessionId, recommendationId), ExistingWorkPolicy.KEEP, request)
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
        val auth = AuthManager.getInstance(applicationContext)
        val firestore = FirebaseInitializer.getFirestore(applicationContext) ?: return Result.retry()
        val repository = JamRepository(firestore, auth, BuildConfig.JAM_INVITE_HOST)
        val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val heartbeatJob = progressScope.launch { keepJamTransferPresence(sessionId, repository, auth) }
        val localFile = File(applicationContext.cacheDir, "jam-source-$recommendationId.part")
        return try {
            applicationContext.contentResolver.openInputStream(uri)?.use { source -> localFile.outputStream().use(source::copyTo) }
                ?: throw IllegalStateException("The selected song could not be opened.")
            if (localFile.length() !in 1..JamPeerTransfer.MAX_TRANSFER_BYTES) throw IllegalStateException("This song exceeds the 25 MB transfer limit.")
            val checksum = sha256(localFile)
            repository.preparePeerUpload(sessionId, recommendationId, checksum, localFile.length(), mime)
            repository.beginLocalUpload(sessionId, recommendationId)
            val recommendation = repository.getRecommendation(sessionId, recommendationId)
                ?: throw IllegalStateException("This Jam recommendation is no longer available.")
            val recipients = recommendation.eligibleUids.filter {
                it != auth.currentUser?.uid && recommendation.votes[it] == JamVote.ACCEPT &&
                    recommendation.downloads[it]?.readyForPlayback != true
            }
            if (recipients.isEmpty()) throw IllegalStateException("No Jam participant approved this transfer.")
            recipients.forEachIndexed { index, recipientUid ->
                val peerResult = JamPeerTransfer(applicationContext, firestore).send(
                    sessionId, recommendationId, auth.currentUser?.uid ?: throw IllegalStateException("Sign in to transfer this song."),
                    recipientUid, Uri.fromFile(localFile), mime
                ) { perPeer ->
                    val total = ((index * 100 + perPeer) / recipients.size).coerceIn(0, 100)
                    if (total == 100 || total % 5 == 0) progressScope.launch {
                        runCatching { repository.reportLocalUploadProgress(sessionId, recommendationId, total) }
                    }
                }
                peerResult.file.delete()
            }
            repository.completeLocalUpload(sessionId, recommendationId)
            localFile.delete()
            Result.success()
        } catch (error: Throwable) {
            if (runAttemptCount >= MAX_ATTEMPTS) {
                runCatching { repository.failLocalUpload(sessionId, recommendationId, error.message ?: "Upload failed.") }
                Result.failure()
            } else Result.retry()
        } finally {
            localFile.delete()
            heartbeatJob.cancel()
            progressScope.cancel()
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
        val expectedChecksum = inputData.getString(KEY_CHECKSUM) ?: return Result.failure()
        val track = JamTrack(
            sharedTrackId = inputData.getString(KEY_SHARED_TRACK_ID).orEmpty(),
            title = inputData.getString(KEY_TITLE).orEmpty(),
            artist = inputData.getString(KEY_ARTIST).orEmpty(),
            album = inputData.getString(KEY_ALBUM).orEmpty(),
            durationMs = inputData.getLong(KEY_DURATION, 0L),
            checksum = expectedChecksum
        )
        val auth = AuthManager.getInstance(applicationContext)
        val firestore = FirebaseInitializer.getFirestore(applicationContext) ?: return Result.retry()
        val repository = firestore.let {
            JamRepository(it, auth, BuildConfig.JAM_INVITE_HOST)
        } ?: return Result.retry()
        val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val heartbeatJob = progressScope.launch { keepJamTransferPresence(sessionId, repository, auth) }
        var temporary = File(applicationContext.cacheDir, "jam-$recommendationId.part")
        return try {
            repository.updateDownloadState(sessionId, recommendationId, "DOWNLOADING", 0)
            val recommendation = repository.getRecommendation(sessionId, recommendationId)
                ?: throw IllegalStateException("This Jam recommendation is no longer available.")
            val recipientUid = auth.currentUser?.uid ?: throw IllegalStateException("Sign in to receive this song.")
            temporary.delete()
            val received = JamPeerTransfer(applicationContext, firestore).receive(
                sessionId, recommendationId, recommendation.proposerUid, recipientUid,
                inputData.getLong(KEY_FILE_SIZE, 0L)
            ) { percent ->
                if (percent % 5 == 0) progressScope.launch {
                    runCatching { repository.updateDownloadState(sessionId, recommendationId, "DOWNLOADING", percent) }
                }
            }
            temporary = received.file
            repository.updateDownloadState(sessionId, recommendationId, "VERIFYING_FILE", 100)
            val checksum = sha256(temporary)
            if (!checksum.equals(expectedChecksum, ignoreCase = true)) {
                repository.updateDownloadState(sessionId, recommendationId, "DOWNLOAD_FAILED", 0, error = "File verification failed.")
                temporary.delete()
                return Result.failure()
            }
            if (!isPlayableAudio(temporary, track.durationMs)) {
                repository.updateDownloadState(sessionId, recommendationId, "DOWNLOAD_FAILED", 0, error = "The downloaded audio file could not be opened.")
                temporary.delete()
                return Result.failure()
            }
            JamRecommendationAssetStore.install(applicationContext, temporary, recommendationId, checksum, track, inputData.getString(KEY_MIME).orEmpty())
            repository.updateDownloadState(sessionId, recommendationId, "READY_FOR_PLAYBACK", 100, checksum = checksum)
            Result.success()
        } catch (error: Throwable) {
            if (runAttemptCount >= MAX_ATTEMPTS) {
                runCatching { repository.updateDownloadState(sessionId, recommendationId, "DOWNLOAD_FAILED", 0, error = error.message ?: "Download failed.") }
                Result.failure()
            } else Result.retry()
        } finally {
            temporary.delete()
            heartbeatJob.cancel()
            progressScope.cancel()
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
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val toleranceMs = maxOf(10_000L, (expectedDurationMs * 0.03).toLong())
        hasAudio != "no" && durationMs > 0L && kotlin.math.abs(durationMs - expectedDurationMs) <= toleranceMs
    } catch (_: Throwable) {
        false
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
                staging.delete()
                throw IllegalStateException("The audio file could not be registered locally.")
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
