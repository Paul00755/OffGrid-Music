package com.example.jam

import com.example.auth.AuthManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Client facade: all Jam writes go through authenticated, trusted callable functions. */
class JamRepository(
    private val firestore: FirebaseFirestore,
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

    suspend fun beginLocalUpload(sessionId: String, recommendationId: String) {
        requireUid()
        call("beginJamUpload", mapOf("sessionId" to sessionId, "recommendationId" to recommendationId))
    }

    suspend fun preparePeerUpload(sessionId: String, recommendationId: String, checksum: String, fileSize: Long, contentType: String) {
        requireUid()
        val ref = sessionRef(sessionId).collection(RECOMMENDATIONS).document(recommendationId)
        ref.update(mapOf("checksum" to checksum, "fileSize" to fileSize, "contentType" to contentType.take(100))).await()
    }

    suspend fun getRecommendation(sessionId: String, recommendationId: String): JamRecommendation? =
        recommendationFrom(sessionRef(sessionId).collection(RECOMMENDATIONS).document(recommendationId).get().await())

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

    /** Spark-compatible operations: Firestore stores Jam metadata only; no callable/server storage. */
    private suspend fun call(name: String, data: Map<String, Any>): Map<String, Any?> = try {
        val uid = authManager.currentUser?.uid?.takeIf(String::isNotBlank)
            ?: throw JamRepositoryException("Sign in to use OffGrid Jam.")
        when (name) {
            "createJam" -> createJamClient(uid, data)
            "joinJam" -> joinJamClient(uid, data)
            "heartbeatJam" -> heartbeatClient(uid, data)
            "updateJamPermissions" -> updatePermissionsClient(uid, data)
            "mutateJamQueue" -> mutateQueueClient(uid, data)
            "registerJamDeviceToken" -> emptyMap()
            "createJamRecommendation" -> createRecommendationClient(uid, data)
            "voteJamRecommendation" -> voteRecommendationClient(uid, data)
            "beginJamUpload" -> beginPeerTransferClient(uid, data)
            "updateJamUploadProgress" -> updatePeerTransferProgressClient(uid, data)
            "failJamUpload" -> failPeerTransferClient(uid, data)
            "retryJamUpload" -> retryPeerTransferClient(uid, data)
            "completeJamUpload" -> completePeerTransferClient(uid, data)
            "updateJamDownloadState" -> updateDownloadClient(uid, data)
            "retryJamDownload" -> retryDownloadClient(uid, data)
            "submitJamPlaybackCommand" -> playbackCommandClient(uid, data)
            "closeJam" -> closeJamClient(uid, data)
            "revokeJamInvite" -> revokeInviteClient(uid, data)
            "transferJamHost" -> transferHostClient(uid, data)
            "leaveJam" -> leaveJamClient(uid, data)
            else -> throw JamRepositoryException("This Jam action is not supported on the Spark backend.")
        }
    } catch (error: Throwable) {
        if (error is JamRepositoryException) throw error
        throw JamRepositoryException(error.message ?: "Jam service is unavailable.")
    }

    private suspend fun createJamClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val id = java.util.UUID.randomUUID().toString()
        val code = generateInviteCode()
        val inviteHash = invitationDigest(code)
        val session = sessionRef(id)
        val member = session.collection(PARTICIPANTS).document(uid)
        val invite = firestore.collection("jamInvitations").document(inviteHash)
        val playback = mapOf("sharedTrackId" to "", "queueItemId" to "", "isPlaying" to false, "positionMs" to 0L,
            "playbackSpeed" to 1.0, "updatedAt" to Timestamp.now(), "revision" to 0L, "commandId" to "",
            "commandType" to "CREATE", "actorUid" to uid, "step" to 0)
        firestore.runTransaction { tx ->
            tx.set(session, mapOf("hostUid" to uid, "status" to "ACTIVE", "participantCount" to 1,
                "maxParticipants" to MAX_PARTICIPANTS, "inviteCodeHash" to inviteHash, "createdAt" to Timestamp.now(),
                "permissions" to data["permissions"], "playback" to playback, "revision" to 1L, "queueRevision" to 0L))
            tx.set(member, participantRecord(uid, data["displayName"] as? String, data["photoUrl"] as? String, true))
            tx.set(invite, mapOf("sessionId" to id, "active" to true, "createdAt" to Timestamp.now(),
                "expiresAt" to Timestamp(java.util.Date(System.currentTimeMillis() + INVITE_LIFETIME_MS))))
            null
        }.await()
        return mapOf("sessionId" to id, "invitationCode" to code)
    }

    private suspend fun joinJamClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val code = (data["invitationCode"] as? String).orEmpty().filter(Char::isLetterOrDigit).uppercase()
        if (code.length != INVITE_CODE_LENGTH) throw JamRepositoryException("Invitation code is invalid.")
        val invitation = firestore.collection("jamInvitations").document(invitationDigest(code))
        val info = invitation.get().await()
        val id = info.getString("sessionId") ?: throw JamRepositoryException("This invitation is invalid or has been revoked.")
        val session = sessionRef(id)
        val member = session.collection(PARTICIPANTS).document(uid)
        firestore.runTransaction { tx ->
            val inviteSnapshot = tx.get(invitation)
            val sessionSnapshot = tx.get(session)
            val memberSnapshot = tx.get(member)
            if (inviteSnapshot.getBoolean("active") != true || (inviteSnapshot.getTimestamp("expiresAt")?.toDate()?.time ?: 0L) <= System.currentTimeMillis()) {
                throw JamRepositoryException("This invitation has expired or was revoked.")
            }
            if (sessionSnapshot.getString("status") != "ACTIVE") throw JamRepositoryException("This Jam has ended.")
            if (!memberSnapshot.exists()) {
                val count = sessionSnapshot.getLong("participantCount") ?: 0L
                if (count >= MAX_PARTICIPANTS) throw JamRepositoryException("This Jam is full (maximum 10 people).")
                tx.set(member, participantRecord(uid, data["displayName"] as? String, data["photoUrl"] as? String, false) + mapOf("invitationHash" to invitation.id))
                tx.update(session, mapOf("participantCount" to count + 1, "revision" to (sessionSnapshot.getLong("revision") ?: 0L) + 1))
            }
            id
        }.await()
        return mapOf("sessionId" to id)
    }

    private suspend fun heartbeatClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val id = sessionId(data); val session = sessionRef(id); val member = session.collection(PARTICIPANTS).document(uid)
        val snapshot = member.get().await()
        if (!snapshot.exists()) throw JamRepositoryException("You are not a participant in this Jam.")
        member.update(mapOf("displayName" to safeDisplayName(data), "photoUrl" to safePhoto(data), "lastSeenAt" to Timestamp.now())).await()
        return mapOf("ok" to true)
    }

    private suspend fun updatePermissionsClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val session = sessionRef(sessionId(data)); val snapshot = session.get().await()
        if (snapshot.getString("hostUid") != uid) throw JamRepositoryException("Only the host can change Jam permissions.")
        session.update(mapOf("permissions" to data["permissions"], "revision" to (snapshot.getLong("revision") ?: 0L) + 1)).await()
        return mapOf("ok" to true)
    }

    private suspend fun mutateQueueClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val id = sessionId(data); val session = sessionRef(id); val queue = session.collection(QUEUE)
        val operation = data["operation"] as? String ?: ""
        val documents = queue.orderBy("position").limit(MAX_QUEUE_SIZE.toLong()).get().await().documents
        val current = session.get().await()
        val permissions = JamPermissions.from(current.get("permissions"))
        val kind = if (operation == "ADD") JamPermissionKind.ADD_TO_QUEUE else JamPermissionKind.MANAGE_QUEUE
        if (!permissions.allows(kind, uid, current.getString("hostUid").orEmpty())) throw JamRepositoryException("You don't have permission to change the queue.")
        when (operation) {
            "ADD" -> {
                if (documents.size >= MAX_QUEUE_SIZE) throw JamRepositoryException("The Jam queue is full.")
                val track = data["track"] as? Map<*, *> ?: throw JamRepositoryException("Track data is invalid.")
                val doc = queue.document()
                doc.set(mapOf("track" to track, "position" to documents.size, "addedByUid" to uid, "addedAt" to Timestamp.now())).await()
            }
            "REMOVE" -> queue.document(data["queueItemId"] as? String ?: "").delete().await()
            "REORDER" -> {
                val ids = (data["queueItemIds"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                if (ids.toSet() != documents.map { it.id }.toSet() || ids.size != documents.size) throw JamRepositoryException("Queue changed. Try again.")
                val batch = firestore.batch()
                ids.forEachIndexed { index, itemId -> batch.update(queue.document(itemId), "position", index) }
                batch.commit().await()
            }
            else -> throw JamRepositoryException("Queue operation is invalid.")
        }
        session.update(mapOf("queueRevision" to (current.getLong("queueRevision") ?: 0L) + 1, "revision" to (current.getLong("revision") ?: 0L) + 1)).await()
        return mapOf("ok" to true)
    }

    private suspend fun createRecommendationClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val id = sessionId(data); val session = sessionRef(id); val snapshot = session.get().await()
        val type = data["recommendationType"] as? String ?: ""
        val permissions = JamPermissions.from(snapshot.get("permissions"))
        if (type == "LOCAL_SONG" && !permissions.allowLocalSongSharing) throw JamRepositoryException("Local song sharing is disabled.")
        if (type == "SHARED_LIBRARY" && !permissions.allowSharedLibraryRecommendations) throw JamRepositoryException("Recommendations are disabled.")
        if (type == "SHARED_LIBRARY" && permissions.manageQueue.mode != JamAccessMode.HOST_ONLY) throw JamRepositoryException("Shared-library recommendations require host-controlled queue management.")
        val members = session.collection(PARTICIPANTS).get().await().documents
        val eligible = members.map { it.id }
        val required = if (type == "LOCAL_SONG") eligible.size else eligible.size / 2 + 1
        val track = data["track"] ?: throw JamRepositoryException("Track data is invalid.")
        val ref = session.collection(RECOMMENDATIONS).document()
        ref.set(mapOf("recommendationType" to type, "proposerUid" to uid,
            "proposerName" to (authManager.currentUser?.displayName ?: "Listener"), "track" to track,
            "eligibleUids" to eligible, "deadlineAt" to Timestamp(java.util.Date(System.currentTimeMillis() + VOTE_WINDOW_MS)),
            "status" to "VOTING", "requiredApprovalCount" to required, "approvalCount" to 0, "rejectionCount" to 0,
            "votes" to eligible.associateWith { if (it == uid) "ACCEPT" else "PENDING" },
            "downloads" to if (type == "LOCAL_SONG") mapOf(uid to mapOf("status" to "READY_FOR_PLAYBACK", "progress" to 100,
                "checksumVerified" to true, "locallyAvailable" to true, "readyForPlayback" to true)) else emptyMap<String, Any>(),
            "createdAt" to Timestamp.now(), "uploadStatus" to "WAITING_FOR_PEERS")).await()
        if (eligible.size == 1) ref.update(mapOf("approvalCount" to 1, "status" to if (type == "LOCAL_SONG") "APPROVED_PENDING_UPLOAD" else "READY")).await()
        return mapOf("recommendationId" to ref.id)
    }

    private suspend fun voteRecommendationClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val session = sessionRef(sessionId(data)); val ref = session.collection(RECOMMENDATIONS).document(data["recommendationId"] as? String ?: "")
        val existingQueueSize = session.collection(QUEUE).orderBy("position").limit(MAX_QUEUE_SIZE.toLong()).get().await().size()
        firestore.runTransaction { tx ->
            val snap = tx.get(ref); val votes = (snap.get("votes") as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }?.toMutableMap() ?: mutableMapOf()
            if (snap.getString("status") != "VOTING" || uid !in (snap.get("eligibleUids") as? List<*>).orEmpty()) throw JamRepositoryException("Voting is closed for this recommendation.")
            if (votes[uid] != "PENDING") throw JamRepositoryException("You already voted on this song.")
            val vote = data["vote"] as? String ?: ""
            if (vote !in setOf("ACCEPT", "REJECT")) throw JamRepositoryException("Vote is invalid.")
            votes[uid] = vote
            val yes = votes.values.count { it == "ACCEPT" }; val no = votes.values.count { it == "REJECT" }
            val pending = votes.values.count { it == "PENDING" }; val required = (snap.getLong("requiredApprovalCount") ?: 1L).toInt()
            val type = snap.getString("recommendationType") ?: "SHARED_LIBRARY"
            val status = when {
                type == "LOCAL_SONG" && no > 0 -> "REJECTED"
                yes >= required -> if (type == "LOCAL_SONG") "APPROVED_PENDING_UPLOAD" else "READY"
                yes + pending < required -> "REJECTED"
                System.currentTimeMillis() >= (snap.getTimestamp("deadlineAt")?.toDate()?.time ?: 0L) -> "EXPIRED"
                else -> "VOTING"
            }
            if (status == "READY" && type == "SHARED_LIBRARY") {
                val sessionSnapshot = tx.get(session)
                if (existingQueueSize >= MAX_QUEUE_SIZE) throw JamRepositoryException("The shared queue is full.")
                val track = snap.get("track") as? Map<*, *> ?: throw JamRepositoryException("Track metadata is missing.")
                tx.set(session.collection(QUEUE).document(), mapOf("track" to track, "position" to existingQueueSize,
                    "addedByUid" to snap.getString("proposerUid").orEmpty(), "addedAt" to Timestamp.now(), "source" to "RECOMMENDATION"))
                tx.update(session, mapOf("queueRevision" to (sessionSnapshot.getLong("queueRevision") ?: 0L) + 1L,
                    "revision" to (sessionSnapshot.getLong("revision") ?: 0L) + 1L))
            }
            tx.update(ref, mapOf("votes" to votes, "approvalCount" to yes, "rejectionCount" to no, "status" to status))
            null
        }.await()
        return mapOf("ok" to true)
    }

    private suspend fun beginPeerTransferClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val ref = recommendationRef(data); val snapshot = ref.get().await()
        val isFirstSend = snapshot.getString("status") == "APPROVED_PENDING_UPLOAD"
        val isRetry = snapshot.getString("status") == "DOWNLOADING" && snapshot.getString("uploadStatus") == "RETRY_REQUIRED"
        if (snapshot.getString("proposerUid") != uid || (!isFirstSend && !isRetry)) throw JamRepositoryException("This peer transfer is not ready to start.")
        ref.update(mapOf("status" to "UPLOADING", "uploadStatus" to "UPLOADING", "uploadProgress" to 0)).await()
        return mapOf("ok" to true)
    }
    private suspend fun updatePeerTransferProgressClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val ref = recommendationRef(data); val snapshot = ref.get().await()
        if (snapshot.getString("proposerUid") != uid) throw JamRepositoryException("This transfer belongs to another participant.")
        ref.update("uploadProgress", (data["progress"] as? Number)?.toInt()?.coerceIn(0, 100) ?: 0).await()
        return mapOf("ok" to true)
    }
    private suspend fun failPeerTransferClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val ref = recommendationRef(data); val snapshot = ref.get().await()
        if (snapshot.getString("proposerUid") != uid) throw JamRepositoryException("This transfer belongs to another participant.")
        ref.update(mapOf("status" to "FAILED", "uploadStatus" to "UPLOAD_FAILED", "failureReason" to (data["error"] as? String ?: "Transfer failed."))).await()
        return mapOf("ok" to true)
    }
    private suspend fun retryPeerTransferClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val ref = recommendationRef(data); val snapshot = ref.get().await()
        if (snapshot.getString("proposerUid") != uid) throw JamRepositoryException("This transfer belongs to another participant.")
        ref.update(mapOf("status" to "APPROVED_PENDING_UPLOAD", "uploadStatus" to "WAITING_FOR_PEERS", "uploadProgress" to 0)).await()
        return mapOf("ok" to true)
    }
    private suspend fun completePeerTransferClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val ref = recommendationRef(data); val snapshot = ref.get().await()
        if (snapshot.getString("proposerUid") != uid) throw JamRepositoryException("This transfer belongs to another participant.")
        ref.update(mapOf("status" to "DOWNLOADING", "uploadStatus" to "COMPLETE", "uploadProgress" to 100)).await()
        return mapOf("ok" to true)
    }
    private suspend fun updateDownloadClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val ref = recommendationRef(data)
        val session = sessionRef(sessionId(data))
        val existingQueueSize = session.collection(QUEUE).orderBy("position").limit(MAX_QUEUE_SIZE.toLong()).get().await().size()
        firestore.runTransaction { tx ->
            val snap = tx.get(ref)
            if (uid !in (snap.get("eligibleUids") as? List<*>).orEmpty()) throw JamRepositoryException("You are not part of this transfer.")
            val downloads = (snap.get("downloads") as? Map<*, *>)?.entries?.associate { it.key as Any to it.value as Any }?.toMutableMap() ?: mutableMapOf()
            val current = (downloads[uid] as? Map<*, *>)?.entries?.associate { it.key as Any to it.value as Any }?.toMutableMap() ?: mutableMapOf()
            current["status"] = data["status"] ?: "DOWNLOADING"
            current["progress"] = data["progress"] ?: 0
            current["readyForPlayback"] = data["status"] == "READY_FOR_PLAYBACK"
            current["checksumVerified"] = data["checksumVerified"] ?: (data["status"] == "READY_FOR_PLAYBACK")
            current["locallyAvailable"] = data["locallyAvailable"] ?: (data["status"] == "READY_FOR_PLAYBACK")
            data["error"]?.let { current["error"] = it }
            downloads[uid] = current
            val eligible = (snap.get("eligibleUids") as? List<*>).orEmpty().map { it.toString() }
            val ready = eligible.all { key -> ((downloads[key] as? Map<*, *>)?.get("readyForPlayback") as? Boolean) == true }
            val values = mutableMapOf<String, Any>("downloads" to downloads)
            data["checksum"]?.let { values["checksum"] = it }
            if (ready && snap.getString("status") != "READY") {
                val currentSession = tx.get(session)
                if (existingQueueSize >= MAX_QUEUE_SIZE) throw JamRepositoryException("The shared queue is full.")
                val track = snap.get("track") as? Map<*, *> ?: throw JamRepositoryException("Track metadata is missing.")
                tx.set(session.collection(QUEUE).document(), mapOf("track" to track, "position" to existingQueueSize,
                    "addedByUid" to snap.getString("proposerUid").orEmpty(), "addedAt" to Timestamp.now(), "source" to "RECOMMENDATION"))
                values["status"] = "READY"
                tx.update(session, mapOf("queueRevision" to (currentSession.getLong("queueRevision") ?: 0L) + 1L,
                    "revision" to (currentSession.getLong("revision") ?: 0L) + 1L))
            }
            tx.update(ref, values)
            null
        }.await()
        return mapOf("ok" to true)
    }
    private suspend fun retryDownloadClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val ref = recommendationRef(data); val snap = ref.get().await()
        if (uid !in (snap.get("eligibleUids") as? List<*>).orEmpty()) throw JamRepositoryException("You are not part of this transfer.")
        updateDownloadClient(uid, data + mapOf("status" to "WAITING_TO_DOWNLOAD", "progress" to 0))
        if (uid != snap.getString("proposerUid")) ref.update("uploadStatus", "RETRY_REQUIRED").await()
        return mapOf("ok" to true)
    }

    private suspend fun playbackCommandClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val session = sessionRef(sessionId(data)); val snapshot = session.get().await()
        val playback = snapshot.get("playback") as? Map<*, *> ?: emptyMap<Any, Any>()
        val type = data["commandType"] as? String ?: ""
        val permissions = JamPermissions.from(snapshot.get("permissions"))
        val kind = runCatching { JamPermissionKind.valueOf(type) }.getOrNull() ?: throw JamRepositoryException("Playback command is invalid.")
        if (!permissions.allows(kind, uid, snapshot.getString("hostUid").orEmpty())) throw JamRepositoryException("You don't have permission to control playback.")
        val queue = session.collection(QUEUE).orderBy("position").get().await().documents
        val now = System.currentTimeMillis()
        val commandId = data["commandId"] as? String ?: java.util.UUID.randomUUID().toString()
        return firestore.runTransaction { tx ->
            val currentSession = tx.get(session)
            val state = currentSession.get("playback") as? Map<*, *> ?: emptyMap<Any, Any>()
            var sharedId = state["sharedTrackId"] as? String ?: ""
            var itemId = state["queueItemId"] as? String ?: ""
            var position = (state["positionMs"] as? Number)?.toLong() ?: 0L
            var playing = state["isPlaying"] as? Boolean ?: false
            var step = 0
            val speed = (state["playbackSpeed"] as? Number)?.toDouble() ?: 1.0
            val updatedAt = (state["updatedAt"] as? Timestamp)?.toDate()?.time ?: now
            val expectedPosition = if (playing) position + ((now - updatedAt).coerceAtLeast(0L) * speed).toLong() else position
            when (kind) {
                JamPermissionKind.PLAY_PAUSE -> {
                    if (playing) position = expectedPosition
                    playing = data["isPlaying"] as? Boolean ?: !playing
                }
                JamPermissionKind.SEEK -> {
                    val currentTrack = queue.firstOrNull { it.id == itemId }?.get("track") as? Map<*, *>
                    val duration = (currentTrack?.get("durationMs") as? Number)?.toLong() ?: Long.MAX_VALUE
                    position = ((data["positionMs"] as? Number)?.toLong() ?: 0L).coerceIn(0L, duration)
                }
                JamPermissionKind.SKIP_TRACK -> {
                    if (queue.isEmpty()) throw JamRepositoryException("Add a track to the shared queue first.")
                    val direction = if ((data["step"] as? Number)?.toInt() == -1) -1 else 1
                    val currentIndex = queue.indexOfFirst { it.id == itemId }
                    if (direction < 0 && playing && expectedPosition > 3_000L) {
                        position = 0L
                        step = -1
                    } else {
                        val targetIndex = if (currentIndex < 0) 0 else currentIndex + direction
                        when {
                            targetIndex < 0 -> {
                                val currentTrack = queue[currentIndex.coerceAtLeast(0)].get("track") as? Map<*, *> ?: emptyMap<Any, Any>()
                                sharedId = currentTrack["sharedTrackId"] as? String ?: ""
                                itemId = queue[currentIndex.coerceAtLeast(0)].id
                                position = 0L
                            }
                            targetIndex >= queue.size -> {
                                position = expectedPosition
                                playing = false
                            }
                            else -> {
                                val target = queue[targetIndex]
                                val track = target.get("track") as? Map<*, *> ?: emptyMap<Any, Any>()
                                sharedId = track["sharedTrackId"] as? String ?: ""
                                itemId = target.id
                                position = 0L
                                playing = true
                            }
                        }
                        step = direction
                    }
                }
                else -> Unit
            }
            val rev = ((state["revision"] as? Number)?.toLong() ?: 0L) + 1L
            val updated = mapOf("sharedTrackId" to sharedId, "queueItemId" to itemId, "isPlaying" to playing, "positionMs" to position,
                "playbackSpeed" to 1.0, "updatedAt" to Timestamp.now(), "revision" to rev, "commandId" to commandId,
                "commandType" to type, "actorUid" to uid, "step" to step)
            tx.update(session, mapOf("playback" to updated, "revision" to (currentSession.getLong("revision") ?: 0L) + 1L))
            mapOf("sharedTrackId" to sharedId, "queueItemId" to itemId, "isPlaying" to playing, "positionMs" to position,
                "playbackSpeed" to 1.0, "updatedAtMs" to now, "revision" to rev, "commandId" to commandId,
                "commandType" to type, "actorUid" to uid) as Map<String, Any?>
        }.await()
    }

    private suspend fun closeJamClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val session = sessionRef(sessionId(data)); val snap = session.get().await()
        if (snap.getString("hostUid") != uid) throw JamRepositoryException("Only the host can end this Jam.")
        session.update(mapOf("status" to "CLOSED", "revision" to (snap.getLong("revision") ?: 0L) + 1)).await()
        revokeInviteClient(uid, data)
        return mapOf("ok" to true)
    }
    private suspend fun revokeInviteClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val session = sessionRef(sessionId(data)); val snap = session.get().await()
        if (snap.getString("hostUid") != uid) throw JamRepositoryException("Only the host can revoke the invitation.")
        val hash = snap.getString("inviteCodeHash")
        if (hash != null) firestore.collection("jamInvitations").document(hash).update("active", false).await()
        return mapOf("ok" to true)
    }
    private suspend fun transferHostClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val session = sessionRef(sessionId(data)); val snap = session.get().await()
        if (snap.getString("hostUid") != uid) throw JamRepositoryException("Only the host can transfer host controls.")
        val target = data["participantUid"] as? String ?: throw JamRepositoryException("Choose a participant.")
        val batch = firestore.batch(); batch.update(session, "hostUid", target)
        batch.update(session.collection(PARTICIPANTS).document(uid), "role", "PARTICIPANT")
        batch.update(session.collection(PARTICIPANTS).document(target), "role", "HOST")
        batch.update(session, "revision", (snap.getLong("revision") ?: 0L) + 1L); batch.commit().await()
        return mapOf("ok" to true)
    }
    private suspend fun leaveJamClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val session = sessionRef(sessionId(data)); val snap = session.get().await()
        val people = session.collection(PARTICIPANTS).orderBy("joinedAt").get().await().documents.filter { it.id != uid }
        val batch = firestore.batch()
        batch.delete(session.collection(PARTICIPANTS).document(uid))
        if (people.isEmpty()) {
            batch.update(session, mapOf("status" to "CLOSED", "participantCount" to 0L,
                "revision" to (snap.getLong("revision") ?: 0L) + 1L))
            snap.getString("inviteCodeHash")?.let { batch.update(firestore.collection("jamInvitations").document(it), "active", false) }
        } else {
            val values = mutableMapOf<String, Any>("participantCount" to people.size,
                "revision" to (snap.getLong("revision") ?: 0L) + 1L)
            if (snap.getString("hostUid") == uid) {
                values["hostUid"] = people.first().id
                batch.update(people.first().reference, "role", "HOST")
            }
            batch.update(session, values)
        }
        batch.commit().await()
        return mapOf("ok" to true)
    }

    private fun recommendationRef(data: Map<String, Any>) = sessionRef(sessionId(data)).collection(RECOMMENDATIONS)
        .document(data["recommendationId"] as? String ?: throw JamRepositoryException("Recommendation is missing."))
    private fun sessionId(data: Map<String, Any>) = data["sessionId"] as? String ?: throw JamRepositoryException("Jam session is missing.")
    private fun safeDisplayName(data: Map<String, Any>) = (data["displayName"] as? String)?.trim()?.take(80)?.ifBlank { "Listener" } ?: "Listener"
    private fun safePhoto(data: Map<String, Any>) = (data["photoUrl"] as? String)?.takeIf { it.startsWith("https://") }?.take(500).orEmpty()
    private fun participantRecord(uid: String, displayName: String?, photo: String?, host: Boolean) = mapOf(
        "uid" to uid, "displayName" to displayName?.trim()?.take(80)?.ifBlank { "Listener" }.orEmpty().ifBlank { "Listener" },
        "photoUrl" to photo?.takeIf { it.startsWith("https://") }?.take(500).orEmpty(), "role" to if (host) "HOST" else "PARTICIPANT",
        "joinedAt" to Timestamp.now(), "lastSeenAt" to Timestamp.now())

    private fun generateInviteCode(): String {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val random = java.security.SecureRandom()
        return buildString(INVITE_CODE_LENGTH) { repeat(INVITE_CODE_LENGTH) { append(alphabet[random.nextInt(alphabet.length)]) } }
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
        private const val INVITE_CODE_LENGTH = 16
        private const val VOTE_WINDOW_MS = 30_000L
        private const val INVITE_LIFETIME_MS = 7 * 24 * 60 * 60_000L
        private const val SESSIONS = "jamSessions"
        private const val PARTICIPANTS = "participants"
        private const val QUEUE = "queue"
        private const val RECOMMENDATIONS = "recommendations"
        private const val MAX_RECOMMENDATIONS = 30
    }
}

typealias JamRepositoryException = JamRepository.JamRepositoryException
