package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.service.PlaybackState
import com.example.ui.components.AddToPlaylistDialog
import com.example.ui.components.AlbumArtView
import com.example.ui.components.BentoCard
import com.example.ui.components.DuplicateFinderSheet
import com.example.ui.components.FastScrollbar
import com.example.ui.components.SongActionMenu
import com.example.ui.components.SongItem
import com.example.ui.theme.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

enum class LibraryCategory(val title: String) {
    SONGS("Songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    FOLDERS("Folders")
}

enum class SortOption(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    RECENTLY_ADDED("Recently Added"),
    RECENTLY_PLAYED("Recently Played"),
    DURATION("Duration")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun LibraryScreen(
    allSongs: List<Song>,
    playbackState: PlaybackState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSongClick: (Song) -> Unit,
    onPlayQueue: (List<Song>, Int) -> Unit = { list, idx -> list.getOrNull(idx)?.let { onSongClick(it) } },
    onFavoriteToggle: (Song) -> Unit,
    onBackClick: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    initialSortOption: String = "RECENTLY_ADDED",
    initialSortAscending: Boolean = true,
    onSortChanged: (String, Boolean) -> Unit = { _, _ -> },
    onPlayNext: (Song) -> Unit = {},
    onAddToQueue: (Song) -> Unit = {},
    onAddSongToPlaylist: (Long, Long) -> Unit = { _, _ -> },
    onCreateNewPlaylist: (String) -> Unit = {},
    onDeleteSong: (Song) -> Unit = {},
    onEditTags: ((Song) -> Unit)? = null,
    allPlaylists: List<Playlist> = emptyList(),
    onGetPlaylistsContainingSong: suspend (Long) -> List<Playlist> = { emptyList() },
    onRemoveFromPlaylist: (Long, Long) -> Unit = { _, _ -> },
    onShuffleAll: ((List<Song>) -> Unit)? = null,
    recentSearches: List<String> = emptyList(),
    onAddRecentSearch: (String) -> Unit = {},
    onDeleteRecentSearch: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    scanProgress: com.example.scanner.ScanProgress = com.example.scanner.ScanProgress(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val songsListState = rememberLazyListState()
    var isLibraryHeaderVisible by remember { mutableStateOf(true) }
    var previousIndex by remember { mutableIntStateOf(0) }
    var previousScrollOffset by remember { mutableIntStateOf(0) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta < -8f) {
                    isLibraryHeaderVisible = false
                } else if (delta > 8f) {
                    isLibraryHeaderVisible = true
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(songsListState) {
        snapshotFlow {
            Pair(songsListState.firstVisibleItemIndex, songsListState.firstVisibleItemScrollOffset)
        }.collect { (currentIndex, currentOffset) ->
            if (currentIndex == 0 && currentOffset <= 10) {
                isLibraryHeaderVisible = true
            } else if (currentIndex > previousIndex || (currentIndex == previousIndex && currentOffset > previousScrollOffset + 10)) {
                isLibraryHeaderVisible = false
            } else if (currentIndex < previousIndex || (currentIndex == previousIndex && currentOffset < previousScrollOffset - 10)) {
                isLibraryHeaderVisible = true
            }
            previousIndex = currentIndex
            previousScrollOffset = currentOffset
        }
    }

    var selectedCategory by remember { mutableStateOf(LibraryCategory.SONGS) }
    var selectedSort by remember(initialSortOption) {
        mutableStateOf(
            try { SortOption.valueOf(initialSortOption) } catch (_: Exception) { SortOption.RECENTLY_ADDED }
        )
    }
    var sortAscending by remember(initialSortAscending) { mutableStateOf(initialSortAscending) }
    var filterFavoritesOnly by remember { mutableStateOf(false) }

    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    var songForAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var songForRemoveFromPlaylist by remember { mutableStateOf<Song?>(null) }
    var selectedAlbumForSheet by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    var selectedArtistForSheet by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    var showDuplicateFinderSheet by remember { mutableStateOf(false) }
    val currentPlayingSongId = remember(playbackState.currentSong?.id) { playbackState.currentSong?.id }

    fun filterAndSortLibrarySongs(
        source: List<Song>,
        q: String,
        sort: SortOption,
        ascending: Boolean,
        favOnly: Boolean
    ): List<Song> {
        if (source.isEmpty()) return emptyList()
        val trimmedQ = q.trim()
        var list = if (trimmedQ.isBlank()) source
        else source.filter {
            it.title.contains(trimmedQ, ignoreCase = true) ||
                it.displayTitle.contains(trimmedQ, ignoreCase = true) ||
                it.artist.contains(trimmedQ, ignoreCase = true) ||
                it.displayArtist.contains(trimmedQ, ignoreCase = true) ||
                it.album.contains(trimmedQ, ignoreCase = true) ||
                it.displayAlbum.contains(trimmedQ, ignoreCase = true)
        }

        if (favOnly) {
            list = list.filter { it.isFavorite }
        }

        val sorted = when (sort) {
            SortOption.TITLE -> if (ascending) list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }) else list.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.displayTitle })
            SortOption.ARTIST -> if (ascending) list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayArtist }) else list.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.displayArtist })
            SortOption.ALBUM -> if (ascending) list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayAlbum }) else list.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.displayAlbum })
            SortOption.RECENTLY_ADDED -> if (ascending) list.sortedByDescending { it.dateAdded } else list.sortedBy { it.dateAdded }
            SortOption.RECENTLY_PLAYED -> if (ascending) list.sortedByDescending { it.lastPlayed } else list.sortedBy { it.lastPlayed }
            SortOption.DURATION -> if (ascending) list.sortedBy { it.durationMs } else list.sortedByDescending { it.durationMs }
        }
        return sorted.distinctBy { it.id }
    }

    // Deduplicate by id (.distinctBy { it.id }) and run filtering/sorting off the main thread with debouncing
    val processedSongs by produceState(
        initialValue = emptyList(),
        keys = arrayOf(allSongs, searchQuery, selectedSort, sortAscending, filterFavoritesOnly)
    ) {
        if (searchQuery.isNotBlank()) {
            kotlinx.coroutines.delay(80L)
        }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            filterAndSortLibrarySongs(allSongs, searchQuery, selectedSort, sortAscending, filterFavoritesOnly)
        }
    }

    // Debounced artwork preloading ahead of viewport so scrolling never stutters at 60Hz, 90Hz, or 120Hz
    LaunchedEffect(processedSongs, selectedCategory) {
        if (selectedCategory == LibraryCategory.SONGS && processedSongs.isNotEmpty()) {
            snapshotFlow { songsListState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .debounce(24L)
                .collectLatest { firstVisible ->
                    com.example.ui.components.ArtworkResolutionCache.preloadWindow(
                        context = context,
                        songs = processedSongs,
                        firstVisibleIndex = firstVisible,
                        windowAhead = 35,
                        windowBehind = 10
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

    val groupedAlphabetSongs by produceState(
        initialValue = emptyMap<String, List<Song>>(),
        key1 = processedSongs,
        key2 = isAlphabeticalSort,
        key3 = selectedCategory
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (selectedCategory == LibraryCategory.SONGS && isAlphabeticalSort) {
                processedSongs.groupBy { getAlphabetLabel(it) }
            } else emptyMap()
        }
    }

    val albums by produceState(
        initialValue = emptyMap<String, List<Song>>(),
        key1 = processedSongs,
        key2 = selectedCategory
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (selectedCategory == LibraryCategory.ALBUMS) processedSongs.groupBy { it.displayAlbum } else emptyMap()
        }
    }

    val artists by produceState(
        initialValue = emptyMap<String, List<Song>>(),
        key1 = processedSongs,
        key2 = selectedCategory
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (selectedCategory == LibraryCategory.ARTISTS) processedSongs.groupBy { it.displayArtist } else emptyMap()
        }
    }

    val folders by produceState(
        initialValue = emptyMap<String, List<Song>>(),
        key1 = processedSongs,
        key2 = selectedCategory
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (selectedCategory == LibraryCategory.FOLDERS) {
                processedSongs.groupBy { song ->
                    song.path.takeIf { path -> path.contains('/') }?.substringBeforeLast('/') ?: "Internal Storage"
                }
            } else emptyMap()
        }
    }

    val albumsList by produceState(
        initialValue = emptyList<Pair<String, List<Song>>>(),
        key1 = albums
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { albums.map { it.key to it.value } }
    }
    val artistsList by produceState(
        initialValue = emptyList<Pair<String, List<Song>>>(),
        key1 = artists
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { artists.map { it.key to it.value } }
    }
    val foldersList by produceState(
        initialValue = emptyList<Pair<String, List<Song>>>(),
        key1 = folders
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { folders.map { it.key to it.value } }
    }

    val indexedAlphabetLabels by produceState(
        initialValue = emptyList<String?>(),
        keys = arrayOf(selectedCategory, isAlphabeticalSort, groupedAlphabetSongs, processedSongs, albums, artists)
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (!isAlphabeticalSort) {
                emptyList()
            } else {
                when (selectedCategory) {
                    LibraryCategory.SONGS -> {
                        val labels = mutableListOf<String?>()
                        if (groupedAlphabetSongs.isNotEmpty()) {
                            groupedAlphabetSongs.forEach { (letter, songs) ->
                                labels.add(letter)
                                repeat(songs.size) { labels.add(letter) }
                            }
                        } else {
                            processedSongs.forEach { song -> labels.add(getAlphabetLabel(song)) }
                        }
                        labels
                    }
                    LibraryCategory.ALBUMS -> albums.keys.map { albumName ->
                        albumName.trim().firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
                    }
                    LibraryCategory.ARTISTS -> artists.keys.map { artistName ->
                        artistName.trim().firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
                    }
                    else -> emptyList()
                }
            }
        }
    }

    val fastScrollAlphabetProvider: ((Int) -> String?)? = remember(isAlphabeticalSort, indexedAlphabetLabels) {
        if (!isAlphabeticalSort || indexedAlphabetLabels.isEmpty()) {
            null
        } else {
            { idx: Int -> indexedAlphabetLabels.getOrNull(idx) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Title Row with Back Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onBackClick,
                        shape = CircleShape,
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Home",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Your Library",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${processedSongs.size} tracks",
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Search button
                    Surface(
                        onClick = onOpenSearch,
                        shape = CircleShape,
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Favorites button
                    Surface(
                        onClick = onOpenFavorites,
                        shape = CircleShape,
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Favorites",
                                tint = BentoAccentPinkText,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Duplicate songs cleaner button
                    Surface(
                        onClick = { showDuplicateFinderSheet = true },
                        shape = CircleShape,
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Find Duplicate Songs",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Sort toggle button
                    Surface(
                        onClick = {
                            sortAscending = !sortAscending
                            onSortChanged(selectedSort.name, sortAscending)
                        },
                        shape = CircleShape,
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                contentDescription = "Sort Direction",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Settings & More button
                    Surface(
                        onClick = onOpenSettings,
                        shape = CircleShape,
                        color = BentoPrimary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.32f)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings & More",
                                tint = BentoPrimary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            AnimatedVisibility(
                visible = isLibraryHeaderVisible,
                enter = expandVertically(animationSpec = tween(250)) + fadeIn(animationSpec = tween(250)),
                exit = shrinkVertically(animationSpec = tween(250)) + fadeOut(animationSpec = tween(200))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(SortOption.values(), key = { it.name }, contentType = { "sort_chip" }) { option ->
                            val isSelected = option == selectedSort
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) BentoPrimary else BentoCardSurface)
                                    .clickable {
                                        selectedSort = option
                                        onSortChanged(option.name, sortAscending)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = option.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) BentoOnPrimary else BentoTextSecondary
                                )
                            }
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(LibraryCategory.values(), key = { it.name }, contentType = { "category_chip" }) { category ->
                            val isSelected = category == selectedCategory
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isSelected) BentoPrimary else BentoCardSurface)
                                    .clickable { selectedCategory = category }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = category.title,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) BentoOnPrimary else BentoTextSecondary
                                )
                            }
                        }

                        item(key = "favorites_filter_chip") {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (filterFavoritesOnly) BentoAccentPinkText else BentoCardSurface)
                                    .clickable { filterFavoritesOnly = !filterFavoritesOnly }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = null,
                                        tint = if (filterFavoritesOnly) Color.White else BentoTextSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Favorites",
                                        fontSize = 13.sp,
                                        color = if (filterFavoritesOnly) Color.White else BentoTextSecondary
                                    )
                                }
                            }
                        }
                    }

                    LibrarySearchHeaderBlock(
                        searchQuery = searchQuery,
                        onSearchQueryChange = onSearchQueryChange,
                        recentSearches = recentSearches,
                        onAddRecentSearch = onAddRecentSearch,
                        onDeleteRecentSearch = onDeleteRecentSearch,
                        onClearRecentSearches = onClearRecentSearches
                    )

                    if (selectedCategory == LibraryCategory.SONGS && processedSongs.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            colors = CardDefaults.cardColors(containerColor = BentoCardDarkPurple),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (processedSongs.isNotEmpty()) {
                                            if (onShuffleAll != null) {
                                                onShuffleAll(processedSongs)
                                            } else {
                                                val shuffled = processedSongs.shuffled()
                                                onPlayQueue(shuffled, 0)
                                            }
                                            Toast.makeText(context, "Shuffling ${processedSongs.size} tracks", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary)
                                ) {
                                    Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Shuffle All Songs", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Scanning status remains available while the library controls auto-hide.
            if (scanProgress.isScanning || scanProgress.status == com.example.scanner.ScanStatus.SCANNING) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = BentoPrimary
                                )
                                Text(
                                    text = "${scanProgress.validTracksFound} tracks indexed",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoPrimary
                                )
                            }
                            Text(
                                text = if (scanProgress.currentFolder.isNotBlank()) "Scanning (${scanProgress.currentFolder})..." else "Scanning...",
                                fontSize = 11.5.sp,
                                color = BentoTextSecondary
                            )
                        }
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(CircleShape),
                            color = BentoPrimary,
                            trackColor = BentoBorder.copy(alpha = 0.30f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Content List with FastScrollbar
            val currentTotalItems = when (selectedCategory) {
                LibraryCategory.SONGS -> processedSongs.size
                LibraryCategory.ALBUMS -> albums.size
                LibraryCategory.ARTISTS -> artists.size
                LibraryCategory.FOLDERS -> folders.size
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = songsListState,
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (selectedCategory) {
                        LibraryCategory.SONGS -> {
                            if (isAlphabeticalSort && groupedAlphabetSongs.isNotEmpty()) {
                                groupedAlphabetSongs.forEach { (letterHeader, songsForLetter) ->
                                    item(key = "letter_header_$letterHeader", contentType = "letter_header") {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = letterHeader,
                                                fontSize = 15.sp,
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

                                    items(songsForLetter, key = { it.id }, contentType = { "song_item" }) { song ->
                                        Box {
                                            SongItem(
                                                song = song,
                                                isPlaying = currentPlayingSongId == song.id,
                                                onClick = {
                                                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                    onSongClick(song)
                                                },
                                                onFavoriteToggle = {
                                                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                    onFavoriteToggle(song)
                                                },
                                                onMoreClick = {
                                                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                    selectedSongForMenu = song
                                                }
                                            )

                                            if (selectedSongForMenu?.id == song.id) {
                                                SongActionMenu(
                                                    expanded = true,
                                                    song = song,
                                                    isInPlaylist = false,
                                                    onDismissRequest = { selectedSongForMenu = null },
                                                    onPlayNext = {
                                                        if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                        onPlayNext(song)
                                                    },
                                                    onAddToQueue = {
                                                        if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                        onAddToQueue(song)
                                                    },
                                                    onAddToPlaylist = {
                                                        if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                        songForAddToPlaylist = song
                                                    },
                                                    onOpenRemoveFromPlaylist = {
                                                        if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                        songForRemoveFromPlaylist = song
                                                    },
                                                    onEditTags = if (onEditTags != null) {
                                                        {
                                                            if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                            onEditTags(song)
                                                        }
                                                    } else null,
                                                    onDeleteSong = {
                                                        if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                        onDeleteSong(song)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                items(processedSongs, key = { it.id }, contentType = { "song_item" }) { song ->
                                    Box {
                                        SongItem(
                                            song = song,
                                            isPlaying = currentPlayingSongId == song.id,
                                            onClick = {
                                                if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                onSongClick(song)
                                            },
                                            onFavoriteToggle = {
                                                if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                onFavoriteToggle(song)
                                            },
                                            onMoreClick = {
                                                if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                selectedSongForMenu = song
                                            }
                                        )

                                        if (selectedSongForMenu?.id == song.id) {
                                            SongActionMenu(
                                                expanded = true,
                                                song = song,
                                                isInPlaylist = false,
                                                onDismissRequest = { selectedSongForMenu = null },
                                                onPlayNext = {
                                                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                    onPlayNext(song)
                                                },
                                                onAddToQueue = {
                                                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                    onAddToQueue(song)
                                                },
                                                onAddToPlaylist = {
                                                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                    songForAddToPlaylist = song
                                                },
                                                onOpenRemoveFromPlaylist = {
                                                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                    songForRemoveFromPlaylist = song
                                                },
                                                onEditTags = if (onEditTags != null) {
                                                    {
                                                        if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                        onEditTags(song)
                                                    }
                                                } else null,
                                                onDeleteSong = {
                                                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                    onDeleteSong(song)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        LibraryCategory.ALBUMS -> {
                            items(albumsList, key = { "album_${it.first}" }, contentType = { "album_item" }) { (albumName, songs) ->
                                val firstSongWithArt = songs.firstOrNull { !it.artworkUri.isNullOrBlank() } ?: songs.firstOrNull()
                                BentoCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                            selectedAlbumForSheet = albumName to songs
                                        },
                                    backgroundColor = BentoCardSurface
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (firstSongWithArt != null) {
                                            AlbumArtView(
                                                song = firstSongWithArt,
                                                modifier = Modifier.size(52.dp),
                                                cornerRadius = 14
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(BentoCardPurple),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Default.Album, contentDescription = null, tint = BentoPrimaryContainer)
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(albumName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                            Text("${songs.size} tracks • ${songs.firstOrNull()?.displayArtist ?: "Unknown"}", fontSize = 12.sp, color = BentoTextSecondary)
                                        }
                                    }
                                }
                            }
                        }
                        LibraryCategory.ARTISTS -> {
                            items(artistsList, key = { "artist_${it.first}" }, contentType = { "artist_item" }) { (artistName, songs) ->
                                BentoCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                            selectedArtistForSheet = artistName to songs
                                        },
                                    backgroundColor = BentoCardSurface
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        com.example.ui.components.ArtistAvatar(
                                            artistName = artistName,
                                            modifier = Modifier.size(52.dp),
                                            fallbackArtworkUri = songs.firstOrNull()?.artworkUri
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(artistName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                            Text("${songs.size} songs", fontSize = 12.sp, color = BentoTextSecondary)
                                        }
                                    }
                                }
                            }
                        }
                        LibraryCategory.FOLDERS -> {
                            items(foldersList, key = { (folderPath, _) -> "folder_$folderPath" }, contentType = { "folder_item" }) { (folderPath, songs) ->
                                BentoCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                        },
                                    backgroundColor = BentoCardSurface
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(BentoCardDarkPurple),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Folder, contentDescription = null, tint = BentoPrimary)
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                folderPath.substringAfterLast('/'),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoTextPrimary
                                            )
                                            Text("${songs.size} songs • $folderPath", fontSize = 11.sp, color = BentoTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (currentTotalItems > 5) {
                    FastScrollbar(
                        listState = songsListState,
                        totalItems = currentTotalItems,
                        itemLabelProvider = fastScrollAlphabetProvider,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 0.dp, top = 8.dp, bottom = 120.dp)
                    )
                }
            }
        }

        songForAddToPlaylist?.let { targetSong ->
            AddToPlaylistDialog(
                song = targetSong,
                playlists = allPlaylists,
                onSelectPlaylist = { targetPlaylist ->
                    onAddSongToPlaylist(targetPlaylist.id, targetSong.id)
                },
                onCreateNewPlaylist = { newName ->
                    onCreateNewPlaylist(newName)
                },
                onDismiss = { songForAddToPlaylist = null }
            )
        }

        songForRemoveFromPlaylist?.let { targetSong ->
            com.example.ui.components.RemoveFromPlaylistDialog(
                song = targetSong,
                onGetPlaylistsContainingSong = onGetPlaylistsContainingSong,
                onRemoveFromPlaylist = onRemoveFromPlaylist,
                onDismiss = { songForRemoveFromPlaylist = null }
            )
        }

        selectedAlbumForSheet?.let { (albumName, songs) ->
            com.example.ui.components.AlbumDetailSheet(
                albumName = albumName,
                artistName = songs.firstOrNull()?.displayArtist ?: "Unknown Artist",
                albumSongs = songs,
                playbackState = playbackState,
                onDismiss = { selectedAlbumForSheet = null },
                onPlayAll = { song, queue ->
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onPlayQueue(queue, queue.indexOf(song).coerceAtLeast(0))
                    selectedAlbumForSheet = null
                },
                onFavoriteToggle = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onFavoriteToggle(it)
                },
                onPlayNext = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onPlayNext(it)
                },
                onAddToQueue = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onAddToQueue(it)
                },
                onAddToPlaylist = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    songForAddToPlaylist = it
                },
                onOpenRemoveFromPlaylist = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    songForRemoveFromPlaylist = it
                },
                onDeleteSong = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onDeleteSong(it)
                },
                onEditTags = onEditTags
            )
        }

        selectedArtistForSheet?.let { (artistName, songs) ->
            com.example.ui.components.ArtistDetailSheet(
                artistName = artistName,
                artistSongs = songs,
                playbackState = playbackState,
                onDismiss = { selectedArtistForSheet = null },
                onPlayAll = { song, queue ->
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onPlayQueue(queue, queue.indexOf(song).coerceAtLeast(0))
                    selectedArtistForSheet = null
                },
                onFavoriteToggle = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onFavoriteToggle(it)
                },
                onPlayNext = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onPlayNext(it)
                },
                onAddToQueue = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onAddToQueue(it)
                },
                onAddToPlaylist = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    songForAddToPlaylist = it
                },
                onOpenRemoveFromPlaylist = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    songForRemoveFromPlaylist = it
                },
                onDeleteSong = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onDeleteSong(it)
                },
                onEditTags = onEditTags
            )
        }

        if (showDuplicateFinderSheet) {
            DuplicateFinderSheet(
                allSongs = allSongs,
                onDismiss = { showDuplicateFinderSheet = false },
                onDeleteSongs = { songsToDelete ->
                    songsToDelete.forEach { song ->
                        onDeleteSong(song)
                    }
                    showDuplicateFinderSheet = false
                },
                onPreviewSong = onSongClick
            )
        }
    }
}

