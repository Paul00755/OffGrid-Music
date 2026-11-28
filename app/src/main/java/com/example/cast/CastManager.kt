package com.example.cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.Lyrics
import com.example.data.model.Song
import com.example.util.LrcParser
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Process-wide casting coordinator supporting:
 * 1. Google Cast (Chromecast, Google TV, Android TV, Nest speakers, Cast-enabled Smart TVs)
 *    with synced WebVTT lyrics, seamless next-track queue pre-loading, and hardware volume control.
 * 2. DLNA / UPnP Smart TVs & Wi-Fi Speakers (Samsung, LG, Sony, Roku, Sonos, Xbox, Kodi).
 * 3. Local LAN HTTP streaming of offline tracks, album art, and WebVTT lyrics via [LocalCastMediaServer].
 *
 * Reliability & Efficiency:
 * - Zero startup overhead: SDK, MediaRouter, locks, and HTTP server start only on user action.
 * - Stops MediaRouter active scanning as soon as the sheet closes or a device connects (prevents phone lag).
 * - Holds lightweight Wi-Fi + partial wake locks strictly while casting so playback never stops after 2–3 songs.
 * - Single shared instance wired directly into PlaybackManager so in-app UI, notifications, widgets,
 *   and headphone controls stay 100% synchronized with the TV.
 */
