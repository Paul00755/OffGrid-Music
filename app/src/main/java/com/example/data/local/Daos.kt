package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE isMissing = 0 ORDER BY title ASC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE path = :path")
    suspend fun getSongByPath(path: String): SongEntity?

    @Query("SELECT * FROM songs WHERE isFavorite = 1 AND isMissing = 0 ORDER BY title ASC")
    fun getFavoriteSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isMissing = 0 ORDER BY dateAdded DESC LIMIT 150")
    fun getRecentlyAddedSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isMissing = 0 AND lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT 250")
    fun getRecentlyPlayedSongs(): Flow<List<SongEntity>>

    @Query("UPDATE songs SET lastPlayed = 0")
    suspend fun clearRecentlyPlayedHistory()

    @Query("SELECT * FROM songs WHERE isMissing = 0 AND playCount > 0 ORDER BY playCount DESC LIMIT 150")
    fun getMostPlayedSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isMissing = 0 AND album = :albumName ORDER BY trackNumber ASC, title ASC")
    fun getSongsByAlbum(albumName: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isMissing = 0 AND artist = :artistName ORDER BY title ASC")
    fun getSongsByArtist(artistName: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isMissing = 0 AND genre = :genre ORDER BY title ASC")
    fun getSongsByGenre(genre: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isMissing = 0 AND path LIKE :folderPath || '%' ORDER BY title ASC")
    fun getSongsInFolder(folderPath: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isMissing = 0 AND (title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' OR path LIKE '%' || :query || '%') ORDER BY title ASC")
    fun searchSongs(query: String): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Update
    suspend fun updateSong(song: SongEntity)

    @Update
    suspend fun updateSongs(songs: List<SongEntity>)

    @Query("UPDATE songs SET isManuallyEdited = :isManual WHERE id = :songId")
    suspend fun updateManualEditStatus(songId: Long, isManual: Boolean = true)

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id = :songId")
    suspend fun setFavorite(songId: Long, isFavorite: Boolean)

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id = :songId OR path = :path")
    suspend fun setFavoriteBySong(songId: Long, path: String, isFavorite: Boolean)

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id IN (:songIds)")
    suspend fun setFavoritesBatch(songIds: List<Long>, isFavorite: Boolean = true)

    @Query("UPDATE songs SET isFavorite = 0")
    suspend fun clearAllFavorites()

    @Query("UPDATE songs SET artworkUri = :artworkUri WHERE id = :songId")
    suspend fun updateSongArtwork(songId: Long, artworkUri: String?)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayed = :timestamp WHERE id = :songId")
    suspend fun recordPlay(songId: Long, timestamp: Long)

    @Query("UPDATE songs SET playCount = :playCount, lastPlayed = :lastPlayed WHERE id = :songId")
    suspend fun updatePlayStats(songId: Long, playCount: Int, lastPlayed: Long)

    @Query("SELECT * FROM songs WHERE isMissing = 0")
    suspend fun getValidSongsOnce(): List<SongEntity>

    @Query("UPDATE songs SET isMissing = 1 WHERE path = :path")
    suspend fun markMissing(path: String)

    @Query("DELETE FROM songs WHERE isMissing = 1")
    suspend fun deleteMissingSongs()

    @Query("DELETE FROM songs WHERE path LIKE 'sample://%' OR (path LIKE 'sample_%' AND id < 0)")
    suspend fun deleteSampleSongs()

    @Query("DELETE FROM songs WHERE (path LIKE 'sample://%' OR (path LIKE 'sample_%' AND id < 0)) AND isFavorite = 0 AND playCount = 0")
    suspend fun deleteUnusedSampleSongs()

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteSong(id: Long)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteSongsByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM songs WHERE isMissing = 0")
    fun getSongCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongCountOnce(): Int

    @Query("SELECT * FROM songs")
    suspend fun getAllSongsOnce(): List<SongEntity>
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY isExcluded ASC, name ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY isExcluded ASC, name ASC")
    suspend fun getAllFoldersOnce(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE isExcluded = 0")
    suspend fun getIncludedFoldersList(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE isExcluded = 1")
    suspend fun getExcludedFoldersList(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE path = :path")
    suspend fun getFolderByPath(path: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFoldersBatch(folders: List<FolderEntity>)

    @Query("DELETE FROM folders WHERE path = :path")
    suspend fun deleteFolder(path: String)

    @Query("DELETE FROM folders")
    suspend fun clearAllFolders()

    @Query("SELECT COUNT(*) FROM folders")
    suspend fun getFolderCountOnce(): Int
}

data class PlaylistWithDetails(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val isPinned: Boolean,
    val artworkUri: String?,
    val songCount: Int,
    val totalDurationMs: Long
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY isPinned DESC, name ASC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("""
        SELECT 
            p.id as id,
            p.name as name,
            p.createdAt as createdAt,
            p.isPinned as isPinned,
            p.artworkUri as artworkUri,
            COUNT(ps.songId) as songCount,
            COALESCE(SUM(s.durationMs), 0) as totalDurationMs
        FROM playlists p
        LEFT JOIN playlist_songs ps ON p.id = ps.playlistId
        LEFT JOIN songs s ON ps.songId = s.id
        GROUP BY p.id
        ORDER BY p.isPinned DESC, p.name ASC
    """)
    fun getAllPlaylistsWithDetails(): Flow<List<PlaylistWithDetails>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(crossRef: PlaylistSongCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongs(crossRefs: List<PlaylistSongCrossRef>)

    @Transaction
    suspend fun updatePlaylistOrder(playlistId: Long, crossRefs: List<PlaylistSongCrossRef>) {
        clearPlaylistSongs(playlistId)
        insertPlaylistSongs(crossRefs)
    }

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun deletePlaylistSong(playlistId: Long, songId: Long)

    @Query("DELETE FROM playlist_songs WHERE songId = :songId")
    suspend fun removeSongFromAllPlaylists(songId: Long)

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun isSongInPlaylist(playlistId: Long, songId: Long): Int

    @Query("""
        SELECT p.* FROM playlists p 
        INNER JOIN playlist_songs ps ON p.id = ps.playlistId 
        WHERE ps.songId = :songId 
        ORDER BY p.isPinned DESC, p.name ASC
    """)
    suspend fun getPlaylistsContainingSong(songId: Long): List<PlaylistEntity>

    @Query("""
        SELECT s.* FROM songs s 
        INNER JOIN playlist_songs ps ON s.id = ps.songId 
        WHERE ps.playlistId = :playlistId 
        ORDER BY ps.position ASC
    """)
    fun getPlaylistSongs(playlistId: Long): Flow<List<SongEntity>>

    @Query("""
        SELECT s.* FROM songs s 
        INNER JOIN playlist_songs ps ON s.id = ps.songId 
        WHERE ps.playlistId = :playlistId 
        ORDER BY ps.position ASC
    """)
    suspend fun getPlaylistSongsOnce(playlistId: Long): List<SongEntity>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getPlaylistSongCount(playlistId: Long): Int

    @Query("SELECT SUM(s.durationMs) FROM playlist_songs ps INNER JOIN songs s ON ps.songId = s.id WHERE ps.playlistId = :playlistId")
    suspend fun getPlaylistTotalDuration(playlistId: Long): Long?

    @Query("UPDATE playlists SET artworkUri = :artworkUri WHERE id = :playlistId")
    suspend fun updatePlaylistArtwork(playlistId: Long, artworkUri: String?)

    @Query("UPDATE playlists SET name = :newName WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, newName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordPlaylistPlay(event: PlaylistPlayEventEntity)

    @Query("""
        SELECT 
            p.id as id,
            p.name as name,
            p.createdAt as createdAt,
            p.isPinned as isPinned,
            p.artworkUri as artworkUri,
            COUNT(DISTINCT ps.songId) as songCount,
            COALESCE(SUM(s.durationMs), 0) as totalDurationMs
        FROM playlists p
        INNER JOIN (
            SELECT playlistId, COUNT(id) as playCount
            FROM playlist_play_events
            GROUP BY playlistId
        ) pe ON p.id = pe.playlistId
        LEFT JOIN playlist_songs ps ON p.id = ps.playlistId
        LEFT JOIN songs s ON ps.songId = s.id
        GROUP BY p.id
        ORDER BY pe.playCount DESC, p.name ASC
        LIMIT :limit
    """)
    fun getMostPlayedPlaylistsWithDetails(limit: Int = 10): Flow<List<PlaylistWithDetails>>

    @Query("DELETE FROM playlist_play_events WHERE id NOT IN (SELECT id FROM playlist_play_events ORDER BY timestamp DESC LIMIT :maxRecords)")
    suspend fun prunePlaylistPlayEventsKeepMostRecent(maxRecords: Int = 25000): Int

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun getPlaylistCountOnce(): Int

    @Query("SELECT COUNT(*) FROM playlist_songs")
    suspend fun getPlaylistSongsCrossRefCountOnce(): Int

    @Query("SELECT * FROM playlists")
    suspend fun getAllPlaylistsOnce(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_songs ORDER BY playlistId ASC, position ASC")
    suspend fun getAllPlaylistSongsOnce(): List<PlaylistSongCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylists()

    @Query("DELETE FROM playlist_songs")
    suspend fun clearAllPlaylistSongs()

    @Query("DELETE FROM playlist_play_events")
    suspend fun clearAllPlaylistPlayEvents()
}

data class RecentlyPlayedHistoryItem(
    @Embedded val song: SongEntity,
    @ColumnInfo(name = "historyId") val historyId: Long,
    @ColumnInfo(name = "historyStartedAt") val historyStartedAt: Long
)

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaybackHistory(history: PlaybackHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaybackHistoryBatch(histories: List<PlaybackHistoryEntity>)

    @Query("SELECT * FROM playback_history WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getHistoryBySessionId(sessionId: String): PlaybackHistoryEntity?

    @Query("""
        SELECT 
            s.*,
            h.id AS historyId,
            h.startedAt AS historyStartedAt
        FROM playback_history h
        INNER JOIN songs s ON h.trackId = s.id
        WHERE s.isMissing = 0 AND h.isQualified = 1
        ORDER BY h.startedAt DESC
        LIMIT :limit
    """)
    fun getRecentlyPlayedHistory(limit: Int = 300): Flow<List<RecentlyPlayedHistoryItem>>

    @Query("SELECT SUM(listenedDurationMs) FROM playback_history WHERE startedAt >= :startTime AND startedAt < :endTime")
    fun getTotalDurationPlayedBetween(startTime: Long, endTime: Long = 9223372036854775807L): Flow<Long?>

    @Query("SELECT COUNT(*) FROM playback_history WHERE startedAt >= :startTime AND startedAt < :endTime AND isQualified = 1")
    fun getTrackCountBetween(startTime: Long, endTime: Long = 9223372036854775807L): Flow<Int>

    @Query("SELECT COUNT(DISTINCT trackId) FROM playback_history WHERE startedAt >= :startTime AND startedAt < :endTime AND isQualified = 1")
    fun getUniqueTrackCountBetween(startTime: Long, endTime: Long = 9223372036854775807L): Flow<Int>

    @Query("""
        SELECT s.id, s.title, s.artist, s.album, s.genre, s.durationMs, s.path, s.contentUriString, s.artworkUri, s.albumId, s.artistId, s.trackNumber, s.year, s.dateAdded, s.lastPlayed, h.pCount as playCount, s.isFavorite, s.bitrate, s.format, s.isMissing, s.isCorrupted, s.isManuallyEdited
        FROM songs s 
        INNER JOIN (
            SELECT trackId, COUNT(*) as pCount, SUM(listenedDurationMs) as pDuration 
            FROM playback_history 
            WHERE startedAt >= :startTime AND startedAt < :endTime AND isQualified = 1
            GROUP BY trackId 
            ORDER BY pCount DESC, pDuration DESC 
            LIMIT :limit
        ) h ON s.id = h.trackId
        WHERE s.isMissing = 0
        ORDER BY h.pCount DESC, h.pDuration DESC
    """)
    fun getMostPlayedSongsBetween(startTime: Long, endTime: Long = 9223372036854775807L, limit: Int = 20): Flow<List<SongEntity>>

    @Query("""
        SELECT s.artist as name, COUNT(h.id) as playCount, SUM(h.listenedDurationMs) as totalDurationMs
        FROM playback_history h
        INNER JOIN songs s ON h.trackId = s.id
        WHERE s.isMissing = 0 AND h.startedAt >= :startTime AND h.startedAt < :endTime AND h.isQualified = 1
        GROUP BY s.artist
        ORDER BY playCount DESC, totalDurationMs DESC
        LIMIT :limit
    """)
    fun getTopArtistsBetween(startTime: Long, endTime: Long = 9223372036854775807L, limit: Int = 25): Flow<List<ArtistStatEntity>>

    @Query("""
        SELECT s.album as name, s.artist as artist, MAX(s.artworkUri) as artworkUri, COUNT(h.id) as playCount
        FROM playback_history h
        INNER JOIN songs s ON h.trackId = s.id
        WHERE s.isMissing = 0 AND h.startedAt >= :startTime AND h.startedAt < :endTime AND h.isQualified = 1
        GROUP BY s.album
        ORDER BY playCount DESC, SUM(h.listenedDurationMs) DESC
        LIMIT :limit
    """)
    fun getTopAlbumsBetween(startTime: Long, endTime: Long = 9223372036854775807L, limit: Int = 25): Flow<List<AlbumStatEntity>>

    @Query("""
        SELECT 
            p.id as id,
            p.name as name,
            p.createdAt as createdAt,
            p.isPinned as isPinned,
            p.artworkUri as artworkUri,
            COUNT(DISTINCT ps.songId) as songCount,
            COALESCE(SUM(s.durationMs), 0) as totalDurationMs
        FROM playlists p
        INNER JOIN (
            SELECT playlistId, COUNT(id) as playCount
            FROM playlist_play_events
            WHERE timestamp >= :startTime AND timestamp < :endTime
            GROUP BY playlistId
        ) pe ON p.id = pe.playlistId
        LEFT JOIN playlist_songs ps ON p.id = ps.playlistId
        LEFT JOIN songs s ON ps.songId = s.id
        GROUP BY p.id
        ORDER BY pe.playCount DESC, p.name ASC
        LIMIT :limit
    """)
    fun getMostPlayedPlaylistsBetween(startTime: Long, endTime: Long = 9223372036854775807L, limit: Int = 25): Flow<List<PlaylistWithDetails>>

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()

    @Query("DELETE FROM playback_history WHERE isQualified = 0 AND startedAt < :cutoffTime")
    suspend fun pruneUnqualifiedHistoryOlderThan(cutoffTime: Long): Int

    @Query("DELETE FROM playback_history WHERE id NOT IN (SELECT id FROM playback_history ORDER BY startedAt DESC LIMIT :maxRecords)")
    suspend fun pruneHistoryKeepMostRecent(maxRecords: Int = 50000): Int

    @Query("SELECT COUNT(*) FROM playback_history")
    suspend fun getHistoryCountOnce(): Int

    @Query("SELECT * FROM playback_history ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getHistoryBatchOnce(limit: Int = 1000): List<PlaybackHistoryEntity>

    @Query("SELECT * FROM playback_history WHERE startedAt >= :startTime ORDER BY startedAt ASC")
    fun getPlaybackHistorySince(startTime: Long): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE startedAt >= :startTime AND startedAt < :endTime ORDER BY startedAt ASC")
    fun getPlaybackHistoryBetween(startTime: Long, endTime: Long = 9223372036854775807L): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE startedAt >= :startTime AND startedAt < :endTime ORDER BY startedAt ASC")
    suspend fun getPlaybackHistoryBetweenOnce(startTime: Long, endTime: Long = 9223372036854775807L): List<PlaybackHistoryEntity>

    @Query("UPDATE playback_history SET trackId = :trackId WHERE sessionId = :sessionId")
    suspend fun updateTrackIdBySessionId(sessionId: String, trackId: Long)

    @Query("UPDATE playback_history SET trackId = :trackId WHERE id = :historyId")
    suspend fun updateTrackIdById(historyId: Long, trackId: Long)

    @Query("SELECT * FROM playback_history WHERE trackId = 0")
    suspend fun getUnmatchedHistory(): List<PlaybackHistoryEntity>

    @Query("SELECT * FROM playback_history ORDER BY startedAt DESC")
    suspend fun getAllHistoryOnce(): List<PlaybackHistoryEntity>

    @Query("""
        SELECT trackId, COUNT(*) as pCount, MAX(startedAt) as maxStartedAt 
        FROM playback_history 
        WHERE trackId > 0 AND isQualified = 1 
        GROUP BY trackId
    """)
    suspend fun getAggregateSongPlayStats(): List<SongPlayStatAggregation>
}

data class SongPlayStatAggregation(
    val trackId: Long,
    val pCount: Int,
    val maxStartedAt: Long
)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun getLyricsForSong(songId: Long): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLyrics(lyrics: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE songId = :songId")
    suspend fun deleteLyrics(songId: Long)

    @Query("DELETE FROM lyrics")
    suspend fun clearLyricsCache()

    @Query("DELETE FROM lyrics WHERE songId NOT IN (SELECT id FROM songs)")
    suspend fun pruneOrphanLyrics(): Int

    @Query("DELETE FROM lyrics WHERE songId NOT IN (SELECT songId FROM lyrics ORDER BY songId DESC LIMIT :maxRecords)")
    suspend fun pruneLyricsKeepMostRecent(maxRecords: Int = 15000): Int

    @Query("SELECT COUNT(*) FROM lyrics")
    suspend fun getLyricsCountOnce(): Int
}

@Dao
interface RecentSearchDao {
    @Query("SELECT query FROM recent_searches ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSearches(limit: Int = 50): Flow<List<String>>

    @Query("SELECT * FROM recent_searches ORDER BY timestamp DESC")
    suspend fun getAllRecentSearchesOnce(): List<RecentSearchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(search: RecentSearchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchesBatch(searches: List<RecentSearchEntity>)

    @Query("DELETE FROM recent_searches WHERE query = :query")
    suspend fun deleteSearch(query: String)

    @Query("DELETE FROM recent_searches WHERE query NOT IN (SELECT query FROM recent_searches ORDER BY timestamp DESC LIMIT :maxRecords)")
    suspend fun pruneRecentSearchesKeepMostRecent(maxRecords: Int = 100): Int

    @Query("SELECT COUNT(*) FROM recent_searches")
    suspend fun getRecentSearchCountOnce(): Int

    @Query("DELETE FROM recent_searches")
    suspend fun clearAll()
}

@Dao
interface SmartPlaylistDao {
    @Query("SELECT * FROM smart_playlists ORDER BY createdAt DESC")
    fun getAllSmartPlaylists(): Flow<List<SmartPlaylistEntity>>

    @Query("SELECT * FROM smart_playlists")
    suspend fun getAllSmartPlaylistsOnce(): List<SmartPlaylistEntity>

    @Query("SELECT COUNT(*) FROM smart_playlists")
    suspend fun getSmartPlaylistCountOnce(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSmartPlaylist(playlist: SmartPlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSmartPlaylists(playlists: List<SmartPlaylistEntity>)

    @Query("DELETE FROM smart_playlists WHERE id = :id")
    suspend fun deleteSmartPlaylist(id: Long)

    @Query("DELETE FROM smart_playlists")
    suspend fun clearAllSmartPlaylists()
}