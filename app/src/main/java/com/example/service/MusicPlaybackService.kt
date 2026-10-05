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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicPlaybackService : MediaSessionService() {

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

                    // Ensure large icon artwork and text are directly embedded into the notification using authoritative cache
                    try {
                        val currentSong = PlaybackManager.activeInstance?.playbackState?.value?.currentSong
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
                                val localUriStr = currentSong.artworkUri
                                    ?: com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(this@MusicPlaybackService, currentSong.id, currentSong.path)
                                    ?: com.example.ui.components.ArtworkResolutionCache.getMemory(currentSong.id)
                                    ?: com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(this@MusicPlaybackService, currentSong.id, currentSong.path)
                                if (!localUriStr.isNullOrBlank()) {
                                    try {
                                        bmp = com.example.util.ArtworkManager.decodeBitmapFromUri(this@MusicPlaybackService, localUriStr, 512)
                                        if (bmp != null) {
                                            com.example.util.ArtworkManager.putCachedNotificationBitmap(currentSong.id, bmp)
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }

                            if (bmp != null && !bmp.isRecycled) {
                                @Suppress("DEPRECATION")
                                mediaNotification.notification.largeIcon = bmp
                                val icon = android.graphics.drawable.Icon.createWithBitmap(bmp)
                                mediaNotification.notification.extras.putParcelable(android.app.Notification.EXTRA_LARGE_ICON, icon)
                                mediaNotification.notification.extras.putParcelable(android.app.Notification.EXTRA_LARGE_ICON_BIG, icon)
                                mediaNotification.notification.extras.putParcelable("androidx.core.app.NotificationCompat.LARGE_ICON", bmp)
                                mediaNotification.notification.extras.putParcelable(android.app.Notification.EXTRA_PICTURE, bmp)
                                mediaNotification.notification.extras.putParcelable(android.app.Notification.EXTRA_PICTURE_ICON, icon)
                                try {
                                    val field = android.app.Notification::class.java.getDeclaredField("mLargeIcon")
                                    field.isAccessible = true
                                    field.set(mediaNotification.notification, icon)
                                } catch (_: Throwable) {}
                            } else {
                                // Asynchronously decode and dispatch notification update without blocking
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
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
                                            withContext(kotlinx.coroutines.Dispatchers.Main) {
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
        session?.let { safeAddSession(it) }
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media controls and current playing song"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return activeMediaSession ?: PlaybackManager.activeInstance?.mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeMediaSession?.let { safeAddSession(it) }
        if (intent?.action == ACTION_HEADPHONE_CONNECTED) {
            val pm = PlaybackManager.activeInstance ?: PlaybackManager.getInstance(applicationContext)
            pm.handleHeadphoneConnectedFromBroadcast()
        }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val prefs = com.example.data.local.UserPreferencesRepository.getInstance(applicationContext)
            val isAutoPlayEnabled = prefs.cachedAutoPlayOnHeadphoneConnection
            val player = activeMediaSession?.player ?: PlaybackManager.activeInstance?.player
            val isPlaying = player?.isPlaying == true

            // Only stop service and dismiss notification if auto-play is not enabled and player is not actively playing
            if (!isAutoPlayEnabled && !isPlaying) {
                player?.pause()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancelAll()
                stopSelf()
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
            val shouldStartInForeground = startInForegroundRequired || isPlaying || hasActiveMedia
            if (isPlaying) {
                acquireWakeLock()
            } else {
                releaseWakeLock()
            }
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
                    var bmp = com.example.util.ArtworkManager.getCachedNotificationBitmap(currentSong.id)
                    if (bmp == null || bmp.isRecycled) {
                        val localUriStr = currentSong.artworkUri
                            ?: com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(this@MusicPlaybackService, currentSong.id, currentSong.path)
                            ?: com.example.ui.components.ArtworkResolutionCache.getMemory(currentSong.id)
                            ?: com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(this@MusicPlaybackService, currentSong.id, currentSong.path)
                        if (!localUriStr.isNullOrBlank()) {
                            try {
                                bmp = com.example.util.ArtworkManager.decodeBitmapFromUri(this@MusicPlaybackService, localUriStr, 512)
                                if (bmp != null) {
                                    com.example.util.ArtworkManager.putCachedNotificationBitmap(currentSong.id, bmp)
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    val meta = androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(currentSong.displayTitle)
                        .setArtist(currentSong.displayArtist)
                        .setAlbumTitle(currentSong.displayAlbum)
                        .apply {
                            if (bmp != null && !bmp.isRecycled) {
                                val stream = java.io.ByteArrayOutputStream()
                                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                                setArtworkData(stream.toByteArray(), androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            }
                            if (!currentSong.artworkUri.isNullOrBlank()) {
                                setArtworkUri(android.net.Uri.parse(currentSong.artworkUri))
                            }
                        }
                        .build()
                    session.player.playlistMetadata = meta
                }
                val hasActiveMedia = session.player.mediaItemCount > 0 || PlaybackManager.activeInstance?.playbackState?.value?.currentSong != null
                onUpdateNotification(session, hasActiveMedia)
                lastMediaNotification?.let { mn ->
                    val bmp = currentSong?.let { com.example.util.ArtworkManager.getCachedNotificationBitmap(it.id) }
                    if (bmp != null && !bmp.isRecycled) {
                        @Suppress("DEPRECATION")
                        mn.notification.largeIcon = bmp
                        val icon = android.graphics.drawable.Icon.createWithBitmap(bmp)
                        mn.notification.extras.putParcelable(android.app.Notification.EXTRA_LARGE_ICON, icon)
                        mn.notification.extras.putParcelable(android.app.Notification.EXTRA_LARGE_ICON_BIG, icon)
                        mn.notification.extras.putParcelable("androidx.core.app.NotificationCompat.LARGE_ICON", bmp)
                        mn.notification.extras.putParcelable(android.app.Notification.EXTRA_PICTURE, bmp)
                        mn.notification.extras.putParcelable(android.app.Notification.EXTRA_PICTURE_ICON, icon)
                        try {
                            val field = android.app.Notification::class.java.getDeclaredField("mLargeIcon")
                            field.isAccessible = true
                            field.set(mn.notification, icon)
                        } catch (_: Throwable) {}
                    }
                    try {
                        lastNotificationCallback?.onNotificationChanged(mn)
                    } catch (_: Throwable) {}
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    notificationManager?.notify(mn.notificationId, mn.notification)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
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
        const val CHANNEL_ID = "media_playback_channel"
        const val ACTION_HEADPHONE_CONNECTED = "com.example.service.ACTION_HEADPHONE_CONNECTED"
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
