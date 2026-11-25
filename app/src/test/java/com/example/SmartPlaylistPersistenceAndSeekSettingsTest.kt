package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SmartPlaylistEntity
import com.example.data.local.SmartPlaylistMetadata
import com.example.data.local.SmartPlaylistMetadataStore
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.SmartPlaylist
import com.example.data.model.Song
import com.example.data.sync.SongIdentityHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmartPlaylistPersistenceAndSeekSettingsTest {

    private lateinit var context: Context
    private lateinit var metadataStore: SmartPlaylistMetadataStore
    private lateinit var preferences: UserPreferencesRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        metadataStore = SmartPlaylistMetadataStore.getInstance(context)
        metadataStore.clearAll()
        preferences = UserPreferencesRepository.getInstance(context)
    }

    @Test
    fun testSmartPlaylistMetadataCloudAndRestartRoundTripPreservesOrder() {
        val s1 = Song(id = 1L, title = "Alpha", artist = "Artist 1", album = "Alb", durationMs = 210_000L, path = "/a.mp3", contentUriString = "content://1")
        val s2 = Song(id = 2L, title = "Beta", artist = "Artist 2", album = "Alb", durationMs = 220_000L, path = "/b.mp3", contentUriString = "content://2")
        val sig1 = SongIdentityHelper.computeSignature(s1)
        val sig2 = SongIdentityHelper.computeSignature(s2)

        val spA = SmartPlaylist(
            id = 1L,
            name = "Late Night Vibes",
            genreFilter = "Jazz, Lo-Fi",
            minPlayCount = 2,
            timeframeDays = 30,
            maxTrackLimit = 25,
            sortCriteria = "MOST_PLAYED",
            createdAt = 1_700_000_000_000L,
            artistFilter = "Artist 1, Artist 2",
            favoritesOnly = true,
            durationFilter = "MEDIUM",
            customTrackOrder = listOf(sig2, sig1),
            displayOrder = 1
        )
        val spB = SmartPlaylist(
            id = 2L,
            name = "Workout Boost",
            genreFilter = "Rock",
            createdAt = 1_700_000_005_000L,
            displayOrder = 0
        )

        metadataStore.saveFromSmartPlaylist(spA)
        metadataStore.saveFromSmartPlaylist(spB)

        val cloudA = metadataStore.toCloudSmartPlaylist(SmartPlaylistEntity.fromDomainModel(spA))
        val cloudB = metadataStore.toCloudSmartPlaylist(SmartPlaylistEntity.fromDomainModel(spB))

        // Simulate wipe / restore from cloud backup
        metadataStore.clearAll()
        val restoredEntities = metadataStore.restoreFromCloud(listOf(cloudA, cloudB), replaceAll = true)
        assertEquals(2, restoredEntities.size)

        val restoredMerged = restoredEntities
            .map { metadataStore.applyMetadata(it) }
            .sortedBy { it.displayOrder }

        assertEquals("Workout Boost", restoredMerged[0].name)
        assertEquals(0, restoredMerged[0].displayOrder)

        val restoredA = restoredMerged[1]
        assertEquals("Late Night Vibes", restoredA.name)
        assertEquals(1, restoredA.displayOrder)
        assertEquals(listOf(sig2, sig1), restoredA.customTrackOrder)
        assertTrue(restoredA.favoritesOnly)
        assertEquals("MEDIUM", restoredA.durationFilter)
        assertEquals("Artist 1, Artist 2", restoredA.artistFilter)
        assertEquals(2, restoredA.minPlayCount)
        assertEquals(30, restoredA.timeframeDays)

        // Verify track order is preserved after restore
        val filtered = restoredA.copy(
            genreFilter = "ALL",
            minPlayCount = 0,
            timeframeDays = 0,
            favoritesOnly = false
        ).filterSongs(listOf(s1, s2))
        assertEquals(listOf(2L, 1L), filtered.map { it.id })
    }

    @Test
    fun testNowPlayingSeekButtonPreferencesDefaultsAndIndependentIntervals() = runTest {
        preferences.resetToDefaultSettings()
        assertFalse(preferences.showNowPlayingSeekButtons.first())
        assertEquals(10, preferences.nowPlayingBackwardSeconds.first())
        assertEquals(10, preferences.nowPlayingForwardSeconds.first())

        preferences.setShowNowPlayingSeekButtons(true)
        preferences.setNowPlayingBackwardSeconds(5)
        preferences.setNowPlayingForwardSeconds(30)

        assertTrue(preferences.showNowPlayingSeekButtons.first())
        assertEquals(5, preferences.nowPlayingBackwardSeconds.first())
        assertEquals(30, preferences.nowPlayingForwardSeconds.first())

        // Verify cloud export / import preserves independent values
        val exported = preferences.exportUserSettings()
        assertTrue(exported.showNowPlayingSeekButtons)
        assertEquals(5, exported.nowPlayingBackwardSeconds)
        assertEquals(30, exported.nowPlayingForwardSeconds)

        preferences.resetToDefaultSettings()
        assertFalse(preferences.showNowPlayingSeekButtons.first())

        assertTrue(preferences.importUserSettings(exported, checkTimestamp = false))
        assertTrue(preferences.showNowPlayingSeekButtons.first())
        assertEquals(5, preferences.nowPlayingBackwardSeconds.first())
        assertEquals(30, preferences.nowPlayingForwardSeconds.first())

        // Verify 15s option is accepted and preserved independently
        preferences.setNowPlayingBackwardSeconds(15)
        preferences.setNowPlayingForwardSeconds(15)
        assertEquals(15, preferences.nowPlayingBackwardSeconds.first())
        assertEquals(15, preferences.nowPlayingForwardSeconds.first())
        assertEquals(15, preferences.getNowPlayingBackwardSecondsSync())
        assertEquals(15, preferences.getNowPlayingForwardSecondsSync())
    }

    @Test
    fun testCustomTrackOrderPersistsAndAppendsNewMatches() {
        val songs = listOf(
            Song(id = 1L, title = "Rock Anthem", artist = "Band A", album = "Album 1", genre = "Rock", durationMs = 200_000L, path = "/m/1.mp3", contentUriString = "content://1"),
            Song(id = 2L, title = "Pop Hit", artist = "Singer B", album = "Album 2", genre = "Pop", durationMs = 180_000L, path = "/m/2.mp3", contentUriString = "content://2"),
            Song(id = 3L, title = "Jazz Solo", artist = "Artist C", album = "Album 3", genre = "Jazz", durationMs = 300_000L, path = "/m/3.mp3", contentUriString = "content://3"),
            Song(id = 4L, title = "Heavy Rock", artist = "Band A", album = "Album 4", genre = "Rock", durationMs = 240_000L, path = "/m/4.mp3", contentUriString = "content://4")
        )
        val sig1 = SongIdentityHelper.computeSignature(songs[0])
        val sig2 = SongIdentityHelper.computeSignature(songs[1])
        val sig3 = SongIdentityHelper.computeSignature(songs[2])

        val smart = SmartPlaylist(
            name = "Reordered Smart Mix",
            sortCriteria = "TITLE",
            customTrackOrder = listOf(sig3, sig1, sig2)
        )
        val ordered = smart.filterSongs(songs)
        assertEquals(listOf(3L, 1L, 2L, 4L), ordered.map { it.id })
        assertTrue(smart.hasCustomTrackOrder)
        assertTrue(smart.getCriteriaBadges().contains("Custom Order"))
    }
}
