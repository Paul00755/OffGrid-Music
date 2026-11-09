package com.example.jam

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import com.example.MainActivity
import com.example.auth.AuthManager
import com.example.auth.UserAccount
import com.example.data.model.Song
import com.example.service.PlaybackManager
import com.example.service.PlaybackState
import com.example.service.notification.OffGridFirebaseMessagingService
import com.example.util.TagCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class JamIncomingVoteAlert(
    val recommendationId: String,
    val proposerName: String,
    val trackTitle: String,
    val trackArtist: String,
    val isLocalSong: Boolean
)

/** UI state and playback bridge for Jam; all audio continues through the app's existing player. */
class JamFeatureController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: JamRepository,
    private val authManager: AuthManager,
    private val playbackManager: PlaybackManager,
    private val allSongs: StateFlow<List<Song>>,
    private val userAccount: StateFlow<UserAccount?>,
    private val displayName: StateFlow<String>
) {
    private val _page = MutableStateFlow(JamPage.LANDING)
    val page: StateFlow<JamPage> = _page.asStateFlow()
    private val _uiState = MutableStateFlow<JamUiState>(JamUiState.Idle)
    val uiState: StateFlow<JamUiState> = _uiState.asStateFlow()
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()
    private val _session = MutableStateFlow<JamSession?>(null)
    val session: StateFlow<JamSession?> = _session.asStateFlow()
    private val _participants = MutableStateFlow<List<JamParticipant>>(emptyList())
    val participants: StateFlow<List<JamParticipant>> = _participants.asStateFlow()
    private val _queue = MutableStateFlow<List<JamQueueItem>>(emptyList())
    val queue: StateFlow<List<JamQueueItem>> = _queue.asStateFlow()
    private val _recommendations = MutableStateFlow<List<JamRecommendation>>(emptyList())
    val recommendations: StateFlow<List<JamRecommendation>> = _recommendations.asStateFlow()
    private val _pendingVotes = MutableStateFlow<Map<String, JamVote>>(emptyMap())
    val pendingVotes: StateFlow<Map<String, JamVote>> = _pendingVotes.asStateFlow()
    private val _incomingVoteAlert = MutableStateFlow<JamIncomingVoteAlert?>(null)
    val incomingVoteAlert: StateFlow<JamIncomingVoteAlert?> = _incomingVoteAlert.asStateFlow()
    private val _featureRequests = MutableStateFlow<List<JamFeatureRequest>>(emptyList())
    val featureRequests: StateFlow<List<JamFeatureRequest>> = _featureRequests.asStateFlow()
    private val _invite = MutableStateFlow<JamInvite?>(null)
    val invite: StateFlow<JamInvite?> = _invite.asStateFlow()
    private val _localTrackAvailable = MutableStateFlow(true)
    val localTrackAvailable: StateFlow<Boolean> = _localTrackAvailable.asStateFlow()
    private val _currentUid = MutableStateFlow(authManager.currentUser?.uid)
    val currentUid: StateFlow<String?> = _currentUid.asStateFlow()
    private val _selectedRecommendationId = MutableStateFlow<String?>(null)
    val selectedRecommendationId: StateFlow<String?> = _selectedRecommendationId.asStateFlow()
    private val _sharedLibraryMatches = MutableStateFlow<List<JamSharedLibraryMatch>>(emptyList())
    val sharedLibraryMatches: StateFlow<List<JamSharedLibraryMatch>> = _sharedLibraryMatches.asStateFlow()
    private val _libraryIndexStatus = MutableStateFlow(JamLibraryIndexStatus())
    val libraryIndexStatus: StateFlow<JamLibraryIndexStatus> = _libraryIndexStatus.asStateFlow()
    private val _remoteLibraryIndex = MutableStateFlow(JamLibraryIndexSnapshot())
    private var localLibraryEntries: List<LocalLibraryEntry> = emptyList()
    private var localLibraryReadyForSession: String? = null
    private val notifiedRecommendationIds = LinkedHashSet<String>()

    private sealed interface PendingAction {
        data object Create : PendingAction
        data class Join(val code: String?) : PendingAction
        data class OpenRecommendation(val sessionId: String, val recommendationId: String) : PendingAction
    }

    private var pendingAction: PendingAction? = null
    private var activeUid: String? = null
    private var previousPlayback: PlaybackState? = null
    private var sessionListenerJob: Job? = null
    private var queueListenerJob: Job? = null
    private var participantListenerJob: Job? = null
    private var recommendationListenerJob: Job? = null
    private var libraryIndexListenerJob: Job? = null
    private var featureRequestListenerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var driftJob: Job? = null
    private var soloCloseJob: Job? = null
    private var lastAppliedRevision = -1L
    private var lastAppliedTrackId: String? = null
    private var lastHandledEndTrackId: String? = null
    private var lastAnchoredRevision = -1L
    private var anchoredPositionMs = 0L
    private var anchoredAtLocalWallMs = 0L
    private var lastLocalCommandId: String? = null
    private var lastLocalCommandAtMs = 0L
    private var lastSeekOrApplyRealtimeMs = 0L
    private var restoredForUid: String? = null
    private var lastAnnouncementAtMs: Long = 0L
    @Volatile private var pendingQueueOrderIds: List<String>? = null
    @Volatile private var pendingQueueOrderAtMs: Long = 0L
    private val handlingEnd = AtomicBoolean(false)

    private val endedListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val current = _session.value ?: return
            if (authManager.currentUser?.uid != current.hostUid) return
            val trackId = current.playback.sharedTrackId ?: return
            if (lastHandledEndTrackId == trackId || !handlingEnd.compareAndSet(false, true)) return
            lastHandledEndTrackId = trackId
            scope.launch {
                try {
                    skip(1)
                } catch (error: Throwable) {
                    _uiState.value = JamUiState.Error(error.message ?: "Couldn't advance the shared queue.")
                } finally {
                    handlingEnd.set(false)
                }
            }
        }
    }

    init {
        activeInstance = this
        playbackManager.player.addListener(endedListener)
        scope.launch {
            userAccount.collectLatest { account ->
                val uid = account?.uid ?: authManager.currentUser?.uid
                val oldUid = activeUid
                if (oldUid != null && uid != oldUid && _sessionId.value != null) {
                    stopObserving(restorePlayback = true)
                }
                activeUid = uid
                _currentUid.value = uid
                if (uid == null) restoredForUid = null
                if (uid != null && restoredForUid != uid) {
                    restoredForUid = uid
                    val prefs = context.getSharedPreferences(TRANSFER_PREFS, Context.MODE_PRIVATE)
                    val savedSession = prefs.getString(KEY_ACTIVE_SESSION, null)
                    if (_sessionId.value == null && !savedSession.isNullOrBlank()) {
                        // Cleanly exit any stale session left over from a previous app termination
                        prefs.edit()
                            .remove(KEY_ACTIVE_UID)
                            .remove(KEY_ACTIVE_SESSION)
                            .remove(KEY_ACTIVE_INVITE_CODE)
                            .remove(KEY_ACTIVE_INVITE_URL)
                            .apply()
                        appCloseScope.launch {
                            withContext(NonCancellable) {
                                runCatching { repository.leave(savedSession) }
                            }
                        }
                    }
                }
                val pending = pendingAction
                if (uid != null && pending != null) {
                    pendingAction = null
                    when (pending) {
                        PendingAction.Create -> _page.value = JamPage.CREATE
                        is PendingAction.Join -> {
                            val code = pending.code
                            if (code.isNullOrBlank()) _page.value = JamPage.JOIN else joinByCode(code)
                        }
                        is PendingAction.OpenRecommendation -> openRecommendation(pending.sessionId, pending.recommendationId)
                    }
                }
            }
        }
        scope.launch {
            combine(_session, _queue, allSongs) { session, queue, songs -> Triple(session, queue, songs) }
                .collectLatest { (session, queue, songs) -> applyPlaybackSnapshot(session, queue, songs) }
        }
        scope.launch {
            combine(_sessionId, allSongs) { sessionId, songs -> sessionId to songs }
                .collectLatest { (sessionId, songs) -> indexLocalLibrary(sessionId, songs) }
        }
        scope.launch {
            combine(_participants, _remoteLibraryIndex) { participants, index -> participants to index }
                .collectLatest { updateSharedLibraryMatches() }
        }
    }

    /** Returns true when the caller should show the existing app authentication screen. */
    fun requestCreate(): Boolean {
        if (authManager.currentUser == null) {
            pendingAction = PendingAction.Create
            return true
        }
        _uiState.value = JamUiState.Idle
        _page.value = JamPage.CREATE
        return false
    }

    /** A null/blank code opens the join form; a code resumes the invitation flow after sign-in. */
    fun requestJoin(code: String? = null): Boolean {
        if (authManager.currentUser == null) {
            pendingAction = PendingAction.Join(code)
            return true
        }
        if (code.isNullOrBlank()) {
            _uiState.value = JamUiState.Idle
            _page.value = JamPage.JOIN
        } else {
            joinByCode(code)
        }
        return false
    }

    fun requestOpenRecommendation(sessionId: String, recommendationId: String): Boolean {
        if (authManager.currentUser == null) {
            pendingAction = PendingAction.OpenRecommendation(sessionId, recommendationId)
            return true
        }
        openRecommendation(sessionId, recommendationId)
        return false
    }

    private fun openRecommendation(sessionId: String, recommendationId: String) {
        _selectedRecommendationId.value = recommendationId
        if (_sessionId.value != sessionId) activateSession(sessionId, JamPage.ACTIVE) else _page.value = JamPage.ACTIVE
    }

    fun openPage(page: JamPage) {
        val session = _session.value
        val uid = authManager.currentUser?.uid
        if (page == JamPage.ACTIVE && session != null && !session.started &&
            session.hostUid == uid && session.participantCount < 2) {
            _page.value = JamPage.INVITE
            return
        }
        if (_sessionId.value != null && page == JamPage.LANDING) {
            val hostStillWaiting = session?.started != true &&
                (_page.value == JamPage.INVITE || (_invite.value != null && session?.hostUid == uid))
            _page.value = if (hostStillWaiting) {
                JamPage.INVITE
            } else {
                JamPage.ACTIVE
            }
            return
        }
        _page.value = page
    }

    fun clearError() {
        if (_uiState.value is JamUiState.Error) _uiState.value = JamUiState.Idle
    }

    fun cancelPendingAction() {
        pendingAction = null
    }

    fun createJam(permissions: JamPermissions, maxParticipants: Int) {
        val account = currentAccountOrNull() ?: run { requestCreate(); return }
        runAction {
            val invite = repository.createSession(
                displayName = resolvedDisplayName(account),
                photoUrl = account.photoUrl,
                permissions = permissions,
                maxParticipants = maxParticipants
            )
            activateSession(invite.sessionId, page = JamPage.INVITE)
            _invite.value = invite
            context.getSharedPreferences(TRANSFER_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ACTIVE_INVITE_CODE, invite.code)
                .putString(KEY_ACTIVE_INVITE_URL, invite.url)
                .apply()
        }
    }

    fun joinByCode(code: String) {
        val normalized = extractJamInviteCode(code)
        val account = currentAccountOrNull() ?: run { requestJoin(normalized); return }
        _page.value = JamPage.JOIN
        runAction {
            val id = repository.joinByCode(
                code = normalized,
                displayName = resolvedDisplayName(account),
                photoUrl = account.photoUrl
            )
            _invite.value = null
            activateSession(id, page = JamPage.ACTIVE)
        }
    }

    fun startJam() {
        val id = _sessionId.value ?: return
        runAction {
            repository.startSession(id)
            _page.value = JamPage.ACTIVE
        }
    }

    fun addSong(song: Song) {
        val sessionId = _sessionId.value ?: return
        val track = trackFrom(song) ?: run {
            _uiState.value = JamUiState.Error("This track needs a title and artist before it can be shared.")
            return
        }
        val insertNext = playbackManager.addToQueuePosition.equals("TOP_NEXT", ignoreCase = true)
        runAction {
            if (insertNext) {
                repository.playTrackNext(sessionId, track)
            } else {
                repository.addTrack(sessionId, track)
            }
            _uiState.value = JamUiState.Idle
        }
    }

    fun recommendSharedTrack(track: JamTrack) {
        val id = _sessionId.value ?: return
        runAction {
            repository.createRecommendation(id, JamRecommendationType.SHARED_LIBRARY, track)
            _uiState.value = JamUiState.Notice("Started 30s vote for \"${track.title}\".")
        }
    }

    fun recommendSharedLibrarySong(match: JamSharedLibraryMatch) {
        recommendSharedTrack(match.track)
    }

    fun addSharedLibrarySongToQueue(match: JamSharedLibraryMatch) {
        val sessionId = _sessionId.value ?: return
        val insertNext = playbackManager.addToQueuePosition.equals("TOP_NEXT", ignoreCase = true)
        runAction {
            if (insertNext) {
                repository.playTrackNext(sessionId, match.track)
                _uiState.value = JamUiState.Notice("Added \"${match.track.title}\" to play next in the shared queue.")
            } else {
                repository.addTrack(sessionId, match.track)
                _uiState.value = JamUiState.Notice("Added \"${match.track.title}\" to the shared queue.")
            }
        }
    }

    fun playSharedLibrarySongNext(match: JamSharedLibraryMatch) {
        val sessionId = _sessionId.value ?: return
        runAction {
            repository.playTrackNext(sessionId, match.track)
            _uiState.value = JamUiState.Notice("\"${match.track.title}\" will play next.")
        }
    }

    fun playSharedLibrarySongNow(match: JamSharedLibraryMatch) {
        val sessionId = _sessionId.value ?: return
        runAction {
            repository.playTrackNow(sessionId, match.track)
            _uiState.value = JamUiState.Notice("Now playing \"${match.track.title}\" for everyone.")
        }
    }

    fun addAllMatchingSongsToQueue(shuffle: Boolean = false) {
        val sessionId = _sessionId.value ?: return
        val tracks = _sharedLibraryMatches.value.map { it.track }
        if (tracks.isEmpty()) {
            _uiState.value = JamUiState.Error("No matching songs are available to add.")
            return
        }
        runAction {
            val added = repository.addTracksBatch(sessionId, tracks, shuffle = shuffle)
            _uiState.value = if (added > 0) {
                JamUiState.Notice(
                    if (shuffle) "Shuffled and added $added matching song(s) to the shared queue."
                    else "Added $added matching song(s) to the shared queue."
                )
            } else {
                JamUiState.Notice("The shared queue is already full (${JamRepository.MAX_QUEUE_SIZE} tracks).")
            }
        }
    }

    fun playQueueItem(queueItemId: String) {
        val current = _session.value ?: return
        val uid = authManager.currentUser?.uid.orEmpty()
        val isSwitch = queueItemId != current.playback.queueItemId
        val permitted = current.started &&
            current.permissions.allows(JamPermissionKind.PLAY_PAUSE, uid, current.hostUid) &&
            (!isSwitch || (current.permissions.allows(JamPermissionKind.SKIP_TRACK, uid, current.hostUid) &&
                current.permissions.allows(JamPermissionKind.MANAGE_QUEUE, uid, current.hostUid)))
        if (!permitted) {
            _uiState.value = JamUiState.Error("You don't have permission to switch tracks in the shared queue.")
            return
        }
        val targetItem = _queue.value.firstOrNull { it.id == queueItemId } ?: return
        val targetPos = if (isSwitch) 0L else current.playback.estimatedPosition()
        val nowMs = System.currentTimeMillis()
        val cmdId = UUID.randomUUID().toString()
        lastLocalCommandId = cmdId
        lastLocalCommandAtMs = nowMs
        anchoredPositionMs = targetPos
        anchoredAtLocalWallMs = nowMs
        lastSeekOrApplyRealtimeMs = android.os.SystemClock.elapsedRealtime()
        lastHandledEndTrackId = null

        val optimisticPlayback = current.playback.copy(
            sharedTrackId = targetItem.track.sharedTrackId,
            queueItemId = targetItem.id,
            isPlaying = true,
            positionMs = targetPos,
            updatedAtMs = nowMs,
            commandId = cmdId,
            commandType = JamPermissionKind.PLAY_PAUSE.name,
            actorUid = uid
        )
        _session.value = current.copy(playback = optimisticPlayback)
        val targetSong = matchLocalSong(targetItem.track, allSongs.value)
        if (targetSong != null) {
            _localTrackAvailable.value = true
            lastAppliedTrackId = targetItem.track.sharedTrackId
            playbackManager.applyJamPlaybackSnapshot(
                song = targetSong,
                positionMs = targetPos,
                isPlaying = true,
                seekPosition = isSwitch
            )
        }
        scope.launch {
            runCatching {
                val committed = repository.submitPlaybackCommand(
                    sessionId = current.id,
                    type = JamPermissionKind.PLAY_PAUSE,
                    isPlaying = true,
                    positionMs = targetPos,
                    targetQueueItemId = targetItem.id,
                    targetSharedTrackId = targetItem.track.sharedTrackId,
                    hasActiveItem = !current.playback.queueItemId.isNullOrBlank(),
                    commandId = cmdId,
                    isDirectSelect = true
                )
                lastAnchoredRevision = maxOf(lastAnchoredRevision, committed.revision)
                lastAppliedRevision = maxOf(lastAppliedRevision, committed.revision)
                val latest = _session.value
                if (latest != null && latest.id == current.id && committed.revision >= latest.playback.revision) {
                    _session.value = latest.copy(
                        playback = committed.copy(
                            positionMs = anchoredPositionMs,
                            updatedAtMs = anchoredAtLocalWallMs
                        )
                    )
                }
            }.onFailure { error ->
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't play the selected track.")
            }
        }
    }

    fun shuffleQueue() {
        val id = _sessionId.value ?: return
        val current = _queue.value
        if (current.size <= 1) return
        val activeId = _session.value?.playback?.queueItemId
        val activeItem = current.firstOrNull { it.id == activeId }
        val others = current.filter { it.id != activeId }.shuffled()
        val reorderedItems = if (activeItem != null) {
            listOf(activeItem) + others
        } else {
            others
        }
        val orderedIds = reorderedItems.map { it.id }
        pendingQueueOrderIds = orderedIds
        pendingQueueOrderAtMs = System.currentTimeMillis()
        _queue.value = reorderedItems.mapIndexed { idx, item -> item.copy(position = idx.toLong()) }
        runAction {
            repository.reorderQueue(id, orderedIds)
            _uiState.value = JamUiState.Notice("Shuffled the shared queue.")
        }
    }

    fun dismissIncomingVoteAlert() {
        _incomingVoteAlert.value = null
    }

    fun removeDuplicateSongsFromQueue() {
        val id = _sessionId.value ?: return
        runAction {
            val removed = repository.removeDuplicateTracks(id)
            _uiState.value = if (removed > 0) {
                JamUiState.Notice("Removed $removed duplicate song(s) from the shared queue.")
            } else {
                JamUiState.Notice("All songs in the shared queue are already unique.")
            }
        }
    }

    fun reorderQueueIds(orderedIds: List<String>) {
        val id = _sessionId.value ?: return
        if (orderedIds.size <= 1) return
        val currentMap = _queue.value.associateBy { it.id }
        val reordered = orderedIds.mapNotNull { currentMap[it] }
        if (reordered.size == _queue.value.size) {
            pendingQueueOrderIds = orderedIds
            pendingQueueOrderAtMs = System.currentTimeMillis()
            _queue.value = reordered.mapIndexed { idx, item -> item.copy(position = idx.toLong()) }
        }
        scope.launch {
            runCatching { repository.reorderQueue(id, orderedIds) }
                .onFailure {
                    pendingQueueOrderIds = null
                    _uiState.value = JamUiState.Error(it.message ?: "Couldn't reorder the shared queue.")
                }
        }
    }

    fun resolveSongForTrack(track: JamTrack, songs: List<Song> = allSongs.value): Song? {
        _sharedLibraryMatches.value.firstOrNull { it.matchKey == track.sharedTrackId }?.song?.let { return it }
        return matchLocalSong(track, songs)
    }

    fun recommendLocalTrack(track: JamTrack, uri: Uri, mimeType: String) {
        val id = _sessionId.value ?: return
        runAction {
            val stagedUri = withContext(Dispatchers.IO) {
                runCatching {
                    val stagingDir = File(context.cacheDir, "jam_staged_uploads").apply { mkdirs() }
                    val stagedFile = File(stagingDir, "staged_${System.currentTimeMillis()}_${track.sharedTrackId.take(16)}.part")
                    val inputStream = if (uri.scheme?.lowercase() == "file") {
                        val filePath = uri.path ?: error("Invalid local file path.")
                        File(filePath).inputStream()
                    } else {
                        context.contentResolver.openInputStream(uri)
                    }
                    inputStream?.use { input ->
                        stagedFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: error("Couldn't read the selected audio file.")
                    if (!stagedFile.isFile || stagedFile.length() <= 0L) error("Selected audio file is empty.")
                    Uri.fromFile(stagedFile)
                }.getOrElse { uri }
            }
            val prefs = context.getSharedPreferences(TRANSFER_PREFS, Context.MODE_PRIVATE)
            // Save by track ID BEFORE creating the recommendation so if observeRecommendations
            // emits immediately upon creation, syncLocalTransfers always has the staged URI ready.
            prefs.edit()
                .putString("source_by_track:${track.sharedTrackId}", stagedUri.toString())
                .putString("mime_by_track:${track.sharedTrackId}", mimeType)
                .apply()
            val recommendationId = repository.createRecommendation(id, JamRecommendationType.LOCAL_SONG, track)
            prefs.edit()
                .putString("source:$recommendationId", stagedUri.toString())
                .putString("mime:$recommendationId", mimeType)
                .apply()
            _uiState.value = JamUiState.Notice("Started 30s vote for local song \"${track.title}\".")
            syncLocalTransfers(_recommendations.value)
        }
    }

    fun vote(recommendationId: String, vote: JamVote) {
        val id = _sessionId.value ?: return
        if (_pendingVotes.value.containsKey(recommendationId)) return
        _pendingVotes.value = _pendingVotes.value + (recommendationId to vote)
        if (_incomingVoteAlert.value?.recommendationId == recommendationId) {
            _incomingVoteAlert.value = null
        }
        scope.launch {
            try {
                repository.voteRecommendation(id, recommendationId, vote)
            } catch (error: Throwable) {
                _pendingVotes.value = _pendingVotes.value - recommendationId
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't submit your vote.")
            }
        }
    }

    fun retryDownload(recommendationId: String) {
        val id = _sessionId.value ?: return
        runAction { repository.retryDownload(id, recommendationId) }
    }

    fun retryUpload(recommendationId: String) {
        val id = _sessionId.value ?: return
        runAction {
            repository.retryLocalUpload(id, recommendationId)
            syncLocalTransfers(_recommendations.value)
        }
    }

    fun removeQueueItem(itemId: String) {
        val id = _sessionId.value ?: return
        runAction { repository.removeTrack(id, itemId) }
    }

    fun moveQueueItem(itemId: String, delta: Int) {
        val current = _queue.value
        val from = current.indexOfFirst { it.id == itemId }
        val to = (from + delta).coerceIn(0, current.lastIndex)
        if (from < 0 || from == to) return
        val reordered = current.toMutableList().apply {
            add(to, removeAt(from))
        }
        val ids = reordered.map { it.id }
        pendingQueueOrderIds = ids
        pendingQueueOrderAtMs = System.currentTimeMillis()
        _queue.value = reordered.mapIndexed { idx, item -> item.copy(position = idx.toLong()) }
        val id = _sessionId.value ?: return
        runAction { repository.reorderQueue(id, ids) }
    }

    fun setPermissions(permissions: JamPermissions) {
        val id = _sessionId.value ?: return
        val previous = _session.value
        if (previous != null && previous.id == id) {
            // Apply permission update immediately in-memory so all Jam controls reflect changes in 0ms
            _session.value = previous.copy(permissions = permissions)
        }
        scope.launch {
            runCatching {
                repository.updatePermissions(id, permissions)
            }.onFailure { error ->
                if (previous != null && _sessionId.value == id) {
                    _session.value = previous
                }
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't update Jam permissions.")
            }
        }
    }

    fun requestFeature(feature: JamFeature) {
        val id = _sessionId.value ?: return
        val account = currentAccountOrNull() ?: return
        runAction { repository.requestFeature(id, feature, resolvedDisplayName(account)) }
    }

    fun resolveFeatureRequest(feature: JamFeature, enableFeature: Boolean, blockRequester: Boolean = false) {
        val id = _sessionId.value ?: return
        if (enableFeature) {
            val current = _session.value
            if (current != null && current.id == id) {
                val updatedPerms = when (feature) {
                    JamFeature.SHARED_RECOMMENDATIONS -> current.permissions.copy(allowSharedLibraryRecommendations = true)
                    JamFeature.LOCAL_SONG_SHARING -> current.permissions.copy(allowLocalSongSharing = true)
                }
                _session.value = current.copy(permissions = updatedPerms)
            }
        }
        runAction { repository.resolveFeatureRequest(id, feature, enableFeature, blockRequester) }
    }

    fun togglePlayback() {
        val current = _session.value ?: return
        setPlaybackPlaying(!current.playback.isPlaying)
    }

    private fun setPlaybackPlaying(isPlaying: Boolean) {
        val current = _session.value ?: return
        val uid = authManager.currentUser?.uid.orEmpty()
        val permitted = current.started && current.permissions.allows(JamPermissionKind.PLAY_PAUSE, uid, current.hostUid)
        if (!permitted) {
            _uiState.value = JamUiState.Error("You don't have permission to control playback.")
            return
        }
        val hasActiveItem = !current.playback.queueItemId.isNullOrBlank()
        val activePos = if (hasActiveItem) {
            if (_localTrackAvailable.value && playbackManager.player.currentPosition >= 0L) {
                playbackManager.player.currentPosition.coerceAtLeast(0L)
            } else {
                current.playback.estimatedPosition()
            }
        } else 0L
        val targetItem = _queue.value.firstOrNull { it.id == current.playback.queueItemId }
            ?: _queue.value.firstOrNull()
        val nowMs = System.currentTimeMillis()
        val cmdId = UUID.randomUUID().toString()
        lastLocalCommandId = cmdId
        lastLocalCommandAtMs = nowMs
        anchoredPositionMs = activePos
        anchoredAtLocalWallMs = nowMs
        lastSeekOrApplyRealtimeMs = android.os.SystemClock.elapsedRealtime()

        if (targetItem != null) {
            val optimisticPlayback = current.playback.copy(
                sharedTrackId = targetItem.track.sharedTrackId,
                queueItemId = targetItem.id,
                isPlaying = isPlaying,
                positionMs = activePos,
                updatedAtMs = nowMs,
                commandId = cmdId,
                commandType = JamPermissionKind.PLAY_PAUSE.name,
                actorUid = uid
            )
            _session.value = current.copy(playback = optimisticPlayback)
            val local = matchLocalSong(targetItem.track, allSongs.value)
            if (local != null) {
                _localTrackAvailable.value = true
                lastAppliedTrackId = targetItem.track.sharedTrackId
                playbackManager.applyJamPlaybackSnapshot(
                    song = local,
                    positionMs = activePos,
                    isPlaying = isPlaying,
                    seekPosition = !hasActiveItem || kotlin.math.abs(playbackManager.player.currentPosition - activePos) > 120L
                )
            }
        }
        scope.launch {
            runCatching {
                val committed = repository.submitPlaybackCommand(
                    sessionId = current.id,
                    type = JamPermissionKind.PLAY_PAUSE,
                    isPlaying = isPlaying,
                    positionMs = activePos,
                    targetQueueItemId = targetItem?.id,
                    targetSharedTrackId = targetItem?.track?.sharedTrackId,
                    hasActiveItem = hasActiveItem,
                    commandId = cmdId,
                    isDirectSelect = false
                )
                lastAnchoredRevision = maxOf(lastAnchoredRevision, committed.revision)
                lastAppliedRevision = maxOf(lastAppliedRevision, committed.revision)
                val latest = _session.value
                if (latest != null && latest.id == current.id && committed.revision >= latest.playback.revision) {
                    _session.value = latest.copy(
                        playback = committed.copy(
                            positionMs = anchoredPositionMs,
                            updatedAtMs = anchoredAtLocalWallMs
                        )
                    )
                }
            }.onFailure { error ->
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't update Jam playback.")
            }
        }
    }

    fun skip(step: Int) {
        val current = _session.value ?: return
        val uid = authManager.currentUser?.uid.orEmpty()
        val permitted = current.started && current.permissions.allows(JamPermissionKind.SKIP_TRACK, uid, current.hostUid)
        if (!permitted) {
            _uiState.value = JamUiState.Error("You don't have permission to skip tracks.")
            return
        }
        val q = _queue.value
        if (q.isEmpty()) {
            _uiState.value = JamUiState.Error("Add a track to the shared queue first.")
            return
        }
        val activePos = if (_localTrackAvailable.value && playbackManager.player.currentPosition >= 0L) {
            playbackManager.player.currentPosition.coerceAtLeast(0L)
        } else {
            current.playback.estimatedPosition()
        }
        val currentIndex = q.indexOfFirst { it.id == current.playback.queueItemId }
        val direction = if (step < 0) -1 else 1

        val targetItem: JamQueueItem
        val targetPos: Long
        val targetPlaying: Boolean
        if (direction < 0 && current.playback.isPlaying && activePos > 3_000L && currentIndex >= 0) {
            targetItem = q[currentIndex]
            targetPos = 0L
            targetPlaying = true
        } else {
            val targetIndex = if (currentIndex < 0) 0 else currentIndex + direction
            when {
                targetIndex < 0 -> {
                    targetItem = q[currentIndex.coerceAtLeast(0)]
                    targetPos = 0L
                    targetPlaying = true
                }
                targetIndex >= q.size -> {
                    targetItem = q[currentIndex.coerceAtLeast(0)]
                    targetPos = activePos
                    targetPlaying = false
                }
                else -> {
                    targetItem = q[targetIndex]
                    targetPos = 0L
                    targetPlaying = true
                }
            }
        }

        val nowMs = System.currentTimeMillis()
        val cmdId = UUID.randomUUID().toString()
        lastLocalCommandId = cmdId
        lastLocalCommandAtMs = nowMs
        anchoredPositionMs = targetPos
        anchoredAtLocalWallMs = nowMs
        lastSeekOrApplyRealtimeMs = android.os.SystemClock.elapsedRealtime()
        lastHandledEndTrackId = null

        val optimisticPlayback = current.playback.copy(
            sharedTrackId = targetItem.track.sharedTrackId,
            queueItemId = targetItem.id,
            isPlaying = targetPlaying,
            positionMs = targetPos,
            updatedAtMs = nowMs,
            commandId = cmdId,
            commandType = JamPermissionKind.SKIP_TRACK.name,
            actorUid = uid
        )
        _session.value = current.copy(playback = optimisticPlayback)

        val targetSong = matchLocalSong(targetItem.track, allSongs.value)
        if (targetSong != null) {
            _localTrackAvailable.value = true
            lastAppliedTrackId = targetItem.track.sharedTrackId
            playbackManager.applyJamPlaybackSnapshot(
                song = targetSong,
                positionMs = targetPos,
                isPlaying = targetPlaying,
                seekPosition = true
            )
        } else {
            _localTrackAvailable.value = false
            playbackManager.pauseJamPlayback()
        }

        scope.launch {
            runCatching {
                val committed = repository.submitPlaybackCommand(
                    sessionId = current.id,
                    type = JamPermissionKind.SKIP_TRACK,
                    isPlaying = targetPlaying,
                    positionMs = targetPos,
                    step = direction,
                    targetQueueItemId = targetItem.id,
                    targetSharedTrackId = targetItem.track.sharedTrackId,
                    hasActiveItem = !current.playback.queueItemId.isNullOrBlank(),
                    commandId = cmdId,
                    isDirectSelect = false
                )
                lastAnchoredRevision = maxOf(lastAnchoredRevision, committed.revision)
                lastAppliedRevision = maxOf(lastAppliedRevision, committed.revision)
                val latest = _session.value
                if (latest != null && latest.id == current.id && committed.revision >= latest.playback.revision) {
                    _session.value = latest.copy(
                        playback = committed.copy(
                            positionMs = anchoredPositionMs,
                            updatedAtMs = anchoredAtLocalWallMs
                        )
                    )
                }
            }.onFailure { error ->
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't skip track.")
            }
        }
    }

    fun seek(positionMs: Long) {
        val current = _session.value ?: return
        val uid = authManager.currentUser?.uid.orEmpty()
        val safePos = positionMs.coerceAtLeast(0L)
        val permitted = current.started && current.permissions.allows(JamPermissionKind.SEEK, uid, current.hostUid)
        if (!permitted) {
            _uiState.value = JamUiState.Error("You don't have permission to seek playback.")
            return
        }
        val nowMs = System.currentTimeMillis()
        val cmdId = UUID.randomUUID().toString()
        lastLocalCommandId = cmdId
        lastLocalCommandAtMs = nowMs
        anchoredPositionMs = safePos
        anchoredAtLocalWallMs = nowMs
        lastSeekOrApplyRealtimeMs = android.os.SystemClock.elapsedRealtime()

        playbackManager.seekJamPlayback(safePos)
        _session.value = current.copy(
            playback = current.playback.copy(
                positionMs = safePos,
                updatedAtMs = nowMs,
                commandId = cmdId,
                commandType = JamPermissionKind.SEEK.name,
                actorUid = uid
            )
        )
        scope.launch {
            runCatching {
                val committed = repository.submitPlaybackCommand(
                    sessionId = current.id,
                    type = JamPermissionKind.SEEK,
                    positionMs = safePos,
                    hasActiveItem = !current.playback.queueItemId.isNullOrBlank(),
                    commandId = cmdId
                )
                lastAnchoredRevision = maxOf(lastAnchoredRevision, committed.revision)
                lastAppliedRevision = maxOf(lastAppliedRevision, committed.revision)
                val latest = _session.value
                if (latest != null && latest.id == current.id && committed.revision >= latest.playback.revision) {
                    _session.value = latest.copy(
                        playback = committed.copy(
                            positionMs = anchoredPositionMs,
                            updatedAtMs = anchoredAtLocalWallMs
                        )
                    )
                }
            }.onFailure { error ->
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't seek playback.")
            }
        }
    }

    fun closeJam() {
        val id = _sessionId.value ?: return
        runAction {
            repository.closeSession(id)
            stopObserving(restorePlayback = true)
            _page.value = JamPage.LANDING
            _uiState.value = JamUiState.Notice("Jam room closed for everyone.")
        }
    }

    fun leaveJam() {
        val id = _sessionId.value ?: return
        runAction {
            val result = repository.leave(id)
            stopObserving(restorePlayback = true)
            _page.value = JamPage.LANDING
            result.message?.let { _uiState.value = JamUiState.Notice(it) }
        }
    }

    /**
     * Automatically leaves the active Jam session when the user closes the app.
     * If the leaving user is the host and at least 2 listeners remain, the earliest listener
     * is promoted to host; if fewer than 2 listeners remain, the Jam room closes automatically.
     */
    fun onAppClosed() {
        val prefs = context.getSharedPreferences(TRANSFER_PREFS, Context.MODE_PRIVATE)
        val id = _sessionId.value ?: prefs.getString(KEY_ACTIVE_SESSION, null)
        if (id.isNullOrBlank()) return
        stopObserving(restorePlayback = true)
        _page.value = JamPage.LANDING
        appCloseScope.launch {
            withContext(NonCancellable) {
                runCatching { repository.leave(id) }
            }
        }
    }

    fun removeParticipant(participantUid: String) {
        val id = _sessionId.value ?: return
        runAction {
            val result = repository.removeParticipant(id, participantUid)
            if (result.sessionClosed) {
                stopObserving(restorePlayback = true)
                _page.value = JamPage.LANDING
            }
            _uiState.value = JamUiState.Notice(result.message ?: "Listener removed from the Jam.")
        }
    }

    suspend fun leaveBeforeSignOut() {
        val id = _sessionId.value
        if (id != null) runCatching { repository.leave(id) }
        stopObserving(restorePlayback = true)
        _page.value = JamPage.LANDING
    }

    fun revokeInvite() {
        val id = _sessionId.value ?: return
        runAction {
            repository.revokeInvite(id)
            _invite.value = _invite.value?.copy(url = null)
        }
    }

    fun heartbeatNow() {
        val id = _sessionId.value ?: return
        val account = currentAccountOrNull() ?: return
        scope.launch {
            runCatching {
                repository.heartbeat(id, resolvedDisplayName(account), account.photoUrl)
            }.onFailure { _uiState.value = JamUiState.Error(it.message ?: "Connection was interrupted.") }
        }
    }

    private fun activateSession(id: String, page: JamPage, restoringSavedSession: Boolean = false) {
        stopListenersOnly()
        soloCloseJob?.cancel()
        soloCloseJob = null
        if (_sessionId.value == null) previousPlayback = playbackManager.playbackState.value
        playbackManager.setJamPlaybackActive(true)
        playbackManager.setJamCommandHandler { action, value ->
            when (action) {
                com.example.service.JamPlaybackAction.PLAY -> setPlaybackPlaying(true)
                com.example.service.JamPlaybackAction.PAUSE -> setPlaybackPlaying(false)
                com.example.service.JamPlaybackAction.SKIP_NEXT -> skip(1)
                com.example.service.JamPlaybackAction.SKIP_PREVIOUS -> skip(-1)
                com.example.service.JamPlaybackAction.SEEK -> seek(value)
            }
        }
        playbackManager.pauseJamPlayback()
        _sessionId.value = id
        val accountUid = currentAccountOrNull()?.uid
        val prefs = context.getSharedPreferences(TRANSFER_PREFS, Context.MODE_PRIVATE)
        val isSameSavedSession = restoringSavedSession &&
            prefs.getString(KEY_ACTIVE_UID, null) == accountUid &&
            prefs.getString(KEY_ACTIVE_SESSION, null) == id
        val restoredInvite = if (isSameSavedSession) {
            prefs.getString(KEY_ACTIVE_INVITE_CODE, null)?.takeIf(String::isNotBlank)?.let { code ->
                JamInvite(id, code, prefs.getString(KEY_ACTIVE_INVITE_URL, null))
            }
        } else null
        _invite.value = restoredInvite
        currentAccountOrNull()?.uid?.let { uid ->
            prefs.edit().putString(KEY_ACTIVE_UID, uid).putString(KEY_ACTIVE_SESSION, id).apply()
        }
        if (!isSameSavedSession) {
            prefs.edit().remove(KEY_ACTIVE_INVITE_CODE).remove(KEY_ACTIVE_INVITE_URL).apply()
        }
        _session.value = null
        _participants.value = emptyList()
        _queue.value = emptyList()
        _recommendations.value = emptyList()
        _pendingVotes.value = emptyMap()
        _incomingVoteAlert.value = null
        notifiedRecommendationIds.clear()
        _featureRequests.value = emptyList()
        _remoteLibraryIndex.value = JamLibraryIndexSnapshot()
        _sharedLibraryMatches.value = emptyList()
        _libraryIndexStatus.value = JamLibraryIndexStatus()
        localLibraryEntries = emptyList()
        localLibraryReadyForSession = null
        _page.value = if (restoredInvite != null && page == JamPage.ACTIVE) JamPage.INVITE else page
        _uiState.value = JamUiState.Idle
        lastAppliedRevision = -1L
        lastAppliedTrackId = null
        lastHandledEndTrackId = null
        lastAnchoredRevision = -1L
        anchoredPositionMs = 0L
        anchoredAtLocalWallMs = 0L
        lastLocalCommandId = null
        lastLocalCommandAtMs = 0L
        lastSeekOrApplyRealtimeMs = 0L

        sessionListenerJob = scope.launch {
            var isInitialSessionEmission = true
            repository.observeSession(id)
                .catch { error ->
                    if (_sessionId.value == id) {
                        _uiState.value = JamUiState.Error(error.message ?: "The Jam session is no longer available.")
                        stopObserving(restorePlayback = true)
                        _page.value = JamPage.LANDING
                    }
                }
                .collectLatest { rawSession ->
                    val uid = authManager.currentUser?.uid
                    val session = if (rawSession != null) {
                        val nowMs = System.currentTimeMillis()
                        val rawPb = rawSession.playback
                        if (rawPb.revision != lastAnchoredRevision) {
                            val isOwnEcho = !rawPb.commandId.isNullOrBlank() && rawPb.commandId == lastLocalCommandId
                            lastAnchoredRevision = rawPb.revision
                            if (isOwnEcho) {
                                // Keep the local anchor set when the controller issued this command
                                lastAppliedRevision = maxOf(lastAppliedRevision, rawPb.revision)
                            } else if (isInitialSessionEmission) {
                                anchoredAtLocalWallMs = nowMs
                                anchoredPositionMs = if (rawPb.isPlaying && rawPb.updatedAtMs > 0L && (nowMs - rawPb.updatedAtMs) in 5_000L..600_000L) {
                                    rawPb.positionMs + (nowMs - rawPb.updatedAtMs)
                                } else {
                                    rawPb.positionMs
                                }
                            } else {
                                // Live remote command arrived over Firestore WebSocket (~80ms transit time).
                                // Anchor directly to this device's local clock so inter-device clock skew
                                // (1-5s difference between phones) NEVER causes 2-5s seeks or jumps.
                                anchoredAtLocalWallMs = nowMs
                                val transitCompensationMs = if (rawPb.isPlaying && rawPb.actorUid != uid) 80L else 0L
                                anchoredPositionMs = (rawPb.positionMs + transitCompensationMs).coerceAtLeast(0L)
                            }
                        }
                        rawSession.copy(
                            playback = rawPb.copy(
                                positionMs = anchoredPositionMs,
                                updatedAtMs = if (anchoredAtLocalWallMs > 0L) anchoredAtLocalWallMs else nowMs
                            )
                        )
                    } else null
                    isInitialSessionEmission = false
                    _session.value = session
                    if (session != null && uid != null && uid in session.kickedUids) {
                        val removalMessage = session.announcement?.forUser(uid)
                            ?: "The host removed you from this Jam. You cannot rejoin this Jam room."
                        stopObserving(restorePlayback = true)
                        _page.value = JamPage.LANDING
                        _uiState.value = JamUiState.Notice(removalMessage)
                        return@collectLatest
                    }
                    if (session == null || !session.isActive) {
                        if (_sessionId.value == id) {
                            _uiState.value = JamUiState.Notice(
                                session?.announcement?.forUser(authManager.currentUser?.uid.orEmpty()) ?: "This Jam has ended."
                            )
                            stopObserving(restorePlayback = true)
                            _page.value = JamPage.LANDING
                        }
                    } else {
                        val announcement = session.announcement
                        if (announcement != null && announcement.createdAtMs > lastAnnouncementAtMs &&
                            System.currentTimeMillis() - announcement.createdAtMs < 5 * 60_000L) {
                            lastAnnouncementAtMs = announcement.createdAtMs
                            _uiState.value = JamUiState.Notice(announcement.forUser(uid.orEmpty()))
                        }
                        if (session.started && session.participantCount < 2) {
                            _page.value = JamPage.LANDING
                            _uiState.value = JamUiState.Notice("This Jam is closing because fewer than two listeners remain.")
                        } else if (!session.started && session.hostUid == uid && _page.value == JamPage.ACTIVE) {
                            _page.value = JamPage.INVITE
                        } else if (session.started && _page.value == JamPage.INVITE) {
                            _page.value = JamPage.ACTIVE
                        }
                        if (session.started && session.participantCount < 2 && soloCloseJob?.isActive != true) {
                            soloCloseJob = scope.launch {
                                runCatching { repository.closeIfStartedWithOneListener(id) }
                                    .onFailure { _uiState.value = JamUiState.Error(it.message ?: "Couldn't close an empty Jam room.") }
                            }
                        }
                    }
                }
        }
        queueListenerJob = scope.launch {
            repository.observeQueue(id)
                .catch { error -> handleListenerFailure(id, error) }
                .collectLatest { incoming ->
                    val expectedIds = pendingQueueOrderIds
                    val incomingIds = incoming.map { it.id }
                    if (expectedIds != null) {
                        if (incomingIds == expectedIds) {
                            pendingQueueOrderIds = null
                            _queue.value = incoming
                        } else if (
                            System.currentTimeMillis() - pendingQueueOrderAtMs < 4_500L &&
                            incomingIds.size == expectedIds.size &&
                            incomingIds.toSet() == expectedIds.toSet()
                        ) {
                            val byId = incoming.associateBy { it.id }
                            _queue.value = expectedIds.mapIndexedNotNull { idx, itemId ->
                                byId[itemId]?.copy(position = idx.toLong())
                            }
                        } else {
                            pendingQueueOrderIds = null
                            _queue.value = incoming
                        }
                    } else {
                        _queue.value = incoming
                    }
                }
        }
        participantListenerJob = scope.launch {
            repository.observeParticipants(id)
                .catch { error -> handleListenerFailure(id, error) }
                .collectLatest {
                    _participants.value = it
                    updateSharedLibraryMatches()
                }
        }
        libraryIndexListenerJob = scope.launch {
            repository.observeSharedLibraryIndex(id)
                .catch { error ->
                    if (_sessionId.value == id) {
                        _libraryIndexStatus.value = _libraryIndexStatus.value.copy(
                            error = error.message ?: "Couldn't read the shared library index."
                        )
                    }
                }
                .collectLatest {
                    _remoteLibraryIndex.value = it
                    updateSharedLibraryMatches()
                }
        }
        recommendationListenerJob = scope.launch {
            var isFirstEmission = true
            repository.observeRecommendations(id)
                .catch { error -> handleListenerFailure(id, error) }
                .collectLatest { recommendations ->
                    _recommendations.value = recommendations
                    val uid = authManager.currentUser?.uid
                    if (uid != null) {
                        // Clear any pending optimistic votes that have now been confirmed by Firestore
                        val confirmedIds = recommendations.filter { it.votes[uid] != JamVote.PENDING || !it.isVoting }.map { it.id }.toSet()
                        if (confirmedIds.isNotEmpty() && _pendingVotes.value.keys.any { it in confirmedIds }) {
                            _pendingVotes.value = _pendingVotes.value - confirmedIds
                        }
                        // Clear incoming alert if the recommendation is no longer waiting for this user's vote
                        val currentAlertId = _incomingVoteAlert.value?.recommendationId
                        if (currentAlertId != null) {
                            val rec = recommendations.firstOrNull { it.id == currentAlertId }
                            if (rec == null || !rec.isVoting || rec.votes[uid] != JamVote.PENDING) {
                                _incomingVoteAlert.value = null
                            }
                        }
                        val nowMs = System.currentTimeMillis()
                        recommendations.forEach { rec ->
                            if (isFirstEmission && rec.deadlineAtMs - nowMs < 2_000L) {
                                notifiedRecommendationIds.add(rec.id)
                                return@forEach
                            }
                            if (rec.isVoting && rec.proposerUid != uid && rec.votes[uid] == JamVote.PENDING &&
                                nowMs < rec.deadlineAtMs && notifiedRecommendationIds.add(rec.id)) {
                                val proposer = _participants.value.firstOrNull { it.uid == rec.proposerUid }?.displayName
                                    ?.takeIf(String::isNotBlank) ?: rec.proposerName.ifBlank { "A listener" }
                                val messageBody = "$proposer has recommended a song & is waiting for your vote approval"
                                if (!MainActivity.isActivityAlive) {
                                    OffGridFirebaseMessagingService.showJamRecommendationNotification(
                                        context = context.applicationContext,
                                        title = "OffGrid Jam · Vote Requested",
                                        body = "$messageBody (${rec.track.title})",
                                        sessionId = id,
                                        recommendationId = rec.id,
                                        kind = if (rec.type == JamRecommendationType.LOCAL_SONG) "jam_local_song" else "jam_recommendation",
                                        recipientUid = uid
                                    )
                                } else {
                                    _incomingVoteAlert.value = JamIncomingVoteAlert(
                                        recommendationId = rec.id,
                                        proposerName = proposer,
                                        trackTitle = rec.track.title,
                                        trackArtist = rec.track.artist,
                                        isLocalSong = rec.type == JamRecommendationType.LOCAL_SONG
                                    )
                                }
                            }
                        }
                    }
                    isFirstEmission = false
                    syncLocalTransfers(recommendations)
                }
        }
        featureRequestListenerJob = scope.launch {
            repository.observeFeatureRequests(id)
                .catch { error -> handleListenerFailure(id, error) }
                .collectLatest { _featureRequests.value = it }
        }
        heartbeatJob = scope.launch {
            while (_sessionId.value == id) {
                heartbeatNow()
                delay(120_000L)
            }
        }
        driftJob = scope.launch {
            while (_sessionId.value == id) {
                delay(DRIFT_CHECK_INTERVAL_MS)
                correctPlaybackDrift()
            }
        }
    }

    private fun applyPlaybackSnapshot(session: JamSession?, queue: List<JamQueueItem>, songs: List<Song>) {
        if (session == null || !session.isActive || _sessionId.value != session.id) return
        val playback = session.playback
        val item = queue.firstOrNull { it.id == playback.queueItemId }
            ?: playback.sharedTrackId?.takeIf(String::isNotBlank)?.let { tid -> queue.firstOrNull { it.track.sharedTrackId == tid } }
            ?: queue.firstOrNull()
        val track = item?.track ?: return
        val trackId = track.sharedTrackId.takeIf(String::isNotBlank) ?: return
        val local = matchLocalSong(track, songs)
        if (local == null) {
            _localTrackAvailable.value = false
            if (lastAppliedTrackId != trackId) playbackManager.pauseJamPlayback()
            lastAppliedTrackId = trackId
            lastAppliedRevision = playback.revision
            return
        }
        val wasUnavailable = !_localTrackAvailable.value
        _localTrackAvailable.value = true
        val isOwnAlreadyApplied = !playback.commandId.isNullOrBlank() &&
            playback.commandId == lastLocalCommandId &&
            lastAppliedTrackId == trackId &&
            !wasUnavailable
        if (isOwnAlreadyApplied) {
            lastAppliedRevision = maxOf(lastAppliedRevision, playback.revision)
            return
        }
        val isNewTrack = lastAppliedTrackId != trackId
        val isNewRevision = playback.revision > lastAppliedRevision
        if (!isNewTrack && !isNewRevision && !wasUnavailable) return
        lastAppliedTrackId = trackId
        lastAppliedRevision = playback.revision
        if (isNewTrack) lastHandledEndTrackId = null

        val expectedPosition = playback.estimatedPosition()
        val currentPlayerPos = playbackManager.player.currentPosition.coerceAtLeast(0L)
        val driftFromExpected = kotlin.math.abs(currentPlayerPos - expectedPosition)
        val seekPosition = isNewTrack ||
            wasUnavailable ||
            playback.commandType == JamPermissionKind.SEEK.name ||
            playback.commandType == JamPermissionKind.SKIP_TRACK.name ||
            (!playback.isPlaying && driftFromExpected > 80L) ||
            (playback.isPlaying && driftFromExpected > 180L)
        if (seekPosition) {
            lastSeekOrApplyRealtimeMs = android.os.SystemClock.elapsedRealtime()
        }
        runCatching { playbackManager.player.setPlaybackSpeed(1f) }
        playbackManager.applyJamPlaybackSnapshot(local, expectedPosition, playback.isPlaying, seekPosition)
    }

    private fun correctPlaybackDrift() {
        val session = _session.value ?: return
        val playback = session.playback
        val trackId = playback.sharedTrackId ?: return
        if (!playback.isPlaying || trackId != lastAppliedTrackId || !_localTrackAvailable.value) return
        val player = playbackManager.player
        if (player.playbackState != Player.STATE_READY || !player.isPlaying) return
        val nowRealtime = android.os.SystemClock.elapsedRealtime()
        // Allow ExoPlayer to settle after any track switch, resume, or explicit seek so it never oscillates
        if (nowRealtime - lastSeekOrApplyRealtimeMs < 3_500L) return

        val expected = playback.estimatedPosition()
        val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        if (expected >= duration - 1_500L) return
        val actual = player.currentPosition.coerceAtLeast(0L)
        val drift = expected - actual
        val absolute = kotlin.math.abs(drift)
        if (absolute >= SEEK_DRIFT_THRESHOLD_MS) {
            lastSeekOrApplyRealtimeMs = nowRealtime
            runCatching { player.setPlaybackSpeed(1f) }
            playbackManager.seekJamPlayback(expected)
        } else if (absolute >= SPEED_CORRECTION_THRESHOLD_MS) {
            val adjustedSpeed = if (drift > 0L) 1.02f else 0.98f
            runCatching { player.setPlaybackSpeed(adjustedSpeed) }
            scope.launch {
                delay(SPEED_CORRECTION_DURATION_MS)
                runCatching { player.setPlaybackSpeed(1f) }
            }
        } else if (player.playbackParameters.speed != 1f) {
            runCatching { player.setPlaybackSpeed(1f) }
        }
    }

    private data class LocalLibraryEntry(
        val song: Song,
        val track: JamTrack,
        val audioFingerprint: String?,
        val matchKeys: List<String>
    )

    private suspend fun indexLocalLibrary(sessionId: String?, songs: List<Song>) {
        if (sessionId.isNullOrBlank()) {
            localLibraryEntries = emptyList()
            localLibraryReadyForSession = null
            _sharedLibraryMatches.value = emptyList()
            _libraryIndexStatus.value = JamLibraryIndexStatus()
            return
        }

        delay(650L) // Let MediaStore scans settle before rebuilding the private Jam index.
        val eligibleSongs = songs.filter { !it.isMissing && !it.isCorrupted && it.durationMs > 0L }
        localLibraryEntries = emptyList()
        localLibraryReadyForSession = null
        _sharedLibraryMatches.value = emptyList()
        _libraryIndexStatus.value = JamLibraryIndexStatus(
            localTotalTracks = eligibleSongs.size,
            participantCount = _participants.value.count { it.isConnected }
        )

        val entries = ArrayList<LocalLibraryEntry>(eligibleSongs.size)
        eligibleSongs.forEachIndexed { index, song ->
            val audioFingerprint = JamLibraryFingerprint.forSong(context, song)
            val track = trackFrom(song, audioFingerprint)
            if (track != null) {
                val keys = jamLibraryMatchKeys(track, audioFingerprint, sessionId)
                if (keys.isNotEmpty()) entries += LocalLibraryEntry(song, track, audioFingerprint, keys)
            }
            if ((index + 1) % 20 == 0 || index == eligibleSongs.lastIndex) {
                _libraryIndexStatus.value = _libraryIndexStatus.value.copy(localIndexedTracks = index + 1)
            }
        }

        if (_sessionId.value != sessionId) return
        val matchKeys = entries.flatMap { it.matchKeys }.distinct()
        runCatching {
            repository.publishSharedLibraryIndex(sessionId, eligibleSongs.size, matchKeys)
        }.onFailure { error ->
            if (_sessionId.value == sessionId) {
                _libraryIndexStatus.value = _libraryIndexStatus.value.copy(
                    localIndexedTracks = eligibleSongs.size,
                    error = error.message ?: "Couldn't publish this device's private matching index."
                )
            }
        }.onSuccess {
            if (_sessionId.value == sessionId) {
                localLibraryEntries = entries
                localLibraryReadyForSession = sessionId
                _libraryIndexStatus.value = _libraryIndexStatus.value.copy(
                    localIndexedTracks = eligibleSongs.size,
                    localTotalTracks = eligibleSongs.size,
                    error = null
                )
                updateSharedLibraryMatches()
                applyPlaybackSnapshot(_session.value, _queue.value, allSongs.value)
            }
        }
    }

    private fun updateSharedLibraryMatches() {
        val currentSessionId = _sessionId.value
        val currentParticipants = _participants.value.filter { it.isConnected }
        val uidList = currentParticipants.map { it.uid }.distinct()
        val index = _remoteLibraryIndex.value
        val readyParticipants = uidList.count { it in index.readyUids }
        val localReady = currentSessionId != null && localLibraryReadyForSession == currentSessionId
        val allReady = localReady && uidList.isNotEmpty() && readyParticipants == uidList.size
        val commonKeys = if (allReady) {
            uidList.mapNotNull(index.keysByUid::get).reduceOrNull { common, keys -> common intersect keys }.orEmpty()
        } else emptySet()

        _sharedLibraryMatches.value = if (allReady) {
            localLibraryEntries.mapNotNull { entry ->
                val commonKey = entry.matchKeys.firstOrNull { it in commonKeys } ?: return@mapNotNull null
                val sharedTrack = entry.track.copy(
                    sharedTrackId = commonKey,
                    audioFingerprint = null
                )
                JamSharedLibraryMatch(entry.song, sharedTrack, commonKey)
            }.distinctBy { it.matchKey }
        } else emptyList()

        _libraryIndexStatus.value = _libraryIndexStatus.value.copy(
            indexedParticipants = readyParticipants,
            participantCount = uidList.size,
            isReady = allReady
        )
    }

    private fun matchLocalSong(track: JamTrack, songs: List<Song>): Song? {
        val fingerprint = track.audioFingerprint?.takeIf(String::isNotBlank)
        if (fingerprint != null) {
            val fingerprintMatches = songs.filter { JamLibraryFingerprint.cached(context, it) == fingerprint }
            fingerprintMatches.singleOrNull()?.let { return it }
            if (fingerprintMatches.size > 1) return null
        }
        val trackPrefix = track.sharedTrackId.take(24)
        if (!track.checksum.isNullOrBlank()) {
            val checksumPrefix = track.checksum.take(16)
            songs.firstOrNull { song ->
                song.path.contains(trackPrefix) && song.path.contains(checksumPrefix) && java.io.File(song.path).isFile
            }?.let { return it }
        }
        if (trackPrefix.isNotBlank()) {
            songs.firstOrNull { song ->
                song.path.contains("jam_shared") && song.path.contains(trackPrefix) && java.io.File(song.path).isFile
            }?.let { return it }
            val sharedDir = File(context.filesDir, "jam_shared")
            if (sharedDir.isDirectory) {
                val downloadedFile = sharedDir.listFiles()?.firstOrNull { file ->
                    file.isFile && file.length() > 0L && file.name.startsWith("$trackPrefix-") && !file.name.endsWith(".part")
                }
                if (downloadedFile != null) {
                    val fileUri = Uri.fromFile(downloadedFile).toString()
                    return Song(
                        id = (downloadedFile.absolutePath.hashCode().toLong() and 0x7fffffffL) + 900_000_000L,
                        title = track.title,
                        artist = track.artist,
                        album = track.album.ifBlank { "Jam Shared" },
                        durationMs = track.durationMs,
                        contentUriString = fileUri,
                        artworkUri = null,
                        path = downloadedFile.absolutePath,
                        dateAdded = downloadedFile.lastModified() / 1000L
                    )
                }
            }
        }
        val idMatch = songs.firstOrNull {
            val localTrack = trackFrom(it, JamLibraryFingerprint.cached(context, it)) ?: return@firstOrNull false
            localTrack.sharedTrackId == track.sharedTrackId ||
                track.sharedTrackId in jamLibraryMatchKeys(localTrack, JamLibraryFingerprint.cached(context, it), _sessionId.value.orEmpty())
        }
        if (idMatch != null) return idMatch
        val title = normalizeMatchTitle(track.title)
        val artist = normalize(TagCleaner.cleanArtist(track.artist))
        val album = normalize(TagCleaner.cleanAlbum(track.album))
        if (artist.isBlank() || track.durationMs <= 0L) return null
        val candidates = songs.filter { song ->
            val localTitle = normalizeMatchTitle(song.displayTitle)
            val localArtist = normalize(TagCleaner.cleanArtist(song.displayArtist))
            val localAlbum = normalize(TagCleaner.cleanAlbum(song.displayAlbum))
            val durationDiff = kotlin.math.abs(song.durationMs - track.durationMs)
            val albumCompatible = album == localAlbum || isUnknownAlbum(album) || isUnknownAlbum(localAlbum)
            title.isNotBlank() && localTitle == title && localArtist == artist && albumCompatible && durationDiff <= MAX_MATCH_DURATION_DIFF_MS
        }
        return candidates.singleOrNull()
    }

    private fun trackFrom(song: Song, audioFingerprint: String? = null): JamTrack? {
        if (song.isMissing || song.isCorrupted || song.durationMs <= 0L) return null
        val title = TagCleaner.cleanTitle(song.displayTitle, "").trim().ifBlank { song.displayTitle.trim() }
        val artist = TagCleaner.cleanArtist(song.displayArtist).trim().ifBlank { song.displayArtist.trim() }
        if (title.isBlank() || (artist.isBlank() && audioFingerprint.isNullOrBlank())) return null
        val candidate = JamTrack(
            sharedTrackId = "",
            title = title.take(180),
            artist = artist.ifBlank { "Unknown Artist" }.take(180),
            album = TagCleaner.cleanAlbum(song.displayAlbum).trim().take(180),
            durationMs = song.durationMs,
            audioFingerprint = audioFingerprint
        )
        return candidate.copy(sharedTrackId = sharedTrackId(candidate))
    }

    private fun normalizeMatchTitle(value: String): String = value.lowercase()
        .replace(Regex("\\.(mp3|m4a|mp4|aac|flac|ogg|wav)$"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .dropLastWhile { it in setOf("audio", "video", "song", "lyrics", "lyric", "official", "tamil", "telugu", "malayalam", "hindi", "mp3", "mp4") }
        .joinToString(" ")

    private fun stopObserving(restorePlayback: Boolean) {
        stopListenersOnly()
        _sessionId.value = null
        _session.value = null
        _participants.value = emptyList()
        _queue.value = emptyList()
        _recommendations.value = emptyList()
        _pendingVotes.value = emptyMap()
        _incomingVoteAlert.value = null
        notifiedRecommendationIds.clear()
        _featureRequests.value = emptyList()
        _remoteLibraryIndex.value = JamLibraryIndexSnapshot()
        _sharedLibraryMatches.value = emptyList()
        _libraryIndexStatus.value = JamLibraryIndexStatus()
        localLibraryEntries = emptyList()
        localLibraryReadyForSession = null
        _invite.value = null
        _localTrackAvailable.value = true
        if (restorePlayback) {
            playbackManager.restorePlaybackAfterJam(previousPlayback)
            previousPlayback = null
        }
        context.getSharedPreferences(TRANSFER_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACTIVE_UID)
            .remove(KEY_ACTIVE_SESSION)
            .remove(KEY_ACTIVE_INVITE_CODE)
            .remove(KEY_ACTIVE_INVITE_URL)
            .apply()
        lastAppliedRevision = -1L
        lastAppliedTrackId = null
    }

    private fun stopListenersOnly() {
        sessionListenerJob?.cancel(); sessionListenerJob = null
        queueListenerJob?.cancel(); queueListenerJob = null
        participantListenerJob?.cancel(); participantListenerJob = null
        recommendationListenerJob?.cancel(); recommendationListenerJob = null
        libraryIndexListenerJob?.cancel(); libraryIndexListenerJob = null
        featureRequestListenerJob?.cancel(); featureRequestListenerJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        driftJob?.cancel(); driftJob = null
        soloCloseJob?.cancel(); soloCloseJob = null
    }

    private fun handleListenerFailure(sessionId: String, error: Throwable) {
        if (_sessionId.value != sessionId) return
        val uid = authManager.currentUser?.uid.orEmpty()
        val current = _session.value
        val wasRemoved = uid.isNotBlank() && current?.kickedUids?.contains(uid) == true
        val message = if (wasRemoved) {
            current?.announcement?.forUser(uid) ?: "The host removed you from this Jam. You cannot rejoin this Jam room."
        } else {
            error.message?.takeIf(String::isNotBlank) ?: "Jam access ended. Your playback has been restored."
        }
        stopObserving(restorePlayback = true)
        _page.value = JamPage.LANDING
        _uiState.value = if (wasRemoved) JamUiState.Notice(message) else JamUiState.Error(message)
    }

    private fun currentAccountOrNull(): UserAccount? = userAccount.value ?: authManager.currentUser?.let {
        UserAccount(uid = it.uid, email = it.email, displayName = it.displayName, photoUrl = it.photoUrl?.toString())
    }

    private fun resolvedDisplayName(account: UserAccount): String {
        val appName = displayName.value.trim()
            .takeIf { it.isNotBlank() && !it.equals("Music Lover", ignoreCase = true) }
        return appName
            ?: account.displayName?.trim()?.takeIf(String::isNotBlank)
            ?: account.email?.substringBefore("@")?.trim()?.takeIf(String::isNotBlank)
            ?: "Listener"
    }

    private fun syncLocalTransfers(recommendations: List<JamRecommendation>) {
        val uid = authManager.currentUser?.uid ?: return
        val prefs = context.getSharedPreferences(TRANSFER_PREFS, Context.MODE_PRIVATE)
        val sessionId = _sessionId.value ?: return
        recommendations.forEach { recommendation ->
            if (recommendation.type != JamRecommendationType.LOCAL_SONG) return@forEach
            if (recommendation.proposerUid == uid && recommendation.status == "READY") {
                prefs.edit()
                    .remove("source:${recommendation.id}")
                    .remove("mime:${recommendation.id}")
                    .remove("source_by_track:${recommendation.track.sharedTrackId}")
                    .remove("mime_by_track:${recommendation.track.sharedTrackId}")
                    .apply()
            }
            if (recommendation.proposerUid == uid && (recommendation.status in setOf("APPROVED_PENDING_UPLOAD", "UPLOADING") ||
                    recommendation.uploadStatus == "RETRY_REQUIRED")) {
                val uriStr = prefs.getString("source:${recommendation.id}", null)
                    ?: prefs.getString("source_by_track:${recommendation.track.sharedTrackId}", null)
                val uri = uriStr?.let { Uri.parse(it) }
                val mime = prefs.getString("mime:${recommendation.id}", null)
                    ?: prefs.getString("mime_by_track:${recommendation.track.sharedTrackId}", null)
                    ?: "audio/mpeg"
                if (uri != null) {
                    scope.launch(Dispatchers.IO) {
                        JamTransferWork.executeUploadDirect(
                            context = context,
                            sessionId = sessionId,
                            recommendationId = recommendation.id,
                            sourceUri = uri,
                            mimeType = mime,
                            failOnFinalError = false
                        )
                    }
                    JamTransferWork.enqueueUpload(context, sessionId, recommendation.id, uri, mime)
                }
            }
            val ownDownload = recommendation.downloads[uid]
            if (uid != recommendation.proposerUid &&
                recommendation.status in setOf("APPROVED_PENDING_UPLOAD", "UPLOADING", "DOWNLOADING") &&
                recommendation.votes[uid] == JamVote.ACCEPT &&
                (ownDownload == null || ownDownload.status in setOf("WAITING_TO_DOWNLOAD", "DOWNLOADING", "WAITING_FOR_UPLOAD"))
            ) {
                val checksum = recommendation.checksum.orEmpty()
                val fileSize = recommendation.fileSize
                scope.launch(Dispatchers.IO) {
                    val ok = JamTransferWork.executeDownloadDirect(
                        context = context,
                        sessionId = sessionId,
                        recommendationId = recommendation.id,
                        expectedChecksum = checksum,
                        track = recommendation.track,
                        expectedFileSize = fileSize,
                        contentType = recommendation.contentType,
                        failOnFinalError = false
                    )
                    if (ok) {
                        withContext(Dispatchers.Main) {
                            applyPlaybackSnapshot(_session.value, _queue.value, allSongs.value)
                        }
                    }
                }
                if (checksum.isNotBlank() && fileSize > 0L) {
                    JamTransferWork.enqueueDownload(context, sessionId, recommendation.id, checksum, recommendation.track, fileSize, recommendation.contentType)
                }
            }
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        scope.launch {
            _uiState.value = JamUiState.Loading
            try {
                block()
                if (_uiState.value !is JamUiState.Error && _uiState.value !is JamUiState.Notice) {
                    _uiState.value = JamUiState.Idle
                }
            } catch (error: Throwable) {
                _uiState.value = JamUiState.Error(error.message ?: "Jam couldn't complete that action.")
            }
        }
    }

    private fun normalize(value: String) = value.trim().lowercase().replace(Regex("\\s+"), " ")
    private fun isUnknownAlbum(value: String) = value.isBlank() || value.contains("unknown") || value == "-"

    companion object {
        @Volatile
        var activeInstance: JamFeatureController? = null
            private set

        private val appCloseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val TRANSFER_PREFS = "offgrid_jam_transfers"
        private const val KEY_ACTIVE_UID = "active_user_id"
        private const val KEY_ACTIVE_SESSION = "active_session_id"
        private const val KEY_ACTIVE_INVITE_CODE = "active_invite_code"
        private const val KEY_ACTIVE_INVITE_URL = "active_invite_url"
        private const val MAX_MATCH_DURATION_DIFF_MS = 4_000L
        private const val DRIFT_CHECK_INTERVAL_MS = 3_000L
        private const val SEEK_DRIFT_THRESHOLD_MS = 1_200L
        private const val SPEED_CORRECTION_THRESHOLD_MS = 120L
        private const val SPEED_CORRECTION_DURATION_MS = 900L
    }
}
