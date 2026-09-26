package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.firstOrNull

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 9,
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
                    db.execSQL("CREATE TABLE IF NOT EXISTS smart_playlists (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `filterType` TEXT NOT NULL, `rulesJson` TEXT NOT NULL, `sortOrder` TEXT NOT NULL, `limit` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE TABLE IF NOT EXISTS playlist_play_events (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)")
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
                    db.execSQL("CREATE TABLE IF NOT EXISTS playlist_play_events (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)")
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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
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
                    MIGRATION_8_9
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
                INSTANCE = instance
                instance
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
