package com.example.jam

import com.example.BuildConfig
import android.content.Context
import android.net.Uri
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct, ephemeral Jam audio transport. WebRTC chooses a host/LAN route when available,
 * then a STUN-discovered direct route; configured TURN servers are optional relays.
 * Firestore stores only the short-lived SDP handshake and transfer state, never audio.
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
        try {
            val transferRef = transferRef(sessionId, transferId(recommendationId, recipientUid))
            val peer = createPeer()
            val channel = peer.createDataChannel("jam-audio", DataChannel.Init().apply { ordered = true })
            try {
                val offer = createDescription(peer, answer = false)
                transferRef.set(mapOf(
                    "recommendationId" to recommendationId,
                    "senderUid" to senderUid,
                    "recipientUid" to recipientUid,
                    "mimeType" to mimeType.take(100),
                    "fileSize" to file.length(),
                    "sha256" to sha256(file),
                    "offerSdp" to offer.description,
                    "status" to "OFFERED",
                    "createdAtMs" to System.currentTimeMillis()
                )).await()
                awaitAnswer(transferRef, peer)
                awaitConnected(peer)
                awaitOpen(channel)
                val bytes = ByteArray(CHUNK_BYTES)
                file.inputStream().buffered().use { input ->
                    var sent = 0L
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        waitForChannelCapacity(channel)
                        val payload = ByteBuffer.allocateDirect(count)
                        payload.put(bytes, 0, count).flip()
                        if (!channel.send(DataChannel.Buffer(payload, true))) error("Peer connection stopped during transfer.")
                        sent += count
                        onProgress((sent * 100 / file.length().coerceAtLeast(1L)).toInt())
                    }
                }
                transferRef.update("status", "SENT", "completedAtMs", System.currentTimeMillis()).await()
                awaitTransferStatus(transferRef, "RECEIVED")
                TransferResult(file, sha256(file), file.length(), mimeType)
            } finally {
                channel.dispose()
                peer.close()
                peer.dispose()
                runCatching { transferRef.delete().await() }
            }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /** Accepts an incoming offer and writes the stream to a temporary file for verification. */
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
        for (attempt in 0 until 300) {
            offerData = transferRef.get().await().data
            if (offerData != null) break
            delay(100)
        }
        val offer = offerData ?: error("The sender is not currently available. Retry when both devices are online.")
        require(offer["senderUid"] == senderUid && offer["recipientUid"] == recipientUid) { "The transfer invitation is invalid." }
        require((offer["fileSize"] as? Number)?.toLong() == expectedBytes) { "The sender's file details changed." }
        val expectedHash = offer["sha256"] as? String ?: error("The sender did not provide a file checksum.")
        val offerText = offer["offerSdp"] as? String ?: error("The sender's connection offer is missing.")
        val received = CompletableDeferred<File>()
        val output = File(context.cacheDir, "jam-$recommendationId-${System.nanoTime()}.part")
        val bytesReceived = longArrayOf(0)
        val outputStream = FileOutputStream(output)
        val observer = object : PeerConnection.Observer by EmptyPeerObserver {
            override fun onDataChannel(channel: DataChannel) {
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
        try {
            setRemoteDescription(peer, SessionDescription(SessionDescription.Type.OFFER, offerText))
            val answer = createDescription(peer, answer = true)
            transferRef.update(mapOf("answerSdp" to answer.description, "status" to "ANSWERED")).await()
            val resultFile = received.awaitWithTimeout(15 * 60_000L)
            val hash = sha256(resultFile)
            require(hash.equals(expectedHash, true)) { "File verification failed." }
            val mime = offer["mimeType"] as? String ?: "audio/mpeg"
            transferRef.update(mapOf("status" to "RECEIVED", "completedAtMs" to System.currentTimeMillis())).await()
            TransferResult(resultFile, hash, expectedBytes, mime)
        } catch (error: Throwable) {
            runCatching { outputStream.close() }
            output.delete()
            throw error
        } finally {
            peer.close()
            peer.dispose()
        }
    }

    private fun transferRef(sessionId: String, id: String) = firestore.collection("jamSessions")
        .document(sessionId).collection("peerTransfers").document(id)

    private fun transferId(recommendationId: String, recipientUid: String) = "${recommendationId.take(80)}_${recipientUid.take(80)}"

    private fun copyUriToCache(uri: Uri, recommendationId: String): File {
        val file = File(context.cacheDir, "jam-send-$recommendationId-${System.nanoTime()}.part")
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use(input::copyTo) }
            ?: error("The selected song could not be opened.")
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
        val configuration = PeerConnection.RTCConfiguration(servers).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return factory.createPeerConnection(configuration, observer)
            ?: error("Could not create a peer connection.")
    }

    private suspend fun awaitAnswer(ref: com.google.firebase.firestore.DocumentReference, peer: PeerConnection) {
        val answer = CompletableDeferred<String>()
        lateinit var listener: ListenerRegistration
        listener = ref.addSnapshotListener { snapshot, error ->
            if (error != null) answer.completeExceptionally(error)
            else snapshot?.getString("answerSdp")?.let(answer::complete)
        }
        try {
            setRemoteDescription(peer, SessionDescription(SessionDescription.Type.ANSWER, answer.awaitWithTimeout()))
        } finally { listener.remove() }
    }

    private suspend fun awaitTransferStatus(ref: com.google.firebase.firestore.DocumentReference, target: String) {
        val reached = CompletableDeferred<Unit>()
        val listener = ref.addSnapshotListener { snapshot, error ->
            if (error != null) reached.completeExceptionally(error)
            else if (snapshot?.getString("status") == target) reached.complete(Unit)
        }
        try { reached.awaitWithTimeout(2 * 60_000L) } finally { listener.remove() }
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
                    override fun onSetFailure(error: String) { created.completeExceptionally(IllegalStateException(error)) }
                }, sdp)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) { created.completeExceptionally(IllegalStateException(error)) }
            override fun onSetFailure(error: String) { created.completeExceptionally(IllegalStateException(error)) }
        }
        if (answer) peer.createAnswer(observer, constraints) else peer.createOffer(observer, constraints)
        val description = created.awaitWithTimeout()
        repeat(200) {
            if (peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return description
            delay(50)
        }
        return description
    }

    private suspend fun setRemoteDescription(peer: PeerConnection, description: SessionDescription) {
        val result = CompletableDeferred<Unit>()
        peer.setRemoteDescription(object : SdpObserver by NoopSdpObserver {
            override fun onSetSuccess() { result.complete(Unit) }
            override fun onSetFailure(error: String) { result.completeExceptionally(IllegalStateException(error)) }
        }, description)
        result.awaitWithTimeout()
    }

    private suspend fun awaitConnected(peer: PeerConnection) {
        repeat(300) {
            when (peer.connectionState()) {
                PeerConnection.PeerConnectionState.CONNECTED -> return
                PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> error("No direct route was available. Configure TURN or try a compatible local network.")
                else -> delay(100)
            }
        }
        error("Peer connection timed out. Try again or configure TURN for restrictive networks.")
    }

    private suspend fun awaitOpen(channel: DataChannel) {
        repeat(300) {
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
        private const val MAX_BUFFERED_BYTES = 512 * 1024L
        const val MAX_TRANSFER_BYTES = 25L * 1024 * 1024
        private val initLock = Any()
        @Volatile private var peerFactory: PeerConnectionFactory? = null

        private fun factory(context: Context): PeerConnectionFactory = peerFactory ?: synchronized(initLock) {
            peerFactory ?: run {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
                PeerConnectionFactory.builder().createPeerConnectionFactory().also { peerFactory = it }
            }
        }

        private suspend fun <T> CompletableDeferred<T>.awaitWithTimeout(timeoutMs: Long = 45_000L): T {
            repeat((timeoutMs / 100).toInt()) {
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
