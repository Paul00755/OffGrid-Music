package com.example.service.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.UserPreferencesRepository
import com.example.update.AppUpdateRepository
import com.example.update.AppVersion
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Handles Firebase Cloud Messaging release announcements for OffGrid Music.
 */
class OffGridFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = UserPreferencesRepository.getInstance(applicationContext)
        val enabled = prefs.getUpdateNotificationsEnabledSync()
        AppUpdateRepository.getInstance(applicationContext).syncTopicSubscription(enabled)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val prefs = UserPreferencesRepository.getInstance(applicationContext)
        if (!prefs.getUpdateNotificationsEnabledSync()) {
            return
        }

        val data = remoteMessage.data
        val versionStr = data["version"] ?: remoteMessage.notification?.title?.let { extractVersion(it) } ?: ""
        val title = data["title"] ?: remoteMessage.notification?.title ?: "OffGrid Music Update Available 🚀"
        val body = data["body"] ?: remoteMessage.notification?.body ?: "A new stable version of OffGrid Music is available."
        val releaseUrl = data["url"] ?: AppUpdateRepository.GITHUB_REPO_URL

        // Verify version is newer than currently installed build
        if (versionStr.isNotBlank()) {
            val incomingVersion = AppVersion.parse(versionStr)
            val installedVersion = AppUpdateRepository.getInstance(applicationContext).installedVersion
            if (!incomingVersion.isNewerThan(installedVersion)) {
                return
            }
        }

        showUpdateNotification(title, body, versionStr, releaseUrl)
    }

    private fun showUpdateNotification(
        title: String,
        body: String,
        versionStr: String,
        releaseUrl: String
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(notificationManager)

        // Intent to launch app and display in-app update dialog
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_UPDATE_DIALOG, true)
            putExtra(EXTRA_UPDATE_RELEASE_TAG, versionStr)
            putExtra(EXTRA_UPDATE_RELEASE_URL, releaseUrl)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action intent to directly open GitHub release page in browser
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val browserPendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID + 1,
            browserIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_notification, "Update Now", browserPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for new stable OffGrid Music updates and release notes"
                setShowBadge(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun extractVersion(text: String): String {
        val regex = Regex("(?i)v?\\d+\\.\\d+(\\.\\d+)?(-build\\.\\d+)?")
        return regex.find(text)?.value.orEmpty()
    }

    companion object {
        const val CHANNEL_ID = "offgrid_app_updates"
        const val NOTIFICATION_ID = 9527
        const val EXTRA_OPEN_UPDATE_DIALOG = "com.example.EXTRA_OPEN_UPDATE_DIALOG"
        const val EXTRA_UPDATE_RELEASE_TAG = "com.example.EXTRA_UPDATE_RELEASE_TAG"
        const val EXTRA_UPDATE_RELEASE_URL = "com.example.EXTRA_UPDATE_RELEASE_URL"
    }
}
