package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.service.PlaybackState
import com.example.ui.components.AlbumArtView
import com.example.ui.components.BentoCard
import com.example.ui.components.FastScrollbar
import com.example.ui.components.SongItem
import com.example.ui.theme.*

enum class SearchFilter(val title: String) {
    ALL("All"),
    SONGS("Songs"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
    FOLDERS("Folders"),
    PLAYLISTS("Playlists")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    allSongs: List<Song>,
    playlists: List<Playlist>,
    playbackState: PlaybackState,
    onSongClick: (Song) -> Unit,
    onPlaySongQueue: (Song, List<Song>) -> Unit = { song, _ -> onSongClick(song) },
    onFavoriteToggle: (Song) -> Unit,
    recentSearches: List<String> = emptyList(),
    onAddRecentSearch: (String) -> Unit = {},
    onDeleteRecentSearch: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    onPlayNext: (Song) -> Unit = {},
    onAddToQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onDeleteSong: (Song) -> Unit = {},
    onEditTags: ((Song) -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onBackClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf(SearchFilter.ALL) }
    var selectedAlbumForDetail by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    var selectedArtistForDetail by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }

    val searchResultsListState = rememberLazyListState()
    var isSearchHeaderVisible by remember { mutableStateOf(true) }
    var previousIndex by remember { mutableIntStateOf(0) }
    var previousScrollOffset by remember { mutableIntStateOf(0) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val delta = available.y
                if (delta < -8f) {
                    // User slides down through content -> auto hide input box
                    if (isSearchHeaderVisible) {
                        isSearchHeaderVisible = false
                    }
                } else if (delta > 8f) {
                    // User slides up -> smoothly re-appear
                    if (!isSearchHeaderVisible) {
                        isSearchHeaderVisible = true
                    }
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(searchResultsListState) {
        snapshotFlow {
            Pair(searchResultsListState.firstVisibleItemIndex, searchResultsListState.firstVisibleItemScrollOffset)
        }.collect { (currentIndex, currentOffset) ->
            if (currentIndex == 0 && currentOffset <= 10) {
                isSearchHeaderVisible = true
            } else if (currentIndex > previousIndex || (currentIndex == previousIndex && currentOffset > previousScrollOffset + 10)) {
                isSearchHeaderVisible = false
            } else if (currentIndex < previousIndex || (currentIndex == previousIndex && currentOffset < previousScrollOffset - 10)) {
                isSearchHeaderVisible = true
            }
            previousIndex = currentIndex
            previousScrollOffset = currentOffset
        }
    }

    val query = searchQuery.trim()

    data class SearchComputedResults(
        val songs: List<Song> = emptyList(),
        val artists: List<String> = emptyList(),
        val albums: List<String> = emptyList(),
        val folders: List<String> = emptyList(),
        val playlists: List<Playlist> = emptyList()
    )

    fun computeSearchResults(
        songsSource: List<Song>,
        playlistsSource: List<Playlist>,
        q: String
    ): SearchComputedResults {
        if (q.isBlank()) return SearchComputedResults()
        val matchedSongs = songsSource.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.displayTitle.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.displayArtist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true) ||
                it.displayAlbum.contains(q, ignoreCase = true) ||
                it.path.contains(q, ignoreCase = true)
        }
        val matchedArtists = songsSource.asSequence().map { it.displayArtist }.distinct()
            .filter { it.contains(q, ignoreCase = true) }.toList()
        val matchedAlbums = songsSource.asSequence().map { it.displayAlbum }.distinct()
            .filter { it.contains(q, ignoreCase = true) }.toList()
        val matchedFolders = songsSource.asSequence().map { java.io.File(it.path).parent ?: "" }.distinct()
            .filter { it.isNotBlank() && it.contains(q, ignoreCase = true) }.toList()
        val matchedPlaylists = playlistsSource.filter { it.name.contains(q, ignoreCase = true) }
        return SearchComputedResults(
            songs = matchedSongs,
            artists = matchedArtists,
            albums = matchedAlbums,
            folders = matchedFolders,
            playlists = matchedPlaylists
        )
    }

