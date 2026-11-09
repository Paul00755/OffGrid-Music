package com.example.jam

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * Pure Kotlin/JVM ephemeral Jam audio transport.
 * Uses authenticated, ephemeral chunked relay over `peerTransfers/{transferId}` with SHA-256
 * end-to-end verification, background I/O executors, and automatic document deletion in `finally`
 * so zero audio bytes remain stored in Firestore and no native WebRTC C++ libraries are invoked.
 */
class JamPeerTransfer(private val context: Context, private val firestore: FirebaseFirestore) {

    suspend fun send(
        sessionId: String,
        recommendationId: String,
        senderUid: String,
        recipientUid: String,
        source: Uri,
        mimeType: String,
        onProgress: (Int) -> Unit = {}
    ): TransferResult = withContext(Dispatchers.IO) {
        val file = copyUriToCache(source, recommendationId)
        val fileHash = sha256(file)
        val fileLength = file.length()
        val transferRef = transferRef(sessionId, transferId(recommendationId, recipientUid))
        try {
            sendViaEphemeralChunks(
                transferRef = transferRef,
                recommendationId = recommendationId,
                senderUid = senderUid,
                recipientUid = recipientUid,
                file = file,
                fileHash = fileHash,
                fileLength = fileLength,
                mimeType = mimeType,
                onProgress = onProgress
            )
        } catch (error: Throwable) {
            file.delete()
            throw error
        } finally {
            runCatching { transferRef.delete().await() }
        }
    }

