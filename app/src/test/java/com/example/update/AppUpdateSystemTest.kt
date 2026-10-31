package com.example.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateSystemTest {

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
    fun testAppVersionBuildNumberComparison() {
        val build1 = AppVersion.parse("1.0.4-build.10")
        val build2 = AppVersion.parse("1.0.4-build.25")
        val regular = AppVersion.parse("1.0.4")

        assertTrue("Build 25 must be newer than Build 10", build2.isNewerThan(build1))
        assertTrue("Build 10 must be newer than plain 1.0.4", build1.isNewerThan(regular))
    }

    @Test
    fun testAppVersionMalformedDoesNotCrash() {
        val malformed1 = AppVersion.parse("")
        val malformed2 = AppVersion.parse("unknown-tag")
        val malformed3 = AppVersion.parse("alpha-beta-gamma")

        assertEquals("Should parse as v0.0.0", "v0.0.0", malformed1.displayString)
        assertEquals("Should parse as v0.0.0", "v0.0.0", malformed2.displayString)
        assertEquals("Should parse as v0.0.0", "v0.0.0", malformed3.displayString)
    }

    @Test
    fun testReleaseNotesSanitization() {
        val rawMarkdown = """
            # 🎵 OffGrid Music — v1.0.5
            <br>
            <p>Some HTML paragraph</p>
            [Download APK](https://github.com/Paul00755/OffGrid-Music/releases/download/v1.0.5/OffGrid-Music.apk)
            - **Fix**: Crash on startup when storage permission denied
            - **Feature**: Smooth lyric widget transition
            - [x] Tested on Android 14
        """.trimIndent()

        val sanitized = ReleaseInfo.sanitizeReleaseNotes(rawMarkdown)

        assertFalse("Should not contain raw markdown header hashes", sanitized.contains("# 🎵"))
        assertFalse("Should not contain HTML tags", sanitized.contains("<p>") || sanitized.contains("</p>") || sanitized.contains("<br>"))
        assertTrue("Should preserve bullet content", sanitized.contains("Fix: Crash on startup"))
        assertTrue("Should preserve feature bullet", sanitized.contains("Feature: Smooth lyric widget transition"))
    }

    @Test
    fun testApkAssetUrlResolution() {
        val rawJson = """
            {
              "tag_name": "v1.1.0",
              "name": "OffGrid Music v1.1.0",
              "body": "- Enhanced Equalizer\n- Lyric transitions",
              "published_at": "2026-10-31T05:00:00Z",
              "html_url": "https://github.com/Paul00755/OffGrid-Music/releases/tag/v1.1.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://github.com/Paul00755/OffGrid-Music/releases/download/v1.1.0/checksums.txt",
                  "size": 1024
                },
                {
                  "name": "OffGrid-Music.apk",
                  "browser_download_url": "https://github.com/Paul00755/OffGrid-Music/releases/download/v1.1.0/OffGrid-Music.apk",
                  "size": 15728640
                }
              ]
            }
        """.trimIndent()

        val jsonObject = org.json.JSONObject(rawJson)
        val releaseInfo = ReleaseInfo.fromJson(jsonObject)

        assertNotNull("ReleaseInfo should be parsed successfully", releaseInfo)
        assertEquals("v1.1.0", releaseInfo!!.tag)
        assertEquals("OffGrid Music v1.1.0", releaseInfo.name)
        assertEquals(
            "https://github.com/Paul00755/OffGrid-Music/releases/download/v1.1.0/OffGrid-Music.apk",
            releaseInfo.apkDownloadUrl
        )
        assertTrue(releaseInfo.isApkAttached)
    }

    @Test
    fun testUpdateAvailableDismissalFlag() {
        val info = ReleaseInfo(
            tagName = "v1.1.0",
            name = "v1.1.0",
            body = "Notes",
            publishedAt = "2026-10-31T05:00:00Z",
            htmlUrl = "https://github.com/Paul00755/OffGrid-Music/releases/tag/v1.1.0",
            apkDownloadUrl = "https://github.com/Paul00755/OffGrid-Music/releases/download/v1.1.0/OffGrid-Music.apk"
        )

        val availableNotDismissed = UpdateCheckResult.UpdateAvailable(
            release = info,
            isDismissed = false
        )
        assertFalse(availableNotDismissed.isDismissed)

        val availableDismissed = availableNotDismissed.copy(isDismissed = true)
        assertTrue(availableDismissed.isDismissed)
    }
}
