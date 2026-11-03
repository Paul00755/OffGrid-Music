package com.example.service.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.UserPreferencesRepository
import com.example.update.AppUpdateRepository
import com.example.update.ReleaseInfo
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
        processIncomingUpdatePayload(
            context = applicationContext,
            data = remoteMessage.data,
            notificationTitle = remoteMessage.notification?.title,
            notificationBody = remoteMessage.notification?.body
        )
    }

    companion object {
        const val CHANNEL_ID = "offgrid_app_updates"
        const val NOTIFICATION_ID = 9527
        const val EXTRA_OPEN_UPDATE_DIALOG = "com.example.EXTRA_OPEN_UPDATE_DIALOG"
        const val EXTRA_UPDATE_RELEASE_TAG = "com.example.EXTRA_UPDATE_RELEASE_TAG"
        const val EXTRA_UPDATE_RELEASE_URL = "com.example.EXTRA_UPDATE_RELEASE_URL"
        const val EXTRA_UPDATE_RELEASE_TITLE = "com.example.EXTRA_UPDATE_RELEASE_TITLE"
        const val EXTRA_UPDATE_RELEASE_NOTES = "com.example.EXTRA_UPDATE_RELEASE_NOTES"
        const val EXTRA_UPDATE_APK_URL = "com.example.EXTRA_UPDATE_APK_URL"

        /**
         * Processes an incoming FCM payload (testable directly in Robolectric without Play Services).
         * Returns true if a system notification was posted, false if suppressed/filtered.
         */
        fun processIncomingUpdatePayload(
            context: Context,
            data: Map<String, String>,
            notificationTitle: String? = null,
            notificationBody: String? = null
        ): Boolean {
            val appContext = context.applicationContext
            val prefs = UserPreferencesRepository.getInstance(appContext)
            if (!prefs.getUpdateNotificationsEnabledSync()) {
                return false
            }

            val rawTag = data["release_tag"]
                ?: data["tag"]
                ?: data["version"]
                ?: notificationTitle?.let { extractVersion(it) }
                ?: ""

            val rawTitle = data["title"]
                ?: notificationTitle
                ?: if (rawTag.isNotBlank()) "OffGrid Music $rawTag Available" else "OffGrid Music Update Available"

            val rawBody = data["body"]
                ?: data["release_notes"]
                ?: notificationBody
                ?: "A new stable version of OffGrid Music is available."

            val sanitizedBody = ReleaseInfo.sanitizeReleaseNotes(rawBody)

            val resolvedVersion = ReleaseInfo.resolveVersion(rawTag, rawTitle, data["apk_url"], rawBody)
            if (!resolvedVersion.isValid || resolvedVersion.isPreRelease) {
                return false
            }

            val repo = AppUpdateRepository.getInstance(appContext)
            if (!resolvedVersion.isNewerThan(repo.installedVersion)) {
                return false
            }

            val effectiveTag = if (rawTag.isBlank() || rawTag.equals("latest", ignoreCase = true)) {
                if (resolvedVersion.buildNumber > 0L) {
                    "v${resolvedVersion.major}.${resolvedVersion.minor}.${resolvedVersion.patch}-build.${resolvedVersion.buildNumber}"
                } else {
                    "v${resolvedVersion.major}.${resolvedVersion.minor}.${resolvedVersion.patch}"
                }
            } else {
                rawTag.trim()
            }

            val releaseUrl = (data["release_url"] ?: data["url"])
                ?.takeIf { it.isNotBlank() && it.startsWith("https://github.com/") }
                ?: "${AppUpdateRepository.GITHUB_REPO_URL}/releases/tag/$effectiveTag"

            val apkUrl = (data["apk_url"] ?: data["download_url"])?.takeIf { it.isNotBlank() }

            // Synchronize repository state so Home screen / Settings immediately reflect the new release
            repo.onRemoteReleaseNotification(
                tagName = effectiveTag,
                title = rawTitle,
                notes = sanitizedBody,
                releaseUrl = releaseUrl,
                apkUrl = apkUrl,
                forceShowDialog = false
            )

            // Do not spam a notification if the user already dismissed or was already notified of this exact tag
            val dismissedTag = prefs.getDismissedUpdateTagSync()
            val lastNotifiedTag = prefs.getLastNotifiedUpdateTagSync()
            if (effectiveTag.equals(dismissedTag, ignoreCase = true) ||
                effectiveTag.equals(lastNotifiedTag, ignoreCase = true)
            ) {
                return false
            }

            // If the app is currently in the foreground and already displaying the update dialog for this tag,
            // avoid posting a redundant notification at the exact same moment.
            val currentState = repo.updateState.value
            if (MainActivity.isActivityAlive &&
                currentState is com.example.update.UpdateCheckResult.UpdateAvailable &&
                !currentState.isDismissed &&
                currentState.release.tagName.equals(effectiveTag, ignoreCase = true)
            ) {
                prefs.setLastNotifiedUpdateTagSync(effectiveTag)
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPerm = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPerm) {
                    return false
                }
            }

            showUpdateNotification(
                context = appContext,
                title = rawTitle,
                body = sanitizedBody,
                releaseTag = effectiveTag,
                releaseUrl = releaseUrl,
                apkUrl = apkUrl
            )
            prefs.setLastNotifiedUpdateTagSync(effectiveTag)
            return true
        }

        private fun showUpdateNotification(
            context: Context,
            title: String,
            body: String,
            releaseTag: String,
            releaseUrl: String,
            apkUrl: String?
        ) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannel(notificationManager)

            val contentIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_UPDATE_DIALOG, true)
                putExtra(EXTRA_UPDATE_RELEASE_TAG, releaseTag)
                putExtra(EXTRA_UPDATE_RELEASE_URL, releaseUrl)
                putExtra(EXTRA_UPDATE_RELEASE_TITLE, title)
                putExtra(EXTRA_UPDATE_RELEASE_NOTES, body)
                if (!apkUrl.isNullOrBlank()) {
                    putExtra(EXTRA_UPDATE_APK_URL, apkUrl)
                }
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val browserPendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID + 1,
                browserIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body.lines().firstOrNull() ?: body)
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

        internal fun extractVersion(text: String): String {
            val regex = Regex("(?i)v?\\d+\\.\\d+(\\.\\d+)?(-build\\.\\d+)?")
            return regex.find(text)?.value.orEmpty()
        }
    }
}