    private suspend fun sendViaEphemeralChunks(
        transferRef: DocumentReference,
        recommendationId: String,
        senderUid: String,
        recipientUid: String,
        file: File,
        fileHash: String,
        fileLength: Long,
        mimeType: String,
        onProgress: (Int) -> Unit
    ): TransferResult {
        val totalChunks = ceil(fileLength.toDouble() / RELAY_CHUNK_BYTES.toDouble()).toInt().coerceAtLeast(1)
        // Ensure any stale transfer document is deleted first so .set() is always a clean `create`
        // under Firestore security rules.
        runCatching { transferRef.delete().await() }

        transferRef.set(
            mapOf(
                "recommendationId" to recommendationId,
                "senderUid" to senderUid,
                "recipientUid" to recipientUid,
                "mimeType" to mimeType.take(100),
                "fileSize" to fileLength,
                "sha256" to fileHash,
                "offerSdp" to "",
                "mode" to "RELAY",
                "chunkIndex" to -1,
                "chunkTotal" to totalChunks,
                "chunkBase64" to "",
                "ackedChunkIndex" to -1,
                "status" to "RELAY_READY",
                "createdAtMs" to System.currentTimeMillis()
            )
        ).await()

        val buffer = ByteArray(RELAY_CHUNK_BYTES)
        file.inputStream().buffered().use { input ->
            var chunkIdx = 0
            var sentBytes = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val slice = if (read == buffer.size) buffer else buffer.copyOf(read)
                val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)
                transferRef.update(
                    mapOf(
                        "mode" to "RELAY",
                        "chunkIndex" to chunkIdx,
                        "chunkTotal" to totalChunks,
                        "chunkBase64" to b64,
                        "status" to "RELAY_CHUNK"
                    )
                ).await()
                awaitChunkAck(transferRef, chunkIdx, timeoutMs = 45_000L)
                sentBytes += read
                onProgress(((sentBytes * 100L) / fileLength.coerceAtLeast(1L)).toInt().coerceIn(0, 100))
                chunkIdx++
            }
        }
        transferRef.update(
            mapOf(
                "chunkBase64" to "",
                "status" to "SENT",
                "completedAtMs" to System.currentTimeMillis()
            )
        ).await()
        awaitTransferStatus(transferRef, "RECEIVED", timeoutMs = 45_000L)
        return TransferResult(file, fileHash, fileLength, mimeType)
    }

    /** Accepts an incoming ephemeral chunk stream and writes the verified file on a background thread. */
    suspend fun receive(
        sessionId: String,
        recommendationId: String,
        senderUid: String,
        recipientUid: String,
        expectedBytes: Long,
        onProgress: (Int) -> Unit = {}
    ): TransferResult = withContext(Dispatchers.IO) {
        require(expectedBytes in 1..MAX_TRANSFER_BYTES) { "This song exceeds the 25 MB transfer limit." }
        val transferRef = transferRef(sessionId, transferId(recommendationId, recipientUid))
        var offerData: Map<String, Any>? = null
        for (attempt in 0 until 450) {
            offerData = runCatching { transferRef.get().await().data }.getOrNull()
            if (offerData != null) break
            delay(100)
        }
        val offer = offerData ?: error("The sender is not currently available. Retry when both devices are online.")
        require(offer["senderUid"] == senderUid && offer["recipientUid"] == recipientUid) { "The transfer invitation is invalid." }
        require((offer["fileSize"] as? Number)?.toLong() == expectedBytes) { "The sender's file details changed." }
        val expectedHash = offer["sha256"] as? String ?: error("The sender did not provide a file checksum.")
        val mime = offer["mimeType"] as? String ?: "audio/mpeg"

        receiveViaEphemeralChunks(
            transferRef = transferRef,
            recommendationId = recommendationId,
            expectedBytes = expectedBytes,
            expectedHash = expectedHash,
            mime = mime,
            onProgress = onProgress
        )
    }

    private suspend fun receiveViaEphemeralChunks(
        transferRef: DocumentReference,
        recommendationId: String,
        expectedBytes: Long,
        expectedHash: String,
        mime: String,
        onProgress: (Int) -> Unit
    ): TransferResult {
        val output = File(context.cacheDir, "jam-relay-$recommendationId-${System.nanoTime()}.part")
        val completed = CompletableDeferred<File>()
        var nextExpectedChunk = 0
        var writtenBytes = 0L
        val outputStream = FileOutputStream(output)
        val lock = Any()
        val ioExecutor = Executors.newSingleThreadExecutor()

        val listener = transferRef.addSnapshotListener(ioExecutor) { snapshot, error ->
            if (error != null) {
                completed.completeExceptionally(error)
                return@addSnapshotListener
            }
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
            synchronized(lock) {
                if (completed.isCompleted) return@addSnapshotListener
                val chunkIdx = (snapshot.getLong("chunkIndex") ?: -1L).toInt()
                val chunkB64 = snapshot.getString("chunkBase64").orEmpty()
                val status = snapshot.getString("status").orEmpty()
                if (chunkIdx == nextExpectedChunk && chunkB64.isNotEmpty()) {
                    try {
                        val decoded = Base64.decode(chunkB64, Base64.DEFAULT)
                        outputStream.write(decoded)
                        writtenBytes += decoded.size
                        nextExpectedChunk++
                        onProgress(((writtenBytes * 100L) / expectedBytes.coerceAtLeast(1L)).toInt().coerceIn(0, 100))
                        transferRef.update("ackedChunkIndex", chunkIdx)
                        if (writtenBytes >= expectedBytes) {
                            outputStream.flush()
                            outputStream.fd.sync()
                            outputStream.close()
                            completed.complete(output)
                        }
                    } catch (t: Throwable) {
                        completed.completeExceptionally(t)
                    }
                } else if (status == "SENT" && writtenBytes >= expectedBytes) {
                    runCatching {
                        outputStream.flush()
                        outputStream.fd.sync()
                        outputStream.close()
                    }
                    completed.complete(output)
                }
            }
        }

        try {
            val resultFile = completed.awaitWithTimeout(10 * 60_000L)
            val hash = sha256(resultFile)
            require(hash.equals(expectedHash, true)) { "File verification failed." }
            transferRef.update(
                mapOf(
                    "status" to "RECEIVED",
                    "chunkBase64" to "",
                    "completedAtMs" to System.currentTimeMillis()
                )
            ).await()
            return TransferResult(resultFile, hash, expectedBytes, mime)
        } catch (error: Throwable) {
            runCatching { outputStream.close() }
            output.delete()
            throw error
        } finally {
            listener.remove()
            runCatching { ioExecutor.shutdown() }
        }
    }

    private suspend fun awaitChunkAck(ref: DocumentReference, expectedChunkIndex: Int, timeoutMs: Long) {
        val acked = CompletableDeferred<Unit>()
        val ioExecutor = Executors.newSingleThreadExecutor()
        val listener = ref.addSnapshotListener(ioExecutor) { snapshot, error ->
            if (error != null) {
                acked.completeExceptionally(error)
            } else {
                val ackIdx = (snapshot?.getLong("ackedChunkIndex") ?: -1L).toInt()
                val status = snapshot?.getString("status").orEmpty()
                if (ackIdx >= expectedChunkIndex || status == "RECEIVED") {
                    acked.complete(Unit)
                }
            }
        }
        try {
            acked.awaitWithTimeout(timeoutMs)
        } finally {
            listener.remove()
            runCatching { ioExecutor.shutdown() }
        }
    }

    private suspend fun awaitTransferStatus(ref: DocumentReference, target: String, timeoutMs: Long = 60_000L) {
        val reached = CompletableDeferred<Unit>()
        val ioExecutor = Executors.newSingleThreadExecutor()
        val listener = ref.addSnapshotListener(ioExecutor) { snapshot, error ->
            if (error != null) reached.completeExceptionally(error)
            else if (snapshot?.getString("status") == target) reached.complete(Unit)
        }
        try {
            reached.awaitWithTimeout(timeoutMs)
        } finally {
            listener.remove()
            runCatching { ioExecutor.shutdown() }
        }
    }

    private fun transferRef(sessionId: String, id: String) = firestore.collection("jamSessions")
        .document(sessionId).collection("peerTransfers").document(id)

    private fun transferId(recommendationId: String, recipientUid: String) = "${recommendationId.take(80)}_${recipientUid.take(80)}"

    private fun copyUriToCache(uri: Uri, recommendationId: String): File {
        val file = File(context.cacheDir, "jam-send-$recommendationId-${System.nanoTime()}.part")
        var copied = false
        if (uri.scheme?.lowercase() == "file") {
            val sourceFile = uri.path?.let(::File)
            if (sourceFile != null && sourceFile.isFile && sourceFile.length() > 0L) {
                runCatching {
                    sourceFile.inputStream().use { input -> file.outputStream().use(input::copyTo) }
                    copied = file.length() > 0L
                }
            }
        }
        if (!copied) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use(input::copyTo)
                copied = file.length() > 0L
            } ?: error("The selected song could not be opened.")
        }
        require(file.length() in 1..MAX_TRANSFER_BYTES) {
            file.delete()
            "This song exceeds the 25 MB transfer limit."
        }
        return file
    }

    data class TransferResult(val file: File, val sha256: String, val size: Long, val mimeType: String)

    companion object {
        private const val RELAY_CHUNK_BYTES = 192 * 1024
        const val MAX_TRANSFER_BYTES = 25L * 1024 * 1024

        private suspend fun <T> CompletableDeferred<T>.awaitWithTimeout(timeoutMs: Long = 45_000L): T {
            repeat((timeoutMs / 100).toInt().coerceAtLeast(1)) {
                if (isCompleted) return await()
                delay(100)
            }
            throw IllegalStateException("Peer transfer timed out.")
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
