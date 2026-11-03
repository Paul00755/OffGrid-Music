package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Song
import com.example.ui.theme.*
import com.example.util.HapticUtils
import com.example.util.SonicStudioEngine
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

private val BOOKMARK_PRESETS = listOf(
    "Intro",
    "Beat Drop",
    "Chorus",
    "Guitar Solo",
    "Bridge",
    "Outro"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonicStudioBottomSheet(
    song: Song,
    durationMs: Long,
    fallbackPositionMs: Long,
    playbackPositionFlow: StateFlow<Long>?,
    candidateSongs: List<Song>,
    onSeekTo: (Long) -> Unit,
    onLaunchSonicDnaRadio: (List<Song>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val posState = playbackPositionFlow?.collectAsStateWithLifecycle(initialValue = fallbackPositionMs)
    val currentSecond by remember(posState, fallbackPositionMs, durationMs) {
        derivedStateOf {
            val raw = posState?.value ?: fallbackPositionMs
            val safe = if (durationMs > 0L) raw.coerceIn(0L, durationMs) else raw.coerceAtLeast(0L)
            safe / 1000L
        }
    }
    val formattedCurrentTime = remember(currentSecond) {
        String.format(Locale.US, "%d:%02d", currentSecond / 60L, currentSecond % 60L)
    }

    val abLoopState by SonicStudioEngine.abLoopState.collectAsStateWithLifecycle()
    val bookmarkVersion = SonicStudioEngine.bookmarksUpdateSignal.longValue
    val songBookmarks = remember(song.id, bookmarkVersion) {
        SonicStudioEngine.getBookmarks(context, song.id)
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = A-B Loop & Bookmarks, 1 = Sonic DNA Radio
    var customBookmarkLabel by remember { mutableStateOf("") }

    val dnaMatches = remember(song.id, candidateSongs.size) {
        SonicStudioEngine.buildSonicDnaRadioQueue(
            seedSong = song,
            candidateSongs = candidateSongs,
            maxTracks = 25
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BentoCardSurface,
        scrimColor = Color.Black.copy(alpha = 0.68f),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF00E5FF).copy(alpha = 0.16f))
                            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Sonic Studio",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Sonic Studio & DNA",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "${song.displayTitle} • $formattedCurrentTime",
                            fontSize = 12.sp,
                            color = BentoPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                        contentDescription = "Close Sonic Studio",
                        tint = BentoTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Mode Selector Pill Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BentoCardDarkPurple)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabs = listOf(
                    "A–B Loop & Pins" to Icons.Default.Repeat,
                    "Sonic DNA Radio" to Icons.Default.Radar
                )
                tabs.forEachIndexed { idx, (title, icon) ->
                    val isSelected = selectedTab == idx
                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            selectedTab = idx
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) BentoPrimary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .testTag(if (idx == 0) "sonic_tab_loop_pins" else "sonic_tab_dna_radio")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) BentoOnPrimary else BentoTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = title,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) BentoOnPrimary else BentoTextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedTab == 0) {
                // TAB 0: Precision A-B Looper & Timestamped Audio Bookmarks
                val effectiveDurationMs = remember(durationMs, song.durationMs) {
                    if (durationMs > 0L) durationMs else song.durationMs.coerceAtLeast(0L)
                }
                val formattedMaxDuration = remember(effectiveDurationMs) {
                    if (effectiveDurationMs > 0L) SonicStudioEngine.formatDurationMs(effectiveDurationMs) else "--:--"
                }
                val isLoopForThisSong = abLoopState.songId == song.id
                val pointAText = if (isLoopForThisSong) abLoopState.formatPoint(abLoopState.startMs) else "--:--"
                val pointBText = if (isLoopForThisSong) abLoopState.formatPoint(abLoopState.endMs) else "--:--"
                val currentLoopCount = abLoopState.maxLoopCount.coerceIn(
                    com.example.util.AbLoopState.MIN_LOOP_COUNT,
                    com.example.util.AbLoopState.MAX_LOOP_COUNT
                )

                var manualPointAInput by remember(song.id, abLoopState.startMs, isLoopForThisSong) {
                    mutableStateOf(
                        if (isLoopForThisSong && abLoopState.startMs != null) {
                            abLoopState.formatPoint(abLoopState.startMs)
                        } else ""
                    )
                }
                var manualPointBInput by remember(song.id, abLoopState.endMs, isLoopForThisSong) {
                    mutableStateOf(
                        if (isLoopForThisSong && abLoopState.endMs != null) {
                            abLoopState.formatPoint(abLoopState.endMs)
                        } else ""
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BentoCardDarkPurple,
                    border = BorderStroke(
                        1.dp,
                        if (isLoopForThisSong && abLoopState.isActive) Color(0xFF00E5FF).copy(alpha = 0.6f)
                        else Color.White.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Loop,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = "Precision A–B Section Looper",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                    val statusText = when {
                                        isLoopForThisSong && abLoopState.isActive ->
                                            "Looping ${abLoopState.completedLoops}/${currentLoopCount} • Max $formattedMaxDuration"
                                        isLoopForThisSong && abLoopState.isLoopCountExhausted ->
                                            "Finished ${currentLoopCount}x loops • Playing normally"
                                        else ->
                                            "Track length: $formattedMaxDuration • Default 3x (Max 15x)"
                                    }
                                    Text(
                                        text = statusText,
                                        fontSize = 10.5.sp,
                                        color = if (isLoopForThisSong && abLoopState.isActive) Color(0xFF00E5FF) else BentoTextSecondary
                                    )
                                }
                            }
                            if (isLoopForThisSong && (abLoopState.startMs != null || abLoopState.endMs != null)) {
                                TextButton(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        SonicStudioEngine.clearAbLoop()
                                        manualPointAInput = ""
                                        manualPointBInput = ""
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Clear", fontSize = 11.5.sp, color = BentoAccentPinkText, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Live Capture Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    val nowPos = posState?.value ?: fallbackPositionMs
                                    SonicStudioEngine.setLoopPointA(song.id, nowPos, effectiveDurationMs)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isLoopForThisSong && abLoopState.startMs != null)
                                        Color(0xFF00E5FF).copy(alpha = 0.22f)
                                    else Color.White.copy(alpha = 0.08f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("set_loop_point_a_button")
                            ) {
                                Text(
                                    text = "Set A ($pointAText)",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isLoopForThisSong && abLoopState.startMs != null) Color(0xFF00E5FF) else Color.White
                                )
                            }

                            Button(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    val nowPos = posState?.value ?: fallbackPositionMs
                                    val ok = SonicStudioEngine.setLoopPointB(song.id, nowPos, effectiveDurationMs)
                                    if (!ok) {
                                        Toast.makeText(context, "Point B must be after Point A", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isLoopForThisSong && abLoopState.endMs != null)
                                        Color(0xFF00E5FF).copy(alpha = 0.22f)
                                    else Color.White.copy(alpha = 0.08f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("set_loop_point_b_button")
                            ) {
                                Text(
                                    text = "Set B ($pointBText)",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isLoopForThisSong && abLoopState.endMs != null) Color(0xFF00E5FF) else Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Manual Point A & Point B Timestamp Entry Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = manualPointAInput,
                                onValueChange = { if (it.length <= 9) manualPointAInput = it },
                                singleLine = true,
                                placeholder = { Text("A (0:00)", fontSize = 11.5.sp, color = BentoTextSecondary) },
                                label = { Text("Point A", fontSize = 10.5.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00E5FF),
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                                    focusedTextColor = BentoTextPrimary,
                                    unfocusedTextColor = BentoTextPrimary,
                                    focusedLabelColor = Color(0xFF00E5FF),
                                    unfocusedLabelColor = BentoTextSecondary
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("manual_point_a_input")
                            )

                            OutlinedTextField(
                                value = manualPointBInput,
                                onValueChange = { if (it.length <= 9) manualPointBInput = it },
                                singleLine = true,
                                placeholder = { Text("B ($formattedMaxDuration)", fontSize = 11.5.sp, color = BentoTextSecondary) },
                                label = { Text("Point B", fontSize = 10.5.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00E5FF),
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                                    focusedTextColor = BentoTextPrimary,
                                    unfocusedTextColor = BentoTextPrimary,
                                    focusedLabelColor = Color(0xFF00E5FF),
                                    unfocusedLabelColor = BentoTextSecondary
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("manual_point_b_input")
                            )

                            Button(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    val parsedA = if (manualPointAInput.isNotBlank()) {
                                        SonicStudioEngine.parseTimestampToMs(manualPointAInput)
                                    } else {
                                        if (isLoopForThisSong) abLoopState.startMs else 0L
                                    }
                                    val parsedB = if (manualPointBInput.isNotBlank()) {
                                        SonicStudioEngine.parseTimestampToMs(manualPointBInput)
                                    } else {
                                        if (isLoopForThisSong) abLoopState.endMs else null
                                    }

                                    if (manualPointAInput.isNotBlank() && parsedA == null) {
                                        Toast.makeText(context, "Enter Point A as m:ss (e.g. 0:30)", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (manualPointBInput.isNotBlank() && parsedB == null) {
                                        Toast.makeText(context, "Enter Point B as m:ss (e.g. 1:15)", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (effectiveDurationMs > 0L) {
                                        if ((parsedA != null && parsedA > effectiveDurationMs) ||
                                            (parsedB != null && parsedB > effectiveDurationMs)
                                        ) {
                                            Toast.makeText(
                                                context,
                                                "Clamped to track duration ($formattedMaxDuration)",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }

                                    val result = SonicStudioEngine.setManualLoopPoints(
                                        songId = song.id,
                                        rawStartMs = parsedA,
                                        rawEndMs = parsedB,
                                        durationMs = effectiveDurationMs,
                                        loopCount = currentLoopCount
                                    )
                                    if (!result.success) {
                                        Toast.makeText(
                                            context,
                                            result.errorMessage ?: "Point B must be after Point A",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        manualPointAInput = result.state.formatPoint(result.state.startMs)
                                        manualPointBInput = if (result.state.endMs != null) {
                                            result.state.formatPoint(result.state.endMs)
                                        } else ""
                                        result.state.startMs?.let { startTarget ->
                                            val curPos = posState?.value ?: fallbackPositionMs
                                            val endTarget = result.state.endMs
                                            if (curPos < startTarget || (endTarget != null && curPos >= endTarget)) {
                                                onSeekTo(startTarget)
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .height(50.dp)
                                    .testTag("apply_manual_loop_points_button")
                            ) {
                                Text(
                                    text = "Set",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF06151F)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Loop Count Selector (Default 3, Min 1, Max 15)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Loop Repeat Count: ${currentLoopCount}x",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                                Text(
                                    text = "Resumes normal playback after ${currentLoopCount}x",
                                    fontSize = 10.5.sp,
                                    color = BentoTextSecondary
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    onClick = {
                                        if (currentLoopCount > com.example.util.AbLoopState.MIN_LOOP_COUNT) {
                                            HapticUtils.performClickHaptic(context)
                                            SonicStudioEngine.setLoopCount(currentLoopCount - 1)
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White.copy(alpha = 0.08f),
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("loop_count_decrease_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Remove,
                                            contentDescription = "Decrease loop count",
                                            tint = if (currentLoopCount > com.example.util.AbLoopState.MIN_LOOP_COUNT) Color.White else BentoTextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = "${currentLoopCount}",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF00E5FF),
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .testTag("loop_count_value_text")
                                )

                                Surface(
                                    onClick = {
                                        if (currentLoopCount < com.example.util.AbLoopState.MAX_LOOP_COUNT) {
                                            HapticUtils.performClickHaptic(context)
                                            SonicStudioEngine.setLoopCount(currentLoopCount + 1)
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White.copy(alpha = 0.08f),
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("loop_count_increase_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Increase loop count",
                                            tint = if (currentLoopCount < com.example.util.AbLoopState.MAX_LOOP_COUNT) Color.White else BentoTextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                listOf(3, 5, 10, 15).forEach { presetCount ->
                                    val isPresetSelected = currentLoopCount == presetCount
                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            SonicStudioEngine.setLoopCount(presetCount)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isPresetSelected) Color(0xFF00E5FF).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isPresetSelected) Color(0xFF00E5FF).copy(alpha = 0.7f) else Color.Transparent
                                        ),
                                        modifier = Modifier
                                            .height(28.dp)
                                            .testTag("loop_count_preset_${presetCount}")
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 7.dp)
                                        ) {
                                            Text(
                                                text = "${presetCount}x",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isPresetSelected) Color(0xFF00E5FF) else BentoTextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Timestamped Audio Bookmarks Section
                Text(
                    text = "Timestamped Audio Bookmarks",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Quick Preset Chips to Pin Current Moment
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BOOKMARK_PRESETS.forEach { preset ->
                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                val nowPos = posState?.value ?: fallbackPositionMs
                                SonicStudioEngine.addBookmark(context, song.id, nowPos, preset)
                                Toast.makeText(context, "Pinned '$preset' at $formattedCurrentTime", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(50),
                            color = BentoCardDarkPurple,
                            border = BorderStroke(1.dp, Color(0xFFFFD54F).copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkAdd,
                                    contentDescription = null,
                                    tint = Color(0xFFFFD54F),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "+ $preset",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Custom Label Pin Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customBookmarkLabel,
                        onValueChange = { customBookmarkLabel = it },
                        placeholder = {
                            Text("Custom note at $formattedCurrentTime...", fontSize = 12.5.sp, color = BentoTextSecondary)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = BentoPrimary,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.16f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    )

                    Button(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            val nowPos = posState?.value ?: fallbackPositionMs
                            val label = customBookmarkLabel.trim().ifEmpty { "Pin $formattedCurrentTime" }
                            SonicStudioEngine.addBookmark(context, song.id, nowPos, label)
                            customBookmarkLabel = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("pin_custom_bookmark_button")
                    ) {
                        Icon(Icons.Default.PushPin, contentDescription = "Pin", tint = BentoOnPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pin", color = BentoOnPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (songBookmarks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BentoCardDarkPurple.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No bookmarks pinned for this track yet.\nTap a preset above to mark solos, drops, or verses.",
                            fontSize = 12.sp,
                            color = BentoTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(songBookmarks, key = { it.id }) { bookmark ->
                            Surface(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onSeekTo(bookmark.timestampMs)
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = BentoCardDarkPurple,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFFFFD54F).copy(alpha = 0.18f)
                                        ) {
                                            Text(
                                                text = bookmark.formattedTime,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFFFD54F),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        Text(
                                            text = bookmark.label,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            SonicStudioEngine.removeBookmark(context, song.id, bookmark.id)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete bookmark",
                                            tint = BentoTextSecondary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // TAB 1: Sonic DNA Instant Track Radio
                Text(
                    text = "Matched ${dnaMatches.size} tracks in your local library by artist affinity, genre vibe, era, and tempo profile.",
                    fontSize = 12.5.sp,
                    color = BentoTextSecondary
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        val radioSongs = dnaMatches.map { it.song }
                        onLaunchSonicDnaRadio(radioSongs)
                        Toast.makeText(
                            context,
                            "Sonic DNA Radio started (${radioSongs.size} tracks)",
                            Toast.LENGTH_SHORT
                        ).show()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("launch_sonic_dna_radio_button")
                ) {
                    Icon(Icons.Default.Radar, contentDescription = null, tint = BentoOnPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Start Sonic DNA Radio (${dnaMatches.size} Tracks)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoOnPrimary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(dnaMatches, key = { "${it.song.id}_${it.song.path}" }) { match ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BentoCardDarkPurple,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AlbumArtView(
                                    song = match.song,
                                    modifier = Modifier.size(40.dp),
                                    cornerRadius = 8
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = match.song.displayTitle,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${match.song.displayArtist} • ${match.matchReason}",
                                        fontSize = 11.5.sp,
                                        color = Color(0xFF00E5FF),
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
}
