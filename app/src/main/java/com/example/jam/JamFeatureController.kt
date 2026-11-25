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
    private val _syncStatus = MutableStateFlow(JamSyncStatus())
    val syncStatus: StateFlow<JamSyncStatus> = _syncStatus.asStateFlow()
    private val _currentUid = MutableStateFlow(authManager.currentUser?.uid)
    val currentUid: StateFlow<String?> = _currentUid.asStateFlow()
    private val _selectedRecommendationId = MutableStateFlow<String?>(null)
    val selectedRecommendationId: StateFlow<String?> = _selectedRecommendationId.asStateFlow()
    private val _sharedLibraryMatches = MutableStateFlow<List<JamSharedLibraryMatch>>(emptyList())
    val sharedLibraryMatches: StateFlow<List<JamSharedLibraryMatch>> = _sharedLibraryMatches.asStateFlow()
    private val _libraryIndexStatus = MutableStateFlow(JamLibraryIndexStatus())
    val libraryIndexStatus: StateFlow<JamLibraryIndexStatus> = _libraryIndexStatus.asStateFlow()
    private val _remoteLibraryIndex = MutableStateFlow(JamLibraryIndexSnapshot())
    private val localLibraryEntriesLock = Any()
    private val appUpdateRepository = com.example.update.AppUpdateRepository.getInstance(context)
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
    private var clockCalibrationJob: Job? = null
    private var driftJob: Job? = null
    private var soloCloseJob: Job? = null
    private var lastAppliedRevision = -1L
    private var lastAppliedTrackId: String? = null
    private var lastHandledEndQueueItemId: String? = null
    private var lastAnchoredRevision = -1L
    private var anchoredPositionMs = 0L
    private var anchoredAtLocalElapsedMs = 0L
    private var anchoredAtLocalWallMs = 0L
    private var anchoredServerEpochMs = 0L
    @Volatile private var serverClockOffsetMs = 0L
    @Volatile private var isClockCalibrated = false
    private var lastLocalCommandId: String? = null
    private var lastLocalCommandAtMs = 0L
    private var pendingLocalCommandId: String? = null
    private var pendingLocalBaseRevision = -1L
    private var pendingLocalCommandElapsedMs = 0L
    private var pendingLocalPlayback: JamPlaybackState? = null
    private var lastSeekOrApplyRealtimeMs = 0L
    private var readySnapDoneForRevision = -1L
    private var restoredForUid: String? = null
    private var lastAnnouncementAtMs: Long = 0L
    @Volatile private var pendingQueueOrderIds: List<String>? = null
    @Volatile private var pendingQueueOrderAtMs: Long = 0L
    private var lastIndexedLibrarySignature: String? = null
    private val matchedSongCache = java.util.concurrent.ConcurrentHashMap<String, Song>()
    private val handlingEnd = AtomicBoolean(false)

    private fun currentServerClockMs(): Long =
        if (isClockCalibrated) android.os.SystemClock.elapsedRealtime() + serverClockOffsetMs
        else System.currentTimeMillis()

    /**
     * Computes the exact live playback position in ms using our monotonic clock (server-calibrated
     * when available, or monotonic arrival-anchored fallback), completely immune to device wall-clock jumps.
     */
    fun currentEstimatedPlaybackPositionMs(playback: JamPlaybackState? = _session.value?.playback): Long {
        val pb = playback ?: return 0L
        if (!pb.isPlaying) return pb.positionMs.coerceAtLeast(0L)
        if (isClockCalibrated && anchoredServerEpochMs > 0L) {
            val serverNow = currentServerClockMs()
            return (anchoredPositionMs + ((serverNow - anchoredServerEpochMs).coerceAtLeast(0L) * pb.playbackSpeed).toLong())
                .coerceAtLeast(0L)
        }
        if (anchoredAtLocalElapsedMs > 0L) {
            val elapsed = (android.os.SystemClock.elapsedRealtime() - anchoredAtLocalElapsedMs).coerceAtLeast(0L)
            return (anchoredPositionMs + (elapsed * pb.playbackSpeed).toLong()).coerceAtLeast(0L)
        }
        return pb.estimatedPosition()
    }

    private fun syncJamControlPermissions(session: JamSession?) {
        val uid = authManager.currentUser?.uid.orEmpty()
        if (session == null || !session.isActive || !session.started || uid.isBlank()) {
            playbackManager.updateJamControlPermissions(
                canPlayPause = false,
                canSkip = false,
                canSeek = false
            )
            return
        }
        val perms = session.permissions
        playbackManager.updateJamControlPermissions(
            canPlayPause = perms.allows(JamPermissionKind.PLAY_PAUSE, uid, session.hostUid),
            canSkip = perms.allows(JamPermissionKind.SKIP_TRACK, uid, session.hostUid),
            canSeek = perms.allows(JamPermissionKind.SEEK, uid, session.hostUid)
        )
    }

    private val endedListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                val current = _session.value ?: return
                val pb = current.playback
                if (_sessionId.value == current.id &&
                    pb.isPlaying &&
                    _localTrackAvailable.value &&
                    pb.sharedTrackId == lastAppliedTrackId &&
                    readySnapDoneForRevision != pb.revision
                ) {
                    readySnapDoneForRevision = pb.revision
                    val expected = currentEstimatedPlaybackPositionMs(pb)
                    val actual = playbackManager.player.currentPosition.coerceAtLeast(0L)
                    val drift = expected - actual
                    if (kotlin.math.abs(drift) > 35L) {
                        lastSeekOrApplyRealtimeMs = android.os.SystemClock.elapsedRealtime()
                        playbackManager.seekJamPlayback((expected + AUDIO_SEEK_PIPELINE_LEAD_MS).coerceAtLeast(0L))
                    }
                }
                return
            }
            if (playbackState != Player.STATE_ENDED) return
            val current = _session.value ?: return
            if (authManager.currentUser?.uid != current.hostUid) return
            val endKey = current.playback.queueItemId?.takeIf(String::isNotBlank)
                ?: current.playback.sharedTrackId
                ?: return
            if (lastHandledEndQueueItemId == endKey || !handlingEnd.compareAndSet(false, true)) return
            lastHandledEndQueueItemId = endKey
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
        val knownUpdate = appUpdateRepository.updateState.value as? com.example.update.UpdateCheckResult.UpdateAvailable
        if (knownUpdate != null) {
            _uiState.value = buildLocalUpdateRequiredState(knownUpdate.release)
            return false
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
        val knownUpdate = appUpdateRepository.updateState.value as? com.example.update.UpdateCheckResult.UpdateAvailable
        if (knownUpdate != null) {
            _uiState.value = buildLocalUpdateRequiredState(knownUpdate.release)
            return false
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
        if (_uiState.value is JamUiState.Error || _uiState.value is JamUiState.VersionMismatch) {
            _uiState.value = JamUiState.Idle
        }
    }

    fun cancelPendingAction() {
        pendingAction = null
    }

    private fun buildLocalUpdateRequiredState(release: com.example.update.ReleaseInfo): JamUiState.VersionMismatch {
        val currentDisplay = appUpdateRepository.installedVersion.displayString
        val latestDisplay = release.version.displayString
        return JamUiState.VersionMismatch(
            message = "Update required: You are using OffGrid Music $currentDisplay, while $latestDisplay is the latest release. Both users must have the same latest version installed to create or join a Jam.",
            currentVersion = currentDisplay,
            requiredVersion = latestDisplay,
            needsLocalUpdate = true,
            releaseUrl = release.apkDownloadUrl?.takeIf(String::isNotBlank) ?: release.htmlUrl
        )
    }

    private suspend fun ensureLatestVersionForJam(): Boolean {
        val checkResult = appUpdateRepository.verifyLatestVersionForJam()
        if (checkResult is com.example.update.UpdateCheckResult.UpdateAvailable) {
            _uiState.value = buildLocalUpdateRequiredState(checkResult.release)
            return false
        }
        return true
    }

    fun createJam(permissions: JamPermissions, maxParticipants: Int) {
        val account = currentAccountOrNull() ?: run { requestCreate(); return }
        runAction {
            if (!ensureLatestVersionForJam()) return@runAction
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
            if (!ensureLatestVersionForJam()) return@runAction
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

    private fun optimisticallyInsertTrackInQueue(
        track: JamTrack,
        insertNext: Boolean,
        playImmediately: Boolean,
        commandId: String? = null,
        anchorServerMs: Long = 0L
    ) {
        val currentQueue = _queue.value
        val existingIdx = currentQueue.indexOfFirst {
            it.track.sharedTrackId == track.sharedTrackId ||
                (it.track.title.equals(track.title, ignoreCase = true) && it.track.artist.equals(track.artist, ignoreCase = true))
        }
        val activeId = _session.value?.playback?.queueItemId
        val updatedQueue = if (existingIdx >= 0) {
            val existingItem = currentQueue[existingIdx]
            if (insertNext && existingItem.id != activeId) {
                val remaining = currentQueue.filterIndexed { i, _ -> i != existingIdx }.toMutableList()
                val activeIdx = remaining.indexOfFirst { it.id == activeId }
                val targetPos = if (remaining.isEmpty()) 0 else if (activeIdx >= 0) activeIdx + 1 else 1.coerceAtMost(remaining.size)
                remaining.add(targetPos, existingItem)
                remaining.mapIndexed { idx, item -> item.copy(position = idx.toLong()) }
            } else {
                currentQueue
            }
        } else {
            val tempItem = JamQueueItem(
                id = "optimistic_${UUID.randomUUID()}",
                track = track,
                position = currentQueue.size.toLong(),
                addedByUid = authManager.currentUser?.uid.orEmpty()
            )
            if (insertNext) {
                val list = currentQueue.toMutableList()
                val activeIdx = list.indexOfFirst { it.id == activeId }
                val targetPos = if (list.isEmpty()) 0 else if (activeIdx >= 0) activeIdx + 1 else 1.coerceAtMost(list.size)
                list.add(targetPos, tempItem)
                list.mapIndexed { idx, item -> item.copy(position = idx.toLong()) }
            } else {
                (currentQueue + tempItem).mapIndexed { idx, item -> item.copy(position = idx.toLong()) }
            }
        }
        _queue.value = updatedQueue
        if (playImmediately) {
            val targetItem = updatedQueue.firstOrNull { it.track.sharedTrackId == track.sharedTrackId } ?: return
            val currentSession = _session.value ?: return
            val nowMs = System.currentTimeMillis()
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            val cmdId = commandId ?: UUID.randomUUID().toString()
            val uid = authManager.currentUser?.uid.orEmpty()
            lastLocalCommandId = cmdId
            lastLocalCommandAtMs = nowMs
            anchoredPositionMs = 0L
            anchoredAtLocalWallMs = nowMs
            anchoredAtLocalElapsedMs = nowElapsed
            anchoredServerEpochMs = if (anchorServerMs > 0L) anchorServerMs else currentServerClockMs()
            lastSeekOrApplyRealtimeMs = nowElapsed
            lastHandledEndQueueItemId = null
            val optimisticPlayback = currentSession.playback.copy(
                sharedTrackId = track.sharedTrackId,
                queueItemId = targetItem.id,
                isPlaying = true,
                positionMs = 0L,
                updatedAtMs = nowMs,
                anchorServerMs = anchorServerMs,
                commandId = cmdId,
                commandType = JamPermissionKind.SKIP_TRACK.name,
                actorUid = uid
            )
            pendingLocalCommandId = cmdId
            pendingLocalBaseRevision = currentSession.playback.revision
            pendingLocalCommandElapsedMs = nowElapsed
            pendingLocalPlayback = optimisticPlayback
            _session.value = currentSession.copy(playback = optimisticPlayback)
            val local = matchLocalSong(track, allSongs.value)
            if (local != null) {
                _localTrackAvailable.value = true
                lastAppliedTrackId = track.sharedTrackId
                playbackManager.applyJamPlaybackSnapshot(local, 0L, true, seekPosition = true)
            }
        }
    }

    fun addSong(song: Song) {
        val sessionId = _sessionId.value ?: return
        val track = trackFrom(song) ?: run {
            _uiState.value = JamUiState.Error("This track needs a title and artist before it can be shared.")
            return
        }
        matchedSongCache[track.sharedTrackId] = song
        val insertNext = playbackManager.addToQueuePosition.equals("TOP_NEXT", ignoreCase = true)
        optimisticallyInsertTrackInQueue(track, insertNext = insertNext, playImmediately = false)
        scope.launch {
            runCatching {
                if (insertNext) {
                    repository.playTrackNext(sessionId, track)
                } else {
                    repository.addTrack(sessionId, track)
                }
            }.onFailure { error ->
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't add song to the shared queue.")
            }
        }
    }

    fun recommendSharedTrack(track: JamTrack) {
        val id = _sessionId.value ?: return
        scope.launch {
            runCatching {
                repository.createRecommendation(id, JamRecommendationType.SHARED_LIBRARY, track)
                _uiState.value = JamUiState.Notice("Started 30s vote for \"${track.title}\".")
            }.onFailure { error ->
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't start recommendation vote.")
            }
        }
    }

    fun recommendSharedLibrarySong(match: JamSharedLibraryMatch) {
        matchedSongCache[match.track.sharedTrackId] = match.song
        recommendSharedTrack(match.track)
    }

    fun addSharedLibrarySongToQueue(match: JamSharedLibraryMatch) {
        val sessionId = _sessionId.value ?: return
        matchedSongCache[match.track.sharedTrackId] = match.song
        val insertNext = playbackManager.addToQueuePosition.equals("TOP_NEXT", ignoreCase = true)
        optimisticallyInsertTrackInQueue(match.track, insertNext = insertNext, playImmediately = false)
        _uiState.value = if (insertNext) {
            JamUiState.Notice("Added \"${match.track.title}\" to play next in the shared queue.")
        } else {
            JamUiState.Notice("Added \"${match.track.title}\" to the shared queue.")
        }
        scope.launch {
            runCatching {
                if (insertNext) {
                    repository.playTrackNext(sessionId, match.track)
                } else {
                    repository.addTrack(sessionId, match.track)
                }
            }.onFailure { error ->
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't add track to the shared queue.")
            }
        }
    }

    fun playSharedLibrarySongNext(match: JamSharedLibraryMatch) {
        val sessionId = _sessionId.value ?: return
        matchedSongCache[match.track.sharedTrackId] = match.song
        optimisticallyInsertTrackInQueue(match.track, insertNext = true, playImmediately = false)
        _uiState.value = JamUiState.Notice("\"${match.track.title}\" will play next.")
        scope.launch {
            runCatching {
                repository.playTrackNext(sessionId, match.track)
            }.onFailure { error ->
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't queue track next.")
            }
        }
    }

    fun playSharedLibrarySongNow(match: JamSharedLibraryMatch) {
        val sessionId = _sessionId.value ?: return
        matchedSongCache[match.track.sharedTrackId] = match.song
        val cmdId = UUID.randomUUID().toString()
        val anchorServerMs = if (isClockCalibrated) currentServerClockMs() else 0L
        optimisticallyInsertTrackInQueue(
            track = match.track,
            insertNext = false,
            playImmediately = true,
            commandId = cmdId,
            anchorServerMs = anchorServerMs
        )
        _uiState.value = JamUiState.Notice("Now playing \"${match.track.title}\" for everyone.")
        scope.launch {
            runCatching {
                repository.playTrackNow(sessionId, match.track, commandId = cmdId, anchorServerMs = anchorServerMs)
            }.onFailure { error ->
                if (pendingLocalCommandId == cmdId) {
                    pendingLocalCommandId = null
                    pendingLocalPlayback = null
                }
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't start playing track.")
            }
        }
    }

    fun addAllMatchingSongsToQueue(shuffle: Boolean = false) {
        val sessionId = _sessionId.value ?: return
        val matches = _sharedLibraryMatches.value
        val tracks = matches.map {
            matchedSongCache[it.track.sharedTrackId] = it.song
            it.track
        }
        if (tracks.isEmpty()) {
            _uiState.value = JamUiState.Error("No matching songs are available to add.")
            return
        }
        scope.launch {
            runCatching {
                val added = repository.addTracksBatch(sessionId, tracks, shuffle = shuffle)
                _uiState.value = if (added > 0) {
                    JamUiState.Notice(
                        if (shuffle) "Shuffled and added $added matching song(s) to the shared queue."
                        else "Added $added matching song(s) to the shared queue."
                    )
                } else {
                    JamUiState.Notice("The shared queue is already up to date.")
                }
            }.onFailure { error ->
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't add matching songs.")
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
        val targetPos = if (isSwitch) 0L else currentEstimatedPlaybackPositionMs(current.playback)
        val nowMs = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val anchorServerMs = if (isClockCalibrated) nowElapsed + serverClockOffsetMs else 0L
        val cmdId = UUID.randomUUID().toString()
        lastLocalCommandId = cmdId
        lastLocalCommandAtMs = nowMs
        anchoredPositionMs = targetPos
        anchoredAtLocalWallMs = nowMs
        anchoredAtLocalElapsedMs = nowElapsed
        anchoredServerEpochMs = if (anchorServerMs > 0L) anchorServerMs else nowMs
        lastSeekOrApplyRealtimeMs = nowElapsed
        lastHandledEndQueueItemId = null

        val optimisticPlayback = current.playback.copy(
            sharedTrackId = targetItem.track.sharedTrackId,
            queueItemId = targetItem.id,
            isPlaying = true,
            positionMs = targetPos,
            updatedAtMs = nowMs,
            anchorServerMs = anchorServerMs,
            commandId = cmdId,
            commandType = JamPermissionKind.PLAY_PAUSE.name,
            actorUid = uid
        )
        pendingLocalCommandId = cmdId
        pendingLocalBaseRevision = current.playback.revision
        pendingLocalCommandElapsedMs = nowElapsed
        pendingLocalPlayback = optimisticPlayback
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
                    isDirectSelect = true,
                    basePlaybackRevision = current.playback.revision,
                    anchorServerMs = anchorServerMs
                )
                alignControllerAnchorOnAck(cmdId, committed, targetPos)
            }.onFailure { error ->
                if (pendingLocalCommandId == cmdId) {
                    pendingLocalCommandId = null
                    pendingLocalPlayback = null
                }
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
        _uiState.value = JamUiState.Notice("Shuffled the shared queue.")
        scope.launch {
            runCatching {
                repository.reorderQueue(id, orderedIds)
            }.onFailure { error ->
                pendingQueueOrderIds = null
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't shuffle the shared queue.")
            }
        }
    }

    fun dismissIncomingVoteAlert() {
        _incomingVoteAlert.value = null
    }

    fun removeDuplicateSongsFromQueue() {
        val id = _sessionId.value ?: return
        val current = _queue.value
        if (current.size > 1) {
            val activeId = _session.value?.playback?.queueItemId
            val activeKey = current.firstOrNull { it.id == activeId }?.track?.sharedTrackId.orEmpty()
            val seen = HashSet<String>()
            if (activeKey.isNotBlank()) seen.add(activeKey)
            val deduped = current.filter { item ->
                val key = item.track.sharedTrackId.ifBlank { "${item.track.title.lowercase()}|${item.track.artist.lowercase()}" }
                if (item.id == activeId) true else seen.add(key)
            }.mapIndexed { idx, item -> item.copy(position = idx.toLong()) }
            if (deduped.size != current.size) {
                _queue.value = deduped
            }
        }
        scope.launch {
            runCatching {
                val removed = repository.removeDuplicateTracks(id)
                _uiState.value = if (removed > 0) {
                    JamUiState.Notice("Removed $removed duplicate song(s) from the shared queue.")
                } else {
                    JamUiState.Notice("All songs in the shared queue are already unique.")
                }
            }.onFailure { error ->
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't remove duplicate songs.")
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
        val previousQueue = _queue.value
        _queue.value = previousQueue.filterNot { it.id == itemId }.mapIndexed { idx, item -> item.copy(position = idx.toLong()) }
        scope.launch {
            runCatching {
                repository.removeTrack(id, itemId)
            }.onFailure { error ->
                _queue.value = previousQueue
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't remove track from queue.")
            }
        }
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
        scope.launch {
            runCatching { repository.reorderQueue(id, ids) }
                .onFailure { error ->
                    pendingQueueOrderIds = null
                    _uiState.value = JamUiState.Error(error.message ?: "Couldn't move track in queue.")
                }
        }
    }

    fun setPermissions(permissions: JamPermissions) {
        val id = _sessionId.value ?: return
        val previous = _session.value
        if (previous != null && previous.id == id) {
            // Apply permission update immediately in-memory so all Jam controls & headphone rules reflect changes in 0ms
            val updated = previous.copy(permissions = permissions)
            _session.value = updated
            syncJamControlPermissions(updated)
        }
        scope.launch {
            runCatching {
                repository.updatePermissions(id, permissions)
            }.onFailure { error ->
                if (previous != null && _sessionId.value == id) {
                    _session.value = previous
                    syncJamControlPermissions(previous)
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
                val updated = current.copy(permissions = updatedPerms)
                _session.value = updated
                syncJamControlPermissions(updated)
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
                currentEstimatedPlaybackPositionMs(current.playback)
            }
        } else 0L
        val targetItem = _queue.value.firstOrNull { it.id == current.playback.queueItemId }
            ?: _queue.value.firstOrNull()
        val nowMs = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val anchorServerMs = if (isClockCalibrated) nowElapsed + serverClockOffsetMs else 0L
        val cmdId = UUID.randomUUID().toString()
        lastLocalCommandId = cmdId
        lastLocalCommandAtMs = nowMs
        anchoredPositionMs = activePos
        anchoredAtLocalWallMs = nowMs
        anchoredAtLocalElapsedMs = nowElapsed
        anchoredServerEpochMs = if (anchorServerMs > 0L) anchorServerMs else nowMs
        lastSeekOrApplyRealtimeMs = nowElapsed

        if (targetItem != null) {
            val optimisticPlayback = current.playback.copy(
                sharedTrackId = targetItem.track.sharedTrackId,
                queueItemId = targetItem.id,
                isPlaying = isPlaying,
                positionMs = activePos,
                updatedAtMs = nowMs,
                anchorServerMs = anchorServerMs,
                commandId = cmdId,
                commandType = JamPermissionKind.PLAY_PAUSE.name,
                actorUid = uid
            )
            pendingLocalCommandId = cmdId
            pendingLocalBaseRevision = current.playback.revision
            pendingLocalCommandElapsedMs = nowElapsed
            pendingLocalPlayback = optimisticPlayback
            _session.value = current.copy(playback = optimisticPlayback)
            val local = matchLocalSong(targetItem.track, allSongs.value)
            if (local != null) {
                _localTrackAvailable.value = true
                lastAppliedTrackId = targetItem.track.sharedTrackId
                playbackManager.applyJamPlaybackSnapshot(
                    song = local,
                    positionMs = activePos,
                    isPlaying = isPlaying,
                    seekPosition = !hasActiveItem || kotlin.math.abs(playbackManager.player.currentPosition - activePos) > 80L
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
                    isDirectSelect = false,
                    basePlaybackRevision = current.playback.revision,
                    anchorServerMs = anchorServerMs
                )
                alignControllerAnchorOnAck(cmdId, committed, activePos)
            }.onFailure { error ->
                if (pendingLocalCommandId == cmdId) {
                    pendingLocalCommandId = null
                    pendingLocalPlayback = null
                }
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
            currentEstimatedPlaybackPositionMs(current.playback)
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
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val anchorServerMs = if (isClockCalibrated) nowElapsed + serverClockOffsetMs else 0L
        val cmdId = UUID.randomUUID().toString()
        lastLocalCommandId = cmdId
        lastLocalCommandAtMs = nowMs
        anchoredPositionMs = targetPos
        anchoredAtLocalWallMs = nowMs
        anchoredAtLocalElapsedMs = nowElapsed
        anchoredServerEpochMs = if (anchorServerMs > 0L) anchorServerMs else nowMs
        lastSeekOrApplyRealtimeMs = nowElapsed
        lastHandledEndQueueItemId = null

        val optimisticPlayback = current.playback.copy(
            sharedTrackId = targetItem.track.sharedTrackId,
            queueItemId = targetItem.id,
            isPlaying = targetPlaying,
            positionMs = targetPos,
            updatedAtMs = nowMs,
            anchorServerMs = anchorServerMs,
            commandId = cmdId,
            commandType = JamPermissionKind.SKIP_TRACK.name,
            actorUid = uid
        )
        pendingLocalCommandId = cmdId
        pendingLocalBaseRevision = current.playback.revision
        pendingLocalCommandElapsedMs = nowElapsed
        pendingLocalPlayback = optimisticPlayback
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
                    isDirectSelect = false,
                    basePlaybackRevision = current.playback.revision,
                    anchorServerMs = anchorServerMs
                )
                alignControllerAnchorOnAck(cmdId, committed, targetPos)
            }.onFailure { error ->
                if (pendingLocalCommandId == cmdId) {
                    pendingLocalCommandId = null
                    pendingLocalPlayback = null
                }
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
        val activeItem = _queue.value.firstOrNull { it.id == current.playback.queueItemId }
            ?: _queue.value.firstOrNull()
        val nowMs = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val anchorServerMs = if (isClockCalibrated) nowElapsed + serverClockOffsetMs else 0L
        val cmdId = UUID.randomUUID().toString()
        lastLocalCommandId = cmdId
        lastLocalCommandAtMs = nowMs
        anchoredPositionMs = safePos
        anchoredAtLocalWallMs = nowMs
        anchoredAtLocalElapsedMs = nowElapsed
        anchoredServerEpochMs = if (anchorServerMs > 0L) anchorServerMs else nowMs
        lastSeekOrApplyRealtimeMs = nowElapsed

        playbackManager.seekJamPlayback(safePos)
        val optimisticPlayback = current.playback.copy(
            positionMs = safePos,
            updatedAtMs = nowMs,
            anchorServerMs = anchorServerMs,
            commandId = cmdId,
            commandType = JamPermissionKind.SEEK.name,
            actorUid = uid
        )
        pendingLocalCommandId = cmdId
        pendingLocalBaseRevision = current.playback.revision
        pendingLocalCommandElapsedMs = nowElapsed
        pendingLocalPlayback = optimisticPlayback
        _session.value = current.copy(playback = optimisticPlayback)
        scope.launch {
            runCatching {
                val committed = repository.submitPlaybackCommand(
                    sessionId = current.id,
                    type = JamPermissionKind.SEEK,
                    isPlaying = current.playback.isPlaying,
                    positionMs = safePos,
                    targetQueueItemId = activeItem?.id ?: current.playback.queueItemId,
                    targetSharedTrackId = activeItem?.track?.sharedTrackId ?: current.playback.sharedTrackId,
                    hasActiveItem = !current.playback.queueItemId.isNullOrBlank(),
                    commandId = cmdId,
                    isDirectSelect = false,
                    basePlaybackRevision = current.playback.revision,
                    anchorServerMs = anchorServerMs
                )
                alignControllerAnchorOnAck(cmdId, committed, safePos)
            }.onFailure { error ->
                if (pendingLocalCommandId == cmdId) {
                    pendingLocalCommandId = null
                    pendingLocalPlayback = null
                }
                _uiState.value = JamUiState.Error(error.message ?: "Couldn't seek playback.")
            }
        }
    }

    /**
     * Reconciles controller anchor upon Firestore ACK.
     * - When both devices share the calibrated Firestore server clock (`committed.anchorServerMs > 0L`),
     *   the controller NEVER needs to seek back or stutter — remote listeners jump straight to
     *   `commandStartPosMs + (serverNow - anchorServerMs)`, achieving sub-25ms zero-latency lock!
     * - Fallback (before initial clock calibration completes): anchors at ACK time so devices stay aligned.
     */
    private fun alignControllerAnchorOnAck(cmdId: String, committed: JamPlaybackState, commandStartPosMs: Long) {
        lastAnchoredRevision = maxOf(lastAnchoredRevision, committed.revision)
        lastAppliedRevision = maxOf(lastAppliedRevision, committed.revision)
        if (pendingLocalCommandId == cmdId) {
            pendingLocalCommandId = null
            pendingLocalPlayback = null
        }
        if (lastLocalCommandId != cmdId) return
        val ackWallMs = System.currentTimeMillis()
        val ackElapsedMs = android.os.SystemClock.elapsedRealtime()
        if (isClockCalibrated && committed.anchorServerMs > 0L) {
            anchoredPositionMs = commandStartPosMs
            anchoredServerEpochMs = committed.anchorServerMs
            anchoredAtLocalElapsedMs = (committed.anchorServerMs - serverClockOffsetMs).coerceAtLeast(1L)
            val elapsedSinceStart = (ackElapsedMs - anchoredAtLocalElapsedMs).coerceAtLeast(0L)
            anchoredAtLocalWallMs = ackWallMs - elapsedSinceStart
        } else if (committed.isPlaying) {
            val rttMs = (ackWallMs - lastLocalCommandAtMs).coerceAtLeast(0L)
            anchoredAtLocalWallMs = ackWallMs
            anchoredAtLocalElapsedMs = ackElapsedMs
            anchoredPositionMs = commandStartPosMs + FALLBACK_TRANSIT_COMPENSATION_MS
            if (rttMs > 160L && _localTrackAvailable.value) {
                val actualPos = playbackManager.player.currentPosition.coerceAtLeast(0L)
                if (kotlin.math.abs(actualPos - anchoredPositionMs) > 120L) {
                    lastSeekOrApplyRealtimeMs = ackElapsedMs
                    playbackManager.seekJamPlayback(anchoredPositionMs)
                }
            }
        } else {
            anchoredAtLocalWallMs = ackWallMs
            anchoredAtLocalElapsedMs = ackElapsedMs
            anchoredPositionMs = commandStartPosMs
        }
        val latest = _session.value
        if (latest != null && committed.revision >= latest.playback.revision) {
            _session.value = latest.copy(
                playback = committed.copy(
                    positionMs = anchoredPositionMs,
                    updatedAtMs = anchoredAtLocalWallMs
                )
            )
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
        lastHandledEndQueueItemId = null
        lastAnchoredRevision = -1L
        anchoredPositionMs = 0L
        anchoredAtLocalElapsedMs = 0L
        anchoredAtLocalWallMs = 0L
        anchoredServerEpochMs = 0L
        serverClockOffsetMs = 0L
        isClockCalibrated = false
        _syncStatus.value = JamSyncStatus()
        lastLocalCommandId = null
        lastLocalCommandAtMs = 0L
        pendingLocalCommandId = null
        pendingLocalBaseRevision = -1L
        pendingLocalCommandElapsedMs = 0L
        pendingLocalPlayback = null
        lastSeekOrApplyRealtimeMs = 0L
        readySnapDoneForRevision = -1L
        // Start locked until session permissions load so unauthorized listeners cannot trigger headphone controls during join
        playbackManager.updateJamControlPermissions(canPlayPause = false, canSkip = false, canSeek = false)

        // Immediately calibrate monotonic local clock against Firestore server clock for zero-latency audio lock
        clockCalibrationJob = scope.launch(Dispatchers.IO) {
            var firstPass = true
            while (_sessionId.value == id) {
                val samples = if (firstPass) 3 else 2
                val result = runCatching { repository.calibrateServerClock(id, samples = samples) }.getOrNull()
                if (result != null && _sessionId.value == id) {
                    val (offsetMs, rttMs) = result
                    serverClockOffsetMs = offsetMs
                    val newlyCalibrated = !isClockCalibrated
                    isClockCalibrated = true
                    _syncStatus.value = _syncStatus.value.copy(
                        isClockSynced = true,
                        rttMs = rttMs
                    )
                    if (newlyCalibrated) {
                        withContext(Dispatchers.Main) {
                            val activePb = _session.value?.playback
                            if (activePb != null && activePb.isPlaying) {
                                val refServer = if (activePb.anchorServerMs > 0L) activePb.anchorServerMs else activePb.updatedAtMs
                                if (refServer > 0L) {
                                    anchoredServerEpochMs = refServer
                                    anchoredPositionMs = activePb.positionMs.coerceAtLeast(0L)
                                    anchoredAtLocalElapsedMs = (refServer - serverClockOffsetMs).coerceAtLeast(1L)
                                    correctPlaybackDrift()
                                }
                            }
                        }
                    }
                    firstPass = false
                }
                delay(CLOCK_RECALIBRATION_INTERVAL_MS)
            }
        }

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
                        val nowElapsed = android.os.SystemClock.elapsedRealtime()
                        val rawPb = rawSession.playback
                        val activePendingId = pendingLocalCommandId
                        val pendingFresh = activePendingId != null &&
                            (nowElapsed - pendingLocalCommandElapsedMs) < 2_500L &&
                            rawPb.commandId != activePendingId &&
                            rawPb.revision <= pendingLocalBaseRevision
                        val effectivePlayback = if (pendingFresh && pendingLocalPlayback != null) {
                            pendingLocalPlayback!!
                        } else {
                            if (activePendingId != null && (rawPb.commandId == activePendingId || rawPb.revision > pendingLocalBaseRevision)) {
                                pendingLocalCommandId = null
                                pendingLocalPlayback = null
                            }
                            if (rawPb.revision != lastAnchoredRevision) {
                                val isOwnEcho = !rawPb.commandId.isNullOrBlank() && rawPb.commandId == lastLocalCommandId
                                lastAnchoredRevision = rawPb.revision
                                if (isOwnEcho) {
                                    lastAppliedRevision = maxOf(lastAppliedRevision, rawPb.revision)
                                    if (isClockCalibrated && rawPb.anchorServerMs > 0L) {
                                        anchoredServerEpochMs = rawPb.anchorServerMs
                                        anchoredPositionMs = rawPb.positionMs.coerceAtLeast(0L)
                                        anchoredAtLocalElapsedMs = (rawPb.anchorServerMs - serverClockOffsetMs).coerceAtLeast(1L)
                                    }
                                } else {
                                    val serverRef = when {
                                        rawPb.anchorServerMs > 0L -> rawPb.anchorServerMs
                                        rawPb.updatedAtMs > 0L -> rawPb.updatedAtMs
                                        else -> 0L
                                    }
                                    if (isClockCalibrated && serverRef > 0L) {
                                        // Both devices share Firestore server time -> true zero-latency lock!
                                        anchoredPositionMs = rawPb.positionMs.coerceAtLeast(0L)
                                        anchoredServerEpochMs = serverRef
                                        anchoredAtLocalElapsedMs = (serverRef - serverClockOffsetMs).coerceAtLeast(1L)
                                        val elapsedMs = (nowElapsed - anchoredAtLocalElapsedMs).coerceAtLeast(0L)
                                        anchoredAtLocalWallMs = nowMs - elapsedMs
                                    } else if (isInitialSessionEmission) {
                                        anchoredAtLocalWallMs = nowMs
                                        anchoredAtLocalElapsedMs = nowElapsed
                                        anchoredServerEpochMs = serverRef
                                        anchoredPositionMs = if (rawPb.isPlaying && rawPb.updatedAtMs > 0L && (nowMs - rawPb.updatedAtMs) in 500L..600_000L) {
                                            rawPb.positionMs + (nowMs - rawPb.updatedAtMs)
                                        } else {
                                            rawPb.positionMs
                                        }
                                    } else {
                                        // Pre-calibration fallback: anchor to arrival time with one-way transit compensation
                                        anchoredAtLocalWallMs = nowMs
                                        anchoredAtLocalElapsedMs = nowElapsed
                                        anchoredServerEpochMs = serverRef
                                        val transitCompensationMs = if (rawPb.isPlaying && rawPb.actorUid != uid) FALLBACK_TRANSIT_COMPENSATION_MS else 0L
                                        anchoredPositionMs = (rawPb.positionMs + transitCompensationMs).coerceAtLeast(0L)
                                    }
                                }
                            }
                            rawPb.copy(
                                positionMs = anchoredPositionMs,
                                updatedAtMs = if (anchoredAtLocalWallMs > 0L) anchoredAtLocalWallMs else nowMs
                            )
                        }
                        rawSession.copy(playback = effectivePlayback)
                    } else null
                    isInitialSessionEmission = false
                    _session.value = session
                    syncJamControlPermissions(session)
                    if (session != null && session.hostAppVersion.isNotBlank()) {
                        appUpdateRepository.recordObservedPeerVersion(session.hostAppVersion)
                        val localVer = appUpdateRepository.installedVersion
                        val hostVer = com.example.update.AppVersion.parse(session.hostAppVersion)
                        val matches = if (localVer.isValid && hostVer.isValid) {
                            localVer.compareTo(hostVer) == 0
                        } else {
                            com.example.BuildConfig.VERSION_NAME.trim().equals(session.hostAppVersion.trim(), ignoreCase = true)
                        }
                        if (!matches && uid != null && session.hostUid != uid) {
                            val needsLocalUpdate = hostVer.isValid && localVer.isValid && hostVer.isNewerThan(localVer)
                            stopObserving(restorePlayback = true)
                            _page.value = JamPage.LANDING
                            val knownReleaseUrl = (appUpdateRepository.updateState.value as? com.example.update.UpdateCheckResult.UpdateAvailable)
                                ?.release?.let { it.apkDownloadUrl?.takeIf(String::isNotBlank) ?: it.htmlUrl }
                                ?: com.example.update.AppUpdateRepository.GITHUB_RELEASES_URL
                            _uiState.value = JamUiState.VersionMismatch(
                                message = if (needsLocalUpdate) {
                                    "Version mismatch: You have ${localVer.displayString} installed, while this Jam requires ${hostVer.displayString}. Update OffGrid Music to join."
                                } else {
                                    "Version mismatch: The host is on ${if (hostVer.isValid) hostVer.displayString else session.hostAppVersion}, while you have ${localVer.displayString}. Both users must have the same latest version installed."
                                },
                                currentVersion = localVer.displayString,
                                requiredVersion = if (needsLocalUpdate) hostVer.displayString else localVer.displayString,
                                needsLocalUpdate = needsLocalUpdate,
                                releaseUrl = knownReleaseUrl
                            )
                            return@collectLatest
                        }
                    }
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
                    val resolvedQueue = if (expectedIds != null) {
                        if (incomingIds == expectedIds) {
                            pendingQueueOrderIds = null
                            incoming
                        } else if (
                            System.currentTimeMillis() - pendingQueueOrderAtMs < 4_500L &&
                            incomingIds.size == expectedIds.size &&
                            incomingIds.toSet() == expectedIds.toSet()
                        ) {
                            val byId = incoming.associateBy { it.id }
                            expectedIds.mapIndexedNotNull { idx, itemId ->
                                byId[itemId]?.copy(position = idx.toLong())
                            }
                        } else {
                            pendingQueueOrderIds = null
                            incoming
                        }
                    } else {
                        incoming
                    }
                    _queue.value = resolvedQueue
                    // Pre-warm local song lookup cache for upcoming tracks so skips start in 0ms
                    if (resolvedQueue.isNotEmpty()) {
                        val songsSnapshot = allSongs.value
                        scope.launch(Dispatchers.Default) {
                            resolvedQueue.take(6).forEach { qItem ->
                                if (qItem.track.sharedTrackId.isNotBlank() && !matchedSongCache.containsKey(qItem.track.sharedTrackId)) {
                                    matchLocalSong(qItem.track, songsSnapshot)
                                }
                            }
                        }
                    }
                }
        }
        participantListenerJob = scope.launch {
            repository.observeParticipants(id)
                .catch { error -> handleListenerFailure(id, error) }
                .collectLatest { participantsList ->
                    participantsList.forEach { p ->
                        if (p.appVersion.isNotBlank()) {
                            appUpdateRepository.recordObservedPeerVersion(p.appVersion)
                        }
                    }
                    _participants.value = participantsList
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
        if (!session.started || queue.isEmpty()) {
            if (lastAppliedTrackId != null || playbackManager.player.isPlaying) {
                playbackManager.pauseJamPlayback()
            }
            lastAppliedTrackId = null
            return
        }
        val playback = session.playback
        val item = queue.firstOrNull { it.id == playback.queueItemId }
            ?: playback.sharedTrackId?.takeIf(String::isNotBlank)?.let { tid -> queue.firstOrNull { it.track.sharedTrackId == tid } }
            ?: queue.firstOrNull()
        val track = item?.track ?: return
        val trackId = track.sharedTrackId.takeIf(String::isNotBlank) ?: return
        val local = matchLocalSong(track, songs)
        if (local == null) {
            _localTrackAvailable.value = false
            if (lastAppliedTrackId != trackId || playbackManager.player.isPlaying) {
                playbackManager.pauseJamPlayback()
            }
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
        if (isNewTrack) lastHandledEndQueueItemId = null

        val expectedPosition = currentEstimatedPlaybackPositionMs(playback)
        val currentPlayerPos = playbackManager.player.currentPosition.coerceAtLeast(0L)
        val driftFromExpected = kotlin.math.abs(currentPlayerPos - expectedPosition)
        val seekPosition = isNewTrack ||
            wasUnavailable ||
            playback.commandType == JamPermissionKind.SEEK.name ||
            playback.commandType == JamPermissionKind.SKIP_TRACK.name ||
            (!playback.isPlaying && driftFromExpected > 45L) ||
            (playback.isPlaying && driftFromExpected > 75L)
        val targetStartPos = if (playback.isPlaying && seekPosition) {
            (expectedPosition + AUDIO_SEEK_PIPELINE_LEAD_MS).coerceAtLeast(0L)
        } else {
            expectedPosition
        }
        if (seekPosition) {
            lastSeekOrApplyRealtimeMs = android.os.SystemClock.elapsedRealtime()
            readySnapDoneForRevision = -1L
        }
        runCatching { playbackManager.player.setPlaybackSpeed(1f) }
        playbackManager.applyJamPlaybackSnapshot(local, targetStartPos, playback.isPlaying, seekPosition)
    }

    private fun correctPlaybackDrift() {
        val session = _session.value ?: return
        val playback = session.playback
        val trackId = playback.sharedTrackId ?: return
        if (!playback.isPlaying || trackId != lastAppliedTrackId || !_localTrackAvailable.value) {
            if (_syncStatus.value.lastDriftMs != 0L || _syncStatus.value.isSyncedLocked) {
                _syncStatus.value = _syncStatus.value.copy(lastDriftMs = 0L, isSyncedLocked = false)
            }
            return
        }
        val player = playbackManager.player
        if (player.playbackState != Player.STATE_READY || !player.isPlaying) return
        val nowRealtime = android.os.SystemClock.elapsedRealtime()
        // Allow ExoPlayer to settle briefly after any track switch, resume, or explicit seek
        if (nowRealtime - lastSeekOrApplyRealtimeMs < POST_SEEK_SETTLE_MS) return

        val expected = currentEstimatedPlaybackPositionMs(playback)
        val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        if (expected >= duration - 1_000L) return
        val actual = player.currentPosition.coerceAtLeast(0L)
        val drift = expected - actual
        val absolute = kotlin.math.abs(drift)
        _syncStatus.value = _syncStatus.value.copy(
            lastDriftMs = drift,
            isSyncedLocked = absolute <= SYNC_LOCK_WINDOW_MS
        )
        if (absolute >= SEEK_DRIFT_THRESHOLD_MS) {
            lastSeekOrApplyRealtimeMs = nowRealtime
            runCatching { player.setPlaybackSpeed(1f) }
            playbackManager.seekJamPlayback((expected + AUDIO_SEEK_PIPELINE_LEAD_MS).coerceAtLeast(0L))
        } else if (absolute >= SPEED_CORRECTION_THRESHOLD_MS) {
            // Smooth continuous proportional PLL — zero audio glitches, pulls devices tightly in phase
            val proportionalDelta = (drift.toFloat() / 900f).coerceIn(-0.045f, 0.045f)
            val adjustedSpeed = (1f + proportionalDelta).coerceIn(0.955f, 1.045f)
            if (kotlin.math.abs(player.playbackParameters.speed - adjustedSpeed) > 0.002f) {
                runCatching { player.setPlaybackSpeed(adjustedSpeed) }
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
            lastIndexedLibrarySignature = null
            matchedSongCache.clear()
            _sharedLibraryMatches.value = emptyList()
            _libraryIndexStatus.value = JamLibraryIndexStatus()
            return
        }

        val eligibleSongs = withContext(Dispatchers.Default) {
            songs.filter { !it.isMissing && !it.isCorrupted && it.durationMs > 0L }
        }
        val signature = "$sessionId:${eligibleSongs.size}:${eligibleSongs.firstOrNull()?.id ?: 0L}:${eligibleSongs.lastOrNull()?.id ?: 0L}"
        // Do NOT wipe or re-index the library when allSongs only emitted an artwork or play-count update!
        if (localLibraryReadyForSession == sessionId &&
            lastIndexedLibrarySignature == signature &&
            localLibraryEntries.isNotEmpty()
        ) {
            val songById = eligibleSongs.associateBy { it.id }
            localLibraryEntries = localLibraryEntries.map { entry ->
                val latestSong = songById[entry.song.id]
                if (latestSong != null && latestSong !== entry.song) entry.copy(song = latestSong) else entry
            }
            return
        }

        delay(200L) // Brief debounce for initial MediaStore emission
        if (_sessionId.value != sessionId) return

        _libraryIndexStatus.value = JamLibraryIndexStatus(
            localTotalTracks = eligibleSongs.size,
            participantCount = _participants.value.count { it.isConnected }
        )

        val (entries, matchKeys) = withContext(Dispatchers.Default) {
            val built = ArrayList<LocalLibraryEntry>(eligibleSongs.size)
            eligibleSongs.forEachIndexed { index, song ->
                val audioFingerprint = JamLibraryFingerprint.forSong(context, song)
                val track = trackFrom(song, audioFingerprint)
                if (track != null) {
                    val keys = jamLibraryMatchKeys(track, audioFingerprint, sessionId)
                    if (keys.isNotEmpty()) {
                        built += LocalLibraryEntry(song, track, audioFingerprint, keys)
                        matchedSongCache[track.sharedTrackId] = song
                        keys.forEach { k -> matchedSongCache[k] = song }
                    }
                }
                if ((index + 1) % 40 == 0 || index == eligibleSongs.lastIndex) {
                    _libraryIndexStatus.value = _libraryIndexStatus.value.copy(localIndexedTracks = index + 1)
                }
            }
            val distinctKeys = built.flatMap { it.matchKeys }.distinct()
            built to distinctKeys
        }

        if (_sessionId.value != sessionId) return
        localLibraryEntries = entries
        localLibraryReadyForSession = sessionId
        lastIndexedLibrarySignature = signature
        updateSharedLibraryMatches()
        applyPlaybackSnapshot(_session.value, _queue.value, allSongs.value)

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
                _libraryIndexStatus.value = _libraryIndexStatus.value.copy(
                    localIndexedTracks = eligibleSongs.size,
                    localTotalTracks = eligibleSongs.size,
                    error = null
                )
                updateSharedLibraryMatches()
            }
        }
    }

    private suspend fun updateSharedLibraryMatches() {
        val currentSessionId = _sessionId.value
        val currentParticipants = _participants.value.filter { it.isConnected }
        val uidList = currentParticipants.map { it.uid }.distinct()
        val index = _remoteLibraryIndex.value
        val readyParticipants = uidList.count { it in index.readyUids }
        val localReady = currentSessionId != null && localLibraryReadyForSession == currentSessionId
        val allReady = localReady && uidList.isNotEmpty() && readyParticipants == uidList.size
        val entriesSnapshot = localLibraryEntries

        val matches = if (allReady && entriesSnapshot.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                val commonKeys = uidList.mapNotNull(index.keysByUid::get)
                    .reduceOrNull { common, keys -> common intersect keys }
                    .orEmpty()
                if (commonKeys.isEmpty()) {
                    emptyList()
                } else {
                    entriesSnapshot.mapNotNull { entry ->
                        val commonKey = entry.matchKeys.firstOrNull { it in commonKeys } ?: return@mapNotNull null
                        val sharedTrack = entry.track.copy(
                            sharedTrackId = commonKey,
                            audioFingerprint = null
                        )
                        matchedSongCache[commonKey] = entry.song
                        JamSharedLibraryMatch(entry.song, sharedTrack, commonKey)
                    }.distinctBy { it.matchKey }
                }
            }
        } else emptyList()

        _sharedLibraryMatches.value = matches
        _libraryIndexStatus.value = _libraryIndexStatus.value.copy(
            indexedParticipants = readyParticipants,
            participantCount = uidList.size,
            isReady = allReady
        )
    }

    private fun matchLocalSong(track: JamTrack, songs: List<Song>): Song? {
        if (track.sharedTrackId.isNotBlank()) {
            matchedSongCache[track.sharedTrackId]?.let { cached ->
                val latest = songs.firstOrNull { it.id == cached.id } ?: cached
                matchedSongCache[track.sharedTrackId] = latest
                return latest
            }
            localLibraryEntries.firstOrNull { entry ->
                entry.track.sharedTrackId == track.sharedTrackId || track.sharedTrackId in entry.matchKeys
            }?.song?.let { matched ->
                val latest = songs.firstOrNull { it.id == matched.id } ?: matched
                matchedSongCache[track.sharedTrackId] = latest
                return latest
            }
        }
        val fingerprint = track.audioFingerprint?.takeIf(String::isNotBlank)
        if (fingerprint != null) {
            val fingerprintMatches = songs.filter { JamLibraryFingerprint.cached(context, it) == fingerprint }
            fingerprintMatches.singleOrNull()?.let {
                if (track.sharedTrackId.isNotBlank()) matchedSongCache[track.sharedTrackId] = it
                return it
            }
            if (fingerprintMatches.size > 1) return null
        }
        val trackPrefix = track.sharedTrackId.take(24)
        if (!track.checksum.isNullOrBlank()) {
            val checksumPrefix = track.checksum.take(16)
            songs.firstOrNull { song ->
                song.path.contains(trackPrefix) && song.path.contains(checksumPrefix)
            }?.let {
                if (track.sharedTrackId.isNotBlank()) matchedSongCache[track.sharedTrackId] = it
                return it
            }
        }
        if (trackPrefix.isNotBlank()) {
            songs.firstOrNull { song ->
                song.path.contains("jam_shared") && song.path.contains(trackPrefix)
            }?.let {
                if (track.sharedTrackId.isNotBlank()) matchedSongCache[track.sharedTrackId] = it
                return it
            }
            val sharedDir = File(context.filesDir, "jam_shared")
            if (sharedDir.isDirectory) {
                val downloadedFile = sharedDir.listFiles()?.firstOrNull { file ->
                    file.isFile && file.length() > 0L && file.name.startsWith("$trackPrefix-") && !file.name.endsWith(".part")
                }
                if (downloadedFile != null) {
                    val fileUri = Uri.fromFile(downloadedFile).toString()
                    val downloadedSong = Song(
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
                    if (track.sharedTrackId.isNotBlank()) matchedSongCache[track.sharedTrackId] = downloadedSong
                    return downloadedSong
                }
            }
        }
        val currentSessionId = _sessionId.value.orEmpty()
        val idMatch = songs.firstOrNull {
            val fp = JamLibraryFingerprint.cached(context, it)
            val localTrack = trackFrom(it, fp) ?: return@firstOrNull false
            localTrack.sharedTrackId == track.sharedTrackId ||
                track.sharedTrackId in jamLibraryMatchKeys(localTrack, fp, currentSessionId)
        }
        if (idMatch != null) {
            if (track.sharedTrackId.isNotBlank()) matchedSongCache[track.sharedTrackId] = idMatch
            return idMatch
        }
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
        return candidates.singleOrNull()?.also {
            if (track.sharedTrackId.isNotBlank()) matchedSongCache[track.sharedTrackId] = it
        }
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
        _syncStatus.value = JamSyncStatus()
        isClockCalibrated = false
        serverClockOffsetMs = 0L
        pendingLocalCommandId = null
        pendingLocalPlayback = null
        if (restorePlayback) {
            runCatching { playbackManager.player.setPlaybackSpeed(1f) }
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
        lastHandledEndQueueItemId = null
    }

    private fun stopListenersOnly() {
        sessionListenerJob?.cancel(); sessionListenerJob = null
        queueListenerJob?.cancel(); queueListenerJob = null
        participantListenerJob?.cancel(); participantListenerJob = null
        recommendationListenerJob?.cancel(); recommendationListenerJob = null
        libraryIndexListenerJob?.cancel(); libraryIndexListenerJob = null
        featureRequestListenerJob?.cancel(); featureRequestListenerJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        clockCalibrationJob?.cancel(); clockCalibrationJob = null
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
                if (_uiState.value !is JamUiState.Error &&
                    _uiState.value !is JamUiState.Notice &&
                    _uiState.value !is JamUiState.VersionMismatch
                ) {
                    _uiState.value = JamUiState.Idle
                }
            } catch (mismatch: JamRepository.JamVersionMismatchException) {
                if (mismatch.needsLocalUpdate) {
                    appUpdateRepository.recordObservedPeerVersion(mismatch.requiredVersion)
                }
                val knownReleaseUrl = (appUpdateRepository.updateState.value as? com.example.update.UpdateCheckResult.UpdateAvailable)
                    ?.release?.let { it.apkDownloadUrl?.takeIf(String::isNotBlank) ?: it.htmlUrl }
                    ?: com.example.update.AppUpdateRepository.GITHUB_RELEASES_URL
                _uiState.value = JamUiState.VersionMismatch(
                    message = mismatch.message ?: "Both users must have the same latest version of OffGrid Music installed to access Jam.",
                    currentVersion = mismatch.currentVersion,
                    requiredVersion = mismatch.requiredVersion,
                    needsLocalUpdate = mismatch.needsLocalUpdate,
                    releaseUrl = knownReleaseUrl
                )
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
        private const val CLOCK_RECALIBRATION_INTERVAL_MS = 45_000L
        private const val FALLBACK_TRANSIT_COMPENSATION_MS = 55L
        private const val AUDIO_SEEK_PIPELINE_LEAD_MS = 22L
        private const val DRIFT_CHECK_INTERVAL_MS = 350L
        private const val POST_SEEK_SETTLE_MS = 350L
        private const val SEEK_DRIFT_THRESHOLD_MS = 175L
        private const val SPEED_CORRECTION_THRESHOLD_MS = 18L
        private const val SYNC_LOCK_WINDOW_MS = 35L
    }
}
