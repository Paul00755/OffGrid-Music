package com.example.update

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.data.local.UserPreferencesRepository
import com.example.service.notification.OffGridFirebaseMessagingService
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateSystemTest {

    private lateinit var context: Context
    private lateinit var prefs: UserPreferencesRepository
    private lateinit var repo: AppUpdateRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext<Application>()
        UserPreferencesRepository.resetInstanceForTesting(context)
        AppUpdateRepository.resetInstanceForTesting()
        prefs = UserPreferencesRepository.getInstance(context)
        prefs.clearAllForTesting()
        repo = AppUpdateRepository.getInstance(context)
        repo.installedVersionOverrideForTest = AppVersion.parse("1.0.4")
        MainActivity.isActivityAlive = false
    }

    @After
    fun tearDown() = runBlocking {
        prefs.clearAllForTesting()
        AppUpdateRepository.resetInstanceForTesting()
        MainActivity.isActivityAlive = false
    }

    private fun fakeHttpClient(responder: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain -> responder(chain.request()) }
            .build()
    }

    private fun buildJsonResponse(request: Request, code: Int, body: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "HTTP $code")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    // =========================================================================
    // A. Release Detection & Version Comparison Tests
    // =========================================================================

    @Test
    fun testAppVersionParsingAndSemanticComparison() {
        val v1 = AppVersion.parse("1.0.4")
        val v2 = AppVersion.parse("v1.0.9")
        val v3 = AppVersion.parse("v1.0.10")
        val v4 = AppVersion.parse("1.2.0")
        val v5 = AppVersion.parse("2.0.0")

        assertTrue("1.0.9 must be newer than 1.0.4", v2.isNewerThan(v1))
        assertTrue("1.0.10 must be newer than 1.0.9", v3.isNewerThan(v2))
        assertTrue("1.2.0 must be newer than 1.0.10", v4.isNewerThan(v3))
        assertTrue("2.0.0 must be newer than 1.2.0", v5.isNewerThan(v4))

        assertFalse("1.0.4 is not newer than 1.0.4", v1.isNewerThan(v1))
        assertFalse("1.0.4 is not newer than 1.0.9", v1.isNewerThan(v2))
    }

    @Test
    fun testInstalledVersionDoesNotFalseRejectLowBuildNumbers() {
        // Previously, passing BuildConfig.VERSION_CODE (5) as fallbackBuildNumber caused local "1.0.4"
        // to parse as 1.0.4 (Build 5) and reject v1.0.4-build.2!
        val localInstalled = AppVersion.parse("1.0.4")
        val ciReleaseBuild2 = AppVersion.parse("v1.0.4-build.2")
        val ciReleaseBuild18 = AppVersion.parse("v1.0.4-build.18")

        assertEquals(0L, localInstalled.buildNumber)
        assertTrue("v1.0.4-build.2 must be recognized as newer than plain 1.0.4", ciReleaseBuild2.isNewerThan(localInstalled))
        assertTrue("v1.0.4-build.18 must be recognized as newer than v1.0.4-build.2", ciReleaseBuild18.isNewerThan(ciReleaseBuild2))
    }

    @Test
    fun testAppVersionMalformedAndPreReleaseNotTreatedAsNewer() {
        val installed = AppVersion.parse("1.0.4")
        val malformed1 = AppVersion.parse("")
        val malformed2 = AppVersion.parse("latest")
        val preRelease = AppVersion.parse("v1.1.0-beta.1")

        assertFalse(malformed1.isValid)
        assertFalse(malformed2.isValid)
        assertFalse("Malformed tag must never be newer", malformed2.isNewerThan(installed))
        assertTrue(preRelease.isPreRelease)
        assertFalse("Pre-release tag must not trigger stable update", preRelease.isNewerThan(installed))
    }

    @Test
    fun testDraftAndPrereleaseJsonIgnored() {
        val draftJson = JSONObject(
            """
            {
              "tag_name": "v1.2.0",
              "name": "OffGrid Music v1.2.0",
              "body": "Draft notes",
              "draft": true,
              "prerelease": false
            }
            """.trimIndent()
        )
        val prereleaseJson = JSONObject(
            """
            {
              "tag_name": "v1.2.0",
              "name": "OffGrid Music v1.2.0",
              "body": "Beta notes",
              "draft": false,
              "prerelease": true
            }
            """.trimIndent()
        )
        assertNull("Draft release must be ignored", ReleaseInfo.fromJson(draftJson))
        assertNull("Prerelease must be ignored", ReleaseInfo.fromJson(prereleaseJson))
    }

    @Test
    fun testRollingLatestTagResolvesVersionFromTitleOrAssets() {
        val rollingJson = JSONObject(
            """
            {
              "tag_name": "latest",
              "name": "OffGrid Music — Latest Channel (Build #42)",
              "body": "Version 1.0.4-build.42",
              "published_at": "2026-11-02T10:00:00Z",
              "html_url": "https://github.com/Paul00755/OffGrid-Music/releases/tag/latest",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "OffGrid-Music-v1.0.4-build.42.apk",
                  "browser_download_url": "https://github.com/Paul00755/OffGrid-Music/releases/download/latest/OffGrid-Music.apk",
                  "size": 15728640
                }
              ]
            }
            """.trimIndent()
        )
        val parsed = ReleaseInfo.fromJson(rollingJson)
        assertNotNull(parsed)
        assertEquals("v1.0.4-build.42", parsed!!.tagName)
        assertEquals(42L, parsed.version.buildNumber)
        assertTrue(parsed.version.isNewerThan(AppVersion.parse("1.0.4-build.41")))
    }

    @Test
    fun testRepositoryDetectsNewerSameAndOlderReleases() = runBlocking {
        var responseTag = "v1.1.0"
        repo.setHttpClientForTesting(
            fakeHttpClient { req ->
                buildJsonResponse(
                    req,
                    200,
                    """
                    {
                      "tag_name": "$responseTag",
                      "name": "OffGrid Music $responseTag",
                      "body": "### ✨ What's New\n- Faster artwork loading\n- Fixed lyric widget layout",
                      "published_at": "2026-11-02T12:00:00Z",
                      "html_url": "https://github.com/Paul00755/OffGrid-Music/releases/tag/$responseTag",
                      "draft": false,
                      "prerelease": false,
                      "assets": [
                        {
                          "name": "OffGrid-Music.apk",
                          "browser_download_url": "https://github.com/Paul00755/OffGrid-Music/releases/download/$responseTag/OffGrid-Music.apk",
                          "size": 16000000
                        }
                      ]
                    }
                    """.trimIndent()
                )
            }
        )

        // 1. Newer release -> UpdateAvailable
        val resNewer = repo.checkForUpdates(isManual = true)
        assertTrue(resNewer is UpdateCheckResult.UpdateAvailable)
        val available = resNewer as UpdateCheckResult.UpdateAvailable
        assertFalse(available.isDismissed)
        assertEquals("v1.1.0", available.release.tagName)
        assertTrue(available.release.getSanitizedNotes().contains("Faster artwork loading"))

        // 2. Same version -> UpToDate
        responseTag = "v1.0.4"
        val resSame = repo.checkForUpdates(isManual = true)
        assertTrue(resSame is UpdateCheckResult.UpToDate)

        // 3. Older version -> UpToDate
        responseTag = "v1.0.2"
        val resOlder = repo.checkForUpdates(isManual = true)
        assertTrue(resOlder is UpdateCheckResult.UpToDate)
    }

    @Test
    fun testOfflineRateLimitAndMalformedDoNotCacheSuccessTimestamp() = runBlocking {
        // 1. Offline
        repo.setHttpClientForTesting(
            OkHttpClient.Builder()
                .addInterceptor { throw IOException("Network unreachable") }
                .build()
        )
        val offlineRes = repo.checkForUpdates(isManual = false)
        assertTrue(offlineRes is UpdateCheckResult.Offline)
        assertEquals(0L, prefs.getLastUpdateCheckTimestampSync())

        // 2. Rate limited (403)
        repo.setHttpClientForTesting(
            fakeHttpClient { req -> buildJsonResponse(req, 403, """{"message":"API rate limit exceeded"}""") }
        )
        val rateLimitRes = repo.checkForUpdates(isManual = false)
        assertTrue(rateLimitRes is UpdateCheckResult.RateLimited)
        assertEquals(0L, prefs.getLastUpdateCheckTimestampSync())

        // 3. Malformed JSON (200 with broken JSON)
        repo.setHttpClientForTesting(
            fakeHttpClient { req -> buildJsonResponse(req, 200, """{not_valid_json""") }
        )
        val malformedRes = repo.checkForUpdates(isManual = false)
        assertTrue(malformedRes is UpdateCheckResult.Error)
        assertEquals(0L, prefs.getLastUpdateCheckTimestampSync())
    }

    @Test
    fun testCooldownSkipsDuplicateNetworkCallsUnlessManual() = runBlocking {
        var networkCalls = 0
        repo.setHttpClientForTesting(
            fakeHttpClient { req ->
                networkCalls++
                buildJsonResponse(
                    req,
                    200,
                    """
                    {
                      "tag_name": "v1.2.0",
                      "name": "OffGrid Music v1.2.0",
                      "body": "- Major performance update",
                      "published_at": "2026-11-02T12:00:00Z",
                      "html_url": "https://github.com/Paul00755/OffGrid-Music/releases/tag/v1.2.0",
                      "draft": false,
                      "prerelease": false
                    }
                    """.trimIndent()
                )
            }
        )

        // First automatic check performs network call
        val first = repo.checkForUpdates(isManual = false)
        assertTrue(first is UpdateCheckResult.UpdateAvailable)
        assertEquals(1, networkCalls)

        // Second automatic check within cooldown uses cached release without network call
        val second = repo.checkForUpdates(isManual = false)
        assertTrue(second is UpdateCheckResult.UpdateAvailable)
        assertEquals(1, networkCalls)

        // Manual check from Settings bypasses cooldown
        val third = repo.checkForUpdates(isManual = true)
        assertTrue(third is UpdateCheckResult.UpdateAvailable)
        assertEquals(2, networkCalls)
    }

    // =========================================================================
    // B. In-App Dialog & Dismissal Persistence Tests
    // =========================================================================

    @Test
    fun testDismissalPersistenceAndNewerReleaseOverride() = runBlocking {
        var currentTag = "v1.1.0"
        repo.setHttpClientForTesting(
            fakeHttpClient { req ->
                buildJsonResponse(
                    req,
                    200,
                    """
                    {
                      "tag_name": "$currentTag",
                      "name": "OffGrid Music $currentTag",
                      "body": "- Fixes and improvements",
                      "published_at": "2026-11-02T12:00:00Z",
                      "html_url": "https://github.com/Paul00755/OffGrid-Music/releases/tag/$currentTag",
                      "draft": false,
                      "prerelease": false
                    }
                    """.trimIndent()
                )
            }
        )

        // Initial check shows dialog (isDismissed = false)
        val initial = repo.checkForUpdates(isManual = false) as UpdateCheckResult.UpdateAvailable
        assertFalse(initial.isDismissed)

        // User taps "Later" -> dismisses v1.1.0
        repo.dismissRelease("v1.1.0")
        val afterDismiss = repo.updateState.value as UpdateCheckResult.UpdateAvailable
        assertTrue("Dismissed release must be marked isDismissed = true", afterDismiss.isDismissed)
        assertEquals("v1.1.0", prefs.getDismissedUpdateTagSync())

        // Subsequent automatic check for same release v1.1.0 stays dismissed
        val repeatAuto = repo.checkForUpdates(isManual = false) as UpdateCheckResult.UpdateAvailable
        assertTrue("Same release v1.1.0 must remain dismissed on automatic check", repeatAuto.isDismissed)

        // User taps "View Notes" / manual check in Settings -> re-opens dialog on demand
        val reopened = repo.reopenOrCheckForUpdates() as UpdateCheckResult.UpdateAvailable
        assertFalse("Manual reopen from Settings must un-dismiss dialog", reopened.isDismissed)

        // Dismiss v1.1.0 again, then publish v1.2.0 -> newer release MUST show automatically!
        repo.dismissRelease("v1.1.0")
        currentTag = "v1.2.0"
        prefs.setLastUpdateCheckTimestamp(0L) // expire cooldown
        val newerAuto = repo.checkForUpdates(isManual = false) as UpdateCheckResult.UpdateAvailable
        assertEquals("v1.2.0", newerAuto.release.tagName)
        assertFalse("Newer release v1.2.0 must not be suppressed by v1.1.0 dismissal", newerAuto.isDismissed)
    }

    // =========================================================================
    // C. FCM Push Notification & Coexistence Tests
    // =========================================================================

    @Test
    fun testFcmPayloadCreatesNotificationAndDeduplicates() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
        val shadowNm = Shadows.shadowOf(nm)
        val shadowApp = Shadows.shadowOf(context as Application)

        val payload = mapOf(
            "version" to "v1.3.0-build.50",
            "release_tag" to "v1.3.0-build.50",
            "title" to "OffGrid Music v1.3.0 (Build #50)",
            "body" to "• Added gapless playback\n• Improved waveform smoothness",
            "release_url" to "https://github.com/Paul00755/OffGrid-Music/releases/tag/v1.3.0-build.50",
            "apk_url" to "https://github.com/Paul00755/OffGrid-Music/releases/download/v1.3.0-build.50/OffGrid-Music.apk"
        )

        // 0. When POST_NOTIFICATIONS is not granted on Android 13+, repository state updates safely without crashing
        shadowApp.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val postedWithoutPerm = OffGridFirebaseMessagingService.processIncomingUpdatePayload(context, payload)
        assertFalse("Should not post system notification when POST_NOTIFICATIONS is denied", postedWithoutPerm)
        assertTrue("Repository state should still be populated for in-app Home dialog", repo.updateState.value is UpdateCheckResult.UpdateAvailable)

        // 1. Grant POST_NOTIFICATIONS -> delivery posts notification and updates repository state
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val posted1 = OffGridFirebaseMessagingService.processIncomingUpdatePayload(context, payload)
        assertTrue("Newer release payload should post system notification when permission granted", posted1)
        assertEquals(1, shadowNm.allNotifications.size)

        val repoState = repo.updateState.value
        assertTrue(repoState is UpdateCheckResult.UpdateAvailable)
        assertEquals(
            "https://github.com/Paul00755/OffGrid-Music/releases/tag/v1.3.0-build.50",
            (repoState as UpdateCheckResult.UpdateAvailable).release.htmlUrl
        )

        // 2. Duplicate delivery of same release tag is suppressed
        val posted2 = OffGridFirebaseMessagingService.processIncomingUpdatePayload(context, payload)
        assertFalse("Duplicate FCM delivery for same release tag must be suppressed", posted2)

        // 3. Older or same version as installed (1.0.4) is suppressed
        val olderPayload = payload + ("release_tag" to "v1.0.3") + ("version" to "v1.0.3")
        val postedOlder = OffGridFirebaseMessagingService.processIncomingUpdatePayload(context, olderPayload)
        assertFalse("Older release FCM payload must be ignored", postedOlder)
    }

    @Test
    fun testFcmRespectsUserDisabledUpdateNotifications() = runBlocking {
        prefs.setUpdateNotificationsEnabled(false)
        val payload = mapOf(
            "release_tag" to "v1.4.0",
            "title" to "OffGrid Music v1.4.0",
            "body" to "New update",
            "release_url" to "https://github.com/Paul00755/OffGrid-Music/releases/tag/v1.4.0"
        )
        val posted = OffGridFirebaseMessagingService.processIncomingUpdatePayload(context, payload)
        assertFalse("Notification must not be posted when disabled in Settings", posted)
    }

    // =========================================================================
    // D. GitHub Actions Workflow & Script Integrity Tests
    // =========================================================================

    @Test
    fun testGitHubWorkflowFilesAndFcmScriptIntegrity() {
        val rootDir = File("..").canonicalFile
        val releaseWorkflow = File(rootDir, ".github/workflows/release-notification.yml")
        val buildWorkflow = File(rootDir, ".github/workflows/build-apk.yml")
        val fcmScript = File(rootDir, ".github/scripts/send_fcm_release_notification.py")

        assertTrue("release-notification.yml must exist", releaseWorkflow.exists())
        assertTrue("build-apk.yml must exist", buildWorkflow.exists())
        assertTrue("send_fcm_release_notification.py must exist", fcmScript.exists())

        val releaseYamlLines = releaseWorkflow.readLines()
        assertFalse(
            "release-notification.yml must not contain unindented Python heredoc lines",
            releaseYamlLines.any { it.startsWith("import json") || it == "PYEOF" }
        )
        assertTrue(
            "release-notification.yml must invoke send_fcm_release_notification.py",
            releaseWorkflow.readText().contains("python3 .github/scripts/send_fcm_release_notification.py")
        )
        assertTrue(
            "build-apk.yml must invoke send_fcm_release_notification.py",
            buildWorkflow.readText().contains("python3 .github/scripts/send_fcm_release_notification.py")
        )
    }
}
