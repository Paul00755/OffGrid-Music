package com.example.jam

import com.example.auth.AuthManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Transaction
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

    suspend fun createSession(
        displayName: String,
        photoUrl: String?,
        permissions: JamPermissions,
        maxParticipants: Int
    ): JamInvite {
        requireUid()
        val result = call("createJam", mapOf(
            "displayName" to displayName.take(80),
            "photoUrl" to (photoUrl ?: ""),
            "permissions" to permissions.toMap(),
            "maxParticipants" to maxParticipants.coerceIn(2, MAX_PARTICIPANTS)
        ))
        val id = result["sessionId"] as? String ?: throw JamRepositoryException("The Jam was created without a session ID.")
        val code = result["invitationCode"] as? String ?: throw JamRepositoryException("The Jam was created without an invitation code.")
        return JamInvite(id, code, buildInviteUrl(code))
    }

    suspend fun joinByCode(code: String, displayName: String, photoUrl: String?): String {
        requireUid()
        val result = call("joinJam", mapOf(
            "invitationCode" to extractJamInviteCode(code),
            "displayName" to displayName.take(80),
            "photoUrl" to (photoUrl ?: "")
        ))
        return result["sessionId"] as? String ?: throw JamRepositoryException("The invitation response was incomplete.")
    }

    suspend fun leave(sessionId: String): JamLeaveResult {
        requireUid()
        val result = call("leaveJam", mapOf("sessionId" to sessionId))
        return JamLeaveResult(
            sessionClosed = result["sessionClosed"] as? Boolean ?: false,
            message = result["message"] as? String
        )
    }

    suspend fun removeParticipant(sessionId: String, participantUid: String): JamRemoveResult {
        requireUid()
        val result = call("removeJamParticipant", mapOf(
            "sessionId" to sessionId,
            "participantUid" to participantUid
        ))
        return JamRemoveResult(
            sessionClosed = result["sessionClosed"] as? Boolean ?: false,
            message = result["message"] as? String
        )
    }

    suspend fun closeSession(sessionId: String) {
        requireUid()
        call("closeJam", mapOf("sessionId" to sessionId))
    }

    suspend fun startSession(sessionId: String) {
        requireUid()
        call("startJam", mapOf("sessionId" to sessionId))
    }

    suspend fun closeIfStartedWithOneListener(sessionId: String) {
        requireUid()
        call("closeSoloStartedJam", mapOf("sessionId" to sessionId))
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

    suspend fun requestFeature(sessionId: String, feature: JamFeature, displayName: String) {
        requireUid()
        call("requestJamFeature", mapOf(
            "sessionId" to sessionId,
            "featureId" to feature.documentId,
            "displayName" to displayName.take(80)
        ))
    }

    suspend fun resolveFeatureRequest(
        sessionId: String,
        feature: JamFeature,
        enableFeature: Boolean,
        blockRequester: Boolean
    ) {
        requireUid()
        call("resolveJamFeatureRequest", mapOf(
            "sessionId" to sessionId,
            "featureId" to feature.documentId,
            "enableFeature" to enableFeature,
            "blockRequester" to blockRequester
        ))
    }

    suspend fun addTrack(sessionId: String, track: JamTrack) {
        requireUid()
        call("mutateJamQueue", mapOf("sessionId" to sessionId, "operation" to "ADD", "track" to track.toMap()))
    }

    suspend fun playTrackNext(sessionId: String, track: JamTrack) {
        requireUid()
        call("mutateJamQueue", mapOf("sessionId" to sessionId, "operation" to "PLAY_NEXT", "track" to track.toMap()))
    }

    suspend fun playTrackNow(sessionId: String, track: JamTrack) {
        requireUid()
        call("mutateJamQueue", mapOf(
            "sessionId" to sessionId,
            "operation" to "ADD",
            "track" to track.toMap(),
            "playImmediately" to true
        ))
    }

    suspend fun addTracksBatch(sessionId: String, tracks: List<JamTrack>, shuffle: Boolean = false): Int {
        requireUid()
        val result = call("mutateJamQueue", mapOf(
            "sessionId" to sessionId,
            "operation" to "ADD_BATCH",
            "tracks" to tracks.map { it.toMap() },
            "shuffle" to shuffle
        ))
        return (result["addedCount"] as? Number)?.toInt() ?: 0
    }

    suspend fun removeTrack(sessionId: String, queueItemId: String) {
        requireUid()
        call("mutateJamQueue", mapOf("sessionId" to sessionId, "operation" to "REMOVE", "queueItemId" to queueItemId))
    }

    suspend fun reorderQueue(sessionId: String, itemIds: List<String>) {
        requireUid()
        call("mutateJamQueue", mapOf("sessionId" to sessionId, "operation" to "REORDER", "queueItemIds" to itemIds))
    }

    suspend fun removeDuplicateTracks(sessionId: String): Int {
        requireUid()
        val result = call("mutateJamQueue", mapOf("sessionId" to sessionId, "operation" to "REMOVE_DUPLICATES"))
        return (result["removedCount"] as? Number)?.toInt() ?: 0
    }

    suspend fun submitPlaybackCommand(
        sessionId: String,
        type: JamPermissionKind,
        isPlaying: Boolean? = null,
        positionMs: Long? = null,
        step: Int = 1,
        targetQueueItemId: String? = null,
        targetSharedTrackId: String? = null,
        hasActiveItem: Boolean = false,
        commandId: String = java.util.UUID.randomUUID().toString(),
        isDirectSelect: Boolean = false
    ): JamPlaybackState {
        requireUid()
        val payload = mutableMapOf<String, Any>(
            "sessionId" to sessionId,
            "commandType" to type.name,
            "commandId" to commandId,
            "hasActiveItem" to hasActiveItem
        )
        if (isPlaying != null) payload["isPlaying"] = isPlaying
        if (positionMs != null) payload["positionMs"] = positionMs.coerceAtLeast(0L)
        if (type == JamPermissionKind.SKIP_TRACK) payload["step"] = if (step < 0) -1 else 1
        if (!targetQueueItemId.isNullOrBlank()) payload["queueItemId"] = targetQueueItemId
        if (!targetSharedTrackId.isNullOrBlank() && !targetQueueItemId.isNullOrBlank()) {
            payload["sharedTrackId"] = targetSharedTrackId
            payload["resolvedByController"] = true
            payload["isDirectSelect"] = isDirectSelect
        }
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

    fun observeFeatureRequests(sessionId: String): Flow<List<JamFeatureRequest>> = callbackFlow {
        val registration = sessionRef(sessionId).collection(FEATURE_REQUESTS)
            .addSnapshotListener { snapshot, error ->
                if (error != null) close(error)
                else trySend(snapshot?.documents.orEmpty().mapNotNull(::featureRequestFrom))
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

    /** Publishes opaque match hashes only; song names and audio bytes stay on each device. */
    suspend fun publishSharedLibraryIndex(sessionId: String, trackCount: Int, rawMatchKeys: Collection<String>) {
        val uid = requireUidValue()
        val index = sessionRef(sessionId).collection(LIBRARY_INDEX)
        val matchKeys = rawMatchKeys.asSequence()
            .filter { it.length in 20..80 }
            .distinct()
            .take(MAX_LIBRARY_MATCH_KEYS)
            .toList()
        val chunks = matchKeys.chunked(LIBRARY_MATCH_KEYS_PER_CHUNK)
        val wanted = LinkedHashMap<String, Map<String, Any>>()
        chunks.forEachIndexed { chunkIndex, keys ->
            wanted["${uid}_chunk_$chunkIndex"] = mapOf(
                "ownerUid" to uid,
                "kind" to "chunk",
                "chunkIndex" to chunkIndex,
                "schemaVersion" to LIBRARY_INDEX_SCHEMA_VERSION,
                "keys" to keys
            )
        }
        wanted["${uid}_manifest"] = mapOf(
            "ownerUid" to uid,
            "kind" to "manifest",
            "schemaVersion" to LIBRARY_INDEX_SCHEMA_VERSION,
            "chunkCount" to chunks.size,
            "trackCount" to trackCount.coerceAtLeast(0),
            "ready" to true
        )

        val existing = index.whereEqualTo("ownerUid", uid).get().await().documents
        val batch = firestore.batch()
        var hasChanges = false
        existing.forEach { snapshot ->
            if (snapshot.id !in wanted) {
                batch.delete(snapshot.reference)
                hasChanges = true
            }
        }
        wanted.forEach { (documentId, data) ->
            val current = existing.firstOrNull { it.id == documentId }
            val unchanged = current != null && if (data["kind"] == "chunk") {
                current.get("keys") == data["keys"] &&
                    current.getLong("chunkIndex") == (data["chunkIndex"] as Int).toLong() &&
                    current.getLong("schemaVersion") == LIBRARY_INDEX_SCHEMA_VERSION.toLong()
            } else {
                current.getBoolean("ready") == true &&
                    current.getLong("chunkCount") == chunks.size.toLong() &&
                    current.getLong("trackCount") == (data["trackCount"] as Int).toLong() &&
                    current.getLong("schemaVersion") == LIBRARY_INDEX_SCHEMA_VERSION.toLong()
            }
            if (!unchanged) {
                batch.set(index.document(documentId), data)
                hasChanges = true
            }
        }
        if (hasChanges) batch.commit().await()
    }

    fun observeSharedLibraryIndex(sessionId: String): Flow<JamLibraryIndexSnapshot> = callbackFlow {
        val registration = sessionRef(sessionId).collection(LIBRARY_INDEX)
            .addSnapshotListener { snapshot, error ->
                if (error != null) close(error)
                else {
                    val grouped = snapshot?.documents.orEmpty().groupBy { it.getString("ownerUid").orEmpty() }
                    val keysByUid = mutableMapOf<String, Set<String>>()
                    val readyUids = mutableSetOf<String>()
                    grouped.forEach { (uid, userDocs) ->
                        if (uid.isBlank()) return@forEach
                        val manifest = userDocs.firstOrNull { it.getString("kind") == "manifest" } ?: return@forEach
                        if (manifest.getLong("schemaVersion") != LIBRARY_INDEX_SCHEMA_VERSION.toLong() || manifest.getBoolean("ready") != true) return@forEach
                        val expectedChunks = (manifest.getLong("chunkCount") ?: -1L).toInt()
                        val chunks = userDocs.filter { it.getString("kind") == "chunk" }
                        if (expectedChunks < 0 || chunks.size != expectedChunks) return@forEach
                        readyUids += uid
                        keysByUid[uid] = chunks.flatMap { chunk ->
                            (chunk.get("keys") as? List<*>)?.filterIsInstance<String>().orEmpty()
                        }.toSet()
                    }
                    trySend(JamLibraryIndexSnapshot(keysByUid = keysByUid, readyUids = readyUids))
                }
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
            "requestJamFeature" -> requestJamFeatureClient(uid, data)
            "resolveJamFeatureRequest" -> resolveJamFeatureRequestClient(uid, data)
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
            "startJam" -> startJamClient(uid, data)
            "revokeJamInvite" -> revokeInviteClient(uid, data)
            "transferJamHost" -> transferHostClient(uid, data)
            "leaveJam" -> leaveJamClient(uid, data)
            "removeJamParticipant" -> removeJamParticipantClient(uid, data)
        "closeSoloStartedJam" -> closeSoloStartedJamClient(uid, data)
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
        val maxParticipants = ((data["maxParticipants"] as? Number)?.toInt() ?: MAX_PARTICIPANTS)
            .coerceIn(2, MAX_PARTICIPANTS)
        val playback = mapOf("sharedTrackId" to "", "queueItemId" to "", "isPlaying" to false, "positionMs" to 0L,
            "playbackSpeed" to 1.0, "updatedAt" to Timestamp.now(), "revision" to 0L, "commandId" to "",
            "commandType" to "CREATE", "actorUid" to uid, "step" to 0)
        firestore.runTransaction { tx ->
            tx.set(session, mapOf("hostUid" to uid, "status" to "ACTIVE", "participantCount" to 1,
                "maxParticipants" to maxParticipants, "started" to false, "formerHostUids" to emptyList<String>(),
                "kickedUids" to emptyList<String>(), "inviteCodeHash" to inviteHash, "createdAt" to Timestamp.now(),
                "permissions" to data["permissions"], "playback" to playback, "revision" to 1L, "queueRevision" to 0L))
            tx.set(member, participantRecord(uid, data["displayName"] as? String, data["photoUrl"] as? String, true) + mapOf("invitationHash" to inviteHash))
            tx.set(invite, mapOf("sessionId" to id, "active" to true, "createdAt" to Timestamp.now(),
                "expiresAt" to Timestamp(java.util.Date(System.currentTimeMillis() + INVITE_LIFETIME_MS))))
            null
        }.await()
        return mapOf("sessionId" to id, "invitationCode" to code)
    }

    private suspend fun joinJamClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val code = extractJamInviteCode((data["invitationCode"] as? String).orEmpty())
        if (code.length != INVITE_CODE_LENGTH) throw JamRepositoryException("Invitation code is invalid.")
        val invitation = firestore.collection("jamInvitations").document(invitationDigest(code))
        val info = invitation.get().await()
        val id = info.getString("sessionId") ?: throw JamRepositoryException("This invitation is invalid or has been revoked.")
        val session = sessionRef(id)
        val member = session.collection(PARTICIPANTS).document(uid)
        val alreadyMember = runCatching { member.get().await().exists() }.getOrDefault(false)
        if (alreadyMember) return mapOf("sessionId" to id)
        firestore.runTransaction { tx ->
            val inviteSnapshot = tx.get(invitation)
            val sessionSnapshot = tx.get(session)
            if (inviteSnapshot.getBoolean("active") != true || (inviteSnapshot.getTimestamp("expiresAt")?.toDate()?.time ?: 0L) <= System.currentTimeMillis()) {
                throw JamRepositoryException("This invitation has expired or was revoked.")
            }
            if (sessionSnapshot.getString("status") != "ACTIVE") throw JamRepositoryException("This Jam has ended.")
            val kickedUids = (sessionSnapshot.get("kickedUids") as? List<*>)?.filterIsInstance<String>().orEmpty()
            if (uid in kickedUids) {
                throw JamRepositoryException("The host removed you from this Jam. You cannot rejoin this Jam room.")
            }
            val formerHosts = (sessionSnapshot.get("formerHostUids") as? List<*>)?.filterIsInstance<String>().orEmpty()
            if (uid in formerHosts) {
                throw JamRepositoryException("You left this Jam as host and can’t rejoin it. Ask the current host to create a new Jam.")
            }
            if (sessionSnapshot.getBoolean("started") == true) {
                throw JamRepositoryException("This Jam has already started. Ask your friend to start a new session and wait for everyone to join before listening.")
            }
            val count = sessionSnapshot.getLong("participantCount") ?: 0L
            val maxParticipants = (sessionSnapshot.getLong("maxParticipants") ?: MAX_PARTICIPANTS.toLong())
                .toInt().coerceIn(2, MAX_PARTICIPANTS)
            if (count >= maxParticipants) throw JamRepositoryException("This Jam is full (maximum $maxParticipants people).")
            tx.set(member, participantRecord(uid, data["displayName"] as? String, data["photoUrl"] as? String, false) + mapOf("invitationHash" to invitation.id))
            tx.update(session, mapOf("participantCount" to count + 1, "revision" to (sessionSnapshot.getLong("revision") ?: 0L) + 1))
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
        val session = sessionRef(sessionId(data))
        val nextPermissions = JamPermissions.from(data["permissions"])
        val requestRefs = JamFeature.values().associateWith { feature -> featureRequestRef(session.id, feature) }
        val recommendationRefs = session.collection(RECOMMENDATIONS)
            .limit(MAX_RECOMMENDATIONS.toLong())
            .get().await().documents.map { it.reference }
        return firestore.runTransaction { tx ->
            val snapshot = tx.get(session)
            val requestSnapshots = requestRefs.mapValues { (_, ref) -> tx.get(ref) }
            val pendingRecommendations = recommendationRefs.map(tx::get)
                .filter { it.getString("status") == "VOTING" }
            if (snapshot.getString("hostUid") != uid) throw JamRepositoryException("Only the host can change Jam permissions.")
            val currentPermissions = JamPermissions.from(snapshot.get("permissions"))
            tx.update(session, mapOf(
                "permissions" to nextPermissions.toMap(),
                "revision" to (snapshot.getLong("revision") ?: 0L) + 1L
            ))
            JamFeature.values().forEach { feature ->
                if (feature.isEnabled(currentPermissions) != feature.isEnabled(nextPermissions)) {
                    resolveFeatureRequestDoc(tx, requestRefs.getValue(feature), requestSnapshots.getValue(feature), blockRequester = false)
                }
            }
            pendingRecommendations.forEach { recommendation ->
                val type = recommendation.getString("recommendationType")
                val featureEnabled = when (type) {
                    JamRecommendationType.LOCAL_SONG.name -> nextPermissions.allowLocalSongSharing
                    JamRecommendationType.SHARED_LIBRARY.name -> nextPermissions.allowSharedLibraryRecommendations
                    else -> true
                }
                if (!featureEnabled) tx.update(recommendation.reference, "status", "CANCELLED_PERMISSION_DISABLED")
            }
            mapOf("ok" to true)
        }.await()
    }

    private suspend fun requestJamFeatureClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val id = sessionId(data)
        val feature = featureFromId(data["featureId"] as? String)
        val session = sessionRef(id)
        val request = featureRequestRef(id, feature)
        val member = session.collection(PARTICIPANTS).document(uid)
        val displayName = safeDisplayName(data)
        return try {
            firestore.runTransaction { tx ->
                val sessionSnapshot = tx.get(session)
                val memberSnapshot = tx.get(member)
                val requestSnapshot = tx.get(request)
                if (!memberSnapshot.exists()) throw JamRepositoryException("Join the Jam before requesting a feature.")
                if (sessionSnapshot.getString("status") != "ACTIVE") throw JamRepositoryException("This Jam has ended.")
                if (sessionSnapshot.getString("hostUid") == uid) throw JamRepositoryException("The host can enable this feature in Permissions.")
                if (feature.isEnabled(JamPermissions.from(sessionSnapshot.get("permissions")))) {
                    throw JamRepositoryException("This feature is already enabled for the Jam.")
                }
                val blockedUids = (requestSnapshot.get("blockedUids") as? List<*>)?.filterIsInstance<String>().orEmpty()
                if (uid in blockedUids) throw JamRepositoryException("The host has blocked your requests for ${feature.title} in this Jam.")
                val values = mapOf(
                    "active" to true,
                    "requesterUid" to uid,
                    "requesterName" to displayName,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                if (requestSnapshot.exists()) tx.update(request, values) else tx.set(request, values + ("blockedUids" to emptyList<String>()))
                mapOf("ok" to true)
            }.await()
        } catch (error: FirebaseFirestoreException) {
            val requestSnapshot = runCatching { request.get().await() }.getOrNull()
            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED && requestSnapshot?.getBoolean("active") == true) {
                val requester = requestSnapshot.getString("requesterName")?.takeIf(String::isNotBlank) ?: "Someone"
                throw JamRepositoryException("$requester already requested ${feature.title}. Please wait for that request to expire.")
            }
            throw error
        }
    }

    private suspend fun resolveJamFeatureRequestClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val id = sessionId(data)
        val feature = featureFromId(data["featureId"] as? String)
        val session = sessionRef(id)
        val request = featureRequestRef(id, feature)
        val enableFeature = data["enableFeature"] as? Boolean ?: false
        val blockRequester = data["blockRequester"] as? Boolean ?: false
        return firestore.runTransaction { tx ->
            val sessionSnapshot = tx.get(session)
            val requestSnapshot = tx.get(request)
            if (sessionSnapshot.getString("hostUid") != uid) throw JamRepositoryException("Only the host can respond to feature requests.")
            resolveFeatureRequestDoc(tx, request, requestSnapshot, blockRequester)
            val permissions = JamPermissions.from(sessionSnapshot.get("permissions"))
            val nextPermissions = when (feature) {
                JamFeature.SHARED_RECOMMENDATIONS -> permissions.copy(allowSharedLibraryRecommendations = enableFeature || permissions.allowSharedLibraryRecommendations)
                JamFeature.LOCAL_SONG_SHARING -> permissions.copy(allowLocalSongSharing = enableFeature || permissions.allowLocalSongSharing)
            }
            if (enableFeature && nextPermissions != permissions) {
                tx.update(session, mapOf(
                    "permissions" to nextPermissions.toMap(),
                    "revision" to (sessionSnapshot.getLong("revision") ?: 0L) + 1L
                ))
            }
            mapOf("ok" to true, "enabled" to enableFeature)
        }.await()
    }

    private suspend fun mutateQueueClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val id = sessionId(data); val session = sessionRef(id); val queue = session.collection(QUEUE)
        val operation = data["operation"] as? String ?: ""
        val documents = queue.orderBy("position").limit(MAX_QUEUE_SIZE.toLong()).get().await().documents
        val current = session.get().await()
        val hostUid = current.getString("hostUid").orEmpty()
        val permissions = JamPermissions.from(current.get("permissions"))
        val kind = when (operation) {
            "ADD", "PLAY_NEXT", "ADD_BATCH" -> JamPermissionKind.ADD_TO_QUEUE
            else -> JamPermissionKind.MANAGE_QUEUE
        }
        if (!permissions.allows(kind, uid, hostUid)) throw JamRepositoryException("You don't have permission to change the queue.")
        val currentPlayback = (current.get("playback") as? Map<*, *>)?.entries
            ?.associate { it.key.toString() to it.value }
            ?.toMutableMap()
            ?: mutableMapOf()
        val currentQueueItemId = currentPlayback["queueItemId"] as? String ?: ""
        val activeItemExists = currentQueueItemId.isNotBlank() && documents.any { it.id == currentQueueItemId }
        val roomStarted = current.getBoolean("started") == true
        var updatedPlayback: Map<String, Any?>? = null
        var addedCount = 0
        var removedCount = 0

        fun trackDedupKey(trackMap: Map<*, *>?): String {
            if (trackMap == null) return ""
            val sharedId = (trackMap["sharedTrackId"] as? String)?.trim().orEmpty()
            if (sharedId.isNotBlank()) return "id:$sharedId"
            val t = (trackMap["title"] as? String)?.trim()?.lowercase().orEmpty()
            val a = (trackMap["artist"] as? String)?.trim()?.lowercase().orEmpty()
            return if (t.isNotBlank()) "meta:$t|$a" else ""
        }

        when (operation) {
            "ADD" -> {
                val track = data["track"] as? Map<*, *> ?: throw JamRepositoryException("Track data is invalid.")
                val playImmediately = data["playImmediately"] as? Boolean ?: false
                if (playImmediately && (!permissions.allows(JamPermissionKind.PLAY_PAUSE, uid, hostUid) || !permissions.allows(JamPermissionKind.SKIP_TRACK, uid, hostUid))) {
                    throw JamRepositoryException("You don't have permission to play tracks immediately.")
                }
                val trackSharedId = track["sharedTrackId"] as? String ?: ""
                val candidateKey = trackDedupKey(track)
                val existingDoc = if (candidateKey.isNotBlank()) {
                    documents.firstOrNull { trackDedupKey(it.get("track") as? Map<*, *>) == candidateKey }
                } else null
                val targetDocId: String
                if (existingDoc != null) {
                    targetDocId = existingDoc.id
                } else {
                    if (documents.size >= MAX_QUEUE_SIZE) throw JamRepositoryException("The Jam queue is full.")
                    val doc = queue.document()
                    doc.set(mapOf("track" to track, "position" to documents.size, "addedByUid" to uid, "addedAt" to Timestamp.now())).await()
                    targetDocId = doc.id
                    addedCount = 1
                }
                if (playImmediately && roomStarted && permissions.allows(JamPermissionKind.PLAY_PAUSE, uid, hostUid) && permissions.allows(JamPermissionKind.SKIP_TRACK, uid, hostUid)) {
                    val rev = ((currentPlayback["revision"] as? Number)?.toLong() ?: 0L) + 1L
                    updatedPlayback = mapOf(
                        "sharedTrackId" to trackSharedId,
                        "queueItemId" to targetDocId,
                        "isPlaying" to true,
                        "positionMs" to 0L,
                        "playbackSpeed" to 1.0,
                        "updatedAt" to Timestamp.now(),
                        "revision" to rev,
                        "commandId" to java.util.UUID.randomUUID().toString(),
                        "commandType" to JamPermissionKind.SKIP_TRACK.name,
                        "actorUid" to uid,
                        "step" to 1
                    )
                } else if (!activeItemExists && roomStarted) {
                    val rev = ((currentPlayback["revision"] as? Number)?.toLong() ?: 0L) + 1L
                    updatedPlayback = currentPlayback + mapOf(
                        "sharedTrackId" to trackSharedId,
                        "queueItemId" to targetDocId,
                        "positionMs" to 0L,
                        "updatedAt" to Timestamp.now(),
                        "revision" to rev
                    )
                }
            }
            "PLAY_NEXT" -> {
                val track = data["track"] as? Map<*, *> ?: throw JamRepositoryException("Track data is invalid.")
                val trackSharedId = track["sharedTrackId"] as? String ?: ""
                val candidateKey = trackDedupKey(track)
                val existingDoc = if (candidateKey.isNotBlank()) {
                    documents.firstOrNull { trackDedupKey(it.get("track") as? Map<*, *>) == candidateKey }
                } else null
                if (existingDoc != null && existingDoc.id == currentQueueItemId) {
                    // Already playing right now; do not add a duplicate
                    addedCount = 0
                } else if (existingDoc != null) {
                    val remaining = documents.filterNot { it.id == existingDoc.id }.toMutableList()
                    val currentIdx = remaining.indexOfFirst { it.id == currentQueueItemId }
                    val insertPosition = if (remaining.isEmpty()) 0 else if (currentIdx >= 0) currentIdx + 1 else 1.coerceAtMost(remaining.size)
                    remaining.add(insertPosition, existingDoc)
                    val batch = firestore.batch()
                    remaining.forEachIndexed { idx, docSnap ->
                        batch.update(docSnap.reference, "position", idx)
                    }
                    batch.commit().await()
                    addedCount = 0
                } else {
                    if (documents.size >= MAX_QUEUE_SIZE) throw JamRepositoryException("The Jam queue is full.")
                    val currentIdx = documents.indexOfFirst { it.id == currentQueueItemId }
                    val insertPosition = if (documents.isEmpty()) 0 else if (currentIdx >= 0) currentIdx + 1 else 1.coerceAtMost(documents.size)
                    val batch = firestore.batch()
                    val newDoc = queue.document()
                    batch.set(newDoc, mapOf("track" to track, "position" to insertPosition, "addedByUid" to uid, "addedAt" to Timestamp.now()))
                    for (i in insertPosition until documents.size) {
                        batch.update(documents[i].reference, "position", i + 1)
                    }
                    batch.commit().await()
                    addedCount = 1
                    if (!activeItemExists && roomStarted) {
                        val rev = ((currentPlayback["revision"] as? Number)?.toLong() ?: 0L) + 1L
                        updatedPlayback = currentPlayback + mapOf(
                            "sharedTrackId" to trackSharedId,
                            "queueItemId" to newDoc.id,
                            "positionMs" to 0L,
                            "updatedAt" to Timestamp.now(),
                            "revision" to rev
                        )
                    }
                }
            }
            "ADD_BATCH" -> {
                val rawTracks = (data["tracks"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
                if (rawTracks.isEmpty()) throw JamRepositoryException("No matching songs to add.")
                val shuffle = data["shuffle"] as? Boolean ?: false
                val existingKeys = documents.mapNotNull {
                    trackDedupKey(it.get("track") as? Map<*, *>).takeIf(String::isNotBlank)
                }.toMutableSet()
                val remainingCapacity = (MAX_QUEUE_SIZE - documents.size).coerceAtLeast(0)
                val candidateTracks = rawTracks
                    .filter { trackMap ->
                        val key = trackDedupKey(trackMap)
                        key.isNotBlank() && existingKeys.add(key)
                    }
                    .let { if (shuffle) it.shuffled() else it }
                    .take(remainingCapacity)
                if (candidateTracks.isNotEmpty()) {
                    val batch = firestore.batch()
                    var firstDocId: String? = null
                    var firstSharedId: String? = null
                    candidateTracks.forEachIndexed { offset, track ->
                        val doc = queue.document()
                        if (offset == 0) {
                            firstDocId = doc.id
                            firstSharedId = track["sharedTrackId"] as? String ?: ""
                        }
                        batch.set(
                            doc,
                            mapOf(
                                "track" to track,
                                "position" to (documents.size + offset),
                                "addedByUid" to uid,
                                "addedAt" to Timestamp.now()
                            )
                        )
                    }
                    batch.commit().await()
                    addedCount = candidateTracks.size
                    if (!activeItemExists && roomStarted && firstDocId != null) {
                        val rev = ((currentPlayback["revision"] as? Number)?.toLong() ?: 0L) + 1L
                        updatedPlayback = currentPlayback + mapOf(
                            "sharedTrackId" to firstSharedId.orEmpty(),
                            "queueItemId" to firstDocId!!,
                            "positionMs" to 0L,
                            "updatedAt" to Timestamp.now(),
                            "revision" to rev
                        )
                    }
                } else if (shuffle && documents.size > 1 && permissions.allows(JamPermissionKind.MANAGE_QUEUE, uid, hostUid)) {
                    val currentActive = documents.firstOrNull { it.id == currentQueueItemId }
                    val others = documents.filterNot { it.id == currentQueueItemId }.shuffled()
                    val reordered = if (currentActive != null) listOf(currentActive) + others else others
                    val batch = firestore.batch()
                    reordered.forEachIndexed { index, snap -> batch.update(snap.reference, "position", index) }
                    batch.commit().await()
                }
            }
            "REMOVE" -> {
                val removedId = data["queueItemId"] as? String ?: ""
                queue.document(removedId).delete().await()
                if (removedId == currentQueueItemId && roomStarted) {
                    val remaining = documents.filterNot { it.id == removedId }
                    val nextDoc = remaining.firstOrNull()
                    val nextTrack = nextDoc?.get("track") as? Map<*, *>
                    val rev = ((currentPlayback["revision"] as? Number)?.toLong() ?: 0L) + 1L
                    updatedPlayback = currentPlayback + mapOf(
                        "sharedTrackId" to (nextTrack?.get("sharedTrackId") as? String ?: ""),
                        "queueItemId" to (nextDoc?.id ?: ""),
                        "isPlaying" to if (nextDoc == null) false else (currentPlayback["isPlaying"] as? Boolean ?: false),
                        "positionMs" to 0L,
                        "updatedAt" to Timestamp.now(),
                        "revision" to rev
                    )
                }
            }
            "REMOVE_DUPLICATES" -> {
                val seenKeys = mutableSetOf<String>()
                val keptDocs = mutableListOf<DocumentSnapshot>()
                val duplicateDocs = mutableListOf<DocumentSnapshot>()

                // Ensure the currently playing queue item's key is claimed by that exact item if present
                val activeDoc = documents.firstOrNull { it.id == currentQueueItemId }
                val activeKey = activeDoc?.let { trackDedupKey(it.get("track") as? Map<*, *>) }.orEmpty()

                documents.forEach { docSnap ->
                    val key = trackDedupKey(docSnap.get("track") as? Map<*, *>)
                    if (key.isBlank()) {
                        keptDocs += docSnap
                    } else if (activeDoc != null && key == activeKey) {
                        if (docSnap.id == activeDoc.id) {
                            seenKeys += key
                            keptDocs += docSnap
                        } else {
                            duplicateDocs += docSnap
                        }
                    } else if (seenKeys.add(key)) {
                        keptDocs += docSnap
                    } else {
                        duplicateDocs += docSnap
                    }
                }

                if (duplicateDocs.isNotEmpty()) {
                    val batch = firestore.batch()
                    duplicateDocs.forEach { dup -> batch.delete(dup.reference) }
                    keptDocs.forEachIndexed { index, kept -> batch.update(kept.reference, "position", index) }
                    batch.commit().await()
                    removedCount = duplicateDocs.size
                }
            }
            "REORDER" -> {
                val ids = (data["queueItemIds"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                if (ids.toSet() != documents.map { it.id }.toSet() || ids.size != documents.size) throw JamRepositoryException("Queue changed. Try again.")
                val batch = firestore.batch()
                ids.forEachIndexed { index, itemId -> batch.update(queue.document(itemId), "position", index) }
                batch.commit().await()
            }
            else -> throw JamRepositoryException("Queue operation is invalid.")
        }
        val sessionUpdates = mutableMapOf<String, Any>(
            "queueRevision" to (current.getLong("queueRevision") ?: 0L) + 1,
            "revision" to (current.getLong("revision") ?: 0L) + 1
        )
        if (updatedPlayback != null) {
            sessionUpdates["playback"] = updatedPlayback
        }
        session.update(sessionUpdates).await()
        return mapOf("ok" to true, "addedCount" to addedCount, "removedCount" to removedCount)
    }

    private suspend fun createRecommendationClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val id = sessionId(data); val session = sessionRef(id); val snapshot = session.get().await()
        val type = data["recommendationType"] as? String ?: ""
        val permissions = JamPermissions.from(snapshot.get("permissions"))
        val isHost = snapshot.getString("hostUid") == uid
        if (type == "LOCAL_SONG" && !permissions.allowLocalSongSharing && !isHost) throw JamRepositoryException("Local song sharing is disabled.")
        if (type == "SHARED_LIBRARY" && !permissions.allowSharedLibraryRecommendations && !isHost) throw JamRepositoryException("Recommendations are disabled.")
        val members = session.collection(PARTICIPANTS).get().await().documents
        val eligible = members.map { it.id }
        val required = if (type == "LOCAL_SONG") eligible.size else eligible.size / 2 + 1
        if (uid !in eligible || eligible.isEmpty()) throw JamRepositoryException("Join the Jam before recommending a song.")
        val track = data["track"] as? Map<*, *> ?: throw JamRepositoryException("Track data is invalid.")
        val trackSharedId = (track["sharedTrackId"] as? String).orEmpty()
        val existingQueueDocs = session.collection(QUEUE).orderBy("position").limit(MAX_QUEUE_SIZE.toLong()).get().await().documents
        val alreadyInQueue = trackSharedId.isNotBlank() && existingQueueDocs.any {
            ((it.get("track") as? Map<*, *>)?.get("sharedTrackId") as? String) == trackSharedId
        }
        if (alreadyInQueue) {
            throw JamRepositoryException("This song is already in the shared queue.")
        }
        val initialApprovalCount = 1
        val initialStatus = if (initialApprovalCount >= required) {
            if (type == "LOCAL_SONG") "APPROVED_PENDING_UPLOAD" else "READY"
        } else "VOTING"
        val ref = session.collection(RECOMMENDATIONS).document()
        val proposerName = members.firstOrNull { it.id == uid }?.getString("displayName")
            ?.takeIf(String::isNotBlank) ?: authManager.currentUser?.displayName?.takeIf(String::isNotBlank) ?: "Listener"
        ref.set(mapOf("recommendationType" to type, "proposerUid" to uid,
            "proposerName" to proposerName, "track" to track,
            "eligibleUids" to eligible, "deadlineAt" to Timestamp(java.util.Date(System.currentTimeMillis() + VOTE_WINDOW_MS)),
            "status" to initialStatus, "requiredApprovalCount" to required, "approvalCount" to initialApprovalCount, "rejectionCount" to 0,
            "votes" to eligible.associateWith { if (it == uid) "ACCEPT" else "PENDING" },
            "downloads" to if (type == "LOCAL_SONG") mapOf(uid to mapOf("status" to "READY_FOR_PLAYBACK", "progress" to 100,
                "checksumVerified" to true, "locallyAvailable" to true, "readyForPlayback" to true)) else emptyMap<String, Any>(),
            "createdAt" to Timestamp.now(), "uploadStatus" to "WAITING_FOR_PEERS")).await()
        return mapOf("recommendationId" to ref.id)
    }

    private suspend fun voteRecommendationClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val session = sessionRef(sessionId(data)); val ref = session.collection(RECOMMENDATIONS).document(data["recommendationId"] as? String ?: "")
        val existingQueueDocs = session.collection(QUEUE).orderBy("position").limit(MAX_QUEUE_SIZE.toLong()).get().await().documents
        val existingQueueSize = existingQueueDocs.size
        firestore.runTransaction { tx ->
            val snap = tx.get(ref)
            val sessionSnapshot = tx.get(session)
            val votes = (snap.get("votes") as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }?.toMutableMap() ?: mutableMapOf()
            val proposerUid = snap.getString("proposerUid").orEmpty()
            if (uid == proposerUid) throw JamRepositoryException("Your submission is already counted as approved.")
            if (snap.getString("status") != "VOTING" || uid !in (snap.get("eligibleUids") as? List<*>).orEmpty()) throw JamRepositoryException("Voting is closed for this recommendation.")
            if (proposerUid.isNotBlank() && votes[proposerUid] != "ACCEPT") votes[proposerUid] = "ACCEPT"
            if (votes[uid] != "PENDING") throw JamRepositoryException("You already voted on this song.")
            val vote = data["vote"] as? String ?: ""
            if (vote !in setOf("ACCEPT", "REJECT")) throw JamRepositoryException("Vote is invalid.")
            val permissions = JamPermissions.from(sessionSnapshot.get("permissions"))
            val recommendationsEnabled = when (snap.getString("recommendationType")) {
                JamRecommendationType.LOCAL_SONG.name -> permissions.allowLocalSongSharing
                else -> permissions.allowSharedLibraryRecommendations
            }
            if (!recommendationsEnabled) throw JamRepositoryException("The host disabled voting for this feature.")
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
                val track = snap.get("track") as? Map<*, *> ?: throw JamRepositoryException("Track metadata is missing.")
                val trackSharedId = (track["sharedTrackId"] as? String).orEmpty()
                val trackTitle = (track["title"] as? String)?.trim()?.lowercase().orEmpty()
                val trackArtist = (track["artist"] as? String)?.trim()?.lowercase().orEmpty()
                val existingMatch = existingQueueDocs.firstOrNull { doc ->
                    val qTrack = doc.get("track") as? Map<*, *>
                    val qId = (qTrack?.get("sharedTrackId") as? String).orEmpty()
                    val qTitle = (qTrack?.get("title") as? String)?.trim()?.lowercase().orEmpty()
                    val qArtist = (qTrack?.get("artist") as? String)?.trim()?.lowercase().orEmpty()
                    (trackSharedId.isNotBlank() && qId == trackSharedId) ||
                        (trackTitle.isNotBlank() && qTitle == trackTitle && qArtist == trackArtist)
                }
                if (existingMatch == null) {
                    if (existingQueueSize >= MAX_QUEUE_SIZE) throw JamRepositoryException("The shared queue is full.")
                    val newQueueDoc = session.collection(QUEUE).document()
                    tx.set(newQueueDoc, mapOf("track" to track, "position" to existingQueueSize,
                        "addedByUid" to snap.getString("proposerUid").orEmpty(), "addedAt" to Timestamp.now(), "source" to "RECOMMENDATION"))
                    val sessionUpdates = mutableMapOf<String, Any>(
                        "queueRevision" to (sessionSnapshot.getLong("queueRevision") ?: 0L) + 1L,
                        "revision" to (sessionSnapshot.getLong("revision") ?: 0L) + 1L
                    )
                    val currentPlayback = (sessionSnapshot.get("playback") as? Map<*, *>)?.entries
                        ?.associate { it.key.toString() to it.value }
                        ?.toMutableMap()
                    if (sessionSnapshot.getBoolean("started") == true &&
                        currentPlayback != null &&
                        (currentPlayback["queueItemId"] as? String).isNullOrBlank()) {
                        val rev = ((currentPlayback["revision"] as? Number)?.toLong() ?: 0L) + 1L
                        sessionUpdates["playback"] = currentPlayback + mapOf(
                            "sharedTrackId" to trackSharedId,
                            "queueItemId" to newQueueDoc.id,
                            "positionMs" to 0L,
                            "updatedAt" to Timestamp.now(),
                            "revision" to rev
                        )
                    }
                    tx.update(session, sessionUpdates)
                }
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
        val existingQueueDocs = session.collection(QUEUE).orderBy("position").limit(MAX_QUEUE_SIZE.toLong()).get().await().documents
        val existingQueueSize = existingQueueDocs.size
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
                val rawTrack = snap.get("track") as? Map<*, *> ?: throw JamRepositoryException("Track metadata is missing.")
                val resolvedChecksum = (data["checksum"] as? String)?.takeIf(String::isNotBlank)
                    ?: snap.getString("checksum")?.takeIf(String::isNotBlank)
                val track = if (!resolvedChecksum.isNullOrBlank()) {
                    rawTrack.entries.associate { it.key.toString() to it.value } + ("checksum" to resolvedChecksum)
                } else {
                    rawTrack
                }
                val trackSharedId = (track["sharedTrackId"] as? String).orEmpty()
                val trackTitle = (track["title"] as? String)?.trim()?.lowercase().orEmpty()
                val trackArtist = (track["artist"] as? String)?.trim()?.lowercase().orEmpty()
                val existingMatch = existingQueueDocs.firstOrNull { doc ->
                    val qTrack = doc.get("track") as? Map<*, *>
                    val qId = (qTrack?.get("sharedTrackId") as? String).orEmpty()
                    val qTitle = (qTrack?.get("title") as? String)?.trim()?.lowercase().orEmpty()
                    val qArtist = (qTrack?.get("artist") as? String)?.trim()?.lowercase().orEmpty()
                    (trackSharedId.isNotBlank() && qId == trackSharedId) ||
                        (trackTitle.isNotBlank() && qTitle == trackTitle && qArtist == trackArtist)
                }
                values["status"] = "READY"
                if (existingMatch == null) {
                    if (existingQueueSize >= MAX_QUEUE_SIZE) throw JamRepositoryException("The shared queue is full.")
                    val newQueueDoc = session.collection(QUEUE).document()
                    tx.set(newQueueDoc, mapOf("track" to track, "position" to existingQueueSize,
                        "addedByUid" to snap.getString("proposerUid").orEmpty(), "addedAt" to Timestamp.now(), "source" to "RECOMMENDATION"))
                    val sessionUpdates = mutableMapOf<String, Any>(
                        "queueRevision" to (currentSession.getLong("queueRevision") ?: 0L) + 1L,
                        "revision" to (currentSession.getLong("revision") ?: 0L) + 1L
                    )
                    val currentPlayback = (currentSession.get("playback") as? Map<*, *>)?.entries
                        ?.associate { it.key.toString() to it.value }
                        ?.toMutableMap()
                    if (currentSession.getBoolean("started") == true &&
                        currentPlayback != null &&
                        (currentPlayback["queueItemId"] as? String).isNullOrBlank()) {
                        val rev = ((currentPlayback["revision"] as? Number)?.toLong() ?: 0L) + 1L
                        sessionUpdates["playback"] = currentPlayback + mapOf(
                            "sharedTrackId" to trackSharedId,
                            "queueItemId" to newQueueDoc.id,
                            "positionMs" to 0L,
                            "updatedAt" to Timestamp.now(),
                            "revision" to rev
                        )
                    }
                    tx.update(session, sessionUpdates)
                }
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
        val session = sessionRef(sessionId(data))
        val type = data["commandType"] as? String ?: ""
        val kind = runCatching { JamPermissionKind.valueOf(type) }.getOrNull() ?: throw JamRepositoryException("Playback command is invalid.")
        val requestedQueueItemId = (data["queueItemId"] as? String)?.takeIf(String::isNotBlank)
        val requestedSharedTrackId = (data["sharedTrackId"] as? String)?.takeIf(String::isNotBlank)
        val resolvedByController = data["resolvedByController"] == true && requestedQueueItemId != null && requestedSharedTrackId != null
        val isDirectSelect = data["isDirectSelect"] == true
        val callerPositionMs = (data["positionMs"] as? Number)?.toLong()?.coerceAtLeast(0L)
        val needsQueueDocuments = !resolvedByController && (
            kind == JamPermissionKind.SKIP_TRACK ||
                requestedQueueItemId != null ||
                (kind == JamPermissionKind.PLAY_PAUSE && data["hasActiveItem"] != true)
            )
        val queue = if (needsQueueDocuments) {
            session.collection(QUEUE).orderBy("position").get().await().documents
        } else {
            emptyList()
        }
        val now = System.currentTimeMillis()
        val commandId = data["commandId"] as? String ?: java.util.UUID.randomUUID().toString()
        return firestore.runTransaction { tx ->
            val currentSession = tx.get(session)
            if (currentSession.getString("status") != "ACTIVE") throw JamRepositoryException("This Jam has ended.")
            if (currentSession.getBoolean("started") != true) {
                throw JamRepositoryException("Wait for the host to open the room before starting playback.")
            }
            val currentPermissions = JamPermissions.from(currentSession.get("permissions"))
            val currentHostUid = currentSession.getString("hostUid").orEmpty()
            if (!currentPermissions.allows(kind, uid, currentHostUid)) {
                throw JamRepositoryException("You don't have permission to control playback.")
            }
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
                    if (resolvedByController && requestedQueueItemId != null && requestedSharedTrackId != null) {
                        if (requestedQueueItemId != itemId && itemId.isNotBlank() && isDirectSelect) {
                            if (!currentPermissions.allows(JamPermissionKind.SKIP_TRACK, uid, currentHostUid) ||
                                !currentPermissions.allows(JamPermissionKind.MANAGE_QUEUE, uid, currentHostUid)
                            ) {
                                throw JamRepositoryException("Only users with queue control permission can select a different song from the queue.")
                            }
                        }
                        val switched = requestedQueueItemId != itemId
                        sharedId = requestedSharedTrackId
                        itemId = requestedQueueItemId
                        position = if (switched) (callerPositionMs ?: 0L) else (callerPositionMs ?: if (playing) expectedPosition else position)
                        playing = data["isPlaying"] as? Boolean ?: true
                    } else {
                        val requestedTarget = requestedQueueItemId?.let { targetId -> queue.firstOrNull { it.id == targetId } }
                        if (requestedTarget != null && requestedTarget.id != itemId) {
                            if (!currentPermissions.allows(JamPermissionKind.SKIP_TRACK, uid, currentHostUid) ||
                                !currentPermissions.allows(JamPermissionKind.MANAGE_QUEUE, uid, currentHostUid)
                            ) {
                                throw JamRepositoryException("Only users with queue control permission can select a different song from the queue.")
                            }
                            val track = requestedTarget.get("track") as? Map<*, *> ?: emptyMap<Any, Any>()
                            sharedId = track["sharedTrackId"] as? String ?: ""
                            itemId = requestedTarget.id
                            position = 0L
                            playing = data["isPlaying"] as? Boolean ?: true
                        } else if (requestedTarget != null && requestedTarget.id == itemId) {
                            position = callerPositionMs ?: if (playing) expectedPosition else position
                            playing = data["isPlaying"] as? Boolean ?: true
                        } else if ((itemId.isBlank() || (queue.isNotEmpty() && queue.none { it.id == itemId })) && queue.isNotEmpty()) {
                            val firstTarget = queue.first()
                            val track = firstTarget.get("track") as? Map<*, *> ?: emptyMap<Any, Any>()
                            sharedId = track["sharedTrackId"] as? String ?: ""
                            itemId = firstTarget.id
                            position = 0L
                            playing = data["isPlaying"] as? Boolean ?: true
                        } else {
                            position = callerPositionMs ?: if (playing) expectedPosition else position
                            playing = data["isPlaying"] as? Boolean ?: !playing
                        }
                    }
                }
                JamPermissionKind.SEEK -> {
                    val currentTrack = queue.firstOrNull { it.id == itemId }?.get("track") as? Map<*, *>
                    val duration = (currentTrack?.get("durationMs") as? Number)?.toLong() ?: Long.MAX_VALUE
                    position = (callerPositionMs ?: 0L).coerceIn(0L, duration)
                }
                JamPermissionKind.SKIP_TRACK -> {
                    if (resolvedByController && requestedQueueItemId != null && requestedSharedTrackId != null) {
                        if (isDirectSelect && requestedQueueItemId != itemId && !currentPermissions.allows(JamPermissionKind.MANAGE_QUEUE, uid, currentHostUid)) {
                            throw JamRepositoryException("Only users with queue control permission can jump to a specific song in the queue.")
                        }
                        sharedId = requestedSharedTrackId
                        itemId = requestedQueueItemId
                        position = callerPositionMs ?: 0L
                        playing = data["isPlaying"] as? Boolean ?: true
                        step = if ((data["step"] as? Number)?.toInt() == -1) -1 else 1
                    } else {
                        if (queue.isEmpty()) throw JamRepositoryException("Add a track to the shared queue first.")
                        val requestedTarget = requestedQueueItemId?.let { targetId -> queue.firstOrNull { it.id == targetId } }
                        if (requestedTarget != null) {
                            if (requestedTarget.id != itemId && !currentPermissions.allows(JamPermissionKind.MANAGE_QUEUE, uid, currentHostUid)) {
                                throw JamRepositoryException("Only users with queue control permission can jump to a specific song in the queue.")
                            }
                            val track = requestedTarget.get("track") as? Map<*, *> ?: emptyMap<Any, Any>()
                            sharedId = track["sharedTrackId"] as? String ?: ""
                            itemId = requestedTarget.id
                            position = 0L
                            playing = true
                            step = 1
                        } else {
                            val direction = if ((data["step"] as? Number)?.toInt() == -1) -1 else 1
                            val currentIndex = queue.indexOfFirst { it.id == itemId }
                            if (direction < 0 && playing && (callerPositionMs ?: expectedPosition) > 3_000L) {
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
                                        position = callerPositionMs ?: expectedPosition
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
        val session = sessionRef(sessionId(data))
        firestore.runTransaction { tx ->
            val snap = tx.get(session)
            val requestSnapshots = JamFeature.values().associateWith { feature ->
                val ref = featureRequestRef(session.id, feature)
                ref to tx.get(ref)
            }
            if (snap.getString("hostUid") != uid) throw JamRepositoryException("Only the host can end this Jam.")
            tx.update(session, mapOf(
                "status" to "CLOSED",
                "announcement" to mapOf(
                    "message" to "The host closed this Jam room.",
                    "createdAt" to Timestamp.now()
                ),
                "revision" to (snap.getLong("revision") ?: 0L) + 1
            ))
            requestSnapshots.values.forEach { (ref, requestSnapshot) ->
                resolveFeatureRequestDoc(tx, ref, requestSnapshot, blockRequester = false)
            }
            null
        }.await()
        revokeInviteClient(uid, data)
        return mapOf("ok" to true)
    }
    private suspend fun startJamClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val session = sessionRef(sessionId(data))
        return firestore.runTransaction { tx ->
            val snap = tx.get(session)
            if (snap.getString("hostUid") != uid) throw JamRepositoryException("Only the host can start this Jam.")
            if (snap.getString("status") != "ACTIVE") throw JamRepositoryException("This Jam has ended.")
            if (snap.getBoolean("started") == true) return@runTransaction mapOf("ok" to true)
            val count = snap.getLong("participantCount") ?: 0L
            if (count < 2L) throw JamRepositoryException("Wait for at least one other listener to join before starting the Jam.")
            tx.update(session, mapOf(
                "started" to true,
                "revision" to (snap.getLong("revision") ?: 0L) + 1L
            ))
            mapOf("ok" to true)
        }.await()
    }

    private suspend fun closeSoloStartedJamClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val session = sessionRef(sessionId(data))
        return firestore.runTransaction { tx ->
            val snap = tx.get(session)
            val count = snap.getLong("participantCount") ?: 0L
            val shouldClose = snap.getString("status") == "ACTIVE" && snap.getBoolean("started") == true && count < 2L
            val requestSnapshots = JamFeature.values().associateWith { feature ->
                val ref = featureRequestRef(session.id, feature)
                ref to tx.get(ref)
            }
            if (shouldClose) {
                tx.update(session, mapOf(
                    "status" to "CLOSED",
                    "announcement" to mapOf(
                        "message" to "The Jam closed because fewer than two listeners remained.",
                        "createdAt" to Timestamp.now()
                    ),
                    "revision" to (snap.getLong("revision") ?: 0L) + 1L
                ))
                requestSnapshots.values.forEach { (ref, requestSnapshot) ->
                    resolveFeatureRequestDoc(tx, ref, requestSnapshot, blockRequester = false)
                }
            }
            mapOf("ok" to true, "closed" to shouldClose)
        }.await()
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
        val session = sessionRef(sessionId(data))
        val participants = session.collection(PARTICIPANTS)
        val memberRef = participants.document(uid)
        val orderedRemainingRefs = participants.orderBy("joinedAt", Query.Direction.ASCENDING)
            .get().await().documents.filterNot { it.id == uid }.map { it.reference }

        return firestore.runTransaction { tx ->
            val snap = tx.get(session)
            val leavingMember = tx.get(memberRef)
            if (!leavingMember.exists()) {
                return@runTransaction mapOf("ok" to true, "sessionClosed" to (snap.getString("status") == "CLOSED"))
            }

            val isHost = snap.getString("hostUid") == uid
            val remainingCount = ((snap.getLong("participantCount") ?: 1L) - 1L).coerceAtLeast(0L)
            val remainingMembers = if (isHost) {
                orderedRemainingRefs.mapNotNull { ref -> tx.get(ref).takeIf { it.exists() } }
            } else {
                emptyList()
            }
            val requestSnapshots = JamFeature.values().associateWith { feature ->
                val ref = featureRequestRef(session.id, feature)
                ref to tx.get(ref)
            }
            val successor = if (isHost) remainingMembers.firstOrNull() else null
            val revision = (snap.getLong("revision") ?: 0L) + 1L
            val formerHosts = (snap.get("formerHostUids") as? List<*>)
                ?.filterIsInstance<String>().orEmpty().toMutableList()
            if (isHost && uid !in formerHosts) formerHosts.add(uid)

            val updates = mutableMapOf<String, Any>(
                "participantCount" to remainingCount,
                "revision" to revision
            )
            if (isHost) updates["formerHostUids"] = formerHosts

            val shouldClose = remainingCount < 2L || snap.getString("status") != "ACTIVE"
            val message: String
            if (shouldClose) {
                message = when {
                    remainingCount == 0L -> "The Jam closed because no listeners remained."
                    isHost -> "The Jam closed because only one listener remained after the host left."
                    else -> "The Jam closed because only one listener remained after a listener left."
                }
                updates["status"] = "CLOSED"
                updates["announcement"] = mapOf("message" to message, "createdAt" to Timestamp.now())
            } else if (isHost) {
                val newHost = successor ?: throw JamRepositoryException("The listener list changed. Please try leaving the Jam again.")
                val newHostName = newHost.getString("displayName")?.takeIf(String::isNotBlank) ?: "the first listener"
                message = "The host left the Jam. $newHostName is now hosting."
                updates["hostUid"] = newHost.id
                updates["announcement"] = mapOf(
                    "message" to message,
                    "targetUid" to newHost.id,
                    "targetMessage" to "You are now hosting this Jam because the previous host left.",
                    "createdAt" to Timestamp.now()
                )
                tx.update(newHost.reference, "role", "HOST")
            } else {
                message = "You left the Jam."
            }

            if (shouldClose || isHost) {
                requestSnapshots.values.forEach { (ref, requestSnapshot) ->
                    resolveFeatureRequestDoc(tx, ref, requestSnapshot, blockRequester = false)
                }
            }

            tx.delete(memberRef)
            tx.update(session, updates)
            mapOf("ok" to true, "sessionClosed" to shouldClose, "message" to message)
        }.await()
    }

    private suspend fun removeJamParticipantClient(uid: String, data: Map<String, Any>): Map<String, Any?> {
        val session = sessionRef(sessionId(data))
        val targetUid = data["participantUid"] as? String
            ?: throw JamRepositoryException("Choose a listener to remove.")
        if (targetUid == uid) throw JamRepositoryException("The host cannot remove themselves. Use Leave Jam instead.")
        val target = session.collection(PARTICIPANTS).document(targetUid)
        return firestore.runTransaction { tx ->
            val snapshot = tx.get(session)
            val targetSnapshot = tx.get(target)
            val requestSnapshots = JamFeature.values().associateWith { feature ->
                val ref = featureRequestRef(session.id, feature)
                ref to tx.get(ref)
            }
            if (snapshot.getString("hostUid") != uid) throw JamRepositoryException("Only the host can remove a listener.")
            if (!targetSnapshot.exists()) throw JamRepositoryException("This listener has already left the Jam.")
            val currentCount = (snapshot.getLong("participantCount") ?: 0L).coerceAtLeast(1L)
            val remainingCount = (currentCount - 1L).coerceAtLeast(0L)
            val kicked = (snapshot.get("kickedUids") as? List<*>)?.filterIsInstance<String>().orEmpty().toMutableList()
            if (targetUid !in kicked) kicked.add(targetUid)
            val targetName = targetSnapshot.getString("displayName")?.takeIf(String::isNotBlank) ?: "This listener"
            val shouldClose = remainingCount < 2L
            val publicMessage = if (shouldClose) {
                "The Jam closed because the host removed the last guest."
            } else {
                "$targetName was removed from the Jam and cannot rejoin this room."
            }
            val targetMessage = "The host removed you from this Jam. You cannot rejoin this Jam room."
            val revision = (snapshot.getLong("revision") ?: 0L) + 1L
            val updates = mutableMapOf<String, Any>(
                "participantCount" to remainingCount,
                "kickedUids" to kicked,
                "lastRemovedUid" to targetUid,
                "announcement" to mapOf(
                    "message" to publicMessage,
                    "targetUid" to targetUid,
                    "targetMessage" to targetMessage,
                    "createdAt" to Timestamp.now()
                ),
                "revision" to revision
            )
            if (shouldClose) updates["status"] = "CLOSED"
            requestSnapshots.values.forEach { (ref, requestSnapshot) ->
                if (shouldClose || requestSnapshot.getString("requesterUid") == targetUid) {
                    resolveFeatureRequestDoc(tx, ref, requestSnapshot, blockRequester = false)
                }
            }
            tx.delete(target)
            tx.update(session, updates)
            mapOf(
                "ok" to true,
                "sessionClosed" to shouldClose,
                "message" to if (shouldClose) "The Jam was closed because only the host would remain." else "$targetName was removed from the Jam."
            )
        }.await()
    }

    private fun recommendationRef(data: Map<String, Any>) = sessionRef(sessionId(data)).collection(RECOMMENDATIONS)
        .document(data["recommendationId"] as? String ?: throw JamRepositoryException("Recommendation is missing."))
    private fun sessionId(data: Map<String, Any>) = data["sessionId"] as? String ?: throw JamRepositoryException("Jam session is missing.")
    private fun featureFromId(value: String?): JamFeature = JamFeature.values().firstOrNull { it.documentId == value }
        ?: throw JamRepositoryException("This Jam feature is not supported.")
    private fun featureRequestRef(sessionId: String, feature: JamFeature) =
        sessionRef(sessionId).collection(FEATURE_REQUESTS).document(feature.documentId)

    private fun resolveFeatureRequestDoc(
        transaction: Transaction,
        ref: DocumentReference,
        snapshot: DocumentSnapshot,
        blockRequester: Boolean
    ) {
        if (!snapshot.exists() || snapshot.getBoolean("active") != true) return
        val requesterUid = snapshot.getString("requesterUid").orEmpty()
        val blockedUids = (snapshot.get("blockedUids") as? List<*>)?.filterIsInstance<String>().orEmpty().toMutableList()
        if (blockRequester && requesterUid.isNotBlank() && requesterUid !in blockedUids) blockedUids.add(requesterUid)
        transaction.update(ref, mapOf(
            "active" to false,
            "requesterUid" to "",
            "requesterName" to "",
            "resolvedAt" to FieldValue.serverTimestamp(),
            "blockedUids" to blockedUids
        ))
    }

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

    private fun requireUidValue(): String = authManager.currentUser?.uid?.takeIf(String::isNotBlank)
        ?: throw JamRepositoryException("Sign in to use OffGrid Jam.")

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

    private fun featureRequestFrom(snapshot: DocumentSnapshot): JamFeatureRequest? {
        val feature = JamFeature.values().firstOrNull { it.documentId == snapshot.id } ?: return null
        return JamFeatureRequest(
            feature = feature,
            requesterUid = snapshot.getString("requesterUid").orEmpty(),
            requesterName = snapshot.getString("requesterName").orEmpty(),
            createdAtMs = snapshot.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
            blockedUids = (snapshot.get("blockedUids") as? List<*>)?.filterIsInstance<String>().orEmpty(),
            active = snapshot.getBoolean("active") == true
        )
    }

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
                checksum = data["checksum"] as? String,
                audioFingerprint = data["audioFingerprint"] as? String
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
        val proposerUid = data["proposerUid"] as? String ?: ""
        val eligibleUids = (data["eligibleUids"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val rawVotes = (data["votes"] as? Map<*, *>).orEmpty().mapNotNull { (uid, value) ->
            val parsed = runCatching { JamVote.valueOf(value as? String ?: "PENDING") }.getOrDefault(JamVote.PENDING)
            (uid as? String)?.let { it to parsed }
        }.toMap()
        val votes = rawVotes.toMutableMap().apply {
            if (proposerUid in eligibleUids) put(proposerUid, JamVote.ACCEPT)
        }.toMap()
        val derivedApprovalCount = votes.values.count { it == JamVote.ACCEPT }
        val derivedRejectionCount = votes.values.count { it == JamVote.REJECT }
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
            proposerUid = proposerUid,
            proposerName = data["proposerName"] as? String ?: "Listener",
            track = JamTrack(
                sharedTrackId = rawTrack["sharedTrackId"] as? String ?: return null,
                title = rawTrack["title"] as? String ?: return null,
                artist = rawTrack["artist"] as? String ?: "Unknown Artist",
                album = rawTrack["album"] as? String ?: "Unknown Album",
                durationMs = (rawTrack["durationMs"] as? Number)?.toLong() ?: 0L,
                checksum = rawTrack["checksum"] as? String,
                audioFingerprint = rawTrack["audioFingerprint"] as? String
            ),
            eligibleUids = eligibleUids,
            deadlineAtMs = snapshot.getTimestamp("deadlineAt")?.toDate()?.time ?: 0L,
            status = data["status"] as? String ?: "FAILED",
            approvalCount = if (data["votes"] is Map<*, *>) derivedApprovalCount else (data["approvalCount"] as? Number)?.toInt() ?: 0,
            rejectionCount = if (data["votes"] is Map<*, *>) derivedRejectionCount else (data["rejectionCount"] as? Number)?.toInt() ?: 0,
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
        val host = inviteHost.trim().trim('/').takeIf(String::isNotBlank) ?: "offgrid-music.web.app"
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
        private const val FEATURE_REQUESTS = "featureRequests"
        private const val LIBRARY_INDEX = "libraryIndex"
        private const val LIBRARY_INDEX_SCHEMA_VERSION = 1
        private const val LIBRARY_MATCH_KEYS_PER_CHUNK = 200
        private const val MAX_LIBRARY_MATCH_KEYS = 20_000
        private const val MAX_RECOMMENDATIONS = 30
    }
}

typealias JamRepositoryException = JamRepository.JamRepositoryException
