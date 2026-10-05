package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var selectedCategory by remember { mutableStateOf(LibraryCategory.SONGS) }
    var selectedSort by remember(initialSortOption) {
        mutableStateOf(
            try { SortOption.valueOf(initialSortOption) } catch (e: Exception) { SortOption.RECENTLY_ADDED }
        )
    }
    var sortAscending by remember(initialSortAscending) { mutableStateOf(initialSortAscending) }
    var filterFavoritesOnly by remember { mutableStateOf(false) }

    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    var songForAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var selectedAlbumForSheet by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    var selectedArtistForSheet by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    var showDuplicateFinderSheet by remember { mutableStateOf(false) }

    // Search Header Visibility state optimized to avoid per-pixel recomposition
    val isSearchHeaderVisible by remember {
        derivedStateOf {
            songsListState.firstVisibleItemIndex == 0
        }
    }

    var processedSongs by remember(allSongs) {
        mutableStateOf(allSongs)
    }

    LaunchedEffect(allSongs, searchQuery, selectedSort, sortAscending, filterFavoritesOnly) {
        if (allSongs.isEmpty()) {
            processedSongs = emptyList()
            return@LaunchedEffect
        }
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            var list = if (searchQuery.isBlank()) allSongs
            else allSongs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true) ||
                it.album.contains(searchQuery, ignoreCase = true)
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
        processedSongs = result
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

    var groupedAlphabetSongs by remember { mutableStateOf<Map<String, List<Song>>>(emptyMap()) }
    var albums by remember { mutableStateOf<Map<String, List<Song>>>(emptyMap()) }
    var artists by remember { mutableStateOf<Map<String, List<Song>>>(emptyMap()) }
    var folders by remember { mutableStateOf<Map<String, List<Song>>>(emptyMap()) }

    LaunchedEffect(processedSongs, isAlphabeticalSort, selectedSort, selectedCategory) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (selectedCategory == LibraryCategory.SONGS && isAlphabeticalSort) {
                groupedAlphabetSongs = processedSongs.groupBy { getAlphabetLabel(it) }
            } else {
                groupedAlphabetSongs = emptyMap()
            }

            if (selectedCategory == LibraryCategory.ALBUMS) {
                albums = processedSongs.groupBy { it.displayAlbum }
            } else {
                albums = emptyMap()
            }

            if (selectedCategory == LibraryCategory.ARTISTS) {
                artists = processedSongs.groupBy { it.displayArtist }
            } else {
                artists = emptyMap()
            }

            if (selectedCategory == LibraryCategory.FOLDERS) {
                folders = processedSongs.groupBy { song ->
                    song.path.takeIf { p -> p.contains('/') }?.substringBeforeLast('/') ?: "Internal Storage"
                }
            } else {
                folders = emptyMap()
            }
        }
    }

    val albumsList = remember(albums) { albums.entries.toList() }
    val artistsList = remember(artists) { artists.entries.toList() }
    val foldersList = remember(folders) { folders.entries.toList() }

    val indexedAlphabetLabels = remember(selectedCategory, isAlphabeticalSort, groupedAlphabetSongs, processedSongs, albums, artists) {
        if (!isAlphabeticalSort) {
            emptyList<String?>()
        } else {
            when (selectedCategory) {
                LibraryCategory.SONGS -> {
                    if (groupedAlphabetSongs.isNotEmpty()) {
                        val labels = mutableListOf<String?>()
                        labels.add(null) // Controls / Shuffle button row at index 0
                        groupedAlphabetSongs.forEach { (letter, songs) ->
                            labels.add(letter) // stickyHeader
                            repeat(songs.size) { labels.add(letter) } // song items
                        }
                        labels
                    } else {
                        val labels = mutableListOf<String?>()
                        labels.add(null)
                        processedSongs.forEach { song -> labels.add(getAlphabetLabel(song)) }
                        labels
                    }
                }
                LibraryCategory.ALBUMS -> {
                    val labels = mutableListOf<String?>()
                    labels.add(null) // Header row
                    albums.keys.forEach { albumName ->
                        val ch = albumName.trim().firstOrNull()?.uppercaseChar() ?: '#'
                        labels.add(if (ch in 'A'..'Z') ch.toString() else "#")
                    }
                    labels
                }
                LibraryCategory.ARTISTS -> {
                    val labels = mutableListOf<String?>()
                    labels.add(null) // Header row
                    artists.keys.forEach { artistName ->
                        val ch = artistName.trim().firstOrNull()?.uppercaseChar() ?: '#'
                        labels.add(if (ch in 'A'..'Z') ch.toString() else "#")
                    }
                    labels
                }
                else -> emptyList()
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
        modifier = modifier.fillMaxSize(),
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

        // Sort Option Row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
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

        Spacer(modifier = Modifier.height(8.dp))

        // Animated Search Input Bar + Recent Searches (Auto Hiding on Scroll Down, Showing on Scroll Up)
        AnimatedVisibility(
            visible = isSearchHeaderVisible || searchQuery.isNotBlank(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                // Bento Search Field
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

                // Recent Searches Section in Music Library
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
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Scanning Banner if active in Library
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
                                text = "${scanProgress.displayTracksCount} tracks found",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoPrimary
                            )
                        }
                        Text(
                            text = if (scanProgress.currentFolder.isNotBlank()) "Scanning in progress (${scanProgress.currentFolder})..." else "Scanning in progress...",
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

        // Library Category Filter Tabs & Sort Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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

            item {
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

        Spacer(modifier = Modifier.height(12.dp))

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
                        if (processedSongs.isNotEmpty()) {
                            item(key = "shuffle_all_header") {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = BentoCardDarkPurple),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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

                        if (isAlphabeticalSort && groupedAlphabetSongs.isNotEmpty()) {
                            groupedAlphabetSongs.forEach { (letterHeader, songsForLetter) ->
                                stickyHeader(key = "letter_header_$letterHeader") {
                                    Surface(
                                        color = BentoBackground,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp, horizontal = 2.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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
                                }

                                items(songsForLetter, key = { "${it.id}_${it.path.hashCode()}" }, contentType = { "song_item" }) { song ->
                                    Box {
                                        SongItem(
                                            song = song,
                                            isPlaying = playbackState.currentSong?.id == song.id,
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
                            items(processedSongs, key = { "${it.id}_${it.path.hashCode()}" }, contentType = { "song_item" }) { song ->
                                Box {
                                    SongItem(
                                        song = song,
                                        isPlaying = playbackState.currentSong?.id == song.id,
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
                        items(albumsList, key = { "album_${it.key}" }, contentType = { "album_item" }) { (albumName, songs) ->
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
                        items(artistsList, key = { "artist_${it.key}" }, contentType = { "artist_item" }) { (artistName, songs) ->
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
