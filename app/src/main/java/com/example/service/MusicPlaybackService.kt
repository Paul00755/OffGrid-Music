package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicPlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var asyncArtDecodeJob: Job? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        try {
            val defaultProvider = DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(com.example.R.string.app_name)
                .build()

            // Custom MediaNotification provider that enforces ongoing notification so it cannot be dismissed on lockscreen while active in RAM
            val persistentNotificationProvider = object : MediaNotification.Provider {
                override fun createNotification(
                    mediaSession: MediaSession,
                    customLayout: ImmutableList<CommandButton>,
                    actionFactory: MediaNotification.ActionFactory,
                    onNotificationChangedCallback: MediaNotification.Provider.Callback
                ): MediaNotification {
                    lastNotificationCallback = onNotificationChangedCallback
                    val mediaNotification = defaultProvider.createNotification(
                        mediaSession,
                        customLayout,
                        actionFactory,
                        onNotificationChangedCallback
                    )
                    // Mark as ongoing event and no-clear so lockscreen or status bar swiping does not dismiss it while app is alive
                    mediaNotification.notification.flags = mediaNotification.notification.flags or
                            Notification.FLAG_ONGOING_EVENT or
                            Notification.FLAG_NO_CLEAR
                    mediaNotification.notification.visibility = Notification.VISIBILITY_PUBLIC

                    // Ensure large icon artwork and text are directly embedded into the notification using authoritative cache
                    try {
                        val currentSong = PlaybackManager.activeInstance?.playbackState?.value?.currentSong
                            ?: mediaSession.player.currentMediaItem?.let { mi ->
                                val id = mi.mediaId.toLongOrNull() ?: 0L
                                PlaybackManager.activeInstance?.playbackState?.value?.queue?.firstOrNull { it.id == id }
                            }
                        if (currentSong != null) {
                            try {
                                mediaNotification.notification.extras.putCharSequence(android.app.Notification.EXTRA_TITLE, currentSong.displayTitle)
                                mediaNotification.notification.extras.putCharSequence(android.app.Notification.EXTRA_TEXT, currentSong.displayArtist)
                                mediaNotification.notification.extras.putCharSequence(android.app.Notification.EXTRA_SUB_TEXT, currentSong.displayAlbum)
                                mediaNotification.notification.tickerText = "${currentSong.displayTitle} - ${currentSong.displayArtist}"
                            } catch (_: Throwable) {}

                            var bmp = com.example.util.ArtworkManager.getCachedNotificationBitmap(currentSong.id)
                            if (bmp == null || bmp.isRecycled) {
                                val artBytes = com.example.util.ArtworkManager.getCachedNotificationArtwork(currentSong.id)
                                if (artBytes != null && artBytes.isNotEmpty()) {
                                    bmp = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                                    if (bmp != null) {
                                        com.example.util.ArtworkManager.putCachedNotificationBitmap(currentSong.id, bmp)
                                    }
                                }
                            }
                            if (bmp == null || bmp.isRecycled) {
                                val memCached = com.example.ui.components.ArtworkResolutionCache.getMemory(currentSong.id)
                                if (memCached != null) {
                                    bmp = com.example.util.ArtworkManager.getCachedNotificationBitmap(currentSong.id)
                                }
                            }

                            if (bmp != null && !bmp.isRecycled) {
                                @Suppress("DEPRECATION")
                                mediaNotification.notification.largeIcon = bmp
                            } else {
                                // Asynchronously decode and dispatch notification update without blocking
                                asyncArtDecodeJob?.cancel()
                                asyncArtDecodeJob = serviceScope.launch(Dispatchers.IO) {
                                    val loadedBytes = com.example.util.ArtworkManager.getNotificationArtworkBytes(
                                        this@MusicPlaybackService,
                                        currentSong.id,
                                        currentSong.path,
                                        currentSong.albumId,
                                        currentSong.artworkUri
                                    )
                                    if (loadedBytes != null && loadedBytes.isNotEmpty()) {
                                        val newBmp = android.graphics.BitmapFactory.decodeByteArray(loadedBytes, 0, loadedBytes.size)
                                        if (newBmp != null) {
                                            com.example.util.ArtworkManager.putCachedNotificationBitmap(currentSong.id, newBmp)
                                            withContext(Dispatchers.Main) {
                                                if (PlaybackManager.activeInstance?.playbackState?.value?.currentSong?.id == currentSong.id) {
                                                    triggerNotificationUpdate()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            mediaNotification.notification.largeIcon = null
                        }
                    } catch (_: Throwable) {
                        try {
                            @Suppress("DEPRECATION")
                            mediaNotification.notification.largeIcon = null
                        } catch (_: Throwable) {}
                    }

                    lastMediaNotification = mediaNotification
                    return mediaNotification
                }

                override fun handleCustomCommand(
                    session: MediaSession,
                    action: String,
                    extras: Bundle
                ): Boolean {
                    return defaultProvider.handleCustomCommand(session, action, extras)
                }
            }

            setMediaNotificationProvider(persistentNotificationProvider)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val session = activeMediaSession ?: PlaybackManager.activeInstance?.mediaSession
            ?: PlaybackManager.getInstance(applicationContext).mediaSession
        safeAddSession(session)
    }

    fun safeAddSession(session: MediaSession?) {
        if (session == null) return
        try {
            if (!sessions.contains(session)) {
                addSession(session)
            }
        } catch (_: Throwable) {}
    }

    fun safeRemoveSession(session: MediaSession?) {
        if (session == null) return
        try {
            if (sessions.contains(session)) {
                removeSession(session)
            }
        } catch (_: Throwable) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                notificationManager.deleteNotificationChannel("media_playback_channel")
            } catch (_: Throwable) {}
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback Controls",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Media controls and current playing song"
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val session = activeMediaSession ?: PlaybackManager.activeInstance?.mediaSession
            ?: PlaybackManager.getInstance(applicationContext).mediaSession
        safeAddSession(session)
        return session
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = activeMediaSession ?: PlaybackManager.activeInstance?.mediaSession
            ?: PlaybackManager.getInstance(applicationContext).mediaSession
        safeAddSession(session)
        if (intent?.action == ACTION_HEADPHONE_CONNECTED) {
            val pm = PlaybackManager.activeInstance ?: PlaybackManager.getInstance(applicationContext)
            pm.handleHeadphoneConnectedFromBroadcast()
        }
        val result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_ENSURE_BACKGROUND_NOTIFICATION || session != null) {
            triggerNotificationUpdate()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val player = activeMediaSession?.player ?: PlaybackManager.activeInstance?.player
            val isPlaying = player?.isPlaying == true
            val hasActiveSong = PlaybackManager.activeInstance?.playbackState?.value?.currentSong != null || (player != null && player.mediaItemCount > 0)

            // Keep notification persistent in mobile status bar even if swiped from recents so user can resume anytime.
            // Only stop service and dismiss notification if there is no active song loaded at all.
            if (!isPlaying && !hasActiveSong) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancelAll()
                stopSelf()
            } else {
                triggerNotificationUpdate()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        try {
            val player = session.player
            val isPlaying = player.isPlaying || player.playWhenReady
            val hasActiveMedia = player.mediaItemCount > 0 || PlaybackManager.activeInstance?.playbackState?.value?.currentSong != null
            val shouldHoldWakeLock = isPlaying || (player.playWhenReady && hasActiveMedia)
            if (shouldHoldWakeLock) {
                acquireWakeLock()
            } else {
                releaseWakeLock()
            }
            // Keep service in foreground with active status bar notification whenever media is loaded
            // so the notification stays active in the status bar while the app is in the background.
            val shouldStartInForeground = startInForegroundRequired || isPlaying || hasActiveMedia
            super.onUpdateNotification(session, shouldStartInForeground)
        } catch (e: Throwable) {
            try {
                super.onUpdateNotification(session, startInForegroundRequired)
            } catch (_: Throwable) {}
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "OffGridMusic:PlaybackServiceWakeLock")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(3600_000L)
            }
        } catch (_: Throwable) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {}
    }

    fun triggerNotificationUpdate() {
        val session = activeMediaSession ?: PlaybackManager.activeInstance?.mediaSession
        if (session != null) {
            try {
                val currentSong = PlaybackManager.activeInstance?.playbackState?.value?.currentSong
                if (currentSong != null) {
                    val bmp = com.example.util.ArtworkManager.getCachedNotificationBitmap(currentSong.id)
                    if (bmp == null || bmp.isRecycled) {
                        asyncArtDecodeJob?.cancel()
                        asyncArtDecodeJob = serviceScope.launch(Dispatchers.IO) {
                            val loadedBytes = com.example.util.ArtworkManager.getNotificationArtworkBytes(
                                this@MusicPlaybackService,
                                currentSong.id,
                                currentSong.path,
                                currentSong.albumId,
                                currentSong.artworkUri
                            )
                            if (loadedBytes != null && loadedBytes.isNotEmpty()) {
                                val newBmp = android.graphics.BitmapFactory.decodeByteArray(loadedBytes, 0, loadedBytes.size)
                                if (newBmp != null) {
                                    com.example.util.ArtworkManager.putCachedNotificationBitmap(currentSong.id, newBmp)
                                    withContext(Dispatchers.Main) {
                                        if (PlaybackManager.activeInstance?.playbackState?.value?.currentSong?.id == currentSong.id) {
                                            triggerNotificationUpdate()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val hasActiveMedia = session.player.mediaItemCount > 0 || PlaybackManager.activeInstance?.playbackState?.value?.currentSong != null
                onUpdateNotification(session, hasActiveMedia)
                lastMediaNotification?.let { mn ->
                    mn.notification.flags = mn.notification.flags or
                            Notification.FLAG_ONGOING_EVENT or
                            Notification.FLAG_NO_CLEAR
                    mn.notification.visibility = Notification.VISIBILITY_PUBLIC
                    val bmp = currentSong?.let { com.example.util.ArtworkManager.getCachedNotificationBitmap(it.id) }
                    if (bmp != null && !bmp.isRecycled) {
                        @Suppress("DEPRECATION")
                        mn.notification.largeIcon = bmp
                    }
                    try {
                        lastNotificationCallback?.onNotificationChanged(mn)
                    } catch (_: Throwable) {}
                    try {
                        if (hasActiveMedia) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startForeground(
                                    mn.notificationId,
                                    mn.notification,
                                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                                )
                            } else {
                                startForeground(mn.notificationId, mn.notification)
                            }
                        }
                    } catch (_: Throwable) {}
                    try {
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        notificationManager?.notify(mn.notificationId, mn.notification)
                    } catch (_: Throwable) {}
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        asyncArtDecodeJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        activeMediaSession?.let { safeRemoveSession(it) }
        try {
            sessions.toList().forEach { s ->
                try { removeSession(s) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        instance = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "media_playback_channel_v2"
        const val ACTION_HEADPHONE_CONNECTED = "com.example.service.ACTION_HEADPHONE_CONNECTED"
        const val ACTION_ENSURE_BACKGROUND_NOTIFICATION = "com.example.service.ACTION_ENSURE_BACKGROUND_NOTIFICATION"
        var instance: MusicPlaybackService? = null
        var lastNotificationCallback: MediaNotification.Provider.Callback? = null
        var lastMediaNotification: MediaNotification? = null
        var activeMediaSession: MediaSession? = null
            set(value) {
                val old = field
                field = value
                if (old != value) {
                    old?.let { instance?.safeRemoveSession(it) }
                    value?.let { instance?.safeAddSession(it) }
                }
            }
    }
}
