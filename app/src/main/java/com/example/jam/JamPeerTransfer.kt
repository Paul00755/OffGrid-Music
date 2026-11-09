package com.example.jam

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.BuildConfig
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Direct, ephemeral Jam audio transport.
 * Attempts WebRTC DataChannel P2P first; if WebRTC native initialization or P2P NAT traversal
 * cannot connect on the current network, seamlessly falls back to ephemeral chunked relay
 * over `peerTransfers/{transferId}` and deletes the ephemeral transfer document in `finally`
 * so zero audio bytes remain stored in Firestore.
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
            val webrtcResult = runCatching {
                sendViaWebRtc(
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
            }
            if (webrtcResult.isSuccess) {
                return@withContext webrtcResult.getOrThrow()
            }
            // Fallback to ephemeral chunked relay so local song sharing always succeeds
            // even across symmetric NATs, hotspot isolation, or devices without WebRTC P2P route
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

    private suspend fun sendViaWebRtc(
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
        val peer = createPeer()
        val channel = peer.createDataChannel("jam-audio", DataChannel.Init().apply { ordered = true })
        try {
            val offer = createDescription(peer, answer = false)
            transferRef.set(
                mapOf(
                    "recommendationId" to recommendationId,
                    "senderUid" to senderUid,
                    "recipientUid" to recipientUid,
                    "mimeType" to mimeType.take(100),
                    "fileSize" to fileLength,
                    "sha256" to fileHash,
                    "offerSdp" to offer.description,
                    "mode" to "WEBRTC",
                    "status" to "OFFERED",
                    "createdAtMs" to System.currentTimeMillis()
                )
            ).await()
            awaitAnswer(transferRef, peer, timeoutMs = 12_000L)
            awaitConnected(peer, timeoutMs = 10_000L)
            awaitOpen(channel, timeoutMs = 8_000L)
            val bytes = ByteArray(CHUNK_BYTES)
            file.inputStream().buffered().use { input ->
                var sent = 0L
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    waitForChannelCapacity(channel)
                    val payload = ByteBuffer.allocateDirect(count)
                    payload.put(bytes, 0, count).flip()
                    if (!channel.send(DataChannel.Buffer(payload, true))) {
                        error("Peer connection stopped during transfer.")
                    }
                    sent += count
                    onProgress((sent * 100 / fileLength.coerceAtLeast(1L)).toInt().coerceIn(0, 100))
                }
            }
            transferRef.update("status", "SENT", "completedAtMs", System.currentTimeMillis()).await()
            awaitTransferStatus(transferRef, "RECEIVED", timeoutMs = 30_000L)
            return TransferResult(file, fileHash, fileLength, mimeType)
        } finally {
            runCatching { channel.unregisterObserver() }
            runCatching { channel.close() }
            runCatching { channel.dispose() }
            runCatching { peer.close() }
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

    /** Accepts an incoming offer (or ephemeral chunk stream) and writes the verified file. */
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
        val mode = offer["mode"] as? String ?: "WEBRTC"
        val offerText = (offer["offerSdp"] as? String).orEmpty()

        if (mode == "RELAY" || offerText.isBlank()) {
            return@withContext receiveViaEphemeralChunks(
                transferRef = transferRef,
                recommendationId = recommendationId,
                expectedBytes = expectedBytes,
                expectedHash = expectedHash,
                mime = mime,
                onProgress = onProgress
            )
        }

        val webrtcReceived = runCatching {
            receiveViaWebRtc(
                transferRef = transferRef,
                recommendationId = recommendationId,
                offerText = offerText,
                expectedBytes = expectedBytes,
                expectedHash = expectedHash,
                mime = mime,
                onProgress = onProgress
            )
        }
        if (webrtcReceived.isSuccess) {
            return@withContext webrtcReceived.getOrThrow()
        }

        // Wait briefly for sender to switch to RELAY mode and receive chunks
        receiveViaEphemeralChunks(
            transferRef = transferRef,
            recommendationId = recommendationId,
            expectedBytes = expectedBytes,
            expectedHash = expectedHash,
            mime = mime,
            onProgress = onProgress
        )
    }

    private suspend fun receiveViaWebRtc(
        transferRef: DocumentReference,
        recommendationId: String,
        offerText: String,
        expectedBytes: Long,
        expectedHash: String,
        mime: String,
        onProgress: (Int) -> Unit
    ): TransferResult {
        val received = CompletableDeferred<File>()
        val output = File(context.cacheDir, "jam-$recommendationId-${System.nanoTime()}.part")
        val bytesReceived = longArrayOf(0)
        val outputStream = FileOutputStream(output)
        var activeDataChannel: DataChannel? = null
        val observer = object : PeerConnection.Observer by EmptyPeerObserver {
            override fun onDataChannel(channel: DataChannel) {
                activeDataChannel = channel
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) = Unit
                    override fun onStateChange() = Unit
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (!buffer.binary) {
                            received.completeExceptionally(IllegalStateException("Unexpected transfer data."))
                            return
                        }
                        val chunk = ByteArray(buffer.data.remaining())
                        buffer.data.get(chunk)
                        synchronized(outputStream) {
                            if (received.isCompleted) return
                            outputStream.write(chunk)
                            bytesReceived[0] += chunk.size
                            onProgress((bytesReceived[0] * 100 / expectedBytes).toInt().coerceIn(0, 100))
                            if (bytesReceived[0] > expectedBytes) {
                                received.completeExceptionally(IllegalStateException("The received file exceeded its declared size."))
                            } else if (bytesReceived[0] == expectedBytes) {
                                outputStream.flush()
                                outputStream.fd.sync()
                                outputStream.close()
                                received.complete(output)
                            }
                        }
                    }
                })
            }
        }
        val peer = createPeer(observer)
        var relaySwitched = false
        val modeListener = transferRef.addSnapshotListener { snapshot, _ ->
            if (snapshot?.getString("mode") == "RELAY" && !received.isCompleted) {
                relaySwitched = true
                received.completeExceptionally(IllegalStateException("Switched to relay transfer"))
            }
        }
        try {
            setRemoteDescription(peer, SessionDescription(SessionDescription.Type.OFFER, offerText))
            val answer = createDescription(peer, answer = true)
            transferRef.update(mapOf("answerSdp" to answer.description, "status" to "ANSWERED")).await()
            val resultFile = received.awaitWithTimeout(18_000L)
            val hash = sha256(resultFile)
            require(hash.equals(expectedHash, true)) { "File verification failed." }
            transferRef.update(mapOf("status" to "RECEIVED", "completedAtMs" to System.currentTimeMillis())).await()
            return TransferResult(resultFile, hash, expectedBytes, mime)
        } catch (error: Throwable) {
            runCatching { outputStream.close() }
            output.delete()
            if (relaySwitched) throw IllegalStateException("Switching to relay mode")
            throw error
        } finally {
            modeListener.remove()
            runCatching { activeDataChannel?.unregisterObserver() }
            runCatching { activeDataChannel?.close() }
            runCatching { activeDataChannel?.dispose() }
            runCatching { peer.close() }
        }
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

        val listener = transferRef.addSnapshotListener { snapshot, error ->
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
        }
    }

    private suspend fun awaitChunkAck(ref: DocumentReference, expectedChunkIndex: Int, timeoutMs: Long) {
        val acked = CompletableDeferred<Unit>()
        val listener = ref.addSnapshotListener { snapshot, error ->
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
        }
    }

    private fun transferRef(sessionId: String, id: String) = firestore.collection("jamSessions")
        .document(sessionId).collection("peerTransfers").document(id)

    private fun transferId(recommendationId: String, recipientUid: String) = "${recommendationId.take(80)}_${recipientUid.take(80)}"

    private fun copyUriToCache(uri: Uri, recommendationId: String): File {
        val file = File(context.cacheDir, "jam-send-$recommendationId-${System.nanoTime()}.part")
        if (uri.scheme == "file") {
            val sourceFile = uri.path?.let(::File)
            if (sourceFile != null && sourceFile.isFile) {
                sourceFile.inputStream().use { input -> file.outputStream().use(input::copyTo) }
            } else {
                error("The selected song could not be opened.")
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use(input::copyTo) }
                ?: error("The selected song could not be opened.")
        }
        require(file.length() in 1..MAX_TRANSFER_BYTES) {
            file.delete()
            "This song exceeds the 25 MB transfer limit."
        }
        return file
    }

    private fun createPeer(observer: PeerConnection.Observer = EmptyPeerObserver): PeerConnection {
        val factory = factory(context)
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val turnUri = BuildConfig.JAM_TURN_URI.trim()
        if (turnUri.isNotEmpty()) {
            servers += PeerConnection.IceServer.builder(turnUri)
                .setUsername(BuildConfig.JAM_TURN_USERNAME)
                .setPassword(BuildConfig.JAM_TURN_CREDENTIAL)
                .createIceServer()
        }
        // Use default GATHER_ONCE so WebRTC does not invoke ConnectivityManager.requestNetwork
        // (which throws SecurityException without CHANGE_NETWORK_STATE permission).
        val configuration = PeerConnection.RTCConfiguration(servers).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        return factory.createPeerConnection(configuration, observer)
            ?: error("Could not create a peer connection.")
    }

    private suspend fun awaitAnswer(ref: DocumentReference, peer: PeerConnection, timeoutMs: Long = 15_000L) {
        val answer = CompletableDeferred<String>()
        lateinit var listener: ListenerRegistration
        listener = ref.addSnapshotListener { snapshot, error ->
            if (error != null) answer.completeExceptionally(error)
            else snapshot?.getString("answerSdp")?.takeIf(String::isNotBlank)?.let(answer::complete)
        }
        try {
            setRemoteDescription(peer, SessionDescription(SessionDescription.Type.ANSWER, answer.awaitWithTimeout(timeoutMs)))
        } finally {
            listener.remove()
        }
    }

    private suspend fun awaitTransferStatus(ref: DocumentReference, target: String, timeoutMs: Long = 60_000L) {
        val reached = CompletableDeferred<Unit>()
        val listener = ref.addSnapshotListener { snapshot, error ->
            if (error != null) reached.completeExceptionally(error)
            else if (snapshot?.getString("status") == target) reached.complete(Unit)
        }
        try {
            reached.awaitWithTimeout(timeoutMs)
        } finally {
            listener.remove()
        }
    }

    private suspend fun createDescription(peer: PeerConnection, answer: Boolean): SessionDescription {
        val created = CompletableDeferred<SessionDescription>()
        val constraints = MediaConstraints()
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peer.setLocalDescription(object : SdpObserver by NoopSdpObserver {
                    override fun onSetSuccess() {
                        created.complete(sdp)
                    }
                    override fun onSetFailure(error: String) {
                        created.completeExceptionally(IllegalStateException(error))
                    }
                }, sdp)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) {
                created.completeExceptionally(IllegalStateException(error))
            }
            override fun onSetFailure(error: String) {
                created.completeExceptionally(IllegalStateException(error))
            }
        }
        if (answer) peer.createAnswer(observer, constraints) else peer.createOffer(observer, constraints)
        val fallbackDescription = created.awaitWithTimeout(10_000L)
        repeat(80) {
            if (peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                return peer.localDescription ?: fallbackDescription
            }
            delay(50)
        }
        return peer.localDescription ?: fallbackDescription
    }

    private suspend fun setRemoteDescription(peer: PeerConnection, description: SessionDescription) {
        val result = CompletableDeferred<Unit>()
        peer.setRemoteDescription(object : SdpObserver by NoopSdpObserver {
            override fun onSetSuccess() {
                result.complete(Unit)
            }
            override fun onSetFailure(error: String) {
                result.completeExceptionally(IllegalStateException(error))
            }
        }, description)
        result.awaitWithTimeout(10_000L)
    }

    private suspend fun awaitConnected(peer: PeerConnection, timeoutMs: Long = 10_000L) {
        val iterations = (timeoutMs / 100L).toInt().coerceAtLeast(10)
        repeat(iterations) {
            when (peer.connectionState()) {
                PeerConnection.PeerConnectionState.CONNECTED -> return
                PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED ->
                    error("No direct route was available.")
                else -> delay(100)
            }
        }
        error("Peer connection timed out.")
    }

    private suspend fun awaitOpen(channel: DataChannel, timeoutMs: Long = 8_000L) {
        val iterations = (timeoutMs / 100L).toInt().coerceAtLeast(10)
        repeat(iterations) {
            if (channel.state() == DataChannel.State.OPEN) return
            if (channel.state() == DataChannel.State.CLOSED) error("Peer data channel closed before transfer.")
            delay(100)
        }
        error("Peer data channel did not open.")
    }

    private suspend fun waitForChannelCapacity(channel: DataChannel) {
        repeat(45_000) {
            if (channel.state() != DataChannel.State.OPEN) error("Peer connection stopped during transfer.")
            if (channel.bufferedAmount() < MAX_BUFFERED_BYTES) return
            delay(20)
        }
        error("Peer connection is too slow; retry the transfer.")
    }

    data class TransferResult(val file: File, val sha256: String, val size: Long, val mimeType: String)

    companion object {
        private const val CHUNK_BYTES = 16 * 1024
        private const val RELAY_CHUNK_BYTES = 192 * 1024
        private const val MAX_BUFFERED_BYTES = 512 * 1024L
        const val MAX_TRANSFER_BYTES = 25L * 1024 * 1024
        private val initLock = Any()
        @Volatile private var peerFactory: PeerConnectionFactory? = null

        private fun factory(context: Context): PeerConnectionFactory = peerFactory ?: synchronized(initLock) {
            peerFactory ?: run {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions()
                )
                PeerConnectionFactory.builder()
                    .setOptions(PeerConnectionFactory.Options())
                    .createPeerConnectionFactory()
                    .also { peerFactory = it }
            }
        }

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

private object NoopSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}

private object EmptyPeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
    override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
    override fun onIceCandidate(candidate: IceCandidate) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
    override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
    override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
    override fun onDataChannel(channel: DataChannel) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) = Unit
    override fun onTrack(transceiver: org.webrtc.RtpTransceiver) = Unit
}
