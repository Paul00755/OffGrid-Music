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
        val data = remoteMessage.data
        if (data["kind"].orEmpty().startsWith("jam_")) {
            val recipientUid = data["recipientUid"]?.takeIf(String::isNotBlank) ?: return
            val signedInUid = com.example.util.FirebaseInitializer.getAuth(applicationContext)?.currentUser?.uid
            if (signedInUid != null && recipientUid != signedInUid) return
            val sessionId = data["sessionId"].orEmpty()
            val recommendationId = data["recommendationId"].orEmpty()
            if (sessionId.isNotBlank() && recommendationId.isNotBlank() &&
                !MainActivity.deliverForegroundJamRecommendation(sessionId, recommendationId)) {
                val isCurrentAccount = signedInUid == recipientUid
                showJamRecommendationNotification(
                    context = applicationContext,
                    title = if (isCurrentAccount) {
                        remoteMessage.notification?.title ?: data["title"] ?: "OffGrid Jam recommendation"
                    } else "Sign in to view your Jam",
                    body = if (isCurrentAccount) {
                        remoteMessage.notification?.body ?: data["body"] ?: "Open OffGrid Jam to see the latest update."
                    } else "Sign in to OffGrid Music to review the pending recommendation.",
                    sessionId = sessionId,
                    recommendationId = recommendationId,
                    kind = data["kind"].orEmpty(),
                    recipientUid = recipientUid
                )
            }
            return
        }
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
        private const val JAM_CHANNEL_ID = "offgrid_jam_recommendations"
        private const val JAM_NOTIFICATION_TAG = "offgrid_jam_recommendations"
        private const val LEGACY_JAM_NOTIFICATION_ID = 9530
        const val EXTRA_OPEN_UPDATE_DIALOG = "com.example.EXTRA_OPEN_UPDATE_DIALOG"
        const val EXTRA_UPDATE_RELEASE_TAG = "com.example.EXTRA_UPDATE_RELEASE_TAG"
        const val EXTRA_UPDATE_RELEASE_URL = "com.example.EXTRA_UPDATE_RELEASE_URL"
        const val EXTRA_UPDATE_RELEASE_TITLE = "com.example.EXTRA_UPDATE_RELEASE_TITLE"
        const val EXTRA_UPDATE_RELEASE_NOTES = "com.example.EXTRA_UPDATE_RELEASE_NOTES"
        const val EXTRA_UPDATE_APK_URL = "com.example.EXTRA_UPDATE_APK_URL"

        fun showJamRecommendationNotification(
            context: Context,
            title: String,
            body: String,
            sessionId: String,
            recommendationId: String,
            kind: String,
            recipientUid: String
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel(
                    JAM_CHANNEL_ID,
                    "Jam recommendations",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Votes and song sharing updates for OffGrid Jam" })
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("kind", kind)
                putExtra("sessionId", sessionId)
                putExtra("recommendationId", recommendationId)
                putExtra("recipientUid", recipientUid)
            }
            val pending = PendingIntent.getActivity(
                context,
                (sessionId + recommendationId).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val largeLogo = try {
                android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.app_logo)
            } catch (_: Throwable) { null }
            val notificationId = (sessionId + recommendationId).hashCode()
            manager.notify(JAM_NOTIFICATION_TAG, notificationId, NotificationCompat.Builder(context, JAM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .apply { if (largeLogo != null) setLargeIcon(largeLogo) }
                .setColor(0xFF7C4DFF.toInt())
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build())
        }

        fun cancelJamNotification(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(LEGACY_JAM_NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.activeNotifications
                    .filter { it.tag == JAM_NOTIFICATION_TAG }
                    .forEach { manager.cancel(it.tag, it.id) }
            }
        }

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

            val rawTitle = data["release_name"]?.takeIf { it.isNotBlank() }
                ?: data["title"]?.takeIf { it.isNotBlank() && !it.equals("Update available", ignoreCase = true) }
                ?: notificationTitle
                ?: if (rawTag.isNotBlank()) "OffGrid Music $rawTag" else "OffGrid Music Update Available"

            val rawBody = data["release_notes"]?.takeIf { it.isNotBlank() }
                ?: data["body"]
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

            val apkUrl = AppUpdateRepository.LATEST_APK_DOWNLOAD_URL

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
                releaseTitle = rawTitle,
                releaseNotes = sanitizedBody,
                releaseTag = effectiveTag,
                releaseUrl = releaseUrl,
                apkUrl = apkUrl
            )
            prefs.setLastNotifiedUpdateTagSync(effectiveTag)
            return true
        }

        /**
         * Posts the clean system update reminder notification ("Update available" / "vX.Y.Z")
         * whenever the user opens the app while running an older version — even if they previously
         * clicked "Later" / "Not now" on the in-app dialog. Respects user toggle & POST_NOTIFICATIONS.
         */
        fun showAppOpenUpdateReminderNotification(
            context: Context,
            release: ReleaseInfo
        ): Boolean {
            val appContext = context.applicationContext
            val prefs = UserPreferencesRepository.getInstance(appContext)
            if (!prefs.getUpdateNotificationsEnabledSync()) {
                return false
            }
            val repo = AppUpdateRepository.getInstance(appContext)
            if (!release.version.isValid || release.version.isPreRelease || !release.version.isNewerThan(repo.installedVersion)) {
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
                releaseTitle = release.name,
                releaseNotes = release.body,
                releaseTag = release.tagName,
                releaseUrl = release.htmlUrl,
                apkUrl = release.apkDownloadUrl
            )
            return true
        }

        fun ensureNotificationChannel(context: Context) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            createChannel(notificationManager)
        }

        private fun showUpdateNotification(
            context: Context,
            releaseTitle: String,
            releaseNotes: String,
            releaseTag: String,
            releaseUrl: String,
            apkUrl: String?
        ) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannel(notificationManager)

            val cleanVersionTag = formatCleanVersionLabel(releaseTag)

            // Tapping the update notification redirects directly to the working latest OffGrid-Music.apk link
            val directDownloadIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(AppUpdateRepository.LATEST_APK_DOWNLOAD_URL)
            ).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                directDownloadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val largeLogo = try {
                android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.app_logo)
            } catch (_: Throwable) { null }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .apply { if (largeLogo != null) setLargeIcon(largeLogo) }
                .setColor(0xFF7C4DFF.toInt())
                .setContentTitle("Update available")
                .setContentText(cleanVersionTag)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setContentIntent(contentPendingIntent)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        }

        internal fun formatCleanVersionLabel(releaseTag: String): String {
            val trimmed = releaseTag.trim()
            if (trimmed.isBlank()) return "New version"
            val core = trimmed.substringBefore("-build.").trim()
            return if (core.startsWith("v", ignoreCase = true)) {
                "v${core.drop(1)}"
            } else {
                "v$core"
            }
        }

        private fun createChannel(manager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "App Updates",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications when a new OffGrid Music version is available"
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