class CastManager(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mediaServer = LocalCastMediaServer(appContext)
    private val dlnaEngine = DlnaCastEngine(appContext)
    private val castExecutor = Executors.newSingleThreadExecutor()

    private val _sessionState = MutableStateFlow(CastSessionState())
    val sessionState: StateFlow<CastSessionState> = _sessionState.asStateFlow()

    val isCastingRemote: Boolean
        get() = _sessionState.value.isConnected

    val isCastingOrConnecting: Boolean
        get() = _sessionState.value.isActiveSession

    var onRemoteTrackFinished: (() -> Unit)? = null
    var onRemoteAutoAdvancedToSong: ((songId: Long) -> Unit)? = null
    var onRemotePlaybackTick: ((positionMs: Long, isPlaying: Boolean) -> Unit)? = null
    var onRemoteVolumeChanged: ((volume01: Float) -> Unit)? = null
    var onCastConnectionChanged: ((active: Boolean, deviceName: String?) -> Unit)? = null
    var onCastDisconnected: ((lastPositionMs: Long) -> Unit)? = null
    var onEnsureLocalPlaybackSilenced: (() -> Unit)? = null
    var nextSongProvider: (() -> Song?)? = null
    var currentLyricsProvider: ((songId: Long) -> Pair<Lyrics?, Long>?)? = null

    private var castContext: CastContext? = null
    private var castContextDeferred: CompletableDeferred<CastContext?> = CompletableDeferred()
    private var mediaRouter: MediaRouter? = null
    private var routeSelector: MediaRouteSelector? = null
    private var isMediaRouterCallbackRegistered = false
    private var isCastContextInitialized = false

    private val googleCastDevices = LinkedHashMap<String, CastDevice>()
    private val dlnaDevices = LinkedHashMap<String, CastDevice>()

    private var pendingGoogleCastSong: Song? = null
    private var pendingGoogleCastStartPosMs: Long = 0L
    private var pendingGoogleCastDevice: CastDevice? = null
    private var googleCastRetryCount: Int = 0
    private var googleCastConnectTimeoutJob: Job? = null

    private var dlnaScanJob: Job? = null
    private var progressPollJob: Job? = null
    private var castLoadJob: Job? = null
    private var preQueueJob: Job? = null
    private var hasNotifiedFinishForSongId: Long? = null
    private var dlnaWasPlayingSinceLoad: Boolean = false
    private var googleCastWasPlayingSinceLoad: Boolean = false
    private var lastPreQueuedNextSongId: Long? = null
    private var lastKeepAliveAtMs: Long = 0L
    private var registeredCallbackClient: RemoteMediaClient? = null
    private var currentSongUsingHlsVideo: Boolean = false
    private val hlsFallbackSongIds = HashSet<Long>()

    // Sync-guard state: prevents stale Cast status callbacks during a track switch or seek from reverting the app
    private var expectedLoadSongId: Long? = null
    private var lastLoadDispatchedAtMs: Long = 0L
    private var lastSeekDispatchedAtMs: Long = 0L
    private var lastPlayPauseDispatchedAtMs: Long = 0L
    private var lastDispatchedPlayState: Boolean? = null

    private var castWakeLock: PowerManager.WakeLock? = null
    private var castWifiLock: WifiManager.WifiLock? = null

    // Captures notification Media Player slider drags on OEM ROMs (ColorOS/OxygenOS/MIUI/OneUI) that route to STREAM_MUSIC
    private var isSystemVolumeReceiverRegistered = false
    private var lastRemoteVolumeSyncedAtMs = 0L
    private val systemVolumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "android.media.VOLUME_CHANGED_ACTION") return
            if (!_sessionState.value.isConnected) return
            val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            if (streamType != AudioManager.STREAM_MUSIC) return
            val newVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            if (newVol < 0 || newVol == prevVol) return
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val ratio = (newVol.toFloat() / maxVol.toFloat()).coerceIn(0f, 1f)
            // Avoid feedback if we just updated volume via MediaSession remote callback in the last 120ms
            val now = SystemClock.elapsedRealtime()
            if (now - lastRemoteVolumeSyncedAtMs < 120L &&
                kotlin.math.abs(ratio - _sessionState.value.remoteVolume) < 0.04f
            ) {
                return
            }
            setRemoteVolume(ratio)
        }
    }

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
            val session = currentGoogleCastSession() ?: return
            val client = session.remoteMediaClient ?: return
            processGoogleCastStatus(session, client)
        }
    }

    private fun ensureRemoteMediaCallbackRegistered(session: CastSession?) {
        val client = session?.remoteMediaClient ?: return
        if (registeredCallbackClient === client) return
        try {
            registeredCallbackClient?.unregisterCallback(remoteMediaCallback)
        } catch (_: Throwable) {}
        try {
            client.registerCallback(remoteMediaCallback)
            registeredCallbackClient = client
        } catch (_: Throwable) {}
    }

    private fun extractRemoteSongId(status: MediaStatus?): Long? {
        if (status == null) return null
        status.mediaInfo?.customData?.optLong("songId", -1L)?.takeIf { it > 0L }?.let { return it }
        try {
            val curItem = status.getQueueItemById(status.currentItemId)
            curItem?.customData?.optLong("songId", -1L)?.takeIf { it > 0L }?.let { return it }
            curItem?.media?.customData?.optLong("songId", -1L)?.takeIf { it > 0L }?.let { return it }
        } catch (_: Throwable) {}
        val contentId = status.mediaInfo?.contentId.orEmpty()
        if (contentId.isNotBlank()) {
            // Matches /hls/{token}/{songId}/playlist.m3u8 or /stream/{token}/{songId}.ext
            val hlsMatch = Regex("/hls/[^/]+/(\\d+)/").find(contentId)
            hlsMatch?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
            val streamMatch = Regex("/stream/[^/]+/(\\d+)\\.").find(contentId)
            streamMatch?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun processGoogleCastStatus(session: CastSession, client: RemoteMediaClient) {
        try {
            val status = client.mediaStatus
            val nowElapsed = SystemClock.elapsedRealtime()
            val inLoadGraceWindow = expectedLoadSongId != null && (nowElapsed - lastLoadDispatchedAtMs) < 4_000L
            val inSeekGraceWindow = (nowElapsed - lastSeekDispatchedAtMs) < 1_800L
            val inPlayPauseGraceWindow = (nowElapsed - lastPlayPauseDispatchedAtMs) < 1_500L

            val playerState = status?.playerState ?: MediaStatus.PLAYER_STATE_UNKNOWN
            val remoteSongId = extractRemoteSongId(status)
            val stateBefore = _sessionState.value
            val currentCastingId = stateBefore.castingSong?.id

            // If we just requested a new song load and TV is still emitting status for the PREVIOUS song, ignore stale status!
            if (inLoadGraceWindow && remoteSongId != null && remoteSongId != expectedLoadSongId) {
                return
            }
            if (remoteSongId != null && remoteSongId == expectedLoadSongId) {
                expectedLoadSongId = null
            }

            val rawIsPlaying = client.isPlaying || playerState == MediaStatus.PLAYER_STATE_PLAYING
            val isBufferingOrLoading = playerState == MediaStatus.PLAYER_STATE_BUFFERING ||
                playerState == MediaStatus.PLAYER_STATE_LOADING
            val effectiveIsPlaying = when {
                inPlayPauseGraceWindow && lastDispatchedPlayState != null -> lastDispatchedPlayState!!
                inLoadGraceWindow && isBufferingOrLoading -> true
                else -> rawIsPlaying
            }
            if (rawIsPlaying) {
                googleCastWasPlayingSinceLoad = true
            }
            val rawPos = client.approximateStreamPosition.coerceAtLeast(0L)
            val pos = if (inSeekGraceWindow || (inLoadGraceWindow && rawPos == 0L && stateBefore.remotePositionMs > 0L)) {
                stateBefore.remotePositionMs
            } else {
                rawPos
            }
            val dur = client.streamDuration.takeIf { it > 0L }
                ?: stateBefore.castingSong?.durationMs
                ?: stateBefore.remoteDurationMs

            // Sync remote volume if changed on TV remote or Google Home
            try {
                val tvVol = session.volume.toFloat().coerceIn(0f, 1f)
                if (nowElapsed - lastRemoteVolumeSyncedAtMs > 800L &&
                    kotlin.math.abs(tvVol - stateBefore.remoteVolume) >= 0.02f
                ) {
                    _sessionState.update { it.copy(remoteVolume = tvVol) }
                    onRemoteVolumeChanged?.invoke(tvVol)
                }
            } catch (_: Throwable) {}

            // Detect seamless TV-side queue auto-advance to pre-queued next song (only outside load grace window)
            if (!inLoadGraceWindow &&
                remoteSongId != null &&
                currentCastingId != null &&
                remoteSongId != currentCastingId &&
                playerState != MediaStatus.PLAYER_STATE_IDLE
            ) {
                hasNotifiedFinishForSongId = null
                lastPreQueuedNextSongId = null
                googleCastWasPlayingSinceLoad = true
                onRemoteAutoAdvancedToSong?.invoke(remoteSongId)
            }

            if (stateBefore.isRemotePlaying != effectiveIsPlaying ||
                kotlin.math.abs(stateBefore.remotePositionMs - pos) >= 400L ||
                stateBefore.remoteDurationMs != dur
            ) {
                _sessionState.update { s ->
                    s.copy(
                        isRemotePlaying = effectiveIsPlaying,
                        remotePositionMs = pos,
                        remoteDurationMs = dur
                    )
                }
            }
            onRemotePlaybackTick?.invoke(pos, effectiveIsPlaying)

            val activeSong = _sessionState.value.castingSong
            val songId = activeSong?.id
            if (!inLoadGraceWindow && status != null && playerState == MediaStatus.PLAYER_STATE_IDLE && songId != null) {
                val idleReason = status.idleReason
                // If HLS video failed on this specific track/device, immediately fall back to direct audio seamlessly
                if (idleReason == MediaStatus.IDLE_REASON_ERROR && currentSongUsingHlsVideo && songId !in hlsFallbackSongIds) {
                    hlsFallbackSongIds.add(songId)
                    currentSongUsingHlsVideo = false
                    castSong(activeSong, pos.coerceAtLeast(0L))
                    return
                }

                val finishedNormally = idleReason == MediaStatus.IDLE_REASON_FINISHED
                val reachedNaturalEnd = googleCastWasPlayingSinceLoad &&
                    idleReason != MediaStatus.IDLE_REASON_CANCELED &&
                    idleReason != MediaStatus.IDLE_REASON_INTERRUPTED &&
                    dur > 4_000L &&
                    pos >= (dur - 2_500L)

                if ((finishedNormally || reachedNaturalEnd) && hasNotifiedFinishForSongId != songId) {
                    hasNotifiedFinishForSongId = songId
                    googleCastWasPlayingSinceLoad = false
                    onRemoteTrackFinished?.invoke()
                }
            }
        } catch (_: Throwable) {
            // Defensive guard against transient Play Services / Cast IPC errors
        }
    }

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            onEnsureLocalPlaybackSilenced?.invoke()
            val device = pendingGoogleCastDevice ?: buildDeviceFromCastSession(session)
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.CONNECTING,
                    connectedDevice = device,
                    statusMessage = "Connecting to ${device.name}…",
                    errorMessage = null
                )
            }
            onCastConnectionChanged?.invoke(true, device.name)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            googleCastConnectTimeoutJob?.cancel()
            googleCastConnectTimeoutJob = null
            googleCastRetryCount = 0
            onEnsureLocalPlaybackSilenced?.invoke()
            // Immediately stop active MediaRouter scanning once connected to eliminate phone lag
            unregisterMediaRouterDiscovery()
            acquireCastLocks()
            registerSystemVolumeReceiver()

            val device = buildDeviceFromCastSession(session)
            pendingGoogleCastDevice = null
            ensureRemoteMediaCallbackRegistered(session)

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
            onCastConnectionChanged?.invoke(true, device.name)
            onRemoteVolumeChanged?.invoke(currentVol)

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
            googleCastConnectTimeoutJob?.cancel()
            googleCastConnectTimeoutJob = null
            val retryDevice = pendingGoogleCastDevice
            val retrySong = pendingGoogleCastSong
            val retryPos = pendingGoogleCastStartPosMs

            // Auto-retry up to 2 times seamlessly on transient Cast handshake errors before surfacing failure
            if (retryDevice != null && googleCastRetryCount < 2) {
                googleCastRetryCount++
                _sessionState.update {
                    it.copy(
                        connectionState = CastConnectionState.CONNECTING,
                        connectedDevice = retryDevice,
                        statusMessage = "Retrying connection to ${retryDevice.name}…",
                        errorMessage = null
                    )
                }
                scope.launch {
                    try {
                        mediaRouter?.let { r -> r.selectRoute(r.defaultRoute) }
                    } catch (_: Throwable) {}
                    delay(550L)
                    if (_sessionState.value.connectionState == CastConnectionState.CONNECTING &&
                        pendingGoogleCastDevice?.id == retryDevice.id
                    ) {
                        connectGoogleCast(retryDevice, retrySong, retryPos, isRetry = true)
                    }
                }
                return
            }

            googleCastRetryCount = 0
            pendingGoogleCastDevice = null
            pendingGoogleCastSong = null
            unregisterSystemVolumeReceiver()
            releaseCastLocks()
            mediaServer.stop()
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.ERROR,
                    connectedDevice = null,
                    isRemotePlaying = false,
                    hasSyncedLyricsOnTv = false,
                    statusMessage = null,
                    errorMessage = "Could not connect to Google Cast device (code $error). Check that both devices share the same Wi-Fi."
                )
            }
            onCastConnectionChanged?.invoke(false, null)
        }

        override fun onSessionEnding(session: CastSession) {
            try {
                session.remoteMediaClient?.unregisterCallback(remoteMediaCallback)
            } catch (_: Throwable) {}
            registeredCallbackClient = null
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            googleCastConnectTimeoutJob?.cancel()
            googleCastConnectTimeoutJob = null
            val lastPos = _sessionState.value.remotePositionMs
            stopProgressPolling()
            castLoadJob?.cancel()
            preQueueJob?.cancel()
            unregisterSystemVolumeReceiver()
            releaseCastLocks()
            mediaServer.stop()
            lastPreQueuedNextSongId = null
            hasNotifiedFinishForSongId = null
            expectedLoadSongId = null
            googleCastWasPlayingSinceLoad = false
            currentSongUsingHlsVideo = false
            hlsFallbackSongIds.clear()
            registeredCallbackClient = null
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.IDLE,
                    connectedDevice = null,
                    isRemotePlaying = false,
                    castingSong = null,
                    hasSyncedLyricsOnTv = false,
                    statusMessage = "Disconnected from Cast device",
                    errorMessage = null
                )
            }
            onCastConnectionChanged?.invoke(false, null)
            onCastDisconnected?.invoke(lastPos)
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            onEnsureLocalPlaybackSilenced?.invoke()
            val device = buildDeviceFromCastSession(session)
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.CONNECTING,
                    connectedDevice = device,
                    statusMessage = "Reconnecting to ${device.name}…"
                )
            }
            onCastConnectionChanged?.invoke(true, device.name)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            googleCastConnectTimeoutJob?.cancel()
            googleCastConnectTimeoutJob = null
            onEnsureLocalPlaybackSilenced?.invoke()
            unregisterMediaRouterDiscovery()
            acquireCastLocks()
            registerSystemVolumeReceiver()
            val device = buildDeviceFromCastSession(session)
            ensureRemoteMediaCallbackRegistered(session)
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.CONNECTED,
                    connectedDevice = device,
                    statusMessage = "Connected to ${device.name}",
                    errorMessage = null
                )
            }
            onCastConnectionChanged?.invoke(true, device.name)
            startProgressPolling()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            unregisterSystemVolumeReceiver()
            releaseCastLocks()
            mediaServer.stop()
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.IDLE,
                    connectedDevice = null,
                    isRemotePlaying = false,
                    hasSyncedLyricsOnTv = false
                )
            }
            onCastConnectionChanged?.invoke(false, null)
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            _sessionState.update {
                it.copy(statusMessage = "Connection interrupted, reconnecting…")
            }
        }
    }

    fun refreshNetworkState() {
        scope.launch {
            val (wifiOk, ip) = withContext(Dispatchers.IO) {
                val wifi = LocalCastMediaServer.isWifiConnected(appContext)
                val lanIp = LocalCastMediaServer.resolveLocalLanIpv4(appContext)
                wifi to lanIp
            }
            _sessionState.update {
                it.copy(
                    isWifiConnected = wifiOk || !ip.isNullOrBlank(),
                    localIpAddress = ip
                )
            }
        }
    }

    private suspend fun resolveLocalIpOnIo(): String? {
        val cached = _sessionState.value.localIpAddress
        if (!cached.isNullOrBlank() && LocalCastMediaServer.isWifiConnected(appContext)) {
            return cached
        }
        val (wifiOk, ip) = withContext(Dispatchers.IO) {
            val wifi = LocalCastMediaServer.isWifiConnected(appContext)
            val lanIp = LocalCastMediaServer.resolveLocalLanIpv4(appContext)
            wifi to lanIp
        }
        _sessionState.update {
            it.copy(
                isWifiConnected = wifiOk || !ip.isNullOrBlank(),
                localIpAddress = ip
            )
        }
        return ip
    }

    fun startDiscovery() {
        refreshNetworkState()
        ensureCastSdkInitialized()
        registerMediaRouterDiscovery()

        dlnaScanJob?.cancel()
        _sessionState.update { it.copy(isScanning = true, errorMessage = null) }

        dlnaScanJob = scope.launch {
            try {
                val foundDlna = dlnaEngine.discoverRenderers(timeoutMs = 2600L)
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
        // Keep MediaRouter discovery alive while CONNECTING so closing the sheet right after tapping a TV never breaks the Cast handshake!
        if (_sessionState.value.connectionState != CastConnectionState.CONNECTING) {
            unregisterMediaRouterDiscovery()
        }
        _sessionState.update { it.copy(isScanning = false) }
    }

    fun connectAndCast(
        device: CastDevice,
        song: Song?,
        startPositionMs: Long = 0L
    ) {
        // Immediately silence local playback BEFORE async network checks so phone & TV never split-play!
        onEnsureLocalPlaybackSilenced?.invoke()

        scope.launch {
            val localIp = resolveLocalIpOnIo()
            if (localIp.isNullOrBlank()) {
                _sessionState.update {
                    it.copy(
                        connectionState = CastConnectionState.ERROR,
                        errorMessage = "Connect your phone to the same Wi-Fi network as your TV before casting."
                    )
                }
                return@launch
            }

            // If switching from another active device, stop the previous session first
            val prevDevice = _sessionState.value.connectedDevice
            if (prevDevice != null && prevDevice.id != device.id) {
                disconnectInternal(notifyCallback = false)
            }

            when (device.protocol) {
                CastProtocol.GOOGLE_CAST -> {
                    googleCastRetryCount = 0
                    connectGoogleCast(device, song, startPositionMs, isRetry = false)
                }
                CastProtocol.DLNA_UPNP -> connectDlna(device, song, startPositionMs)
            }
        }
    }

    private suspend fun connectGoogleCast(
        device: CastDevice,
        song: Song?,
        startPositionMs: Long,
        isRetry: Boolean = false
    ) {
        ensureCastSdkInitialized()
        // Wait briefly for async CastContext + SessionManagerListener registration BEFORE selecting route!
        withTimeoutOrNull(2_500L) { castContextDeferred.await() }
        registerMediaRouterDiscovery()

        val router = mediaRouter
        var targetRoute = router?.routes?.firstOrNull {
            it.id == device.mediaRouteId ||
                (!it.isDefault && it.isEnabled && it.name.equals(device.name, ignoreCase = true))
        }
        if (router != null && targetRoute == null) {
            // Brief discovery wait in case routes just refreshed
            delay(350L)
            refreshGoogleCastRoutes(router)
            targetRoute = router.routes.firstOrNull {
                it.id == device.mediaRouteId ||
                    (!it.isDefault && it.isEnabled && it.name.equals(device.name, ignoreCase = true))
            }
        }
        if (router == null || targetRoute == null) {
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.ERROR,
                    connectedDevice = null,
                    errorMessage = "${device.name} is no longer reachable. Tap Refresh and make sure your TV is on."
                )
            }
            onCastConnectionChanged?.invoke(false, null)
            return
        }

        pendingGoogleCastDevice = device
        pendingGoogleCastSong = song
        pendingGoogleCastStartPosMs = startPositionMs.coerceAtLeast(0L)

        _sessionState.update {
            it.copy(
                connectionState = CastConnectionState.CONNECTING,
                connectedDevice = device,
                statusMessage = if (isRetry) "Retrying connection to ${device.name}…" else "Connecting to ${device.name}…",
                errorMessage = null
            )
        }
        onCastConnectionChanged?.invoke(true, device.name)

        val existingSession = currentGoogleCastSession()
        if (existingSession != null && existingSession.isConnected && targetRoute.isSelected) {
            googleCastConnectTimeoutJob?.cancel()
            unregisterMediaRouterDiscovery()
            acquireCastLocks()
            registerSystemVolumeReceiver()
            ensureRemoteMediaCallbackRegistered(existingSession)
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.CONNECTED,
                    connectedDevice = device,
                    statusMessage = "Connected to ${device.name}",
                    errorMessage = null
                )
            }
            onCastConnectionChanged?.invoke(true, device.name)
            if (song != null) {
                castSong(song, startPositionMs)
            }
            startProgressPolling()
            return
        }

        // Clear any zombie/disconnected session so Google Cast performs a clean first-try handshake
        if (existingSession != null && !existingSession.isConnected) {
            try {
                castContext?.sessionManager?.endCurrentSession(true)
            } catch (_: Throwable) {}
            delay(180L)
        } else if (targetRoute.isSelected && existingSession == null) {
            // Route was left selected without a CastSession — bounce via default route cleanly
            try {
                router.selectRoute(router.defaultRoute)
            } catch (_: Throwable) {}
            delay(180L)
        }

        // Connection timeout guard so UI never hangs indefinitely on CONNECTING
        googleCastConnectTimeoutJob?.cancel()
        googleCastConnectTimeoutJob = scope.launch {
            delay(13_000L)
            if (_sessionState.value.connectionState == CastConnectionState.CONNECTING &&
                pendingGoogleCastDevice?.id == device.id
            ) {
                if (googleCastRetryCount < 1) {
                    googleCastRetryCount++
                    try { router.selectRoute(router.defaultRoute) } catch (_: Throwable) {}
                    delay(450L)
                    connectGoogleCast(device, pendingGoogleCastSong, pendingGoogleCastStartPosMs, isRetry = true)
                } else {
                    pendingGoogleCastDevice = null
                    pendingGoogleCastSong = null
                    unregisterMediaRouterDiscovery()
                    _sessionState.update {
                        it.copy(
                            connectionState = CastConnectionState.ERROR,
                            connectedDevice = null,
                            statusMessage = null,
                            errorMessage = "Timed out connecting to ${device.name}. Make sure your TV is awake on the same Wi-Fi and try again."
                        )
                    }
                    onCastConnectionChanged?.invoke(false, null)
                }
            }
        }

        try {
            router.selectRoute(targetRoute)
        } catch (e: Throwable) {
            googleCastConnectTimeoutJob?.cancel()
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.ERROR,
                    connectedDevice = null,
                    errorMessage = "Unable to connect to ${device.name}: ${e.message ?: "Route selection failed"}"
                )
            }
            onCastConnectionChanged?.invoke(false, null)
        }
    }

    private fun connectDlna(device: CastDevice, song: Song?, startPositionMs: Long) {
        unregisterMediaRouterDiscovery()
        acquireCastLocks()
        _sessionState.update {
            it.copy(
                connectionState = CastConnectionState.CONNECTING,
                connectedDevice = device,
                statusMessage = "Connecting to ${device.name}…",
                errorMessage = null
            )
        }
        onCastConnectionChanged?.invoke(true, device.name)

        castLoadJob?.cancel()
        castLoadJob = scope.launch {
            try {
                if (song != null) {
                    val result = pushSongToDlna(device, song, startPositionMs)
                    if (result.isSuccess) {
                        registerSystemVolumeReceiver()
                        _sessionState.update {
                            it.copy(
                                connectionState = CastConnectionState.CONNECTED,
                                connectedDevice = device,
                                castingSong = song,
                                isRemotePlaying = true,
                                hasSyncedLyricsOnTv = false,
                                remotePositionMs = startPositionMs.coerceAtLeast(0L),
                                remoteDurationMs = song.durationMs,
                                statusMessage = "Casting to ${device.name}",
                                errorMessage = null
                            )
                        }
                        onCastConnectionChanged?.invoke(true, device.name)
                        onRemotePlaybackTick?.invoke(startPositionMs.coerceAtLeast(0L), true)
                        startProgressPolling()
                    } else {
                        unregisterSystemVolumeReceiver()
                        releaseCastLocks()
                        mediaServer.stop()
                        _sessionState.update {
                            it.copy(
                                connectionState = CastConnectionState.ERROR,
                                connectedDevice = null,
                                isRemotePlaying = false,
                                errorMessage = result.exceptionOrNull()?.message
                                    ?: "Could not start playback on ${device.name}. Accept any prompt on your TV and try again."
                            )
                        }
                        onCastConnectionChanged?.invoke(false, null)
                    }
                } else {
                    _sessionState.update {
                        it.copy(
                            connectionState = CastConnectionState.CONNECTED,
                            connectedDevice = device,
                            castingSong = null,
                            isRemotePlaying = false,
                            hasSyncedLyricsOnTv = false,
                            statusMessage = "Connected to ${device.name} • Pick any song",
                            errorMessage = null
                        )
                    }
                    onCastConnectionChanged?.invoke(true, device.name)
                }
            } catch (e: Throwable) {
                releaseCastLocks()
                mediaServer.stop()
                _sessionState.update {
                    it.copy(
                        connectionState = CastConnectionState.ERROR,
                        connectedDevice = null,
                        errorMessage = e.message ?: "Failed to connect to ${device.name}"
                    )
                }
                onCastConnectionChanged?.invoke(false, null)
            }
        }
    }

    fun castSong(song: Song, startPositionMs: Long = 0L) {
        val stateSnapshot = _sessionState.value
        val device = stateSnapshot.connectedDevice
        if (device == null) {
            if (stateSnapshot.isConnecting) {
                pendingGoogleCastSong = song
                pendingGoogleCastStartPosMs = startPositionMs.coerceAtLeast(0L)
            }
            return
        }
        onEnsureLocalPlaybackSilenced?.invoke()

        if (stateSnapshot.isConnecting && device.protocol == CastProtocol.GOOGLE_CAST) {
            pendingGoogleCastSong = song
            pendingGoogleCastStartPosMs = startPositionMs.coerceAtLeast(0L)
            return
        }

        hasNotifiedFinishForSongId = null
        dlnaWasPlayingSinceLoad = false
        googleCastWasPlayingSinceLoad = false
        lastPreQueuedNextSongId = null
        expectedLoadSongId = song.id
        lastLoadDispatchedAtMs = SystemClock.elapsedRealtime()
        lastDispatchedPlayState = true
        lastPlayPauseDispatchedAtMs = lastLoadDispatchedAtMs

        castLoadJob?.cancel()
        preQueueJob?.cancel()
        castLoadJob = scope.launch {
            val localIp = resolveLocalIpOnIo()
            if (localIp.isNullOrBlank()) {
                _sessionState.update {
                    it.copy(errorMessage = "Wi-Fi disconnected. Reconnect to Wi-Fi to continue casting.")
                }
                return@launch
            }

            when (device.protocol) {
                CastProtocol.GOOGLE_CAST -> {
                    val lyricsEnabled = _sessionState.value.tvLyricsEnabled
                    val useTvVideoHls = device.kind != CastDeviceKind.SPEAKER && song.id !in hlsFallbackSongIds

                    // Prepare ONLY the current song first so TV starts playing immediately without waiting on next-song prep!
                    val (urls, hasLyrics) = withContext(Dispatchers.IO) {
                        val resolvedLyrics = resolveSyncedLyricsForSong(song)
                        val preparedUrls = mediaServer.prepareMediaForSong(
                            song = song,
                            localIp = localIp,
                            syncedLyricsLines = resolvedLyrics.lines,
                            syncOffsetMs = resolvedLyrics.offsetMs,
                            tvLyricsEnabled = lyricsEnabled,
                            enableTvVideoHls = useTvVideoHls
                        )
                        preparedUrls to resolvedLyrics.lines.isNotEmpty()
                    }

                    if (urls == null) {
                        _sessionState.update { it.copy(errorMessage = "Could not prepare local stream for ${song.displayTitle}") }
                        return@launch
                    }
                    val session = currentGoogleCastSession()
                    val client = session?.remoteMediaClient
                    if (session == null || client == null) {
                        _sessionState.update { it.copy(errorMessage = "Cast session is not active. Tap your TV to reconnect.") }
                        return@launch
                    }
                    ensureRemoteMediaCallbackRegistered(session)

                    val usingHls = useTvVideoHls && !urls.hlsVideoUrl.isNullOrBlank()
                    currentSongUsingHlsVideo = usingHls
                    val style = buildTvLyricsTrackStyle()

                    try {
                        val currentMediaInfo = buildGoogleCastMediaInfo(
                            song = song,
                            urls = urls,
                            style = style,
                            preferHlsVideo = usingHls
                        )
                        val activeTrackIds = if (!usingHls && lyricsEnabled && urls.hasSyncedLyrics) {
                            longArrayOf(LYRICS_TRACK_ID)
                        } else {
                            longArrayOf()
                        }

                        val currentItem = MediaQueueItem.Builder(currentMediaInfo)
                            .setAutoplay(true)
                            .setCustomData(JSONObject().put("songId", song.id))
                            .apply {
                                if (activeTrackIds.isNotEmpty()) setActiveTrackIds(activeTrackIds)
                            }
                            .build()

                        expectedLoadSongId = song.id
                        lastLoadDispatchedAtMs = SystemClock.elapsedRealtime()
                        client.queueLoad(
                            arrayOf(currentItem),
                            0,
                            MediaStatus.REPEAT_MODE_REPEAT_OFF,
                            startPositionMs.coerceAtLeast(0L),
                            null
                        )

                        if (!usingHls) {
                            try {
                                client.setTextTrackStyle(style)
                                if (activeTrackIds.isNotEmpty()) {
                                    client.setActiveMediaTracks(activeTrackIds)
                                }
                            } catch (_: Throwable) {}
                        }

                        _sessionState.update {
                            it.copy(
                                castingSong = song,
                                isRemotePlaying = true,
                                hasSyncedLyricsOnTv = hasLyrics,
                                remotePositionMs = startPositionMs.coerceAtLeast(0L),
                                remoteDurationMs = song.durationMs,
                                statusMessage = "Casting to ${device.name}",
                                errorMessage = null
                            )
                        }
                        onRemotePlaybackTick?.invoke(startPositionMs.coerceAtLeast(0L), true)
                        startProgressPolling()

                        // Asynchronously pre-queue the next track AFTER current song has started loading on TV
                        preQueueJob?.cancel()
                        preQueueJob = scope.launch {
                            delay(900L)
                            if (_sessionState.value.castingSong?.id != song.id || !_sessionState.value.isConnected) return@launch
                            val upcomingSong = try { nextSongProvider?.invoke() } catch (_: Throwable) { null }
                            if (upcomingSong == null || upcomingSong.id == song.id || upcomingSong.id == lastPreQueuedNextSongId) return@launch
                            val nextPrepared = withContext(Dispatchers.IO) {
                                val nextLyrics = resolveSyncedLyricsForSong(upcomingSong)
                                val nextUseHls = device.kind != CastDeviceKind.SPEAKER && upcomingSong.id !in hlsFallbackSongIds
                                mediaServer.prepareMediaForSong(
                                    song = upcomingSong,
                                    localIp = localIp,
                                    syncedLyricsLines = nextLyrics.lines,
                                    syncOffsetMs = nextLyrics.offsetMs,
                                    tvLyricsEnabled = _sessionState.value.tvLyricsEnabled,
                                    enableTvVideoHls = nextUseHls
                                )
                            } ?: return@launch
                            if (_sessionState.value.castingSong?.id != song.id || !_sessionState.value.isConnected) return@launch
                            val activeClient = currentGoogleCastSession()?.remoteMediaClient ?: return@launch
                            val nextUsingHls = device.kind != CastDeviceKind.SPEAKER &&
                                upcomingSong.id !in hlsFallbackSongIds &&
                                !nextPrepared.hlsVideoUrl.isNullOrBlank()
                            val nextMediaInfo = buildGoogleCastMediaInfo(
                                song = upcomingSong,
                                urls = nextPrepared,
                                style = style,
                                preferHlsVideo = nextUsingHls
                            )
                            val nextActiveTracks = if (!nextUsingHls && _sessionState.value.tvLyricsEnabled && nextPrepared.hasSyncedLyrics) {
                                longArrayOf(LYRICS_TRACK_ID)
                            } else {
                                longArrayOf()
                            }
                            val nextItem = MediaQueueItem.Builder(nextMediaInfo)
                                .setAutoplay(true)
                                .setPreloadTime(10.0)
                                .setCustomData(JSONObject().put("songId", upcomingSong.id))
                                .apply {
                                    if (nextActiveTracks.isNotEmpty()) setActiveTrackIds(nextActiveTracks)
                                }
                                .build()
                            try {
                                activeClient.queueAppendItem(nextItem, null)
                                lastPreQueuedNextSongId = upcomingSong.id
                            } catch (_: Throwable) {}
                        }
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
                                hasSyncedLyricsOnTv = false,
                                remotePositionMs = startPositionMs.coerceAtLeast(0L),
                                remoteDurationMs = song.durationMs,
                                statusMessage = "Casting to ${device.name}",
                                errorMessage = null
                            )
                        }
                        onRemotePlaybackTick?.invoke(startPositionMs.coerceAtLeast(0L), true)
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

    /**
     * Called when Chromecast seamlessly advances to the pre-queued next track on its own,
     * keeping [CastSessionState] synced and appending the following track for uninterrupted playback.
     */
    fun onSyncedAutoAdvance(activeSong: Song, upcomingNextSong: Song?) {
        val device = _sessionState.value.connectedDevice ?: return
        scope.launch {
            val lyricsEnabled = _sessionState.value.tvLyricsEnabled
            val resolvedCurrent = withContext(Dispatchers.IO) {
                resolveSyncedLyricsForSong(activeSong).also { res ->
                    mediaServer.updateLiveLyricsForSong(
                        songId = activeSong.id,
                        durationMs = activeSong.durationMs,
                        syncedLyricsLines = res.lines,
                        syncOffsetMs = res.offsetMs,
                        tvLyricsEnabled = lyricsEnabled
                    )
                }
            }
            val activeUsingHls = device.protocol == CastProtocol.GOOGLE_CAST &&
                device.kind != CastDeviceKind.SPEAKER &&
                activeSong.id !in hlsFallbackSongIds
            currentSongUsingHlsVideo = activeUsingHls

            _sessionState.update {
                it.copy(
                    castingSong = activeSong,
                    isRemotePlaying = true,
                    hasSyncedLyricsOnTv = resolvedCurrent.lines.isNotEmpty(),
                    remotePositionMs = 0L,
                    remoteDurationMs = activeSong.durationMs,
                    statusMessage = "Casting to ${device.name}",
                    errorMessage = null
                )
            }
            if (device.protocol == CastProtocol.GOOGLE_CAST &&
                upcomingNextSong != null &&
                upcomingNextSong.id != activeSong.id &&
                upcomingNextSong.id != lastPreQueuedNextSongId
            ) {
                val localIp = resolveLocalIpOnIo() ?: return@launch
                val nextUseHls = device.kind != CastDeviceKind.SPEAKER && upcomingNextSong.id !in hlsFallbackSongIds
                val nextUrls = withContext(Dispatchers.IO) {
                    val nextLyrics = resolveSyncedLyricsForSong(upcomingNextSong)
                    mediaServer.prepareMediaForSong(
                        song = upcomingNextSong,
                        localIp = localIp,
                        syncedLyricsLines = nextLyrics.lines,
                        syncOffsetMs = nextLyrics.offsetMs,
                        tvLyricsEnabled = lyricsEnabled,
                        enableTvVideoHls = nextUseHls
                    )
                } ?: return@launch
                val session = currentGoogleCastSession() ?: return@launch
                val client = session.remoteMediaClient ?: return@launch
                ensureRemoteMediaCallbackRegistered(session)
                val style = buildTvLyricsTrackStyle()
                val nextActuallyHls = nextUseHls && !nextUrls.hlsVideoUrl.isNullOrBlank()
                try {
                    val nextInfo = buildGoogleCastMediaInfo(
                        song = upcomingNextSong,
                        urls = nextUrls,
                        style = style,
                        preferHlsVideo = nextActuallyHls
                    )
                    val nextItem = MediaQueueItem.Builder(nextInfo)
                        .setAutoplay(true)
                        .setPreloadTime(10.0)
                        .setCustomData(JSONObject().put("songId", upcomingNextSong.id))
                        .apply {
                            if (!nextActuallyHls && lyricsEnabled && nextUrls.hasSyncedLyrics) {
                                setActiveTrackIds(longArrayOf(LYRICS_TRACK_ID))
                            }
                        }
                        .build()
                    lastPreQueuedNextSongId = upcomingNextSong.id
                    client.queueAppendItem(nextItem, null)
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Pushes newly loaded, fetched, edited, or offset-adjusted lyrics from the app directly
     * into the live TV HLS stream and updates the Cast sheet status immediately.
     */
    fun updateLyricsForSong(songId: Long, lyrics: Lyrics?, offsetMs: Long) {
        val state = _sessionState.value
        if (!state.isActiveSession) return
        val castingSong = state.castingSong
        val syncedLines = extractSyncedLinesFromModel(lyrics)
        val hasLyricsNow = syncedLines.isNotEmpty()
        val isCurrentSong = castingSong?.id == songId
        val hadLyricsBefore = state.hasSyncedLyricsOnTv
        val lyricsEnabled = state.tvLyricsEnabled

        scope.launch {
            withContext(Dispatchers.IO) {
                mediaServer.updateLiveLyricsForSong(
                    songId = songId,
                    durationMs = castingSong?.takeIf { it.id == songId }?.durationMs ?: 0L,
                    syncedLyricsLines = syncedLines,
                    syncOffsetMs = offsetMs,
                    tvLyricsEnabled = lyricsEnabled
                )
            }
            if (isCurrentSong) {
                if (hadLyricsBefore != hasLyricsNow) {
                    _sessionState.update { it.copy(hasSyncedLyricsOnTv = hasLyricsNow) }
                }
                // Flush the Chromecast HLS buffer once when lyrics first become available mid-song
                if (!hadLyricsBefore && hasLyricsNow && lyricsEnabled && currentSongUsingHlsVideo && state.isConnected) {
                    seekRemote(_sessionState.value.remotePositionMs)
                }
            }
        }
    }

    fun setTvLyricsEnabled(enabled: Boolean) {
        _sessionState.update { it.copy(tvLyricsEnabled = enabled) }
        val state = _sessionState.value
        val device = state.connectedDevice ?: return
        if (device.protocol != CastProtocol.GOOGLE_CAST) return
        val song = state.castingSong
        if (song != null) {
            scope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    resolveSyncedLyricsForSong(song).also { res ->
                        mediaServer.updateLiveLyricsForSong(
                            songId = song.id,
                            durationMs = song.durationMs,
                            syncedLyricsLines = res.lines,
                            syncOffsetMs = res.offsetMs,
                            tvLyricsEnabled = enabled
                        )
                    }
                }
                if (resolved.lines.isNotEmpty() && !state.hasSyncedLyricsOnTv) {
                    _sessionState.update { it.copy(hasSyncedLyricsOnTv = true) }
                }
                if (currentSongUsingHlsVideo) {
                    // Refresh TV buffer at current position so toggling lyrics on/off takes effect immediately
                    seekRemote(_sessionState.value.remotePositionMs)
                } else {
                    val client = currentGoogleCastSession()?.remoteMediaClient ?: return@launch
                    try {
                        if (enabled && resolved.lines.isNotEmpty()) {
                            client.setTextTrackStyle(buildTvLyricsTrackStyle())
                            client.setActiveMediaTracks(longArrayOf(LYRICS_TRACK_ID))
                        } else {
                            client.setActiveMediaTracks(longArrayOf())
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun buildGoogleCastMediaInfo(
        song: Song,
        urls: CastMediaUrls,
        style: TextTrackStyle,
        preferHlsVideo: Boolean
    ): MediaInfo {
        if (preferHlsVideo && !urls.hlsVideoUrl.isNullOrBlank()) {
            val videoMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
                putString(MediaMetadata.KEY_TITLE, song.displayTitle)
                putString(MediaMetadata.KEY_SUBTITLE, song.displayArtist)
                urls.artworkUrl?.let { art ->
                    addImage(WebImage(Uri.parse(art)))
                }
            }
            return MediaInfo.Builder(urls.hlsVideoUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType("application/x-mpegurl")
                .setHlsSegmentFormat(HlsSegmentFormat.TS)
                .setHlsVideoSegmentFormat(HlsVideoSegmentFormat.MPEG2_TS)
                .setMetadata(videoMetadata)
                .setCustomData(JSONObject().put("songId", song.id))
                .apply {
                    if (song.durationMs > 0L) setStreamDuration(song.durationMs)
                }
                .build()
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, song.displayTitle)
            putString(MediaMetadata.KEY_ARTIST, song.displayArtist)
            putString(MediaMetadata.KEY_ALBUM_TITLE, song.displayAlbum)
            urls.artworkUrl?.let { art ->
                addImage(WebImage(Uri.parse(art)))
            }
        }
        val tracks = if (urls.hasSyncedLyrics && !urls.lyricsVttUrl.isNullOrBlank()) {
            listOf(
                MediaTrack.Builder(LYRICS_TRACK_ID, MediaTrack.TYPE_TEXT)
                    .setName("Lyrics")
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentId(urls.lyricsVttUrl)
                    .setContentType("text/vtt")
                    .setLanguage("en-US")
                    .build()
            )
        } else {
            emptyList()
        }
        return MediaInfo.Builder(urls.streamUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(urls.mimeType)
            .setMetadata(metadata)
            .setCustomData(JSONObject().put("songId", song.id))
            .apply {
                if (song.durationMs > 0L) setStreamDuration(song.durationMs)
                if (tracks.isNotEmpty()) {
                    setMediaTracks(tracks)
                    setTextTrackStyle(style)
                }
            }
            .build()
    }

    private fun buildTvLyricsTrackStyle(): TextTrackStyle {
        return TextTrackStyle().apply {
            foregroundColor = Color.argb(255, 255, 255, 255)
            backgroundColor = Color.argb(150, 12, 16, 24)
            edgeType = TextTrackStyle.EDGE_TYPE_DROP_SHADOW
            edgeColor = Color.argb(215, 0, 0, 0)
            windowType = TextTrackStyle.WINDOW_TYPE_ROUNDED
            windowColor = Color.argb(110, 10, 14, 22)
            windowCornerRadius = 10
            fontGenericFamily = TextTrackStyle.FONT_FAMILY_SANS_SERIF
            fontStyle = TextTrackStyle.FONT_STYLE_BOLD
            fontScale = 1.0f
        }
    }

    private data class ResolvedLyrics(
        val lines: List<Pair<Long, String>>,
        val offsetMs: Long
    )

    private fun extractSyncedLinesFromModel(lyrics: Lyrics?): List<Pair<Long, String>> {
        if (lyrics == null || lyrics.source == "loading") return emptyList()
        val parsed = try {
            lyrics.parseSyncedLines()
        } catch (_: Throwable) {
            emptyList()
        }
        if (parsed.isNotEmpty()) return parsed
        val raw = lyrics.syncedLyricsJson?.takeIf { it.isNotBlank() } ?: lyrics.plainLyrics.orEmpty()
        if (raw.isBlank()) return emptyList()
        return try {
            LrcParser.parseLrcToLines(raw).map { it.timeMs to it.text }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readLrcFileLines(file: File): List<Pair<Long, String>> {
        if (!file.exists() || !file.canRead() || file.length() !in 1L..1_048_576L) return emptyList()
        val rawUtf8 = try {
            file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        } catch (_: Throwable) {
            ""
        }
        val utf8Lines = try {
            LrcParser.parseLrcToLines(rawUtf8).map { it.timeMs to it.text }
        } catch (_: Throwable) {
            emptyList()
        }
        if (utf8Lines.isNotEmpty()) return utf8Lines

        // Fallback for non-UTF8 encoded .lrc files
        return try {
            val rawAlt = file.readText(Charsets.ISO_8859_1)
            LrcParser.parseLrcToLines(rawAlt).map { it.timeMs to it.text }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun resolveSyncedLyricsForSong(song: Song): ResolvedLyrics {
        return try {
            var offsetMs = try {
                UserPreferencesRepository.getInstance(appContext).getLyricsSyncOffsetMs(song.id)
            } catch (_: Throwable) {
                0L
            }

            // 1. Live in-memory lyrics already displayed in MusicViewModel (covers embedded tags, online fetches, edits)
            val liveEntry = try {
                currentLyricsProvider?.invoke(song.id)
            } catch (_: Throwable) {
                null
            }
            if (liveEntry != null) {
                offsetMs = liveEntry.second
                val liveLines = extractSyncedLinesFromModel(liveEntry.first)
                if (liveLines.isNotEmpty()) {
                    return ResolvedLyrics(liveLines, offsetMs)
                }
            }

            // 2. Room DB cached lyrics
            val dbLyrics = try {
                AppDatabase.getInstance(appContext).lyricsDao().getLyricsForSong(song.id)?.toDomainModel()
            } catch (_: Throwable) {
                null
            }
            val dbLines = extractSyncedLinesFromModel(dbLyrics)
            if (dbLines.isNotEmpty()) {
                return ResolvedLyrics(dbLines, offsetMs)
            }

            // 3. Internal app cacheDir/lyrics/<songId>.lrc
            val cachedLrcFile = File(File(appContext.cacheDir, "lyrics"), "${song.id}.lrc")
            val cachedLines = readLrcFileLines(cachedLrcFile)
            if (cachedLines.isNotEmpty()) {
                return ResolvedLyrics(cachedLines, offsetMs)
            }

            // 4. Sidecar .lrc file alongside the audio file
            if (song.path.isNotBlank()) {
                val lrcFile = LrcParser.findLocalLrcFile(song.path)
                    ?: File("${song.path.substringBeforeLast('.', song.path)}.lrc")
                val sidecarLines = readLrcFileLines(lrcFile)
                if (sidecarLines.isNotEmpty()) {
                    return ResolvedLyrics(sidecarLines, offsetMs)
                }
            }

            ResolvedLyrics(emptyList(), offsetMs)
        } catch (_: Throwable) {
            ResolvedLyrics(emptyList(), 0L)
        }
    }

    private suspend fun pushSongToDlna(
        device: CastDevice,
        song: Song,
        startPositionMs: Long
    ): Result<Unit> {
        val localIp = resolveLocalIpOnIo()
            ?: return Result.failure(IllegalStateException("Wi-Fi network unavailable"))
        val urls = withContext(Dispatchers.IO) {
            mediaServer.prepareMediaForSong(song, localIp, null)
        } ?: return Result.failure(IllegalStateException("Could not start local stream server"))

        val loadRes = dlnaEngine.loadAndPlay(device, song, urls)
        if (loadRes.isSuccess && startPositionMs > 2000L) {
            delay(450)
            dlnaEngine.seek(device, startPositionMs)
        }
        return loadRes
    }

    fun setRemotePlayState(play: Boolean) {
        val state = _sessionState.value
        val device = state.connectedDevice ?: return
        onEnsureLocalPlaybackSilenced?.invoke()
        lastDispatchedPlayState = play
        lastPlayPauseDispatchedAtMs = SystemClock.elapsedRealtime()

        when (device.protocol) {
            CastProtocol.GOOGLE_CAST -> {
                val client = currentGoogleCastSession()?.remoteMediaClient ?: return
                try {
                    if (play) client.play() else client.pause()
                    _sessionState.update { it.copy(isRemotePlaying = play) }
                    onRemotePlaybackTick?.invoke(state.remotePositionMs, play)
                } catch (_: Throwable) {}
            }
            CastProtocol.DLNA_UPNP -> {
                scope.launch {
                    val res = if (play) dlnaEngine.play(device) else dlnaEngine.pause(device)
                    if (res.isSuccess) {
                        _sessionState.update { it.copy(isRemotePlaying = play) }
                        onRemotePlaybackTick?.invoke(state.remotePositionMs, play)
                    }
                }
            }
        }
    }

    fun toggleRemotePlayPause() {
        setRemotePlayState(!_sessionState.value.isRemotePlaying)
    }

    fun seekRemote(positionMs: Long) {
        val state = _sessionState.value
        val device = state.connectedDevice ?: return
        val safePos = positionMs.coerceAtLeast(0L)
        lastSeekDispatchedAtMs = SystemClock.elapsedRealtime()

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

    fun adjustRemoteVolume(delta01: Float) {
        val current = _sessionState.value.remoteVolume
        val stepped = ((current + delta01) * 20f).roundToInt().coerceIn(0, 20) / 20f
        setRemoteVolume(stepped)
    }

    fun setRemoteVolume(volume01: Float) {
        val clamped = volume01.coerceIn(0f, 1f)
        val device = _sessionState.value.connectedDevice ?: return
        lastRemoteVolumeSyncedAtMs = SystemClock.elapsedRealtime()
        _sessionState.update { it.copy(remoteVolume = clamped) }
        onRemoteVolumeChanged?.invoke(clamped)

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
        googleCastConnectTimeoutJob?.cancel()
        googleCastConnectTimeoutJob = null
        googleCastRetryCount = 0
        stopProgressPolling()
        castLoadJob?.cancel()
        preQueueJob?.cancel()
        unregisterSystemVolumeReceiver()
        unregisterMediaRouterDiscovery()
        releaseCastLocks()

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
        expectedLoadSongId = null
        lastDispatchedPlayState = null
        dlnaWasPlayingSinceLoad = false
        googleCastWasPlayingSinceLoad = false
        currentSongUsingHlsVideo = false
        hlsFallbackSongIds.clear()
        registeredCallbackClient = null
        lastPreQueuedNextSongId = null
        mediaServer.stop()

        _sessionState.update {
            it.copy(
                connectionState = CastConnectionState.IDLE,
                connectedDevice = null,
                isRemotePlaying = false,
                castingSong = null,
                hasSyncedLyricsOnTv = false,
                statusMessage = null,
                errorMessage = null
            )
        }
        onCastConnectionChanged?.invoke(false, null)
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

    private fun acquireCastLocks() {
        try {
            if (castWakeLock == null) {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                castWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OffGridMusic:CastStreamWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (castWakeLock?.isHeld == false) {
                // 4-hour safety cap; always released immediately on disconnect
                castWakeLock?.acquire(4L * 3_600_000L)
            }
        } catch (_: Throwable) {}

        try {
            if (castWifiLock == null) {
                val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                castWifiLock = wm?.createWifiLock(mode, "OffGridMusic:CastWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (castWifiLock?.isHeld == false) {
                castWifiLock?.acquire()
            }
        } catch (_: Throwable) {}
    }

    private fun releaseCastLocks() {
        try {
            if (castWakeLock?.isHeld == true) {
                castWakeLock?.release()
            }
        } catch (_: Throwable) {}
        try {
            if (castWifiLock?.isHeld == true) {
                castWifiLock?.release()
            }
        } catch (_: Throwable) {}
    }

    private fun startProgressPolling() {
        progressPollJob?.cancel()
        lastKeepAliveAtMs = SystemClock.elapsedRealtime()
        progressPollJob = scope.launch {
            while (isActive) {
                val state = _sessionState.value
                val device = state.connectedDevice ?: break
                if (state.connectionState != CastConnectionState.CONNECTED) break
                delay(if (device.protocol == CastProtocol.GOOGLE_CAST) 1000L else 1500L)

                when (device.protocol) {
                    CastProtocol.GOOGLE_CAST -> {
                        try {
                            val session = currentGoogleCastSession()
                            val client = session?.remoteMediaClient
                            if (session != null && client != null) {
                                ensureRemoteMediaCallbackRegistered(session)
                                processGoogleCastStatus(session, client)

                                // Periodic 20s status keepalive keeps Cast channel & TV session warm indefinitely
                                val nowMs = SystemClock.elapsedRealtime()
                                if (nowMs - lastKeepAliveAtMs >= 20_000L) {
                                    lastKeepAliveAtMs = nowMs
                                    try {
                                        client.requestStatus()
                                    } catch (_: Throwable) {}
                                }
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

    private fun registerSystemVolumeReceiver() {
        if (isSystemVolumeReceiverRegistered) return
        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            ContextCompat.registerReceiver(
                appContext,
                systemVolumeReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isSystemVolumeReceiverRegistered = true
        } catch (_: Throwable) {}
    }

    private fun unregisterSystemVolumeReceiver() {
        if (!isSystemVolumeReceiverRegistered) return
        try {
            appContext.unregisterReceiver(systemVolumeReceiver)
        } catch (_: Throwable) {}
        isSystemVolumeReceiverRegistered = false
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
                            onEnsureLocalPlaybackSilenced?.invoke()
                            acquireCastLocks()
                            registerSystemVolumeReceiver()
                            ensureRemoteMediaCallbackRegistered(active)
                            val dev = buildDeviceFromCastSession(active)
                            _sessionState.update {
                                it.copy(
                                    connectionState = CastConnectionState.CONNECTED,
                                    connectedDevice = dev,
                                    statusMessage = "Connected to ${dev.name}"
                                )
                            }
                            onCastConnectionChanged?.invoke(true, dev.name)
                            startProgressPolling()
                        }
                    } catch (_: Throwable) {}
                    if (!castContextDeferred.isCompleted) {
                        castContextDeferred.complete(ctx)
                    }
                }
                .addOnFailureListener {
                    if (!castContextDeferred.isCompleted) {
                        castContextDeferred.complete(null)
                    }
                }
        } catch (_: Throwable) {
            if (!castContextDeferred.isCompleted) {
                castContextDeferred.complete(null)
            }
        }
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
        googleCastConnectTimeoutJob?.cancel()
        stopDiscovery()
        stopProgressPolling()
        castLoadJob?.cancel()
        preQueueJob?.cancel()
        unregisterSystemVolumeReceiver()
        unregisterMediaRouterDiscovery()
        releaseCastLocks()
        try {
            castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
        } catch (_: Throwable) {}
        mediaServer.stop()
        try {
            castExecutor.shutdownNow()
        } catch (_: Throwable) {}
        scope.cancel()
    }

    companion object {
        const val LYRICS_TRACK_ID = 1L

        @Volatile
        var activeInstance: CastManager? = null
            private set

        fun getInstance(context: Context): CastManager {
            return activeInstance ?: synchronized(this) {
                activeInstance ?: CastManager(context.applicationContext).also {
                    activeInstance = it
                }
            }
        }
    }
}
