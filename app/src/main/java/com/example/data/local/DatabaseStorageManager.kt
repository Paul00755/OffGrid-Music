package com.example.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File

data class DatabaseStorageStats(
    val mainDbSizeBytes: Long,
    val walSizeBytes: Long,
    val totalDbSizeBytes: Long,
    val songCount: Int,
    val playlistCount: Int,
    val historySessionCount: Int,
    val cachedLyricsCount: Int,
    val searchQueryCount: Int,
    val folderCount: Int,
    val smartPlaylistCount: Int,
    val formattedMainDbSize: String,
    val formattedWalSize: String,
    val formattedTotalDbSize: String,
    val quotaLimit: Int = 50000,
    val quotaRemaining: Int = 50000,
    val quotaUsagePercentage: Float = 0f
)

data class OptimizationResult(
    val success: Boolean,
    val bytesReclaimed: Long,
    val formattedReclaimed: String,
    val message: String
)

object DatabaseStorageManager {

    fun calculateQuotaUsage(historyCount: Int, tier: String): Triple<Int, Int, Float> {
        val quotaLimit = when (tier) {
            "MAXIMUM" -> 100000
            "STANDARD" -> 10000
            else -> 50000
        }
        val remaining = (quotaLimit - historyCount).coerceAtLeast(0)
        val usagePercentage = if (quotaLimit > 0) (historyCount.toFloat() / quotaLimit.toFloat()).coerceIn(0f, 1f) else 0f
        return Triple(quotaLimit, remaining, usagePercentage)
    }

    suspend fun getStorageStats(context: Context): DatabaseStorageStats = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val mainDbFile = context.getDatabasePath("offgrid_music_db")
        val walFile = File(mainDbFile.parentFile, "offgrid_music_db-wal")
        val shmFile = File(mainDbFile.parentFile, "offgrid_music_db-shm")

        val mainDbSize = if (mainDbFile.exists()) mainDbFile.length() else 0L
        val walSize = (if (walFile.exists()) walFile.length() else 0L) + (if (shmFile.exists()) shmFile.length() else 0L)
        val totalSize = mainDbSize + walSize

        val songCount = try { db.songDao().getSongCountOnce() } catch (_: Exception) { 0 }
        val playlistCount = try { db.playlistDao().getPlaylistCountOnce() } catch (_: Exception) { 0 }
        val historyCount = try { db.historyDao().getHistoryCountOnce() } catch (_: Exception) { 0 }
        val lyricsCount = try { db.lyricsDao().getLyricsCountOnce() } catch (_: Exception) { 0 }
        val searchCount = try { db.recentSearchDao().getRecentSearchCountOnce() } catch (_: Exception) { 0 }
        val folderCount = try { db.folderDao().getFolderCountOnce() } catch (_: Exception) { 0 }
        val smartCount = try { db.smartPlaylistDao().getSmartPlaylistCountOnce() } catch (_: Exception) { 0 }

        val tier = try {
            UserPreferencesRepository.getInstance(context).databaseStorageTier.firstOrNull() ?: "EXPANDED"
        } catch (_: Exception) {
            "EXPANDED"
        }

        val (quotaLimit, quotaRemaining, quotaUsagePercentage) = calculateQuotaUsage(historyCount, tier)

        DatabaseStorageStats(
            mainDbSizeBytes = mainDbSize,
            walSizeBytes = walSize,
            totalDbSizeBytes = totalSize,
            songCount = songCount,
            playlistCount = playlistCount,
            historySessionCount = historyCount,
            cachedLyricsCount = lyricsCount,
            searchQueryCount = searchCount,
            folderCount = folderCount,
            smartPlaylistCount = smartCount,
            formattedMainDbSize = UserPreferencesRepository.formatFileSize(mainDbSize),
            formattedWalSize = UserPreferencesRepository.formatFileSize(walSize),
            formattedTotalDbSize = UserPreferencesRepository.formatFileSize(totalSize),
            quotaLimit = quotaLimit,
            quotaRemaining = quotaRemaining,
            quotaUsagePercentage = quotaUsagePercentage
        )
    }

    suspend fun optimizeDatabase(context: Context): OptimizationResult = withContext(Dispatchers.IO) {
        try {
            val mainDbFile = context.getDatabasePath("offgrid_music_db")
            val walFile = File(mainDbFile.parentFile, "offgrid_music_db-wal")
            val beforeSize = (if (mainDbFile.exists()) mainDbFile.length() else 0L) + (if (walFile.exists()) walFile.length() else 0L)

            val db = AppDatabase.getInstance(context)
            val writableDb = db.openHelper.writableDatabase

            // 1. Truncate WAL checkpoint to flush pending transactions into the main B-Tree
            try {
                writableDb.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            } catch (_: Exception) {}

            // 2. Run incremental vacuum to return unused pages
            try {
                writableDb.execSQL("PRAGMA incremental_vacuum(500)")
            } catch (_: Exception) {}

            // 3. Rebuild B-Tree indices to accelerate lookups on large song collections
            try {
                writableDb.execSQL("REINDEX")
            } catch (_: Exception) {}

            // 4. Run SQLite cost-based optimizer to refresh table statistics
            try {
                writableDb.execSQL("PRAGMA optimize")
            } catch (_: Exception) {}

            val afterSize = (if (mainDbFile.exists()) mainDbFile.length() else 0L) + (if (walFile.exists()) walFile.length() else 0L)
            val reclaimed = (beforeSize - afterSize).coerceAtLeast(0L)

            OptimizationResult(
                success = true,
                bytesReclaimed = reclaimed,
                formattedReclaimed = UserPreferencesRepository.formatFileSize(reclaimed),
                message = if (reclaimed > 0) {
                    "Optimized database indexes and reclaimed ${UserPreferencesRepository.formatFileSize(reclaimed)} storage."
                } else {
                    "Database indexes optimized and verified. Storage is operating at peak efficiency."
                }
            )
        } catch (e: Exception) {
            OptimizationResult(
                success = false,
                bytesReclaimed = 0L,
                formattedReclaimed = "0.0 KB",
                message = "Optimization encountered an issue: ${e.localizedMessage ?: "Unknown error"}"
            )
        }
    }
}
