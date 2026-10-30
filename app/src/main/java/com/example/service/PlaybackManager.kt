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
import com.example.util.ArtworkColorExtractor
import com.example.util.ArtworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
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
    val context: Context,
    externalScope: CoroutineScope? = null,
    val sleepTimerManager: SleepTimerManager
) {
    // Dedicated application supervisor scope that outlives any Activity or ViewModel,
    // ensuring background audio playback, progress tracking, crossfades, and gapless transitions
    // continue completely uninterrupted when the app is in the background.
    private val scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "PlaybackManager"
        var activeInstance: PlaybackManager? = null

        fun getInstance(context: Context): PlaybackManager {
            return activeInstance ?: synchronized(this) {
                activeInstance ?: run {
                    val app = context.applicationContext
                    val sleepTimer = SleepTimerManager()
                    PlaybackManager(app, null, sleepTimer).also { activeInstance = it }
                }
            }
        }
    }

    // activeInstance is published at the bottom of initialization once all fields and StateFlows are ready
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
    val audioFocusManager = AudioFocusManager(
        context = context,
        isPlayingProvider = {
            try {
                if (isFadingOutToPause) false else (player.isPlaying || player.playWhenReady || _playbackState.value.isPlaying)
            } catch (_: Throwable) {
                false
            }
        },
        isSleepTimerStoppedProvider = {
            isSleepTimerFinalStopping || (sleepTimerManager.state.value.phase == TrackSleepTimerPhase.STOPPED)
        },
        ownActiveMediaPlayerCountProvider = {
            var count = 0
            try {
                if (player.isPlaying || player.playWhenReady || _playbackState.value.isPlaying) count++
                if (_crossfadePlayer?.isPlaying == true) count++
            } catch (_: Throwable) {
            }
            count
        },
        onPauseRequested = { pauseFromAudioFocus() },
        onResumeRequested = { resumeFromInterruption() },
        onDuckRequested = { duckVol -> try { player.volume = duckVol } catch (_: Throwable) {} },
        onRestoreVolumeRequested = { try { player.volume = 1.0f } catch (_: Throwable) {} }
    )
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

    private fun createLoadControl(): androidx.media3.exoplayer.LoadControl {
        return androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15_000, /* retainBackBufferFromKeyframe = */ false)
            .build()
    }

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setLoadControl(createLoadControl())
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

    private var _crossfadePlayer: ExoPlayer? = null
    val crossfadePlayer: ExoPlayer
        get() {
            return _crossfadePlayer ?: synchronized(this) {
                _crossfadePlayer ?: ExoPlayer.Builder(context, crossfadeRenderersFactory)
                    .setLoadControl(createLoadControl())
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
                        setHandleAudioBecomingNoisy(true)
                        setWakeMode(C.WAKE_MODE_LOCAL)
                        playWhenReady = true
                    }.also { _crossfadePlayer = it }
            }
        }

    private var crossfadeJob: Job? = null

    fun setAudioBalance(balance: Float) {
        stereoBalanceProcessor.balance = balance
        crossfadeStereoBalanceProcessor.balance = balance
    }

    val equalizerManager: EqualizerManager = EqualizerManager(context)

    var onFavoriteToggleFromNotification: (() -> Unit)? = null

    private val forwardingListeners = java.util.concurrent.CopyOnWriteArraySet<Player.Listener>()

    private fun buildLiveMediaMetadata(
        song: Song,
        base: androidx.media3.common.MediaMetadata? = null
    ): androidx.media3.common.MediaMetadata {
        val builder = (base?.buildUpon() ?: androidx.media3.common.MediaMetadata.Builder())
            .setTitle(song.displayTitle)
            .setDisplayTitle(song.displayTitle)
            .setArtist(song.displayArtist)
            .setAlbumTitle(song.displayAlbum)
            .setAlbumArtist(song.displayArtist)

        val artBytes = ArtworkManager.getCachedNotificationArtwork(song.id, song.path)
        if (artBytes != null && artBytes.isNotEmpty()) {
            builder.setArtworkData(artBytes, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        } else {
            builder.setArtworkData(null, null)
        }

        val artUri = song.artworkUri?.takeIf { it.isNotBlank() }
            ?: com.example.ui.components.ArtworkResolutionCache.getMemory(song.id, song.path)
            ?: song.contentUriString.takeIf { it.startsWith("http") }
        if (!artUri.isNullOrBlank()) {
            try {
                builder.setArtworkUri(android.net.Uri.parse(artUri))
            } catch (_: Exception) {}
        } else {
            builder.setArtworkUri(null)
        }
        return builder.build()
    }

    fun notifyLiveMetadataChanged() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main) {
                notifyLiveMetadataChanged()
            }
            return
        }
        try {
            val liveMeta = forwardingPlayer.mediaMetadata
            val events = Player.Events(
                androidx.media3.common.FlagSet.Builder()
                    .addAll(
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED
                    )
                    .build()
            )
            for (listener in forwardingListeners) {
                try {
                    listener.onMediaMetadataChanged(liveMeta)
                    listener.onEvents(forwardingPlayer, events)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    val forwardingPlayer: androidx.media3.common.ForwardingPlayer = object : androidx.media3.common.ForwardingPlayer(player) {
        override fun addListener(listener: Player.Listener) {
            forwardingListeners.add(listener)
            super.addListener(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            forwardingListeners.remove(listener)
            super.removeListener(listener)
        }

        override fun getMediaMetadata(): androidx.media3.common.MediaMetadata {
            val base = super.getMediaMetadata()
            val currentSong = _playbackState.value.currentSong ?: return base
            return buildLiveMediaMetadata(currentSong, base)
        }

        override fun getCurrentMediaItem(): MediaItem? {
            val baseItem = super.getCurrentMediaItem()
            val currentSong = _playbackState.value.currentSong ?: return baseItem
            val liveMeta = buildLiveMediaMetadata(currentSong, baseItem?.mediaMetadata)
            return (baseItem?.buildUpon()
                ?: MediaItem.Builder().setMediaId(currentSong.id.toString()).setUri(currentSong.contentUri))
                .setMediaMetadata(liveMeta)
                .build()
        }

        override fun getMediaItemAt(index: Int): MediaItem {
            val baseItem = super.getMediaItemAt(index)
            val currentSong = _playbackState.value.currentSong
            if (currentSong != null && index == currentMediaItemIndex) {
                val liveMeta = buildLiveMediaMetadata(currentSong, baseItem.mediaMetadata)
                return baseItem.buildUpon().setMediaMetadata(liveMeta).build()
            }
            return baseItem
        }

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

    private var lastCustomLayoutFav: Boolean? = null

    fun updateNotificationLayout() {
        try {
            val currentSong = _playbackState.value.currentSong
            val isFav = currentSong?.isFavorite == true
            if (lastCustomLayoutFav != isFav) {
                lastCustomLayoutFav = isFav
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
            }
            com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateCurrentSongArtwork(songId: Long, artworkUri: String?, rawBytes: ByteArray? = null) {
        if (songId <= 0L) return
        val effectiveUri = artworkUri ?: _playbackState.value.currentSong?.takeIf { it.id == songId }?.artworkUri
        if (effectiveUri != null) {
            updateCurrentSongArtwork(songId, effectiveUri)
            return
        }
        val current = _playbackState.value.currentSong
        if (current?.id != songId) return

        scope.launch(Dispatchers.IO) {
            val bytes = rawBytes ?: ArtworkManager.getNotificationArtworkBytes(
                context = context,
                songId = songId,
                filePath = current.path,
                albumId = current.albumId,
                artworkUriStr = current.artworkUri
            )
            if (bytes != null && bytes.isNotEmpty()) {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    ArtworkManager.putCachedNotificationBitmap(songId, bmp, current.path)
                }
            }
            withContext(Dispatchers.Main) {
                updateNotificationLayout()
                MusicPlaybackService.instance?.triggerNotificationUpdate()
                com.example.widget.WidgetBitmapRenderer.invalidateSong(songId)
                com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
            }
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
            _crossfadePlayer?.stop()
            _crossfadePlayer?.clearMediaItems()
        } catch (_: Exception) {}
        if (resetVolume) {
            player.volume = 1.0f
        }
    }

    private var originalQueue: List<Song> = emptyList()
    private var progressUpdateJob: Job? = null
    private var notificationArtworkJob: Job? = null
    private var gaplessPauseJob: Job? = null
    @Volatile private var isSleepTimerFinalStopping: Boolean = false
    private var lastPrewarmedSongId: Long? = null
    private var prewarmJob: Job? = null

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

    private val userPreferences by lazy { com.example.data.local.UserPreferencesRepository.getInstance(context) }

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
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(song.displayTitle)
            .setArtist(song.displayArtist)
            .setAlbumTitle(song.displayAlbum)
            .apply {
                val art = song.artworkUri?.takeIf { it.isNotBlank() }
                    ?: com.example.ui.components.ArtworkResolutionCache.getMemory(song.id, song.path)
                    ?: song.contentUriString.takeIf { it.startsWith("http") }
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

    fun startMusicPlaybackService(action: String? = null) {
        try {
            val intent = android.content.Intent(context, MusicPlaybackService::class.java).apply {
                if (action != null) this.action = action
            }
            try {
                context.startService(intent)
            } catch (_: Throwable) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                    (player.isPlaying || player.playWhenReady || _playbackState.value.isPlaying)
                ) {
                    try {
                        context.startForegroundService(intent)
                    } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error starting MusicPlaybackService", e)
        }
    }

    @Volatile
    var isUiInForeground: Boolean = true
        private set

    fun onAppForegroundStateChanged(inForeground: Boolean) {
        isUiInForeground = inForeground
        if (inForeground) {
            val syncBlock: () -> Unit = {
                try {
                    val queue = _playbackState.value.queue
                    val mediaIdx = player.currentMediaItemIndex
                    val resolvedIdx = if (mediaIdx in queue.indices) mediaIdx else _playbackState.value.queueIndex
                    val resolvedSong = queue.getOrNull(resolvedIdx) ?: _playbackState.value.currentSong
                    val rawPos = player.currentPosition
                    val livePos = if (rawPos >= 0L && rawPos != C.TIME_UNSET) {
                        rawPos
                    } else {
                        maxOf(currentPositionMs, _playbackPositionMs.value, _playbackState.value.positionMs)
                    }
                    val rawDur = player.duration
                    val liveDur = when {
                        rawDur > 0L && rawDur != C.TIME_UNSET -> rawDur
                        _playbackState.value.durationMs > 0L -> _playbackState.value.durationMs
                        resolvedSong != null && resolvedSong.durationMs > 0L -> resolvedSong.durationMs
                        else -> 0L
                    }
                    val effectivelyPlaying = if (isFadingOutToPause) {
                        false
                    } else {
                        player.isPlaying || (player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE)
                    }
                    currentPositionMs = livePos
                    _playbackPositionMs.value = livePos
                    _playbackState.value = _playbackState.value.copy(
                        currentSong = resolvedSong,
                        queueIndex = if (resolvedIdx >= 0) resolvedIdx else _playbackState.value.queueIndex,
                        positionMs = livePos,
                        durationMs = liveDur,
                        isPlaying = effectivelyPlaying
                    )
                    if (effectivelyPlaying && progressUpdateJob?.isActive != true) {
                        startProgressTracker()
                    }
                } catch (_: Throwable) {}
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                syncBlock()
            } else {
                scope.launch(Dispatchers.Main.immediate) { syncBlock() }
            }
        } else {
            ensureBackgroundNotification()
        }
    }

    fun ensureBackgroundNotification() {
        try {
            val isActivelyPlaying = _playbackState.value.isPlaying || player.isPlaying || player.playWhenReady
            if (!isActivelyPlaying) return
            startMusicPlaybackService(MusicPlaybackService.ACTION_ENSURE_BACKGROUND_NOTIFICATION)
            updateNotificationLayout()
            MusicPlaybackService.instance?.triggerNotificationUpdate()
        } catch (_: Throwable) {}
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

        fun tagQueue(q: List<Song>): List<Pair<Long, Int>> {
            val map = mutableMapOf<Long, Int>()
            return q.map { s ->
                val count = map.getOrDefault(s.id, 0)
                map[s.id] = count + 1
                Pair(s.id, count)
            }
        }

        val currentTagged = tagQueue(currentQueue)
        val newTagged = tagQueue(newQueue)

        val currentToken = if (playerMediaIndex in currentTagged.indices) currentTagged[playerMediaIndex] else null
        val tokenNewIndex = if (currentToken != null) newTagged.indexOf(currentToken) else -1

        val newIndex = if (tokenNewIndex >= 0) {
            tokenNewIndex
        } else if (playerMediaIndex in newQueue.indices && currentSong != null && newQueue[playerMediaIndex].id == currentSong.id) {
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
            val currentCounts = currentQueue.groupingBy { it.id }.eachCount()
            val newCounts = newQueue.groupingBy { it.id }.eachCount()
            val isSameMultiset = (player.mediaItemCount == currentQueue.size &&
                    currentQueue.size == newQueue.size &&
                    currentCounts == newCounts)

            if (isSameMultiset) {
                val currentTaggedMutable = currentTagged.toMutableList()

                for (targetIdx in newTagged.indices) {
                    val targetToken = newTagged[targetIdx]
                    val currIdx = currentTaggedMutable.indexOf(targetToken)
                    if (currIdx != -1 && currIdx != targetIdx) {
                        currentTaggedMutable.removeAt(currIdx)
                        currentTaggedMutable.add(targetIdx, targetToken)
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
            scope.launch(Dispatchers.IO) {
                try {
                    val curSong = _playbackState.value.currentSong
                    val songDur = if (curSong != null && curSong.durationMs > 0L) curSong.durationMs else _playbackState.value.durationMs
                    userPreferences.saveLastPlaybackState(
                        songId = curSong?.id ?: 0L,
                        position = player.currentPosition,
                        queueIds = newQueue.map { it.id },
                        queueIndex = newCurrentIndex,
                        duration = songDur
                    )
                } catch (_: Exception) {}
            }
        }

        return removedCount
    }

    fun playNext(song: Song) {
        playNext(listOf(song))
    }

    fun playNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        val currentQueue = _playbackState.value.queue.toMutableList()
        val currentIndex = if (player.currentMediaItemIndex in currentQueue.indices) {
            player.currentMediaItemIndex
        } else if (_playbackState.value.queueIndex in currentQueue.indices) {
            _playbackState.value.queueIndex
        } else {
            -1
        }
        val insertIndex = if (currentQueue.isEmpty()) {
            0
        } else if (currentIndex >= 0) {
            (currentIndex + 1).coerceAtMost(currentQueue.size)
        } else {
            currentQueue.size
        }

        currentQueue.addAll(insertIndex, songs)
        val mediaItems = songs.map { buildMediaItem(it) }
        player.addMediaItems(insertIndex, mediaItems)

        if (originalQueue.isNotEmpty()) {
            val newOriginal = originalQueue.toMutableList()
            val origIndex = if (currentIndex in originalQueue.indices && _playbackState.value.currentSong != null && originalQueue[currentIndex].id == _playbackState.value.currentSong?.id) {
                currentIndex
            } else if (_playbackState.value.currentSong != null) {
                val found = newOriginal.indexOfFirst { it.id == _playbackState.value.currentSong?.id }
                if (found >= 0) found else newOriginal.size
            } else {
                newOriginal.size
            }
            val origInsert = (origIndex + 1).coerceIn(0, newOriginal.size)
            newOriginal.addAll(origInsert, songs)
            originalQueue = newOriginal
        } else {
            originalQueue = currentQueue
        }

        val activeIndex = player.currentMediaItemIndex.takeIf { it in currentQueue.indices } ?: currentIndex.coerceAtLeast(0)
        _playbackState.value = _playbackState.value.copy(
            queue = currentQueue,
            queueIndex = activeIndex,
            currentSong = currentQueue.getOrNull(activeIndex) ?: _playbackState.value.currentSong
        )
        scope.launch(Dispatchers.IO) {
            try {
                val curSong = _playbackState.value.currentSong
                userPreferences.saveLastPlaybackState(
                    songId = curSong?.id ?: 0L,
                    position = player.currentPosition,
                    queueIds = currentQueue.map { it.id },
                    queueIndex = activeIndex,
                    duration = _playbackState.value.durationMs
                )
            } catch (_: Exception) {}
        }
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
        val currentIndex = if (player.currentMediaItemIndex in currentQueue.indices) {
            player.currentMediaItemIndex
        } else if (_playbackState.value.queueIndex in currentQueue.indices) {
            _playbackState.value.queueIndex
        } else {
            -1
        }

        val insertIndex = if (insertAtTop && currentIndex >= 0 && _playbackState.value.currentSong != null) {
            (currentIndex + 1).coerceAtMost(currentQueue.size)
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
            val origIndex = if (currentIndex in originalQueue.indices && _playbackState.value.currentSong != null && originalQueue[currentIndex].id == _playbackState.value.currentSong?.id) {
                currentIndex
            } else if (_playbackState.value.currentSong != null) {
                val found = newOriginal.indexOfFirst { it.id == _playbackState.value.currentSong?.id }
                if (found >= 0) found else newOriginal.size
            } else {
                newOriginal.size
            }
            if (insertAtTop && origIndex in newOriginal.indices) {
                newOriginal.addAll((origIndex + 1).coerceIn(0, newOriginal.size), songs)
            } else {
                newOriginal.addAll(songs)
            }
            originalQueue = newOriginal
        } else {
            originalQueue = currentQueue
        }

        val activeIndex = player.currentMediaItemIndex.takeIf { it in currentQueue.indices } ?: currentIndex.coerceAtLeast(0)
        _playbackState.value = _playbackState.value.copy(
            queue = currentQueue,
            queueIndex = activeIndex,
            currentSong = currentQueue.getOrNull(activeIndex) ?: _playbackState.value.currentSong
        )
        scope.launch(Dispatchers.IO) {
            try {
                val curSong = _playbackState.value.currentSong
                userPreferences.saveLastPlaybackState(
                    songId = curSong?.id ?: 0L,
                    position = player.currentPosition,
                    queueIds = currentQueue.map { it.id },
                    queueIndex = activeIndex,
                    duration = _playbackState.value.durationMs
                )
            } catch (_: Exception) {}
        }
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
        originalQueue = songs

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
        player.volume = 1.0f
        isRestoringPlayback = false
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
            cancelCrossfade(resetVolume = true)
            currentPositionMs = 0L
            _playbackPositionMs.value = 0L
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
            if (fadeInMs > 0) {
                playWithFade(fadeInMs)
            } else {
                play()
            }
            launchSongArtworkRefresh(targetSong)
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
        cancelCrossfade(resetVolume = true)
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
        launchSongArtworkRefresh(selectedSongFromQueue)
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
                val streamVol = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
                val currentVol = explicitVolume ?: streamVol
                if (currentVol == 0 && (explicitVolume != null || streamVol == 0)) {
                    val isPlayingNow = player.isPlaying || player.playWhenReady || _playbackState.value.isPlaying
                    val currentTrackPos = player.currentPosition
                    val isInitialStartup = currentTrackPos in 0L..2500L && explicitVolume == null
                    if (isPlayingNow && pauseOnZeroVolume && !isInitialStartup && !audioFocusManager.hasActiveInterruption()) {
                        wasPausedByZeroVolume = true
                        if (fadeOutMs > 0) {
                            pauseWithFade(fromZeroVolume = true)
                        } else {
                            pauseInternal()
                            wasPausedByZeroVolume = true
                        }
                    }
                } else if (currentVol > 0) {
                    if (wasPausedByZeroVolume) {
                        wasPausedByZeroVolume = false
                        if (autoResumeOnVolumeIncrease &&
                            !audioFocusManager.userManuallyPausedOrStopped &&
                            !audioFocusManager.hasActiveInterruption() &&
                            !isSleepTimerFinalStopping
                        ) {
                            val notPlaying = !_playbackState.value.isPlaying && !player.isPlaying
                            if (notPlaying && _playbackState.value.queue.isNotEmpty()) {
                                if (fadeInMs > 0) {
                                    playWithFade(fromAutoResume = true)
                                } else {
                                    play(fromAutoResume = true)
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
            val streamType = intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) ?: -1
            if (streamType != android.media.AudioManager.STREAM_MUSIC) return
            val now = System.currentTimeMillis()
            if (now - lastVolumeChangeTime < 100) return
            lastVolumeChangeTime = now

            val streamValue = intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1) ?: -1
            if (streamValue >= 0) {
                checkVolume(streamValue)
            }
        }
    }

    private val volumeObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
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

        pendingAutoPlayJob?.cancel()
        pendingAutoPlayJob = scope.launch(Dispatchers.Main) {
            delay(450)

            if (!autoPlayOnHeadphoneConnection) return@launch
            if (player.isPlaying || _playbackState.value.isPlaying) return@launch

            if (isVolumeZero()) {
                android.util.Log.d(TAG, "Volume is set to zero; skipping auto-play on headphone connection")
                return@launch
            }

            if (!isDeviceStillPresent(key, type)) {
                android.util.Log.d(TAG, "Device $deviceName disconnected immediately; aborting auto-play")
                return@launch
            }

            lastAutoPlayedDeviceKey = key
            android.util.Log.d(TAG, "Auto-playing music upon headphone connection: $deviceName ($key)")
            resumePlaybackOnHeadphoneConnect()
        }
    }

    fun handleHeadphoneConnectedFromBroadcast() {
        scope.launch(Dispatchers.Main) {
            if (!autoPlayOnHeadphoneConnection) return@launch
            if (player.isPlaying || _playbackState.value.isPlaying) return@launch

            pendingAutoPlayJob?.cancel()
            pendingAutoPlayJob = scope.launch(Dispatchers.Main) {
                delay(450)
                if (!autoPlayOnHeadphoneConnection) return@launch
                if (player.isPlaying || _playbackState.value.isPlaying) return@launch

                if (isVolumeZero()) {
                    android.util.Log.d(TAG, "Volume is set to zero; skipping auto-play from broadcast")
                    return@launch
                }

                resumePlaybackOnHeadphoneConnect()
            }
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
            if (isVolumeZero()) {
                android.util.Log.d(TAG, "Volume is set to zero; skipping resumePlaybackOnHeadphoneConnect")
                return
            }

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
            } else {
                scope.launch(Dispatchers.IO) {
                    try {
                        val qIds = userPreferences.lastQueueIds.firstOrNull() ?: emptyList()
                        val qIdx = userPreferences.lastQueueIndex.firstOrNull() ?: 0
                        val pos = userPreferences.lastPosition.firstOrNull() ?: 0L
                        val db = com.example.data.local.AppDatabase.getInstance(context)
                        val songs = mutableListOf<Song>()
                        if (qIds.isNotEmpty()) {
                            for (id in qIds) {
                                db.songDao().getSongById(id)?.toDomainModel()?.let { songs.add(it) }
                            }
                        }
                        if (songs.isEmpty()) {
                            val allLibrarySongs = db.songDao().getAllSongs().firstOrNull()?.map { it.toDomainModel() } ?: emptyList()
                            songs.addAll(allLibrarySongs)
                        }
                        if (songs.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                if (!player.isPlaying && !_playbackState.value.isPlaying && autoPlayOnHeadphoneConnection) {
                                    restorePlaybackState(songs, qIdx.coerceIn(songs.indices), pos.coerceAtLeast(0L))
                                    if (fadeInMs > 0) {
                                        playWithFade(fadeInMs)
                                    } else {
                                        play()
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
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

    var reduceVolumeOnNotification: Boolean
        get() = audioFocusManager.reduceVolumeOnNotification
        set(value) {
            audioFocusManager.reduceVolumeOnNotification = value
        }

    var autoPauseCamera: Boolean
        get() = audioFocusManager.autoPauseCamera
        set(value) {
            audioFocusManager.autoPauseCamera = value
        }

    var autoPauseSystemAudio: Boolean
        get() = audioFocusManager.autoPauseSystemAudio
        set(value) {
            audioFocusManager.autoPauseSystemAudio = value
        }

    var ignoreTransientFocusLoss: Boolean
        get() = audioFocusManager.ignoreTransientFocusLoss
        set(value) {
            audioFocusManager.ignoreTransientFocusLoss = value
        }
    private var fadeJob: Job? = null
    @Volatile
    private var isFadingOutToPause: Boolean = false

    val currentVolumePercent: Int
        get() {
            val mgr = audioManager ?: return 100
            val max = mgr.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = mgr.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            return ((cur.toFloat() / max.toFloat()) * 100).toInt().coerceIn(0, 100)
        }

    var currentPreferredDevice: android.media.AudioDeviceInfo? = null

    init {
        MusicPlaybackService.activeMediaSession = mediaSession

        // Synchronously restore persisted audio engine settings before any playback or ViewModel collection
        try {
            addToQueuePosition = userPreferences.getAddToQueuePositionSync()
            autoPauseCamera = userPreferences.getAutoPauseCameraSync()
            autoPauseSystemAudio = userPreferences.getAutoPauseSystemAudioSync()
            reduceVolumeOnNotification = userPreferences.getReduceVolumeOnNotificationSync()
            pauseOnZeroVolume = userPreferences.getPauseOnZeroVolumeSync()
            autoResumeOnVolumeIncrease = userPreferences.getAutoResumeOnVolumeIncreaseSync()
            autoPlayOnHeadphoneConnection = userPreferences.getAutoPlayOnHeadphoneConnectionSync()
            fadeInMs = userPreferences.getFadeInMsSync()
            fadeOutMs = userPreferences.getFadeOutMsSync()
            crossfadeDurationSec = userPreferences.getCrossfadeDurationSecSync()
            gaplessPlayback = userPreferences.getGaplessPlaybackSync()
            isNormalizeAudioEnabled = userPreferences.getNormalizeAudioSync()
            setAudioBalance(userPreferences.getAudioBalanceSync())
            alwaysShuffleMode = userPreferences.getAlwaysShuffleModeSync()
            scrobbleThresholdType = userPreferences.getScrobbleThresholdTypeSync()
            scrobbleThresholdValue = userPreferences.getScrobbleThresholdValueSync()
            sleepTimerManager.setFinishLastSong(userPreferences.getSleepTimerFinishLastSongSync())
        } catch (_: Throwable) {}

        scope.launch {
            userPreferences.addToQueuePosition.collect { pos ->
                addToQueuePosition = pos
            }
        }
        scope.launch {
            userPreferences.autoPauseCamera.collect { enabled ->
                autoPauseCamera = enabled
            }
        }
        scope.launch {
            userPreferences.autoPauseSystemAudio.collect { enabled ->
                autoPauseSystemAudio = enabled
            }
        }
        scope.launch {
            userPreferences.reduceVolumeOnNotification.collect { enabled ->
                reduceVolumeOnNotification = enabled
            }
        }
        scope.launch {
            userPreferences.pauseOnZeroVolume.collect { enabled ->
                pauseOnZeroVolume = enabled
            }
        }
        scope.launch {
            userPreferences.autoResumeOnVolumeIncrease.collect { enabled ->
                autoResumeOnVolumeIncrease = enabled
            }
        }
        scope.launch {
            userPreferences.autoPlayOnHeadphoneConnection.collect { enabled ->
                autoPlayOnHeadphoneConnection = enabled
            }
        }
        scope.launch {
            userPreferences.fadeInMs.collect { ms ->
                fadeInMs = ms
            }
        }
        scope.launch {
            userPreferences.fadeOutMs.collect { ms ->
                fadeOutMs = ms
            }
        }
        scope.launch {
            userPreferences.crossfadeDurationSec.collect { sec ->
                crossfadeDurationSec = sec
                if (sec == 0 && isCrossfadeTransitioning) {
                    cancelCrossfade(resetVolume = true)
                }
            }
        }
        scope.launch {
            userPreferences.gaplessPlayback.collect { enabled ->
                gaplessPlayback = enabled
            }
        }
        scope.launch {
            userPreferences.normalizeAudio.collect { enabled ->
                isNormalizeAudioEnabled = enabled
            }
        }
        scope.launch {
            userPreferences.audioBalance.collect { bal ->
                setAudioBalance(bal)
            }
        }
        scope.launch {
            userPreferences.sleepTimerFinishLastSong.collect { enabled ->
                sleepTimerManager.setFinishLastSong(enabled)
            }
        }

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizerManager.initAudioSession(audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Issue #8 Fix: During an overlapping crossfade, seeking `player` to the incoming track
                // briefly puts `player` into STATE_BUFFERING (so `isPlaying` is transiently false while
                // `playWhenReady` is still true and `crossfadePlayer` is actively playing).
                // Never cancel the crossfade or flip UI state to paused during crossfade buffering!
                if (!isPlaying && isCrossfadeTransitioning && player.playWhenReady) {
                    _playbackState.value = _playbackState.value.copy(isPlaying = true)
                    return
                }
                if (isFadingOutToPause && isPlaying) {
                    _playbackState.value = _playbackState.value.copy(isPlaying = false)
                    return
                }

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
                    if (isCrossfadeTransitioning && !player.playWhenReady) {
                        cancelCrossfade(resetVolume = true)
                    }
                    val curPos = if (player.currentPosition >= 0L && player.currentPosition != androidx.media3.common.C.TIME_UNSET) player.currentPosition else currentPositionMs
                    currentPositionMs = curPos
                    _playbackPositionMs.value = curPos
                    flushSession(isFinal = false)
                    stopProgressTracker()
                }
                updateNotificationLayout()
                com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return
                if (isSleepTimerFinalStopping) return
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
                lastPrewarmedSongId = targetMediaId ?: currentSongId
                if (targetMediaId != null) {
                    audioNormalizerProcessor.setTrack(targetMediaId)
                    if (!wasCrossfading) {
                        crossfadeNormalizerProcessor.setTrack(targetMediaId)
                    }
                } else {
                    audioNormalizerProcessor.resetNormalizer()
                    if (!wasCrossfading) {
                        crossfadeNormalizerProcessor.resetNormalizer()
                    }
                }
                // Capture oldSession before flushing so sleep timer can identify the finishing track
                val oldSession = activeSession
                val oldTrackId = oldSession?.trackId ?: currentSongId
                val oldSessionId = oldSession?.sessionId ?: currentSongId?.let { "song_$it" }
                val isNaturalEnd = wasCrossfading || (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                val isOldQualified = oldSession != null && (oldSession.scrobbleRecorded || oldSession.sleepTimerDecremented || isSongQualified(oldSession.accumulatedListenedMs, oldSession.totalDurationMs))
                flushSession(isFinal = true)

                val queue = _playbackState.value.queue
                val playerIdx = player.currentMediaItemIndex
                val index = if (playerIdx in queue.indices) {
                    playerIdx
                } else if (targetMediaId != null) {
                    val foundIndex = queue.indexOfFirst { it.id == targetMediaId }
                    if (foundIndex != -1) foundIndex else playerIdx
                } else {
                    playerIdx
                }

                val newSongId = targetMediaId ?: queue.getOrNull(index)?.id

                val sleepState = sleepTimerManager.state.value
                val isTrackSleepFinalStop = if (sleepState.isActive && sleepState.mode == "SONGS") {
                    sleepTimerManager.onTrackTransition(
                        oldTrackId = oldTrackId,
                        isOldQualified = isOldQualified,
                        oldNaturalEnd = isNaturalEnd,
                        newTrackId = newSongId,
                        oldSessionId = oldSessionId
                    )
                } else false

                val isTimeSleepFinalStop = if (sleepState.isActive && sleepState.mode == "TIME" && sleepState.finishLastSong) {
                    sleepState.isWaitingForCurrentTrackToEnd || sleepState.remainingSeconds <= 0L
                } else false

                if (isTrackSleepFinalStop || isTimeSleepFinalStop) {
                    isSleepTimerFinalStopping = true
                    try {
                        // Immediately mute & pause player before any audio from the next song can be rendered
                        try {
                            player.volume = 0f
                            player.playWhenReady = false
                            player.pause()
                        } catch (_: Exception) {}

                        gaplessPauseJob?.cancel()
                        gaplessPauseJob = null
                        fadeJob?.cancel()
                        fadeJob = null
                        cancelCrossfade(resetVolume = false)

                        if (isTimeSleepFinalStop) {
                            android.util.Log.d("SleepTimer", "TIME mode finishLastTrack: current track completed → transitioning to next track ($newSongId) at 0:00 and stopping")
                            sleepTimerManager.cancelTimer()
                        } else {
                            android.util.Log.d("SleepTimer", "Track sleep timer completed → next track ($newSongId) becomes current at 0:00 and stops")
                        }

                        // Update state to the new song (Song E or Song G) so the screen transitions to it and stops at 0:00
                        val targetSong = queue.getOrNull(index)
                        if (targetSong != null) {
                            currentPositionMs = 0L
                            _playbackPositionMs.value = 0L
                            _playbackState.value = _playbackState.value.copy(
                                currentSong = targetSong,
                                queueIndex = index,
                                positionMs = 0L,
                                durationMs = if (targetSong.durationMs > 0L) targetSong.durationMs else _playbackState.value.durationMs,
                                isPlaying = false,
                                playbackError = null
                            )
                            scope.launch(Dispatchers.IO) {
                                try {
                                    userPreferences.saveLastPlaybackState(
                                        songId = targetSong.id,
                                        position = 0L,
                                        queueIds = queue.map { it.id },
                                        queueIndex = index,
                                        duration = targetSong.durationMs
                                    )
                                } catch (_: Exception) {}
                            }
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
                        MusicPlaybackService.instance?.triggerNotificationUpdate()
                        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
                    } finally {
                        isSleepTimerFinalStopping = false
                    }
                    return
                }

                val isNaturalTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT

                if (!wasCrossfading && crossfadeJob?.isActive != true) {
                    val willBePlaying = isNaturalTransition || player.isPlaying || player.playWhenReady
                    if (isNaturalTransition && gaplessPlayback && crossfadeDurationSec <= 0) {
                        // True gapless transition: maintain unity volume across track boundary with zero dip
                        fadeJob?.cancel()
                        fadeJob = null
                        player.volume = 1.0f
                    } else if (fadeInMs > 0 && willBePlaying) {
                        val safeStartVol = if (isNaturalTransition) {
                            // Non-gapless natural transition: start from 0.0f (or current attenuated level after fadeOutMs)
                            if (player.volume < 0.25f) player.volume.coerceAtLeast(0.0f) else 0.0f
                        } else {
                            0.0f
                        }
                        performVolumeRampUp(fadeInMs.coerceIn(0, 10000), startVolume = safeStartVol, targetVolume = 1.0f)
                    } else {
                        fadeJob?.cancel()
                        fadeJob = null
                        player.volume = 1.0f
                    }
                }

                // Repeat Mode Logic:
                if (_playbackState.value.repeatMode == Player.REPEAT_MODE_ONE) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT || (newSongId == repeatOneTargetSongId && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)) {
                        if (repeatOneRemainingPlays > 0) {
                            repeatOneRemainingPlays--
                            player.repeatMode = Player.REPEAT_MODE_OFF
                        } else {
                            turnRepeatOneOff()
                        }
                    } else {
                        turnRepeatOneOff()
                    }
                }

                if (index in queue.indices) {
                    val song = queue[index]
                    audioNormalizerProcessor.setTrack(song.id, song.path)
                    if (!wasCrossfading) {
                        crossfadeNormalizerProcessor.setTrack(song.id, song.path)
                    }
                    if (isRestoringPlayback) {
                        return
                    }

                    val isTrackChange = (targetMediaId != null && targetMediaId != currentSongId) ||
                            (song.id != currentSongId) ||
                            (index != _playbackState.value.queueIndex)

                    val authoritativePos = if (isTrackChange) {
                        0L
                    } else {
                        val currentPos = player.currentPosition
                        if (currentPos >= 0L && currentPos != C.TIME_UNSET) currentPos else 0L
                    }
                    currentPositionMs = authoritativePos
                    _playbackPositionMs.value = authoritativePos
                    val dur = if (song.durationMs > 0L) song.durationMs else {
                        val d = player.duration
                        if (d > 0 && d != C.TIME_UNSET) d else _playbackState.value.durationMs
                    }
                    val updatedSong = if (song.durationMs <= 0L && dur > 0L) song.copy(durationMs = dur) else song

                    val isPlayingNow = if (isNaturalTransition || wasCrossfading) {
                        true
                    } else {
                        player.isPlaying || player.playWhenReady
                    }

                    _playbackState.value = _playbackState.value.copy(
                        currentSong = updatedSong,
                        queueIndex = index,
                        positionMs = authoritativePos,
                        durationMs = dur,
                        isPlaying = isPlayingNow,
                        playbackError = null
                    )
                    if (isPlayingNow) {
                        activeSession = ActiveSession(
                            sessionId = java.util.UUID.randomUUID().toString(),
                            trackId = song.id,
                            startedAt = System.currentTimeMillis(),
                            segmentStartTimeMs = System.currentTimeMillis(),
                            accumulatedListenedMs = 0L,
                            sourceContext = currentSourceContext,
                            totalDurationMs = song.durationMs
                        )
                        startProgressTracker()
                    } else {
                        stopProgressTracker()
                    }

                    if (isNaturalTransition && !isSleepTimerFinalStopping) {
                        try {
                            player.playWhenReady = true
                            player.play()
                        } catch (_: Exception) {}
                    }

                    launchSongArtworkRefresh(song)
                }
                updateNotificationLayout()
                MusicPlaybackService.instance?.triggerNotificationUpdate()
                com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
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
                        val queue = _playbackState.value.queue
                        if (sleepState.isActive && sleepState.mode == "SONGS") {
                            val currSong = _playbackState.value.currentSong
                            if (session != null && isQualified) {
                                sleepTimerManager.onTrackOccurrenceEligible(session.sessionId, currSong?.title)
                            }
                            val stopped = sleepTimerManager.onPlaybackEnded(finishedSongId, isQualified = true, sessionId = session?.sessionId)
                            if (stopped) {
                                sleepTimerStopped = true
                                val nextIndex = if (queue.size > 1) {
                                    (_playbackState.value.queueIndex + 1) % queue.size
                                } else {
                                    player.currentMediaItemIndex.coerceAtLeast(0)
                                }
                                val nextSong = queue.getOrNull(nextIndex) ?: _playbackState.value.currentSong
                                currentPositionMs = 0L
                                _playbackPositionMs.value = 0L
                                if (nextSong != null) {
                                    _playbackState.value = _playbackState.value.copy(
                                        currentSong = nextSong,
                                        queueIndex = nextIndex,
                                        positionMs = 0L,
                                        durationMs = if (nextSong.durationMs > 0L) nextSong.durationMs else _playbackState.value.durationMs,
                                        isPlaying = false
                                    )
                                }
                                try {
                                    player.pause()
                                    player.playWhenReady = false
                                    player.seekTo(nextIndex, 0L)
                                    player.volume = 1.0f
                                } catch (_: Exception) {}
                            }
                        } else if (sleepState.isActive && sleepState.mode == "TIME") {
                            sleepTimerManager.cancelTimer()
                            sleepTimerStopped = true
                            val nextIndex = if (sleepState.finishLastSong && queue.size > 1) {
                                (_playbackState.value.queueIndex + 1) % queue.size
                            } else {
                                player.currentMediaItemIndex.coerceAtLeast(0)
                            }
                            val nextSong = queue.getOrNull(nextIndex) ?: _playbackState.value.currentSong
                            currentPositionMs = 0L
                            _playbackPositionMs.value = 0L
                            if (nextSong != null) {
                                _playbackState.value = _playbackState.value.copy(
                                    currentSong = nextSong,
                                    queueIndex = nextIndex,
                                    positionMs = 0L,
                                    durationMs = if (nextSong.durationMs > 0L) nextSong.durationMs else _playbackState.value.durationMs,
                                    isPlaying = false
                                )
                            }
                            try {
                                player.pause()
                                player.playWhenReady = false
                                player.seekTo(nextIndex, 0L)
                                player.volume = 1.0f
                            } catch (_: Exception) {}
                        }
                        flushSession(isFinal = true)

                        if (!sleepTimerStopped) {
                            val repeatMode = _playbackState.value.repeatMode
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
                        val reliablePos = if (playerPos >= 0L && playerPos != C.TIME_UNSET) playerPos else currentPositionMs
                        currentPositionMs = reliablePos
                        _playbackPositionMs.value = reliablePos

                        val updatedSong = if (currentSong != null && resolvedDur > 0L && currentSong.durationMs != resolvedDur) {
                            currentSong.copy(durationMs = resolvedDur)
                        } else currentSong

                        val isPlayingNow = if (isFadingOutToPause) false else (player.isPlaying || player.playWhenReady)
                        _playbackState.value = _playbackState.value.copy(
                            currentSong = updatedSong,
                            positionMs = reliablePos,
                            durationMs = resolvedDur,
                            isPlaying = isPlayingNow,
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

        sleepTimerManager.isPlaybackActive = {
            try {
                player.isPlaying || player.playWhenReady
            } catch (_: Throwable) {
                false
            }
        }
        sleepTimerManager.onTimerExpired = { handleSleepTimerExpired() }
        activeInstance = this
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
                val startTime = android.os.SystemClock.elapsedRealtime()
                while (true) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
                    if (elapsed >= durationMs) break
                    val fraction = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    val current = initial + fraction * (targetVolume - initial)
                    player.volume = current.coerceIn(0f, 1f)
                    delay(16L)
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

        if (currentSong.durationMs in 1L..2000L || targetSong.durationMs in 1L..2000L) return

        val maxFade = if (currentSong.durationMs > 0L && targetSong.durationMs > 0L) {
            minOf(currentSong.durationMs / 2, targetSong.durationMs / 2, 12000L)
        } else 12000L
        val fadeDuration = durationMs.coerceIn(800L, maxOf(800L, maxFade))

        crossfadeJob?.cancel()
        fadeJob?.cancel()
        isCrossfadeTransitioning = true

        val initialPlayerVol = player.volume.coerceIn(0.15f, 1.0f)

        crossfadeJob = scope.launch(Dispatchers.Main) {
            try {
                // 1. Prepare crossfadePlayer with the outgoing track A while primary player continues playing track A uninterrupted
                crossfadePlayer.stop()
                crossfadePlayer.clearMediaItems()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    currentPreferredDevice?.let { crossfadePlayer.setPreferredAudioDevice(it) }
                }
                crossfadePlayer.playbackParameters = player.playbackParameters
                crossfadeNormalizerProcessor.setTrack(currentSong.id, currentSong.path)
                val currentPos = player.currentPosition.coerceAtLeast(0L)
                val outgoingItem = buildMediaItem(currentSong)
                crossfadePlayer.setMediaItem(outgoingItem, currentPos)
                crossfadePlayer.volume = initialPlayerVol
                crossfadePlayer.prepare()

                // Wait for crossfadePlayer to be ready (up to 450ms) so outgoing audio flows with zero gap
                var waitElapsed = 0L
                while (crossfadePlayer.playbackState != Player.STATE_READY && waitElapsed < 450L && coroutineContext[Job]?.isActive == true) {
                    delay(16L)
                    waitElapsed += 16L
                }

                // 2. Start crossfadePlayer on outgoing track A, and transition primary player to incoming track B from 0:00
                crossfadePlayer.playWhenReady = true
                crossfadePlayer.play()
                audioNormalizerProcessor.setTrack(targetSong.id, targetSong.path)
                player.volume = 0.0f
                player.seekTo(targetIndex, 0L)
                player.playWhenReady = true
                player.play()

                // Wait briefly for primary player to buffer incoming track B before ramping volume
                var inWaitElapsed = 0L
                while (player.playbackState == Player.STATE_BUFFERING && inWaitElapsed < 350L && coroutineContext[Job]?.isActive == true) {
                    delay(16L)
                    inWaitElapsed += 16L
                }

                // 3. Smooth Equal-Power Crossfade curves (sine / cosine energy conservation)
                val startTime = android.os.SystemClock.elapsedRealtime()
                while (coroutineContext[Job]?.isActive == true) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
                    if (elapsed >= fadeDuration) break
                    val progress = (elapsed.toDouble() / fadeDuration.toDouble()).coerceIn(0.0, 1.0)
                    val outVol = (kotlin.math.cos(progress * (Math.PI / 2.0)) * initialPlayerVol).toFloat().coerceIn(0f, 1f)
                    val inVol = kotlin.math.sin(progress * (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)

                    crossfadePlayer.volume = outVol
                    player.volume = inVol
                    delay(16L)
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Deliberately cancelled
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error during crossfadeJob execution", e)
            } finally {
                val wasCancelled = coroutineContext[Job]?.isCancelled == true
                try {
                    crossfadePlayer.stop()
                    crossfadePlayer.clearMediaItems()
                } catch (_: Exception) {}
                if (!wasCancelled && player.playWhenReady && !isFadingOutToPause) {
                    player.volume = 1.0f
                }
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
                val startTime = android.os.SystemClock.elapsedRealtime()
                while (true) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
                    if (elapsed >= durationMs) break
                    val fraction = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    val current = initial * (1f - fraction)
                    player.volume = current.coerceIn(0f, 1f)
                    delay(16L)
                }
                player.volume = 0f
                onComplete()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                onComplete()
            }
        }
    }

    private fun pauseFromAudioFocus() {
        pauseInternal()
    }

    private fun resumeFromInterruption() {
        if (isSleepTimerFinalStopping) return
        val alreadyPlaying = !isFadingOutToPause && (player.isPlaying || _playbackState.value.isPlaying)
        if (alreadyPlaying) return
        if (fadeInMs > 0) {
            playWithFade(fadeInMs, fromAutoResume = true)
        } else {
            play(fromAutoResume = true)
        }
    }

    fun pause() {
        audioFocusManager.cancelPendingResume()
        pauseInternal()
    }

    private fun pauseInternal() {
        isFadingOutToPause = false
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
        com.example.widget.WidgetUpdateManager.setPlayStateImmediate(context, false)
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    fun pauseWithFade(customFadeOutMs: Int? = null, fromZeroVolume: Boolean = false) {
        if (!fromZeroVolume) {
            audioFocusManager.cancelPendingResume()
        }
        wasPausedByZeroVolume = fromZeroVolume
        val duration = (customFadeOutMs ?: fadeOutMs).coerceIn(0, 10000)
        val curPos = if (player.currentPosition > 0L && player.currentPosition != androidx.media3.common.C.TIME_UNSET) player.currentPosition else currentPositionMs
        currentPositionMs = curPos
        _playbackPositionMs.value = curPos
        // Immediately flip UI, notification, and widget state to paused (0ms latency)
        // while keeping audio fading out smoothly in the background!
        isFadingOutToPause = true
        _playbackState.value = _playbackState.value.copy(isPlaying = false, positionMs = curPos)
        com.example.widget.WidgetUpdateManager.setPlayStateImmediate(context, false)
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)

        if (duration > 0 && (player.isPlaying || player.playWhenReady)) {
            performVolumeRampDown(duration) {
                isFadingOutToPause = false
                player.playWhenReady = false
                player.pause()
                player.volume = 1.0f
                val finalPos = if (player.currentPosition > 0L && player.currentPosition != androidx.media3.common.C.TIME_UNSET) player.currentPosition else currentPositionMs
                currentPositionMs = finalPos
                _playbackPositionMs.value = finalPos
                _playbackState.value = _playbackState.value.copy(isPlaying = false, positionMs = finalPos)
                updateNotificationLayout()
                com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
            }
        } else {
            isFadingOutToPause = false
            pauseInternal()
            wasPausedByZeroVolume = fromZeroVolume
        }
    }

    fun play(fromAutoResume: Boolean = false) {
        if (!fromAutoResume) {
            audioFocusManager.onUserPlayRequested()
        }
        val wasFadingOut = isFadingOutToPause
        isFadingOutToPause = false
        if (!fromAutoResume || !audioFocusManager.hasAudioFocus) {
            audioFocusManager.requestAudioFocus()
        }
        fadeJob?.cancel()
        fadeJob = null
        player.volume = 1.0f

        val queue = _playbackState.value.queue
        if (player.mediaItemCount == 0 && queue.isEmpty()) {
            return
        }
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

        // Immediately update UI, notification, and widgets to playing state (0ms latency)
        _playbackState.value = _playbackState.value.copy(isPlaying = true, playbackError = null)
        com.example.widget.WidgetUpdateManager.setPlayStateImmediate(context, true)
        player.playWhenReady = true
        if (!wasFadingOut || !player.isPlaying) {
            player.play()
        }
        startProgressTracker()
        startMusicPlaybackService()
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    fun playWithFade(customFadeInMs: Int? = null, fromAutoResume: Boolean = false) {
        if (!fromAutoResume) {
            audioFocusManager.onUserPlayRequested()
        }
        val wasFadingOut = isFadingOutToPause
        val currentVolBeforeResume = player.volume.coerceIn(0f, 1f)
        isFadingOutToPause = false
        if (!fromAutoResume || !audioFocusManager.hasAudioFocus) {
            audioFocusManager.requestAudioFocus()
        }
        fadeJob?.cancel()
        fadeJob = null
        val duration = (customFadeInMs ?: fadeInMs).coerceIn(0, 10000)

        val queue = _playbackState.value.queue
        if (player.mediaItemCount == 0 && queue.isEmpty()) {
            return
        }
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

        // Immediately update UI, notification, and widgets to playing state (0ms latency)
        // before starting the volume ramp-up so the Play/Pause button responds instantaneously!
        _playbackState.value = _playbackState.value.copy(isPlaying = true, playbackError = null)
        com.example.widget.WidgetUpdateManager.setPlayStateImmediate(context, true)

        if (duration > 0) {
            val startVol = if (wasFadingOut && player.isPlaying) currentVolBeforeResume else 0.0f
            player.volume = startVol
            player.playWhenReady = true
            if (!player.isPlaying) {
                player.play()
            }
            startProgressTracker()
            performVolumeRampUp(duration, startVolume = startVol, targetVolume = 1.0f)
        } else {
            player.volume = 1.0f
            player.playWhenReady = true
            if (!player.isPlaying) {
                player.play()
            }
            startProgressTracker()
        }
        startMusicPlaybackService()
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    fun togglePlayPause() {
        val currentlyPlaying = if (isFadingOutToPause) false else (_playbackState.value.isPlaying || player.isPlaying)
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

        cancelCrossfade(resetVolume = true)
        fadeJob?.cancel()
        fadeJob = null
        player.volume = 1.0f

        val wasPlaying = player.isPlaying || player.playWhenReady || _playbackState.value.isPlaying
        val targetSong = _playbackState.value.queue.getOrNull(targetIdx)

        currentPositionMs = 0L
        _playbackPositionMs.value = 0L
        if (targetSong != null) {
            _playbackState.value = _playbackState.value.copy(
                currentSong = targetSong,
                queueIndex = targetIdx,
                positionMs = 0L,
                durationMs = if (targetSong.durationMs > 0L) targetSong.durationMs else _playbackState.value.durationMs,
                isPlaying = wasPlaying,
                playbackError = null
            )
        }

        player.seekTo(targetIdx, 0L)
        player.prepare()
        if (wasPlaying && !isSleepTimerFinalStopping) {
            player.playWhenReady = true
            player.play()
            if (fadeInMs > 0) {
                performVolumeRampUp(fadeInMs.coerceIn(0, 10000), startVolume = 0.0f, targetVolume = 1.0f)
            } else {
                player.volume = 1.0f
            }
            startProgressTracker()
        } else {
            player.playWhenReady = false
            player.pause()
            stopProgressTracker()
        }
        updateNotificationLayout()
        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
    }

    fun skipToPrevious() {
        if (player.currentPosition > 3000L) {
            cancelCrossfade(resetVolume = true)
            fadeJob?.cancel()
            fadeJob = null
            player.volume = 1.0f
            val wasPlaying = player.isPlaying || player.playWhenReady || _playbackState.value.isPlaying
            currentPositionMs = 0L
            _playbackPositionMs.value = 0L
            _playbackState.value = _playbackState.value.copy(positionMs = 0L, isPlaying = wasPlaying)
            player.seekTo(0L)
            player.prepare()
            if (wasPlaying) {
                player.playWhenReady = true
                player.play()
                if (fadeInMs > 0) {
                    performVolumeRampUp(fadeInMs.coerceIn(0, 10000), startVolume = 0.0f, targetVolume = 1.0f)
                } else {
                    player.volume = 1.0f
                }
                startProgressTracker()
            } else {
                player.playWhenReady = false
                player.pause()
                stopProgressTracker()
            }
            updateNotificationLayout()
            com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
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

            cancelCrossfade(resetVolume = true)
            fadeJob?.cancel()
            fadeJob = null
            player.volume = 1.0f

            val wasPlaying = player.isPlaying || player.playWhenReady || _playbackState.value.isPlaying
            val targetSong = _playbackState.value.queue.getOrNull(targetIdx)

            currentPositionMs = 0L
            _playbackPositionMs.value = 0L
            if (targetSong != null) {
                _playbackState.value = _playbackState.value.copy(
                    currentSong = targetSong,
                    queueIndex = targetIdx,
                    positionMs = 0L,
                    durationMs = if (targetSong.durationMs > 0L) targetSong.durationMs else _playbackState.value.durationMs,
                    isPlaying = wasPlaying,
                    playbackError = null
                )
            }

            player.seekTo(targetIdx, 0L)
            player.prepare()
            if (wasPlaying) {
                player.playWhenReady = true
                player.play()
                if (fadeInMs > 0) {
                    performVolumeRampUp(fadeInMs.coerceIn(0, 10000), startVolume = 0.0f, targetVolume = 1.0f)
                } else {
                    player.volume = 1.0f
                }
                startProgressTracker()
            } else {
                player.playWhenReady = false
                player.pause()
                stopProgressTracker()
            }
            updateNotificationLayout()
            com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
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
                var inCrossfadeZone = false
                var inTrackEndFadeOut = false
                while (player.isPlaying || (player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE)) {
                    inCrossfadeZone = false
                    inTrackEndFadeOut = false
                    if (player.isPlaying) {
                        val currentPos = player.currentPosition.coerceAtLeast(0L)
                        val rawDur = player.duration
                        val totalDur = if (rawDur > 0L && rawDur != androidx.media3.common.C.TIME_UNSET) rawDur else 0L

                        // Seamless Audio Crossfade Transition
                        val crossfadeMs = (crossfadeDurationSec * 1000L).coerceIn(0L, 12000L)
                        val sleepState = sleepTimerManager.state.value
                        val isFinalSleepTrack = sleepState.isActive && (
                            (sleepState.mode == "SONGS" && (sleepState.isFinalTrack || sleepState.isFinalSleepTrack)) ||
                            (sleepState.mode == "TIME" && (sleepState.isWaitingForCurrentTrackToEnd || sleepState.remainingSeconds <= 0L))
                        )
                        val isRepeatOne = _playbackState.value.repeatMode == Player.REPEAT_MODE_ONE
                        val reliableDur = if (totalDur > 2000L) totalDur else (_playbackState.value.currentSong?.durationMs ?: 0L)
                        val canCrossfade = !isFinalSleepTrack && !isRepeatOne && crossfadeMs > 0L && reliableDur > (crossfadeMs + 2000L) && currentPos >= 1500L
                        inCrossfadeZone = canCrossfade && (reliableDur - currentPos) in 150L..crossfadeMs
                        val trackEndFadeOutMs = fadeOutMs.coerceIn(0, 10000).toLong()
                        val effectiveEndFadeWindowMs = when {
                            gaplessPlayback -> 0L
                            trackEndFadeOutMs > 0L -> trackEndFadeOutMs
                            fadeInMs > 0 -> 140L
                            else -> 0L
                        }
                        inTrackEndFadeOut = !gaplessPlayback && crossfadeMs <= 0L && !isRepeatOne && effectiveEndFadeWindowMs > 0L &&
                            reliableDur > (effectiveEndFadeWindowMs + 2000L) && currentPos >= 1500L &&
                            (reliableDur - currentPos) <= effectiveEndFadeWindowMs

                        if (inCrossfadeZone && !hasTriggeredCrossfadeTransition && !isCrossfadeTransitioning) {
                            val nextIdx = if (player.hasNextMediaItem()) {
                                val nextCandidate = player.nextMediaItemIndex
                                if (nextCandidate != androidx.media3.common.C.INDEX_UNSET) nextCandidate else player.currentMediaItemIndex + 1
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
                            if (inTrackEndFadeOut && (fadeJob == null || !fadeJob!!.isActive)) {
                                val rem = (reliableDur - currentPos - 80L).coerceAtLeast(0L)
                                val targetVol = (rem.toFloat() / effectiveEndFadeWindowMs.toFloat()).coerceIn(0.0f, 1.0f)
                                player.volume = targetVol
                            } else if (!inTrackEndFadeOut && !isCrossfadeTransitioning && crossfadeJob?.isActive != true && player.volume < 1.0f && (fadeJob == null || !fadeJob!!.isActive)) {
                                player.volume = 1.0f
                            }
                        }

                        // Proactive Pre-Warming of Upcoming Track
                        val remainingMs = reliableDur - currentPos
                        if (remainingMs in 500L..15000L && !isFinalSleepTrack && !isRepeatOne) {
                            val nextCandidateIdx = if (player.hasNextMediaItem()) {
                                val nextCand = player.nextMediaItemIndex
                                if (nextCand != androidx.media3.common.C.INDEX_UNSET) nextCand else player.currentMediaItemIndex + 1
                            } else if (_playbackState.value.repeatMode == Player.REPEAT_MODE_ALL && _playbackState.value.queue.isNotEmpty()) {
                                0
                            } else -1

                            val nextSong = _playbackState.value.queue.getOrNull(nextCandidateIdx)
                            if (nextSong != null && nextSong.id != lastPrewarmedSongId && (prewarmJob == null || !prewarmJob!!.isActive)) {
                                lastPrewarmedSongId = nextSong.id
                                prewarmJob = scope.launch(Dispatchers.IO) {
                                    try {
                                        val authUri = resolveAndUpgradeTrackArtworkIfNeeded(nextSong)
                                        if (!authUri.isNullOrBlank()) {
                                            com.example.ui.components.ArtworkResolutionCache.cacheArtwork(nextSong.id, nextSong.path, authUri)
                                        }
                                        val cached = ArtworkManager.getCachedNotificationBitmap(nextSong.id, nextSong.path)
                                        if (cached == null || cached.isRecycled) {
                                            val artBytes = ArtworkManager.getNotificationArtworkBytes(
                                                context = context,
                                                songId = nextSong.id,
                                                filePath = nextSong.path,
                                                albumId = nextSong.albumId,
                                                artworkUriStr = authUri ?: nextSong.artworkUri
                                            )
                                            if (artBytes != null && artBytes.isNotEmpty()) {
                                                val bmp = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                                                if (bmp != null) {
                                                    ArtworkManager.putCachedNotificationBitmap(nextSong.id, bmp, nextSong.path)
                                                }
                                            }
                                        }
                                        ArtworkColorExtractor.extractPrimaryColorForSong(context, nextSong)
                                    } catch (_: Throwable) {}
                                }
                            }
                        }

                        // Prevent stale progress emission during track transitions
                        val currentMediaIdx = player.currentMediaItemIndex
                        val currentSong = _playbackState.value.currentSong
                        val currentQueue = _playbackState.value.queue
                        val mediaTrackId = currentQueue.getOrNull(currentMediaIdx)?.id
                        val isMatchingTrack = currentSong != null && (mediaTrackId == null || mediaTrackId == currentSong.id)

                        if (isMatchingTrack) {
                            currentPositionMs = currentPos
                            if (isUiInForeground) {
                                _playbackPositionMs.value = currentPos
                            }

                            val curSec = currentPos / 1000
                            if (curSec != lastWidgetSec) {
                                lastWidgetSec = curSec
                                if (!isUiInForeground) {
                                    _playbackPositionMs.value = currentPos
                                }
                                com.example.widget.WidgetUpdateManager.updateWidgetsProgress(context, currentPos, totalDur)
                            }

                            if (totalDur > 0L && _playbackState.value.durationMs != totalDur) {
                                _playbackState.value = _playbackState.value.copy(durationMs = totalDur)
                            }
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
                    }

                    val inCrossfadeWindow = crossfadeDurationSec > 0 && inCrossfadeZone
                    val loopDelay = when {
                        inCrossfadeWindow || inTrackEndFadeOut -> 60L
                        !isUiInForeground -> 500L
                        else -> 220L
                    }
                    delay(loopDelay)
                }
            } catch (_: Exception) {}
        }
    }

    fun handleSleepTimerExpired() {
        try {
            audioFocusManager.cancelPendingResume()
            stopProgressTracker()
            flushSession(isFinal = true)

            // Stop playback immediately (Scenario 1: normal time sleep timer without finish last track)
            pause()

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
        notificationArtworkJob?.cancel()
        notificationArtworkJob = null
        stopProgressTracker()
        cancelCrossfade(resetVolume = true)
        audioFocusManager.unregisterCameraCallback()
        audioFocusManager.abandonAudioFocus()
        equalizerManager.release()
        try { _crossfadePlayer?.release(); _crossfadePlayer = null } catch (e: Exception) {}
        try { mediaSession.release() } catch (e: Exception) {}
        player.release()
    }

    fun updateSongInQueue(updatedSong: Song) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main) {
                updateSongInQueue(updatedSong)
            }
            return
        }
        val currentQueue = _playbackState.value.queue.toMutableList()
        for (i in currentQueue.indices) {
            if (currentQueue[i].id == updatedSong.id) {
                currentQueue[i] = updatedSong
            }
        }
        if (originalQueue.isNotEmpty()) {
            originalQueue = originalQueue.map { if (it.id == updatedSong.id) updatedSong else it }
        }
        val isCurrent = _playbackState.value.currentSong?.id == updatedSong.id
        val newCurrentSong = if (isCurrent) updatedSong else _playbackState.value.currentSong
        _playbackState.value = _playbackState.value.copy(
            queue = currentQueue,
            currentSong = newCurrentSong
        )
        try {
            val activeMediaIdx = player.currentMediaItemIndex
            for (i in currentQueue.indices) {
                // Never call replaceMediaItem on the actively playing item when only metadata/artwork changed,
                // as ExoPlayer re-prepares the current period and causes a mid-playback pause!
                if (currentQueue[i].id == updatedSong.id && i < player.mediaItemCount && i != activeMediaIdx) {
                    val newMediaItem = buildMediaItem(updatedSong, loadArtworkBytes = false)
                    player.replaceMediaItem(i, newMediaItem)
                }
            }
        } catch (_: Exception) {}

        if (isCurrent) {
            // Immediately push updated title, artist, album, and any already-decoded artwork bytes to MediaSession & Notification
            notifyLiveMetadataChanged()
            updateNotificationLayout()
            MusicPlaybackService.instance?.triggerNotificationUpdate()
            com.example.widget.WidgetUpdateManager.forceRefreshMetadata(context, _playbackState.value)

            scope.launch(Dispatchers.IO) {
                val resolvedArtUri = updatedSong.artworkUri?.takeIf { it.isNotBlank() }
                    ?: com.example.ui.components.ArtworkResolutionCache.getAuthoritativeArtwork(context, updatedSong, false)
                if (!resolvedArtUri.isNullOrBlank()) {
                    val bmp = ArtworkManager.decodeBitmapFromUri(context, resolvedArtUri)
                    if (bmp != null) {
                        ArtworkManager.putCachedNotificationBitmap(updatedSong.id, bmp, updatedSong.path)
                        try {
                            val stream = java.io.ByteArrayOutputStream()
                            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, stream)
                            val bytes = stream.toByteArray()
                            ArtworkManager.putNotificationArtwork(updatedSong.id, bytes, bmp, updatedSong.path)
                        } catch (_: Throwable) {}
                    }
                } else {
                    ArtworkManager.invalidateNotificationArtwork(updatedSong.id, updatedSong.path)
                }
                withContext(Dispatchers.Main) {
                    notifyLiveMetadataChanged()
                    updateNotificationLayout()
                    MusicPlaybackService.instance?.triggerNotificationUpdate()
                    com.example.widget.WidgetBitmapRenderer.invalidateSong(updatedSong.id)
                    com.example.widget.WidgetUpdateManager.forceRefreshMetadata(context, _playbackState.value)
                }
            }
        } else {
            updateNotificationLayout()
            MusicPlaybackService.instance?.triggerNotificationUpdate()
            com.example.widget.WidgetBitmapRenderer.invalidateSong(updatedSong.id)
            com.example.widget.WidgetUpdateManager.forceRefreshMetadata(context, _playbackState.value)
        }
    }

    fun updateCurrentSongArtwork(songId: Long, artworkUri: String, filePath: String? = null) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main) {
                updateCurrentSongArtwork(songId, artworkUri, filePath)
            }
            return
        }
        val currentSong = _playbackState.value.currentSong
        val matchesSong: (Song) -> Boolean = { candidate ->
            candidate.id == songId && (filePath.isNullOrBlank() || candidate.path == filePath)
        }
        val newCurrentSong = if (currentSong != null && matchesSong(currentSong)) currentSong.copy(artworkUri = artworkUri) else currentSong
        val newQueue = _playbackState.value.queue.map {
            if (matchesSong(it)) it.copy(artworkUri = artworkUri) else it
        }
        if (originalQueue.isNotEmpty()) {
            originalQueue = originalQueue.map {
                if (matchesSong(it)) it.copy(artworkUri = artworkUri) else it
            }
        }
        _playbackState.value = _playbackState.value.copy(
            currentSong = newCurrentSong,
            queue = newQueue
        )
        val targetIdx = newQueue.indexOfFirst(matchesSong)
        val targetSong = newQueue.getOrNull(targetIdx) ?: newCurrentSong?.takeIf(matchesSong)
        // Only update non-active MediaItems on ExoPlayer; replacing the currently playing MediaItem
        // when only artworkUri changed causes ExoPlayer to rebuffer and pause playback!
        if (targetIdx >= 0 && targetIdx < player.mediaItemCount && targetIdx != player.currentMediaItemIndex) {
            try {
                player.replaceMediaItem(targetIdx, buildMediaItem(newQueue[targetIdx], loadArtworkBytes = false))
            } catch (_: Exception) {}
        }

        scope.launch(Dispatchers.IO) {
            val bmp = ArtworkManager.decodeBitmapFromUri(context, artworkUri)
                ?: ArtworkManager.getOrExtractArtworkUri(context, songId, targetSong?.path.orEmpty(), targetSong?.albumId ?: 0L)?.let {
                    ArtworkManager.decodeBitmapFromUri(context, it)
                }
            if (bmp != null) {
                ArtworkManager.putCachedNotificationBitmap(songId, bmp, targetSong?.path)
                try {
                    val stream = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, stream)
                    val bytes = stream.toByteArray()
                    ArtworkManager.putNotificationArtwork(songId, bytes, bmp, targetSong?.path)
                } catch (_: Throwable) {}
            }
            withContext(Dispatchers.Main) {
                notifyLiveMetadataChanged()
                updateNotificationLayout()
                MusicPlaybackService.instance?.triggerNotificationUpdate()
                com.example.widget.WidgetBitmapRenderer.invalidateSong(songId)
                com.example.widget.WidgetUpdateManager.forceRefreshMetadata(context, _playbackState.value)
            }
        }
    }

    private suspend fun resolveAndUpgradeTrackArtworkIfNeeded(song: Song): String? {
        var authUri = try {
            com.example.ui.components.ArtworkResolutionCache.getAuthoritativeArtwork(context, song, false)?.also {
                if (it.isNotBlank()) {
                    com.example.ui.components.ArtworkResolutionCache.cacheArtwork(song.id, song.path, it)
                }
            }
        } catch (_: Throwable) { null }

        val isManualArt = com.example.util.ManualTagStore.isArtworkManuallyEdited(context, song.id, song.path)
        if (isManualArt) return authUri

        val preferHighRes = try { userPreferences.getFetchOnlineArtworkHighResSync() } catch (_: Throwable) { userPreferences.cachedFetchOnlineArtworkHighRes }
        val cacheEnabled = userPreferences.cachedCacheAlbumArtEnabled && userPreferences.cachedCacheSongArtWorkEnabled
        val isMissingArtwork = authUri.isNullOrBlank()
        val isEligibleForHighResUpgrade = preferHighRes && !com.example.ui.components.ArtworkResolutionCache.hasHighResArtwork(context, song.id, song.path)

        if ((isMissingArtwork || isEligibleForHighResUpgrade) &&
            com.example.ui.components.ArtworkResolutionCache.shouldAttemptArtworkLookup(song.id, song.path) &&
            com.example.ui.components.ArtworkResolutionCache.tryAcquireOnlineFetch(song.id, song.path, preferHighRes)
        ) {
            val startGen = com.example.util.ManualTagStore.getArtworkEditGeneration(context, song.id, song.path)
            try {
                val onlineUrl = com.example.util.OnlineArtistImageFetcher.getSongArtworkUrl(
                    context = context,
                    title = song.displayTitle,
                    artist = song.displayArtist,
                    album = song.displayAlbum,
                    preferUltraHd = preferHighRes,
                    cacheEnabled = cacheEnabled,
                    songId = song.id,
                    filePath = song.path
                )
                if (!onlineUrl.isNullOrBlank() &&
                    !com.example.util.ManualTagStore.isArtworkManuallyEdited(context, song.id, song.path) &&
                    com.example.util.ManualTagStore.getArtworkEditGeneration(context, song.id, song.path) == startGen
                ) {
                    val savedLocal = if (cacheEnabled) {
                        com.example.util.ArtworkManager.saveFetchedArtworkLocally(
                            context = context,
                            songId = song.id,
                            url = onlineUrl,
                            filePath = song.path,
                            isHighRes = preferHighRes,
                            expectedArtworkGeneration = startGen
                        ) ?: onlineUrl
                    } else onlineUrl
                    if (savedLocal.isNotBlank()) {
                        authUri = savedLocal
                    }
                } else if (onlineUrl.isNullOrBlank() && isMissingArtwork) {
                    com.example.ui.components.ArtworkResolutionCache.markArtworkLookupFailed(song.id, song.path)
                }
            } catch (_: Throwable) {
            } finally {
                com.example.ui.components.ArtworkResolutionCache.releaseOnlineFetch(song.id, song.path, preferHighRes)
            }
        }
        return authUri
    }

    private fun launchSongArtworkRefresh(song: Song) {
        notificationArtworkJob?.cancel()
        notificationArtworkJob = scope.launch(Dispatchers.IO) {
            val initialLocalUri = try {
                com.example.ui.components.ArtworkResolutionCache.getAuthoritativeArtwork(context, song, false)?.also {
                    if (it.isNotBlank()) {
                        com.example.ui.components.ArtworkResolutionCache.cacheArtwork(song.id, song.path, it)
                    }
                }
            } catch (_: Throwable) { null }

            // First immediately populate notification/widget with existing cached/embedded artwork (0ms wait)
            val initialBytes = com.example.util.ArtworkManager.getNotificationArtworkBytes(
                context = context,
                songId = song.id,
                filePath = song.path,
                albumId = song.albumId,
                artworkUriStr = initialLocalUri ?: song.artworkUri
            )
            if (initialBytes != null && initialBytes.isNotEmpty()) {
                val cached = com.example.util.ArtworkManager.getCachedNotificationBitmap(song.id, song.path)
                if (cached == null || cached.isRecycled) {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(initialBytes, 0, initialBytes.size)
                        if (bmp != null) {
                            com.example.util.ArtworkManager.putCachedNotificationBitmap(song.id, bmp, song.path)
                        }
                    } catch (_: Throwable) {}
                }
                withContext(Dispatchers.Main) {
                    if (_playbackState.value.currentSong?.let { it.id == song.id && it.path == song.path } == true) {
                        updateNotificationLayout()
                        MusicPlaybackService.instance?.triggerNotificationUpdate()
                        com.example.widget.WidgetBitmapRenderer.invalidateSong(song.id)
                        com.example.widget.WidgetUpdateManager.updateAllWidgets(context, _playbackState.value)
                    }
                }
            }

            // Next, if artwork is missing (Case A) or eligible for High-Res upgrade when High-Res is ON (Case B),
            // retrieve and upgrade in the background without blocking playback startup!
            val upgradedUri = resolveAndUpgradeTrackArtworkIfNeeded(song)
            if (!upgradedUri.isNullOrBlank() && upgradedUri != initialLocalUri) {
                val upgradedBytes = com.example.util.ArtworkManager.getNotificationArtworkBytes(
                    context = context,
                    songId = song.id,
                    filePath = song.path,
                    albumId = song.albumId,
                    artworkUriStr = upgradedUri
                )
                if (upgradedBytes != null && upgradedBytes.isNotEmpty()) {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(upgradedBytes, 0, upgradedBytes.size)
                        if (bmp != null) {
                            com.example.util.ArtworkManager.putCachedNotificationBitmap(song.id, bmp, song.path)
                        }
                    } catch (_: Throwable) {}
                }
                withContext(Dispatchers.Main) {
                    if (_playbackState.value.currentSong?.let { it.id == song.id && it.path == song.path } == true) {
                        updateCurrentSongArtwork(song.id, upgradedUri, song.path)
                    }
                }
            }
        }
    }

    fun syncFavoriteStateInMemory(songId: Long, isFavorite: Boolean) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main) {
                syncFavoriteStateInMemory(songId, isFavorite)
            }
            return
        }
        val currentSong = _playbackState.value.currentSong
        val newCurrentSong = if (currentSong?.id == songId) currentSong.copy(isFavorite = isFavorite) else currentSong
        val newQueue = _playbackState.value.queue.map {
            if (it.id == songId) it.copy(isFavorite = isFavorite) else it
        }
        if (originalQueue.isNotEmpty()) {
            originalQueue = originalQueue.map {
                if (it.id == songId) it.copy(isFavorite = isFavorite) else it
            }
        }
        val livePos = try {
            val p = player.currentPosition
            if (p > 0L && p != C.TIME_UNSET) p else maxOf(currentPositionMs, _playbackPositionMs.value, _playbackState.value.positionMs)
        } catch (_: Exception) {
            maxOf(currentPositionMs, _playbackPositionMs.value, _playbackState.value.positionMs)
        }
        val liveDur = try {
            val d = player.duration
            if (d > 0L && d != C.TIME_UNSET) d else maxOf(_playbackState.value.durationMs, newCurrentSong?.durationMs ?: 0L)
        } catch (_: Exception) {
            maxOf(_playbackState.value.durationMs, newCurrentSong?.durationMs ?: 0L)
        }
        currentPositionMs = livePos
        _playbackPositionMs.value = livePos
        _playbackState.value = _playbackState.value.copy(
            currentSong = newCurrentSong,
            queue = newQueue,
            positionMs = livePos,
            durationMs = liveDur
        )
        com.example.widget.WidgetUpdateManager.setFavoriteStateImmediate(context, songId, isFavorite)
        updateNotificationLayout()
    }

    fun updateSongFavoriteStatus(songId: Long, isFavorite: Boolean) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main) {
                updateSongFavoriteStatus(songId, isFavorite)
            }
            return
        }
        com.example.widget.WidgetUpdateManager.setFavoriteStateImmediate(context, songId, isFavorite)
        syncFavoriteStateInMemory(songId, isFavorite)
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

    fun isVolumeZero(): Boolean {
        val curVol = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
        return curVol == 0
    }

    fun setPreferredAudioDevice(deviceInfo: android.media.AudioDeviceInfo?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                if (currentPreferredDevice?.id == deviceInfo?.id) return
                currentPreferredDevice = deviceInfo
                player.setPreferredAudioDevice(deviceInfo)
                _crossfadePlayer?.setPreferredAudioDevice(deviceInfo)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
