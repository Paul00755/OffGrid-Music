package com.example.jam

import androidx.compose.runtime.Immutable
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.security.MessageDigest

enum class JamAccessMode { HOST_ONLY, EVERYONE, SELECTED }

enum class JamPermissionKind {
    PLAY_PAUSE, SEEK, SKIP_TRACK, ADD_TO_QUEUE, MANAGE_QUEUE
}

@Immutable
data class JamPermissionRule(
    val mode: JamAccessMode = JamAccessMode.HOST_ONLY,
    val selectedUids: Set<String> = emptySet()
) {
    fun allows(uid: String, hostUid: String): Boolean = when (mode) {
        JamAccessMode.HOST_ONLY -> uid == hostUid
        JamAccessMode.EVERYONE -> true
        JamAccessMode.SELECTED -> uid == hostUid || uid in selectedUids
    }

    fun toMap(): Map<String, Any> = mapOf(
        "mode" to mode.name,
        "selectedUids" to selectedUids.toList()
    )

    companion object {
        fun from(value: Any?): JamPermissionRule {
            val map = value as? Map<*, *> ?: return JamPermissionRule()
            val mode = runCatching {
                JamAccessMode.valueOf(map["mode"] as? String ?: "HOST_ONLY")
            }.getOrDefault(JamAccessMode.HOST_ONLY)
            val selected = (map["selectedUids"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.toSet()
                .orEmpty()
            return JamPermissionRule(mode, selected)
        }
    }
}

@Immutable
data class JamPermissions(
    val playPause: JamPermissionRule = JamPermissionRule(JamAccessMode.EVERYONE),
    val seek: JamPermissionRule = JamPermissionRule(JamAccessMode.EVERYONE),
    val skipTrack: JamPermissionRule = JamPermissionRule(JamAccessMode.EVERYONE),
    val addToQueue: JamPermissionRule = JamPermissionRule(),
    val manageQueue: JamPermissionRule = JamPermissionRule(),
    val allowLocalSongSharing: Boolean = false,
    val allowSharedLibraryRecommendations: Boolean = true
) {
    fun allows(kind: JamPermissionKind, uid: String, hostUid: String): Boolean = when (kind) {
        JamPermissionKind.PLAY_PAUSE -> playPause.allows(uid, hostUid)
        JamPermissionKind.SEEK -> seek.allows(uid, hostUid)
        JamPermissionKind.SKIP_TRACK -> skipTrack.allows(uid, hostUid)
        JamPermissionKind.ADD_TO_QUEUE -> addToQueue.allows(uid, hostUid)
        JamPermissionKind.MANAGE_QUEUE -> manageQueue.allows(uid, hostUid)
    }

    fun toMap(): Map<String, Any> = mapOf(
        "playPause" to playPause.toMap(),
        "seek" to seek.toMap(),
        "skipTrack" to skipTrack.toMap(),
        "addToQueue" to addToQueue.toMap(),
        "manageQueue" to manageQueue.toMap(),
        "allowLocalSongSharing" to allowLocalSongSharing,
        "allowSharedLibraryRecommendations" to allowSharedLibraryRecommendations
    )

    companion object {
        fun from(value: Any?): JamPermissions {
            val map = value as? Map<*, *> ?: return JamPermissions()
            return JamPermissions(
                playPause = JamPermissionRule.from(map["playPause"]),
                seek = JamPermissionRule.from(map["seek"]),
                skipTrack = JamPermissionRule.from(map["skipTrack"]),
                addToQueue = JamPermissionRule.from(map["addToQueue"]),
                manageQueue = JamPermissionRule.from(map["manageQueue"]),
                allowLocalSongSharing = map["allowLocalSongSharing"] as? Boolean ?: false,
                allowSharedLibraryRecommendations = map["allowSharedLibraryRecommendations"] as? Boolean ?: true
            )
        }
    }
}

@Immutable
data class JamTrack(
    val sharedTrackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val checksum: String? = null
) {
    fun toMap(): Map<String, Any> = buildMap {
        put("sharedTrackId", sharedTrackId)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("durationMs", durationMs.coerceAtLeast(0L))
        checksum?.takeIf(String::isNotBlank)?.let { put("checksum", it) }
    }
}

@Immutable
data class JamParticipant(
    val uid: String,
    val displayName: String,
    val photoUrl: String? = null,
    val joinedAtMs: Long = 0L,
    val lastSeenAtMs: Long = 0L,
    val isHost: Boolean = false
) {
    val isConnected: Boolean get() = lastSeenAtMs > 0L && System.currentTimeMillis() - lastSeenAtMs < 6 * 60_000L
}

@Immutable
data class JamQueueItem(
    val id: String,
    val track: JamTrack,
    val addedByUid: String,
    val position: Long
)

@Immutable
data class JamPlaybackState(
    val sharedTrackId: String? = null,
    val queueItemId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val updatedAtMs: Long = 0L,
    val revision: Long = 0L,
    val commandId: String? = null,
    val commandType: String? = null,
    val actorUid: String? = null
) {
    fun estimatedPosition(nowMs: Long = System.currentTimeMillis()): Long {
        if (!isPlaying || updatedAtMs <= 0L) return positionMs
        return (positionMs + ((nowMs - updatedAtMs).coerceAtLeast(0L) * playbackSpeed).toLong())
            .coerceAtLeast(0L)
    }
}

@Immutable
data class JamSession(
    val id: String,
    val hostUid: String,
    val status: String,
    val participantCount: Int,
    val maxParticipants: Int = 10,
    val started: Boolean = false,
    val permissions: JamPermissions,
    val playback: JamPlaybackState,
    val revision: Long,
    val queueRevision: Long,
    val createdAtMs: Long
) {
    val isActive: Boolean get() = status == STATUS_ACTIVE

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_CLOSED = "CLOSED"

        fun from(id: String, snapshot: DocumentSnapshot): JamSession {
            val playbackMap = snapshot.get("playback") as? Map<*, *> ?: emptyMap<Any, Any>()
            val playbackTime = (playbackMap["updatedAt"] as? Timestamp)?.toDate()?.time
                ?: (playbackMap["updatedAtMs"] as? Number)?.toLong()
                ?: 0L
            val playback = JamPlaybackState(
                sharedTrackId = playbackMap["sharedTrackId"] as? String,
                queueItemId = playbackMap["queueItemId"] as? String,
                isPlaying = playbackMap["isPlaying"] as? Boolean ?: false,
                positionMs = (playbackMap["positionMs"] as? Number)?.toLong() ?: 0L,
                playbackSpeed = (playbackMap["playbackSpeed"] as? Number)?.toFloat() ?: 1f,
                updatedAtMs = playbackTime,
                revision = (playbackMap["revision"] as? Number)?.toLong() ?: 0L,
                commandId = playbackMap["commandId"] as? String,
                commandType = playbackMap["commandType"] as? String,
                actorUid = playbackMap["actorUid"] as? String
            )
            return JamSession(
                id = id,
                hostUid = snapshot.getString("hostUid").orEmpty(),
                status = snapshot.getString("status") ?: STATUS_CLOSED,
                participantCount = (snapshot.getLong("participantCount") ?: 0L).toInt(),
                maxParticipants = (snapshot.getLong("maxParticipants") ?: 10L).toInt().coerceIn(2, 10),
                started = snapshot.getBoolean("started") ?: false,
                permissions = JamPermissions.from(snapshot.get("permissions")),
                playback = playback,
                revision = snapshot.getLong("revision") ?: 0L,
                queueRevision = snapshot.getLong("queueRevision") ?: 0L,
                createdAtMs = snapshot.getTimestamp("createdAt")?.toDate()?.time
                    ?: snapshot.getLong("createdAtMs") ?: 0L
            )
        }
    }
}

data class JamInvite(val sessionId: String, val code: String, val url: String?)

enum class JamRecommendationType { SHARED_LIBRARY, LOCAL_SONG }
enum class JamVote { ACCEPT, REJECT, PENDING }

@Immutable
data class JamDownloadState(
    val status: String = "WAITING_FOR_UPLOAD",
    val progress: Int = 0,
    val checksumVerified: Boolean = false,
    val locallyAvailable: Boolean = false,
    val readyForPlayback: Boolean = false,
    val error: String? = null
)

@Immutable
data class JamRecommendation(
    val id: String,
    val type: JamRecommendationType,
    val proposerUid: String,
    val proposerName: String,
    val track: JamTrack,
    val eligibleUids: List<String>,
    val deadlineAtMs: Long,
    val status: String,
    val approvalCount: Int,
    val rejectionCount: Int,
    val requiredApprovalCount: Int,
    val votes: Map<String, JamVote>,
    val downloads: Map<String, JamDownloadState>,
    val storagePath: String? = null,
    val checksum: String? = null,
    val fileSize: Long = 0L,
    val transferExpiresAtMs: Long = 0L,
    val contentType: String? = null,
    val uploadProgress: Int = 0,
    val uploadStatus: String = "WAITING_FOR_UPLOAD",
    val failureReason: String? = null
) {
    val pendingCount: Int get() = (eligibleUids.size - approvalCount - rejectionCount).coerceAtLeast(0)
    val remainingMs: Long get() = (deadlineAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
    val isVoting: Boolean get() = status == "VOTING"
    val isTerminal: Boolean get() = status in setOf("READY", "REJECTED", "EXPIRED", "FAILED")
}

enum class JamPage { LANDING, CREATE, JOIN, INVITE, ACTIVE, PERMISSIONS }

sealed interface JamUiState {
    data object Idle : JamUiState
    data object Loading : JamUiState
    data class Error(val message: String) : JamUiState
}

internal fun sharedTrackId(track: JamTrack): String {
    val normalized = listOf(track.title, track.artist, track.album)
        .joinToString("\u001f") { it.trim().lowercase().replace(Regex("\\s+"), " ") }
    val durationBucket = (track.durationMs.coerceAtLeast(0L) / 2_000L).toString()
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("$normalized\u001f$durationBucket".toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

internal fun invitationDigest(code: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(code.trim().uppercase().toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
