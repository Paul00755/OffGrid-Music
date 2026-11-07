package com.example.jam

import com.example.auth.AuthManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Client facade: all Jam writes go through authenticated, trusted callable functions. */
class JamRepository(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val authManager: AuthManager,
    private val inviteHost: String
) {
    private fun requireUid() {
        if (authManager.currentUser?.uid.isNullOrBlank()) throw JamRepositoryException("Sign in to use OffGrid Jam.")
    }

    suspend fun createSession(displayName: String, photoUrl: String?, permissions: JamPermissions): JamInvite {
        requireUid()
        val result = call("createJam", mapOf(
            "displayName" to displayName.take(80),
            "photoUrl" to (photoUrl ?: ""),
            "permissions" to permissions.toMap()
        ))
        val id = result["sessionId"] as? String ?: throw JamRepositoryException("The Jam was created without a session ID.")
        val code = result["invitationCode"] as? String ?: throw JamRepositoryException("The Jam was created without an invitation code.")
        return JamInvite(id, code, buildInviteUrl(code))
    }

    suspend fun joinByCode(code: String, displayName: String, photoUrl: String?): String {
        requireUid()
        val result = call("joinJam", mapOf(
            "invitationCode" to code.filter(Char::isLetterOrDigit).uppercase().take(16),
            "displayName" to displayName.take(80),
            "photoUrl" to (photoUrl ?: "")
        ))
        return result["sessionId"] as? String ?: throw JamRepositoryException("The invitation response was incomplete.")
    }

    suspend fun leave(sessionId: String) {
        requireUid()
        call("leaveJam", mapOf("sessionId" to sessionId))
    }

    suspend fun closeSession(sessionId: String) {
        requireUid()
        call("closeJam", mapOf("sessionId" to sessionId))
    }

    suspend fun transferHost(sessionId: String, participantUid: String) {
        requireUid()
        call("transferJamHost", mapOf("sessionId" to sessionId, "participantUid" to participantUid))
    }

    suspend fun updatePermissions(sessionId: String, permissions: JamPermissions) {
        requireUid()
        call("updateJamPermissions", mapOf("sessionId" to sessionId, "permissions" to permissions.toMap()))
    }

    suspend fun heartbeat(sessionId: String, displayName: String, photoUrl: String?) {
        requireUid()
        call("heartbeatJam", mapOf(
            "sessionId" to sessionId,
            "displayName" to displayName.take(80),
            "photoUrl" to (photoUrl ?: "")
        ))
    }

    suspend fun addTrack(sessionId: String, track: JamTrack) {
        requireUid()
        call("mutateJamQueue", mapOf("sessionId" to sessionId, "operation" to "ADD", "track" to track.toMap()))
    }

    suspend fun removeTrack(sessionId: String, queueItemId: String) {
        requireUid()
        call("mutateJamQueue", mapOf("sessionId" to sessionId, "operation" to "REMOVE", "queueItemId" to queueItemId))
    }

    suspend fun reorderQueue(sessionId: String, itemIds: List<String>) {
        requireUid()
        call("mutateJamQueue", mapOf("sessionId" to sessionId, "operation" to "REORDER", "queueItemIds" to itemIds))
    }

    suspend fun submitPlaybackCommand(
        sessionId: String,
        type: JamPermissionKind,
        isPlaying: Boolean? = null,
        positionMs: Long? = null,
        step: Int = 1
    ): JamPlaybackState {
        requireUid()
        val payload = mutableMapOf<String, Any>(
            "sessionId" to sessionId,
            "commandType" to type.name,
            "commandId" to java.util.UUID.randomUUID().toString()
        )
        if (isPlaying != null) payload["isPlaying"] = isPlaying
        if (positionMs != null) payload["positionMs"] = positionMs.coerceAtLeast(0L)
        if (type == JamPermissionKind.SKIP_TRACK) payload["step"] = if (step < 0) -1 else 1
        val result = call("submitJamPlaybackCommand", payload)
        return JamPlaybackState(
            sharedTrackId = result["sharedTrackId"] as? String,
            queueItemId = result["queueItemId"] as? String,
            isPlaying = result["isPlaying"] as? Boolean ?: false,
            positionMs = (result["positionMs"] as? Number)?.toLong() ?: 0L,
            playbackSpeed = (result["playbackSpeed"] as? Number)?.toFloat() ?: 1f,
            updatedAtMs = (result["updatedAtMs"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            revision = (result["revision"] as? Number)?.toLong() ?: 0L,
            commandId = result["commandId"] as? String,
            commandType = result["commandType"] as? String,
            actorUid = result["actorUid"] as? String
        )
    }

    suspend fun revokeInvite(sessionId: String) {
        requireUid()
        call("revokeJamInvite", mapOf("sessionId" to sessionId))
    }

    suspend fun registerDeviceToken(token: String) {
        requireUid()
        call("registerJamDeviceToken", mapOf("token" to token))
    }

    suspend fun createRecommendation(sessionId: String, type: JamRecommendationType, track: JamTrack): String {
        requireUid()
        val result = call("createJamRecommendation", mapOf(
            "sessionId" to sessionId,
            "recommendationType" to type.name,
            "track" to track.toMap()
        ))
        return result["recommendationId"] as? String
            ?: throw JamRepositoryException("The recommendation response was incomplete.")
    }

    suspend fun voteRecommendation(sessionId: String, recommendationId: String, vote: JamVote) {
        requireUid()
        call("voteJamRecommendation", mapOf(
            "sessionId" to sessionId,
            "recommendationId" to recommendationId,
            "vote" to vote.name
        ))
    }

    suspend fun beginLocalUpload(sessionId: String, recommendationId: String): String {
        requireUid()
        val result = call("beginJamUpload", mapOf("sessionId" to sessionId, "recommendationId" to recommendationId))
        return result["storagePath"] as? String ?: throw JamRepositoryException("The upload was not authorized.")
    }

    suspend fun completeLocalUpload(sessionId: String, recommendationId: String) {
        requireUid()
        call("completeJamUpload", mapOf("sessionId" to sessionId, "recommendationId" to recommendationId))
    }

    suspend fun reportLocalUploadProgress(sessionId: String, recommendationId: String, progress: Int) {
        requireUid()
        call("updateJamUploadProgress", mapOf(
            "sessionId" to sessionId,
            "recommendationId" to recommendationId,
            "progress" to progress.coerceIn(0, 100)
        ))
    }

    suspend fun failLocalUpload(sessionId: String, recommendationId: String, error: String) {
        requireUid()
        call("failJamUpload", mapOf("sessionId" to sessionId, "recommendationId" to recommendationId, "error" to error.take(160)))
    }

    suspend fun retryLocalUpload(sessionId: String, recommendationId: String) {
        requireUid()
        call("retryJamUpload", mapOf("sessionId" to sessionId, "recommendationId" to recommendationId))
    }

    suspend fun updateDownloadState(
        sessionId: String,
        recommendationId: String,
        status: String,
        progress: Int = 0,
        checksum: String? = null,
        error: String? = null
    ) {
        requireUid()
        val payload = mutableMapOf<String, Any>(
            "sessionId" to sessionId,
            "recommendationId" to recommendationId,
            "status" to status,
            "progress" to progress.coerceIn(0, 100)
        )
        checksum?.let { payload["checksum"] = it; payload["checksumVerified"] = true; payload["locallyAvailable"] = true }
        error?.let { payload["error"] = it.take(160) }
        call("updateJamDownloadState", payload)
    }

    suspend fun retryDownload(sessionId: String, recommendationId: String) {
        requireUid()
        call("retryJamDownload", mapOf("sessionId" to sessionId, "recommendationId" to recommendationId))
    }

    fun observeSession(sessionId: String): Flow<JamSession?> = listenDocument(sessionRef(sessionId)) { snapshot ->
        if (snapshot.exists()) JamSession.from(snapshot.id, snapshot) else null
    }

    fun observeParticipants(sessionId: String): Flow<List<JamParticipant>> = callbackFlow {
        val registration: ListenerRegistration = sessionRef(sessionId).collection(PARTICIPANTS)
            .orderBy("joinedAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) close(error)
                else trySend(snapshot?.documents.orEmpty().mapNotNull(::participantFrom))
            }
        awaitClose { registration.remove() }
    }

    fun observeQueue(sessionId: String): Flow<List<JamQueueItem>> = callbackFlow {
        val registration: ListenerRegistration = sessionRef(sessionId).collection(QUEUE)
            .orderBy("position", Query.Direction.ASCENDING)
            .limit(MAX_QUEUE_SIZE.toLong())
            .addSnapshotListener { snapshot, error ->
                if (error != null) close(error)
                else trySend(snapshot?.documents.orEmpty().mapNotNull(::queueItemFrom))
            }
        awaitClose { registration.remove() }
    }

    fun observeRecommendations(sessionId: String): Flow<List<JamRecommendation>> = callbackFlow {
        val registration = sessionRef(sessionId).collection(RECOMMENDATIONS)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(MAX_RECOMMENDATIONS.toLong())
            .addSnapshotListener { snapshot, error ->
                if (error != null) close(error)
                else trySend(snapshot?.documents.orEmpty().mapNotNull(::recommendationFrom))
            }
        awaitClose { registration.remove() }
    }

    private suspend fun call(name: String, data: Map<String, Any>): Map<String, Any?> {
        return try {
            val result = functions.getHttpsCallable(name).call(data).await().data
            @Suppress("UNCHECKED_CAST")
            (result as? Map<String, Any?>) ?: emptyMap()
        } catch (error: Throwable) {
            throw JamRepositoryException(error.message ?: "Jam service is unavailable.")
        }
    }

    private fun sessionRef(id: String) = firestore.collection(SESSIONS).document(id)

    private fun <T> listenDocument(ref: DocumentReference, parse: (DocumentSnapshot) -> T): Flow<T> = callbackFlow {
        val registration = ref.addSnapshotListener { snapshot, error ->
            if (error != null) close(error)
            else if (snapshot != null) trySend(parse(snapshot))
        }
        awaitClose { registration.remove() }
    }

    private fun participantFrom(snapshot: DocumentSnapshot): JamParticipant? = JamParticipant(
        uid = snapshot.id,
        displayName = snapshot.getString("displayName") ?: "Listener",
        photoUrl = snapshot.getString("photoUrl")?.takeIf(String::isNotBlank),
        joinedAtMs = snapshot.getTimestamp("joinedAt")?.toDate()?.time ?: 0L,
        lastSeenAtMs = snapshot.getTimestamp("lastSeenAt")?.toDate()?.time ?: 0L,
        isHost = snapshot.getString("role") == "HOST"
    )

    private fun queueItemFrom(snapshot: DocumentSnapshot): JamQueueItem? {
        val data = snapshot.get("track") as? Map<*, *> ?: return null
        val trackId = data["sharedTrackId"] as? String ?: return null
        val title = data["title"] as? String ?: return null
        return JamQueueItem(
            id = snapshot.id,
            track = JamTrack(
                sharedTrackId = trackId,
                title = title,
                artist = data["artist"] as? String ?: "Unknown Artist",
                album = data["album"] as? String ?: "Unknown Album",
                durationMs = (data["durationMs"] as? Number)?.toLong() ?: 0L,
                checksum = data["checksum"] as? String
            ),
            addedByUid = snapshot.getString("addedByUid").orEmpty(),
            position = snapshot.getLong("position") ?: 0L
        )
    }

    private fun recommendationFrom(snapshot: DocumentSnapshot): JamRecommendation? {
        val data = snapshot.data ?: return null
        val rawTrack = data["track"] as? Map<*, *> ?: return null
        val type = runCatching {
            JamRecommendationType.valueOf(data["recommendationType"] as? String ?: "")
        }.getOrNull() ?: return null
        val votes = (data["votes"] as? Map<*, *>).orEmpty().mapNotNull { (uid, value) ->
            val parsed = runCatching { JamVote.valueOf(value as? String ?: "PENDING") }.getOrDefault(JamVote.PENDING)
            (uid as? String)?.let { it to parsed }
        }.toMap()
        val downloads = (data["downloads"] as? Map<*, *>).orEmpty().mapNotNull { (uid, value) ->
            val state = value as? Map<*, *> ?: return@mapNotNull null
            (uid as? String)?.let { key -> key to JamDownloadState(
                status = state["status"] as? String ?: "WAITING_FOR_UPLOAD",
                progress = (state["progress"] as? Number)?.toInt()?.coerceIn(0, 100) ?: 0,
                checksumVerified = state["checksumVerified"] as? Boolean ?: false,
                locallyAvailable = state["locallyAvailable"] as? Boolean ?: false,
                readyForPlayback = state["readyForPlayback"] as? Boolean ?: false,
                error = (state["error"] as? String)?.takeIf(String::isNotBlank)
            ) }
        }.toMap()
        return JamRecommendation(
            id = snapshot.id,
            type = type,
            proposerUid = data["proposerUid"] as? String ?: "",
            proposerName = data["proposerName"] as? String ?: "Listener",
            track = JamTrack(
                sharedTrackId = rawTrack["sharedTrackId"] as? String ?: return null,
                title = rawTrack["title"] as? String ?: return null,
                artist = rawTrack["artist"] as? String ?: "Unknown Artist",
                album = rawTrack["album"] as? String ?: "Unknown Album",
                durationMs = (rawTrack["durationMs"] as? Number)?.toLong() ?: 0L,
                checksum = rawTrack["checksum"] as? String
            ),
            eligibleUids = (data["eligibleUids"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            deadlineAtMs = snapshot.getTimestamp("deadlineAt")?.toDate()?.time ?: 0L,
            status = data["status"] as? String ?: "FAILED",
            approvalCount = (data["approvalCount"] as? Number)?.toInt() ?: 0,
            rejectionCount = (data["rejectionCount"] as? Number)?.toInt() ?: 0,
            requiredApprovalCount = (data["requiredApprovalCount"] as? Number)?.toInt() ?: 0,
            votes = votes,
            downloads = downloads,
            storagePath = data["storagePath"] as? String,
            checksum = data["checksum"] as? String,
            fileSize = (data["fileSize"] as? Number)?.toLong() ?: 0L,
            transferExpiresAtMs = snapshot.getTimestamp("transferExpiresAt")?.toDate()?.time ?: 0L,
            contentType = data["contentType"] as? String,
            uploadProgress = (data["uploadProgress"] as? Number)?.toInt()?.coerceIn(0, 100) ?: 0,
            uploadStatus = data["uploadStatus"] as? String ?: "WAITING_FOR_UPLOAD",
            failureReason = data["failureReason"] as? String
        )
    }

    private fun buildInviteUrl(code: String): String? {
        val host = inviteHost.trim().trim('/').takeIf(String::isNotBlank) ?: return null
        return "https://$host/jam/${code.lowercase()}"
    }

    class JamRepositoryException(message: String) : IllegalStateException(message)

    companion object {
        const val MAX_PARTICIPANTS = 10
        const val MAX_QUEUE_SIZE = 200
        private const val SESSIONS = "jamSessions"
        private const val PARTICIPANTS = "participants"
        private const val QUEUE = "queue"
        private const val RECOMMENDATIONS = "recommendations"
        private const val MAX_RECOMMENDATIONS = 30
    }
}

typealias JamRepositoryException = JamRepository.JamRepositoryException
