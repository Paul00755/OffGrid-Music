package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ViewList
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
import com.example.data.model.SmartPlaylist
import com.example.data.model.Song
import com.example.scanner.M3uImportResult
import com.example.scanner.M3uParser
import com.example.ui.components.BentoCard
import com.example.ui.components.FastScrollbar
import com.example.ui.components.PlaylistCover
import com.example.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    smartPlaylists: List<com.example.data.model.SmartPlaylist> = emptyList(),
    allSongs: List<Song> = emptyList(),
    playlistViewMode: String = "GRID",
    onToggleViewMode: () -> Unit = {},
    onCreatePlaylist: (String) -> Unit,
    onCreateSmartPlaylist: (com.example.data.model.SmartPlaylist) -> Unit = {},
    onPlaylistClick: (Playlist) -> Unit,
    onSmartPlaylistClick: (com.example.data.model.SmartPlaylist) -> Unit = {},
    onPlayNow: (Playlist) -> Unit = {},
    onPlayNext: (Playlist) -> Unit = {},
    onAddToQueue: (Playlist) -> Unit = {},
    onRenamePlaylist: (Long, String) -> Unit = { _, _ -> },
    onDeletePlaylist: (Long) -> Unit = {},
    onDeleteSmartPlaylist: (Long) -> Unit = {},
    onUpdatePlaylistArtwork: (Long, String?) -> Unit = { _, _ -> },
    onImportM3uPlaylist: (name: String, songs: List<Song>) -> Unit = { _, _ -> },
    onBackClick: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    playlistsLayout: String = "BENTO_GRID",
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSmartCriteriaDialog by remember { mutableStateOf(false) }
    var playlistNameText by remember { mutableStateOf("") }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var renamePlaylistText by remember { mutableStateOf("") }
    var importResultDialog by remember { mutableStateOf<M3uImportResult?>(null) }
    var multiImportResultDialog by remember { mutableStateOf<List<M3uImportResult>?>(null) }
    var selectedPlaylistForArtwork by remember { mutableStateOf<Long?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var smartPlaylistToDelete by remember { mutableStateOf<SmartPlaylist?>(null) }

    val context = LocalContext.current

    // Smart Criteria Dialog
    if (showSmartCriteriaDialog) {
        com.example.ui.components.SmartPlaylistCriteriaDialog(
            allSongs = allSongs,
            onDismiss = { showSmartCriteriaDialog = false },
            onCreateSmartPlaylist = { smart ->
                onCreateSmartPlaylist(smart)
                showSmartCriteriaDialog = false
            }
        )
    }

    // New Playlist Type Selector Dialog
    if (showCreateDialog) {
        var isSmartSelected by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = BentoCardSurface,
            title = { Text("Create New Playlist", color = BentoTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !isSmartSelected,
                            onClick = { isSmartSelected = false },
                            label = { Text("Standard", fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BentoPrimary,
                                selectedLabelColor = BentoOnPrimary,
                                containerColor = BentoCardSurface,
                                labelColor = BentoTextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = isSmartSelected,
                            onClick = { isSmartSelected = true },
                            label = { Text("✨ Smart Playlist", fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BentoPrimary,
                                selectedLabelColor = BentoOnPrimary,
                                containerColor = BentoCardSurface,
                                labelColor = BentoTextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (!isSmartSelected) {
                        OutlinedTextField(
                            value = playlistNameText,
                            onValueChange = { playlistNameText = it },
                            label = { Text("Playlist Name", color = Color.White.copy(alpha = 0.7f)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = BentoPrimary,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = "Smart Playlists update automatically based on dynamic filters like Genre, Play Count, Recency, and Sorting.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCreateDialog = false
                        if (isSmartSelected) {
                            showSmartCriteriaDialog = true
                        } else {
                            if (playlistNameText.isNotBlank()) {
                                onCreatePlaylist(playlistNameText.trim())
                                playlistNameText = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = Color.Black)
                ) {
                    Text(if (isSmartSelected) "Configure Criteria" else "Create", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }

    val m3uPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val results = mutableListOf<M3uImportResult>()
            uris.forEach { uri ->
                var fileName = ""
                try {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) fileName = cursor.getString(idx) ?: ""
                        }
                    }
                } catch (_: Exception) {}
                if (fileName.isBlank()) {
                    fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Playlist_${System.currentTimeMillis()}"
                }
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val content = stream.bufferedReader().readText()
                        val playlistName = fileName.substringBeforeLast('.')
                        val result = M3uParser.parseM3uContent(playlistName, content, allSongs)
                        results.add(result)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (results.isNotEmpty()) {
                if (results.size == 1) {
                    importResultDialog = results.first()
                } else {
                    multiImportResultDialog = results
                }
            } else {
                Toast.makeText(context, "No valid playlists found in selected files", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val artworkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val playlistId = selectedPlaylistForArtwork
        if (uri != null && playlistId != null) {
            onUpdatePlaylistArtwork(playlistId, uri.toString())
            Toast.makeText(context, "Updated playlist cover", Toast.LENGTH_SHORT).show()
        }
        selectedPlaylistForArtwork = null
    }

    val currentLayout = com.example.ui.theme.PlaylistsLayout.fromId(playlistsLayout)
    when (currentLayout) {
        com.example.ui.theme.PlaylistsLayout.MAGAZINE_HERO -> {
            com.example.ui.screens.layouts.PlaylistsScreenMagazineHero(
                playlists = playlists,
                smartPlaylists = smartPlaylists,
                onCreatePlaylist = { showCreateDialog = true },
                onCreateSmartPlaylist = { showSmartCriteriaDialog = true },
                onPlaylistClick = onPlaylistClick,
                onSmartPlaylistClick = onSmartPlaylistClick,
                onPlayNow = onPlayNow,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onRenamePlaylist = { pl ->
                    playlistToRename = pl
                    renamePlaylistText = pl.name
                },
                onDeletePlaylist = { pl -> playlistToDelete = pl },
                onDeleteSmartPlaylist = { sm -> smartPlaylistToDelete = sm },
                onUpdatePlaylistArtwork = { pl ->
                    selectedPlaylistForArtwork = pl.id
                    artworkPickerLauncher.launch("image/*")
                },
                onImportM3u = { m3uPickerLauncher.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "text/plain", "application/octet-stream", "*/*")) },
                modifier = modifier
            )
        }
        com.example.ui.theme.PlaylistsLayout.COMPACT_LIST -> {
            com.example.ui.screens.layouts.PlaylistsScreenCompactList(
                playlists = playlists,
                smartPlaylists = smartPlaylists,
                onCreatePlaylist = { showCreateDialog = true },
                onCreateSmartPlaylist = { showSmartCriteriaDialog = true },
                onPlaylistClick = onPlaylistClick,
                onSmartPlaylistClick = onSmartPlaylistClick,
                onPlayNow = onPlayNow,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onRenamePlaylist = { pl ->
                    playlistToRename = pl
                    renamePlaylistText = pl.name
                },
                onDeletePlaylist = { pl -> playlistToDelete = pl },
                onDeleteSmartPlaylist = { sm -> smartPlaylistToDelete = sm },
                onUpdatePlaylistArtwork = { pl ->
                    selectedPlaylistForArtwork = pl.id
                    artworkPickerLauncher.launch("image/*")
                },
                onImportM3u = { m3uPickerLauncher.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "text/plain", "application/octet-stream", "*/*")) },
                modifier = modifier
            )
        }
        com.example.ui.theme.PlaylistsLayout.GLOW_CARDS -> {
            com.example.ui.screens.layouts.PlaylistsScreenGlowCards(
                playlists = playlists,
                smartPlaylists = smartPlaylists,
                onCreatePlaylist = { showCreateDialog = true },
                onCreateSmartPlaylist = { showSmartCriteriaDialog = true },
                onPlaylistClick = onPlaylistClick,
                onSmartPlaylistClick = onSmartPlaylistClick,
                onPlayNow = onPlayNow,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onRenamePlaylist = { pl ->
                    playlistToRename = pl
                    renamePlaylistText = pl.name
                },
                onDeletePlaylist = { pl -> playlistToDelete = pl },
                onDeleteSmartPlaylist = { sm -> smartPlaylistToDelete = sm },
                onUpdatePlaylistArtwork = { pl ->
                    selectedPlaylistForArtwork = pl.id
                    artworkPickerLauncher.launch("image/*")
                },
                onImportM3u = { m3uPickerLauncher.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "text/plain", "application/octet-stream", "*/*")) },
                modifier = modifier
            )
        }
        com.example.ui.theme.PlaylistsLayout.BENTO_GRID -> {
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
                        text = "Playlists",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${playlists.size + smartPlaylists.size} collections",
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Search Button
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

                // Favorites Button
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

                // View Mode Toggle Button (Grid vs List)
                Surface(
                    onClick = onToggleViewMode,
                    shape = CircleShape,
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (playlistViewMode == "GRID") Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle Grid/List View",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                // Import M3U Button
                Surface(
                    onClick = { m3uPickerLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "audio/m3u", "text/plain")) },
                    shape = CircleShape,
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Import M3U",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                // New Playlist Button
                Surface(
                    onClick = { showCreateDialog = true },
                    shape = CircleShape,
                    color = BentoPrimary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.32f)),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Playlist",
                            tint = BentoPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Smart Playlists Hub Section
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Smart Playlists Hub",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                }

                TextButton(onClick = { showSmartCriteriaDialog = true }) {
                    Text("+ Create", color = BentoPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (smartPlaylists.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSmartCriteriaDialog = true },
                    colors = CardDefaults.cardColors(containerColor = BentoCardDarkPurple),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BentoPrimary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = BentoPrimary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Build Dynamic Smart Playlists",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = "Auto-generate playlists by genre, play count, recency & limit.",
                                fontSize = 12.sp,
                                color = BentoTextSecondary
                            )
                        }
                    }
                }
            } else {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(smartPlaylists, key = { idx, smart -> "${smart.id}_$idx" }) { _, smart ->
                        val matchedCount = remember(smart, allSongs) { smart.filterSongs(allSongs).size }
                        Card(
                            modifier = Modifier
                                .width(210.dp)
                                .clickable { onSmartPlaylistClick(smart) },
                            colors = CardDefaults.cardColors(containerColor = BentoCardDarkPurple),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    IconButton(
                                        onClick = { smartPlaylistToDelete = smart },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.Close,
                                            contentDescription = "Delete",
                                            tint = BentoTextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = smart.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Text(
                                    text = "$matchedCount tracks matched",
                                    fontSize = 12.sp,
                                    color = BentoPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    smart.getCriteriaBadges().take(3).forEach { badge ->
                                        Box(
                                            modifier = Modifier
                                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = badge,
                                                fontSize = 9.sp,
                                                color = BentoTextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (playlists.isEmpty()) {

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BentoCardSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No Playlists Created",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "Tap + to create custom playlists or use the import icon to import .M3U / .M3U8 playlists.",
                        fontSize = 12.sp,
                        color = BentoTextSecondary
                    )
                }
            }
        } else {
            if (playlistViewMode == "GRID") {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    items(playlists, key = { it.id }, contentType = { "playlist_grid_card" }) { playlist ->
                        PlaylistGridCard(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist) },
                            onPlayNow = { onPlayNow(playlist) },
                            onPlayNext = { onPlayNext(playlist) },
                            onAddToQueue = { onAddToQueue(playlist) },
                            onRename = {
                                playlistToRename = playlist
                                renamePlaylistText = playlist.name
                            },
                            onChangeCover = {
                                selectedPlaylistForArtwork = playlist.id
                                artworkPickerLauncher.launch("image/*")
                            },
                            onRemoveCover = { onUpdatePlaylistArtwork(playlist.id, null) },
                            onDelete = { playlistToDelete = playlist }
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(playlists, key = { it.id }, contentType = { "playlist_list_card" }) { playlist ->
                            PlaylistListCard(
                                playlist = playlist,
                                onClick = { onPlaylistClick(playlist) },
                                onPlayNow = { onPlayNow(playlist) },
                                onPlayNext = { onPlayNext(playlist) },
                                onAddToQueue = { onAddToQueue(playlist) },
                                onRename = {
                                    playlistToRename = playlist
                                    renamePlaylistText = playlist.name
                                },
                                onChangeCover = {
                                    selectedPlaylistForArtwork = playlist.id
                                    artworkPickerLauncher.launch("image/*")
                                },
                                onRemoveCover = { onUpdatePlaylistArtwork(playlist.id, null) },
                                onDelete = { playlistToDelete = playlist }
                            )
                        }
                    }

                    FastScrollbar(
                        listState = listState,
                        totalItems = playlists.size,
                        bottomPadding = 120.dp,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}
}
}

    playlistToRename?.let { targetPlaylist ->
        AlertDialog(
            onDismissRequest = { playlistToRename = null },
            title = { Text("Rename Playlist", color = BentoTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renamePlaylistText,
                    onValueChange = { renamePlaylistText = it },
                    label = { Text("Playlist Name", color = BentoTextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = BentoTextPrimary,
                        unfocusedTextColor = BentoTextPrimary,
                        focusedBorderColor = BentoPrimary,
                        unfocusedBorderColor = BentoCardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renamePlaylistText.trim()
                        if (trimmed.isNotBlank()) {
                            onRenamePlaylist(targetPlaylist.id, trimmed)
                            playlistToRename = null
                            Toast.makeText(context, "Playlist renamed to \"$trimmed\"", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                ) {
                    Text("Save", color = BentoOnPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToRename = null }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    playlistToDelete?.let { targetPlaylist ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete playlist \"${targetPlaylist.name}\"?", color = BentoTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will delete the playlist from your library. Music files will not be deleted from your device.",
                    fontSize = 14.sp,
                    color = BentoTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        playlistToDelete = null
                        onDeletePlaylist(targetPlaylist.id)
                        Toast.makeText(context, "Playlist deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoAccentPinkText)
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    smartPlaylistToDelete?.let { targetSmart ->
        AlertDialog(
            onDismissRequest = { smartPlaylistToDelete = null },
            title = { Text("Delete smart playlist \"${targetSmart.name}\"?", color = BentoTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will delete the smart playlist definition. Music files will not be deleted.",
                    fontSize = 14.sp,
                    color = BentoTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        smartPlaylistToDelete = null
                        onDeleteSmartPlaylist(targetSmart.id)
                        Toast.makeText(context, "Smart playlist deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoAccentPinkText)
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { smartPlaylistToDelete = null }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    val result = importResultDialog
    if (result != null) {
        AlertDialog(
            onDismissRequest = { importResultDialog = null },
            title = { Text("M3U Import Summary", color = BentoTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Playlist Name: ${result.playlistName}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                    Text("Total Entries in File: ${result.totalEntries}", fontSize = 13.sp, color = BentoTextSecondary)
                    Text("Matched Songs in Library: ${result.matchedSongs.size}", fontSize = 13.sp, color = BentoPrimary)
                    Text("Missing Tracks: ${result.missingPaths.size}", fontSize = 13.sp, color = BentoAccentPinkText)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImportM3uPlaylist(result.playlistName, result.matchedSongs)
                        importResultDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                ) {
                    Text("Import ${result.matchedSongs.size} Songs", color = BentoOnPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { importResultDialog = null }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface
        )
    }

    val multiResults = multiImportResultDialog
    if (multiResults != null) {
        val totalPlaylists = multiResults.size
        val totalSongsToImport = multiResults.sumOf { it.matchedSongs.size }

        AlertDialog(
            onDismissRequest = { multiImportResultDialog = null },
            title = { Text("Batch Import Playlists ($totalPlaylists)", color = BentoTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Found $totalPlaylists valid playlist files:", fontSize = 13.sp, color = BentoTextSecondary)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .background(BentoCardDarkPurple.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        multiResults.forEach { r ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(r.playlistName, fontSize = 13.sp, color = BentoTextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                                Text("${r.matchedSongs.size} songs", fontSize = 12.sp, color = BentoPrimary)
                            }
                        }
                    }
                    Text("Total $totalSongsToImport matched songs will be imported across $totalPlaylists playlists.", fontSize = 12.sp, color = BentoTextSecondary)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        multiResults.forEach { r ->
                            onImportM3uPlaylist(r.playlistName, r.matchedSongs)
                        }
                        multiImportResultDialog = null
                        Toast.makeText(context, "Imported $totalPlaylists playlists successfully", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                ) {
                    Text("Import All ($totalPlaylists)", color = BentoOnPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { multiImportResultDialog = null }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface
        )
    }
}

@Composable
private fun PlaylistGridCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onPlayNow: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onRename: () -> Unit = {},
    onChangeCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(BentoCardSurface)
            .border(1.dp, BentoBorder, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                PlaylistCover(
                    playlist = playlist,
                    playlistSongs = emptyList(),
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 16
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                ) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(BentoCardSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Play Now", color = BentoTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onPlayNow()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Play Next", color = BentoTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onPlayNext()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Queue", color = BentoTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.QueueMusic,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onAddToQueue()
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.White.copy(alpha = 0.1f))
                        DropdownMenuItem(
                            text = { Text("Open Playlist", color = BentoTextPrimary) },
                            onClick = {
                                showMenu = false
                                onClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename Playlist", color = BentoTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Change Cover", color = BentoTextPrimary) },
                            onClick = {
                                showMenu = false
                                onChangeCover()
                            }
                        )
                        if (playlist.artworkUri != null) {
                            DropdownMenuItem(
                                text = { Text("Remove Custom Cover", color = BentoTextSecondary) },
                                onClick = {
                                    showMenu = false
                                    onRemoveCover()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete Playlist", color = BentoAccentPinkText) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val durationMins = playlist.totalDurationMs / (1000 * 60)
            val timeSubtitle = if (durationMins >= 60) "${String.format("%.1f", durationMins / 60.0)} hrs" else "$durationMins mins"
            val trackSubtitle = "${playlist.songCount} ${if (playlist.songCount == 1) "track" else "tracks"}"

            Text(
                text = playlist.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = BentoTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = "$trackSubtitle • $timeSubtitle",
                fontSize = 12.sp,
                color = BentoTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlaylistListCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onPlayNow: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onRename: () -> Unit = {},
    onChangeCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BentoCardSurface)
            .border(1.dp, BentoBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlaylistCover(
                playlist = playlist,
                playlistSongs = emptyList(),
                modifier = Modifier.size(60.dp),
                cornerRadius = 12
            )
            Spacer(modifier = Modifier.width(16.dp))
            val durationMins = playlist.totalDurationMs / (1000 * 60)
            val timeSubtitle = if (durationMins >= 60) "${String.format("%.1f", durationMins / 60.0)} hrs" else "$durationMins mins"
            val trackSubtitle = "${playlist.songCount} ${if (playlist.songCount == 1) "track" else "tracks"}"

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$trackSubtitle • $timeSubtitle",
                    fontSize = 12.sp,
                    color = BentoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = BentoTextSecondary
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(BentoCardSurface)
                ) {
                    DropdownMenuItem(
                        text = { Text("Play Now", color = BentoTextPrimary) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onPlayNow()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Play Next", color = BentoTextPrimary) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onPlayNext()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue", color = BentoTextPrimary) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.QueueMusic,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onAddToQueue()
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.White.copy(alpha = 0.1f))
                    DropdownMenuItem(
                        text = { Text("Open Playlist", color = BentoTextPrimary) },
                        onClick = {
                            showMenu = false
                            onClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename Playlist", color = BentoTextPrimary) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Change Cover", color = BentoTextPrimary) },
                        onClick = {
                            showMenu = false
                            onChangeCover()
                        }
                    )
                    if (playlist.artworkUri != null) {
                        DropdownMenuItem(
                            text = { Text("Remove Custom Cover", color = BentoTextSecondary) },
                            onClick = {
                                showMenu = false
                                onRemoveCover()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete Playlist", color = BentoAccentPinkText) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
