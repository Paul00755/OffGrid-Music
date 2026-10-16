package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.model.Song
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TagEditorDialog(
    song: Song,
    initialLyrics: String = "",
    onDismiss: () -> Unit,
    onSaveTags: (title: String, artist: String, album: String, genre: String, year: Int, trackNumber: Int, artworkUri: String?, lyrics: String) -> Unit
) {
    var title by remember(song.id) { mutableStateOf(song.title.ifBlank { song.displayTitle }) }
    var artist by remember(song.id) { mutableStateOf(song.artist.ifBlank { song.displayArtist }) }
    var album by remember(song.id) { mutableStateOf(song.album.ifBlank { song.displayAlbum }) }
    var genre by remember(song.id) { mutableStateOf(song.genre.ifBlank { "Music" }) }
    var yearText by remember(song.id) { mutableStateOf(if (song.year > 0) song.year.toString() else "") }
    var trackNumberText by remember(song.id) { mutableStateOf(if (song.trackNumber > 0) song.trackNumber.toString() else "") }
    var artworkUri by remember(song.id) { mutableStateOf<String?>(song.artworkUri) }
    var lyrics by remember(song.id) { mutableStateOf(initialLyrics) }
    val context = LocalContext.current

    LaunchedEffect(song.id) {
        if (artworkUri.isNullOrBlank()) {
            val resolved = ArtworkResolutionCache.getOrCheckDisk(context, song.id)
                ?: com.example.util.ArtworkManager.getOrExtractArtworkUri(context, song.id, song.path, song.albumId, song.album)
            if (!resolved.isNullOrBlank()) {
                artworkUri = resolved
            }
        }
    }
    var showConfirmSave by remember { mutableStateOf(false) }
    var showArtworkSearchDialog by remember { mutableStateOf(false) }
    var artworkSearchQuery by remember(song.id) { mutableStateOf("${song.displayTitle} ${song.displayArtist}".trim()) }
    var isSearchingArtwork by remember { mutableStateOf(false) }
    var artworkCandidates by remember(song.id) { mutableStateOf<List<com.example.util.ArtworkCandidate>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(song.id, initialLyrics) {
        if (initialLyrics.isNotBlank() && lyrics.isBlank()) {
            lyrics = initialLyrics
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            artworkUri = uri.toString()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(24.dp)),
            color = BentoCardSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(BentoPrimary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.EditNote,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Tag & Lyrics Editor",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = "Edit song metadata & embedded lyrics",
                                fontSize = 12.sp,
                                color = BentoTextSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(BentoCardDarkPurple)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                // Form Scroll Area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Artwork / Thumbnail Section
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Thumbnail Image",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextSecondary,
                            modifier = Modifier
                                .align(Alignment.Start)
                                .padding(bottom = 8.dp)
                        )

                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(BentoCardDarkPurple)
                                .border(1.dp, BentoCardBorder, RoundedCornerShape(16.dp))
                                .clickable { imagePickerLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            if (!artworkUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = artworkUri,
                                    contentDescription = "Song Thumbnail",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.35f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoCamera,
                                        contentDescription = "Change Thumbnail",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AddPhotoAlternate,
                                        contentDescription = "Select Artwork",
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Pick Image", fontSize = 11.sp, color = BentoTextSecondary)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Gallery", fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    showArtworkSearchDialog = true
                                    if (artworkCandidates.isEmpty()) {
                                        val q = "${title.ifBlank { song.displayTitle }} ${artist.ifBlank { song.displayArtist }}".trim()
                                        artworkSearchQuery = q
                                        isSearchingArtwork = true
                                        coroutineScope.launch {
                                            artworkCandidates = com.example.util.OnlineArtistImageFetcher.searchArtworkCandidates(q)
                                            isSearchingArtwork = false
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Search Online Cover", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (!artworkUri.isNullOrBlank()) {
                            TextButton(
                                onClick = { artworkUri = null },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Remove artwork", fontSize = 11.sp, color = BentoAccentPinkText)
                            }
                        }
                    }

                    // Track Name Field
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Track Name / Title", color = BentoTextSecondary) },
                        leadingIcon = { Icon(Icons.Default.Title, contentDescription = null, tint = BentoPrimary) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = BentoTextPrimary,
                            unfocusedTextColor = BentoTextPrimary,
                            focusedBorderColor = BentoPrimary,
                            unfocusedBorderColor = BentoCardBorder,
                            focusedContainerColor = BentoCardDarkPurple,
                            unfocusedContainerColor = BentoCardDarkPurple
                        )
                    )

                    // Artist Name Field
                    OutlinedTextField(
                        value = artist,
                        onValueChange = { artist = it },
                        label = { Text("Artist Name", color = BentoTextSecondary) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = BentoPrimary) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = BentoTextPrimary,
                            unfocusedTextColor = BentoTextPrimary,
                            focusedBorderColor = BentoPrimary,
                            unfocusedBorderColor = BentoCardBorder,
                            focusedContainerColor = BentoCardDarkPurple,
                            unfocusedContainerColor = BentoCardDarkPurple
                        )
                    )

                    // Album Name Field
                    OutlinedTextField(
                        value = album,
                        onValueChange = { album = it },
                        label = { Text("Album Name", color = BentoTextSecondary) },
                        leadingIcon = { Icon(Icons.Default.Album, contentDescription = null, tint = BentoPrimary) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = BentoTextPrimary,
                            unfocusedTextColor = BentoTextPrimary,
                            focusedBorderColor = BentoPrimary,
                            unfocusedBorderColor = BentoCardBorder,
                            focusedContainerColor = BentoCardDarkPurple,
                            unfocusedContainerColor = BentoCardDarkPurple
                        )
                    )

                    // Genre Field
                    OutlinedTextField(
                        value = genre,
                        onValueChange = { genre = it },
                        label = { Text("Genre", color = BentoTextSecondary) },
                        leadingIcon = { Icon(Icons.Default.Category, contentDescription = null, tint = BentoPrimary) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = BentoTextPrimary,
                            unfocusedTextColor = BentoTextPrimary,
                            focusedBorderColor = BentoPrimary,
                            unfocusedBorderColor = BentoCardBorder,
                            focusedContainerColor = BentoCardDarkPurple,
                            unfocusedContainerColor = BentoCardDarkPurple
                        )
                    )

                    // Year and Track Number Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = yearText,
                            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) yearText = it },
                            label = { Text("Year", color = BentoTextSecondary) },
                            leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null, tint = BentoPrimary) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = BentoTextPrimary,
                                unfocusedTextColor = BentoTextPrimary,
                                focusedBorderColor = BentoPrimary,
                                unfocusedBorderColor = BentoCardBorder,
                                focusedContainerColor = BentoCardDarkPurple,
                                unfocusedContainerColor = BentoCardDarkPurple
                            )
                        )

                        OutlinedTextField(
                            value = trackNumberText,
                            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) trackNumberText = it },
                            label = { Text("Track #", color = BentoTextSecondary) },
                            leadingIcon = { Icon(Icons.Default.FormatListNumbered, contentDescription = null, tint = BentoPrimary) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = BentoTextPrimary,
                                unfocusedTextColor = BentoTextPrimary,
                                focusedBorderColor = BentoPrimary,
                                unfocusedBorderColor = BentoCardBorder,
                                focusedContainerColor = BentoCardDarkPurple,
                                unfocusedContainerColor = BentoCardDarkPurple
                            )
                        )
                    }

                    // Embedded Lyrics Field
                    OutlinedTextField(
                        value = lyrics,
                        onValueChange = { lyrics = it },
                        label = { Text("Embedded Lyrics (Plain or synced .lrc)", color = BentoTextSecondary) },
                        placeholder = { Text("Paste plain text lyrics or synced LRC lines ([00:12.34] Lyrics text)...", color = BentoTextSecondary.copy(alpha = 0.6f)) },
                        leadingIcon = { Icon(Icons.Default.Lyrics, contentDescription = null, tint = BentoPrimary) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp),
                        singleLine = false,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = BentoTextPrimary,
                            unfocusedTextColor = BentoTextPrimary,
                            focusedBorderColor = BentoPrimary,
                            unfocusedBorderColor = BentoCardBorder,
                            focusedContainerColor = BentoCardDarkPurple,
                            unfocusedContainerColor = BentoCardDarkPurple
                        )
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(bottom = 12.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoTextSecondary)
                    ) {
                        Text("Cancel", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { showConfirmSave = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary
                        )
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Tags", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Confirmation Dialog before saving changes to local files/database
        if (showConfirmSave) {
            AlertDialog(
                onDismissRequest = { showConfirmSave = false },
                title = {
                    Text(
                        text = "Confirm Tag Updates",
                        color = BentoTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to save these tag & lyrics changes? This will permanently update local file tags and app metadata.",
                        color = BentoTextSecondary,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showConfirmSave = false
                            val y = yearText.trim().toIntOrNull() ?: 0
                            val trk = trackNumberText.trim().toIntOrNull() ?: 0
                            onSaveTags(title.trim(), artist.trim(), album.trim(), genre.trim(), y, trk, artworkUri, lyrics.trim())
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary
                        )
                    ) {
                        Text("Confirm & Save", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmSave = false }) {
                        Text("Cancel", color = BentoTextSecondary)
                    }
                },
                containerColor = BentoCardSurface,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Online Artwork Search and Selection Dialog
        if (showArtworkSearchDialog) {
            var tempSelectedUri by remember { mutableStateOf<String?>(artworkUri) }

            Dialog(
                onDismissRequest = { showArtworkSearchDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .fillMaxHeight(0.85f)
                        .clip(RoundedCornerShape(24.dp)),
                    color = BentoCardSurface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Dialog Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Search Online Covers",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                                Text(
                                    text = "Ultra-HD covers from iTunes & Deezer",
                                    fontSize = 11.sp,
                                    color = BentoTextSecondary
                                )
                            }

                            IconButton(
                                onClick = { showArtworkSearchDialog = false },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(BentoCardDarkPurple)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = BentoTextPrimary, modifier = Modifier.size(16.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Search Input Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = artworkSearchQuery,
                                onValueChange = { artworkSearchQuery = it },
                                placeholder = { Text("Search song or artist...", fontSize = 13.sp, color = BentoTextSecondary.copy(alpha = 0.6f)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = BentoTextPrimary,
                                    unfocusedTextColor = BentoTextPrimary,
                                    focusedBorderColor = BentoPrimary,
                                    unfocusedBorderColor = BentoCardBorder,
                                    focusedContainerColor = BentoCardDarkPurple,
                                    unfocusedContainerColor = BentoCardDarkPurple
                                )
                            )

                            Button(
                                onClick = {
                                    isSearchingArtwork = true
                                    coroutineScope.launch {
                                        artworkCandidates = com.example.util.OnlineArtistImageFetcher.searchArtworkCandidates(artworkSearchQuery)
                                        isSearchingArtwork = false
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary)
                            ) {
                                Text("Search", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Candidates List or Loading/Empty state
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            if (isSearchingArtwork) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(color = BentoPrimary, modifier = Modifier.size(36.dp))
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("Fetching high-res artwork options...", fontSize = 13.sp, color = BentoTextSecondary)
                                }
                            } else if (artworkCandidates.isEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.ImageSearch, contentDescription = null, tint = BentoTextSecondary.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("No covers found for this query", fontSize = 13.sp, color = BentoTextSecondary)
                                    Text("Try adjusting the title or artist in search", fontSize = 11.sp, color = BentoTextSecondary.copy(alpha = 0.7f))
                                }
                            } else {
                                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(artworkCandidates.size) { index ->
                                        val candidate = artworkCandidates[index]
                                        val isSelected = tempSelectedUri == candidate.imageUrl

                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { tempSelectedUri = candidate.imageUrl }
                                                .border(
                                                    width = if (isSelected) 2.5.dp else 1.dp,
                                                    color = if (isSelected) BentoPrimary else BentoCardBorder,
                                                    shape = RoundedCornerShape(12.dp)
                                                ),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isSelected) BentoCardDarkPurple else BentoCardSurface
                                            )
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp)) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(1f)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(BentoCardDarkPurple)
                                                ) {
                                                    AsyncImage(
                                                        model = candidate.imageUrl,
                                                        contentDescription = candidate.title,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                    if (isSelected) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(26.dp)
                                                                .padding(4.dp)
                                                                .align(Alignment.TopEnd)
                                                                .clip(CircleShape)
                                                                .background(BentoPrimary),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = BentoOnPrimary, modifier = Modifier.size(14.dp))
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = candidate.title,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BentoTextPrimary,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${candidate.artist} • ${candidate.source}",
                                                    fontSize = 10.sp,
                                                    color = BentoTextSecondary,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Bottom Actions: Cancel & OK / Select
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showArtworkSearchDialog = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Cancel")
                            }

                            Button(
                                onClick = {
                                    if (!tempSelectedUri.isNullOrBlank()) {
                                        artworkUri = tempSelectedUri
                                    }
                                    showArtworkSearchDialog = false
                                },
                                enabled = !tempSelectedUri.isNullOrBlank(),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Select & Use", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
