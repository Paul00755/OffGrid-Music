package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Song
import com.example.data.model.SmartPlaylist
import com.example.ui.theme.BentoBackground
import com.example.ui.theme.BentoCardDarkPurple
import com.example.ui.theme.BentoPrimary
import com.example.ui.theme.BentoTextPrimary
import com.example.ui.theme.BentoTextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartPlaylistCriteriaDialog(
    allSongs: List<Song>,
    onDismiss: () -> Unit,
    onCreateSmartPlaylist: (SmartPlaylist) -> Unit
) {
    var titleText by remember { mutableStateOf("") }
    var selectedGenre by remember { mutableStateOf("ALL") }
    var minPlayCount by remember { mutableIntStateOf(0) }
    var timeframeDays by remember { mutableIntStateOf(0) } // 0 = Anytime, 7, 30, 90
    var trackLimit by remember { mutableIntStateOf(0) } // 0 = Unlimited, 10, 25, 50, 100
    var sortCriteria by remember { mutableStateOf("RECENTLY_ADDED") } // RECENTLY_ADDED, MOST_PLAYED, TITLE, ARTIST

    // Extract available genres from songs
    val availableGenres = remember(allSongs) {
        val extracted = allSongs.map { it.genre.trim() }
            .filter { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
            .distinct()
            .sorted()
        listOf("ALL") + extracted
    }

    // Dynamic Live Matching Preview calculation
    val matchingSongs = remember(allSongs, selectedGenre, minPlayCount, timeframeDays, trackLimit, sortCriteria) {
        val dummy = SmartPlaylist(
            name = "Temp",
            genreFilter = selectedGenre,
            minPlayCount = minPlayCount,
            timeframeDays = timeframeDays,
            maxTrackLimit = trackLimit,
            sortCriteria = sortCriteria
        )
        dummy.filterSongs(allSongs)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B162B),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = BentoPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Smart Playlist Builder",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Name Input Field
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("Playlist Name", color = Color.White.copy(alpha = 0.7f)) },
                    placeholder = { Text("e.g. Heavy Rock Bangerz", color = Color.White.copy(alpha = 0.4f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = BentoPrimary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // 1. Genre Filter
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Genre Filter", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableGenres.take(12).forEach { genre ->
                            val selected = selectedGenre.equals(genre, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = { selectedGenre = genre },
                                label = { Text(genre, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BentoPrimary,
                                    selectedLabelColor = Color.Black,
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = Color.White
                                )
                            )
                        }
                    }
                }

                // 2. Minimum Play Count
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Min Play Count", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (minPlayCount == 0) "Any" else "≥ $minPlayCount plays",
                            color = BentoPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 1, 3, 5, 10, 20).forEach { count ->
                            val selected = minPlayCount == count
                            FilterChip(
                                selected = selected,
                                onClick = { minPlayCount = count },
                                label = { Text(if (count == 0) "Any" else "≥ $count", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BentoPrimary,
                                    selectedLabelColor = Color.Black,
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = Color.White
                                )
                            )
                        }
                    }
                }

                // 3. Timeframe Added / Played
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Timeframe", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            0 to "Anytime",
                            7 to "Last 7 Days",
                            30 to "Last 30 Days",
                            90 to "Last 90 Days"
                        ).forEach { (days, label) ->
                            val selected = timeframeDays == days
                            FilterChip(
                                selected = selected,
                                onClick = { timeframeDays = days },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BentoPrimary,
                                    selectedLabelColor = Color.Black,
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = Color.White
                                )
                            )
                        }
                    }
                }

                // 4. Track Limit
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Track Count Limit", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            0 to "Unlimited",
                            10 to "10 tracks",
                            25 to "25 tracks",
                            50 to "50 tracks",
                            100 to "100 tracks"
                        ).forEach { (limit, label) ->
                            val selected = trackLimit == limit
                            FilterChip(
                                selected = selected,
                                onClick = { trackLimit = limit },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BentoPrimary,
                                    selectedLabelColor = Color.Black,
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = Color.White
                                )
                            )
                        }
                    }
                }

                // 5. Sorting Criteria
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sort By", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "RECENTLY_ADDED" to "Recently Added",
                            "MOST_PLAYED" to "Most Played",
                            "TITLE" to "Track Title",
                            "ARTIST" to "Artist Name"
                        ).forEach { (key, label) ->
                            val selected = sortCriteria == key
                            FilterChip(
                                selected = selected,
                                onClick = { sortCriteria = key },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BentoPrimary,
                                    selectedLabelColor = Color.Black,
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = Color.White
                                )
                            )
                        }
                    }
                }

                // 6. Dynamic Live Track Preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                    shape = RoundedCornerShape(12.dp)
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (matchingSongs.isNotEmpty()) BentoPrimary else Color.Red, CircleShape)
                                )
                                Text(
                                    text = "Live Track Preview",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Text(
                                text = "${matchingSongs.size} tracks matched",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoPrimary
                            )
                        }

                        if (matchingSongs.isEmpty()) {
                            Text(
                                text = "No songs fit these criteria. Try relaxing your filters.",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp
                            )
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(matchingSongs.take(15)) { song ->
                                    Card(
                                        modifier = Modifier.width(110.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.1f)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = song.displayTitle,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = song.displayArtist,
                                                fontSize = 10.sp,
                                                color = Color.White.copy(alpha = 0.7f),
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
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = titleText.trim().ifBlank {
                        val g = if (selectedGenre != "ALL") "$selectedGenre " else ""
                        "${g}Smart Playlist".trim()
                    }
                    val created = SmartPlaylist(
                        name = finalName,
                        genreFilter = selectedGenre,
                        minPlayCount = minPlayCount,
                        timeframeDays = timeframeDays,
                        maxTrackLimit = trackLimit,
                        sortCriteria = sortCriteria
                    )
                    onCreateSmartPlaylist(created)
                    onDismiss()
                },
                enabled = matchingSongs.isNotEmpty() || titleText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = Color.Black)
            ) {
                Text("Create Smart Playlist", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White.copy(alpha = 0.7f))
            }
        }
    )
}
