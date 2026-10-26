package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.firstOrNull

@Database(
    entities = [
        SongEntity::class,
        FolderEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        PlayHistoryEntity::class,
        PlaybackHistoryEntity::class,
        LyricsEntity::class,
        RecentSearchEntity::class,
        SmartPlaylistEntity::class,
        PlaylistPlayEventEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun folderDao(): FolderDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun smartPlaylistDao(): SmartPlaylistDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE songs ADD COLUMN artworkUri TEXT")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE playlists ADD COLUMN artworkUri TEXT")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE TABLE IF NOT EXISTS recent_searches (`query` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`query`))")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `smart_playlists` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`genreFilter` TEXT NOT NULL, " +
                            "`minPlayCount` INTEGER NOT NULL, " +
                            "`timeframeDays` INTEGER NOT NULL, " +
                            "`maxTrackLimit` INTEGER NOT NULL, " +
                            "`sortCriteria` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL)"
                    )
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_play_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_play_events_playlistId` ON `playlist_play_events` (`playlistId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_play_events_timestamp` ON `playlist_play_events` (`timestamp`)")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE playback_history ADD COLUMN isQualified INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_play_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_play_events_playlistId` ON `playlist_play_events` (`playlistId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_play_events_timestamp` ON `playlist_play_events` (`timestamp`)")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_isQualified_startedAt` ON `playback_history` (`isQualified`, `startedAt`)")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE songs ADD COLUMN isManuallyEdited INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = mutableSetOf<String>()
                db.query("PRAGMA table_info(`playlists`)").use { cursor ->
                    val nameColumn = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameColumn >= 0) columns += cursor.getString(nameColumn)
                    }
                }
                if ("cloudId" !in columns) {
                    db.execSQL("ALTER TABLE playlists ADD COLUMN cloudId TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Non-destructively heals an existing SQLite database on disk BEFORE Room opens it.
         *
         * Why this is critical for in-place app updates:
         * - When a user installs a newer APK over an older APK that already had `version = 10`
         *   (or a lower version with legacy column definitions in `smart_playlists` or missing
         *   indices in `playlist_play_events` / `playback_history`), Room's `RoomOpenHelper.checkIdentity`
         *   checks `room_master_table.identity_hash`.
         * - If the schema hash changed while `version` stayed `10`, Room does NOT run
         *   `fallbackToDestructiveMigration` — it throws a fatal `IllegalStateException` on startup,
         *   crashing the app immediately when opened after an update!
         * - By opening the raw SQLite file first, ensuring every table, column, and index matches
         *   the current Room entities while preserving 100% of user data (songs, playlists,
         *   favorites, listening stats, lyrics, recent searches), and dropping `room_master_table`,
         *   Room sees no existing master hash, runs `onValidateSchema(db)` (which succeeds),
         *   and writes the new `identity_hash` cleanly without wiping any data.
         */
        private const val ROOM_MASTER_IDENTITY_HASH = "bbe7c72c916555313ebf50d3515990b4"

        @Synchronized
        internal fun healExistingDatabaseBeforeRoomOpen(context: Context) {
            val dbFile = context.getDatabasePath("offgrid_music_db")
            if (dbFile == null || !dbFile.exists() || dbFile.length() == 0L) return

            var rawDb: SQLiteDatabase? = null
            try {
                rawDb = try {
                    SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
                    )
                } catch (_: Throwable) {
                    SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READWRITE
                    )
                }

                val priorVersion = try { rawDb.version } catch (_: Exception) { 0 }

                fun getColumns(tableName: String): Set<String> {
                    val cols = mutableSetOf<String>()
                    try {
                        rawDb.rawQuery("PRAGMA table_info(`$tableName`)", null).use { cursor ->
                            val nameIdx = cursor.getColumnIndex("name")
                            while (cursor.moveToNext()) {
                                if (nameIdx >= 0) {
                                    cols.add(cursor.getString(nameIdx))
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    return cols
                }

                fun ensureColumn(tableName: String, columnName: String, definition: String) {
                    val cols = getColumns(tableName)
                    if (cols.isNotEmpty() && !cols.contains(columnName)) {
                        try {
                            rawDb.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $definition")
                        } catch (_: Exception) {}
                    }
                }

                rawDb.beginTransaction()
                try {
                    // 1. Ensure `songs` table & all columns/indices exist
                    rawDb.execSQL(
                        "CREATE TABLE IF NOT EXISTS `songs` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`title` TEXT NOT NULL, " +
                            "`artist` TEXT NOT NULL, " +
                            "`album` TEXT NOT NULL, " +
                            "`genre` TEXT NOT NULL, " +
                            "`durationMs` INTEGER NOT NULL, " +
                            "`path` TEXT NOT NULL, " +
                            "`contentUriString` TEXT NOT NULL, " +
                            "`artworkUri` TEXT, " +
                            "`albumId` INTEGER NOT NULL, " +
                            "`artistId` INTEGER NOT NULL, " +
                            "`trackNumber` INTEGER NOT NULL, " +
                            "`year` INTEGER NOT NULL, " +
                            "`dateAdded` INTEGER NOT NULL, " +
                            "`lastPlayed` INTEGER NOT NULL, " +
                            "`playCount` INTEGER NOT NULL, " +
                            "`isFavorite` INTEGER NOT NULL, " +
                            "`bitrate` INTEGER NOT NULL, " +
                            "`format` TEXT NOT NULL, " +
                            "`isMissing` INTEGER NOT NULL, " +
                            "`isCorrupted` INTEGER NOT NULL, " +
                            "`isManuallyEdited` INTEGER NOT NULL)"
                    )
                    ensureColumn("songs", "title", "TEXT NOT NULL DEFAULT ''")
                    ensureColumn("songs", "artist", "TEXT NOT NULL DEFAULT 'Unknown Artist'")
                    ensureColumn("songs", "album", "TEXT NOT NULL DEFAULT 'Unknown Album'")
                    ensureColumn("songs", "genre", "TEXT NOT NULL DEFAULT 'Unknown Genre'")
                    ensureColumn("songs", "durationMs", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("songs", "path", "TEXT NOT NULL DEFAULT ''")
                    ensureColumn("songs", "contentUriString", "TEXT NOT NULL DEFAULT ''")
                    ensureColumn("songs", "artworkUri", "TEXT")
                    ensureColumn("songs", "albumId", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("songs", "artistId", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("songs", "trackNumber", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("songs", "year", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("songs", "dateAdded", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("songs", "lastPlayed", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("songs", "playCount", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("songs", "isFavorite", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("songs", "bitrate", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("songs", "format", "TEXT NOT NULL DEFAULT 'MP3'")
                    ensureColumn("songs", "isMissing", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("songs", "isCorrupted", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("songs", "isManuallyEdited", "INTEGER NOT NULL DEFAULT 0")
                    rawDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_songs_path` ON `songs` (`path`)")
                    rawDb.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_artist` ON `songs` (`artist`)")
                    rawDb.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_album` ON `songs` (`album`)")
                    rawDb.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_genre` ON `songs` (`genre`)")

                    // 2. Ensure `folders` table & all columns exist
                    rawDb.execSQL(
                        "CREATE TABLE IF NOT EXISTS `folders` (" +
                            "`path` TEXT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`uriString` TEXT NOT NULL, " +
                            "`isExcluded` INTEGER NOT NULL, " +
                            "`songCount` INTEGER NOT NULL, " +
                            "`lastScanTime` INTEGER NOT NULL, " +
                            "`isAccessible` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`path`))"
                    )
                    ensureColumn("folders", "name", "TEXT NOT NULL DEFAULT ''")
                    ensureColumn("folders", "uriString", "TEXT NOT NULL DEFAULT ''")
                    ensureColumn("folders", "isExcluded", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("folders", "songCount", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("folders", "lastScanTime", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("folders", "isAccessible", "INTEGER NOT NULL DEFAULT 1")

                    // 3. Ensure `playlists` table & all columns exist
                    rawDb.execSQL(
                        "CREATE TABLE IF NOT EXISTS `playlists` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`isPinned` INTEGER NOT NULL, " +
                            "`artworkUri` TEXT)"
                    )
                    ensureColumn("playlists", "name", "TEXT NOT NULL DEFAULT ''")
                    ensureColumn("playlists", "createdAt", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("playlists", "isPinned", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("playlists", "artworkUri", "TEXT")

                    // 4. Ensure `playlist_songs` table exists
                    rawDb.execSQL(
                        "CREATE TABLE IF NOT EXISTS `playlist_songs` (" +
                            "`playlistId` INTEGER NOT NULL, " +
                            "`songId` INTEGER NOT NULL, " +
                            "`position` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`playlistId`, `songId`))"
                    )
                    ensureColumn("playlist_songs", "position", "INTEGER NOT NULL DEFAULT 0")

                    // 5. Ensure `play_history` table exists
                    rawDb.execSQL(
                        "CREATE TABLE IF NOT EXISTS `play_history` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`songId` INTEGER NOT NULL, " +
                            "`timestamp` INTEGER NOT NULL, " +
                            "`durationPlayedMs` INTEGER NOT NULL, " +
                            "`completed` INTEGER NOT NULL)"
                    )
                    ensureColumn("play_history", "durationPlayedMs", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("play_history", "completed", "INTEGER NOT NULL DEFAULT 1")

                    // 6. Ensure `playback_history` table & all columns/indices exist
                    rawDb.execSQL(
                        "CREATE TABLE IF NOT EXISTS `playback_history` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`trackId` INTEGER NOT NULL, " +
                            "`startedAt` INTEGER NOT NULL, " +
                            "`endedAt` INTEGER NOT NULL, " +
                            "`listenedDurationMs` INTEGER NOT NULL, " +
                            "`sourceContext` TEXT NOT NULL, " +
                            "`completionPercentage` REAL NOT NULL, " +
                            "`sessionId` TEXT NOT NULL, " +
                            "`isQualified` INTEGER NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL)"
                    )
                    ensureColumn("playback_history", "trackId", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("playback_history", "startedAt", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("playback_history", "endedAt", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("playback_history", "listenedDurationMs", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("playback_history", "sourceContext", "TEXT NOT NULL DEFAULT 'UNKNOWN'")
                    ensureColumn("playback_history", "completionPercentage", "REAL NOT NULL DEFAULT 0.0")
                    ensureColumn("playback_history", "sessionId", "TEXT NOT NULL DEFAULT ''")
                    ensureColumn("playback_history", "isQualified", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn("playback_history", "createdAt", "INTEGER NOT NULL DEFAULT 0")
                    rawDb.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_trackId` ON `playback_history` (`trackId`)")
                    rawDb.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_startedAt` ON `playback_history` (`startedAt`)")
                    rawDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playback_history_sessionId` ON `playback_history` (`sessionId`)")
                    rawDb.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_isQualified_startedAt` ON `playback_history` (`isQualified`, `startedAt`)")

                    // 7. Ensure `lyrics` table exists
                    rawDb.execSQL(
                        "CREATE TABLE IF NOT EXISTS `lyrics` (" +
                            "`songId` INTEGER NOT NULL, " +
                            "`plainLyrics` TEXT, " +
                            "`syncedLyricsJson` TEXT, " +
                            "`isCached` INTEGER NOT NULL, " +
                            "`source` TEXT NOT NULL, " +
                            "PRIMARY KEY(`songId`))"
                    )
                    ensureColumn("lyrics", "plainLyrics", "TEXT")
                    ensureColumn("lyrics", "syncedLyricsJson", "TEXT")
                    ensureColumn("lyrics", "isCached", "INTEGER NOT NULL DEFAULT 1")
                    ensureColumn("lyrics", "source", "TEXT NOT NULL DEFAULT 'local'")

                    // 8. Ensure `recent_searches` table exists
                    rawDb.execSQL(
                        "CREATE TABLE IF NOT EXISTS `recent_searches` (" +
                            "`query` TEXT NOT NULL, " +
                            "`timestamp` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`query`))"
                    )
                    ensureColumn("recent_searches", "timestamp", "INTEGER NOT NULL DEFAULT 0")

                    // 9. Heal `smart_playlists` (if legacy MIGRATION_4_5 created `filterType/rulesJson`, rebuild cleanly)
                    val smartCols = getColumns("smart_playlists")
                    val expectedSmartCols = setOf(
                        "id", "name", "genreFilter", "minPlayCount",
                        "timeframeDays", "maxTrackLimit", "sortCriteria", "createdAt"
                    )
                    if (smartCols.isNotEmpty() && smartCols != expectedSmartCols) {
                        rawDb.execSQL(
                            "CREATE TABLE IF NOT EXISTS `smart_playlists_new` (" +
                                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`name` TEXT NOT NULL, " +
                                "`genreFilter` TEXT NOT NULL, " +
                                "`minPlayCount` INTEGER NOT NULL, " +
                                "`timeframeDays` INTEGER NOT NULL, " +
                                "`maxTrackLimit` INTEGER NOT NULL, " +
                                "`sortCriteria` TEXT NOT NULL, " +
                                "`createdAt` INTEGER NOT NULL)"
                        )
                        if (smartCols.contains("name") && smartCols.contains("createdAt")) {
                            rawDb.execSQL(
                                "INSERT OR IGNORE INTO `smart_playlists_new` (`id`, `name`, `genreFilter`, `minPlayCount`, `timeframeDays`, `maxTrackLimit`, `sortCriteria`, `createdAt`) " +
                                    "SELECT `id`, `name`, 'ALL', 0, 0, 0, 'RECENTLY_ADDED', `createdAt` FROM `smart_playlists`"
                            )
                        }
                        rawDb.execSQL("DROP TABLE `smart_playlists`")
                        rawDb.execSQL("ALTER TABLE `smart_playlists_new` RENAME TO `smart_playlists`")
                    } else {
                        rawDb.execSQL(
                            "CREATE TABLE IF NOT EXISTS `smart_playlists` (" +
                                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`name` TEXT NOT NULL, " +
                                "`genreFilter` TEXT NOT NULL, " +
                                "`minPlayCount` INTEGER NOT NULL, " +
                                "`timeframeDays` INTEGER NOT NULL, " +
                                "`maxTrackLimit` INTEGER NOT NULL, " +
                                "`sortCriteria` TEXT NOT NULL, " +
                                "`createdAt` INTEGER NOT NULL)"
                        )
                    }

                    // 10. Ensure `playlist_play_events` table and its required indices exist
                    rawDb.execSQL(
                        "CREATE TABLE IF NOT EXISTS `playlist_play_events` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`playlistId` INTEGER NOT NULL, " +
                            "`timestamp` INTEGER NOT NULL)"
                    )
                    rawDb.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_play_events_playlistId` ON `playlist_play_events` (`playlistId`)")
                    rawDb.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_play_events_timestamp` ON `playlist_play_events` (`timestamp`)")

                    // 11. If this existing DB already has user songs/playlists or a prior DB version,
                    //     mark onboarding_completed = true in SharedPreferences so updating over an
                    //     older version NEVER treats the user as new.
                    var existingSongs = 0
                    var existingPlaylists = 0
                    try {
                        rawDb.rawQuery("SELECT COUNT(*) FROM `songs`", null).use { c ->
                            if (c.moveToFirst()) existingSongs = c.getInt(0)
                        }
                        rawDb.rawQuery("SELECT COUNT(*) FROM `playlists`", null).use { c ->
                            if (c.moveToFirst()) existingPlaylists = c.getInt(0)
                        }
                    } catch (_: Exception) {}

                    val bootPrefs = context.getSharedPreferences("offgrid_boot_cache", Context.MODE_PRIVATE)
                    val alreadyCheckedInstall = bootPrefs.getBoolean("initial_install_checked", false)
                    val editor = bootPrefs.edit()
                        .putBoolean("db_checked_once", true)
                        .putBoolean("db_healed_v10", true)
                    if (!alreadyCheckedInstall && (existingSongs > 0 || existingPlaylists > 0 || (priorVersion in 1..9))) {
                        editor.putBoolean("onboarding_completed", true)
                    }
                    editor.apply()

                    // 12. Stamp room_master_table with the current version 10 identity_hash so Room's
                    //     RoomOpenDelegate.checkIdentity succeeds without throwing IllegalStateException
                    //     on minor SQLite default/affinity differences from older builds, preserving 100% of data.
                    rawDb.execSQL("CREATE TABLE IF NOT EXISTS `room_master_table` (`id` INTEGER PRIMARY KEY, `identity_hash` TEXT)")
                    rawDb.execSQL("INSERT OR REPLACE INTO `room_master_table` (`id`, `identity_hash`) VALUES(42, '$ROOM_MASTER_IDENTITY_HASH')")
                    rawDb.version = 10

                    rawDb.setTransactionSuccessful()
                } finally {
                    try {
                        rawDb.endTransaction()
                    } catch (_: Exception) {}
                }
            } catch (_: Throwable) {
                // Ignore and allow Room's normal open/fallback lifecycle to proceed
            } finally {
                try {
                    rawDb?.close()
                } catch (_: Exception) {}
            }
        }

        private fun buildRoomDatabase(appContext: Context): AppDatabase {
            return Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                "offgrid_music_db"
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11
                )
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        try {
                            db.execSQL("PRAGMA synchronous = NORMAL")
                            db.execSQL("PRAGMA journal_size_limit = 67108864") // Expanded WAL limit to 64MB
                            db.execSQL("PRAGMA cache_size = -32768") // 32MB page cache
                            db.execSQL("PRAGMA mmap_size = 268435456") // 256MB memory mapping
                            db.execSQL("PRAGMA wal_autocheckpoint = 500")
                            db.execSQL("PRAGMA busy_timeout = 10000")
                            db.execSQL("PRAGMA temp_store = MEMORY")
                            db.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
                        } catch (_: Exception) {}
                    }
                })
                .fallbackToDestructiveMigration(true)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val bootPrefs = appContext.getSharedPreferences("offgrid_boot_cache", Context.MODE_PRIVATE)
                    val alreadyHealedV10 = bootPrefs.getBoolean("db_healed_v10", false)
                    if (!alreadyHealedV10) {
                        val dbFile = appContext.getDatabasePath("offgrid_music_db")
                        val hadExistingDatabase = dbFile != null && dbFile.exists() && dbFile.length() > 0L
                        if (hadExistingDatabase) {
                            healExistingDatabaseBeforeRoomOpen(appContext)
                        } else {
                            bootPrefs.edit().putBoolean("db_healed_v10", true).apply()
                        }
                    }
                    val instance = buildRoomDatabase(appContext)
                    INSTANCE = instance
                    instance
                }
            }
        }

        suspend fun performMaintenance(context: Context) {
            try {
                val db = getInstance(context)
                val now = System.currentTimeMillis()

                val tier = try {
                    UserPreferencesRepository.getInstance(context).databaseStorageTier.firstOrNull() ?: "EXPANDED"
                } catch (_: Exception) {
                    "EXPANDED"
                }

                val (historyLimit, playlistEventsLimit, searchLimit, lyricsLimit, unqualifiedDays) = when (tier) {
                    "MAXIMUM" -> Tuple5(100000, 50000, 200, 50000, 90L)
                    "STANDARD" -> Tuple5(10000, 5000, 50, 3000, 30L)
                    else -> Tuple5(50000, 25000, 100, 15000, 60L) // EXPANDED default
                }

                val cutoffTime = now - unqualifiedDays * 24 * 60 * 60 * 1000

                // 1. Prune abandoned/unqualified history sessions older than cutoff
                db.historyDao().pruneUnqualifiedHistoryOlderThan(cutoffTime)

                // 2. Bound history table to configured capacity tier (preserves stats while bounding storage)
                db.historyDao().pruneHistoryKeepMostRecent(historyLimit)

                // 3. Bound playlist play events
                db.playlistDao().prunePlaylistPlayEventsKeepMostRecent(playlistEventsLimit)

                // 4. Bound recent searches
                db.recentSearchDao().pruneRecentSearchesKeepMostRecent(searchLimit)

                // 5. Bound lyrics cache and prune orphans
                try {
                    db.lyricsDao().pruneOrphanLyrics()
                    db.lyricsDao().pruneLyricsKeepMostRecent(lyricsLimit)
                } catch (_: Exception) {}

                // 6. Clean legacy play_history table if present
                try {
                    db.openHelper.writableDatabase.execSQL("DELETE FROM play_history WHERE timestamp < $cutoffTime")
                } catch (_: Exception) {}

                // 7. Clean and de-duplicate file storage in background
                com.example.util.ArtworkManager.cleanStorage(context)

                // 8. Checkpoint and truncate SQLite WAL file to reclaim disk space
                db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")

                // 9. Free unallocated database pages back to the filesystem
                db.openHelper.writableDatabase.execSQL("PRAGMA incremental_vacuum(100)")
            } catch (_: Exception) {}
        }
    }
}

private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
