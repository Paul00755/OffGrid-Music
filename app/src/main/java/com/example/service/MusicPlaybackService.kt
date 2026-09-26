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

                    // Ensure large icon artwork is directly embedded into the notification using memory cache
                    try {
                        val currentSong = PlaybackManager.activeInstance?.playbackState?.value?.currentSong
                        if (currentSong != null) {
                            val cachedBmp = com.example.util.ArtworkManager.getCachedNotificationBitmap(currentSong.id)
                            if (cachedBmp != null && !cachedBmp.isRecycled) {
                                @Suppress("DEPRECATION")
                                mediaNotification.notification.largeIcon = cachedBmp
                            } else {
                                val artBytes = com.example.util.ArtworkManager.getCachedNotificationArtwork(currentSong.id)
                                if (artBytes != null && artBytes.isNotEmpty()) {
                                    val bmp = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                                    if (bmp != null) {
                                        com.example.util.ArtworkManager.putCachedNotificationBitmap(currentSong.id, bmp)
                                        @Suppress("DEPRECATION")
                                        mediaNotification.notification.largeIcon = bmp
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {}

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
        session?.let { addSession(it) }
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
        activeMediaSession?.let { addSession(it) }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            // When app is swiped away and cleared from Recent Apps:
            // Vanish notification and cleanly stop foreground service
            val player = activeMediaSession?.player ?: PlaybackManager.activeInstance?.player
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
            super.onUpdateNotification(session, shouldStartInForeground)
        } catch (e: Throwable) {
            try {
                super.onUpdateNotification(session, startInForegroundRequired)
            } catch (_: Throwable) {}
        }
    }

    fun triggerNotificationUpdate() {
        val session = activeMediaSession ?: PlaybackManager.activeInstance?.mediaSession
        if (session != null) {
            try {
                val hasActiveMedia = session.player.mediaItemCount > 0 || PlaybackManager.activeInstance?.playbackState?.value?.currentSong != null
                onUpdateNotification(session, hasActiveMedia)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        activeMediaSession?.let { removeSession(it) }
        instance = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "media_playback_channel"
        var instance: MusicPlaybackService? = null
        var lastNotificationCallback: MediaNotification.Provider.Callback? = null
        var lastMediaNotification: MediaNotification? = null
        var activeMediaSession: MediaSession? = null
            set(value) {
                val old = field
                field = value
                if (old != value) {
                    old?.let { instance?.removeSession(it) }
                    value?.let { instance?.addSession(it) }
                }
            }
    }
}
