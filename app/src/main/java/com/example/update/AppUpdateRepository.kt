package com.example.update

import android.content.Context
import com.example.BuildConfig
import com.example.data.local.UserPreferencesRepository
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Repository responsible for checking, caching, and handling GitHub releases and FCM updates.
 */
class AppUpdateRepository(
    private val context: Context,
    private val preferences: UserPreferencesRepository,
    private var httpClient: OkHttpClient = defaultHttpClient()
) {

    private val checkMutex = Mutex()

    private val _updateState = MutableStateFlow<UpdateCheckResult>(UpdateCheckResult.Idle)
    val updateState: StateFlow<UpdateCheckResult> = _updateState.asStateFlow()

    /**
     * Parses the installed app version directly from [BuildConfig.VERSION_NAME].
     * Does not pass [BuildConfig.VERSION_CODE] as fallbackBuildNumber because VERSION_CODE is a static
     * manifest integer (e.g. 5) rather than a GitHub CI run number (-build.X).
     */
    var installedVersionOverrideForTest: AppVersion? = null

    val installedVersion: AppVersion
        get() = installedVersionOverrideForTest ?: AppVersion.parse(BuildConfig.VERSION_NAME)

    init {
        // Ensure FCM topic subscription is synchronized on app startup without requiring user login
        syncTopicSubscription(preferences.getUpdateNotificationsEnabledSync())
    }

    internal fun setHttpClientForTesting(client: OkHttpClient) {
        this.httpClient = client
    }

    /**
     * Checks GitHub for the latest stable release.
     * When [isManual] is false, enforces a 4-hour cooldown to avoid redundant requests.
     */
    suspend fun checkForUpdates(isManual: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            val now = System.currentTimeMillis()
            val lastCheck = preferences.lastUpdateCheckTimestamp.first()
            val dismissedTag = preferences.dismissedUpdateTag.first()
            val currentInstalled = installedVersion

            // 1. Cooldown check for automatic checks
            if (!isManual && lastCheck > 0L && (now - lastCheck) < COOLDOWN_MS) {
                val cachedJson = preferences.cachedLatestReleaseJson.first()
                if (cachedJson.isNotBlank()) {
                    val cachedRelease = ReleaseInfo.fromJsonString(cachedJson)
                    if (cachedRelease != null && cachedRelease.version.isNewerThan(currentInstalled)) {
                        val isDismissed = cachedRelease.tagName.equals(dismissedTag, ignoreCase = true)
                        val result = UpdateCheckResult.UpdateAvailable(cachedRelease, isDismissed = isDismissed)
                        _updateState.value = result
                        return@withLock result
                    } else if (cachedRelease != null) {
                        val result = UpdateCheckResult.UpToDate(currentInstalled.displayString)
                        _updateState.value = result
                        return@withLock result
                    }
                }
            }

            _updateState.value = UpdateCheckResult.Checking

            val request = Request.Builder()
                .url(GITHUB_LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "OffGrid-Music-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val bodyString = response.body?.string().orEmpty()
                            val release = ReleaseInfo.fromJsonString(bodyString)
                            if (release == null) {
                                // Note: Do NOT update lastUpdateCheckTimestamp on invalid/draft/prerelease data
                                val err = UpdateCheckResult.Error("No valid stable release found")
                                _updateState.value = err
                                return@withLock err
                            }

                            // Persist verified stable release cache and successful check timestamp
                            preferences.setCachedLatestReleaseJson(bodyString)
                            preferences.setLastUpdateCheckTimestamp(now)

                            val isNewer = release.version.isNewerThan(currentInstalled)
                            // Manual checks explicitly requested by the user always show the dialog even if previously dismissed
                            val isDismissed = if (isManual) {
                                false
                            } else {
                                release.tagName.equals(dismissedTag, ignoreCase = true)
                            }

                            val result = if (isNewer) {
                                UpdateCheckResult.UpdateAvailable(release, isDismissed = isDismissed)
                            } else {
                                UpdateCheckResult.UpToDate(currentInstalled.displayString)
                            }
                            _updateState.value = result
                            result
                        }
                        304 -> {
                            preferences.setLastUpdateCheckTimestamp(now)
                            val cachedJson = preferences.cachedLatestReleaseJson.first()
                            val cachedRelease = ReleaseInfo.fromJsonString(cachedJson)
                            val result = if (cachedRelease != null && cachedRelease.version.isNewerThan(currentInstalled)) {
                                val isDismissed = if (isManual) false else cachedRelease.tagName.equals(dismissedTag, ignoreCase = true)
                                UpdateCheckResult.UpdateAvailable(cachedRelease, isDismissed = isDismissed)
                            } else {
                                UpdateCheckResult.UpToDate(currentInstalled.displayString)
                            }
                            _updateState.value = result
                            result
                        }
                        404 -> {
                            // Repository has no published stable releases yet
                            val upToDate = UpdateCheckResult.UpToDate(currentInstalled.displayString)
                            _updateState.value = upToDate
                            upToDate
                        }
                        403, 429 -> {
                            // Rate limit exceeded - do NOT overwrite lastUpdateCheckTimestamp
                            val rateLimited = UpdateCheckResult.RateLimited("API rate limit exceeded. Please try again later.")
                            _updateState.value = rateLimited
                            rateLimited
                        }
                        else -> {
                            val err = UpdateCheckResult.Error("GitHub response: ${response.code}")
                            _updateState.value = err
                            err
                        }
                    }
                }
            } catch (_: IOException) {
                val offline = UpdateCheckResult.Offline("No network connection")
                _updateState.value = offline
                offline
            } catch (e: Exception) {
                val err = UpdateCheckResult.Error(e.message ?: "Check failed")
                _updateState.value = err
                err
            }
        }
    }

    /**
     * Re-opens the update dialog if an update is already known to be available, or runs a manual check.
     */
    suspend fun reopenOrCheckForUpdates(): UpdateCheckResult {
        val current = _updateState.value
        if (current is UpdateCheckResult.UpdateAvailable) {
            val undismissed = current.copy(isDismissed = false)
            _updateState.value = undismissed
            return undismissed
        }
        return checkForUpdates(isManual = true)
    }

    /**
     * Ingests release metadata received from an FCM push notification or notification tap intent,
     * ensuring consistent state between FCM notifications and the in-app update dialog without duplicates.
     */
    fun onRemoteReleaseNotification(
        tagName: String,
        title: String,
        notes: String,
        releaseUrl: String,
        apkUrl: String? = null,
        forceShowDialog: Boolean = false
    ): Boolean {
        val resolvedVersion = ReleaseInfo.resolveVersion(tagName, title, null, notes)
        if (!resolvedVersion.isValid || resolvedVersion.isPreRelease) return false
        if (!resolvedVersion.isNewerThan(installedVersion)) return false

        val normalizedTag = if (tagName.isBlank() || tagName.equals("latest", ignoreCase = true)) {
            if (resolvedVersion.buildNumber > 0L) {
                "v${resolvedVersion.major}.${resolvedVersion.minor}.${resolvedVersion.patch}-build.${resolvedVersion.buildNumber}"
            } else {
                "v${resolvedVersion.major}.${resolvedVersion.minor}.${resolvedVersion.patch}"
            }
        } else {
            tagName.trim()
        }

        val dismissedTag = preferences.getDismissedUpdateTagSync()
        val isDismissed = if (forceShowDialog) {
            false
        } else {
            normalizedTag.equals(dismissedTag, ignoreCase = true)
        }

        val current = _updateState.value
        if (current is UpdateCheckResult.UpdateAvailable &&
            current.release.tagName.equals(normalizedTag, ignoreCase = true)
        ) {
            if (forceShowDialog && current.isDismissed) {
                _updateState.value = current.copy(isDismissed = false)
            }
            return true
        }

        val safeUrl = releaseUrl.takeIf { it.startsWith("https://github.com/") }
            ?: "$GITHUB_REPO_URL/releases/tag/$normalizedTag"

        val releaseInfo = ReleaseInfo(
            tagName = normalizedTag,
            name = title.ifBlank { "OffGrid Music $normalizedTag" },
            body = notes.ifBlank { "• Stability improvements and new features." },
            publishedAt = "",
            htmlUrl = safeUrl,
            apkDownloadUrl = apkUrl?.takeIf { it.isNotBlank() },
            version = resolvedVersion
        )

        _updateState.value = UpdateCheckResult.UpdateAvailable(
            release = releaseInfo,
            isDismissed = isDismissed
        )
        return true
    }

    /**
     * Marks the given release tag as dismissed so it won't auto-popup on the Home screen.
     * Newer release tags will not match [tagName] and will still be shown automatically.
     */
    suspend fun dismissRelease(tagName: String) {
        preferences.setDismissedUpdateTag(tagName)
        val current = _updateState.value
        if (current is UpdateCheckResult.UpdateAvailable &&
            current.release.tagName.equals(tagName, ignoreCase = true)
        ) {
            _updateState.value = current.copy(isDismissed = true)
        }
    }

    /**
     * Synchronizes FCM topic subscription for app updates with user preferences.
     * Does not require user authentication.
     */
    fun syncTopicSubscription(enabled: Boolean) {
        try {
            val messaging = FirebaseMessaging.getInstance()
            if (enabled) {
                messaging.subscribeToTopic(UPDATE_TOPIC)
            } else {
                messaging.unsubscribeFromTopic(UPDATE_TOPIC)
            }
        } catch (_: Throwable) {
            // Gracefully ignore if Firebase is uninitialized or Google Play Services is unavailable
        }
    }

    companion object {
        const val GITHUB_REPO_URL = "https://github.com/Paul00755/OffGrid-Music"
        const val GITHUB_RELEASES_URL = "https://github.com/Paul00755/OffGrid-Music/releases"
        const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/Paul00755/OffGrid-Music/releases/latest"
        const val UPDATE_TOPIC = "offgrid_updates"
        const val COOLDOWN_MS = 4 * 3600 * 1000L // 4 hours

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        @Volatile
        private var INSTANCE: AppUpdateRepository? = null

        fun getInstance(context: Context): AppUpdateRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppUpdateRepository(
                    context.applicationContext,
                    UserPreferencesRepository.getInstance(context)
                ).also { INSTANCE = it }
            }
        }

        internal fun resetInstanceForTesting() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
}

