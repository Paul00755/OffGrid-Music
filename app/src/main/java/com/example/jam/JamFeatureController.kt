package com.example.jam

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import com.example.auth.AuthManager
import com.example.auth.UserAccount
import com.example.data.model.Song
import com.example.service.PlaybackManager
import com.example.service.PlaybackState
import com.example.util.TagCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
    private var featureRequestListenerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var driftJob: Job? = null
    private var soloCloseJob: Job? = null
    private var lastAppliedRevision = -1L
    private var lastAppliedTrackId: String? = null
    private var lastHandledEndTrackId: String? = null
    private var restoredForUid: String? = null
    private var lastAnnouncementAtMs: Long = 0L
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
                    repository.submitPlaybackCommand(current.id, JamPermissionKind.SKIP_TRACK, step = 1)
                } catch (error: Throwable) {
                    _uiState.value = JamUiState.Error(error.message ?: "Couldn't advance the shared queue.")
                } finally {
                    handlingEnd.set(false)
                }
            }
        }
    }

    init {
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
                    if (_sessionId.value == null && prefs.getString(KEY_ACTIVE_UID, null) == uid && !savedSession.isNullOrBlank()) {
                        activateSession(savedSession, JamPage.ACTIVE, restoringSavedSession = true)
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
        val account = currentAccountOrNull() ?: run { requestJoin(code); return }
        runAction {
            val id = repository.joinByCode(
                code = code,
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
        runAction {
            repository.addTrack(sessionId, track)
            _uiState.value = JamUiState.Idle
        }
    }

    fun recommendSharedTrack(track: JamTrack) {
        val id = _sessionId.value ?: return
        runAction { repository.createRecommendation(id, JamRecommendationType.SHARED_LIBRARY, track) }
    }

    fun recommendLocalTrack(track: JamTrack, uri: Uri, mimeType: String) {
        val id = _sessionId.value ?: return
        runAction {
            val recommendationId = repository.createRecommendation(id, JamRecommendationType.LOCAL_SONG, track)
            context.getSharedPreferences(TRANSFER_PREFS, Context.MODE_PRIVATE).edit()
                .putString("source:$recommendationId", uri.toString())
                .putString("mime:$recommendationId", mimeType)
                .apply()
            syncLocalTransfers(_recommendations.value)
        }
    }

    fun vote(recommendationId: String, vote: JamVote) {
        val id = _sessionId.value ?: return
        runAction { repository.voteRecommendation(id, recommendationId, vote) }
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
        val ids = current.map { it.id }.toMutableList().apply {
            add(to, removeAt(from))
        }
        val id = _sessionId.value ?: return
        runAction { repository.reorderQueue(id, ids) }
    }

    fun setPermissions(permissions: JamPermissions) {
        val id = _sessionId.value ?: return
        runAction { repository.updatePermissions(id, permissions) }
    }

    fun requestFeature(feature: JamFeature) {
        val id = _sessionId.value ?: return
        val account = currentAccountOrNull() ?: return
        runAction { repository.requestFeature(id, feature, resolvedDisplayName(account)) }
    }

    fun resolveFeatureRequest(feature: JamFeature, enableFeature: Boolean, blockRequester: Boolean = false) {
        val id = _sessionId.value ?: return
        runAction { repository.resolveFeatureRequest(id, feature, enableFeature, blockRequester) }
    }

    fun togglePlayback() {
        val current = _session.value ?: return
        setPlaybackPlaying(!current.playback.isPlaying)
    }

    private fun setPlaybackPlaying(isPlaying: Boolean) {
        val current = _session.value ?: return
        runAction {
            repository.submitPlaybackCommand(
                current.id,
                JamPermissionKind.PLAY_PAUSE,
                isPlaying = isPlaying
            )
        }
    }

    fun skip(step: Int) {
        val current = _session.value ?: return
        runAction { repository.submitPlaybackCommand(current.id, JamPermissionKind.SKIP_TRACK, step = step) }
    }

    fun seek(positionMs: Long) {
        val current = _session.value ?: return
        runAction { repository.submitPlaybackCommand(current.id, JamPermissionKind.SEEK, positionMs = positionMs) }
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
        _featureRequests.value = emptyList()
        _page.value = if (restoredInvite != null && page == JamPage.ACTIVE) JamPage.INVITE else page
        _uiState.value = JamUiState.Idle
        lastAppliedRevision = -1L
        lastAppliedTrackId = null
        lastHandledEndTrackId = null

        sessionListenerJob = scope.launch {
            repository.observeSession(id)
                .catch { error ->
                    if (_sessionId.value == id) {
                        _uiState.value = JamUiState.Error(error.message ?: "The Jam session is no longer available.")
                        stopObserving(restorePlayback = true)
                        _page.value = JamPage.LANDING
                    }
                }
                .collectLatest { session ->
                    _session.value = session
                    val uid = authManager.currentUser?.uid
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
            repository.observeQueue(id).collectLatest { _queue.value = it }
        }
        participantListenerJob = scope.launch {
            repository.observeParticipants(id).collectLatest { _participants.value = it }
        }
        recommendationListenerJob = scope.launch {
            repository.observeRecommendations(id).collectLatest { recommendations ->
                _recommendations.value = recommendations
                syncLocalTransfers(recommendations)
            }
        }
        featureRequestListenerJob = scope.launch {
            repository.observeFeatureRequests(id).collectLatest { _featureRequests.value = it }
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
        val trackId = playback.sharedTrackId?.takeIf(String::isNotBlank) ?: return
        val item = queue.firstOrNull { it.id == playback.queueItemId } ?: queue.firstOrNull { it.track.sharedTrackId == trackId }
        val track = item?.track ?: return
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
        val isNewTrack = lastAppliedTrackId != trackId
        val isNewRevision = playback.revision > lastAppliedRevision
        if (!isNewTrack && !isNewRevision && !wasUnavailable) return
        lastAppliedTrackId = trackId
        lastAppliedRevision = playback.revision

        val expectedPosition = playback.estimatedPosition()
        val seekPosition = isNewTrack || wasUnavailable || playback.commandType == JamPermissionKind.SEEK.name
        playbackManager.applyJamPlaybackSnapshot(local, expectedPosition, playback.isPlaying, seekPosition)
    }

    private fun correctPlaybackDrift() {
        val session = _session.value ?: return
        val playback = session.playback
        val trackId = playback.sharedTrackId ?: return
        if (!playback.isPlaying || trackId != lastAppliedTrackId || !_localTrackAvailable.value) return
        val expected = playback.estimatedPosition()
        val actual = playbackManager.player.currentPosition.coerceAtLeast(0L)
        val drift = expected - actual
        val absolute = kotlin.math.abs(drift)
        if (absolute >= SEEK_DRIFT_THRESHOLD_MS) {
            playbackManager.seekJamPlayback(expected)
        } else if (absolute >= SPEED_CORRECTION_THRESHOLD_MS) {
            val adjustedSpeed = if (drift > 0L) 1.015f else 0.985f
            runCatching { playbackManager.player.setPlaybackSpeed(adjustedSpeed) }
            scope.launch {
                delay(SPEED_CORRECTION_DURATION_MS)
                runCatching { playbackManager.player.setPlaybackSpeed(1f) }
            }
        }
    }

    private fun matchLocalSong(track: JamTrack, songs: List<Song>): Song? {
        if (!track.checksum.isNullOrBlank()) {
            val trackPrefix = track.sharedTrackId.take(24)
            val checksumPrefix = track.checksum.take(16)
            return songs.firstOrNull { song ->
                song.path.contains(trackPrefix) && song.path.contains(checksumPrefix) && java.io.File(song.path).isFile
            }
        }
        val idMatch = songs.firstOrNull { trackFrom(it)?.sharedTrackId == track.sharedTrackId }
        if (idMatch != null) return idMatch
        val title = normalize(TagCleaner.cleanTitle(track.title, ""))
        val artist = normalize(TagCleaner.cleanArtist(track.artist))
        val album = normalize(TagCleaner.cleanAlbum(track.album))
        if (title.isBlank() || artist.isBlank()) return null
        val candidates = songs.filter { song ->
            val localTitle = normalize(TagCleaner.cleanTitle(song.displayTitle, ""))
            val localArtist = normalize(TagCleaner.cleanArtist(song.displayArtist))
            val localAlbum = normalize(TagCleaner.cleanAlbum(song.displayAlbum))
            val durationDiff = kotlin.math.abs(song.durationMs - track.durationMs)
            val albumCompatible = album == localAlbum || isUnknownAlbum(album) || isUnknownAlbum(localAlbum)
            localTitle == title && localArtist == artist && albumCompatible && durationDiff <= MAX_MATCH_DURATION_DIFF_MS
        }
        return candidates.singleOrNull()
    }

    private fun trackFrom(song: Song): JamTrack? {
        if (song.isMissing || song.isCorrupted || song.durationMs <= 0L) return null
        val title = TagCleaner.cleanTitle(song.displayTitle, "").trim()
        val artist = TagCleaner.cleanArtist(song.displayArtist).trim()
        if (title.isBlank() || artist.isBlank()) return null
        val candidate = JamTrack(
            sharedTrackId = "",
            title = title.take(180),
            artist = artist.take(180),
            album = TagCleaner.cleanAlbum(song.displayAlbum).trim().take(180),
            durationMs = song.durationMs
        )
        return candidate.copy(sharedTrackId = sharedTrackId(candidate))
    }

    private fun stopObserving(restorePlayback: Boolean) {
        stopListenersOnly()
        _sessionId.value = null
        _session.value = null
        _participants.value = emptyList()
        _queue.value = emptyList()
        _recommendations.value = emptyList()
        _featureRequests.value = emptyList()
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
        featureRequestListenerJob?.cancel(); featureRequestListenerJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        driftJob?.cancel(); driftJob = null
        soloCloseJob?.cancel(); soloCloseJob = null
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
                prefs.edit().remove("source:${recommendation.id}").remove("mime:${recommendation.id}").apply()
            }
            if (recommendation.proposerUid == uid && (recommendation.status in setOf("APPROVED_PENDING_UPLOAD", "UPLOADING") ||
                    recommendation.uploadStatus == "RETRY_REQUIRED")) {
                val uri = prefs.getString("source:${recommendation.id}", null)?.let { Uri.parse(it) }
                val mime = prefs.getString("mime:${recommendation.id}", null)
                if (uri != null && !mime.isNullOrBlank()) {
                    JamTransferWork.enqueueUpload(context, sessionId, recommendation.id, uri, mime)
                }
            }
            val ownDownload = recommendation.downloads[uid]
            if (uid != recommendation.proposerUid && recommendation.status in setOf("UPLOADING", "DOWNLOADING") &&
                recommendation.votes[uid] == JamVote.ACCEPT && (ownDownload == null || ownDownload.status in setOf("WAITING_TO_DOWNLOAD", "DOWNLOADING", "WAITING_FOR_UPLOAD"))) {
                val checksum = recommendation.checksum ?: return@forEach
                if (recommendation.fileSize <= 0L) return@forEach
                JamTransferWork.enqueueDownload(context, sessionId, recommendation.id, checksum, recommendation.track, recommendation.fileSize, recommendation.contentType)
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
        private const val TRANSFER_PREFS = "offgrid_jam_transfers"
        private const val KEY_ACTIVE_UID = "active_user_id"
        private const val KEY_ACTIVE_SESSION = "active_session_id"
        private const val KEY_ACTIVE_INVITE_CODE = "active_invite_code"
        private const val KEY_ACTIVE_INVITE_URL = "active_invite_url"
        private const val MAX_MATCH_DURATION_DIFF_MS = 4_000L
        private const val DRIFT_CHECK_INTERVAL_MS = 15_000L
        private const val SEEK_DRIFT_THRESHOLD_MS = 1_800L
        private const val SPEED_CORRECTION_THRESHOLD_MS = 600L
        private const val SPEED_CORRECTION_DURATION_MS = 3_000L
    }
}