    val searchComputed by produceState(
        initialValue = remember { computeSearchResults(allSongs, playlists, query) },
        key1 = allSongs,
        key2 = playlists,
        key3 = query
    ) {
        if (query.isBlank()) {
            value = SearchComputedResults()
        } else {
            kotlinx.coroutines.delay(90L)
            val computed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                computeSearchResults(allSongs, playlists, query)
            }
            value = computed
        }
    }

    val matchingSongs = searchComputed.songs
    val matchingArtists = searchComputed.artists
    val matchingAlbums = searchComputed.albums
    val matchingFolders = searchComputed.folders
    val matchingPlaylists = searchComputed.playlists

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

        // Title & Header Actions
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
                        text = "Search",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (searchQuery.isNotBlank()) "Filter results" else "${allSongs.size} tracks indexed",
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (searchQuery.isNotEmpty()) {
                    Surface(
                        onClick = { onSearchQueryChange("") },
                        shape = CircleShape,
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear Search",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                } else {
                    Surface(
                        onClick = { /* Already on search screen */ },
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
                }

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

        Spacer(modifier = Modifier.height(8.dp))

        // Search Input Bar (Animated auto-hide on scroll)
        AnimatedVisibility(
            visible = isSearchHeaderVisible,
            enter = expandVertically(animationSpec = androidx.compose.animation.core.tween(250)) + fadeIn(animationSpec = androidx.compose.animation.core.tween(250)),
            exit = shrinkVertically(animationSpec = androidx.compose.animation.core.tween(250)) + fadeOut(animationSpec = androidx.compose.animation.core.tween(200))
        ) {
            val isSearchDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            Column {
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            text = "Search songs, artists, albums, folders...",
                            color = if (isSearchDark) BentoTextSecondary else Color(0xFF64748B)
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = BentoPrimary) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = if (isSearchDark) BentoTextSecondary else Color(0xFF64748B)
                                )
                            }
                        }
                    },
                    singleLine = true,
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
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = if (isSearchDark) BentoCardSurface else Color(0xFFF1F5F9),
                        unfocusedContainerColor = if (isSearchDark) BentoCardSurface else Color(0xFFF1F5F9),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = if (isSearchDark) Color.White else Color(0xFF0F172A),
                        unfocusedTextColor = if (isSearchDark) Color.White else Color(0xFF0F172A),
                        cursorColor = BentoPrimary
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = if (isSearchDark) Color.White else Color(0xFF0F172A),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Search Filter Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(SearchFilter.values()) { filter ->
                        val isSelected = filter == selectedFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) BentoPrimary else BentoCardSurface)
                                .clickable { selectedFilter = filter }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = filter.title,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) BentoOnPrimary else BentoTextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (allSongs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No music indexed yet.",
                    color = BentoTextSecondary,
                    fontSize = 16.sp
                )
            }
        } else if (query.isBlank()) {
            if (recentSearches.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Searches",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        TextButton(
                            onClick = onClearRecentSearches,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = BentoTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Clear All",
                                fontSize = 13.sp,
                                color = BentoTextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    LazyColumn(
                        state = searchResultsListState,
                        contentPadding = PaddingValues(bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(recentSearches, key = { idx, recentTerm -> "${recentTerm}_$idx" }) { _, recentTerm ->
                            BentoCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSearchQueryChange(recentTerm)
                                        onAddRecentSearch(recentTerm)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                            tint = BentoTextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = recentTerm,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    IconButton(
                                        onClick = { onDeleteRecentSearch(recentTerm) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove search",
                                            tint = BentoTextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = BentoTextSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Search songs, artists, albums or folders",
                            color = BentoTextSecondary,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        } else {
            val hasResults = matchingSongs.isNotEmpty() || matchingArtists.isNotEmpty() ||
                    matchingAlbums.isNotEmpty() || matchingFolders.isNotEmpty() || matchingPlaylists.isNotEmpty()

            if (!hasResults) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No results found",
                        color = BentoTextSecondary,
                        fontSize = 16.sp
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = searchResultsListState,
                        contentPadding = PaddingValues(bottom = 120.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Matching Songs
                        if ((selectedFilter == SearchFilter.ALL || selectedFilter == SearchFilter.SONGS) && matchingSongs.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Songs (${matchingSongs.size})",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                            }
                            itemsIndexed(matchingSongs, key = { idx, song -> "${song.id}_$idx" }) { _, song ->
                                var showMenu by remember { mutableStateOf(false) }
                                Box {
                                    SongItem(
                                        song = song,
                                        isPlaying = playbackState.currentSong?.id == song.id,
                                        onClick = {
                                            if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery) else onAddRecentSearch(song.displayTitle)
                                            onSongClick(song)
                                        },
                                        onFavoriteToggle = {
                                            if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery) else onAddRecentSearch(song.displayTitle)
                                            onFavoriteToggle(song)
                                        },
                                        onMoreClick = {
                                            if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery) else onAddRecentSearch(song.displayTitle)
                                            showMenu = true
                                        }
                                    )

                                    if (showMenu) {
                                        com.example.ui.components.SongActionMenu(
                                            expanded = true,
                                            song = song,
                                            onDismissRequest = { showMenu = false },
                                            onPlayNow = {
                                                if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery) else onAddRecentSearch(song.displayTitle)
                                                onSongClick(song)
                                            },
                                            onPlayNext = {
                                                if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery) else onAddRecentSearch(song.displayTitle)
                                                onPlayNext(song)
                                            },
                                            onAddToQueue = {
                                                if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery) else onAddRecentSearch(song.displayTitle)
                                                onAddToQueue(song)
                                            },
                                            onAddToPlaylist = {
                                                if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery) else onAddRecentSearch(song.displayTitle)
                                                onAddToPlaylist(song)
                                            },
                                            onFavoriteToggle = {
                                                if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery) else onAddRecentSearch(song.displayTitle)
                                                onFavoriteToggle(song)
                                            },
                                            onEditTags = if (onEditTags != null) {
                                                {
                                                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery) else onAddRecentSearch(song.displayTitle)
                                                    onEditTags(song)
                                                }
                                            } else null,
                                            onDeleteSong = {
                                                if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery) else onAddRecentSearch(song.displayTitle)
                                                onDeleteSong(song)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Matching Artists
                        if ((selectedFilter == SearchFilter.ALL || selectedFilter == SearchFilter.ARTISTS) && matchingArtists.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Artists (${matchingArtists.size})",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                            }
                            items(matchingArtists) { artist ->
                                val artistSongs = allSongs.filter { it.displayArtist.equals(artist, ignoreCase = true) }
                                val firstArtistArt = artistSongs.firstOrNull { !it.artworkUri.isNullOrBlank() }?.artworkUri ?: artistSongs.firstOrNull()?.artworkUri
                                BentoCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                            if (artistSongs.isNotEmpty()) selectedArtistForDetail = artist to artistSongs
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            com.example.ui.components.ArtistAvatar(
                                                artistName = artist,
                                                modifier = Modifier.size(40.dp),
                                                fallbackArtworkUri = firstArtistArt
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(artist, color = BentoTextPrimary, fontWeight = FontWeight.Bold)
                                                Text("${artistSongs.size} songs", color = BentoTextSecondary, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            // Matching Albums
                            if ((selectedFilter == SearchFilter.ALL || selectedFilter == SearchFilter.ALBUMS) && matchingAlbums.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Albums (${matchingAlbums.size})",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                }
                                items(matchingAlbums) { album ->
                                    val albumSongs = allSongs.filter { it.displayAlbum.equals(album, ignoreCase = true) }
                                    val firstSongWithArt = albumSongs.firstOrNull { !it.artworkUri.isNullOrBlank() } ?: albumSongs.firstOrNull()
                                    BentoCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                if (albumSongs.isNotEmpty()) selectedAlbumForDetail = album to albumSongs
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (firstSongWithArt != null) {
                                                com.example.ui.components.AlbumArtView(
                                                    song = firstSongWithArt,
                                                    modifier = Modifier.size(44.dp),
                                                    cornerRadius = 8
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Album,
                                                    contentDescription = null,
                                                    tint = BentoPrimary,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(album, color = BentoTextPrimary, fontWeight = FontWeight.Bold)
                                                Text("${albumSongs.size} songs", color = BentoTextSecondary, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            // Matching Folders
                            if ((selectedFilter == SearchFilter.ALL || selectedFilter == SearchFilter.FOLDERS) && matchingFolders.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Folders (${matchingFolders.size})",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                }
                                items(matchingFolders) { folderPath ->
                                    val folderSongs = allSongs.filter { java.io.File(it.path).parent == folderPath }
                                    BentoCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                                if (folderSongs.isNotEmpty()) onSongClick(folderSongs.first())
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = BentoPrimary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = folderPath.substringAfterLast('/'),
                                                    color = BentoTextPrimary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = folderPath,
                                                    color = BentoTextSecondary,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Matching Playlists
                            if ((selectedFilter == SearchFilter.ALL || selectedFilter == SearchFilter.PLAYLISTS) && matchingPlaylists.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Playlists (${matchingPlaylists.size})",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                }
                                items(matchingPlaylists) { playlist ->
                                    BentoCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.QueueMusic,
                                                contentDescription = null,
                                                tint = BentoPrimary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(playlist.name, color = BentoTextPrimary, fontWeight = FontWeight.Bold)
                                                Text("${playlist.songCount} songs", color = BentoTextSecondary, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val totalSearchResults = matchingSongs.size + matchingArtists.size + matchingAlbums.size + matchingFolders.size + matchingPlaylists.size
                        FastScrollbar(
                            listState = searchResultsListState,
                            totalItems = totalSearchResults,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 2.dp, top = 8.dp, bottom = 120.dp)
                        )
                    }
                }
        }

        // Album Detail Sheet Overlay
        selectedAlbumForDetail?.let { (albumName, songs) ->
            com.example.ui.components.AlbumDetailSheet(
                albumName = albumName,
                artistName = songs.firstOrNull()?.displayArtist ?: "Unknown Artist",
                albumSongs = songs,
                playbackState = playbackState,
                onDismiss = { selectedAlbumForDetail = null },
                onPlayAll = { song, queue ->
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onPlaySongQueue(song, queue)
                    selectedAlbumForDetail = null
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
                    onAddToPlaylist(it)
                },
                onDeleteSong = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onDeleteSong(it)
                },
                onEditTags = onEditTags
            )
        }

        // Artist Detail Sheet Overlay
        selectedArtistForDetail?.let { (artistName, songs) ->
            com.example.ui.components.ArtistDetailSheet(
                artistName = artistName,
                artistSongs = songs,
                playbackState = playbackState,
                onDismiss = { selectedArtistForDetail = null },
                onPlayAll = { song, queue ->
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onPlaySongQueue(song, queue)
                    selectedArtistForDetail = null
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
                    onAddToPlaylist(it)
                },
                onDeleteSong = {
                    if (searchQuery.isNotBlank()) onAddRecentSearch(searchQuery)
                    onDeleteSong(it)
                },
                onEditTags = onEditTags
            )
        }
    }
    }
}
