package com.example.ui.screens

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Song
import com.example.jam.JamQueueItem
import com.example.jam.JamSharedLibraryMatch
import com.example.jam.JamTrack
import com.example.jam.sharedTrackId
import com.example.ui.components.AlbumArtView
import com.example.ui.components.ArtworkResolutionCache
import com.example.ui.components.FastScrollbar
import com.example.ui.theme.BentoAccentPinkText
import com.example.ui.theme.BentoBorder
import com.example.ui.theme.BentoCardBorder
import com.example.ui.theme.BentoCardDarkPurple
import com.example.ui.theme.BentoCardPurple
import com.example.ui.theme.BentoCardSurface
import com.example.ui.theme.BentoOnPrimary
import com.example.ui.theme.BentoPrimary
import com.example.ui.theme.BentoTextPrimary
import com.example.ui.theme.BentoTextSecondary
import com.example.util.TagCleaner
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

internal enum class JamLocalShareMode {
    IN_APP_LIBRARY,
    LOCAL_STORAGE
}

internal data class InAppLibraryAudioSelection(
    val song: Song,
    val uri: Uri,
    val track: JamTrack,
    val mimeType: String,
    val sizeBytes: Long
)

private const val MAX_JAM_SHARE_BYTES = 25L * 1024L * 1024L

internal fun prepareInAppSongForLocalShare(
    context: Context,
    song: Song
): Result<InAppLibraryAudioSelection> = runCatching {
    require(!song.isMissing && !song.isCorrupted && song.durationMs > 0L) {
        "This song is unavailable or corrupted."
    }

    val directFile = song.path.takeIf(String::isNotBlank)?.let(::File)
    val canReadDirectFile = directFile != null && directFile.isFile && directFile.canRead() && directFile.length() > 0L

    val contentUri = song.contentUriString.takeIf(String::isNotBlank)?.let(Uri::parse)
    val resolvedUri: Uri
    val resolvedSize: Long

    if (canReadDirectFile && directFile != null) {
        resolvedUri = Uri.fromFile(directFile)
        resolvedSize = directFile.length()
    } else if (contentUri != null) {
        resolvedUri = contentUri
        resolvedSize = resolveContentUriSize(context.contentResolver, contentUri)
    } else {
        throw IllegalArgumentException("This song's audio file could not be accessed.")
    }

    if (resolvedSize !in 4..MAX_JAM_SHARE_BYTES) {
        throw IllegalArgumentException("Audio files must be 25 MB or smaller to share in Jam (this file is ${"%.1f".format(resolvedSize / (1024.0 * 1024.0))} MB).")
    }

    val ext = song.format.lowercase().ifBlank {
        song.path.substringAfterLast('.', "").lowercase()
    }
    val mimeType = when (ext) {
        "mp3", "mpeg" -> "audio/mpeg"
        "m4a", "mp4", "alac" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav", "wave" -> "audio/wav"
        else -> contentUri?.let { context.contentResolver.getType(it) }?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
    }

    val cleanTitle = TagCleaner.cleanTitle(song.displayTitle, "").trim()
        .ifBlank { song.displayTitle.trim() }
        .ifBlank { "Unknown Track" }
        .take(180)
    val cleanArtist = TagCleaner.cleanArtist(song.displayArtist).trim()
        .ifBlank { song.displayArtist.trim() }
        .ifBlank { "Unknown Artist" }
        .take(180)
    val cleanAlbum = TagCleaner.cleanAlbum(song.displayAlbum).trim().take(180)

    val candidate = JamTrack(
        sharedTrackId = "",
        title = cleanTitle,
        artist = cleanArtist,
        album = cleanAlbum,
        durationMs = song.durationMs
    )
    val track = candidate.copy(sharedTrackId = sharedTrackId(candidate))

    InAppLibraryAudioSelection(
        song = song,
        uri = resolvedUri,
        track = track,
        mimeType = mimeType,
        sizeBytes = resolvedSize
    )
}

