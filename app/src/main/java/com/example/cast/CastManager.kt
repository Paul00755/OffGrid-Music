package com.example.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.example.data.model.Song
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Unified casting coordinator supporting:
 * 1. Google Cast (Chromecast, Google TV, Android TV, Nest speakers, Cast-enabled Smart TVs)
 * 2. DLNA / UPnP Smart TVs & Wi-Fi Speakers (Samsung, LG, Sony, Roku, Sonos, Xbox, Kodi)
 * 3. Local LAN HTTP streaming of offline tracks & album art via [LocalCastMediaServer]
 *
 * Designed for zero idle overhead and graceful degradation on devices without Google Play Services.
 */
class CastManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mediaServer = LocalCastMediaServer(appContext)
    private val dlnaEngine = DlnaCastEngine(appContext)
    private val castExecutor = Executors.newSingleThreadExecutor()

    private val _sessionState = MutableStateFlow(CastSessionState())
    val sessionState: StateFlow<CastSessionState> = _sessionState.asStateFlow()

    val isCastingRemote: Boolean
        get() = _sessionState.value.isConnected

    var onRemoteTrackFinished: (() -> Unit)? = null
    var onRemotePlaybackTick: ((positionMs: Long, isPlaying: Boolean) -> Unit)? = null
    var onCastDisconnected: ((lastPositionMs: Long) -> Unit)? = null

    private var castContext: CastContext? = null
    private var mediaRouter: MediaRouter? = null
    private var routeSelector: MediaRouteSelector? = null
    private var isMediaRouterCallbackRegistered = false
    private var isCastContextInitialized = false

    private val googleCastDevices = LinkedHashMap<String, CastDevice>()
    private val dlnaDevices = LinkedHashMap<String, CastDevice>()

    private var pendingGoogleCastSong: Song? = null
    private var pendingGoogleCastStartPosMs: Long = 0L
    private var pendingGoogleCastDevice: CastDevice? = null

    private var dlnaScanJob: Job? = null
    private var progressPollJob: Job? = null
    private var hasNotifiedFinishForSongId: Long? = null
    private var dlnaWasPlayingSinceLoad: Boolean = false

    private val mediaRouterCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
            refreshGoogleCastRoutes(router)
        }

        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
            refreshGoogleCastRoutes(router)
        }

        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
            refreshGoogleCastRoutes(router)
        }
    }

    private val remoteMediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            try {
                val session = currentGoogleCastSession() ?: return
                val client = session.remoteMediaClient ?: return
                val status = client.mediaStatus
                val isPlaying = client.isPlaying
                val pos = client.approximateStreamPosition.coerceAtLeast(0L)
                val dur = client.streamDuration.takeIf { it > 0L }
                    ?: _sessionState.value.castingSong?.durationMs
                    ?: 0L

                _sessionState.update { state ->
                    state.copy(
                        isRemotePlaying = isPlaying,
                        remotePositionMs = pos,
                        remoteDurationMs = dur
                    )
                }
                onRemotePlaybackTick?.invoke(pos, isPlaying)

                val songId = _sessionState.value.castingSong?.id
                if (status != null &&
                    status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                    status.idleReason == MediaStatus.IDLE_REASON_FINISHED &&
                    songId != null &&
                    hasNotifiedFinishForSongId != songId
                ) {
                    hasNotifiedFinishForSongId = songId
                    onRemoteTrackFinished?.invoke()
                }
            } catch (_: Throwable) {
                // Defensive guard against transient Play Services / Cast IPC errors on abrupt TV disconnect
            }
        }
    }

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            val device = pendingGoogleCastDevice ?: buildDeviceFromCastSession(session)
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.CONNECTING,
                    connectedDevice = device,
                    statusMessage = "Connecting to ${device.name}…",
                    errorMessage = null
                )
            }
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            val device = buildDeviceFromCastSession(session)
            pendingGoogleCastDevice = null
            try {
                session.remoteMediaClient?.registerCallback(remoteMediaCallback)
            } catch (_: Throwable) {}

            val currentVol = try {
                session.volume.toFloat().coerceIn(0f, 1f)
            } catch (_: Throwable) {
                0.7f
            }

            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.CONNECTED,
                    connectedDevice = device,
                    remoteVolume = currentVol,
                    statusMessage = "Connected to ${device.name}",
                    errorMessage = null
                )
            }

            val songToLoad = pendingGoogleCastSong
            val startPos = pendingGoogleCastStartPosMs
            pendingGoogleCastSong = null
            pendingGoogleCastStartPosMs = 0L
            if (songToLoad != null) {
                castSong(songToLoad, startPos)
            }
            startProgressPolling()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pendingGoogleCastDevice = null
            pendingGoogleCastSong = null
            mediaServer.stop()
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.ERROR,
                    connectedDevice = null,
                    isRemotePlaying = false,
                    statusMessage = null,
                    errorMessage = "Could not connect to Google Cast device (code $error). Check that both devices share the same Wi-Fi."
                )
            }
        }

        override fun onSessionEnding(session: CastSession) {
            try {
                session.remoteMediaClient?.unregisterCallback(remoteMediaCallback)
            } catch (_: Throwable) {}
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            val lastPos = _sessionState.value.remotePositionMs
            stopProgressPolling()
            mediaServer.stop()
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.IDLE,
                    connectedDevice = null,
                    isRemotePlaying = false,
                    castingSong = null,
                    statusMessage = "Disconnected from Cast device",
                    errorMessage = null
                )
            }
            onCastDisconnected?.invoke(lastPos)
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            val device = buildDeviceFromCastSession(session)
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.CONNECTING,
                    connectedDevice = device,
                    statusMessage = "Reconnecting to ${device.name}…"
                )
            }
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            val device = buildDeviceFromCastSession(session)
            try {
                session.remoteMediaClient?.registerCallback(remoteMediaCallback)
            } catch (_: Throwable) {}
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.CONNECTED,
                    connectedDevice = device,
                    statusMessage = "Connected to ${device.name}",
                    errorMessage = null
                )
            }
            startProgressPolling()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            mediaServer.stop()
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.IDLE,
                    connectedDevice = null,
                    isRemotePlaying = false
                )
            }
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            _sessionState.update {
                it.copy(statusMessage = "Connection interrupted, attempting to reconnect…")
            }
        }
    }

    fun refreshNetworkState() {
        val wifiOk = LocalCastMediaServer.isWifiConnected(appContext)
        val ip = LocalCastMediaServer.resolveLocalLanIpv4(appContext)
        _sessionState.update {
            it.copy(
                isWifiConnected = wifiOk || !ip.isNullOrBlank(),
                localIpAddress = ip
            )
        }
    }

    fun startDiscovery() {
        refreshNetworkState()
        ensureCastSdkInitialized()
        registerMediaRouterDiscovery()

        dlnaScanJob?.cancel()
        _sessionState.update { it.copy(isScanning = true, errorMessage = null) }

        dlnaScanJob = scope.launch {
            try {
                val foundDlna = dlnaEngine.discoverRenderers(timeoutMs = 2800L)
                dlnaDevices.clear()
                foundDlna.forEach { dlnaDevices[it.id] = it }
                publishCombinedDevices()
            } catch (_: Throwable) {
                // Keep any Google Cast devices intact
            } finally {
                _sessionState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun stopDiscovery() {
        dlnaScanJob?.cancel()
        dlnaScanJob = null
        if (!_sessionState.value.isConnected && !_sessionState.value.isConnecting) {
            unregisterMediaRouterDiscovery()
        }
        _sessionState.update { it.copy(isScanning = false) }
    }

    fun connectAndCast(
        device: CastDevice,
        song: Song?,
        startPositionMs: Long = 0L
    ) {
        refreshNetworkState()
        val localIp = _sessionState.value.localIpAddress
        if (localIp.isNullOrBlank()) {
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.ERROR,
                    errorMessage = "Connect your phone to the same Wi-Fi network as your TV before casting."
                )
            }
            return
        }

        // If switching from another active device, stop the previous session first
        val prevDevice = _sessionState.value.connectedDevice
        if (prevDevice != null && prevDevice.id != device.id) {
            disconnectInternal(notifyCallback = false)
        }

        when (device.protocol) {
            CastProtocol.GOOGLE_CAST -> connectGoogleCast(device, song, startPositionMs)
            CastProtocol.DLNA_UPNP -> connectDlna(device, song, startPositionMs)
        }
    }

    private fun connectGoogleCast(device: CastDevice, song: Song?, startPositionMs: Long) {
        ensureCastSdkInitialized()
        val router = mediaRouter
        val targetRoute = router?.routes?.firstOrNull { it.id == device.mediaRouteId }
        if (router == null || targetRoute == null) {
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.ERROR,
                    errorMessage = "${device.name} is no longer reachable. Tap Scan Again and make sure your TV is on."
                )
            }
            return
        }

        pendingGoogleCastDevice = device
        pendingGoogleCastSong = song
        pendingGoogleCastStartPosMs = startPositionMs.coerceAtLeast(0L)

        _sessionState.update {
            it.copy(
                connectionState = CastConnectionState.CONNECTING,
                connectedDevice = device,
                statusMessage = "Connecting to ${device.name}…",
                errorMessage = null
            )
        }

        val existingSession = currentGoogleCastSession()
        if (existingSession != null && existingSession.isConnected && targetRoute.isSelected) {
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.CONNECTED,
                    connectedDevice = device,
                    statusMessage = "Connected to ${device.name}",
                    errorMessage = null
                )
            }
            if (song != null) {
                castSong(song, startPositionMs)
            }
            startProgressPolling()
            return
        }

        try {
            router.selectRoute(targetRoute)
        } catch (e: Throwable) {
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.ERROR,
                    connectedDevice = null,
                    errorMessage = "Unable to connect to ${device.name}: ${e.message ?: "Route selection failed"}"
                )
            }
        }
    }

    private fun connectDlna(device: CastDevice, song: Song?, startPositionMs: Long) {
        _sessionState.update {
            it.copy(
                connectionState = CastConnectionState.CONNECTING,
                connectedDevice = device,
                statusMessage = "Connecting to ${device.name}…",
                errorMessage = null
            )
        }

        scope.launch {
            try {
                if (song != null) {
                    val result = pushSongToDlna(device, song, startPositionMs)
                    if (result.isSuccess) {
                        _sessionState.update {
                            it.copy(
                                connectionState = CastConnectionState.CONNECTED,
                                connectedDevice = device,
                                castingSong = song,
                                isRemotePlaying = true,
                                remotePositionMs = startPositionMs.coerceAtLeast(0L),
                                remoteDurationMs = song.durationMs,
                                statusMessage = "Casting to ${device.name}",
                                errorMessage = null
                            )
                        }
                        startProgressPolling()
                    } else {
                        mediaServer.stop()
                        _sessionState.update {
                            it.copy(
                                connectionState = CastConnectionState.ERROR,
                                connectedDevice = null,
                                isRemotePlaying = false,
                                errorMessage = result.exceptionOrNull()?.message
                                    ?: "Could not start playback on ${device.name}. Accept any prompt on your TV screen and try again."
                            )
                        }
                    }
                } else {
                    _sessionState.update {
                        it.copy(
                            connectionState = CastConnectionState.CONNECTED,
                            connectedDevice = device,
                            castingSong = null,
                            isRemotePlaying = false,
                            statusMessage = "Connected to ${device.name} • Pick any song to play",
                            errorMessage = null
                        )
                    }
                }
            } catch (e: Throwable) {
                mediaServer.stop()
                _sessionState.update {
                    it.copy(
                        connectionState = CastConnectionState.ERROR,
                        connectedDevice = null,
                        errorMessage = e.message ?: "Failed to connect to ${device.name}"
                    )
                }
            }
        }
    }

    fun castSong(song: Song, startPositionMs: Long = 0L) {
        val device = _sessionState.value.connectedDevice ?: return
        refreshNetworkState()
        val localIp = _sessionState.value.localIpAddress
        if (localIp.isNullOrBlank()) {
            _sessionState.update {
                it.copy(errorMessage = "Wi-Fi disconnected. Reconnect to Wi-Fi to continue casting.")
            }
            return
        }

        hasNotifiedFinishForSongId = null
        dlnaWasPlayingSinceLoad = false

        scope.launch {
            when (device.protocol) {
                CastProtocol.GOOGLE_CAST -> {
                    val urls = withContext(Dispatchers.IO) {
                        mediaServer.prepareMediaForSong(song, localIp)
                    }
                    if (urls == null) {
                        _sessionState.update { it.copy(errorMessage = "Could not prepare local audio stream for ${song.displayTitle}") }
                        return@launch
                    }
                    val client = currentGoogleCastSession()?.remoteMediaClient
                    if (client == null) {
                        _sessionState.update { it.copy(errorMessage = "Cast session is not active. Tap your TV to reconnect.") }
                        return@launch
                    }

                    try {
                        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                            putString(MediaMetadata.KEY_TITLE, song.displayTitle)
                            putString(MediaMetadata.KEY_ARTIST, song.displayArtist)
                            putString(MediaMetadata.KEY_ALBUM_TITLE, song.displayAlbum)
                            urls.artworkUrl?.let { art ->
                                addImage(WebImage(Uri.parse(art)))
                            }
                        }
                        val mediaInfo = MediaInfo.Builder(urls.streamUrl)
                            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                            .setContentType(urls.mimeType)
                            .setMetadata(metadata)
                            .apply {
                                if (song.durationMs > 0L) setStreamDuration(song.durationMs)
                            }
                            .build()

                        val requestData = MediaLoadRequestData.Builder()
                            .setMediaInfo(mediaInfo)
                            .setAutoplay(true)
                            .setCurrentTime(startPositionMs.coerceAtLeast(0L))
                            .build()

                        client.load(requestData)
                        _sessionState.update {
                            it.copy(
                                castingSong = song,
                                isRemotePlaying = true,
                                remotePositionMs = startPositionMs.coerceAtLeast(0L),
                                remoteDurationMs = song.durationMs,
                                statusMessage = "Casting to ${device.name}",
                                errorMessage = null
                            )
                        }
                        startProgressPolling()
                    } catch (e: Throwable) {
                        _sessionState.update {
                            it.copy(errorMessage = "Failed to load track on ${device.name}: ${e.message ?: "Unknown error"}")
                        }
                    }
                }

                CastProtocol.DLNA_UPNP -> {
                    val result = pushSongToDlna(device, song, startPositionMs)
                    if (result.isSuccess) {
                        _sessionState.update {
                            it.copy(
                                castingSong = song,
                                isRemotePlaying = true,
                                remotePositionMs = startPositionMs.coerceAtLeast(0L),
                                remoteDurationMs = song.durationMs,
                                statusMessage = "Casting to ${device.name}",
                                errorMessage = null
                            )
                        }
                        startProgressPolling()
                    } else {
                        _sessionState.update {
                            it.copy(
                                errorMessage = result.exceptionOrNull()?.message
                                    ?: "TV could not play ${song.displayTitle}."
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun pushSongToDlna(
        device: CastDevice,
        song: Song,
        startPositionMs: Long
    ): Result<Unit> {
        val localIp = _sessionState.value.localIpAddress
            ?: return Result.failure(IllegalStateException("Wi-Fi network unavailable"))
        val urls = withContext(Dispatchers.IO) {
            mediaServer.prepareMediaForSong(song, localIp)
        } ?: return Result.failure(IllegalStateException("Could not start local stream server"))

        val loadRes = dlnaEngine.loadAndPlay(device, song, urls)
        if (loadRes.isSuccess && startPositionMs > 2000L) {
            delay(450)
            dlnaEngine.seek(device, startPositionMs)
        }
        return loadRes
    }

    fun toggleRemotePlayPause() {
        val state = _sessionState.value
        val device = state.connectedDevice ?: return
        val shouldPlay = !state.isRemotePlaying

        when (device.protocol) {
            CastProtocol.GOOGLE_CAST -> {
                val client = currentGoogleCastSession()?.remoteMediaClient ?: return
                try {
                    if (shouldPlay) client.play() else client.pause()
                    _sessionState.update { it.copy(isRemotePlaying = shouldPlay) }
                    onRemotePlaybackTick?.invoke(state.remotePositionMs, shouldPlay)
                } catch (_: Throwable) {}
            }
            CastProtocol.DLNA_UPNP -> {
                scope.launch {
                    val res = if (shouldPlay) dlnaEngine.play(device) else dlnaEngine.pause(device)
                    if (res.isSuccess) {
                        _sessionState.update { it.copy(isRemotePlaying = shouldPlay) }
                        onRemotePlaybackTick?.invoke(state.remotePositionMs, shouldPlay)
                    }
                }
            }
        }
    }

    fun seekRemote(positionMs: Long) {
        val state = _sessionState.value
        val device = state.connectedDevice ?: return
        val safePos = positionMs.coerceAtLeast(0L)

        _sessionState.update { it.copy(remotePositionMs = safePos) }
        onRemotePlaybackTick?.invoke(safePos, state.isRemotePlaying)

        when (device.protocol) {
            CastProtocol.GOOGLE_CAST -> {
                val client = currentGoogleCastSession()?.remoteMediaClient ?: return
                try {
                    val opts = MediaSeekOptions.Builder().setPosition(safePos).build()
                    client.seek(opts)
                } catch (_: Throwable) {}
            }
            CastProtocol.DLNA_UPNP -> {
                scope.launch {
                    dlnaEngine.seek(device, safePos)
                }
            }
        }
    }

    fun setRemoteVolume(volume01: Float) {
        val clamped = volume01.coerceIn(0f, 1f)
        val device = _sessionState.value.connectedDevice ?: return
        _sessionState.update { it.copy(remoteVolume = clamped) }

        when (device.protocol) {
            CastProtocol.GOOGLE_CAST -> {
                try {
                    currentGoogleCastSession()?.setVolume(clamped.toDouble())
                } catch (_: Throwable) {}
            }
            CastProtocol.DLNA_UPNP -> {
                scope.launch {
                    dlnaEngine.setVolume(device, clamped)
                }
            }
        }
    }

    fun disconnect() {
        disconnectInternal(notifyCallback = true)
    }

    private fun disconnectInternal(notifyCallback: Boolean) {
        val lastPos = _sessionState.value.remotePositionMs
        val device = _sessionState.value.connectedDevice
        stopProgressPolling()

        if (device?.protocol == CastProtocol.GOOGLE_CAST) {
            try {
                castContext?.sessionManager?.endCurrentSession(true)
            } catch (_: Throwable) {}
            try {
                mediaRouter?.let { router ->
                    router.selectRoute(router.defaultRoute)
                }
            } catch (_: Throwable) {}
        } else if (device?.protocol == CastProtocol.DLNA_UPNP) {
            scope.launch {
                try { dlnaEngine.stop(device) } catch (_: Throwable) {}
            }
        }

        pendingGoogleCastDevice = null
        pendingGoogleCastSong = null
        hasNotifiedFinishForSongId = null
        dlnaWasPlayingSinceLoad = false
        mediaServer.stop()

        _sessionState.update {
            it.copy(
                connectionState = CastConnectionState.IDLE,
                connectedDevice = null,
                isRemotePlaying = false,
                castingSong = null,
                statusMessage = null,
                errorMessage = null
            )
        }
        if (notifyCallback) {
            onCastDisconnected?.invoke(lastPos)
        }
    }

    fun clearError() {
        _sessionState.update { it.copy(errorMessage = null) }
    }

    fun launchSystemCastSettings(activityContext: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            Intent("com.android.settings.panel.action.MEDIA_OUTPUT").apply {
                putExtra("com.android.settings.panel.extra.PACKAGE_NAME", activityContext.packageName)
            },
            Intent(Settings.ACTION_WIFI_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activityContext.startActivity(intent)
                return
            } catch (_: Throwable) {}
        }
    }

    fun launchWifiSettings(activityContext: Context) {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(intent)
        } catch (_: Throwable) {}
    }

    fun launchBluetoothSettings(activityContext: Context) {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(intent)
        } catch (_: Throwable) {}
    }

    private fun startProgressPolling() {
        progressPollJob?.cancel()
        progressPollJob = scope.launch {
            while (isActive) {
                delay(1000L)
                val state = _sessionState.value
                val device = state.connectedDevice ?: break
                if (state.connectionState != CastConnectionState.CONNECTED) break

                when (device.protocol) {
                    CastProtocol.GOOGLE_CAST -> {
                        try {
                            val client = currentGoogleCastSession()?.remoteMediaClient
                            if (client != null) {
                                val isPlaying = client.isPlaying
                                val pos = client.approximateStreamPosition.coerceAtLeast(0L)
                                val dur = client.streamDuration.takeIf { it > 0L }
                                    ?: state.castingSong?.durationMs
                                    ?: state.remoteDurationMs
                                _sessionState.update {
                                    it.copy(
                                        isRemotePlaying = isPlaying,
                                        remotePositionMs = pos,
                                        remoteDurationMs = dur
                                    )
                                }
                                onRemotePlaybackTick?.invoke(pos, isPlaying)
                            }
                        } catch (_: Throwable) {
                            // Ignore transient polling errors when remote device disconnects
                        }
                    }
                    CastProtocol.DLNA_UPNP -> {
                        val status = dlnaEngine.queryPlaybackStatus(device)
                        if (status != null) {
                            val playing = status.transportState == "PLAYING" || status.transportState == "TRANSITIONING"
                            if (status.transportState == "PLAYING") {
                                dlnaWasPlayingSinceLoad = true
                            }
                            val effectiveDur = status.durationMs.takeIf { it > 0L }
                                ?: state.castingSong?.durationMs
                                ?: state.remoteDurationMs
                            val effectivePos = if (status.positionMs > 0L) status.positionMs else state.remotePositionMs
                            _sessionState.update {
                                it.copy(
                                    isRemotePlaying = playing,
                                    remotePositionMs = effectivePos,
                                    remoteDurationMs = effectiveDur
                                )
                            }
                            onRemotePlaybackTick?.invoke(effectivePos, playing)

                            val songId = state.castingSong?.id
                            val reachedEnd = dlnaWasPlayingSinceLoad &&
                                status.transportState == "STOPPED" &&
                                songId != null &&
                                hasNotifiedFinishForSongId != songId
                            if (reachedEnd) {
                                hasNotifiedFinishForSongId = songId
                                dlnaWasPlayingSinceLoad = false
                                onRemoteTrackFinished?.invoke()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopProgressPolling() {
        progressPollJob?.cancel()
        progressPollJob = null
    }

    private fun ensureCastSdkInitialized() {
        if (isCastContextInitialized) return
        isCastContextInitialized = true
        try {
            mediaRouter = MediaRouter.getInstance(appContext)
            routeSelector = MediaRouteSelector.Builder()
                .addControlCategory(
                    CastMediaControlIntent.categoryForCast(
                        CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                    )
                )
                .build()
        } catch (_: Throwable) {}

        try {
            CastContext.getSharedInstance(appContext, castExecutor)
                .addOnSuccessListener { ctx ->
                    castContext = ctx
                    try {
                        ctx.sessionManager.addSessionManagerListener(castSessionListener, CastSession::class.java)
                        val active = ctx.sessionManager.currentCastSession
                        if (active != null && active.isConnected) {
                            val dev = buildDeviceFromCastSession(active)
                            _sessionState.update {
                                it.copy(
                                    connectionState = CastConnectionState.CONNECTED,
                                    connectedDevice = dev,
                                    statusMessage = "Connected to ${dev.name}"
                                )
                            }
                            startProgressPolling()
                        }
                    } catch (_: Throwable) {}
                }
                .addOnFailureListener {
                    // Play Services Cast unavailable on this device; DLNA & Bluetooth remain available
                }
        } catch (_: Throwable) {}
    }

    private fun registerMediaRouterDiscovery() {
        val router = mediaRouter ?: return
        val selector = routeSelector ?: return
        if (!isMediaRouterCallbackRegistered) {
            try {
                router.addCallback(
                    selector,
                    mediaRouterCallback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
                )
                isMediaRouterCallbackRegistered = true
            } catch (_: Throwable) {}
        }
        refreshGoogleCastRoutes(router)
    }

    private fun unregisterMediaRouterDiscovery() {
        if (!isMediaRouterCallbackRegistered) return
        try {
            mediaRouter?.removeCallback(mediaRouterCallback)
        } catch (_: Throwable) {}
        isMediaRouterCallbackRegistered = false
    }

    private fun refreshGoogleCastRoutes(router: MediaRouter) {
        val selector = routeSelector ?: return
        googleCastDevices.clear()
        try {
            for (route in router.routes) {
                if (!route.isDefault && route.isEnabled && route.matchesSelector(selector)) {
                    val lower = "${route.name} ${route.description.orEmpty()}".lowercase(Locale.US)
                    val kind = when {
                        route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER ||
                            lower.contains("speaker") || lower.contains("nest audio") || lower.contains("mini") || lower.contains("home") -> CastDeviceKind.SPEAKER
                        lower.contains("hub") || lower.contains("display") -> CastDeviceKind.DISPLAY
                        else -> CastDeviceKind.TV
                    }
                    val device = CastDevice(
                        id = "gcast_${route.id}",
                        name = route.name.ifBlank { "Google Cast Device" },
                        subtitle = route.description?.takeIf { it.isNotBlank() } ?: "Chromecast / Google TV",
                        protocol = CastProtocol.GOOGLE_CAST,
                        kind = kind,
                        mediaRouteId = route.id
                    )
                    googleCastDevices[device.id] = device
                }
            }
        } catch (_: Throwable) {}
        publishCombinedDevices()
    }

    private fun publishCombinedDevices() {
        val combined = buildList {
            addAll(googleCastDevices.values)
            // Avoid showing duplicate entries when a TV advertises both Google Cast and DLNA under the same name
            val castNames = googleCastDevices.values.map { it.name.trim().lowercase(Locale.US) }.toSet()
            for (dlna in dlnaDevices.values) {
                if (dlna.name.trim().lowercase(Locale.US) !in castNames) {
                    add(dlna)
                }
            }
        }
        _sessionState.update { it.copy(discoveredDevices = combined) }
    }

    private fun currentGoogleCastSession(): CastSession? {
        return try {
            castContext?.sessionManager?.currentCastSession
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildDeviceFromCastSession(session: CastSession): CastDevice {
        val castDev = session.castDevice
        val name = castDev?.friendlyName?.takeIf { it.isNotBlank() } ?: "Google Cast TV"
        val model = castDev?.modelName?.takeIf { it.isNotBlank() } ?: "Chromecast / Google TV"
        val routeId = mediaRouter?.selectedRoute?.id
        return CastDevice(
            id = "gcast_${routeId ?: castDev?.deviceId ?: name.hashCode()}",
            name = name,
            subtitle = model,
            protocol = CastProtocol.GOOGLE_CAST,
            kind = CastDeviceKind.TV,
            mediaRouteId = routeId
        )
    }

    fun release() {
        stopDiscovery()
        stopProgressPolling()
        unregisterMediaRouterDiscovery()
        try {
            castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
        } catch (_: Throwable) {}
        mediaServer.stop()
        try {
            castExecutor.shutdownNow()
        } catch (_: Throwable) {}
        scope.cancel()
    }
}
