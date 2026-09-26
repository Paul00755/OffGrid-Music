package com.example.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.model.Song
import com.example.util.ArtworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_OFF, // 0=OFF, 1=ONE, 2=ALL
    val isShuffleEnabled: Boolean = false,
    val playbackError: String? = null
)

@OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackManager(
    private val context: Context,
    private val scope: CoroutineScope,
    val sleepTimerManager: SleepTimerManager
) {
    companion object {
        private const val TAG = "PlaybackManager"
        var activeInstance: PlaybackManager? = null
    }

    init {
        activeInstance = this
    }
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
    val stereoBalanceProcessor = StereoBalanceAudioProcessor()
    val audioNormalizerProcessor = AudioNormalizerProcessor()

    val crossfadeStereoBalanceProcessor = StereoBalanceAudioProcessor()
    val crossfadeNormalizerProcessor = AudioNormalizerProcessor()

    var isNormalizeAudioEnabled: Boolean = false
        set(value) {
            field = value
            audioNormalizerProcessor.isEnabled = value
            crossfadeNormalizerProcessor.isEnabled = value
        }

    private val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): androidx.media3.exoplayer.audio.AudioSink {
            return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(stereoBalanceProcessor, audioNormalizerProcessor))
                .build()
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setUsePlatformDiagnostics(false)
        .build()
        .apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
        setHandleAudioBecomingNoisy(true)
        setWakeMode(C.WAKE_MODE_LOCAL)
    }

    private val crossfadeRenderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): androidx.media3.exoplayer.audio.AudioSink {
            return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(crossfadeStereoBalanceProcessor, crossfadeNormalizerProcessor))
                .build()
        }
    }

    val crossfadePlayer: ExoPlayer = ExoPlayer.Builder(context, crossfadeRenderersFactory)
        .setUsePlatformDiagnostics(false)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false
            )
            playWhenReady = true
        }

    private var crossfadeJob: Job? = null

    fun setAudioBalance(balance: Float) {
        stereoBalanceProcessor.balance = balance
        crossfadeStereoBalanceProcessor.balance = balance
    }

    val equalizerManager: EqualizerManager = EqualizerManager(context)

    var onFavoriteToggleFromNotification: (() -> Unit)? = null

    val forwardingPlayer: androidx.media3.common.ForwardingPlayer = object : androidx.media3.common.ForwardingPlayer(player) {
        override fun play() {
            if (fadeInMs > 0) {
                playWithFade()
            } else {
                this@PlaybackManager.play()
            }
        }

        override fun pause() {
            if (fadeOutMs > 0) {
                pauseWithFade()
            } else {
                this@PlaybackManager.pause()
            }
        }

        override fun seekToNext() {
            this@PlaybackManager.skipToNext()
        }

        override fun seekToNextMediaItem() {
            this@PlaybackManager.skipToNext()
        }

        override fun seekToPrevious() {
            this@PlaybackManager.skipToPrevious()
        }

        override fun seekToPreviousMediaItem() {
            this@PlaybackManager.skipToPrevious()
        }
    }

    val mediaSession: androidx.media3.session.MediaSession = androidx.media3.session.MediaSession.Builder(context, forwardingPlayer)
        .setId(java.util.UUID.randomUUID().toString())
        .setSessionActivity(
            android.app.PendingIntent.getActivity(
                context,
                0,
                android.content.Intent(context, com.example.MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .setCallback(object : androidx.media3.session.MediaSession.Callback {
            override fun onConnect(
                session: androidx.media3.session.MediaSession,
                controller: androidx.media3.session.MediaSession.ControllerInfo
            ): androidx.media3.session.MediaSession.ConnectionResult {
                val connectionResult = super.onConnect(session, controller)
                val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                    .add(androidx.media3.session.SessionCommand("ACTION_TOGGLE_FAVORITE", android.os.Bundle.EMPTY))
                    .add(androidx.media3.session.SessionCommand("ACTION_TOGGLE_SHUFFLE", android.os.Bundle.EMPTY))
                    .build()
                return androidx.media3.session.MediaSession.ConnectionResult.accept(
                    sessionCommands,
                    connectionResult.availablePlayerCommands
                )
            }

            override fun onPlayerCommandRequest(
                session: androidx.media3.session.MediaSession,
                controller: androidx.media3.session.MediaSession.ControllerInfo,
                playerCommand: Int
            ): Int {
                if (playerCommand == Player.COMMAND_PLAY_PAUSE) {
                    if (player.playbackState == Player.STATE_IDLE) {
                        if (player.mediaItemCount == 0 && _playbackState.value.queue.isNotEmpty()) {
                            val mediaItems = _playbackState.value.queue.map { buildMediaItem(it) }
                            val idx = _playbackState.value.queueIndex.coerceIn(_playbackState.value.queue.indices)
                            player.setMediaItems(mediaItems, idx, _playbackState.value.positionMs)
                        }
                        player.prepare()
                    } else if (player.playbackState == Player.STATE_ENDED) {
                        player.seekTo(0, 0L)
                        player.prepare()
                    }
                }
                return super.onPlayerCommandRequest(session, controller, playerCommand)
            }

            override fun onCustomCommand(
                session: androidx.media3.session.MediaSession,
                controller: androidx.media3.session.MediaSession.ControllerInfo,
                customCommand: androidx.media3.session.SessionCommand,
                args: android.os.Bundle
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                when (customCommand.customAction) {
                    "ACTION_TOGGLE_FAVORITE" -> {
                        onFavoriteToggleFromNotification?.invoke()
                        updateNotificationLayout()
                    }
                    "ACTION_TOGGLE_SHUFFLE" -> {
                        toggleShuffle()
                        updateNotificationLayout()
                    }
                }
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                )
            }
        })
        .build()

    fun updateNotificationLayout() {
        try {
            val currentSong = _playbackState.value.currentSong
            val isFav = currentSong?.isFavorite == true
            val favoriteButton = androidx.media3.session.CommandButton.Builder()
                .setDisplayName(if (isFav) "Unfavorite" else "Favorite")
                .setIconResId(if (isFav) com.example.R.drawable.ic_favorite else com.example.R.drawable.ic_favorite_border)
                .setSessionCommand(androidx.media3.session.SessionCommand("ACTION_TOGGLE_FAVORITE", android.os.Bundle.EMPTY))
                .build()

            val shuffleButton = androidx.media3.session.CommandButton.Builder()
                .setDisplayName("Shuffle")
                .setIconResId(com.example.R.drawable.ic_shuffle)
                .setSessionCommand(androidx.media3.session.SessionCommand("ACTION_TOGGLE_SHUFFLE", android.os.Bundle.EMPTY))
                .build()

            mediaSession.setCustomLayout(listOf(favoriteButton, shuffleButton))
            com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    @Volatile var currentPositionMs: Long = 0L
        private set

    @Volatile var isCrossfadeTransitioning: Boolean = false
        private set

    fun cancelCrossfade(resetVolume: Boolean = true) {
        crossfadeJob?.cancel()
        crossfadeJob = null
        isCrossfadeTransitioning = false
        hasTriggeredCrossfadeTransition = false
        try {
            crossfadePlayer.stop()
            crossfadePlayer.clearMediaItems()
        } catch (_: Exception) {}
        if (resetVolume) {
            player.volume = 1.0f
        }
    }

    private var originalQueue: List<Song> = emptyList()
    private var progressUpdateJob: Job? = null

    var addToQueuePosition: String = "BOTTOM"
    private var isRestoringPlayback: Boolean = false

    var currentSourceContext: String = "UNKNOWN"
    var scrobbleThresholdType: String = "SECONDS"
    var scrobbleThresholdValue: Int = 30

    fun isSongQualified(listenedMs: Long, totalDurationMs: Long): Boolean {
        return if (scrobbleThresholdType == "PERCENTAGE") {
            if (totalDurationMs <= 0L) listenedMs >= (scrobbleThresholdValue * 1000L)
            else (listenedMs.toFloat() / totalDurationMs.toFloat()) >= (scrobbleThresholdValue / 100f)
        } else {
            listenedMs >= (scrobbleThresholdValue * 1000L)
        }
    }

    private val userPreferences by lazy { com.example.data.local.UserPreferencesRepository(context) }

    private data class ActiveSession(
        val sessionId: String,
        val trackId: Long,
        val startedAt: Long,
        var accumulatedListenedMs: Long = 0L,
        var segmentStartTimeMs: Long = 0L,
        val sourceContext: String,
        val totalDurationMs: Long,
        var sleepTimerDecremented: Boolean = false,
        var scrobbleRecorded: Boolean = false
    )

    private var activeSession: ActiveSession? = null
    private var repeatOneTargetSongId: Long? = null
    private var repeatOneRemainingPlays: Int = 0

    var onUpdatePlaybackSession: ((sessionId: String, trackId: Long, startedAt: Long, endedAt: Long, listenedDurationMs: Long, sourceContext: String, totalTrackDurationMs: Long) -> Unit)? = null

    private fun flushSession(isFinal: Boolean = false) {
        val session = activeSession ?: return
        if (session.segmentStartTimeMs > 0L) {
            val now = System.currentTimeMillis()
            session.accumulatedListenedMs += (now - session.segmentStartTimeMs)
            session.segmentStartTimeMs = if (isFinal) 0L else now
        }
        if (session.accumulatedListenedMs >= 1000L) {
            onUpdatePlaybackSession?.invoke(
                session.sessionId,
                session.trackId,
                session.startedAt,
                System.currentTimeMillis(),
                session.accumulatedListenedMs,
                session.sourceContext,
                session.totalDurationMs
            )
        }
        if (isFinal) {
            activeSession = null
        }
    }

    private fun buildMediaItem(song: Song, loadArtworkBytes: Boolean = false): MediaItem {
        // Fast path: Only use memory-cached artwork bytes or Uri. Never block main thread with disk I/O or decoding!
        val artBytes = try {
            com.example.util.ArtworkManager.getCachedNotificationArtwork(song.id)
        } catch (_: Throwable) { null }

        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(song.displayTitle)
            .setArtist(song.displayArtist)
            .setAlbumTitle(song.displayAlbum)
            .apply {
                if (artBytes != null && artBytes.isNotEmpty()) {
                    setArtworkData(artBytes, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
                val art = song.artworkUri ?: song.contentUriString.takeIf { it.startsWith("http") }
                if (!art.isNullOrBlank()) {
                    try {
                        setArtworkUri(android.net.Uri.parse(art))
                    } catch (_: Exception) {}
                }
            }
            .build()

        return MediaItem.Builder()
            .setUri(song.contentUri)
            .setMediaId(song.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    fun setAudioDevice(device: android.media.AudioDeviceInfo?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                player.setPreferredAudioDevice(device)
            } catch (_: Exception) {}
        }
    }

    fun startMusicPlaybackService() {
        try {
            val intent = android.content.Intent(context, MusicPlaybackService::class.java)
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MusicPlaybackService via startService", e)
        }
    }

    init {
        MusicPlaybackService.activeMediaSession = mediaSession
        startMusicPlaybackService()

        scope.launch {
            userPreferences.addToQueuePosition.collect { pos ->
                addToQueuePosition = pos
            }
        }

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizerManager.initAudioSession(audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                val currentSong = _playbackState.value.currentSong
                if (isPlaying && currentSong != null) {
                    val sessionId = player.audioSessionId
                    if (sessionId > 0 && sessionId != C.AUDIO_SESSION_ID_UNSET) {
                        equalizerManager.initAudioSession(sessionId)
                    }
                    val existing = activeSession
                    if (existing == null || existing.trackId != currentSong.id) {
                        flushSession(isFinal = true)
                        val newSession = ActiveSession(
                            sessionId = java.util.UUID.randomUUID().toString(),
                            trackId = currentSong.id,
                            startedAt = System.currentTimeMillis(),
                            segmentStartTimeMs = System.currentTimeMillis(),
                            accumulatedListenedMs = 0L,
                            sourceContext = currentSourceContext,
                            totalDurationMs = currentSong.durationMs
                        )
                        activeSession = newSession
                        val sleepState = sleepTimerManager.state.value
                        if (sleepState.isTrackTimerActive && sleepState.baselineTrackIdentity?.startsWith("baseline_${player.currentMediaItemIndex}_${currentSong.id}") == true) {
                            sleepTimerManager.setBaselineIdentity(newSession.sessionId)
                        }
                    } else {
                        existing.segmentStartTimeMs = System.currentTimeMillis()
                    }
                    startProgressTracker()
                } else {
                    val curPos = if (player.currentPosition >= 0L && player.currentPosition != androidx.media3.common.C.TIME_UNSET) player.currentPosition else currentPositionMs
                    currentPositionMs = curPos
                    _playbackPositionMs.value = curPos
                    flushSession(isFinal = false)
                    stopProgressTracker()
                }
                updateNotificationLayout()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return
                val targetMediaId = mediaItem.mediaId?.toLongOrNull()
                val currentSongId = _playbackState.value.currentSong?.id

                // Prevent initial play interruption: If this transition was caused by a playlist modification
                // (e.g. artwork or tag update) for the SAME song that is already actively playing,
                // do NOT flush the session, reset normalizer, or perform a volume ramp-down/up!
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && targetMediaId != null && targetMediaId == currentSongId) {
                    return
                }

                consecutiveErrorCount = 0
                val wasCrossfading = isCrossfadeTransitioning
                hasTriggeredCrossfadeTransition = false
                if (targetMediaId != null) {
                    audioNormalizerProcessor.setTrack(targetMediaId)
                    crossfadeNormalizerProcessor.setTrack(targetMediaId)
                } else {
                    audioNormalizerProcessor.resetNormalizer()
                    crossfadeNormalizerProcessor.resetNormalizer()
                }
                flushSession(isFinal = true)
                
                if (!wasCrossfading && crossfadeJob?.isActive != true) {
                    val transitionFadeMs = if (fadeInMs > 0) fadeInMs else 0
                    if (transitionFadeMs > 0) {
                        performVolumeRampUp(transitionFadeMs, startVolume = 0.0f, targetVolume = 1.0f)
                    } else {
                        fadeJob?.cancel()
                        fadeJob = null
                        player.volume = 1.0f
                    }
                }

                val queue = _playbackState.value.queue
                val playerIdx = player.currentMediaItemIndex
                val index = if (playerIdx in queue.indices && (targetMediaId == null || queue[playerIdx].id == targetMediaId)) {
                    playerIdx
                } else if (targetMediaId != null) {
                    val foundIndex = queue.indexOfFirst { it.id == targetMediaId }
                    if (foundIndex != -1) foundIndex else playerIdx
                } else {
                    playerIdx
                }

                val oldSession = activeSession
                val isNaturalEnd = wasCrossfading || (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                val isOldQualified = oldSession != null && (oldSession.scrobbleRecorded || oldSession.sleepTimerDecremented || isSongQualified(oldSession.accumulatedListenedMs, oldSession.totalDurationMs))
                val newSongId = targetMediaId ?: queue.getOrNull(index)?.id

                val sleepState = sleepTimerManager.state.value
                val isTrackSleepFinalStop = if (sleepState.isActive && sleepState.mode == "SONGS") {
                    sleepTimerManager.onTrackTransition(
                        oldTrackId = oldSession?.trackId,
                        isOldQualified = isOldQualified,
                        oldNaturalEnd = isNaturalEnd,
                        newTrackId = newSongId,
                        oldSessionId = oldSession?.sessionId
                    )
                } else {
                    if (oldSession != null) {
                        sleepTimerManager.onSongFinished(oldSession.trackId, isQualified = isOldQualified, sessionId = oldSession.sessionId)
                    }
                    false
                }

                if (isTrackSleepFinalStop) {
                    android.util.Log.d("SleepTimer", "G becomes current → position 0:00")
                    android.util.Log.d("SleepTimer", "Playback stopped")
                    cancelCrossfade(resetVolume = true)
                    // Update state to the new song (Song G) so Now Playing screen transits to Song G
                    if (index in queue.indices) {
                        val song = queue[index]
                        _playbackState.value = _playbackState.value.copy(
                            currentSong = song,
                            queueIndex = index,
                            positionMs = 0L,
                            durationMs = if (song.durationMs > 0L) song.durationMs else _playbackState.value.durationMs,
                            isPlaying = false,
                            playbackError = null
                        )
                    }
                    try {
                        player.pause()
                        player.playWhenReady = false
                        player.seekTo(index, 0L)
                        player.volume = 1.0f
                    } catch (_: Exception) {}
                    stopProgressTracker()
                    flushSession(isFinal = true)
                    activeSession = null
                    updateNotificationLayout()
                    com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
                    return
                }

                // Repeat Mode Logic:
                if (_playbackState.value.repeatMode == Player.REPEAT_MODE_ONE) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT || (newSongId == repeatOneTargetSongId && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)) {
                        if (repeatOneRemainingPlays > 0) {
                            // First repeat play has started; prepare to auto-advance to next song after this play ends
                            repeatOneRemainingPlays--
                            player.repeatMode = Player.REPEAT_MODE_OFF
                        } else {
                            // Completed replay; turn Repeat-One OFF
                            turnRepeatOneOff()
                        }
                    } else {
                        // Advanced to another song or skipped; turn Repeat-One OFF immediately
                        turnRepeatOneOff()
                    }
                }

                if (index in queue.indices) {
                    val song = queue[index]
                    audioNormalizerProcessor.setTrack(song.id, song.path)
                    crossfadeNormalizerProcessor.setTrack(song.id, song.path)
                    if (isRestoringPlayback) {
                        return
                    }
                    val currentPos = player.currentPosition
                    val safePos = if (currentPos > 0L && currentPos != C.TIME_UNSET) currentPos else _playbackState.value.positionMs
                    currentPositionMs = safePos
                    _playbackPositionMs.value = safePos
                    val dur = if (song.durationMs > 0L) song.durationMs else {
                        val d = player.duration
                        if (d > 0 && d != C.TIME_UNSET) d else _playbackState.value.durationMs
                    }
                    val updatedSong = if (song.durationMs <= 0L && dur > 0L) song.copy(durationMs = dur) else song
                    _playbackState.value = _playbackState.value.copy(
                        currentSong = updatedSong,
                        queueIndex = index,
                        positionMs = safePos,
                        durationMs = dur,
                        playbackError = null
                    )
                    if (player.isPlaying || player.playWhenReady) {
                        activeSession = ActiveSession(
                            sessionId = java.util.UUID.randomUUID().toString(),
                            trackId = song.id,
                            startedAt = System.currentTimeMillis(),
                            segmentStartTimeMs = System.currentTimeMillis(),
                            accumulatedListenedMs = 0L,
                            sourceContext = currentSourceContext,
                            totalDurationMs = song.durationMs
                        )
                    }

                    // Ensure active song's artwork is loaded into notification immediately
                    scope.launch(Dispatchers.IO) {
                        val artBytes = com.example.util.ArtworkManager.getNotificationArtworkBytes(
                            context = context,
                            songId = song.id,
                            filePath = song.path,
                            albumId = song.albumId,
                            artworkUriStr = song.artworkUri
                        )
                        if (artBytes != null && artBytes.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                if (_playbackState.value.currentSong?.id == song.id) {
                                    updateNotificationLayout()
                                    MusicPlaybackService.instance?.triggerNotificationUpdate()
                                }
                            }
                        }
                    }
                }
                updateNotificationLayout()
                MusicPlaybackService.instance?.triggerNotificationUpdate()
                // Do not blindly decrement sleep timer on skip; qualification happens when listened threshold is reached or track completes naturally.
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        _playbackState.value = _playbackState.value.copy(isPlaying = false, positionMs = 0L)
                        stopProgressTracker()
                        val session = activeSession
                        val finishedSongId = session?.trackId ?: _playbackState.value.currentSong?.id
                        val isQualified = session != null && (session.scrobbleRecorded || session.sleepTimerDecremented || isSongQualified(session.accumulatedListenedMs, session.totalDurationMs))

                        var sleepTimerStopped = false
                        val sleepState = sleepTimerManager.state.value
                        if (sleepState.isActive && sleepState.mode == "SONGS") {
                            val currSong = _playbackState.value.currentSong
                            if (session != null && isQualified) {
                                sleepTimerManager.onTrackOccurrenceEligible(session.sessionId, currSong?.title)
                            }
                            val stopped = sleepTimerManager.onPlaybackEnded(finishedSongId, isQualified = true, sessionId = session?.sessionId)
                            if (stopped) {
                                sleepTimerStopped = true
                                android.util.Log.d("SleepTimer", "Playback stopped")
                                try {
                                    player.pause()
                                    player.playWhenReady = false
                                    player.seekTo(player.currentMediaItemIndex, 0L)
                                    player.volume = 1.0f
                                } catch (_: Exception) {}
                            }
                        } else {
                            sleepTimerManager.onSongFinished(finishedSongId, isQualified = isQualified, sessionId = session?.sessionId)
                        }
                        flushSession(isFinal = true)

                        if (!sleepTimerStopped) {
                            val repeatMode = _playbackState.value.repeatMode
                            val queue = _playbackState.value.queue
                            if (repeatMode == Player.REPEAT_MODE_ONE) {
                                if (repeatOneRemainingPlays > 0) {
                                    repeatOneRemainingPlays--
                                    player.repeatMode = Player.REPEAT_MODE_OFF
                                    player.seekTo(player.currentMediaItemIndex, 0L)
                                    player.prepare()
                                    player.play()
                                } else {
                                    turnRepeatOneOff()
                                }
                            } else if (repeatMode == Player.REPEAT_MODE_ALL && queue.isNotEmpty()) {
                                player.seekTo(0, 0L)
                                player.prepare()
                                player.play()
                            }
                        }
                    }
                    Player.STATE_READY -> {
                        val dur = player.duration
                        val currentSong = _playbackState.value.currentSong
                        val fallbackDur = if (currentSong != null && currentSong.durationMs > 0L) currentSong.durationMs else _playbackState.value.durationMs
                        val resolvedDur = if (dur > 0 && dur != C.TIME_UNSET) dur else fallbackDur

                        val playerPos = player.currentPosition
                        val reliablePos = if (playerPos > 0L && playerPos != C.TIME_UNSET) playerPos else _playbackState.value.positionMs
                        currentPositionMs = reliablePos
                        _playbackPositionMs.value = reliablePos

                        val updatedSong = if (currentSong != null && resolvedDur > 0L && currentSong.durationMs != resolvedDur) {
                            currentSong.copy(durationMs = resolvedDur)
                        } else currentSong

                        _playbackState.value = _playbackState.value.copy(
                            currentSong = updatedSong,
                            positionMs = reliablePos,
                            durationMs = resolvedDur,
                            playbackError = null
                        )
                        if (resolvedDur > 0L && currentSong != null) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    userPreferences.saveLastPlaybackState(
                                        songId = currentSong.id,
                                        position = reliablePos,
                                        queueIds = _playbackState.value.queue.map { it.id },
                                        queueIndex = _playbackState.value.queueIndex,
                                        duration = resolvedDur
                                    )
                                } catch (_: Exception) {}
                            }
                        }
                        if (player.isPlaying || player.playWhenReady) {
                            startProgressTracker()
                        }
                    }
                    Player.STATE_IDLE -> {
                        // Keep state consistent
                    }
                    Player.STATE_BUFFERING -> {
                        // Buffering
                    }
                }
                updateNotificationLayout()
            }

            private var consecutiveErrorCount = 0

            override fun onRepeatModeChanged(repeatMode: Int) {
                _playbackState.value = _playbackState.value.copy(repeatMode = repeatMode)
                updateNotificationLayout()
                com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
            }

            override fun onPlayerError(error: PlaybackException) {
                consecutiveErrorCount++
                com.example.util.CrashReporter.logNonFatal(
                    context,
                    "PlayerError",
                    error,
                    mapOf(
                        "errorCode" to error.errorCodeName,
                        "currentSong" to (_playbackState.value.currentSong?.displayTitle ?: "None")
                    )
                )
                _playbackState.value = _playbackState.value.copy(
                    playbackError = "Playback error: ${error.localizedMessage ?: "File inaccessible"}",
                    isPlaying = false
                )
                stopProgressTracker()
                flushSession(isFinal = false)
                val queueSize = _playbackState.value.queue.size
                if (consecutiveErrorCount < queueSize && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    consecutiveErrorCount = 0
                    player.prepare()
                }
            }
        })
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val currentQueue = _playbackState.value.queue.toMutableList()
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices || fromIndex == toIndex) return
        val movedItem = currentQueue.removeAt(fromIndex)
        currentQueue.add(toIndex, movedItem)
        originalQueue = currentQueue

        try {
            if (fromIndex < player.mediaItemCount && toIndex < player.mediaItemCount) {
                player.moveMediaItem(fromIndex, toIndex)
            }
        } catch (_: Exception) {}

        val newQueueIndex = player.currentMediaItemIndex
        _playbackState.value = _playbackState.value.copy(
            queue = currentQueue,
            queueIndex = newQueueIndex,
            currentSong = currentQueue.getOrNull(newQueueIndex) ?: _playbackState.value.currentSong
        )
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
        scope.launch(Dispatchers.IO) {
            try {
                val curSong = _playbackState.value.currentSong
                val songDur = if (curSong != null && curSong.durationMs > 0L) curSong.durationMs else (if (player.duration > 0L && player.duration != C.TIME_UNSET) player.duration else _playbackState.value.durationMs)
                userPreferences.saveLastPlaybackState(
                    songId = curSong?.id ?: 0L,
                    position = player.currentPosition,
                    queueIds = currentQueue.map { it.id },
                    queueIndex = newQueueIndex,
                    duration = songDur
                )
            } catch (_: Exception) {}
        }
    }

    fun updateQueueOrder(newQueue: List<Song>) {
        if (newQueue.isEmpty()) return
        originalQueue = newQueue

        val currentSong = _playbackState.value.currentSong
        val currentQueue = _playbackState.value.queue
        val isCurrentlyPlaying = player.isPlaying || _playbackState.value.isPlaying

        val playerMediaIndex = player.currentMediaItemIndex
        val newIndex = if (playerMediaIndex in newQueue.indices && currentSong != null && newQueue[playerMediaIndex].id == currentSong.id) {
            playerMediaIndex
        } else if (currentSong != null) {
            val idx = newQueue.indexOfFirst { it.id == currentSong.id }
            if (idx >= 0) idx else 0
        } else 0

        _playbackState.value = _playbackState.value.copy(
            queue = newQueue,
            queueIndex = newIndex,
            currentSong = newQueue.getOrNull(newIndex) ?: currentSong,
            isPlaying = isCurrentlyPlaying
        )

        try {
            // Check if this is a pure reorder of the exact same queue elements (handles duplicates correctly)
            val currentCounts = currentQueue.groupingBy { it.id }.eachCount()
            val newCounts = newQueue.groupingBy { it.id }.eachCount()
            val isSameMultiset = (player.mediaItemCount == currentQueue.size &&
                    currentQueue.size == newQueue.size &&
                    currentCounts == newCounts)

            if (isSameMultiset) {
                // Tag each occurrence with an occurrence counter so duplicate song instances have distinct tokens
                fun tagQueue(q: List<Song>): List<Pair<Long, Int>> {
                    val map = mutableMapOf<Long, Int>()
                    return q.map { s ->
                        val count = map.getOrDefault(s.id, 0)
                        map[s.id] = count + 1
                        Pair(s.id, count)
                    }
                }

                val currentTagged = tagQueue(currentQueue).toMutableList()
                val newTagged = tagQueue(newQueue)

                for (targetIdx in newTagged.indices) {
                    val targetToken = newTagged[targetIdx]
                    val currIdx = currentTagged.indexOf(targetToken)
                    if (currIdx != -1 && currIdx != targetIdx) {
                        currentTagged.removeAt(currIdx)
                        currentTagged.add(targetIdx, targetToken)
                        player.moveMediaItem(currIdx, targetIdx)
                    }
                }
            } else {
                val currentPos = if (player.currentPosition > 0L && player.currentPosition != androidx.media3.common.C.TIME_UNSET) player.currentPosition else _playbackState.value.positionMs
                val mediaItems = newQueue.map { buildMediaItem(it) }
                val safeIndex = newIndex.coerceIn(mediaItems.indices)
                player.setMediaItems(mediaItems, safeIndex, currentPos)
                player.prepare()
                if (isCurrentlyPlaying) {
                    player.playWhenReady = true
                    player.play()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating player media items during queue reorder", e)
        }

        val finalQueueIndex = player.currentMediaItemIndex
        if (finalQueueIndex in newQueue.indices) {
            _playbackState.value = _playbackState.value.copy(
                queueIndex = finalQueueIndex,
                currentSong = newQueue[finalQueueIndex]
            )
        }

        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
        scope.launch(Dispatchers.IO) {
            try {
                val curSong = _playbackState.value.currentSong
                val songDur = if (curSong != null && curSong.durationMs > 0L) curSong.durationMs else _playbackState.value.durationMs
                userPreferences.saveLastPlaybackState(
                    songId = curSong?.id ?: 0L,
                    position = player.currentPosition,
                    queueIds = newQueue.map { it.id },
                    queueIndex = newIndex,
                    duration = songDur
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error saving last playback state during queue update", e)
            }
        }
    }

    fun removeFromQueue(index: Int) {
        val currentQueue = _playbackState.value.queue.toMutableList()
        if (index !in currentQueue.indices) return

        currentQueue.removeAt(index)
        player.removeMediaItem(index)

        val newQueueIndex = player.currentMediaItemIndex
        val newCurrentSong = if (currentQueue.isNotEmpty()) currentQueue.getOrNull(newQueueIndex) else null

        _playbackState.value = _playbackState.value.copy(
            queue = currentQueue,
            queueIndex = if (currentQueue.isNotEmpty()) newQueueIndex else -1,
            currentSong = newCurrentSong,
            isPlaying = if (currentQueue.isEmpty()) false else player.isPlaying
        )
        scope.launch(Dispatchers.IO) {
            try {
                val songDur = if (newCurrentSong != null && newCurrentSong.durationMs > 0L) newCurrentSong.durationMs else _playbackState.value.durationMs
                userPreferences.saveLastPlaybackState(
                    songId = newCurrentSong?.id ?: 0L,
                    position = player.currentPosition,
                    queueIds = currentQueue.map { it.id },
                    queueIndex = newQueueIndex,
                    duration = songDur
                )
            } catch (_: Exception) {}
        }
    }

    fun removeDuplicatesFromQueue(): Int {
        val currentQueue = _playbackState.value.queue
        if (currentQueue.size <= 1) return 0

        val currentIndex = _playbackState.value.queueIndex.coerceIn(0, currentQueue.lastIndex)
        val activeSong = currentQueue.getOrNull(currentIndex) ?: _playbackState.value.currentSong

        val seenIds = mutableSetOf<Long>()
        val seenPaths = mutableSetOf<String>()
        val seenMeta = mutableSetOf<String>()

        fun isDuplicate(s: Song): Boolean {
            val hasSeenId = s.id > 0 && seenIds.contains(s.id)
            val hasSeenPath = s.path.isNotBlank() && seenPaths.contains(s.path)
            val metaKey = "${s.displayTitle.lowercase().trim()}___${s.displayArtist.lowercase().trim()}"
            val hasSeenMeta = metaKey.isNotBlank() && seenMeta.contains(metaKey)
            return hasSeenId || hasSeenPath || hasSeenMeta
        }

        fun registerSong(s: Song) {
            if (s.id > 0) seenIds.add(s.id)
            if (s.path.isNotBlank()) seenPaths.add(s.path)
            val metaKey = "${s.displayTitle.lowercase().trim()}___${s.displayArtist.lowercase().trim()}"
            if (metaKey.isNotBlank()) seenMeta.add(metaKey)
        }

        val newQueue = mutableListOf<Song>()
        var removedCount = 0
        var newCurrentIndex = 0

        // Process active playing song first if valid
        val activeMetaKey = activeSong?.let { "${it.displayTitle.lowercase().trim()}___${it.displayArtist.lowercase().trim()}" }

        for (i in currentQueue.indices) {
            val song = currentQueue[i]
            if (i == currentIndex) {
                newCurrentIndex = newQueue.size
                newQueue.add(song)
                registerSong(song)
            } else {
                val isDupOfActive = activeSong != null && (
                    (song.id > 0 && song.id == activeSong.id) ||
                    (song.path.isNotBlank() && song.path == activeSong.path) ||
                    ("${song.displayTitle.lowercase().trim()}___${song.displayArtist.lowercase().trim()}" == activeMetaKey)
                )
                if (isDupOfActive || isDuplicate(song)) {
                    removedCount++
                } else {
                    newQueue.add(song)
                    registerSong(song)
                }
            }
        }

        if (removedCount > 0) {
            val currentPosition = player.currentPosition
            val isCurrentlyPlaying = player.isPlaying || _playbackState.value.isPlaying

            val mediaItems = newQueue.map { buildMediaItem(it) }
            try {
                player.setMediaItems(mediaItems, newCurrentIndex, currentPosition)
                player.prepare()
                if (isCurrentlyPlaying) {
                    player.play()
                }
            } catch (_: Exception) {}

            originalQueue = newQueue
            _playbackState.value = _playbackState.value.copy(
                queue = newQueue,
                queueIndex = newCurrentIndex,
                currentSong = newQueue.getOrNull(newCurrentIndex),
                isPlaying = isCurrentlyPlaying
            )
            updateNotificationLayout()
            com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
        }

        return removedCount
    }

    fun playNext(song: Song) {
        playNext(listOf(song))
    }

    fun playNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        val currentQueue = _playbackState.value.queue.toMutableList()
        val insertIndex = if (currentQueue.isEmpty()) 0 else (_playbackState.value.queueIndex + 1).coerceAtMost(currentQueue.size)

        currentQueue.addAll(insertIndex, songs)
        val mediaItems = songs.map { buildMediaItem(it) }
        player.addMediaItems(insertIndex, mediaItems)

        _playbackState.value = _playbackState.value.copy(
            queue = currentQueue,
            queueIndex = player.currentMediaItemIndex,
            currentSong = currentQueue.getOrNull(player.currentMediaItemIndex)
        )
        if (currentQueue.size == songs.size && player.isPlaying) {
            player.prepare()
            player.play()
        } else if (currentQueue.size == songs.size) {
            player.prepare()
        }
    }

    fun addToQueue(song: Song) {
        addToQueue(listOf(song))
    }

    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        val currentQueue = _playbackState.value.queue.toMutableList()
        val insertAtTop = addToQueuePosition.equals("TOP_NEXT", ignoreCase = true)
        val currentIndex = player.currentMediaItemIndex

        val insertIndex = if (insertAtTop && currentIndex in currentQueue.indices && _playbackState.value.currentSong != null) {
            currentIndex + 1
        } else {
            currentQueue.size
        }

        if (insertIndex in 0..currentQueue.size) {
            currentQueue.addAll(insertIndex, songs)
        } else {
            currentQueue.addAll(songs)
        }

        val mediaItems = songs.map { buildMediaItem(it) }
        if (insertIndex in 0 until player.mediaItemCount) {
            player.addMediaItems(insertIndex, mediaItems)
        } else {
            player.addMediaItems(mediaItems)
        }

        if (originalQueue.isNotEmpty()) {
            val newOriginal = originalQueue.toMutableList()
            val origCurrentIndex = newOriginal.indexOfFirst { it.id == _playbackState.value.currentSong?.id }
            if (insertAtTop && origCurrentIndex in newOriginal.indices) {
                newOriginal.addAll(origCurrentIndex + 1, songs)
            } else {
                newOriginal.addAll(songs)
            }
            originalQueue = newOriginal
        } else {
            originalQueue = currentQueue
        }

        _playbackState.value = _playbackState.value.copy(
            queue = currentQueue,
            queueIndex = player.currentMediaItemIndex,
            currentSong = currentQueue.getOrNull(player.currentMediaItemIndex)
        )
        if (currentQueue.size == songs.size && player.isPlaying) {
            player.prepare()
            player.play()
        } else if (currentQueue.size == songs.size) {
            player.prepare()
        }
    }

    fun restorePlaybackState(songs: List<Song>, startIndex: Int = 0, startPositionMs: Long = 0L, fallbackDurationMs: Long = 0L) {
        if (songs.isEmpty()) return
        originalQueue = songs
        val actualIndex = startIndex.coerceIn(songs.indices)
        val safePos = startPositionMs.coerceAtLeast(0L)
        val rawSong = songs[actualIndex]
        val reliableDur = if (rawSong.durationMs > 0L) rawSong.durationMs else (if (fallbackDurationMs > 0L) fallbackDurationMs else _playbackState.value.durationMs)
        val song = if (rawSong.durationMs <= 0L && reliableDur > 0L) rawSong.copy(durationMs = reliableDur) else rawSong

        isRestoringPlayback = true
        currentPositionMs = safePos
        _playbackPositionMs.value = safePos

        _playbackState.value = _playbackState.value.copy(
            queue = songs,
            queueIndex = actualIndex,
            currentSong = song,
            positionMs = safePos,
            durationMs = reliableDur,
            isPlaying = false,
            playbackError = null
        )

        player.playWhenReady = false
        val mediaItems = songs.map { buildMediaItem(it) }
        player.setMediaItems(mediaItems, actualIndex, safePos)
        player.prepare()
        player.pause()
        player.playWhenReady = false
        isRestoringPlayback = false
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    fun setRepeatMode(mode: Int) {
        val safeMode = when (mode) {
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }

        when (safeMode) {
            Player.REPEAT_MODE_ONE -> {
                val currentSong = _playbackState.value.currentSong
                repeatOneTargetSongId = currentSong?.id
                repeatOneRemainingPlays = 1
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
            Player.REPEAT_MODE_ALL -> {
                repeatOneTargetSongId = null
                repeatOneRemainingPlays = 0
                player.repeatMode = Player.REPEAT_MODE_ALL
            }
            else -> {
                repeatOneTargetSongId = null
                repeatOneRemainingPlays = 0
                player.repeatMode = Player.REPEAT_MODE_OFF
            }
        }

        _playbackState.value = _playbackState.value.copy(repeatMode = safeMode)
        scope.launch {
            try {
                userPreferences.setRepeatMode(safeMode)
            } catch (_: Exception) {}
        }
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    fun turnRepeatOneOff() {
        if (_playbackState.value.repeatMode == Player.REPEAT_MODE_ONE) {
            repeatOneTargetSongId = null
            repeatOneRemainingPlays = 0
            player.repeatMode = Player.REPEAT_MODE_OFF
            _playbackState.value = _playbackState.value.copy(repeatMode = Player.REPEAT_MODE_OFF)
            scope.launch {
                try {
                    userPreferences.setRepeatMode(Player.REPEAT_MODE_OFF)
                } catch (_: Exception) {}
            }
            updateNotificationLayout()
            com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
        }
    }

    fun removeSongFromQueueById(songId: Long) {
        val currentQueue = _playbackState.value.queue
        val indicesToRemove = currentQueue.mapIndexedNotNull { index, song ->
            if (song.id == songId) index else null
        }.reversed()

        indicesToRemove.forEach { index ->
            removeFromQueue(index)
        }
    }

    var alwaysShuffleMode: Boolean = false

    fun jumpToQueueIndex(index: Int) {
        if (_playbackState.value.repeatMode == Player.REPEAT_MODE_ONE) {
            turnRepeatOneOff()
        }
        val queue = _playbackState.value.queue
        if (index in queue.indices) {
            val targetSong = queue[index]
            _playbackState.value = _playbackState.value.copy(
                queueIndex = index,
                currentSong = targetSong,
                positionMs = 0L,
                durationMs = if (targetSong.durationMs > 0) targetSong.durationMs else _playbackState.value.durationMs,
                isPlaying = true,
                playbackError = null
            )
            fadeJob?.cancel()
            fadeJob = null
            player.volume = 1.0f
            if (player.mediaItemCount != queue.size) {
                val mediaItems = queue.map { buildMediaItem(it) }
                player.setMediaItems(mediaItems, index, 0L)
            } else {
                player.seekTo(index, 0L)
            }
            player.prepare()
            play()
            updateNotificationLayout()
            MusicPlaybackService.instance?.triggerNotificationUpdate()
            com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
            scope.launch(Dispatchers.IO) {
                try {
                    val songDur = if (targetSong.durationMs > 0L) targetSong.durationMs else _playbackState.value.durationMs
                    userPreferences.saveLastPlaybackState(
                        songId = targetSong.id,
                        position = 0L,
                        queueIds = queue.map { it.id },
                        queueIndex = index,
                        duration = songDur
                    )
                } catch (_: Exception) {}
            }
        }
    }

    fun playQueue(
        songs: List<Song>,
        startIndex: Int = 0,
        sourceContext: String = "UNKNOWN",
        forceShuffle: Boolean? = null
    ) {
        if (songs.isEmpty()) return
        startMusicPlaybackService()
        fadeJob?.cancel()
        fadeJob = null
        player.volume = 1.0f

        currentSourceContext = sourceContext
        originalQueue = songs
        val targetIndex = startIndex.coerceIn(songs.indices)

        val useShuffle = forceShuffle ?: (alwaysShuffleMode || _playbackState.value.isShuffleEnabled)

        val currentQueue = if (useShuffle) {
            if (forceShuffle == true && startIndex == 0) {
                songs.shuffled()
            } else {
                val selectedSong = songs[targetIndex]
                val pool = songs.filterIndexed { index, _ -> index != targetIndex }.shuffled()
                listOf(selectedSong) + pool
            }
        } else {
            songs
        }

        val actualIndex = if (useShuffle) 0 else targetIndex.coerceIn(currentQueue.indices)
        val selectedSongFromQueue = currentQueue.getOrNull(actualIndex) ?: songs[targetIndex]

        currentPositionMs = 0L
        _playbackPositionMs.value = 0L
        _playbackState.value = _playbackState.value.copy(
            isShuffleEnabled = useShuffle,
            queue = currentQueue,
            queueIndex = actualIndex,
            currentSong = selectedSongFromQueue,
            positionMs = 0L,
            durationMs = selectedSongFromQueue.durationMs,
            isPlaying = true,
            playbackError = null
        )

        audioNormalizerProcessor.setTrack(selectedSongFromQueue.id, selectedSongFromQueue.path)
        crossfadeNormalizerProcessor.setTrack(selectedSongFromQueue.id, selectedSongFromQueue.path)

        val mediaItems = currentQueue.map { s ->
            buildMediaItem(s, loadArtworkBytes = false)
        }
        if (mediaItems.isNotEmpty()) {
            player.setMediaItems(mediaItems, actualIndex, 0L)
            if (_playbackState.value.repeatMode == Player.REPEAT_MODE_ALL) {
                player.repeatMode = Player.REPEAT_MODE_ALL
            } else if (_playbackState.value.repeatMode == Player.REPEAT_MODE_ONE) {
                turnRepeatOneOff()
            } else {
                player.repeatMode = Player.REPEAT_MODE_OFF
            }
            player.prepare()
            if (fadeInMs > 0) {
                playWithFade(fadeInMs)
            } else {
                play()
            }
        }
        updateNotificationLayout()
        MusicPlaybackService.instance?.triggerNotificationUpdate()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    var fadeInMs: Int = 1000
    var fadeOutMs: Int = 1000
    var fadeInOutMs: Int = 1000
        set(value) {
            field = value
            fadeInMs = value
            fadeOutMs = value
        }
    var crossfadeDurationSec: Int = 0
    private var hasTriggeredCrossfadeTransition = false
    var gaplessPlayback: Boolean = true
    var pauseOnZeroVolume: Boolean = true
    var autoResumeOnVolumeIncrease: Boolean = true
    var wasPausedByZeroVolume = false

    private var lastVolumeChangeTime = 0L
    fun checkVolume(explicitVolume: Int? = null) {
        scope.launch(Dispatchers.Main) {
            try {
                val currentVol = explicitVolume ?: audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
                if (currentVol == 0) {
                    val isPlayingNow = player.isPlaying || player.playWhenReady || _playbackState.value.isPlaying
                    // Don't falsely pause if player just started (< 2.5 seconds) unless user explicitly reduced volume to 0
                    val currentTrackPos = player.currentPosition
                    val isInitialStartup = currentTrackPos in 0L..2500L && explicitVolume == null
                    if (isPlayingNow && pauseOnZeroVolume && !isInitialStartup) {
                        wasPausedByZeroVolume = true
                        if (fadeOutMs > 0) {
                            pauseWithFade(fromZeroVolume = true)
                        } else {
                            pause()
                            wasPausedByZeroVolume = true
                        }
                    }
                } else if (currentVol > 0) {
                    if (wasPausedByZeroVolume) {
                        wasPausedByZeroVolume = false
                        if (autoResumeOnVolumeIncrease) {
                            val notPlaying = !_playbackState.value.isPlaying && !player.isPlaying
                            if (notPlaying && _playbackState.value.queue.isNotEmpty()) {
                                if (fadeInMs > 0) {
                                    playWithFade()
                                } else {
                                    play()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private val volumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeChangeTime < 100) return
            lastVolumeChangeTime = now

            val streamType = intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) ?: -1
            val streamValue = intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1) ?: -1
            if (streamType == android.media.AudioManager.STREAM_MUSIC && streamValue >= 0) {
                checkVolume(streamValue)
            } else {
                checkVolume(null)
            }
        }
    }

    private val volumeObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            checkVolume()
        }
    }

    @Volatile
    var autoPlayOnHeadphoneConnection: Boolean = false

    private var isHeadphoneDetectionInitialized = false
    private val knownConnectedDevices = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var lastAutoPlayedDeviceKey: String? = null
    private var pendingAutoPlayJob: Job? = null

    private fun getDeviceSignature(device: android.media.AudioDeviceInfo): String {
        val address = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try { device.address } catch (_: Throwable) { "" }
        } else ""
        val name = device.productName?.toString() ?: ""
        val id = device.id
        val type = device.type
        return "dev_${type}_${id}_${name}_$address"
    }

    private fun isSupportedHeadphoneType(type: Int): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val isBle = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                type == android.media.AudioDeviceInfo.TYPE_BLE_BROADCAST
            } else false

            return type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                    type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                    type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    type == android.media.AudioDeviceInfo.TYPE_HEARING_AID ||
                    isBle
        }
        return false
    }

    private fun isDeviceStillPresent(key: String, type: Int): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val devices = audioManager?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS) ?: return false
                return devices.any { dev ->
                    isSupportedHeadphoneType(dev.type) && (getDeviceSignature(dev) == key || dev.type == type)
                }
            } catch (_: Exception) {
                return false
            }
        } else {
            @Suppress("DEPRECATION")
            return (audioManager?.isWiredHeadsetOn == true || audioManager?.isBluetoothA2dpOn == true)
        }
    }

    private fun onHeadphoneConnected(key: String, type: Int, deviceName: String?) {
        if (!isHeadphoneDetectionInitialized) return
        if (!autoPlayOnHeadphoneConnection) return
        if (knownConnectedDevices.contains(key)) return
        knownConnectedDevices.add(key)

        if (key == lastAutoPlayedDeviceKey) return

        // Cancel any pending auto-play job and debounce
        pendingAutoPlayJob?.cancel()
        pendingAutoPlayJob = scope.launch(Dispatchers.Main) {
            delay(450) // Allow connection and Android audio-output routing to stabilize

            if (!autoPlayOnHeadphoneConnection) return@launch
            if (player.isPlaying || _playbackState.value.isPlaying) return@launch

            // If the device disconnects immediately, do not incorrectly start playback afterward
            if (!isDeviceStillPresent(key, type)) {
                android.util.Log.d(TAG, "Device $deviceName disconnected immediately; aborting auto-play")
                return@launch
            }

            lastAutoPlayedDeviceKey = key
            android.util.Log.d(TAG, "Auto-playing music upon headphone connection: $deviceName ($key)")
            resumePlaybackOnHeadphoneConnect()
        }
    }

    private fun onHeadphoneDisconnected(key: String) {
        knownConnectedDevices.remove(key)
        if (lastAutoPlayedDeviceKey == key) {
            lastAutoPlayedDeviceKey = null
        }
        pendingAutoPlayJob?.cancel()
        pendingAutoPlayJob = null
    }

    private fun resumePlaybackOnHeadphoneConnect() {
        try {
            if (player.isPlaying || _playbackState.value.isPlaying) return

            if (player.mediaItemCount > 0) {
                if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0L)
                }
                if (fadeInMs > 0) {
                    playWithFade(fadeInMs)
                } else {
                    play()
                }
            } else if (_playbackState.value.queue.isNotEmpty()) {
                val queue = _playbackState.value.queue
                val idx = _playbackState.value.queueIndex.coerceIn(queue.indices)
                val pos = _playbackState.value.positionMs
                restorePlaybackState(queue, idx, pos)
                if (fadeInMs > 0) {
                    playWithFade(fadeInMs)
                } else {
                    play()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming playback on headphone connect", e)
        }
    }

    private val audioDeviceCallback = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                if (!isHeadphoneDetectionInitialized || addedDevices == null) return
                for (dev in addedDevices) {
                    if (isSupportedHeadphoneType(dev.type)) {
                        val key = getDeviceSignature(dev)
                        onHeadphoneConnected(key, dev.type, dev.productName?.toString())
                    }
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                if (removedDevices == null) return
                for (dev in removedDevices) {
                    if (isSupportedHeadphoneType(dev.type)) {
                        val key = getDeviceSignature(dev)
                        onHeadphoneDisconnected(key)
                    }
                }
            }
        }
    } else null

    private val headphoneReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isHeadphoneDetectionInitialized || intent == null) return
            when (intent.action) {
                android.media.AudioManager.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    val name = intent.getStringExtra("name") ?: "Wired Headset"
                    val key = "wired_$name"
                    if (state == 1) {
                        val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        } else 0
                        onHeadphoneConnected(key, type, name)
                    } else if (state == 0) {
                        onHeadphoneDisconnected(key)
                    }
                }
                android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1)
                    @Suppress("DEPRECATION")
                    val dev = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    val key = "bt_${dev?.address ?: "a2dp"}"
                    val devName = try { dev?.name } catch (_: SecurityException) { null } ?: "Bluetooth Audio"
                    val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    } else 0
                    if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        onHeadphoneConnected(key, type, devName)
                    } else if (state == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                        onHeadphoneDisconnected(key)
                    }
                }
                android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1)
                    @Suppress("DEPRECATION")
                    val dev = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    val key = "bt_h_${dev?.address ?: "headset"}"
                    val devName = try { dev?.name } catch (_: SecurityException) { null } ?: "Bluetooth Headset"
                    val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    } else 0
                    if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        onHeadphoneConnected(key, type, devName)
                    } else if (state == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                        onHeadphoneDisconnected(key)
                    }
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val dev = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    val key = "bt_${dev?.address ?: "acl"}"
                    val devName = try { dev?.name } catch (_: SecurityException) { null } ?: "Bluetooth Audio"
                    val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    } else 0
                    onHeadphoneConnected(key, type, devName)
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    @Suppress("DEPRECATION")
                    val dev = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    val key = "bt_${dev?.address ?: "acl"}"
                    onHeadphoneDisconnected(key)
                }
            }
        }
    }

    init {
        try {
            val filter = android.content.IntentFilter().apply {
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.EXTRA_VOLUME_STREAM_VALUE")
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(volumeReceiver, filter)
            }
            context.contentResolver.registerContentObserver(
                android.provider.Settings.System.CONTENT_URI,
                true,
                volumeObserver
            )
        } catch (_: Exception) {}

        // Register Audio Device Callback and Broadcast Receiver for Headphone auto-play
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && audioDeviceCallback != null) {
                audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
            }
            val hpFilter = android.content.IntentFilter().apply {
                addAction(android.media.AudioManager.ACTION_HEADSET_PLUG)
                addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(headphoneReceiver, hpFilter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(headphoneReceiver, hpFilter)
            }

            // Snapshot already-connected audio devices so initial callback registration or app recreate never triggers auto-play
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val existingDevs = audioManager?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                if (existingDevs != null) {
                    for (dev in existingDevs) {
                        if (isSupportedHeadphoneType(dev.type)) {
                            val key = getDeviceSignature(dev)
                            knownConnectedDevices.add(key)
                            lastAutoPlayedDeviceKey = key
                        }
                    }
                }
            }
            isHeadphoneDetectionInitialized = true
        } catch (_: Exception) {}
    }

    var reduceVolumeOnNotification: Boolean = true
        set(value) {
            field = value
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    value // handle audio focus ducking
                )
            } catch (_: Exception) {}
        }
    private var fadeJob: Job? = null

    val currentVolumePercent: Int
        get() {
            val mgr = audioManager ?: return 100
            val max = mgr.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = mgr.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            return ((cur.toFloat() / max.toFloat()) * 100).toInt().coerceIn(0, 100)
        }

    fun setDeviceVolumePercent(percent: Int) {
        try {
            val mgr = audioManager ?: return
            val max = mgr.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val target = ((percent.coerceIn(0, 100) / 100f) * max).toInt().coerceIn(0, max)
            mgr.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: Exception) {}
    }

    fun performVolumeRampUp(durationMs: Int, startVolume: Float = player.volume, targetVolume: Float = 1.0f) {
        fadeJob?.cancel()
        if (durationMs <= 0) {
            player.volume = targetVolume
            return
        }
        fadeJob = scope.launch(Dispatchers.Main) {
            try {
                val initial = startVolume.coerceIn(0f, 1f)
                player.volume = initial
                val steps = (durationMs / 25).coerceIn(10, 200)
                val stepDelay = (durationMs.toLong() / steps).coerceAtLeast(16L)
                for (i in 1..steps) {
                    val fraction = i.toFloat() / steps.toFloat()
                    val current = initial + fraction * (targetVolume - initial)
                    player.volume = current.coerceIn(0f, 1f)
                    delay(stepDelay)
                }
                player.volume = targetVolume
            } catch (_: Exception) {
            } finally {
                if (coroutineContext[Job]?.isCancelled != true) {
                    player.volume = targetVolume
                }
            }
        }
    }

    fun triggerOverlappingCrossfade(targetIndex: Int, durationMs: Long) {
        val queue = _playbackState.value.queue
        if (targetIndex !in queue.indices) return
        val currentSong = _playbackState.value.currentSong ?: return
        val targetSong = queue[targetIndex]

        // Safety check for short songs:
        if (currentSong.durationMs in 1L..2000L || targetSong.durationMs in 1L..2000L) return

        val maxFade = if (currentSong.durationMs > 0L && targetSong.durationMs > 0L) {
            minOf(currentSong.durationMs / 2, targetSong.durationMs / 2, 12000L)
        } else 12000L
        val fadeDuration = durationMs.coerceIn(800L, maxOf(800L, maxFade))

        crossfadeJob?.cancel()
        fadeJob?.cancel()
        isCrossfadeTransitioning = true

        val initialPlayerVol = player.volume.coerceIn(0.1f, 1.0f)

        try {
            crossfadePlayer.stop()
            crossfadePlayer.clearMediaItems()
            val incomingItem = buildMediaItem(targetSong)
            crossfadePlayer.setMediaItem(incomingItem, 0L)
            crossfadePlayer.volume = 0.0f
            crossfadePlayer.prepare()
            crossfadePlayer.play()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error preparing crossfadePlayer for incoming track", e)
            isCrossfadeTransitioning = false
            return
        }

        crossfadeJob = scope.launch(Dispatchers.Main) {
            try {
                val steps = (fadeDuration / 25L).coerceIn(20, 300).toInt()
                val stepDelay = (fadeDuration / steps).coerceAtLeast(16L)

                for (step in 1..steps) {
                    delay(stepDelay)
                    val progress = (step.toDouble() / steps.toDouble()).coerceIn(0.0, 1.0)
                    // Equal-Power Crossfade curves: sum of squares = 1.0 at all times (no dip in volume)
                    val outVol = (kotlin.math.cos(progress * (Math.PI / 2.0)) * initialPlayerVol).toFloat().coerceIn(0f, 1f)
                    val inVol = kotlin.math.sin(progress * (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)

                    player.volume = outVol
                    crossfadePlayer.volume = inVol
                }

                // Seamless Handover: transfer to player at the position reached by crossfadePlayer
                val handoverPos = crossfadePlayer.currentPosition.coerceAtLeast(0L)
                player.seekTo(targetIndex, handoverPos)
                player.volume = 1.0f
                player.prepare()
                player.play()

                // Keep crossfadePlayer playing for a brief 80ms bridge window to ensure zero gap while player decodes
                delay(80L)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Deliberately cancelled
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error during crossfadeJob execution", e)
            } finally {
                try {
                    crossfadePlayer.stop()
                    crossfadePlayer.clearMediaItems()
                } catch (_: Exception) {}
                player.volume = 1.0f
                isCrossfadeTransitioning = false
            }
        }
    }

    fun performVolumeRampDown(durationMs: Int, onComplete: () -> Unit) {
        fadeJob?.cancel()
        if (durationMs <= 0) {
            onComplete()
            return
        }
        fadeJob = scope.launch(Dispatchers.Main) {
            try {
                val initial = player.volume.coerceIn(0f, 1f)
                val steps = (durationMs / 25).coerceIn(10, 200)
                val stepDelay = (durationMs.toLong() / steps).coerceAtLeast(16L)
                for (i in 1..steps) {
                    val fraction = i.toFloat() / steps.toFloat()
                    val current = initial * (1f - fraction)
                    player.volume = current.coerceIn(0f, 1f)
                    delay(stepDelay)
                }
                player.volume = 0f
                onComplete()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled deliberately, do not execute onComplete()
                throw e
            } catch (_: Exception) {
                onComplete()
            }
        }
    }

    fun pause() {
        wasPausedByZeroVolume = false
        cancelCrossfade(resetVolume = true)
        fadeJob?.cancel()
        fadeJob = null
        player.playWhenReady = false
        player.pause()
        player.volume = 1.0f
        val curPos = if (player.currentPosition > 0L && player.currentPosition != androidx.media3.common.C.TIME_UNSET) player.currentPosition else currentPositionMs
        currentPositionMs = curPos
        _playbackPositionMs.value = curPos
        _playbackState.value = _playbackState.value.copy(isPlaying = false, positionMs = curPos)
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    fun pauseWithFade(customFadeOutMs: Int? = null, fromZeroVolume: Boolean = false) {
        wasPausedByZeroVolume = fromZeroVolume
        val duration = customFadeOutMs ?: fadeOutMs
        val curPos = if (player.currentPosition > 0L && player.currentPosition != androidx.media3.common.C.TIME_UNSET) player.currentPosition else currentPositionMs
        currentPositionMs = curPos
        _playbackPositionMs.value = curPos
        _playbackState.value = _playbackState.value.copy(isPlaying = false, positionMs = curPos)
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)

        if (duration > 0 && (player.isPlaying || player.playWhenReady)) {
            performVolumeRampDown(duration) {
                player.playWhenReady = false
                player.pause()
                player.volume = 1.0f
                _playbackState.value = _playbackState.value.copy(isPlaying = false)
                updateNotificationLayout()
                com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
            }
        } else {
            pause()
            wasPausedByZeroVolume = fromZeroVolume
        }
    }

    fun play() {
        fadeJob?.cancel()
        fadeJob = null
        player.volume = 1.0f
        startMusicPlaybackService()

        val queue = _playbackState.value.queue
        if (player.mediaItemCount == 0 && queue.isNotEmpty()) {
            val mediaItems = queue.map { buildMediaItem(it) }
            val idx = _playbackState.value.queueIndex.coerceIn(queue.indices)
            val safePos = _playbackState.value.positionMs.coerceAtLeast(0L)
            player.setMediaItems(mediaItems, idx, safePos)
            player.prepare()
        } else if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        } else if (player.playbackState == Player.STATE_ENDED) {
            val idx = _playbackState.value.queueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
            player.seekTo(idx, 0L)
            player.prepare()
        }

        player.playWhenReady = true
        player.play()
        startProgressTracker()

        _playbackState.value = _playbackState.value.copy(isPlaying = true, playbackError = null)
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    fun playWithFade(customFadeInMs: Int? = null) {
        fadeJob?.cancel()
        fadeJob = null
        val duration = customFadeInMs ?: fadeInMs
        startMusicPlaybackService()
        _playbackState.value = _playbackState.value.copy(isPlaying = true, playbackError = null)
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)

        val queue = _playbackState.value.queue
        if (player.mediaItemCount == 0 && queue.isNotEmpty()) {
            val mediaItems = queue.map { buildMediaItem(it) }
            val idx = _playbackState.value.queueIndex.coerceIn(queue.indices)
            val safePos = _playbackState.value.positionMs.coerceAtLeast(0L)
            player.setMediaItems(mediaItems, idx, safePos)
            player.prepare()
        } else if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        } else if (player.playbackState == Player.STATE_ENDED) {
            val idx = _playbackState.value.queueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
            player.seekTo(idx, 0L)
            player.prepare()
        }

        if (duration > 0) {
            player.volume = 0f
            player.playWhenReady = true
            player.play()
            startProgressTracker()
            _playbackState.value = _playbackState.value.copy(isPlaying = true, playbackError = null)
            performVolumeRampUp(duration, startVolume = 0f, targetVolume = 1.0f)
        } else {
            player.volume = 1.0f
            player.playWhenReady = true
            player.play()
            startProgressTracker()
            _playbackState.value = _playbackState.value.copy(isPlaying = true, playbackError = null)
        }
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    fun togglePlayPause() {
        val currentlyPlaying = _playbackState.value.isPlaying
        if (currentlyPlaying) {
            if (fadeOutMs > 0) {
                pauseWithFade()
            } else {
                pause()
            }
        } else {
            if (fadeInMs > 0) {
                playWithFade()
            } else {
                play()
            }
        }
    }

    fun skipToNext() {
        if (_playbackState.value.repeatMode == Player.REPEAT_MODE_ONE) {
            turnRepeatOneOff()
        }
        val targetIdx = if (player.hasNextMediaItem()) {
            player.currentMediaItemIndex + 1
        } else if (_playbackState.value.repeatMode == Player.REPEAT_MODE_ALL && _playbackState.value.queue.isNotEmpty()) {
            0
        } else -1

        if (targetIdx == -1) return

        // Cancel any active crossfade or fade operations immediately and reset player volume
        cancelCrossfade(resetVolume = true)
        fadeJob?.cancel()
        fadeJob = null
        player.volume = 1.0f

        val wasPlaying = player.isPlaying || player.playWhenReady || _playbackState.value.isPlaying
        player.seekTo(targetIdx, 0L)
        player.prepare()
        if (wasPlaying) {
            player.playWhenReady = true
            player.play()
        } else {
            player.playWhenReady = false
        }
    }

    fun skipToPrevious() {
        if (player.currentPosition > 3000L) {
            cancelCrossfade(resetVolume = true)
            fadeJob?.cancel()
            fadeJob = null
            player.volume = 1.0f
            val wasPlaying = player.isPlaying || player.playWhenReady || _playbackState.value.isPlaying
            player.seekTo(0L)
            player.prepare()
            if (wasPlaying) {
                player.playWhenReady = true
                player.play()
            } else {
                player.playWhenReady = false
            }
        } else {
            if (_playbackState.value.repeatMode == Player.REPEAT_MODE_ONE) {
                turnRepeatOneOff()
            }
            val targetIdx = if (player.hasPreviousMediaItem()) {
                player.currentMediaItemIndex - 1
            } else if (_playbackState.value.repeatMode == Player.REPEAT_MODE_ALL && _playbackState.value.queue.isNotEmpty()) {
                (_playbackState.value.queue.size - 1).coerceAtLeast(0)
            } else -1

            if (targetIdx == -1) return

            // Cancel any active crossfade or fade operations immediately and reset player volume
            cancelCrossfade(resetVolume = true)
            fadeJob?.cancel()
            fadeJob = null
            player.volume = 1.0f

            val wasPlaying = player.isPlaying || player.playWhenReady || _playbackState.value.isPlaying
            player.seekTo(targetIdx, 0L)
            player.prepare()
            if (wasPlaying) {
                player.playWhenReady = true
                player.play()
            } else {
                player.playWhenReady = false
            }
        }
    }

    fun seekTo(positionMs: Long) {
        cancelCrossfade(resetVolume = true)
        val safePos = positionMs.coerceAtLeast(0L)
        currentPositionMs = safePos
        _playbackPositionMs.value = safePos
        _playbackState.value = _playbackState.value.copy(positionMs = safePos)
        player.seekTo(safePos)
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    fun seekForward(deltaMs: Long = 10000L) {
        val cur = if (player.currentPosition > 0L && player.currentPosition != androidx.media3.common.C.TIME_UNSET) player.currentPosition else _playbackState.value.positionMs
        val dur = if (player.duration > 0 && player.duration != androidx.media3.common.C.TIME_UNSET) player.duration else _playbackState.value.durationMs
        val target = if (dur > 0) (cur + deltaMs).coerceAtMost(dur) else cur + deltaMs
        seekTo(target)
    }

    fun seekBackward(deltaMs: Long = 10000L) {
        val cur = if (player.currentPosition > 0L && player.currentPosition != androidx.media3.common.C.TIME_UNSET) player.currentPosition else _playbackState.value.positionMs
        val target = (cur - deltaMs).coerceAtLeast(0L)
        seekTo(target)
    }

    fun toggleShuffle(): String {
        val currentShuffle = _playbackState.value.isShuffleEnabled
        val newShuffle = !currentShuffle
        val message = if (newShuffle) "Shuffle: On" else "Shuffle: Off"

        if (originalQueue.isEmpty() && _playbackState.value.queue.isNotEmpty()) {
            originalQueue = _playbackState.value.queue
        }

        if (originalQueue.isEmpty()) {
            _playbackState.value = _playbackState.value.copy(isShuffleEnabled = newShuffle)
            return message
        }

        fadeJob?.cancel()
        fadeJob = null
        player.volume = 1.0f

        val wasPlaying = player.isPlaying || player.playWhenReady || _playbackState.value.isPlaying
        val currentSong = _playbackState.value.currentSong
        val currentPos = player.currentPosition
        val safePos = if (currentPos > 0L && currentPos != C.TIME_UNSET) currentPos else _playbackState.value.positionMs.coerceAtLeast(0L)

        val newQueue = if (newShuffle) {
            if (currentSong != null) {
                val pool = originalQueue.filter { it.id != currentSong.id }.shuffled()
                listOf(currentSong) + pool
            } else {
                originalQueue.shuffled()
            }
        } else {
            originalQueue
        }
        val newIndex = if (currentSong != null) {
            val idx = newQueue.indexOfFirst { it.id == currentSong.id }
            if (idx >= 0) idx else 0
        } else 0

        val targetSong = newQueue.getOrNull(newIndex) ?: currentSong

        _playbackState.value = _playbackState.value.copy(
            isShuffleEnabled = newShuffle,
            queue = newQueue,
            queueIndex = newIndex,
            currentSong = targetSong,
            positionMs = safePos,
            playbackError = null
        )

        val mediaItems = newQueue.map { buildMediaItem(it) }
        if (mediaItems.isNotEmpty()) {
            val safeIndex = newIndex.coerceIn(mediaItems.indices)
            player.setMediaItems(mediaItems, safeIndex, safePos)
            player.prepare()
            if (wasPlaying) {
                play()
            } else {
                player.pause()
                player.playWhenReady = false
            }
        }
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
        return message
    }

    fun toggleRepeat(): String {
        val nextMode = when (_playbackState.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        setRepeatMode(nextMode)
        return when (nextMode) {
            Player.REPEAT_MODE_ALL -> "Repeat All"
            Player.REPEAT_MODE_ONE -> "Repeat Current Track"
            else -> "Repeat Off"
        }
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressUpdateJob = scope.launch(Dispatchers.Main) {
            try {
                var lastWidgetSec = -1L
                while (player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)) {
                    if (player.isPlaying) {
                        val currentPos = player.currentPosition
                        val rawDur = player.duration
                        val totalDur = if (rawDur > 0L && rawDur != androidx.media3.common.C.TIME_UNSET) rawDur else 0L

                        // Seamless Audio Crossfade Transition
                        val crossfadeMs = (crossfadeDurationSec * 1000L).coerceIn(0L, 12000L)
                        val sleepState = sleepTimerManager.state.value
                        val isFinalSleepTrack = sleepState.isActive && (
                            (sleepState.mode == "SONGS" && (sleepState.isFinalTrack || sleepState.isFinalSleepTrack || sleepState.remainingSongs <= 1)) ||
                            (sleepState.mode == "TIME" && (sleepState.isWaitingForCurrentTrackToEnd || sleepState.remainingSeconds <= 0L))
                        )
                        val reliableDur = if (totalDur > 2000L) totalDur else (_playbackState.value.currentSong?.durationMs ?: 0L)
                        val canCrossfade = !isFinalSleepTrack && crossfadeMs > 0L && reliableDur > (crossfadeMs + 4000L) && currentPos >= 5000L
                        val inCrossfadeZone = canCrossfade && (reliableDur - currentPos) in 150L..crossfadeMs

                        if (inCrossfadeZone && !hasTriggeredCrossfadeTransition && !isCrossfadeTransitioning) {
                            val nextIdx = if (player.hasNextMediaItem()) {
                                player.currentMediaItemIndex + 1
                            } else if (_playbackState.value.repeatMode == Player.REPEAT_MODE_ALL && _playbackState.value.queue.isNotEmpty()) {
                                0
                            } else -1

                            if (nextIdx in _playbackState.value.queue.indices) {
                                hasTriggeredCrossfadeTransition = true
                                val crossfadeDur = crossfadeMs.coerceIn(800L, 12000L)
                                triggerOverlappingCrossfade(nextIdx, crossfadeDur)
                            }
                        } else if (crossfadeMs > 0L && reliableDur > (crossfadeMs + 1000L) && (reliableDur - currentPos) > crossfadeMs) {
                            hasTriggeredCrossfadeTransition = false
                            if (!isCrossfadeTransitioning && crossfadeJob?.isActive != true && player.volume < 1.0f && (fadeJob == null || !fadeJob!!.isActive)) {
                                player.volume = 1.0f
                            }
                        } else if (crossfadeMs <= 0L) {
                            hasTriggeredCrossfadeTransition = false
                            if (!isCrossfadeTransitioning && crossfadeJob?.isActive != true && player.volume < 1.0f && (fadeJob == null || !fadeJob!!.isActive)) {
                                player.volume = 1.0f
                            }
                        }

                        currentPositionMs = currentPos
                        _playbackPositionMs.value = currentPos

                        val curSec = currentPos / 1000
                        if (curSec != lastWidgetSec) {
                            lastWidgetSec = curSec
                            com.example.widget.WidgetUpdateManager.updateWidgetsProgress(context, currentPos, totalDur)
                        }

                        if (totalDur > 0L && _playbackState.value.durationMs != totalDur) {
                            _playbackState.value = _playbackState.value.copy(durationMs = totalDur)
                        }

                        val session = activeSession
                        if (session != null && session.segmentStartTimeMs > 0L) {
                            val currentMs = session.accumulatedListenedMs + (System.currentTimeMillis() - session.segmentStartTimeMs)
                            if (isSongQualified(currentMs, session.totalDurationMs)) {
                                if (!session.scrobbleRecorded) {
                                    session.scrobbleRecorded = true
                                    flushSession(isFinal = false)
                                }
                            }
                        }

                        // Check if sleep timer is waiting for current track to reach complete duration in TIME mode
                        if (sleepState.isActive) {
                            val isWaitingInTime = (sleepState.mode == "TIME" && (sleepState.isWaitingForCurrentTrackToEnd || sleepState.remainingSeconds <= 0L))
                            if (isWaitingInTime && totalDur > 0L && currentPos >= (totalDur - 350L)) {
                                val currId = _playbackState.value.currentSong?.id
                                val sess = activeSession
                                val isQualified = sess?.let { it.scrobbleRecorded || it.sleepTimerDecremented || isSongQualified(it.accumulatedListenedMs, it.totalDurationMs) } ?: true
                                sleepTimerManager.onSongFinished(currId, isQualified = isQualified, sessionId = sess?.sessionId)
                            }
                        }
                    }

                    val loopDelay = if (crossfadeDurationSec > 0) 100L else 250L
                    delay(loopDelay)
                }
            } catch (_: Exception) {}
        }
    }

    fun handleSleepTimerExpired() {
        try {
            stopProgressTracker()
            flushSession(isFinal = true)

            val currentlyPlaying = try { player.isPlaying } catch (_: Exception) { false }
            if (currentlyPlaying) {
                performVolumeRampDown(1000) {
                    try {
                        player.pause()
                        player.playWhenReady = false
                        player.volume = 1.0f
                    } catch (_: Exception) {}
                    _playbackState.value = _playbackState.value.copy(isPlaying = false)
                    updateNotificationLayout()
                    com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
                }
            } else {
                try {
                    player.pause()
                    player.playWhenReady = false
                } catch (_: Exception) {}
                _playbackState.value = _playbackState.value.copy(isPlaying = false)
                updateNotificationLayout()
                com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
            }

            val currentSong = _playbackState.value.currentSong
            val currentIdx = _playbackState.value.queueIndex
            val currentQueue = _playbackState.value.queue

            if (currentSong != null) {
                val currentPos = try { player.currentPosition.coerceAtLeast(0L) } catch (_: Exception) { 0L }
                val currentDur = if (currentSong.durationMs > 0L) currentSong.durationMs else (if (player.duration > 0L && player.duration != C.TIME_UNSET) player.duration else _playbackState.value.durationMs)
                scope.launch(Dispatchers.IO) {
                    try {
                        userPreferences.saveLastPlaybackState(
                            songId = currentSong.id,
                            position = currentPos,
                            queueIds = currentQueue.map { it.id },
                            queueIndex = currentIdx,
                            duration = currentDur
                        )
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    fun startTrackSleepTimer(count: Int) {
        val session = activeSession
        val currentSong = _playbackState.value.currentSong
        val currentSongId = currentSong?.id
        val baselineIdentity = if (session != null) {
            session.sessionId
        } else if (currentSongId != null) {
            "baseline_${player.currentMediaItemIndex}_$currentSongId"
        } else null

        sleepTimerManager.startSongCount(count, baselineIdentity, currentSongId)
    }

    private fun stopProgressTracker() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }

    fun release() {
        try { context.unregisterReceiver(volumeReceiver) } catch (e: Exception) {}
        try { context.unregisterReceiver(headphoneReceiver) } catch (e: Exception) {}
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try { audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback) } catch (e: Exception) {}
        }
        pendingAutoPlayJob?.cancel()
        pendingAutoPlayJob = null
        stopProgressTracker()
        cancelCrossfade(resetVolume = true)
        try { crossfadePlayer.release() } catch (e: Exception) {}
        try { mediaSession.release() } catch (e: Exception) {}
        player.release()
    }

    fun updateSongInQueue(updatedSong: Song) {
        com.example.util.ArtworkManager.invalidateNotificationArtwork(updatedSong.id)
        val currentQueue = _playbackState.value.queue.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == updatedSong.id }
        if (index >= 0) {
            currentQueue[index] = updatedSong
            val isCurrent = _playbackState.value.currentSong?.id == updatedSong.id
            val newCurrentSong = if (isCurrent) updatedSong else _playbackState.value.currentSong
            _playbackState.value = _playbackState.value.copy(
                queue = currentQueue,
                currentSong = newCurrentSong
            )
            try {
                val activePlayingIndex = player.currentMediaItemIndex
                // Only replace queued items that are not currently actively playing to avoid audio interruption
                if (index < player.mediaItemCount && index != activePlayingIndex) {
                    val newMediaItem = buildMediaItem(updatedSong, loadArtworkBytes = false)
                    player.replaceMediaItem(index, newMediaItem)
                }
            } catch (_: Exception) {}
            updateNotificationLayout()
            MusicPlaybackService.instance?.triggerNotificationUpdate()
            com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
        }
    }

    fun updateCurrentSongArtwork(songId: Long, artworkUri: String) {
        com.example.util.ArtworkManager.invalidateNotificationArtwork(songId)
        val currentSong = _playbackState.value.currentSong
        val newCurrentSong = if (currentSong?.id == songId) currentSong.copy(artworkUri = artworkUri) else currentSong
        val newQueue = _playbackState.value.queue.map {
            if (it.id == songId) it.copy(artworkUri = artworkUri) else it
        }
        _playbackState.value = _playbackState.value.copy(
            currentSong = newCurrentSong,
            queue = newQueue
        )
        // CRITICAL FIX: NEVER call player.replaceMediaItem on the currently playing track!
        // Calling replaceMediaItem on the active media index interrupts the audio decoder, resets the
        // audio pipeline, and triggers a volume ramp-up (silencing volume to 0) at 1-8 seconds into playback.
        // We only update queued items that are NOT currently playing.
        val activePlayingIndex = player.currentMediaItemIndex
        val targetIdx = newQueue.indexOfFirst { it.id == songId }
        if (targetIdx >= 0 && targetIdx != activePlayingIndex && targetIdx < player.mediaItemCount) {
            try {
                player.replaceMediaItem(targetIdx, buildMediaItem(newQueue[targetIdx], loadArtworkBytes = false))
            } catch (_: Exception) {}
        }
        updateNotificationLayout()
        MusicPlaybackService.instance?.triggerNotificationUpdate()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    fun updateSongFavoriteStatus(songId: Long, isFavorite: Boolean) {
        val currentSong = _playbackState.value.currentSong
        val newCurrentSong = if (currentSong?.id == songId) currentSong.copy(isFavorite = isFavorite) else currentSong
        val newQueue = _playbackState.value.queue.map {
            if (it.id == songId) it.copy(isFavorite = isFavorite) else it
        }
        _playbackState.value = _playbackState.value.copy(
            currentSong = newCurrentSong,
            queue = newQueue
        )
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    fun toggleCurrentSongFavorite() {
        val currentSong = _playbackState.value.currentSong ?: return
        val newFav = !currentSong.isFavorite
        updateSongFavoriteStatus(currentSong.id, newFav)
        scope.launch(Dispatchers.IO) {
            try {
                com.example.data.local.AppDatabase.getInstance(context).songDao().setFavoriteBySong(currentSong.id, currentSong.path, newFav)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setPreferredAudioDevice(deviceInfo: android.media.AudioDeviceInfo?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                player.setPreferredAudioDevice(deviceInfo)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