private fun resolveContentUriSize(resolver: ContentResolver, uri: Uri): Long {
    val queriedSize = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) cursor.getLong(idx) else -1L
            } else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    if (queriedSize > 0L) return queriedSize

    var total = 0L
    resolver.openInputStream(uri)?.use { stream ->
        val buf = ByteArray(8192)
        while (true) {
            val read = stream.read(buf)
            if (read < 0) break
            total += read
            if (total > MAX_JAM_SHARE_BYTES) break
        }
    } ?: throw IllegalArgumentException("This song's audio file couldn't be opened.")
    return total
}

/**
 * Mode selector showing the two Local Sharing options inside Jam:
 * 1) "Share from In-App Library"
 * 2) "Share from Local"
 */
@Composable
internal fun JamLocalShareSourceSelector(
    selectedMode: JamLocalShareMode,
    unmatchedCount: Int,
    onSelectInAppLibrary: () -> Unit,
    onSelectLocalStorage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val isLibrarySelected = selectedMode == JamLocalShareMode.IN_APP_LIBRARY
        val isLocalSelected = selectedMode == JamLocalShareMode.LOCAL_STORAGE

        Surface(
            onClick = onSelectInAppLibrary,
            shape = RoundedCornerShape(16.dp),
            color = if (isLibrarySelected) BentoPrimary else BentoCardPurple.copy(alpha = 0.55f),
            border = BorderStroke(
                width = if (isLibrarySelected) 1.5.dp else 1.dp,
                color = if (isLibrarySelected) BentoPrimary else BentoCardBorder
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("jam_share_from_in_app_library_button")
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = null,
                        tint = if (isLibrarySelected) BentoOnPrimary else BentoPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Share from In-App Library",
                        color = if (isLibrarySelected) BentoOnPrimary else BentoTextPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "$unmatchedCount unmatched tracks",
                    color = if (isLibrarySelected) BentoOnPrimary.copy(alpha = 0.85f) else BentoTextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Surface(
            onClick = onSelectLocalStorage,
            shape = RoundedCornerShape(16.dp),
            color = if (isLocalSelected) BentoPrimary else BentoCardPurple.copy(alpha = 0.55f),
            border = BorderStroke(
                width = if (isLocalSelected) 1.5.dp else 1.dp,
                color = if (isLocalSelected) BentoPrimary else BentoCardBorder
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("jam_share_from_local_button")
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.UploadFile,
                        contentDescription = null,
                        tint = if (isLocalSelected) BentoOnPrimary else BentoPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Share from Local",
                        color = if (isLocalSelected) BentoOnPrimary else BentoTextPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Browse device files",
                    color = if (isLocalSelected) BentoOnPrimary.copy(alpha = 0.85f) else BentoTextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Replicated In-App Library browser embedded directly inside the Jam Local Sharing screen.
 * Shows all songs in the user's in-app library EXCEPT songs already matched in the Jam session,
 * complete with search bar, sort chips, sort direction toggle, category tabs (Songs, Albums,
 * Artists, Folders), Favorites filter, alphabetical section headers, and FastScrollbar.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun JamEmbeddedInAppLibraryBrowser(
    allSongs: List<Song>,
    sharedLibraryMatches: List<JamSharedLibraryMatch>,
    queue: List<JamQueueItem>,
    selectedSongId: Long?,
    onSelectSongToShare: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf(LibraryCategory.SONGS) }
    var selectedSort by rememberSaveable { mutableStateOf(SortOption.TITLE) }
    var sortAscending by rememberSaveable { mutableStateOf(true) }
    var filterFavoritesOnly by rememberSaveable { mutableStateOf(false) }
    var drillDownGroup by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) } // (type, key)

    // Compute the set of matched songs to exclude from the In-App Library browser
    val matchedSongIds = remember(sharedLibraryMatches) {
        sharedLibraryMatches.mapTo(HashSet()) { it.song.id }
    }
    val matchedSongPaths = remember(sharedLibraryMatches) {
        sharedLibraryMatches.mapNotNullTo(HashSet()) { it.song.path.takeIf(String::isNotBlank) }
    }
    val matchedNormalizedTitlesArtists = remember(sharedLibraryMatches) {
        sharedLibraryMatches.mapTo(HashSet()) { match ->
            val t = TagCleaner.cleanTitle(match.song.displayTitle, "").trim().lowercase()
            val a = TagCleaner.cleanArtist(match.song.displayArtist).trim().lowercase()
            "$t|$a"
        }
    }
    val queuedTrackIds = remember(queue) {
        queue.mapTo(HashSet()) { it.track.sharedTrackId }
    }

    // All valid in-app library songs excluding matched songs
    val unmatchedSongs = remember(allSongs, matchedSongIds, matchedSongPaths, matchedNormalizedTitlesArtists) {
        allSongs.filter { song ->
            if (song.isMissing || song.isCorrupted || song.durationMs <= 0L) return@filter false
            if (song.id in matchedSongIds) return@filter false
            if (song.path.isNotBlank() && song.path in matchedSongPaths) return@filter false
            val normKey = "${TagCleaner.cleanTitle(song.displayTitle, "").trim().lowercase()}|${TagCleaner.cleanArtist(song.displayArtist).trim().lowercase()}"
            normKey !in matchedNormalizedTitlesArtists
        }.distinctBy { it.id }
    }

    val processedSongs = remember(
        unmatchedSongs,
        searchQuery,
        selectedSort,
        sortAscending,
        filterFavoritesOnly,
        drillDownGroup
    ) {
        var list = unmatchedSongs
        val activeDrill = drillDownGroup
        if (activeDrill != null) {
            val (groupType, groupKey) = activeDrill
            list = when (groupType) {
                "ALBUM" -> list.filter { it.displayAlbum == groupKey }
                "ARTIST" -> list.filter { it.displayArtist == groupKey }
                "FOLDER" -> list.filter {
                    val folder = it.path.takeIf { p -> p.contains('/') }?.substringBeforeLast('/') ?: "Internal Storage"
                    folder == groupKey
                }
                else -> list
            }
        }

        val trimmedQ = searchQuery.trim()
        if (trimmedQ.isNotBlank()) {
            list = list.filter {
                it.title.contains(trimmedQ, ignoreCase = true) ||
                    it.displayTitle.contains(trimmedQ, ignoreCase = true) ||
                    it.artist.contains(trimmedQ, ignoreCase = true) ||
                    it.displayArtist.contains(trimmedQ, ignoreCase = true) ||
                    it.album.contains(trimmedQ, ignoreCase = true) ||
                    it.displayAlbum.contains(trimmedQ, ignoreCase = true)
            }
        }

        if (filterFavoritesOnly) {
            list = list.filter { it.isFavorite }
        }

        when (selectedSort) {
            SortOption.TITLE -> if (sortAscending) list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }) else list.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.displayTitle })
            SortOption.ARTIST -> if (sortAscending) list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayArtist }) else list.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.displayArtist })
            SortOption.ALBUM -> if (sortAscending) list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayAlbum }) else list.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.displayAlbum })
            SortOption.RECENTLY_ADDED -> if (sortAscending) list.sortedByDescending { it.dateAdded } else list.sortedBy { it.dateAdded }
            SortOption.RECENTLY_PLAYED -> if (sortAscending) list.sortedByDescending { it.lastPlayed } else list.sortedBy { it.lastPlayed }
            SortOption.DURATION -> if (sortAscending) list.sortedBy { it.durationMs } else list.sortedByDescending { it.durationMs }
        }
    }

    val firstVisibleItemIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }

    // Preload artwork thumbnails for smooth scrolling
    LaunchedEffect(processedSongs, selectedCategory, drillDownGroup) {
        if ((selectedCategory == LibraryCategory.SONGS || drillDownGroup != null) && processedSongs.isNotEmpty()) {
            snapshotFlow { firstVisibleItemIndex }
                .distinctUntilChanged()
                .debounce(16L)
                .collectLatest { firstVisible ->
                    ArtworkResolutionCache.preloadWindow(
                        context = context,
                        songs = processedSongs,
                        firstVisibleIndex = firstVisible,
                        windowAhead = 48,
                        windowBehind = 16
                    )
                }
        }
    }

    val isAlphabeticalSort = selectedSort == SortOption.TITLE || selectedSort == SortOption.ALBUM || selectedSort == SortOption.ARTIST

    fun getAlphabetLabel(song: Song): String {
        val target = when (selectedSort) {
            SortOption.TITLE -> song.displayTitle
            SortOption.ALBUM -> song.displayAlbum
            SortOption.ARTIST -> song.displayArtist
            else -> ""
        }.trim()
        val ch = target.firstOrNull()?.uppercaseChar() ?: '#'
        return if (ch in 'A'..'Z') ch.toString() else "#"
    }

    val effectiveShowingSongs = selectedCategory == LibraryCategory.SONGS || drillDownGroup != null

    val groupedAlphabetSongs = remember(processedSongs, isAlphabeticalSort, effectiveShowingSongs, selectedSort) {
        if (effectiveShowingSongs && isAlphabeticalSort) {
            processedSongs.groupBy { getAlphabetLabel(it) }
        } else emptyMap()
    }

    val albumsList = remember(processedSongs, selectedCategory, drillDownGroup) {
        if (selectedCategory == LibraryCategory.ALBUMS && drillDownGroup == null) {
            processedSongs.groupBy { it.displayAlbum }.map { it.key to it.value }
        } else emptyList()
    }

    val artistsList = remember(processedSongs, selectedCategory, drillDownGroup) {
        if (selectedCategory == LibraryCategory.ARTISTS && drillDownGroup == null) {
            processedSongs.groupBy { it.displayArtist }.map { it.key to it.value }
        } else emptyList()
    }

    val foldersList = remember(processedSongs, selectedCategory, drillDownGroup) {
        if (selectedCategory == LibraryCategory.FOLDERS && drillDownGroup == null) {
            processedSongs.groupBy { song ->
                song.path.takeIf { p -> p.contains('/') }?.substringBeforeLast('/') ?: "Internal Storage"
            }.map { it.key to it.value }
        } else emptyList()
    }

    val indexedAlphabetLabels = remember(
        effectiveShowingSongs,
        selectedCategory,
        isAlphabeticalSort,
        groupedAlphabetSongs,
        processedSongs,
        albumsList,
        artistsList
    ) {
        if (!isAlphabeticalSort) {
            emptyList()
        } else if (effectiveShowingSongs) {
            val labels = ArrayList<String?>(processedSongs.size + groupedAlphabetSongs.size)
            if (groupedAlphabetSongs.isNotEmpty()) {
                groupedAlphabetSongs.forEach { (letter, list) ->
                    labels.add(letter)
                    repeat(list.size) { labels.add(letter) }
                }
            } else {
                processedSongs.forEach { s -> labels.add(getAlphabetLabel(s)) }
            }
            labels
        } else {
            when (selectedCategory) {
                LibraryCategory.ALBUMS -> albumsList.map { (albumName, _) ->
                    albumName.trim().firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
                }
                LibraryCategory.ARTISTS -> artistsList.map { (artistName, _) ->
                    artistName.trim().firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
                }
                else -> emptyList()
            }
        }
    }

    val fastScrollAlphabetProvider: ((Int) -> String?)? = remember(isAlphabeticalSort, indexedAlphabetLabels) {
        if (!isAlphabeticalSort || indexedAlphabetLabels.isEmpty()) null
        else { idx: Int -> indexedAlphabetLabels.getOrNull(idx) }
    }

    val totalScrollItems = when {
        effectiveShowingSongs -> if (groupedAlphabetSongs.isNotEmpty()) processedSongs.size + groupedAlphabetSongs.size else processedSongs.size
        selectedCategory == LibraryCategory.ALBUMS -> albumsList.size
        selectedCategory == LibraryCategory.ARTISTS -> artistsList.size
        selectedCategory == LibraryCategory.FOLDERS -> foldersList.size
        else -> 0
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BentoCardDarkPurple.copy(alpha = 0.55f))
            .border(1.dp, BentoCardBorder, RoundedCornerShape(18.dp))
            .padding(12.dp)
            .testTag("jam_embedded_in_app_library"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Library Header Row with track counts & Sort Direction toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (drillDownGroup != null) {
                    Surface(
                        onClick = { drillDownGroup = null },
                        shape = CircleShape,
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.35f)),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to groups",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column {
                    Text(
                        text = drillDownGroup?.second?.substringAfterLast('/') ?: "In-App Library (Unmatched)",
                        color = BentoTextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${processedSongs.size} available to share · ${sharedLibraryMatches.size} matched excluded",
                        color = BentoTextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                onClick = { sortAscending = !sortAscending },
                shape = CircleShape,
                color = BentoCardSurface,
                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.35f)),
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = "Toggle Sort Direction",
                        tint = BentoPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search in-app library songs, artists, albums") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = BentoPrimary) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search", tint = BentoTextSecondary)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("jam_in_app_library_search_input")
        )

        // Sort Option Chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(SortOption.values(), key = { it.name }) { option ->
                val isSelected = option == selectedSort
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) BentoPrimary else BentoCardSurface)
                        .clickable { selectedSort = option }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = option.label,
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) BentoOnPrimary else BentoTextSecondary
                    )
                }
            }
        }

        // Category Chips (Songs | Albums | Artists | Folders | Favorites)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryCategory.values(), key = { it.name }) { category ->
                val isSelected = category == selectedCategory && drillDownGroup == null
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) BentoPrimary else BentoCardSurface)
                        .clickable {
                            selectedCategory = category
                            drillDownGroup = null
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = category.title,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) BentoOnPrimary else BentoTextSecondary
                    )
                }
            }
            item(key = "jam_lib_favorites_chip") {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (filterFavoritesOnly) BentoAccentPinkText else BentoCardSurface)
                        .clickable { filterFavoritesOnly = !filterFavoritesOnly }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = if (filterFavoritesOnly) Color.White else BentoTextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Favorites",
                            fontSize = 12.sp,
                            fontWeight = if (filterFavoritesOnly) FontWeight.Bold else FontWeight.Medium,
                            color = if (filterFavoritesOnly) Color.White else BentoTextSecondary
                        )
                    }
                }
            }
        }

        // Scrollable Song/Group List + FastScrollbar
        if (unmatchedSongs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (allSongs.isEmpty()) {
                        "Your in-app library is empty."
                    } else {
                        "All songs in your in-app library are already matched across every connected device!"
                    },
                    color = BentoTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else if (processedSongs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No unmatched songs match \"$searchQuery\".",
                    color = BentoTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("jam_in_app_library_list"),
                    contentPadding = PaddingValues(start = 2.dp, end = 22.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (effectiveShowingSongs) {
                        if (isAlphabeticalSort && groupedAlphabetSongs.isNotEmpty()) {
                            groupedAlphabetSongs.forEach { (letterHeader, songsForLetter) ->
                                item(key = "jam_lib_header_$letterHeader", contentType = "letter_header") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = letterHeader,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        HorizontalDivider(
                                            modifier = Modifier.weight(1f),
                                            color = BentoCardPurple.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                                items(songsForLetter, key = { it.id }, contentType = { "jam_lib_song" }) { song ->
                                    JamInAppLibrarySongRow(
                                        song = song,
                                        isSelected = song.id == selectedSongId,
                                        queuedTrackIds = queuedTrackIds,
                                        onSelect = { onSelectSongToShare(song) }
                                    )
                                }
                            }
                        } else {
                            items(processedSongs, key = { it.id }, contentType = { "jam_lib_song" }) { song ->
                                JamInAppLibrarySongRow(
                                    song = song,
                                    isSelected = song.id == selectedSongId,
                                    queuedTrackIds = queuedTrackIds,
                                    onSelect = { onSelectSongToShare(song) }
                                )
                            }
                        }
                    } else if (selectedCategory == LibraryCategory.ALBUMS) {
                        items(albumsList, key = { "jam_album_${it.first}" }) { (albumName, albumSongs) ->
                            val firstSong = albumSongs.firstOrNull()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(BentoCardSurface)
                                    .clickable { drillDownGroup = "ALBUM" to albumName }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (firstSong != null) {
                                    AlbumArtView(song = firstSong, modifier = Modifier.size(46.dp), cornerRadius = 10)
                                } else {
                                    Box(
                                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(BentoCardPurple),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Album, contentDescription = null, tint = BentoPrimary)
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(albumName, color = BentoTextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${albumSongs.size} unmatched tracks · ${firstSong?.displayArtist ?: "Unknown"}", color = BentoTextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    } else if (selectedCategory == LibraryCategory.ARTISTS) {
                        items(artistsList, key = { "jam_artist_${it.first}" }) { (artistName, artistSongs) ->
                            val firstSong = artistSongs.firstOrNull()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(BentoCardSurface)
                                    .clickable { drillDownGroup = "ARTIST" to artistName }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (firstSong != null) {
                                    AlbumArtView(song = firstSong, modifier = Modifier.size(46.dp), cornerRadius = 23)
                                } else {
                                    Box(
                                        modifier = Modifier.size(46.dp).clip(CircleShape).background(BentoCardPurple),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Person, contentDescription = null, tint = BentoPrimary)
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(artistName, color = BentoTextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${artistSongs.size} unmatched tracks", color = BentoTextSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } else if (selectedCategory == LibraryCategory.FOLDERS) {
                        items(foldersList, key = { "jam_folder_${it.first}" }) { (folderPath, folderSongs) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(BentoCardSurface)
                                    .clickable { drillDownGroup = "FOLDER" to folderPath }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(BentoCardPurple),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = BentoPrimary)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(folderPath.substringAfterLast('/'), color = BentoTextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${folderSongs.size} unmatched tracks · $folderPath", color = BentoTextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                if (totalScrollItems > 6) {
                    FastScrollbar(
                        listState = listState,
                        totalItems = totalScrollItems,
                        itemIndexToLabel = fastScrollAlphabetProvider,
                        topPadding = 6.dp,
                        bottomPadding = 16.dp,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun JamInAppLibrarySongRow(
    song: Song,
    isSelected: Boolean,
    queuedTrackIds: Set<String>,
    onSelect: () -> Unit
) {
    val alreadyQueued = remember(song.id, queuedTrackIds) {
        val candidate = JamTrack(
            sharedTrackId = "",
            title = TagCleaner.cleanTitle(song.displayTitle, "").trim().ifBlank { song.displayTitle.trim() }.take(180),
            artist = TagCleaner.cleanArtist(song.displayArtist).trim().ifBlank { song.displayArtist.trim() }.ifBlank { "Unknown Artist" }.take(180),
            album = TagCleaner.cleanAlbum(song.displayAlbum).trim().take(180),
            durationMs = song.durationMs
        )
        sharedTrackId(candidate) in queuedTrackIds
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isSelected) BentoPrimary.copy(alpha = 0.2f)
                else BentoCardSurface
            )
            .then(
                if (isSelected) Modifier.border(1.dp, BentoPrimary, RoundedCornerShape(14.dp))
                else Modifier
            )
            .clickable(enabled = !alreadyQueued, onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("jam_in_app_song_${song.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AlbumArtView(
            song = song,
            modifier = Modifier.size(44.dp),
            cornerRadius = 10
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.displayTitle,
                color = if (isSelected) BentoPrimary else BentoTextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${song.displayArtist} · ${formatDurationMmSs(song.durationMs)}",
                color = BentoTextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (alreadyQueued) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = BentoCardPurple
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(14.dp))
                    Text("Queued", color = BentoPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) BentoPrimary else BentoPrimary.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onSelect)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Share,
                        contentDescription = null,
                        tint = if (isSelected) BentoOnPrimary else BentoPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (isSelected) "Selected" else "Share",
                        color = if (isSelected) BentoOnPrimary else BentoPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
