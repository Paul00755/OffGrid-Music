package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.SmartPlaylist
import com.example.data.model.Song
import com.example.ui.theme.*
import com.example.util.HapticUtils
import java.util.Locale

private data class SmartPlaylistPreset(
    val id: String,
    val label: String,
    val defaultName: String,
    val favoritesOnly: Boolean = false,
    val unplayedOnly: Boolean = false,
    val minPlayCount: Int = 0,
    val timeframeDays: Int = 0,
    val durationFilter: String = "ANY",
    val maxTrackLimit: Int = 0,
    val sortCriteria: String = "RECENTLY_ADDED"
)

private val SMART_PLAYLIST_PRESETS = listOf(
    SmartPlaylistPreset(
        id = "most_played",
        label = "Top Rotation",
        defaultName = "Top Rotation",
        minPlayCount = 3,
        maxTrackLimit = 50,
        sortCriteria = "MOST_PLAYED"
    ),
    SmartPlaylistPreset(
        id = "fresh_drops",
        label = "Fresh Additions",
        defaultName = "Fresh Additions",
        timeframeDays = 30,
        maxTrackLimit = 50,
        sortCriteria = "RECENTLY_ADDED"
    ),
    SmartPlaylistPreset(
        id = "favorites_mix",
        label = "Favorites Mix",
        defaultName = "Favorites Mix",
        favoritesOnly = true,
        sortCriteria = "RECENTLY_PLAYED"
    ),
    SmartPlaylistPreset(
        id = "hidden_gems",
        label = "Unplayed Gems",
        defaultName = "Unplayed Gems",
        unplayedOnly = true,
        maxTrackLimit = 50,
        sortCriteria = "RECENTLY_ADDED"
    ),
    SmartPlaylistPreset(
        id = "deep_cuts",
        label = "Long Cuts (>5m)",
        defaultName = "Long Cuts",
        durationFilter = "LONG",
        sortCriteria = "DURATION_DESC"
    )
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SmartPlaylistCriteriaDialog(
    allSongs: List<Song>,
    onDismiss: () -> Unit,
    onCreateSmartPlaylist: (SmartPlaylist) -> Unit,
    initialSmartPlaylist: SmartPlaylist? = null
) {
    val context = LocalContext.current
    val isEditMode = initialSmartPlaylist != null
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var titleText by remember(initialSmartPlaylist) { mutableStateOf(initialSmartPlaylist?.name ?: "") }
    var selectedGenres by remember(initialSmartPlaylist) {
        mutableStateOf(initialSmartPlaylist?.parsedGenres()?.toSet() ?: emptySet())
    }
    var selectedArtists by remember(initialSmartPlaylist) {
        mutableStateOf(initialSmartPlaylist?.parsedArtists()?.toSet() ?: emptySet())
    }
    var favoritesOnly by remember(initialSmartPlaylist) {
        mutableStateOf(initialSmartPlaylist?.favoritesOnly ?: false)
    }
    var unplayedOnly by remember(initialSmartPlaylist) {
        mutableStateOf(initialSmartPlaylist?.unplayedOnly ?: false)
    }
    var minPlayCount by remember(initialSmartPlaylist) {
        mutableIntStateOf(initialSmartPlaylist?.minPlayCount ?: 0)
    }
    var timeframeDays by remember(initialSmartPlaylist) {
        mutableIntStateOf(initialSmartPlaylist?.timeframeDays ?: 0)
    }
    var durationFilter by remember(initialSmartPlaylist) {
        mutableStateOf(initialSmartPlaylist?.durationFilter ?: "ANY")
    }
    var trackLimit by remember(initialSmartPlaylist) {
        mutableIntStateOf(initialSmartPlaylist?.maxTrackLimit ?: 0)
    }
    var sortCriteria by remember(initialSmartPlaylist) {
        mutableStateOf(initialSmartPlaylist?.sortCriteria ?: "RECENTLY_ADDED")
    }
    var activePresetId by remember { mutableStateOf<String?>(null) }
    var genreSearchQuery by remember { mutableStateOf("") }
    var artistSearchQuery by remember { mutableStateOf("") }

    // Extract normalized genres sorted by frequency then alphabetically
    val availableGenres = remember(allSongs) {
        val counts = LinkedHashMap<String, Int>()
        allSongs.forEach { song ->
            val raw = song.genre.trim()
            if (raw.isNotBlank() && !raw.equals("<unknown>", ignoreCase = true)) {
                val tokens = raw.split("/", ",", ";", "&")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                val effectiveTokens = if (tokens.isEmpty()) listOf(raw) else tokens
                effectiveTokens.forEach { token ->
                    val existingKey = counts.keys.firstOrNull { it.equals(token, ignoreCase = true) } ?: token
                    counts[existingKey] = (counts[existingKey] ?: 0) + 1
                }
            }
        }
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }
    }

    // Extract artists sorted by track count descending
    val availableArtists = remember(allSongs) {
        val counts = LinkedHashMap<String, Int>()
        allSongs.forEach { song ->
            val artist = song.displayArtist.trim()
            if (artist.isNotBlank() && !artist.equals("Unknown Artist", ignoreCase = true) && !artist.equals("<unknown>", ignoreCase = true)) {
                val existingKey = counts.keys.firstOrNull { it.equals(artist, ignoreCase = true) } ?: artist
                counts[existingKey] = (counts[existingKey] ?: 0) + 1
            }
        }
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }
    }

    val filteredGenres = remember(availableGenres, genreSearchQuery, selectedGenres) {
        val q = genreSearchQuery.trim()
        val base = if (q.isBlank()) availableGenres else availableGenres.filter { it.contains(q, ignoreCase = true) }
        val pinned = selectedGenres.filter { sel -> base.none { it.equals(sel, ignoreCase = true) } }
        (pinned + base).take(if (q.isBlank()) 24 else 40)
    }

    val filteredArtists = remember(availableArtists, artistSearchQuery, selectedArtists) {
        val q = artistSearchQuery.trim()
        val base = if (q.isBlank()) availableArtists else availableArtists.filter { it.contains(q, ignoreCase = true) }
        val pinned = selectedArtists.filter { sel -> base.none { it.equals(sel, ignoreCase = true) } }
        (pinned + base).take(if (q.isBlank()) 18 else 36)
    }

    val genreFilterString = remember(selectedGenres) {
        if (selectedGenres.isEmpty()) "ALL" else selectedGenres.joinToString(", ")
    }
    val artistFilterString = remember(selectedArtists) {
        if (selectedArtists.isEmpty()) "ALL" else selectedArtists.joinToString(", ")
    }

    val previewSmartPlaylist = remember(
        titleText,
        genreFilterString,
        artistFilterString,
        favoritesOnly,
        unplayedOnly,
        minPlayCount,
        timeframeDays,
        durationFilter,
        trackLimit,
        sortCriteria,
        initialSmartPlaylist
    ) {
        SmartPlaylist(
            id = initialSmartPlaylist?.id ?: 0L,
            name = titleText.trim().ifBlank { "Smart Playlist" },
            genreFilter = genreFilterString,
            minPlayCount = if (unplayedOnly) 0 else minPlayCount,
            timeframeDays = timeframeDays,
            maxTrackLimit = trackLimit,
            sortCriteria = sortCriteria,
            createdAt = initialSmartPlaylist?.createdAt ?: System.currentTimeMillis(),
            artistFilter = artistFilterString,
            favoritesOnly = favoritesOnly,
            durationFilter = durationFilter,
            unplayedOnly = unplayedOnly,
            customTrackOrder = initialSmartPlaylist?.customTrackOrder ?: emptyList(),
            displayOrder = initialSmartPlaylist?.displayOrder ?: Int.MAX_VALUE
        )
    }

    val matchingSongs = remember(allSongs, previewSmartPlaylist) {
        previewSmartPlaylist.filterSongs(allSongs)
    }

    val totalDurationFormatted = remember(matchingSongs) {
        formatSmartDuration(matchingSongs.sumOf { it.durationMs.coerceAtLeast(0L) })
    }

    val suggestedName = remember(selectedGenres, selectedArtists, favoritesOnly, unplayedOnly, minPlayCount, timeframeDays, durationFilter) {
        buildSmartSuggestedName(
            genres = selectedGenres,
            artists = selectedArtists,
            favoritesOnly = favoritesOnly,
            unplayedOnly = unplayedOnly,
            minPlayCount = minPlayCount,
            timeframeDays = timeframeDays,
            durationFilter = durationFilter
        )
    }

    val hasActiveFilters = selectedGenres.isNotEmpty() ||
        selectedArtists.isNotEmpty() ||
        favoritesOnly ||
        unplayedOnly ||
        minPlayCount > 0 ||
        timeframeDays > 0 ||
        durationFilter != "ANY" ||
        trackLimit > 0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BentoBackground,
        contentColor = BentoTextPrimary,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = BentoTextSecondary.copy(alpha = 0.4f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Sheet Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        BentoPrimary.copy(alpha = 0.28f),
                                        BentoCardDarkPurple
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = if (isEditMode) "Edit Smart Playlist" else "Smart Playlist Studio",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Dynamic rules · auto-updates & supports drag reorder",
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (hasActiveFilters) {
                    IconButton(
                        onClick = {
                            HapticUtils.performTickHaptic(context)
                            selectedGenres = emptySet()
                            selectedArtists = emptySet()
                            favoritesOnly = false
                            unplayedOnly = false
                            minPlayCount = 0
                            timeframeDays = 0
                            durationFilter = "ANY"
                            trackLimit = 0
                            sortCriteria = "RECENTLY_ADDED"
                            activePresetId = null
                        },
                        modifier = Modifier.testTag("smart_playlist_reset_filters_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset filters",
                            tint = BentoTextSecondary
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("smart_playlist_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = BentoTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Scrollable Builder Body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Quick Presets Row (Create Mode)
                if (!isEditMode) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Quick Templates",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSecondary
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(SMART_PLAYLIST_PRESETS, key = { it.id }) { preset ->
                                val isSelected = activePresetId == preset.id
                                Surface(
                                    onClick = {
                                        HapticUtils.performTickHaptic(context)
                                        activePresetId = preset.id
                                        if (titleText.isBlank() || SMART_PLAYLIST_PRESETS.any { it.defaultName == titleText }) {
                                            titleText = preset.defaultName
                                        }
                                        selectedGenres = emptySet()
                                        selectedArtists = emptySet()
                                        favoritesOnly = preset.favoritesOnly
                                        unplayedOnly = preset.unplayedOnly
                                        minPlayCount = preset.minPlayCount
                                        timeframeDays = preset.timeframeDays
                                        durationFilter = preset.durationFilter
                                        trackLimit = preset.maxTrackLimit
                                        sortCriteria = preset.sortCriteria
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isSelected) BentoPrimary.copy(alpha = 0.20f) else BentoCardSurface,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.45f)
                                    ),
                                    modifier = Modifier.testTag("smart_preset_${preset.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = if (isSelected) BentoPrimary else BentoTextSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = preset.label,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) BentoPrimary else BentoTextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Name Input
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("Playlist Name") },
                    placeholder = { Text(suggestedName, color = BentoTextSubtle) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        if (titleText.isNotEmpty()) {
                            IconButton(onClick = { titleText = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear name", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = BentoTextPrimary,
                        unfocusedTextColor = BentoTextPrimary,
                        focusedBorderColor = BentoPrimary,
                        unfocusedBorderColor = BentoBorder.copy(alpha = 0.6f),
                        focusedContainerColor = BentoCardSurface.copy(alpha = 0.6f),
                        unfocusedContainerColor = BentoCardSurface.copy(alpha = 0.4f),
                        focusedLabelColor = BentoPrimary,
                        unfocusedLabelColor = BentoTextSecondary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("smart_playlist_name_input")
                )

                // Favorites Only Toggle Card
                Surface(
                    onClick = {
                        HapticUtils.performTickHaptic(context)
                        favoritesOnly = !favoritesOnly
                        activePresetId = null
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (favoritesOnly) BentoPrimary.copy(alpha = 0.14f) else BentoCardSurface,
                    border = BorderStroke(
                        1.dp,
                        if (favoritesOnly) BentoPrimary.copy(alpha = 0.7f) else BentoBorder.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("smart_playlist_favorites_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (favoritesOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                tint = if (favoritesOnly) BentoPrimary else BentoTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Favorites Only",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                                Text(
                                    text = "Only include tracks you've marked as favorite",
                                    fontSize = 11.sp,
                                    color = BentoTextSecondary
                                )
                            }
                        }
                        Switch(
                            checked = favoritesOnly,
                            onCheckedChange = {
                                HapticUtils.performTickHaptic(context)
                                favoritesOnly = it
                                activePresetId = null
                            }
                        )
                    }
                }

                // Multi-Genre Filter Section
                if (availableGenres.isNotEmpty()) {
                    SmartRuleSectionCard(
                        title = "Genres",
                        trailingLabel = if (selectedGenres.isEmpty()) "All Genres" else "${selectedGenres.size} selected"
                    ) {
                        if (availableGenres.size > 10) {
                            OutlinedTextField(
                                value = genreSearchQuery,
                                onValueChange = { genreSearchQuery = it },
                                placeholder = { Text("Filter genres...", fontSize = 12.sp, color = BentoTextSubtle) },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = BentoTextSecondary)
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = BentoTextPrimary,
                                    unfocusedTextColor = BentoTextPrimary,
                                    focusedBorderColor = BentoPrimary,
                                    unfocusedBorderColor = BentoBorder.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SmartSelectableChip(
                                label = "All",
                                selected = selectedGenres.isEmpty(),
                                onClick = {
                                    HapticUtils.performTickHaptic(context)
                                    selectedGenres = emptySet()
                                    activePresetId = null
                                }
                            )
                            filteredGenres.forEach { genre ->
                                val isSelected = selectedGenres.any { it.equals(genre, ignoreCase = true) }
                                SmartSelectableChip(
                                    label = genre,
                                    selected = isSelected,
                                    onClick = {
                                        HapticUtils.performTickHaptic(context)
                                        selectedGenres = if (isSelected) {
                                            selectedGenres.filterNot { it.equals(genre, ignoreCase = true) }.toSet()
                                        } else {
                                            selectedGenres + genre
                                        }
                                        activePresetId = null
                                    }
                                )
                            }
                        }
                    }
                }

                // Multi-Artist Filter Section
                if (availableArtists.isNotEmpty()) {
                    SmartRuleSectionCard(
                        title = "Artists",
                        trailingLabel = if (selectedArtists.isEmpty()) "All Artists" else "${selectedArtists.size} selected"
                    ) {
                        if (availableArtists.size > 8) {
                            OutlinedTextField(
                                value = artistSearchQuery,
                                onValueChange = { artistSearchQuery = it },
                                placeholder = { Text("Filter artists...", fontSize = 12.sp, color = BentoTextSubtle) },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = BentoTextSecondary)
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = BentoTextPrimary,
                                    unfocusedTextColor = BentoTextPrimary,
                                    focusedBorderColor = BentoPrimary,
                                    unfocusedBorderColor = BentoBorder.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SmartSelectableChip(
                                label = "All",
                                selected = selectedArtists.isEmpty(),
                                onClick = {
                                    HapticUtils.performTickHaptic(context)
                                    selectedArtists = emptySet()
                                    activePresetId = null
                                }
                            )
                            filteredArtists.forEach { artist ->
                                val isSelected = selectedArtists.any { it.equals(artist, ignoreCase = true) }
                                SmartSelectableChip(
                                    label = artist,
                                    selected = isSelected,
                                    onClick = {
                                        HapticUtils.performTickHaptic(context)
                                        selectedArtists = if (isSelected) {
                                            selectedArtists.filterNot { it.equals(artist, ignoreCase = true) }.toSet()
                                        } else {
                                            selectedArtists + artist
                                        }
                                        activePresetId = null
                                    }
                                )
                            }
                        }
                    }
                }

                // Play Activity (Unplayed / Min Plays)
                SmartRuleSectionCard(
                    title = "Play Activity",
                    trailingLabel = when {
                        unplayedOnly -> "Unplayed (0)"
                        minPlayCount == 0 -> "Any"
                        else -> "≥ $minPlayCount plays"
                    }
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmartSelectableChip(
                            label = "Any",
                            selected = !unplayedOnly && minPlayCount == 0,
                            onClick = {
                                HapticUtils.performTickHaptic(context)
                                unplayedOnly = false
                                minPlayCount = 0
                                activePresetId = null
                            }
                        )
                        SmartSelectableChip(
                            label = "Unplayed",
                            selected = unplayedOnly,
                            onClick = {
                                HapticUtils.performTickHaptic(context)
                                unplayedOnly = true
                                minPlayCount = 0
                                activePresetId = null
                            }
                        )
                        listOf(1, 3, 5, 10, 20).forEach { count ->
                            SmartSelectableChip(
                                label = "≥ $count plays",
                                selected = !unplayedOnly && minPlayCount == count,
                                onClick = {
                                    HapticUtils.performTickHaptic(context)
                                    unplayedOnly = false
                                    minPlayCount = count
                                    activePresetId = null
                                }
                            )
                        }
                    }
                }

                // Timeframe & Track Length
                SmartRuleSectionCard(
                    title = "Timeframe & Length",
                    trailingLabel = buildString {
                        append(if (timeframeDays == 0) "Anytime" else "Last ${timeframeDays}d")
                        if (durationFilter != "ANY") {
                            append(" · ")
                            append(
                                when (durationFilter) {
                                    "SHORT" -> "<3m"
                                    "MEDIUM" -> "3–5m"
                                    "LONG" -> ">5m"
                                    else -> ""
                                }
                            )
                        }
                    }
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0 to "Anytime", 7 to "7 Days", 30 to "30 Days", 90 to "90 Days").forEach { (days, label) ->
                            SmartSelectableChip(
                                label = label,
                                selected = timeframeDays == days,
                                onClick = {
                                    HapticUtils.performTickHaptic(context)
                                    timeframeDays = days
                                    activePresetId = null
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "ANY" to "Any Length",
                            "SHORT" to "< 3 min",
                            "MEDIUM" to "3–5 min",
                            "LONG" to "> 5 min"
                        ).forEach { (code, label) ->
                            SmartSelectableChip(
                                label = label,
                                selected = durationFilter == code,
                                onClick = {
                                    HapticUtils.performTickHaptic(context)
                                    durationFilter = code
                                    activePresetId = null
                                }
                            )
                        }
                    }
                }

                // Track Limit & Default Sort
                SmartRuleSectionCard(
                    title = "Limit & Default Sort",
                    trailingLabel = if (trackLimit == 0) "Unlimited" else "Max $trackLimit"
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0 to "Unlimited", 10 to "10", 25 to "25", 50 to "50", 100 to "100").forEach { (limit, label) ->
                            SmartSelectableChip(
                                label = label,
                                selected = trackLimit == limit,
                                onClick = {
                                    HapticUtils.performTickHaptic(context)
                                    trackLimit = limit
                                    activePresetId = null
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "RECENTLY_ADDED" to "Recently Added",
                            "MOST_PLAYED" to "Most Played",
                            "RECENTLY_PLAYED" to "Recently Played",
                            "TITLE" to "Title A–Z",
                            "ARTIST" to "Artist A–Z",
                            "DURATION_ASC" to "Shortest",
                            "DURATION_DESC" to "Longest"
                        ).forEach { (key, label) ->
                            SmartSelectableChip(
                                label = label,
                                selected = sortCriteria == key,
                                onClick = {
                                    HapticUtils.performTickHaptic(context)
                                    sortCriteria = key
                                    activePresetId = null
                                }
                            )
                        }
                    }
                }

                // Live Track Preview Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = BentoCardDarkPurple.copy(alpha = 0.72f),
                    border = BorderStroke(
                        1.dp,
                        if (matchingSongs.isNotEmpty()) BentoPrimary.copy(alpha = 0.42f) else BentoBorder.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .background(
                                            if (matchingSongs.isNotEmpty()) BentoPrimary else BentoError,
                                            CircleShape
                                        )
                                )
                                Text(
                                    text = "Live Match Preview",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                            }
                            Text(
                                text = if (matchingSongs.isEmpty()) {
                                    "0 tracks"
                                } else {
                                    "${matchingSongs.size} tracks · $totalDurationFormatted"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (matchingSongs.isNotEmpty()) BentoPrimary else BentoError
                            )
                        }

                        if (matchingSongs.isEmpty()) {
                            Text(
                                text = "No tracks match all selected rules yet. You can relax a filter or save the rule to auto-populate later.",
                                color = BentoTextSecondary,
                                fontSize = 12.sp
                            )
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(matchingSongs.take(15), key = { it.id }) { song ->
                                    Surface(
                                        modifier = Modifier.width(126.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        color = BentoCardSurface.copy(alpha = 0.85f),
                                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.35f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            AlbumArtView(
                                                song = song,
                                                modifier = Modifier.size(34.dp),
                                                cornerRadius = 8
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = song.displayTitle,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BentoTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = song.displayArtist,
                                                    fontSize = 9.5.sp,
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
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            // Sticky Action Footer
            HorizontalDivider(color = BentoBorder.copy(alpha = 0.35f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("smart_playlist_cancel_button")
                ) {
                    Text("Cancel", color = BentoTextSecondary, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        val finalName = titleText.trim().ifBlank { suggestedName }
                        val saved = previewSmartPlaylist.copy(name = finalName)
                        onCreateSmartPlaylist(saved)
                        onDismiss()
                    },
                    enabled = matchingSongs.isNotEmpty() || titleText.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BentoPrimary,
                        contentColor = BentoOnPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("smart_playlist_save_button")
                ) {
                    Icon(
                        imageVector = if (isEditMode) Icons.Default.Check else Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isEditMode) {
                            "Save Rules (${matchingSongs.size})"
                        } else {
                            "Create Mix (${matchingSongs.size})"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartRuleSectionCard(
    title: String,
    trailingLabel: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = BentoCardSurface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.42f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
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
                        imageVector = Icons.Default.FilterList,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = title,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                }
                Text(
                    text = trailingLabel,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoPrimary
                )
            }
            content()
        }
    }
}

@Composable
private fun SmartSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
            }
        } else null,
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = BentoPrimary,
            selectedLabelColor = BentoOnPrimary,
            selectedLeadingIconColor = BentoOnPrimary,
            containerColor = BentoBackground.copy(alpha = 0.55f),
            labelColor = BentoTextPrimary
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = BentoBorder.copy(alpha = 0.45f),
            selectedBorderColor = BentoPrimary
        )
    )
}

private fun buildSmartSuggestedName(
    genres: Set<String>,
    artists: Set<String>,
    favoritesOnly: Boolean,
    unplayedOnly: Boolean,
    minPlayCount: Int,
    timeframeDays: Int,
    durationFilter: String
): String {
    val parts = mutableListOf<String>()
    if (favoritesOnly) parts.add("Favorite")
    if (genres.size == 1) parts.add(genres.first())
    else if (genres.size > 1) parts.add("${genres.first()} +${genres.size - 1}")
    if (artists.size == 1) parts.add(artists.first())
    when {
        unplayedOnly -> parts.add("Unplayed")
        minPlayCount >= 10 -> parts.add("Heavy Rotation")
        minPlayCount > 0 -> parts.add("Top Played")
    }
    if (timeframeDays > 0 && parts.size < 2) parts.add("Last ${timeframeDays}d")
    when (durationFilter) {
        "SHORT" -> if (parts.size < 2) parts.add("Quick Tracks")
        "LONG" -> if (parts.size < 2) parts.add("Deep Cuts")
    }
    return if (parts.isEmpty()) "Smart Mix" else parts.joinToString(" ")
}

private fun formatSmartDuration(totalMs: Long): String {
    val totalMinutes = (totalMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val mins = totalMinutes % 60L
    return when {
        hours > 0 -> String.format(Locale.US, "%dh %dm", hours, mins)
        totalMinutes > 0 -> "${totalMinutes}m"
        else -> "<1m"
    }
}
