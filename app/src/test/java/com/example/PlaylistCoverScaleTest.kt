package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.example.data.local.AppDatabase
import com.example.data.local.SongEntity
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.ui.components.PlaylistCover
import com.example.ui.components.PlaylistCoverCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaylistCoverScaleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun automaticCoverShowsUniqueArtworkAndContinuesPastDuplicates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val colors = listOf(
            AndroidColor.RED,
            AndroidColor.GREEN,
            AndroidColor.BLUE,
            AndroidColor.YELLOW
        )
        val artworkUris = colors.mapIndexed { index, color -> createArtwork(context, index, color) }
        val songIds = (System.nanoTime() and 0x0000FFFFFFFFFFFFL) + 100_000L
        val playlistSongs = listOf(
            testSong(songIds + 1, artworkUris[0]),
            testSong(songIds + 2, artworkUris[0]), // Duplicate artwork must not block later unique covers.
            testSong(songIds + 3, artworkUris[1]),
            testSong(songIds + 4, artworkUris[2]),
            testSong(songIds + 5, artworkUris[3])
        )

        composeTestRule.setContent {
            Box(Modifier.size(120.dp)) {
                PlaylistCover(
                    playlist = Playlist(id = songIds, name = "Artwork Regression"),
                    playlistSongs = playlistSongs,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 0
                )
            }
        }

        composeTestRule.waitUntil(10_000L) {
            PlaylistCoverCache.getCollageUris(songIds)?.size == 4 &&
                (1..4).all { index ->
                    composeTestRule.onAllNodesWithContentDescription("Artwork Regression cover artwork $index")
                        .fetchSemanticsNodes().isNotEmpty()
                }
        }
        val selectedUris = PlaylistCoverCache.getCollageUris(songIds)
        assertEquals("The first four distinct artwork files should be selected in playlist order", artworkUris, selectedUris)
        for (index in 1..4) {
            composeTestRule.onNodeWithContentDescription("Artwork Regression cover artwork $index").assertIsDisplayed()
        }
    }

    @Test
    fun roomLibraryQueriesReturnAllTracksAtThreeFiveAndTenThousand() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            var inserted = 0
            for (targetCount in listOf(3_000, 5_000, 10_000)) {
                val batch = (inserted + 1..targetCount).map { index ->
                    SongEntity(
                        title = "Track ${index.toString().padStart(5, '0')}",
                        artist = "Artist ${index % 100}",
                        album = "Album ${index % 500}",
                        genre = "Test",
                        durationMs = 180_000L,
                        path = "/scale-test/track-$index.mp3",
                        contentUriString = "content://scale-test/audio/$index"
                    )
                }
                batch.chunked(500).forEach { db.songDao().insertSongs(it) }
                inserted = targetCount

                val startedAt = System.nanoTime()
                val entities = db.songDao().getAllSongs().first()
                val songs = withContext(Dispatchers.Default) { entities.map(SongEntity::toDomainModel) }
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                assertEquals("Library query lost tracks at $targetCount", targetCount, songs.size)
                println("LIBRARY_SCALE rows=$targetCount room_flow_plus_mapping_ms=$elapsedMs")
            }
        } finally {
            db.close()
        }
    }

    private fun testSong(id: Long, artworkUri: String) = Song(
        id = id,
        title = "Track $id",
        artist = "Test Artist",
        album = "Test Album",
        durationMs = 180_000L,
        path = "/playlist-cover-test/track-$id.mp3",
        contentUriString = "content://playlist-cover-test/audio/$id",
        artworkUri = artworkUri
    )

    private fun createArtwork(context: Context, index: Int, color: Int): String {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val file = File(context.cacheDir, "playlist-cover-scale-${System.nanoTime()}-$index.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return android.net.Uri.fromFile(file).toString()
    }
}
