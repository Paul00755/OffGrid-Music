package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.Album
import com.example.data.model.Artist
import com.example.data.model.FolderInfo
import com.example.data.model.Lyrics
import com.example.data.model.Playlist
import com.example.data.model.Song

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["genre"])
    ]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val durationMs: Long,
    val path: String,
    val contentUriString: String,
    val artworkUri: String? = null,
    val albumId: Long = 0L,
    val artistId: Long = 0L,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastPlayed: Long = 0L,
    val playCount: Int = 0,
    val isFavorite: Boolean = false,
    val bitrate: Int = 0,
    val format: String = "MP3",
    val isMissing: Boolean = false,
    val isCorrupted: Boolean = false
) {
    fun toDomainModel(): Song = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        durationMs = durationMs,
        path = path,
        contentUriString = contentUriString,
        artworkUri = artworkUri,
        albumId = albumId,
        artistId = artistId,
        trackNumber = trackNumber,
        year = year,
        dateAdded = dateAdded,
        lastPlayed = lastPlayed,
        playCount = playCount,
        isFavorite = isFavorite,
        bitrate = bitrate,
        format = format,
        isMissing = isMissing,
        isCorrupted = isCorrupted
    )

    companion object {
        fun fromDomainModel(song: Song): SongEntity = SongEntity(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            genre = song.genre,
            durationMs = song.durationMs,
            path = song.path,
            contentUriString = song.contentUriString,
            artworkUri = song.artworkUri,
            albumId = song.albumId,
            artistId = song.artistId,
            trackNumber = song.trackNumber,
            year = song.year,
            dateAdded = song.dateAdded,
            lastPlayed = song.lastPlayed,
            playCount = song.playCount,
            isFavorite = song.isFavorite,
            bitrate = song.bitrate,
            format = song.format,
            isMissing = song.isMissing,
            isCorrupted = song.isCorrupted
        )
    }
}

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val path: String,
    val name: String,
    val uriString: String,
    val isExcluded: Boolean = false,
    val songCount: Int = 0,
    val lastScanTime: Long = System.currentTimeMillis(),
    val isAccessible: Boolean = true
) {
    fun toDomainModel(): FolderInfo = FolderInfo(
        path = path,
        name = name,
        uriString = uriString,
        isExcluded = isExcluded,
        songCount = songCount,
        lastScanTime = lastScanTime,
        isAccessible = isAccessible
    )
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val artworkUri: String? = null
) {
    fun toDomainModel(songCount: Int, totalDurationMs: Long = 0L): Playlist = Playlist(
        id = id,
        name = name,
        songCount = songCount,
        totalDurationMs = totalDurationMs,
        createdAt = createdAt,
        isPinned = isPinned,
        artworkUri = artworkUri
    )
}

@Entity(
    tableName = "playlist_play_events",
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["timestamp"])
    ]
)
data class PlaylistPlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val playlistId: Long,
    val timestamp: Long = System.currentTimeMillis()
)

data class ArtistStatEntity(
    val name: String,
    val playCount: Int,
    val totalDurationMs: Long
)

data class AlbumStatEntity(
    val name: String,
    val artist: String,
    val artworkUri: String?,
    val playCount: Int
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    val position: Int
)

@Entity(
    tableName = "playback_history",
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["startedAt"]),
        Index(value = ["sessionId"], unique = true),
        Index(value = ["isQualified", "startedAt"])
    ]
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: Long,
    val startedAt: Long,
    val endedAt: Long = 0L,
    val listenedDurationMs: Long = 0L,
    val sourceContext: String = "UNKNOWN",
    val completionPercentage: Float = 0f,
    val sessionId: String = "",
    val isQualified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val songId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val durationPlayedMs: Long = 0L,
    val completed: Boolean = false
)

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val songId: Long,
    val plainLyrics: String? = null,
    val syncedLyricsJson: String? = null,
    val isCached: Boolean = true,
    val source: String = "local"
) {
    fun toDomainModel(): Lyrics = Lyrics(
        songId = songId,
        plainLyrics = plainLyrics,
        syncedLyricsJson = syncedLyricsJson,
        isCached = isCached,
        source = source
    )
}

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "smart_playlists")
data class SmartPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val genreFilter: String = "ALL",
    val minPlayCount: Int = 0,
    val timeframeDays: Int = 0,
    val maxTrackLimit: Int = 0,
    val sortCriteria: String = "RECENTLY_ADDED",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomainModel(): com.example.data.model.SmartPlaylist = com.example.data.model.SmartPlaylist(
        id = id,
        name = name,
        genreFilter = genreFilter,
        minPlayCount = minPlayCount,
        timeframeDays = timeframeDays,
        maxTrackLimit = maxTrackLimit,
        sortCriteria = sortCriteria,
        createdAt = createdAt
    )

    companion object {
        fun fromDomainModel(smartPlaylist: com.example.data.model.SmartPlaylist): SmartPlaylistEntity = SmartPlaylistEntity(
            id = smartPlaylist.id,
            name = smartPlaylist.name,
            genreFilter = smartPlaylist.genreFilter,
            minPlayCount = smartPlaylist.minPlayCount,
            timeframeDays = smartPlaylist.timeframeDays,
            maxTrackLimit = smartPlaylist.maxTrackLimit,
            sortCriteria = smartPlaylist.sortCriteria,
            createdAt = smartPlaylist.createdAt
        )
    }
}

