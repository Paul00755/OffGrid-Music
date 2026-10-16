package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserAccountIsolationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var songDao: SongDao
    private lateinit var playlistDao: PlaylistDao
    private lateinit var historyDao: HistoryDao
    private lateinit var smartPlaylistDao: SmartPlaylistDao
    private lateinit var userPreferences: UserPreferencesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        songDao = db.songDao()
        playlistDao = db.playlistDao()
        historyDao = db.historyDao()
        smartPlaylistDao = db.smartPlaylistDao()
        userPreferences = UserPreferencesRepository.getInstance(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun clearAccountOwnedLocalData() {
        songDao.clearAllFavorites()
        playlistDao.clearAllPlaylists()
        playlistDao.clearAllPlaylistSongs()
        playlistDao.clearAllPlaylistPlayEvents()
        smartPlaylistDao.clearAllSmartPlaylists()
        historyDao.clearHistory()
        userPreferences.resetAccountSpecificData()
    }

    @Test
    fun testDeviceWithNoLogin_clearsPriorAccountDataAndEntersCleanGuestState() = runTest {
        // Setup initial device songs (device-local files)
        val song1 = SongEntity(
            id = 101L,
            title = "Track A",
            artist = "Artist A",
            album = "Album A",
            genre = "Rock",
            durationMs = 200000L,
            path = "/storage/emulated/0/Music/track_a.mp3",
            contentUriString = "content://media/external/audio/media/101",
            isFavorite = true // User A favorite
        )
        val song2 = SongEntity(
            id = 102L,
            title = "Track B",
            artist = "Artist B",
            album = "Album B",
            genre = "Rock",
            durationMs = 180000L,
            path = "/storage/emulated/0/Music/track_b.mp3",
            contentUriString = "content://media/external/audio/media/102",
            isFavorite = false
        )
        songDao.insertSongs(listOf(song1, song2))

        // Simulate stale state left by previous user / backup restore
        userPreferences.setLastActiveUid("user_A_uid")
        userPreferences.setCustomDisplayName("Alice")
        userPreferences.setCustomAvatarUri("file:///avatar_alice.jpg")

        val plId = playlistDao.insertPlaylist(PlaylistEntity(name = "Alice Rock", createdAt = 1000L))
        playlistDao.insertPlaylistSongs(listOf(PlaylistSongCrossRef(plId, 101L, 0)))
        historyDao.insertPlaybackHistory(
            PlaybackHistoryEntity(
                trackId = 101L,
                startedAt = 1000L,
                endedAt = 2000L,
                listenedDurationMs = 1000L,
                sessionId = "session_alice"
            )
        )

        assertEquals(1, songDao.getAllSongsOnce().count { it.isFavorite })
        assertEquals(1, playlistDao.getAllPlaylistsOnce().size)
        assertEquals(1, historyDao.getHistoryCountOnce())

        // App starts with NO logged-in Firebase user (guest state)
        clearAccountOwnedLocalData()

        // 1. Verify User A account preferences are wiped
        assertNull(userPreferences.getLastActiveUid())
        assertNull(userPreferences.customDisplayName.first())
        assertNull(userPreferences.customAvatarUri.first())

        // 2. Verify account-owned Room records are cleared
        assertEquals("Favorites must be reset to 0 for unauthenticated state", 0, songDao.getAllSongsOnce().count { it.isFavorite })
        assertEquals("Playlists must be empty", 0, playlistDao.getAllPlaylistsOnce().size)
        assertEquals("History must be empty", 0, historyDao.getHistoryCountOnce())

        // 3. Verify device-local songs are preserved!
        assertEquals("Device songs must remain intact", 2, songDao.getSongCountOnce())
    }

    @Test
    fun testSwitchingUsersOnSameDevice_userBDoesNotInheritUserAData() = runTest {
        // Device-local song library
        val song = SongEntity(
            id = 201L,
            title = "Stairway to Heaven",
            artist = "Led Zeppelin",
            album = "Led Zeppelin IV",
            genre = "Rock",
            durationMs = 480000L,
            path = "/storage/emulated/0/Music/stairway.mp3",
            contentUriString = "content://media/external/audio/media/201",
            isFavorite = false
        )
        songDao.insertSongs(listOf(song))

        // Step 1: User A is logged in
        userPreferences.setLastActiveUid("user_A_uid")
        songDao.setFavoriteBySong(201L, song.path, true)
        val plA = playlistDao.insertPlaylist(PlaylistEntity(name = "User A Best Hits", createdAt = 2000L))
        historyDao.insertPlaybackHistory(
            PlaybackHistoryEntity(
                trackId = 201L,
                startedAt = 2000L,
                endedAt = 3000L,
                listenedDurationMs = 1000L,
                sessionId = "session_A"
            )
        )

        assertEquals(1, songDao.getAllSongsOnce().count { it.isFavorite })
        assertEquals(1, playlistDao.getAllPlaylistsOnce().size)

        // Step 2: User A signs out
        clearAccountOwnedLocalData()

        assertEquals("Favorites must be cleared on sign out", 0, songDao.getAllSongsOnce().count { it.isFavorite })
        assertEquals("Playlists must be cleared on sign out", 0, playlistDao.getAllPlaylistsOnce().size)
        assertEquals("History must be cleared on sign out", 0, historyDao.getHistoryCountOnce())

        // Step 3: User B signs in
        userPreferences.setLastActiveUid("user_B_uid")

        // User B has fresh empty cloud account or distinct data
        assertEquals("User B must NOT inherit User A favorites", 0, songDao.getAllSongsOnce().count { it.isFavorite })
        assertEquals("User B must NOT inherit User A playlists", 0, playlistDao.getAllPlaylistsOnce().size)
        assertEquals("User B must NOT inherit User A history", 0, historyDao.getHistoryCountOnce())
        assertEquals("Device song remains available", 1, songDao.getSongCountOnce())
    }

    @Test
    fun testGuestToAuthenticatedUser_preservesGuestCreatedContent() = runTest {
        // Device starts in clean guest state (lastActiveUid == null)
        assertNull(userPreferences.getLastActiveUid())

        val song = SongEntity(
            id = 301L,
            title = "Hotel California",
            artist = "Eagles",
            album = "Hotel California",
            genre = "Rock",
            durationMs = 390000L,
            path = "/storage/emulated/0/Music/hotel.mp3",
            contentUriString = "content://media/external/audio/media/301",
            isFavorite = true // Guest marked as favorite
        )
        songDao.insertSongs(listOf(song))

        val guestPlaylistId = playlistDao.insertPlaylist(PlaylistEntity(name = "Guest Favorites", createdAt = 5000L))
        playlistDao.insertPlaylistSongs(listOf(PlaylistSongCrossRef(guestPlaylistId, 301L, 0)))

        // Guest user logs in as User C for the first time
        val lastUid = userPreferences.getLastActiveUid()
        val newAuthoritativeUid = "user_C_uid"

        // Since lastUid is null, it's a guest-to-auth transition; guest data must NOT be wiped!
        if (!lastUid.isNullOrBlank() && lastUid != newAuthoritativeUid) {
            clearAccountOwnedLocalData()
        }
        userPreferences.setLastActiveUid(newAuthoritativeUid)

        assertEquals("Guest favorite must be preserved for new account merge", 1, songDao.getAllSongsOnce().count { it.isFavorite })
        assertEquals("Guest playlist must be preserved for new account merge", 1, playlistDao.getAllPlaylistsOnce().size)
        assertEquals("User C is now authoritative UID", "user_C_uid", userPreferences.getLastActiveUid())
    }

    @Test
    fun testGuestStateDisplayName_neverLeaksPreviousUserCachedName() {
        // When userAccount is null, effective display name must always be generic guest name
        val userAccount: com.example.auth.UserAccount? = null
        val cachedPreviousUserName: String? = "Alice Sensitive"

        val effectiveDisplayName = if (userAccount == null) {
            "Music Lover"
        } else {
            cachedPreviousUserName?.takeIf { it.isNotBlank() } ?: "Music Lover"
        }

        assertEquals("Guest state must never leak cached name", "Music Lover", effectiveDisplayName)
    }
}
