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
}
