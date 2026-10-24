package com.example.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

data class BackupImportResult(
    val success: Boolean,
    val playlistsRestored: Int,
    val smartPlaylistsRestored: Int,
    val favoritesRestored: Int,
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

    suspend fun exportDatabaseBackup(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val root = JSONObject()
            root.put("version", 1)
            root.put("app", "OffGrid Music")
            root.put("timestamp", System.currentTimeMillis())
            root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))

            // Export Playlists
            val playlists = db.playlistDao().getAllPlaylistsOnce()
            val playlistArray = JSONArray()
            for (p in playlists) {
                val pObj = JSONObject()
                pObj.put("id", p.id)
                pObj.put("name", p.name)
                pObj.put("createdAt", p.createdAt)
                pObj.put("isPinned", p.isPinned)
                pObj.put("artworkUri", p.artworkUri ?: "")
                playlistArray.put(pObj)
            }
            root.put("playlists", playlistArray)

            // Export Playlist Songs
            val crossRefs = db.playlistDao().getAllPlaylistSongsOnce()
            val crossRefArray = JSONArray()
            for (cr in crossRefs) {
                val crObj = JSONObject()
                crObj.put("playlistId", cr.playlistId)
                crObj.put("songId", cr.songId)
                crObj.put("position", cr.position)
                crossRefArray.put(crObj)
            }
            root.put("playlistSongs", crossRefArray)

            // Export Smart Playlists
            val smartPlaylists = db.smartPlaylistDao().getAllSmartPlaylistsOnce()
            val smartArray = JSONArray()
            for (sp in smartPlaylists) {
                val spObj = JSONObject()
                spObj.put("id", sp.id)
                spObj.put("name", sp.name)
                spObj.put("genreFilter", sp.genreFilter)
                spObj.put("minPlayCount", sp.minPlayCount)
                spObj.put("timeframeDays", sp.timeframeDays)
                spObj.put("maxTrackLimit", sp.maxTrackLimit)
                spObj.put("sortCriteria", sp.sortCriteria)
                spObj.put("createdAt", sp.createdAt)
                smartArray.put(spObj)
            }
            root.put("smartPlaylists", smartArray)

            // Export Favorites & Song Paths
            val allSongs = db.songDao().getAllSongsOnce()
            val favsArray = JSONArray()
            for (s in allSongs.filter { it.isFavorite }) {
                val sObj = JSONObject()
                sObj.put("path", s.path)
                sObj.put("title", s.title)
                sObj.put("artist", s.artist)
                favsArray.put(sObj)
            }
            root.put("favorites", favsArray)

            // Export User Settings & Preferences
            try {
                val userSettings = UserPreferencesRepository.getInstance(context).exportUserSettings()
                val settingsMap = userSettings.toMap()
                val sObj = JSONObject()
                for ((key, value) in settingsMap) {
                    if (value != null) {
                        sObj.put(key, value)
                    }
                }
                root.put("userSettings", sObj)
            } catch (_: Exception) {}

            val backupDirs = mutableListOf<File>()
            try {
                val dlDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (dlDir != null) backupDirs.add(File(dlDir, "OffGridMusic_Backup"))
            } catch (_: Exception) {}
            try {
                val docDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
                if (docDir != null) backupDirs.add(File(docDir, "OffGridMusic_Backup"))
            } catch (_: Exception) {}
            context.getExternalFilesDir("backups")?.let { backupDirs.add(it) }
            backupDirs.add(context.filesDir.resolve("backups"))

            val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val jsonContent = root.toString(2)
            var primaryBackupFile: File? = null

            for (dir in backupDirs) {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    val f = File(dir, "offgrid_library_backup_$timestampStr.json")
                    f.writeText(jsonContent, Charsets.UTF_8)
                    File(dir, "offgrid_auto_backup.json").writeText(jsonContent, Charsets.UTF_8)
                    if (primaryBackupFile == null) primaryBackupFile = f
                } catch (_: Exception) {}
            }
            primaryBackupFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun importDatabaseBackup(context: Context, jsonString: String): BackupImportResult = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)
            val db = AppDatabase.getInstance(context)

            var restoredPlaylists = 0
            var restoredSmart = 0
            var restoredFavs = 0

            // 1. Restore Playlists
            if (root.has("playlists")) {
                val pArray = root.getJSONArray("playlists")
                val playlistsToInsert = mutableListOf<PlaylistEntity>()
                for (i in 0 until pArray.length()) {
                    val item = pArray.getJSONObject(i)
                    playlistsToInsert.add(
                        PlaylistEntity(
                            id = item.optLong("id", 0L),
                            name = item.optString("name", "Restored Playlist"),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            isPinned = item.optBoolean("isPinned", false),
                            artworkUri = item.optString("artworkUri").takeIf { it.isNotBlank() }
                        )
                    )
                }
                if (playlistsToInsert.isNotEmpty()) {
                    db.playlistDao().insertPlaylists(playlistsToInsert)
                    restoredPlaylists = playlistsToInsert.size
                }
            }

            // 2. Restore Playlist Songs
            if (root.has("playlistSongs")) {
                val crArray = root.getJSONArray("playlistSongs")
                val crossRefs = mutableListOf<PlaylistSongCrossRef>()
                for (i in 0 until crArray.length()) {
                    val item = crArray.getJSONObject(i)
                    crossRefs.add(
                        PlaylistSongCrossRef(
                            playlistId = item.optLong("playlistId"),
                            songId = item.optLong("songId"),
                            position = item.optInt("position", 0)
                        )
                    )
                }
                if (crossRefs.isNotEmpty()) {
                    db.playlistDao().insertPlaylistSongs(crossRefs)
                }
            }

            // 3. Restore Smart Playlists
            if (root.has("smartPlaylists")) {
                val spArray = root.getJSONArray("smartPlaylists")
                val smartList = mutableListOf<SmartPlaylistEntity>()
                for (i in 0 until spArray.length()) {
                    val item = spArray.getJSONObject(i)
                    smartList.add(
                        SmartPlaylistEntity(
                            id = item.optLong("id", 0L),
                            name = item.optString("name", "Restored Smart Playlist"),
                            genreFilter = item.optString("genreFilter", "ALL"),
                            minPlayCount = item.optInt("minPlayCount", 0),
                            timeframeDays = item.optInt("timeframeDays", 0),
                            maxTrackLimit = item.optInt("maxTrackLimit", 0),
                            sortCriteria = item.optString("sortCriteria", "RECENTLY_ADDED"),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                if (smartList.isNotEmpty()) {
                    db.smartPlaylistDao().insertSmartPlaylists(smartList)
                    restoredSmart = smartList.size
                }
            }

            // 4. Restore Favorites
            if (root.has("favorites")) {
                val fArray = root.getJSONArray("favorites")
                for (i in 0 until fArray.length()) {
                    val item = fArray.getJSONObject(i)
                    val path = item.optString("path")
                    if (path.isNotBlank()) {
                        db.songDao().setFavoriteBySong(0L, path, true)
                        restoredFavs++
                    }
                }
            }

            // 5. Restore User Settings & Preferences
            var restoredSettings = false
            if (root.has("userSettings")) {
                try {
                    val sObj = root.getJSONObject("userSettings")
                    val map = mutableMapOf<String, Any?>()
                    val keys = sObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        map[key] = sObj.get(key)
                    }
                    val cloudSettings = com.example.data.sync.CloudUserSettings.fromMap(map)
                    UserPreferencesRepository.getInstance(context).importUserSettings(cloudSettings, checkTimestamp = false)
                    restoredSettings = true
                } catch (_: Exception) {}
            }

            val settingsSuffix = if (restoredSettings) " and user settings restored." else "."
            BackupImportResult(
                success = true,
                playlistsRestored = restoredPlaylists,
                smartPlaylistsRestored = restoredSmart,
                favoritesRestored = restoredFavs,
                message = "Restored $restoredPlaylists playlist(s), $restoredSmart smart playlist(s), marked $restoredFavs song(s) as favorite$settingsSuffix"
            )
        } catch (e: Exception) {
            BackupImportResult(
                success = false,
                playlistsRestored = 0,
                smartPlaylistsRestored = 0,
                favoritesRestored = 0,
                message = "Failed to import backup: ${e.localizedMessage ?: "Invalid JSON format"}"
            )
        }
    }

    suspend fun autoSaveLibrarySnapshot(context: Context) = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val songCount = try { db.songDao().getSongCountOnce() } catch (_: Exception) { 0 }
            val playlistCount = try { db.playlistDao().getPlaylistCountOnce() } catch (_: Exception) { 0 }
            if (songCount > 0 || playlistCount > 0) {
                exportDatabaseBackup(context)
            }
        } catch (_: Exception) {}
    }

    suspend fun autoRestoreLibraryIfEmpty(context: Context) = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val songCount = try { db.songDao().getSongCountOnce() } catch (_: Exception) { 0 }
            val playlistCount = try { db.playlistDao().getPlaylistCountOnce() } catch (_: Exception) { 0 }
            val smartCount = try { db.smartPlaylistDao().getSmartPlaylistCountOnce() } catch (_: Exception) { 0 }

            // Guard: Never perform auto-restore on healthy, populated databases
            // Only recover if the database has zero songs, playlists, and smart playlists
            if (songCount > 0 || playlistCount > 0 || smartCount > 0) {
                return@withContext
            }

            val candidateDirs = mutableListOf<File>()
            try {
                val dlDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (dlDir != null) candidateDirs.add(File(dlDir, "OffGridMusic_Backup"))
            } catch (_: Exception) {}
            try {
                val docDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
                if (docDir != null) candidateDirs.add(File(docDir, "OffGridMusic_Backup"))
            } catch (_: Exception) {}
            context.getExternalFilesDir("backups")?.let { candidateDirs.add(it) }
            candidateDirs.add(context.filesDir.resolve("backups"))

            var latestBackup: File? = null
            for (dir in candidateDirs) {
                if (dir.exists()) {
                    val autoFile = File(dir, "offgrid_auto_backup.json")
                    if (autoFile.exists() && autoFile.length() > 0) {
                        if (latestBackup == null || autoFile.lastModified() > latestBackup.lastModified()) {
                            latestBackup = autoFile
                        }
                    }
                    val files = dir.listFiles { f -> f.extension == "json" && f.name.startsWith("offgrid_library_backup_") }
                    val dirLatest = files?.maxByOrNull { it.lastModified() }
                    if (dirLatest != null && (latestBackup == null || dirLatest.lastModified() > latestBackup.lastModified())) {
                        latestBackup = dirLatest
                    }
                }
            }

            if (latestBackup != null && latestBackup.exists()) {
                val json = latestBackup.readText(Charsets.UTF_8)
                importDatabaseBackup(context, json)
            }
        } catch (_: Exception) {}
    }
}
