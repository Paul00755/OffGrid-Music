package com.example.cast

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.Song
import com.example.util.LrcParser
import com.google.android.gms.cast.CastMediaControlIntent
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
    private var castLoadJob: Job? = null
    private var hasNotifiedFinishForSongId: Long? = null
    private var dlnaWasPlayingSinceLoad: Boolean = false
    private var lastPreQueuedNextSongId: Long? = null
    private var lastKeepAliveAtMs: Long = 0L

    private var castWakeLock: PowerManager.WakeLock? = null
    private var castWifiLock: WifiManager.WifiLock? = null

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

                // Sync remote volume if user changed it on TV remote or Google Home
                try {
                    val tvVol = session.volume.toFloat().coerceIn(0f, 1f)
                    if (kotlin.math.abs(tvVol - _sessionState.value.remoteVolume) >= 0.02f) {
                        _sessionState.update { it.copy(remoteVolume = tvVol) }
                        onRemoteVolumeChanged?.invoke(tvVol)
                    }
                } catch (_: Throwable) {}

                // Detect seamless TV-side queue auto-advance to pre-queued next song
                val remoteSongId = status?.mediaInfo?.customData?.optLong("songId", -1L)
                    ?.takeIf { it > 0L }
                val currentCastingId = _sessionState.value.castingSong?.id
                if (remoteSongId != null &&
                    currentCastingId != null &&
                    remoteSongId != currentCastingId &&
                    status.playerState != MediaStatus.PLAYER_STATE_IDLE
                ) {
                    hasNotifiedFinishForSongId = null
                    lastPreQueuedNextSongId = null
                    onRemoteAutoAdvancedToSong?.invoke(remoteSongId)
                }

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
            onEnsureLocalPlaybackSilenced?.invoke()
            // Immediately stop active MediaRouter scanning once connected to eliminate phone lag
            unregisterMediaRouterDiscovery()
            acquireCastLocks()

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
            pendingGoogleCastDevice = null
            pendingGoogleCastSong = null
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
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            val lastPos = _sessionState.value.remotePositionMs
            stopProgressPolling()
            castLoadJob?.cancel()
            releaseCastLocks()
            mediaServer.stop()
            lastPreQueuedNextSongId = null
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
            onEnsureLocalPlaybackSilenced?.invoke()
            unregisterMediaRouterDiscovery()
            acquireCastLocks()
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
            onCastConnectionChanged?.invoke(true, device.name)
            startProgressPolling()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
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
        // Always unregister active MediaRouter scanning when the sheet closes to save CPU/Wi-Fi and prevent lag
        unregisterMediaRouterDiscovery()
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
                CastProtocol.GOOGLE_CAST -> connectGoogleCast(device, song, startPositionMs)
                CastProtocol.DLNA_UPNP -> connectDlna(device, song, startPositionMs)
            }
        }
    }

    private fun connectGoogleCast(device: CastDevice, song: Song?, startPositionMs: Long) {
        ensureCastSdkInitialized()
        // Ensure route table is fresh if user tapped quickly
        val router = mediaRouter
        val targetRoute = router?.routes?.firstOrNull { it.id == device.mediaRouteId }
        if (router == null || targetRoute == null) {
            _sessionState.update {
                it.copy(
                    connectionState = CastConnectionState.ERROR,
                    errorMessage = "${device.name} is no longer reachable. Tap Refresh and make sure your TV is on."
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
        onCastConnectionChanged?.invoke(true, device.name)

        val existingSession = currentGoogleCastSession()
        if (existingSession != null && existingSession.isConnected && targetRoute.isSelected) {
            unregisterMediaRouterDiscovery()
            acquireCastLocks()
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
        lastPreQueuedNextSongId = null

        castLoadJob?.cancel()
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
                    val prepared = withContext(Dispatchers.IO) {
                        val vtt = resolveSyncedLyricsVttForSong(song)
                        val urls = mediaServer.prepareMediaForSong(song, localIp, vtt)
                        // Also prepare the next song for seamless gapless TV queue transitions!
                        val upcomingSong = try { nextSongProvider?.invoke() } catch (_: Throwable) { null }
                        val upcomingUrls = if (upcomingSong != null && upcomingSong.id != song.id) {
                            val nextVtt = resolveSyncedLyricsVttForSong(upcomingSong)
                            mediaServer.prepareMediaForSong(upcomingSong, localIp, nextVtt)?.let { upcomingSong to it }
                        } else {
                            null
                        }
                        Triple(urls, upcomingUrls, !vtt.isNullOrBlank())
                    }

                    val urls = prepared.first
                    val upcomingPair = prepared.second
                    val hasLyrics = prepared.third

                    if (urls == null) {
                        _sessionState.update { it.copy(errorMessage = "Could not prepare local stream for ${song.displayTitle}") }
                        return@launch
                    }
                    val client = currentGoogleCastSession()?.remoteMediaClient
                    if (client == null) {
                        _sessionState.update { it.copy(errorMessage = "Cast session is not active. Tap your TV to reconnect.") }
                        return@launch
                    }

                    val lyricsEnabled = _sessionState.value.tvLyricsEnabled
                    val style = buildTvLyricsTrackStyle()

                    try {
                        val currentMediaInfo = buildGoogleCastMediaInfo(song, urls, style)
                        val activeTrackIds = if (lyricsEnabled && urls.hasSyncedLyrics) longArrayOf(LYRICS_TRACK_ID) else longArrayOf()

                        if (upcomingPair != null) {
                            val (nextSong, nextUrls) = upcomingPair
                            val nextMediaInfo = buildGoogleCastMediaInfo(nextSong, nextUrls, style)
                            val nextActiveTracks = if (lyricsEnabled && nextUrls.hasSyncedLyrics) longArrayOf(LYRICS_TRACK_ID) else longArrayOf()

                            val currentItem = MediaQueueItem.Builder(currentMediaInfo)
                                .setAutoplay(true)
                                .apply {
                                    if (activeTrackIds.isNotEmpty()) setActiveTrackIds(activeTrackIds)
                                }
                                .build()
                            val nextItem = MediaQueueItem.Builder(nextMediaInfo)
                                .setAutoplay(true)
                                .setPreloadTime(8.0)
                                .apply {
                                    if (nextActiveTracks.isNotEmpty()) setActiveTrackIds(nextActiveTracks)
                                }
                                .build()

                            lastPreQueuedNextSongId = nextSong.id
                            client.queueLoad(
                                arrayOf(currentItem, nextItem),
                                0,
                                MediaStatus.REPEAT_MODE_REPEAT_OFF,
                                startPositionMs.coerceAtLeast(0L),
                                null
                            )
                        } else {
                            val requestData = MediaLoadRequestData.Builder()
                                .setMediaInfo(currentMediaInfo)
                                .setAutoplay(true)
                                .setCurrentTime(startPositionMs.coerceAtLeast(0L))
                                .apply {
                                    if (activeTrackIds.isNotEmpty()) setActiveTrackIds(activeTrackIds)
                                }
                                .build()
                            client.load(requestData)
                        }

                        // Explicitly apply crisp TextTrackStyle and active tracks on the receiver
                        try {
                            client.setTextTrackStyle(style)
                            if (activeTrackIds.isNotEmpty()) {
                                client.setActiveMediaTracks(activeTrackIds)
                            }
                        } catch (_: Throwable) {}

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
            val hasCurrentLyrics = withContext(Dispatchers.IO) {
                !resolveSyncedLyricsVttForSong(activeSong).isNullOrBlank()
            }
            _sessionState.update {
                it.copy(
                    castingSong = activeSong,
                    isRemotePlaying = true,
                    hasSyncedLyricsOnTv = hasCurrentLyrics,
                    remotePositionMs = 0L,
                    remoteDurationMs = activeSong.durationMs,
                    statusMessage = "Casting to ${device.name}",
                    errorMessage = null
                )
            }
            if (device.protocol == CastProtocol.GOOGLE_CAST && upcomingNextSong != null && upcomingNextSong.id != activeSong.id && upcomingNextSong.id != lastPreQueuedNextSongId) {
                val localIp = resolveLocalIpOnIo() ?: return@launch
                val nextUrls = withContext(Dispatchers.IO) {
                    val nextVtt = resolveSyncedLyricsVttForSong(upcomingNextSong)
                    mediaServer.prepareMediaForSong(upcomingNextSong, localIp, nextVtt)
                } ?: return@launch
                val client = currentGoogleCastSession()?.remoteMediaClient ?: return@launch
                val style = buildTvLyricsTrackStyle()
                val lyricsEnabled = _sessionState.value.tvLyricsEnabled
                try {
                    val nextInfo = buildGoogleCastMediaInfo(upcomingNextSong, nextUrls, style)
                    val nextItem = MediaQueueItem.Builder(nextInfo)
                        .setAutoplay(true)
                        .setPreloadTime(8.0)
                        .apply {
                            if (lyricsEnabled && nextUrls.hasSyncedLyrics) {
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

    fun setTvLyricsEnabled(enabled: Boolean) {
        _sessionState.update { it.copy(tvLyricsEnabled = enabled) }
        val device = _sessionState.value.connectedDevice ?: return
        if (device.protocol != CastProtocol.GOOGLE_CAST) return
        val client = currentGoogleCastSession()?.remoteMediaClient ?: return
        try {
            if (enabled && _sessionState.value.hasSyncedLyricsOnTv) {
                client.setTextTrackStyle(buildTvLyricsTrackStyle())
                client.setActiveMediaTracks(longArrayOf(LYRICS_TRACK_ID))
            } else {
                client.setActiveMediaTracks(longArrayOf())
            }
        } catch (_: Throwable) {}
    }

    private fun buildGoogleCastMediaInfo(
        song: Song,
        urls: CastMediaUrls,
        style: TextTrackStyle
    ): MediaInfo {
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

    private suspend fun resolveSyncedLyricsVttForSong(song: Song): String? {
        return try {
            val offsetMs = try {
                UserPreferencesRepository.getInstance(appContext).getLyricsSyncOffsetMs(song.id)
            } catch (_: Throwable) {
                0L
            }

            // 1. Check cached lyrics in Room DB
            val dbLyrics = try {
                AppDatabase.getInstance(appContext).lyricsDao().getLyricsForSong(song.id)?.toDomainModel()
            } catch (_: Throwable) {
                null
            }
            var syncedLines: List<Pair<Long, String>> = dbLyrics?.parseSyncedLines().orEmpty()

            // 2. Fallback: check sidecar .lrc file alongside the audio file
            if (syncedLines.isEmpty() && song.path.isNotBlank()) {
                val lrcFile = LrcParser.findLocalLrcFile(song.path)
                    ?: File("${song.path.substringBeforeLast('.', song.path)}.lrc")
                if (lrcFile.exists() && lrcFile.canRead() && lrcFile.length() in 1L..512_000L) {
                    val rawLrc = lrcFile.readText(Charsets.UTF_8)
                    syncedLines = LrcParser.parseLrcToLines(rawLrc).map { it.timeMs to it.text }
                }
            }

            if (syncedLines.isEmpty()) return null
            LocalCastMediaServer.buildWebVttFromLyrics(
                syncedLines = syncedLines,
                songDurationMs = song.durationMs,
                syncOffsetMs = offsetMs
            )
        } catch (_: Throwable) {
            null
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
        stopProgressPolling()
        castLoadJob?.cancel()
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
        dlnaWasPlayingSinceLoad = false
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
                            val client = currentGoogleCastSession()?.remoteMediaClient
                            if (client != null) {
                                val isPlaying = client.isPlaying
                                val pos = client.approximateStreamPosition.coerceAtLeast(0L)
                                val dur = client.streamDuration.takeIf { it > 0L }
                                    ?: state.castingSong?.durationMs
                                    ?: state.remoteDurationMs

                                // Only mutate CastSessionState when values meaningfully change
                                if (state.isRemotePlaying != isPlaying ||
                                    kotlin.math.abs(state.remotePositionMs - pos) >= 500L ||
                                    state.remoteDurationMs != dur
                                ) {
                                    _sessionState.update {
                                        it.copy(
                                            isRemotePlaying = isPlaying,
                                            remotePositionMs = pos,
                                            remoteDurationMs = dur
                                        )
                                    }
                                }
                                onRemotePlaybackTick?.invoke(pos, isPlaying)

                                // Periodic 30s status keepalive prevents router NAT idle drop after 2-3 songs
                                val nowMs = SystemClock.elapsedRealtime()
                                if (nowMs - lastKeepAliveAtMs >= 30_000L) {
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
        castLoadJob?.cancel()
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
