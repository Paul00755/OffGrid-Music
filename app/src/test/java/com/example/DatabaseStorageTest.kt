package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DatabaseStorageManager
import com.example.data.local.DatabaseStorageStats
import com.example.data.local.PlaylistEntity
import com.example.data.local.SmartPlaylistEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseStorageTest {

    @Test
    fun testDatabaseStorageStatsFormatting() {
        val stats = DatabaseStorageStats(
            mainDbSizeBytes = 2048 * 1024L,
            walSizeBytes = 512 * 1024L,
            totalDbSizeBytes = 2560 * 1024L,
            songCount = 1500,
            playlistCount = 12,
            historySessionCount = 8500,
            cachedLyricsCount = 420,
            searchQueryCount = 50,
            folderCount = 3,
            smartPlaylistCount = 4,
            formattedMainDbSize = "2.00 MB",
            formattedWalSize = "512.00 KB",
            formattedTotalDbSize = "2.50 MB"
        )

        assertEquals("2.00 MB", stats.formattedMainDbSize)
        assertEquals("512.00 KB", stats.formattedWalSize)
        assertEquals(1500, stats.songCount)
        assertEquals(12, stats.playlistCount)
        assertEquals(4, stats.smartPlaylistCount)
        assertEquals(8500, stats.historySessionCount)
        assertEquals(420, stats.cachedLyricsCount)
    }

    @Test
    fun testDatabaseBackupExportAndImport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)

        // Insert sample playlist
        val playlistId = db.playlistDao().insertPlaylist(
            PlaylistEntity(
                name = "Deep Focus Vibes",
                createdAt = 1700000000000L,
                isPinned = true
            )
        )
        assertTrue(playlistId > 0)

        // Insert sample smart playlist
        val smartId = db.smartPlaylistDao().insertSmartPlaylist(
            SmartPlaylistEntity(
                name = "Rock Anthems",
                genreFilter = "Rock",
                minPlayCount = 5,
                timeframeDays = 30,
                maxTrackLimit = 50,
                sortCriteria = "MOST_PLAYED"
            )
        )
        assertTrue(smartId > 0)

        // Export backup
        val backupFile = DatabaseStorageManager.exportDatabaseBackup(context)
        assertNotNull(backupFile)
        assertTrue(backupFile!!.exists())
        val jsonContent = backupFile.readText()
        val root = JSONObject(jsonContent)

        assertTrue(root.has("playlists"))
        assertTrue(root.has("smartPlaylists"))
        assertEquals("OffGrid Music", root.getString("app"))

        // Test import
        val importResult = DatabaseStorageManager.importDatabaseBackup(context, jsonContent)
        assertTrue(importResult.success)
        assertTrue(importResult.playlistsRestored >= 1)
        assertTrue(importResult.smartPlaylistsRestored >= 1)

        // Test stats calculation
        val stats = DatabaseStorageManager.getStorageStats(context)
        assertNotNull(stats)
        assertTrue(stats.playlistCount >= 1)
        assertTrue(stats.smartPlaylistCount >= 1)

        // Test optimization
        val optResult = DatabaseStorageManager.optimizeDatabase(context)
        assertNotNull(optResult)
        assertTrue(optResult.success)
    }

    @Test
    fun testIndependentUserStorageQuotaAccounting() {
        // User A / Device A: Quota = 10,000, 5,000 records used -> 5,000 remaining
        val (limitA, remA, usageA) = DatabaseStorageManager.calculateQuotaUsage(5000, "STANDARD")
        assertEquals(10000, limitA)
        assertEquals(5000, remA)
        assertEquals(0.5f, usageA, 0.001f)

        // User B / Device B: Quota = 10,000, 5,000 records used -> 5,000 remaining
        val (limitB, remB, usageB) = DatabaseStorageManager.calculateQuotaUsage(5000, "STANDARD")
        assertEquals(10000, limitB)
        assertEquals(5000, remB)
        assertEquals(0.5f, usageB, 0.001f)

        // Verify User B's 5,000 records do NOT consume User A's remaining 5,000
        assertEquals(remA, remB)

        // User A upgrades to EXPANDED (50K): User A now has 50K capacity and 45K remaining
        val (upgradedLimitA, upgradedRemA, upgradedUsageA) = DatabaseStorageManager.calculateQuotaUsage(5000, "EXPANDED")
        assertEquals(50000, upgradedLimitA)
        assertEquals(45000, upgradedRemA)
        assertEquals(0.1f, upgradedUsageA, 0.001f)

        // User B remains on STANDARD with completely independent 10K quota and 5K remaining
        val (stillLimitB, stillRemB, _) = DatabaseStorageManager.calculateQuotaUsage(5000, "STANDARD")
        assertEquals(10000, stillLimitB)
        assertEquals(5000, stillRemB)

        // User A upgrades to MAXIMUM (100K)
        val (maxLimitA, maxRemA, maxUsageA) = DatabaseStorageManager.calculateQuotaUsage(5000, "MAXIMUM")
        assertEquals(100000, maxLimitA)
        assertEquals(95000, maxRemA)
        assertEquals(0.05f, maxUsageA, 0.001f)
    }

    @Test
    fun testStoryTimeRangesAndBoundarySemantics() {
        val fixedNow = 1718000000000L // arbitrary fixed timestamp

        // Today: start of today to start of tomorrow
        val (todayStart, todayEnd) = com.example.ui.components.StoryPeriod.TODAY.getTimeRange(fixedNow)
        assertTrue(todayStart < fixedNow)
        assertTrue(todayEnd > fixedNow)
        val diffTodayDays = (todayEnd - todayStart) / (24 * 60 * 60 * 1000L)
        assertEquals(1L, diffTodayDays)

        // Yesterday: start of yesterday to start of today
        val (yestStart, yestEnd) = com.example.ui.components.StoryPeriod.YESTERDAY.getTimeRange(fixedNow)
        assertEquals(todayStart, yestEnd)
        val diffYestDays = (yestEnd - yestStart) / (24 * 60 * 60 * 1000L)
        assertEquals(1L, diffYestDays)

        // Last 7 Days: exact existing semantics (6 days ago midnight to Long.MAX_VALUE)
        val (l7Start, l7End) = com.example.ui.components.StoryPeriod.LAST_7_DAYS.getTimeRange(fixedNow)
        assertTrue(l7Start < todayStart)
        assertEquals(Long.MAX_VALUE, l7End)

        // This Month & Last Month
        val (thisMonthStart, thisMonthEnd) = com.example.ui.components.StoryPeriod.THIS_MONTH.getTimeRange(fixedNow)
        val (lastMonthStart, lastMonthEnd) = com.example.ui.components.StoryPeriod.LAST_MONTH.getTimeRange(fixedNow)
        assertEquals(thisMonthStart, lastMonthEnd)
        assertTrue(lastMonthStart < lastMonthEnd)

        // This Year
        val (thisYearStart, thisYearEnd) = com.example.ui.components.StoryPeriod.THIS_YEAR.getTimeRange(fixedNow)
        assertTrue(thisYearStart <= fixedNow)
        assertTrue(thisYearEnd > fixedNow)

        // All Time
        val (allStart, allEnd) = com.example.ui.components.StoryPeriod.ALL_TIME.getTimeRange(fixedNow)
        assertEquals(0L, allStart)
        assertEquals(Long.MAX_VALUE, allEnd)

        // Custom range: same day selection
        val customRangeSameDay = com.example.ui.components.StoryTimeRange.custom(
            startMs = todayStart,
            endMs = todayEnd,
            label = "Jun 10, 2024"
        )
        val (customStart, customEnd) = customRangeSameDay.getTimeRange()
        assertEquals(todayStart, customStart)
        assertEquals(todayEnd, customEnd)
        assertEquals("Jun 10, 2024", customRangeSameDay.displayLabel)
    }
}