@Composable
private fun LibrarySearchHeaderBlock(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    recentSearches: List<String>,
    onAddRecentSearch: (String) -> Unit,
    onDeleteRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit
) {
    Column {
        TextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search songs, artists, albums...", color = BentoTextSecondary) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = BentoPrimary) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search", tint = BentoTextSecondary)
                    }
                }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = {
                    if (searchQuery.isNotBlank()) {
                        onAddRecentSearch(searchQuery)
                    }
                }
            ),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = BentoCardSurface,
                unfocusedContainerColor = BentoCardSurface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = BentoTextPrimary,
                unfocusedTextColor = BentoTextPrimary
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        )

        if (recentSearches.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Recent Searches",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextSecondary
                    )
                }

                TextButton(
                    onClick = onClearRecentSearches,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Clear All",
                        fontSize = 11.sp,
                        color = BentoTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(recentSearches, key = { it }, contentType = { "recent_search" }) { term ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(BentoCardSurface)
                            .clickable {
                                onSearchQueryChange(term)
                                onAddRecentSearch(term)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = term,
                                fontSize = 12.sp,
                                color = BentoTextPrimary,
                                maxLines = 1
                            )
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete search term",
                                tint = BentoTextSecondary,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { onDeleteRecentSearch(term) }
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}
