package com.example.ui.screens.layouts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Playlist
import com.example.data.model.SmartPlaylist
import com.example.ui.components.FastScrollbar
import com.example.ui.components.PlaylistCover
import com.example.ui.theme.*
import com.example.util.HapticUtils

@Composable
fun PlaylistsScreenMagazineHero(
    playlists: List<Playlist>,
    mostPlayedPlaylists: List<Playlist> = emptyList(),
    smartPlaylists: List<SmartPlaylist>,
    onCreatePlaylist: () -> Unit,
    onCreateSmartPlaylist: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onSmartPlaylistClick: (SmartPlaylist) -> Unit,
    onPlayNow: (Playlist) -> Unit,
    onPlayNext: (Playlist) -> Unit,
    onAddToQueue: (Playlist) -> Unit,
    onRenamePlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onDeleteSmartPlaylist: (SmartPlaylist) -> Unit,
    onUpdatePlaylistArtwork: (Playlist) -> Unit,
    onImportM3u: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Spotlight the Most Played Playlist on top (falling back to largest playlist or first playlist)
    val featuredPlaylist = remember(mostPlayedPlaylists, playlists) {
        val topPlayedId = mostPlayedPlaylists.firstOrNull()?.id
        playlists.firstOrNull { it.id == topPlayedId }
            ?: mostPlayedPlaylists.firstOrNull()
            ?: playlists.maxByOrNull { it.songCount }
            ?: playlists.firstOrNull()
    }
    val isMostPlayedSpotlight = remember(mostPlayedPlaylists, featuredPlaylist) {
        featuredPlaylist != null && mostPlayedPlaylists.any { it.id == featuredPlaylist.id }
    }
    val otherPlaylists = remember(playlists, featuredPlaylist) {
        if (featuredPlaylist == null) emptyList()
        else playlists.filter { it.id != featuredPlaylist.id }
    }

    fun formatDuration(ms: Long): String {
        val mins = (ms / (1000 * 60)).coerceAtLeast(0L)
        return if (mins >= 60) "${String.format("%.1f", mins / 60.0)} hrs" else "$mins mins"
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. Editorial Masthead Header
            item(key = "magazine_hero_header", contentType = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (onBackClick != null) {
                                Surface(
                                    onClick = onBackClick,
                                    shape = CircleShape,
                                    color = BentoCardSurface.copy(alpha = 0.7f),
                                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
                                            tint = BentoTextPrimary,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                            }

                            Column {
                                Text(
                                    text = "EDITORIAL ISSUE • CURATED ANTHOLOGY",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BentoPrimary,
                                    letterSpacing = 1.6.sp
                                )
                                Text(
                                    text = "The Record",
                                    fontSize = 26.sp,
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Black,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (onOpenSearch != null) {
                                Surface(
                                    onClick = onOpenSearch,
                                    shape = CircleShape,
                                    color = BentoCardSurface.copy(alpha = 0.75f),
                                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Search, contentDescription = "Search", tint = BentoTextPrimary, modifier = Modifier.size(17.dp))
                                    }
                                }
                            }
                            if (onImportM3u != null) {
                                Surface(
                                    onClick = onImportM3u,
                                    shape = CircleShape,
                                    color = BentoCardSurface.copy(alpha = 0.75f),
                                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.FileUpload, contentDescription = "Import M3U Playlist", tint = BentoTextPrimary, modifier = Modifier.size(17.dp))
                                    }
                                }
                            }
                            Surface(
                                onClick = onCreateSmartPlaylist,
                                shape = CircleShape,
                                color = BentoCardSurface.copy(alpha = 0.75f),
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Smart Playlist", tint = BentoPrimary, modifier = Modifier.size(17.dp))
                                }
                            }
                            Surface(
                                onClick = onCreatePlaylist,
                                shape = CircleShape,
                                color = BentoPrimary,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Add, contentDescription = "New Playlist", tint = BentoOnPrimary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = BentoBorder.copy(alpha = 0.45f)
                    )
                }
            }

            // 2. Full-Bleed Magazine Cover Story (Most Played Playlist Spotlight on Top)
            if (featuredPlaylist != null) {
                item(key = "magazine_hero_featured_${featuredPlaylist.id}", contentType = "hero") {
                    var heroMenuExpanded by remember { mutableStateOf(false) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(330.dp)
                            .clip(RoundedCornerShape(34.dp))
                            .border(
                                1.dp,
                                Brush.linearGradient(
                                    listOf(
                                        BentoPrimary.copy(alpha = 0.55f),
                                        Color.White.copy(alpha = 0.14f)
                                    )
                                ),
                                RoundedCornerShape(34.dp)
                            )
                            .clickable { onPlaylistClick(featuredPlaylist) }
                    ) {
                        // Blurred atmospheric cover backdrop
                        PlaylistCover(
                            playlist = featuredPlaylist,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(26.dp),
                            cornerRadius = 34
                        )

                        // Deep magazine gradient overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0.0f to Color.Black.copy(alpha = 0.42f),
                                            0.45f to BentoCardDarkPurple.copy(alpha = 0.72f),
                                            1.0f to Color.Black.copy(alpha = 0.94f)
                                        )
                                    )
                                )
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(22.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Top Row: Spotlight Badge + Options Menu
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = BentoPrimary.copy(alpha = 0.24f),
                                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.6f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocalFireDepartment,
                                            contentDescription = null,
                                            tint = BentoPrimary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = if (isMostPlayedSpotlight) "MOST PLAYED • COVER STORY" else "COVER STORY • SPOTLIGHT",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White,
                                            letterSpacing = 1.1.sp
                                        )
                                    }
                                }

                                Box {
                                    Surface(
                                        onClick = { heroMenuExpanded = true },
                                        shape = CircleShape,
                                        color = Color.White.copy(alpha = 0.14f),
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.MoreHoriz,
                                                contentDescription = "Playlist Options",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = heroMenuExpanded,
                                        onDismissRequest = { heroMenuExpanded = false },
                                        modifier = Modifier.background(BentoCardSurface)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Play Next", color = BentoTextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.SkipNext, null, tint = BentoPrimary) },
                                            onClick = { heroMenuExpanded = false; onPlayNext(featuredPlaylist) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Add to Queue", color = BentoTextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.QueueMusic, null, tint = BentoPrimary) },
                                            onClick = { heroMenuExpanded = false; onAddToQueue(featuredPlaylist) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Change Cover Art", color = BentoTextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Image, null, tint = BentoPrimary) },
                                            onClick = { heroMenuExpanded = false; onUpdatePlaylistArtwork(featuredPlaylist) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Rename", color = BentoTextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Edit, null, tint = BentoPrimary) },
                                            onClick = { heroMenuExpanded = false; onRenamePlaylist(featuredPlaylist) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete", color = BentoError) },
                                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = BentoError) },
                                            onClick = { heroMenuExpanded = false; onDeletePlaylist(featuredPlaylist) }
                                        )
                                    }
                                }
                            }

                            // Center Hero Feature: Circular Vinyl/Cover Medallion + Editorial Headline
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    // Outer glowing halo ring
                                    Box(
                                        modifier = Modifier
                                            .size(116.dp)
                                            .clip(RoundedCornerShape(30.dp))
                                            .background(
                                                Brush.sweepGradient(
                                                    listOf(
                                                        BentoPrimary.copy(alpha = 0.65f),
                                                        Color.White.copy(alpha = 0.15f),
                                                        BentoPrimary.copy(alpha = 0.65f)
                                                    )
                                                )
                                            )
                                    )
                                    PlaylistCover(
                                        playlist = featuredPlaylist,
                                        modifier = Modifier
                                            .size(110.dp)
                                            .clip(RoundedCornerShape(28.dp)),
                                        cornerRadius = 28
                                    )
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "VOL. 01 • TOP ROTATION",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoPrimary,
                                        letterSpacing = 1.4.sp
                                    )
                                    Text(
                                        text = featuredPlaylist.name,
                                        fontSize = 24.sp,
                                        fontFamily = FontFamily.Serif,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        lineHeight = 28.sp
                                    )
                                    Text(
                                        text = "${featuredPlaylist.songCount} Tracks  •  ${formatDuration(featuredPlaylist.totalDurationMs)}",
                                        fontSize = 12.sp,
                                        fontStyle = FontStyle.Italic,
                                        color = Color.White.copy(alpha = 0.78f)
                                    )
                                }
                            }

                            // Bottom Capsule Actions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onPlayNow(featuredPlaylist)
                                    },
                                    shape = RoundedCornerShape(50),
                                    color = BentoPrimary,
                                    shadowElevation = 8.dp,
                                    modifier = Modifier
                                        .weight(1.45f)
                                        .height(48.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BentoOnPrimary, modifier = Modifier.size(21.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Play Cover Story", fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = BentoOnPrimary)
                                    }
                                }

                                Surface(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onAddToQueue(featuredPlaylist)
                                    },
                                    shape = RoundedCornerShape(50),
                                    color = Color.White.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.QueueMusic, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Queue Issue", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Empty state when no playlists exist yet
                item(key = "magazine_empty_state") {
                    Surface(
                        shape = RoundedCornerShape(30.dp),
                        color = BentoCardSurface.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCreatePlaylist() }
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.QueueMusic, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(44.dp))
                            Text("Publish Your First Editorial Issue", fontSize = 17.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                            Text("Tap + to curate a playlist or import an .M3U collection.", fontSize = 12.5.sp, color = BentoTextSecondary)
                        }
                    }
                }
            }

            // 3. Editorial Smart Mixes Carousel (Floating Pill Cards)
            if (smartPlaylists.isNotEmpty()) {
                item(key = "magazine_smart_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(17.dp))
                            Text(
                                text = "SYNDICATED SMART EDITIONS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoTextPrimary,
                                letterSpacing = 1.2.sp
                            )
                        }
                        Text(
                            text = "+ New Rule",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoPrimary,
                            modifier = Modifier.clickable { onCreateSmartPlaylist() }
                        )
                    }
                }
                item(key = "magazine_smart_row") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(smartPlaylists, key = { idx, sm -> "${sm.id}_$idx" }) { idx, smart ->
                            Surface(
                                shape = RoundedCornerShape(26.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .width(220.dp)
                                    .clip(RoundedCornerShape(26.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                BentoPrimary.copy(alpha = 0.18f),
                                                BentoCardDarkPurple.copy(alpha = 0.88f)
                                            )
                                        )
                                    )
                                    .clickable { onSmartPlaylistClick(smart) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "ISSUE 0${idx + 1}",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = BentoPrimary,
                                            letterSpacing = 1.2.sp
                                        )
                                        IconButton(
                                            onClick = { onDeleteSmartPlaylist(smart) },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Delete", tint = BentoTextSecondary, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    Text(
                                        text = smart.name,
                                        fontSize = 15.5.sp,
                                        fontFamily = FontFamily.Serif,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = smart.getCriteriaBadges().take(2).joinToString(" • ").ifEmpty { "Auto-updating mix" },
                                        fontSize = 11.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Unboxed Editorial Archive Stream (Frameless Magazine Index with Hairline Rules)
            if (otherPlaylists.isNotEmpty()) {
                item(key = "magazine_all_collections_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "BACK ISSUES • ARCHIVE INDEX",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoTextSecondary,
                                letterSpacing = 1.4.sp
                            )
                            Text(
                                text = "${otherPlaylists.size} Volumes",
                                fontSize = 11.5.sp,
                                fontStyle = FontStyle.Italic,
                                color = BentoTextSecondary
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = BentoBorder.copy(alpha = 0.45f))
                    }
                }
                itemsIndexed(otherPlaylists, key = { _, it -> it.id }, contentType = { _, _ -> "magazine_playlist_card" }) { index, playlist ->
                    var menuExpanded by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaylistClick(playlist) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "%02d".format(index + 2),
                                fontSize = 18.sp,
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Light,
                                color = BentoPrimary.copy(alpha = 0.85f),
                                modifier = Modifier.width(28.dp)
                            )

                            PlaylistCover(
                                playlist = playlist,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(20.dp)),
                                cornerRadius = 20
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "VOLUME %02d".format(index + 2),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BentoPrimary,
                                    letterSpacing = 1.2.sp
                                )
                                Text(
                                    text = playlist.name,
                                    fontSize = 16.5.sp,
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${playlist.songCount} tracks  •  ${formatDuration(playlist.totalDurationMs)}",
                                    fontSize = 12.sp,
                                    color = BentoTextSecondary
                                )
                            }

                            Surface(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onPlayNow(playlist)
                                },
                                shape = CircleShape,
                                color = BentoPrimary.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = BentoPrimary, modifier = Modifier.size(20.dp))
                                }
                            }

                            Box {
                                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = BentoTextSecondary, modifier = Modifier.size(18.dp))
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                    modifier = Modifier.background(BentoCardSurface)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Play Next", color = BentoTextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.SkipNext, null, tint = BentoPrimary) },
                                        onClick = { menuExpanded = false; onPlayNext(playlist) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add to Queue", color = BentoTextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.QueueMusic, null, tint = BentoPrimary) },
                                        onClick = { menuExpanded = false; onAddToQueue(playlist) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Change Cover Art", color = BentoTextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Image, null, tint = BentoPrimary) },
                                        onClick = { menuExpanded = false; onUpdatePlaylistArtwork(playlist) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename", color = BentoTextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Edit, null, tint = BentoPrimary) },
                                        onClick = { menuExpanded = false; onRenamePlaylist(playlist) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = BentoError) },
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = BentoError) },
                                        onClick = { menuExpanded = false; onDeletePlaylist(playlist) }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = BentoBorder.copy(alpha = 0.28f),
                            modifier = Modifier.padding(start = 42.dp)
                        )
                    }
                }
            }
        }

        FastScrollbar(
            listState = listState,
            totalItems = otherPlaylists.size + smartPlaylists.size + 4,
            itemIndexToLabel = { idx ->
                val plIndex = (idx - 3).coerceIn(0, (otherPlaylists.size - 1).coerceAtLeast(0))
                otherPlaylists.getOrNull(plIndex)?.name?.firstOrNull()?.uppercaseChar()?.toString()
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(bottom = 100.dp, end = 2.dp)
        )
    }
}
