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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import com.example.util.HapticUtils
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.example.ui.components.playlistGridReorderItem
import com.example.ui.components.playlistListReorderItem
import com.example.ui.components.rememberLazyGridPlaylistReorderState
import com.example.ui.components.rememberLazyListPlaylistReorderState
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    mostPlayedPlaylists: List<Playlist> = emptyList(),
    smartPlaylists: List<SmartPlaylist> = emptyList(),
    allSongs: List<Song> = emptyList(),
    playlistViewMode: String = "GRID",
    onToggleViewMode: () -> Unit = {},
    onCreatePlaylist: (String) -> Unit,
    onCreateSmartPlaylist: (SmartPlaylist) -> Unit = {},
    onPlaylistClick: (Playlist) -> Unit,
    onSmartPlaylistClick: (SmartPlaylist) -> Unit = {},
    onPlayNow: (Playlist) -> Unit = {},
    onPlayNext: (Playlist) -> Unit = {},
    onAddToQueue: (Playlist) -> Unit = {},
    onRenamePlaylist: (Long, String) -> Unit = { _, _ -> },
    onDeletePlaylist: (Long) -> Unit = {},
    onDeleteSmartPlaylist: (Long) -> Unit = {},
    onUpdatePlaylistArtwork: (Long, String?) -> Unit = { _, _ -> },
    onImportM3uPlaylist: (name: String, songs: List<Song>) -> Unit = { _, _ -> },
    onImportMultipleM3uPlaylists: ((List<Pair<String, List<Song>>>) -> Unit)? = null,
    onBackClick: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onReorderPlaylists: (List<Playlist>) -> Unit = {},
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
    var isParsingM3uFiles by remember { mutableStateOf(false) }
    var selectedPlaylistForArtwork by remember { mutableStateOf<Long?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var smartPlaylistToDelete by remember { mutableStateOf<SmartPlaylist?>(null) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

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
                            label = { Text("Playlist Name", color = BentoTextSecondary) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = BentoTextPrimary,
                                unfocusedTextColor = BentoTextPrimary,
                                focusedBorderColor = BentoPrimary,
                                unfocusedBorderColor = BentoBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = "Smart Playlists update automatically based on dynamic filters like Genre, Play Count, Recency, and Sorting.",
                            color = BentoTextSecondary,
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
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary)
                ) {
                    Text(if (isSmartSelected) "Configure Criteria" else "Create", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            }
        )
    }

    val m3uPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isParsingM3uFiles = true
            coroutineScope.launch {
                val (results, invalidFileCount) = withContext(Dispatchers.IO) {
                    val parsedList = mutableListOf<M3uImportResult>()
                    var ignoredCount = 0
                    val usedNamesInBatch = HashSet<String>()

                    uris.forEachIndexed { idx, uri ->
                        var fileName = ""
                        try {
                            context.contentResolver.query(
                                uri,
                                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                                null,
                                null,
                                null
                            )?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val colIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    if (colIdx >= 0) fileName = cursor.getString(colIdx) ?: ""
                                }
                            }
                        } catch (_: Exception) {}

                        if (fileName.isBlank()) {
                            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Playlist_${idx + 1}"
                        }
                        val cleanName = try { Uri.decode(fileName) } catch (_: Exception) { fileName }
                        val lowerName = cleanName.lowercase()
                        val hasM3uExtension = lowerName.endsWith(".m3u") || lowerName.endsWith(".m3u8")

                        try {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                val content = stream.bufferedReader(Charsets.UTF_8).use { reader ->
                                    val sb = StringBuilder()
                                    val buffer = CharArray(4096)
                                    var charsRead: Int
                                    var totalChars = 0
                                    while (reader.read(buffer).also { charsRead = it } != -1) {
                                        sb.append(buffer, 0, charsRead)
                                        totalChars += charsRead
                                        if (totalChars > 2 * 1024 * 1024) break
                                    }
                                    sb.toString()
                                }

                                val looksLikeM3uContent = content.contains("#EXTM3U", ignoreCase = true) ||
                                    content.contains("#EXTINF", ignoreCase = true)

                                if (!hasM3uExtension && !looksLikeM3uContent) {
                                    ignoredCount++
                                    return@forEachIndexed
                                }

                                var basePlaylistName = cleanName.substringBeforeLast('.').trim()
                                    .ifBlank { "Imported Playlist ${idx + 1}" }
                                if (!usedNamesInBatch.add(basePlaylistName.lowercase())) {
                                    basePlaylistName = "$basePlaylistName (${idx + 1})"
                                    usedNamesInBatch.add(basePlaylistName.lowercase())
                                }

                                val result = M3uParser.parseM3uContent(basePlaylistName, content, allSongs)
                                if (result.totalEntries > 0 || hasM3uExtension) {
                                    parsedList.add(result)
                                } else {
                                    ignoredCount++
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            ignoredCount++
                        }
                    }
                    parsedList to ignoredCount
                }

                isParsingM3uFiles = false

                if (invalidFileCount > 0 && results.isEmpty()) {
                    Toast.makeText(
                        context,
                        "Only .m3u / .m3u8 playlist files are supported ($invalidFileCount file(s) skipped).",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (invalidFileCount > 0) {
                    Toast.makeText(
                        context,
                        "Skipped $invalidFileCount non-playlist file(s). Found ${results.size} valid playlist(s).",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                if (results.isNotEmpty()) {
                    if (results.size == 1) {
                        importResultDialog = results.first()
                    } else {
                        multiImportResultDialog = results
                    }
                } else if (invalidFileCount == 0) {
                    Toast.makeText(context, "No valid playlists found in selected files", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val launchM3uPicker = {
        // Use "*/*" so Android's SAF file picker allows selecting multiple .m3u / .m3u8 files
        // across all OEM file managers (many OEMs classify .m3u8 as application/octet-stream or text/plain)
        m3uPickerLauncher.launch(arrayOf("*/*"))
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

    val currentLayout = PlaylistsLayout.fromId(playlistsLayout)
    when (currentLayout) {
        PlaylistsLayout.MAGAZINE_HERO -> {
            com.example.ui.screens.layouts.PlaylistsScreenMagazineHero(
                playlists = playlists,
                mostPlayedPlaylists = mostPlayedPlaylists,
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
                onImportM3u = launchM3uPicker,
                onBackClick = onBackClick,
                onOpenSearch = onOpenSearch,
                modifier = modifier
            )
        }
        PlaylistsLayout.COMPACT_LIST -> {
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
                onImportM3u = launchM3uPicker,
                onBackClick = onBackClick,
                onReorderPlaylists = onReorderPlaylists,
                modifier = modifier
            )
        }
        PlaylistsLayout.GLOW_CARDS -> {
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
                onImportM3u = launchM3uPicker,
                onReorderPlaylists = onReorderPlaylists,
                modifier = modifier
            )
        }
        PlaylistsLayout.BENTO_GRID -> {
            val bentoHeaderContent: @Composable () -> Unit = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 1. Bento Grid Top Bar (scrolls alongside playlists when user scrolls down)
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
                                shape = RoundedCornerShape(13.dp),
                                color = BentoCardSurface,
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
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
                                    text = "BENTO COLLECTIONS",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BentoPrimary,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "Playlists",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Surface(
                                onClick = onOpenSearch,
                                shape = CircleShape,
                                color = BentoCardSurface,
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
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

                            Surface(
                                onClick = onOpenFavorites,
                                shape = CircleShape,
                                color = BentoCardSurface,
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
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
                                onClick = onToggleViewMode,
                                shape = CircleShape,
                                color = BentoCardSurface,
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
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
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. Asymmetric Bento Mosaic Action Capsules (non-boxy pill & squircle dock)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Primary Pill Action: New Mix
                        Surface(
                            onClick = { showCreateDialog = true },
                            shape = RoundedCornerShape(50),
                            color = Color.Transparent,
                            modifier = Modifier
                                .weight(1.25f)
                                .height(50.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(BentoPrimary, BentoPrimary.copy(alpha = 0.78f))
                                    )
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = BentoOnPrimary.copy(alpha = 0.18f),
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Add, contentDescription = "New Playlist", tint = BentoOnPrimary, modifier = Modifier.size(16.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("New Mix", fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, color = BentoOnPrimary, maxLines = 1)
                                    Text("${playlists.size} curated", fontSize = 9.5.sp, color = BentoOnPrimary.copy(alpha = 0.82f), maxLines = 1)
                                }
                            }
                        }

                        // Frosted Capsule: Smart Hub
                        Surface(
                            onClick = { showSmartCriteriaDialog = true },
                            shape = RoundedCornerShape(50),
                            color = BentoCardDarkPurple.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "Smart Mix", tint = BentoPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text("Smart", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary, maxLines = 1)
                                    Text("${smartPlaylists.size} rules", fontSize = 9.5.sp, color = BentoTextSecondary, maxLines = 1)
                                }
                            }
                        }

                        // Circular Glass Orb: Import M3U
                        Surface(
                            onClick = launchM3uPicker,
                            shape = CircleShape,
                            color = BentoCardSurface.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
                            modifier = Modifier.size(50.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.FileDownload, contentDescription = "Import M3U", tint = BentoPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Smart Playlists Floating Capsule Carousel (when populated)
                    if (smartPlaylists.isNotEmpty()) {
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(smartPlaylists, key = { idx, smart -> "${smart.id}_$idx" }) { _, smart ->
                                val matchedCount = remember(smart, allSongs) { smart.filterSongs(allSongs).size }
                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.38f)),
                                    modifier = Modifier
                                        .width(210.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    BentoPrimary.copy(alpha = 0.20f),
                                                    BentoCardDarkPurple.copy(alpha = 0.92f)
                                                )
                                            )
                                        )
                                        .clickable { onSmartPlaylistClick(smart) }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = BentoPrimary.copy(alpha = 0.18f)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.AutoAwesome,
                                                        contentDescription = null,
                                                        tint = BentoPrimary,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                    Text(
                                                        text = "$matchedCount tracks",
                                                        fontSize = 10.sp,
                                                        color = BentoPrimary,
                                                        fontWeight = FontWeight.ExtraBold
                                                    )
                                                }
                                            }
                                            IconButton(
                                                onClick = { smartPlaylistToDelete = smart },
                                                modifier = Modifier.size(20.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Delete",
                                                    tint = BentoTextSecondary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = smart.name,
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            smart.getCriteriaBadges().take(2).forEach { badge ->
                                                Box(
                                                    modifier = Modifier
                                                        .background(BentoCardSurface.copy(alpha = 0.65f), RoundedCornerShape(50))
                                                        .padding(horizontal = 8.dp, vertical = 2.dp)
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
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }

            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                if (playlists.isEmpty()) {
                    LazyColumn(
                        contentPadding = PaddingValues(top = 14.dp, bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        item(key = "bento_scrollable_header", contentType = "header") {
                            bentoHeaderContent()
                        }
                        item(key = "bento_empty_state", contentType = "empty") {
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
                                        text = "Tap + to create custom playlists or use the import icon to import single or multiple .M3U / .M3U8 playlists.",
                                        fontSize = 12.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                            }
                        }
                    }
                } else if (playlistViewMode == "GRID") {
                    val gridState = rememberLazyGridState()
                    val gridReorderState = rememberLazyGridPlaylistReorderState(
                        context = context,
                        playlists = playlists,
                        gridState = gridState,
                        onReorderPlaylists = onReorderPlaylists
                    )
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(top = 14.dp, bottom = 120.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            item(
                                key = "bento_scrollable_header",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = "header"
                            ) {
                                bentoHeaderContent()
                            }
                            items(
                                items = gridReorderState.orderedPlaylists,
                                key = { it.id },
                                contentType = { "playlist_grid_card" }
                            ) { playlist ->
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
                                    onDelete = { playlistToDelete = playlist },
                                    modifier = Modifier.playlistGridReorderItem(gridReorderState, playlist.id)
                                )
                            }
                        }
                    }
                } else {
                    val listState = rememberLazyListState()
                    val listReorderState = rememberLazyListPlaylistReorderState(
                        context = context,
                        playlists = playlists,
                        listState = listState,
                        onReorderPlaylists = onReorderPlaylists
                    )
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(top = 14.dp, bottom = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            item(key = "bento_scrollable_header", contentType = "header") {
                                bentoHeaderContent()
                            }
                            items(
                                items = listReorderState.orderedPlaylists,
                                key = { it.id },
                                contentType = { "playlist_list_card" }
                            ) { playlist ->
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
                                    onDelete = { playlistToDelete = playlist },
                                    modifier = Modifier.playlistListReorderItem(listReorderState, playlist.id)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (isParsingM3uFiles) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Reading Playlist Files...", color = BentoTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = BentoPrimary,
                        strokeWidth = 2.5.dp
                    )
                    Text(
                        text = "Matching tracks against your library...",
                        fontSize = 13.5.sp,
                        color = BentoTextSecondary
                    )
                }
            },
            confirmButton = {},
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(16.dp)
        )
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
            title = { Text("M3U Import Summary", color = BentoTextPrimary, fontWeight = FontWeight.Bold) },
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
                        Toast.makeText(context, "Imported \"${result.playlistName}\" (${result.matchedSongs.size} songs)", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                ) {
                    Text("Import ${result.matchedSongs.size} Songs", color = BentoOnPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { importResultDialog = null }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    val multiResults = multiImportResultDialog
    if (multiResults != null) {
        val totalPlaylists = multiResults.size
        val totalSongsToImport = multiResults.sumOf { it.matchedSongs.size }
        val totalMissing = multiResults.sumOf { it.missingPaths.size }

        AlertDialog(
            onDismissRequest = { multiImportResultDialog = null },
            title = {
                Text(
                    "Batch Import Playlists ($totalPlaylists)",
                    color = BentoTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Selected $totalPlaylists playlist files:",
                        fontSize = 13.sp,
                        color = BentoTextSecondary
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .background(BentoCardDarkPurple.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .border(1.dp, BentoBorder.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(multiResults, key = { idx, r -> "${r.playlistName}_$idx" }) { _, r ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = r.playlistName,
                                    fontSize = 13.sp,
                                    color = BentoTextPrimary,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                                Text(
                                    text = "${r.matchedSongs.size}/${r.totalEntries} matched",
                                    fontSize = 12.sp,
                                    color = BentoPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Text(
                        text = "Total $totalSongsToImport matched songs will be imported across $totalPlaylists playlists" +
                            if (totalMissing > 0) " ($totalMissing missing tracks skipped)." else ".",
                        fontSize = 12.sp,
                        color = BentoTextSecondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val batchPayload = multiResults.map { it.playlistName to it.matchedSongs }
                        if (onImportMultipleM3uPlaylists != null) {
                            onImportMultipleM3uPlaylists(batchPayload)
                        } else {
                            multiResults.forEach { r ->
                                onImportM3uPlaylist(r.playlistName, r.matchedSongs)
                            }
                        }
                        multiImportResultDialog = null
                        Toast.makeText(
                            context,
                            "Imported $totalPlaylists playlists ($totalSongsToImport tracks) successfully",
                            Toast.LENGTH_SHORT
                        ).show()
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
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(16.dp)
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
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val durationMins = playlist.totalDurationMs / (1000 * 60)
    val timeSubtitle = if (durationMins >= 60) "${String.format("%.1f", durationMins / 60.0)} hrs" else "$durationMins mins"
    val trackSubtitle = "${playlist.songCount} ${if (playlist.songCount == 1) "track" else "tracks"}"

    // Full-bleed Bento Mosaic Tile with organic 28.dp squircle silhouette and glassmorphic bottom scrim
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.88f)
            .clip(RoundedCornerShape(28.dp))
            .background(BentoCardSurface)
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.22f),
                        BentoBorder.copy(alpha = 0.35f)
                    )
                ),
                RoundedCornerShape(28.dp)
            )
            .clickable(onClick = onClick)
    ) {
        // Full-bleed cover artwork filling the entire Bento tile
        PlaylistCover(
            playlist = playlist,
            playlistSongs = emptyList(),
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 28
        )

        // Multi-stop atmospheric gradient scrim for crisp typography without boxy card frames
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.28f),
                            0.42f to Color.Transparent,
                            0.72f to Color.Black.copy(alpha = 0.68f),
                            1.0f to Color.Black.copy(alpha = 0.92f)
                        )
                    )
                )
        )

        // Top-left Floating Frosted Capsule: Track Count
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.52f),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.22f)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = BentoPrimary,
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = "${playlist.songCount}",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }
        }

        // Top-end Floating Options Button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.48f))
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (showMenu) {
                PlaylistActionSheet(
                    playlist = playlist,
                    onDismiss = { showMenu = false },
                    onOpenPlaylist = onClick,
                    onPlayNow = onPlayNow,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onRename = onRename,
                    onChangeCover = onChangeCover,
                    onRemoveCover = if (playlist.artworkUri != null) onRemoveCover else null,
                    onDelete = onDelete
                )
            }
        }

        // Bottom Overlay Row: Title + Duration on Left, Floating Play Orb on Right
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = playlist.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$trackSubtitle • $timeSubtitle",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.76f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                onClick = onPlayNow,
                shape = CircleShape,
                color = BentoPrimary,
                shadowElevation = 6.dp,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Playlist",
                        tint = BentoOnPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
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
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
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

                if (showMenu) {
                    PlaylistActionSheet(
                        playlist = playlist,
                        onDismiss = { showMenu = false },
                        onOpenPlaylist = onClick,
                        onPlayNow = onPlayNow,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onRename = onRename,
                        onChangeCover = onChangeCover,
                        onRemoveCover = if (playlist.artworkUri != null) onRemoveCover else null,
                        onDelete = onDelete
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistActionSheet(
    playlist: Playlist,
    onDismiss: () -> Unit,
    onOpenPlaylist: (() -> Unit)? = null,
    onPlayNow: (() -> Unit)? = null,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onRename: () -> Unit,
    onChangeCover: () -> Unit,
    onRemoveCover: (() -> Unit)? = null,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val durationMins = playlist.totalDurationMs / (1000 * 60)
    val timeSubtitle = if (durationMins >= 60) "${String.format("%.1f", durationMins / 60.0)} hrs" else "$durationMins mins"
    val trackSubtitle = "${playlist.songCount} ${if (playlist.songCount == 1) "track" else "tracks"}"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BentoCardSurface,
        scrimColor = Color.Black.copy(alpha = 0.62f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header with Playlist Cover, Name, Metadata, and Close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlaylistCover(
                    playlist = playlist,
                    playlistSongs = emptyList(),
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    cornerRadius = 14
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "$trackSubtitle • $timeSubtitle",
                        fontSize = 12.5.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismiss()
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(BentoCardDarkPurple)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = BentoTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Quick Playback Action Pills Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onPlayNow != null) {
                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onDismiss()
                            onPlayNow()
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = BentoPrimary,
                        modifier = Modifier.weight(1.1f).height(42.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = BentoOnPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Play Now",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoOnPrimary,
                                maxLines = 1
                            )
                        }
                    }
                }

                Surface(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismiss()
                        onPlayNext()
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = BentoCardDarkPurple,
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Play Next",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary,
                            maxLines = 1
                        )
                    }
                }

                Surface(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismiss()
                        onAddToQueue()
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = BentoCardDarkPurple,
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Queue",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary,
                            maxLines = 1
                        )
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(6.dp))

            if (onOpenPlaylist != null) {
                PlaylistActionOptionRow(
                    icon = Icons.Default.QueueMusic,
                    title = "Open Playlist",
                    subtitle = "View & reorder tracks inside this collection",
                    iconTint = BentoPrimary,
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismiss()
                        onOpenPlaylist()
                    }
                )
            }

            PlaylistActionOptionRow(
                icon = Icons.Default.Edit,
                title = "Rename Playlist",
                subtitle = "Change the display title of this playlist",
                iconTint = BentoPrimary,
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    onDismiss()
                    onRename()
                }
            )

            PlaylistActionOptionRow(
                icon = Icons.Default.Image,
                title = "Change Cover Artwork",
                subtitle = "Choose a custom image from your gallery",
                iconTint = BentoPrimary,
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    onDismiss()
                    onChangeCover()
                }
            )

            if (onRemoveCover != null && playlist.artworkUri != null) {
                PlaylistActionOptionRow(
                    icon = Icons.Default.HideImage,
                    title = "Remove Custom Cover",
                    subtitle = "Revert to automatic album artwork mosaic",
                    iconTint = BentoTextSecondary,
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismiss()
                        onRemoveCover()
                    }
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))

            PlaylistActionOptionRow(
                icon = Icons.Default.Delete,
                title = "Delete Playlist",
                subtitle = "Remove this playlist (audio files stay on device)",
                iconTint = BentoAccentPinkText,
                textColor = BentoAccentPinkText,
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    onDismiss()
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun PlaylistActionOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color,
    textColor: Color = BentoTextPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(iconTint.copy(alpha = 0.14f))
                .border(0.5.dp, iconTint.copy(alpha = 0.32f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = BentoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}