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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Repository responsible for checking, caching, and handling GitHub releases and FCM updates.
 */
class AppUpdateRepository(
    private val context: Context,
    private val preferences: UserPreferencesRepository
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val _updateState = MutableStateFlow<UpdateCheckResult>(UpdateCheckResult.Idle)
    val updateState: StateFlow<UpdateCheckResult> = _updateState.asStateFlow()

    val installedVersion: AppVersion by lazy {
        AppVersion.parse(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
    }

    /**
     * Checks GitHub for the latest stable release.
     * When [isManual] is false, enforces a 4-hour cooldown to avoid redundant requests.
     */
    suspend fun checkForUpdates(isManual: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastCheck = preferences.lastUpdateCheckTimestamp.first()
        val dismissedTag = preferences.dismissedUpdateTag.first()

        // 1. Cooldown check for automatic checks
        if (!isManual && (now - lastCheck) < COOLDOWN_MS) {
            val cachedJson = preferences.cachedLatestReleaseJson.first()
            if (cachedJson.isNotBlank()) {
                val cachedRelease = parseReleaseJson(cachedJson)
                if (cachedRelease != null && cachedRelease.version.isNewerThan(installedVersion)) {
                    val isDismissed = cachedRelease.tagName.equals(dismissedTag, ignoreCase = true)
                    val result = UpdateCheckResult.UpdateAvailable(cachedRelease, isDismissed = isDismissed)
                    _updateState.value = result
                    return@withContext result
                } else if (cachedRelease != null) {
                    val result = UpdateCheckResult.UpToDate(installedVersion.displayString)
                    _updateState.value = result
                    return@withContext result
                }
            }
        }

        _updateState.value = UpdateCheckResult.Checking

        val request = Request.Builder()
            .url(GITHUB_LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "OffGrid-Music-Android")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val bodyString = response.body?.string().orEmpty()
                        val release = parseReleaseJson(bodyString)
                        if (release == null) {
                            val err = UpdateCheckResult.Error("Invalid release data")
                            _updateState.value = err
                            return@withContext err
                        }

                        // Ignore draft or prerelease builds for standard updates
                        if (release.isDraft || release.isPrerelease) {
                            val upToDate = UpdateCheckResult.UpToDate(installedVersion.displayString)
                            _updateState.value = upToDate
                            return@withContext upToDate
                        }

                        // Persist cache and timestamp
                        preferences.setCachedLatestReleaseJson(bodyString)
                        preferences.setLastUpdateCheckTimestamp(now)

                        val isNewer = release.version.isNewerThan(installedVersion)
                        val isDismissed = release.tagName.equals(dismissedTag, ignoreCase = true)

                        val result = if (isNewer) {
                            UpdateCheckResult.UpdateAvailable(release, isDismissed = isDismissed)
                        } else {
                            UpdateCheckResult.UpToDate(installedVersion.displayString)
                        }
                        _updateState.value = result
                        result
                    }
                    304 -> {
                        // Not modified
                        preferences.setLastUpdateCheckTimestamp(now)
                        val upToDate = UpdateCheckResult.UpToDate(installedVersion.displayString)
                        _updateState.value = upToDate
                        upToDate
                    }
                    403, 429 -> {
                        // Rate limit exceeded
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

    /**
     * Marks the given release tag as dismissed so it won't auto-popup on the Home screen.
     */
    suspend fun dismissRelease(tagName: String) {
        preferences.setDismissedUpdateTag(tagName)
        val current = _updateState.value
        if (current is UpdateCheckResult.UpdateAvailable && current.release.tagName == tagName) {
            _updateState.value = current.copy(isDismissed = true)
        }
    }

    /**
     * Synchronizes FCM topic subscription for app updates with user preferences.
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
            // Gracefully ignore if Firebase is uninitialized or Google Play Services is missing
        }
    }

    private fun parseReleaseJson(jsonString: String): ReleaseInfo? {
        return try {
            val json = JSONObject(jsonString)
            val tagName = json.optString("tag_name", "")
            if (tagName.isBlank()) return null

            val name = json.optString("name", tagName)
            val body = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")
            val htmlUrl = json.optString("html_url", "$GITHUB_REPO_URL/releases/tag/$tagName")
            val isDraft = json.optBoolean("draft", false)
            val isPrerelease = json.optBoolean("prerelease", false)

            var apkUrl: String? = null
            var apkSize = 0L

            val assetsArray: JSONArray? = json.optJSONArray("assets")
            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.optJSONObject(i) ?: continue
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "").ifBlank { null }
                        apkSize = asset.optLong("size", 0L)
                        // Prefer clean name "OffGrid-Music.apk" if available
                        if (assetName.equals("OffGrid-Music.apk", ignoreCase = true)) {
                            break
                        }
                    }
                }
            }

            ReleaseInfo(
                tagName = tagName,
                name = name,
                body = body,
                publishedAt = publishedAt,
                htmlUrl = htmlUrl,
                apkDownloadUrl = apkUrl,
                apkSize = apkSize,
                isDraft = isDraft,
                isPrerelease = isPrerelease,
                version = AppVersion.parse(tagName)
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val GITHUB_REPO_URL = "https://github.com/Paul00755/OffGrid-Music"
        const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/Paul00755/OffGrid-Music/releases/latest"
        const val UPDATE_TOPIC = "offgrid_updates"
        const val COOLDOWN_MS = 4 * 3600 * 1000L // 4 hours

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
    }
}
