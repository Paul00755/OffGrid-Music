package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
import com.example.BuildConfig
import com.example.data.model.FolderInfo
import com.example.service.SleepTimerState
import com.example.service.transcription.AudioTranscribeMode
import com.example.service.transcription.TranscribeLanguage
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.update.UpdateCheckResult
import com.example.util.HapticUtils

enum class SettingsSection(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ALL("All", Icons.Default.Tune),
    AUDIO("Audio & Sound", Icons.Default.Equalizer),
    UI("UI & Theme", Icons.Default.Palette),
    HOME_FEED("Home Feed", Icons.Default.DashboardCustomize),
    CACHE("Cache & Storage", Icons.Default.Storage),
    PLAYBACK("Playback & Timers", Icons.Default.PlayCircle),
    LIBRARY("Library & Folders", Icons.Default.Folder),
    OTHER("Other & Privacy", Icons.Default.Security),
    CLOUD_SYNC("Cloud & Sync", Icons.Default.CloudSync)
}

data class SettingsCategoryCardItem(
    val section: SettingsSection,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentColor: Color
)

val SETTINGS_CATEGORY_CARD_ITEMS = listOf(
    SettingsCategoryCardItem(SettingsSection.AUDIO, "Audio & Sound", "Crossfade, Equalizer, Loudness normalization & Balance", Icons.Default.Equalizer, Color(0xFF00E5FF)),
    SettingsCategoryCardItem(SettingsSection.UI, "UI & Theme", "Dark/Light/AMOLED, Dynamic colors & Screen layouts", Icons.Default.Palette, Color(0xFFBB86FC)),
    SettingsCategoryCardItem(SettingsSection.HOME_FEED, "Home Feed", "Customize home sections, Reorder & Custom feed presets", Icons.Default.DashboardCustomize, Color(0xFF7C4DFF)),
    SettingsCategoryCardItem(SettingsSection.PLAYBACK, "Playback & Timers", "Sleep timer, Volume automation, Gestures & Queue behavior", Icons.Default.PlayCircle, Color(0xFFFF9100)),
    SettingsCategoryCardItem(SettingsSection.LIBRARY, "Library & Folders", "Scan device storage, Exclude folders & Short-clip filter", Icons.Default.Folder, Color(0xFF2979FF)),
    SettingsCategoryCardItem(SettingsSection.CACHE, "Cache & Storage", "Artwork cache, Lyrics storage & SQLite database optimization", Icons.Default.Storage, Color(0xFF00E676)),
    SettingsCategoryCardItem(SettingsSection.CLOUD_SYNC, "Cloud & Sync", "Google & Email account, Cloud backup & Cross-device sync", Icons.Default.CloudSync, Color(0xFF00E5FF)),
    SettingsCategoryCardItem(SettingsSection.OTHER, "Other & Privacy", "Search history, Diagnostics logs, Updates & Reset", Icons.Default.Security, Color(0xFFFF5252))
)

private val SETTINGS_WHITESPACE_REGEX = Regex("[\\s\\-_/.]+")

private fun tokenizeSettingsQuery(query: String): List<String> {
    if (query.isBlank()) return emptyList()
    return query.trim()
        .lowercase()
        .split(SETTINGS_WHITESPACE_REGEX)
        .filter { it.isNotEmpty() }
}

private fun matchesSettingsTokens(queryTokens: List<String>, vararg keywords: String): Boolean {
    if (queryTokens.isEmpty()) return false
    val normalizedTarget = buildString {
        for (i in keywords.indices) {
            if (i > 0) append(' ')
            append(keywords[i].lowercase().replace('-', ' ').replace('_', ' '))
        }
    }
    val targetWords = normalizedTarget.split(' ').filter { it.isNotEmpty() }
    // All query tokens must match (AND semantics across words) via substring, prefix, or acronym/subsequence
    return queryTokens.all { term ->
        if (normalizedTarget.contains(term)) {
            true
        } else if (term.length >= 2 && targetWords.any { word -> word.startsWith(term) || (word.length >= 3 && term.startsWith(word)) }) {
            true
        } else if (term.length in 2..5 && targetWords.size >= term.length) {
            // Acronym match (e.g., "eq", "hr", "np")
            val initials = targetWords.joinToString("") { it.take(1) }
            initials.contains(term)
        } else {
            false
        }
    }
}

private fun matchesSettingsSearch(query: String, vararg keywords: String): Boolean {
    val tokens = tokenizeSettingsQuery(query)
    return matchesSettingsTokens(tokens, *keywords)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreenSectionsManagerCard(
    currentSections: List<String>,
    onUpdateSections: (List<String>) -> Unit,
    customPresets: List<com.example.data.local.CustomHomeFeedPreset> = emptyList(),
    onSaveCustomPreset: (String, List<String>) -> Unit = { _, _ -> },
    onDeleteCustomPreset: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeDescriptors = remember(currentSections) {
        currentSections.map { normalizeHomeSectionKey(it) }
            .distinct()
            .mapNotNull { normKey -> ALL_HOME_SECTIONS.find { it.key == normKey } }
    }
    val inactiveDescriptors = remember(activeDescriptors) {
        val activeKeys = activeDescriptors.map { it.key }.toSet()
        ALL_HOME_SECTIONS.filter { it.key !in activeKeys }
    }

    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<com.example.data.local.CustomHomeFeedPreset?>(null) }

    val builtInPresets = remember {
        listOf(
            "Default Full" to listOf("continue_listening", "quick_actions", "listening_stats", "most_played", "most_played_playlists", "recently_played", "recently_added", "top_artists", "top_albums"),
            "Minimalist" to listOf("recently_played", "most_played_playlists"),
            "Discovery & Stats" to listOf("listening_stats", "most_played", "top_artists", "top_albums"),
            "Recents First" to listOf("recently_played", "recently_added", "most_played_playlists")
        )
    }

    val activeKeys = remember(activeDescriptors) { activeDescriptors.map { it.key } }

    if (showSavePresetDialog) {
        var presetNameInput by remember { mutableStateOf("") }
        var nameError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = {
                Text(
                    text = "Save Custom Feed Configuration",
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Save your current ${activeDescriptors.size} active section(s) and their exact display order as a reusable preset.",
                        fontSize = 12.5.sp,
                        color = BentoTextSecondary,
                        lineHeight = 18.sp
                    )
                    OutlinedTextField(
                        value = presetNameInput,
                        onValueChange = {
                            presetNameInput = it.take(28)
                            nameError = null
                        },
                        label = { Text("Configuration name") },
                        placeholder = { Text("e.g., Focus, Commute, Evening") },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = nameError?.let { err -> { Text(err, color = BentoError) } },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BentoPrimary,
                            focusedLabelColor = BentoPrimary,
                            unfocusedContainerColor = BentoCardPurple.copy(alpha = 0.45f),
                            focusedContainerColor = BentoCardPurple.copy(alpha = 0.65f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_feed_custom_preset_name_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = presetNameInput.trim()
                        when {
                            trimmed.isEmpty() -> nameError = "Enter a name for this configuration"
                            activeKeys.isEmpty() -> nameError = "Enable at least one section before saving"
                            builtInPresets.any { it.first.equals(trimmed, ignoreCase = true) } -> {
                                nameError = "Choose a different name from built-in presets"
                            }
                            else -> {
                                HapticUtils.performClickHaptic(context)
                                onSaveCustomPreset(trimmed, activeKeys)
                                showSavePresetDialog = false
                                android.widget.Toast.makeText(
                                    context,
                                    "Saved \"$trimmed\" feed configuration",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary),
                    modifier = Modifier.testTag("home_feed_save_preset_confirm_button")
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(22.dp)
        )
    }

    presetToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = {
                Text(
                    text = "Delete \"${target.name}\"?",
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "This removes the saved feed configuration from your custom presets. Your currently active Home sections will not change.",
                    fontSize = 13.sp,
                    color = BentoTextSecondary,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDeleteCustomPreset(target.name)
                        presetToDelete = null
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = BentoError, contentColor = Color.White)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(22.dp)
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Group A: Built-in & Custom Saved Presets
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSubGroupLabel(title = "FEED PRESETS & CUSTOM CONFIGURATIONS")
            SettingsCardContainer(accentGlow = BentoPrimary) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Quick Feed Presets",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Apply a built-in layout or save your own named setup",
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            showSavePresetDialog = true
                        },
                        enabled = activeKeys.isNotEmpty(),
                        shape = CircleShape,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = BentoPrimary.copy(alpha = 0.16f),
                            contentColor = BentoPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .testTag("home_feed_save_custom_preset_button")
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Save Current", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    builtInPresets.forEach { (presetName, presetSections) ->
                        val isCurrentMatch = activeKeys == presetSections
                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onUpdateSections(presetSections)
                            },
                            shape = CircleShape,
                            color = if (isCurrentMatch) BentoPrimary else BentoCardPurple.copy(alpha = 0.55f),
                            border = BorderStroke(
                                1.dp,
                                if (isCurrentMatch) BentoPrimary else BentoBorder.copy(alpha = 0.55f)
                            )
                        ) {
                            Text(
                                text = presetName,
                                fontSize = 12.sp,
                                fontWeight = if (isCurrentMatch) FontWeight.Bold else FontWeight.Medium,
                                color = if (isCurrentMatch) BentoOnPrimary else BentoTextPrimary,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                if (customPresets.isNotEmpty()) {
                    SettingsDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "YOUR SAVED CONFIGURATIONS",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp,
                            color = BentoTextSubtle
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            customPresets.forEach { preset ->
                                val normalizedPreset = remember(preset.sections) {
                                    preset.sections.map { normalizeHomeSectionKey(it) }.distinct()
                                }
                                val isCurrentMatch = activeKeys == normalizedPreset
                                Surface(
                                    shape = CircleShape,
                                    color = if (isCurrentMatch) BentoPrimary else BentoPrimary.copy(alpha = 0.10f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isCurrentMatch) BentoPrimary else BentoPrimary.copy(alpha = 0.35f)
                                    ),
                                    modifier = Modifier.testTag("custom_home_preset_${preset.name.lowercase().replace(' ', '_')}")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clickable {
                                                HapticUtils.performClickHaptic(context)
                                                onUpdateSections(normalizedPreset)
                                            }
                                            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                                    ) {
                                        Text(
                                            text = preset.name,
                                            fontSize = 12.sp,
                                            fontWeight = if (isCurrentMatch) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (isCurrentMatch) BentoOnPrimary else BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${normalizedPreset.size}",
                                            fontSize = 10.5.sp,
                                            color = if (isCurrentMatch) BentoOnPrimary.copy(alpha = 0.8f) else BentoTextSecondary
                                        )
                                        IconButton(
                                            onClick = {
                                                HapticUtils.performClickHaptic(context)
                                                presetToDelete = preset
                                            },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Delete ${preset.name}",
                                                tint = if (isCurrentMatch) BentoOnPrimary.copy(alpha = 0.85f) else BentoTextSecondary,
                                                modifier = Modifier.size(14.dp)
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

        // Group B: Active Sections (Display Order)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSubGroupLabel(title = "ACTIVE SECTIONS (DISPLAY ORDER)")
            SettingsCardContainer(accentGlow = BentoPrimary) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Home Screen Feed Sections",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Reorder, show, or hide sections across all home layouts",
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = BentoPrimary.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = "${activeDescriptors.size} / ${ALL_HOME_SECTIONS.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoPrimary,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }

                if (activeDescriptors.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BentoCardPurple.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No sections active. Enable sections below to populate your Home feed.",
                            fontSize = 12.sp,
                            color = BentoTextSubtle,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        activeDescriptors.forEachIndexed { index, descriptor ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = BentoCardPurple.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = BentoPrimary.copy(alpha = 0.16f),
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "${index + 1}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BentoPrimary
                                                )
                                            }
                                        }

                                        Icon(
                                            imageVector = descriptor.icon,
                                            contentDescription = null,
                                            tint = BentoPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = descriptor.title,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = BentoTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = descriptor.subtitle,
                                                fontSize = 10.5.sp,
                                                color = BentoTextSubtle,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (index > 0) {
                                                    HapticUtils.performClickHaptic(context)
                                                    val updatedKeys = activeDescriptors.map { it.key }.toMutableList()
                                                    val item = updatedKeys.removeAt(index)
                                                    updatedKeys.add(index - 1, item)
                                                    onUpdateSections(updatedKeys)
                                                }
                                            },
                                            enabled = index > 0,
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowUp,
                                                contentDescription = "Move Up",
                                                tint = if (index > 0) BentoTextPrimary else BentoTextSubtle.copy(alpha = 0.35f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                if (index < activeDescriptors.size - 1) {
                                                    HapticUtils.performClickHaptic(context)
                                                    val updatedKeys = activeDescriptors.map { it.key }.toMutableList()
                                                    val item = updatedKeys.removeAt(index)
                                                    updatedKeys.add(index + 1, item)
                                                    onUpdateSections(updatedKeys)
                                                }
                                            },
                                            enabled = index < activeDescriptors.size - 1,
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Move Down",
                                                tint = if (index < activeDescriptors.size - 1) BentoTextPrimary else BentoTextSubtle.copy(alpha = 0.35f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                HapticUtils.performClickHaptic(context)
                                                val updatedKeys = activeDescriptors.map { it.key }.toMutableList()
                                                updatedKeys.removeAt(index)
                                                onUpdateSections(updatedKeys)
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.VisibilityOff,
                                                contentDescription = "Hide Section",
                                                tint = BentoError.copy(alpha = 0.85f),
                                                modifier = Modifier.size(16.dp)
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

        // Group C: Inactive / Hidden Sections
        if (inactiveDescriptors.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSubGroupLabel(title = "HIDDEN SECTIONS")
                SettingsCardContainer(accentGlow = BentoPrimary) {
                    Text(
                        text = "Tap any section below to add it back to your Home feed",
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        inactiveDescriptors.forEach { descriptor ->
                            Surface(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    val updatedKeys = activeDescriptors.map { it.key }.toMutableList()
                                    updatedKeys.add(descriptor.key)
                                    onUpdateSections(updatedKeys)
                                },
                                shape = CircleShape,
                                color = BentoCardPurple.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add",
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = descriptor.title,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = BentoTextPrimary
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

val PRESET_ACCENT_COLORS = listOf(
    "CYAN" to Color(0xFF00E5FF),
    "PURPLE" to Color(0xFFBB86FC),
    "PINK" to Color(0xFFFF4081),
    "GREEN" to Color(0xFF00E676),
    "ORANGE" to Color(0xFFFF9100),
    "GOLD" to Color(0xFFFFD700),
    "BLUE" to Color(0xFF2979FF),
    "RED" to Color(0xFFFF5252),
    "TEAL" to Color(0xFF1DE9B6),
    "INDIGO" to Color(0xFF7C4DFF),
    "DARK_RED" to Color(0xFF8B1A1A),
    "DARK_PURPLE" to Color(0xFF4A148C),
    "DARK_BLUE" to Color(0xFF1A3A7A),
    "DARK_GREEN" to Color(0xFF1B5E20),
    "DARK_TEAL" to Color(0xFF004D40),
    "DARK_AMBER" to Color(0xFF7F3300)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    folders: List<FolderInfo>,
    sleepTimerState: SleepTimerState,
    waveformStyle: String = "SNAKE",
    onSetWaveformStyle: (String) -> Unit = {},
    themeMode: String = "DARK",
    onSetThemeMode: (String) -> Unit = {},
    uiTheme: String = "MIDNIGHT_OBSIDIAN",
    onSetUiTheme: (String) -> Unit = {},
    displayScale: String = "DEFAULT",
    onSetDisplayScale: (String) -> Unit = {},
    homeScreenLayout: String = "HERO_SPOTLIGHT",
    onSetHomeScreenLayout: (String) -> Unit = {},
    nowPlayingLayout: String = "MODERN_BENTO",
    onSetNowPlayingLayout: (String) -> Unit = {},
    playlistsLayout: String = "BENTO_GRID",
    onSetPlaylistsLayout: (String) -> Unit = {},
    lyricsLayout: String = "CLASSIC_DEFAULT",
    onSetLyricsLayout: (String) -> Unit = {},
    showNowPlayingVolumeBar: Boolean = false,
    onSetShowNowPlayingVolumeBar: (Boolean) -> Unit = {},
    showAdvancedSongInfo: Boolean = false,
    onSetShowAdvancedSongInfo: (Boolean) -> Unit = {},
    isDynamicArtworkTheme: Boolean = false,
    onSetDynamicArtworkTheme: (Boolean) -> Unit = {},
    useDarkColors: Boolean = false,
    onSetUseDarkColors: (Boolean) -> Unit = {},
    isGradientEffect: Boolean = true,
    onSetGradientEffect: (Boolean) -> Unit = {},
    isGlassEffect: Boolean = true,
    onSetGlassEffect: (Boolean) -> Unit = {},
    isGradientGlassEffect: Boolean = true,
    onSetGradientGlassEffect: (Boolean) -> Unit = {},
    fetchOnlineArtworkHighRes: Boolean = false,
    onSetFetchOnlineArtworkHighRes: (Boolean) -> Unit = {},
    cacheLyricsEnabled: Boolean = true,
    onSetCacheLyricsEnabled: (Boolean) -> Unit = {},
    onClearLyricsCache: () -> Unit = {},
    cacheAlbumArtEnabled: Boolean = true,
    onSetCacheAlbumArtEnabled: (Boolean) -> Unit = {},
    onClearAlbumArtCache: () -> Unit = {},
    cacheSongArtWorkEnabled: Boolean = true,
    onSetCacheSongArtWorkEnabled: (Boolean) -> Unit = {},
    onClearSongArtCache: () -> Unit = {},
    lyricsCacheSize: String = "0 KB",
    albumArtCacheSize: String = "0 KB",
    songArtCacheSize: String = "0 KB",
    totalCacheSize: String = "0 KB",
    onRefreshCacheSizes: () -> Unit = {},
    onClearAllCaches: () -> Unit = {},
    databaseStorageTier: String = "EXPANDED",
    onSetDatabaseStorageTier: (String) -> Unit = {},
    databaseStorageStats: com.example.data.local.DatabaseStorageStats? = null,
    isOptimizingDatabase: Boolean = false,
    onOptimizeDatabase: ((reclaimedBytes: Long, message: String) -> Unit) -> Unit = { it(0L, "") },
    onRefreshDatabaseStats: () -> Unit = {},
    customEqBands: List<Float> = emptyList(),
    onSetCustomEqBands: (List<Float>) -> Unit = {},
    customBassBoost: Int = 0,
    onSetCustomBassBoost: (Int) -> Unit = {},
    customVirtualizer: Int = 0,
    onSetCustomVirtualizer: (Int) -> Unit = {},
    customAccentColor: Int = 0,
    onSetCustomAccentColor: (Int) -> Unit = {},
    accentColorPreset: String = "CYAN",
    onSetAccentColorPreset: (String) -> Unit = {},
    miniPlayerStyle: String = "FLOATING",
    onSetMiniPlayerStyle: (String) -> Unit = {},
    miniPlayerShowPrevious: Boolean = true,
    onSetMiniPlayerShowPrevious: (Boolean) -> Unit = {},
    miniPlayerShowNext: Boolean = true,
    onSetMiniPlayerShowNext: (Boolean) -> Unit = {},
    miniPlayerShowFavorite: Boolean = true,
    onSetMiniPlayerShowFavorite: (Boolean) -> Unit = {},
    miniPlayerSwipeToSkip: Boolean = true,
    onSetMiniPlayerSwipeToSkip: (Boolean) -> Unit = {},
    miniPlayerSwipeUpToOpen: Boolean = true,
    onSetMiniPlayerSwipeUpToOpen: (Boolean) -> Unit = {},
    miniPlayerSwipeDownToDismiss: Boolean = false,
    onSetMiniPlayerSwipeDownToDismiss: (Boolean) -> Unit = {},
    miniPlayerShowProgress: Boolean = true,
    onSetMiniPlayerShowProgress: (Boolean) -> Unit = {},
    miniPlayerCircularArt: Boolean = false,
    onSetMiniPlayerCircularArt: (Boolean) -> Unit = {},
    livePreviewSong: com.example.data.model.Song? = null,
    livePreviewIsPlaying: Boolean = false,
    livePreviewLyrics: com.example.data.model.Lyrics? = null,
    livePreviewPlaylists: List<com.example.data.model.Playlist> = emptyList(),
    livePreviewPlaylistNames: List<String> = emptyList(),
    livePreviewRecentSongs: List<com.example.data.model.Song> = emptyList(),
    livePreviewHomeStats: StudioHomeLiveStats = StudioHomeLiveStats(),
    gaplessPlayback: Boolean = true,
    onSetGaplessPlayback: (Boolean) -> Unit = {},
    normalizeAudio: Boolean = false,
    onSetNormalizeAudio: (Boolean) -> Unit = {},
    autoPlayOnHeadphoneConnection: Boolean = false,
    onSetAutoPlayOnHeadphoneConnection: (Boolean) -> Unit = {},
    autoPlayOnAppStartup: Boolean = false,
    onSetAutoPlayOnAppStartup: (Boolean) -> Unit = {},
    isTranscribeTranslateEnabled: Boolean = false,
    onSetTranscribeTranslateEnabled: (Boolean) -> Unit = {},
    audioTranscribeTargetLang: String = "ta",
    onSetAudioTranscribeTargetLang: (String) -> Unit = {},
    hasUserApiKey: Boolean = false,
    maskedApiKey: String? = null,
    isKeyValidating: Boolean = false,
    keyValidationErrorMessage: String? = null,
    onSaveUserApiKey: (String, (Boolean, String?) -> Unit) -> Unit = { _, _ -> },
    onDeleteUserApiKey: () -> Unit = {},
    onClearKeyValidationError: () -> Unit = {},
    pauseOnZeroVolume: Boolean = false,
    onSetPauseOnZeroVolume: (Boolean) -> Unit = {},
    autoResumeOnVolumeIncrease: Boolean = false,
    onSetAutoResumeOnVolumeIncrease: (Boolean) -> Unit = {},
    ignoreAudioDuration: Int = 0,
    onSetIgnoreAudioDuration: (Int) -> Unit = {},
    maxSongDuration: Int = 300,
    fullDeviceScan: Boolean = false,
    onSetFullDeviceScan: (Boolean) -> Unit = {},
    storeIndexedSongsLocally: Boolean = false,
    onSetStoreIndexedSongsLocally: (Boolean) -> Unit = {},
    localSongSyncProgress: com.example.data.repository.LocalSongSyncProgress = com.example.data.repository.LocalSongSyncProgress(),
    onSetSongDurationRange: (minSec: Int, maxSec: Int) -> Unit = { _, _ -> },
    fadeInOutMs: Int = 1000,
    onSetFadeInOutMs: (Int) -> Unit = {},
    fadeInMs: Int = 1000,
    onSetFadeInMs: (Int) -> Unit = {},
    fadeOutMs: Int = 1000,
    onSetFadeOutMs: (Int) -> Unit = {},
    crossfadeDurationSec: Int = 0,
    onSetCrossfadeDurationSec: (Int) -> Unit = {},
    shakeToSkip: Boolean = false,
    onSetShakeToSkip: (Boolean) -> Unit = {},
    keepScreenAwake: Boolean = false,
    onSetKeepScreenAwake: (Boolean) -> Unit = {},
    audioBalance: Float = 0.0f,
    onSetAudioBalance: (Float) -> Unit = {},
    equalizerPreset: String = "NORMAL",
    onSetEqualizerPreset: (String) -> Unit = {},
    lyricsAutoScroll: Boolean = true,
    onSetLyricsAutoScroll: (Boolean) -> Unit = {},
    reduceVolumeOnNotification: Boolean = true,
    onSetReduceVolumeOnNotification: (Boolean) -> Unit = {},
    autoPauseSystemAudio: Boolean = true,
    onSetAutoPauseSystemAudio: (Boolean) -> Unit = {},
    sleepTimerMode: String = "TIME",
    onSetSleepTimerMode: (String) -> Unit = {},
    scrobbleThresholdType: String = "PERCENTAGE",
    onSetScrobbleThresholdType: (String) -> Unit = {},
    scrobbleThresholdValue: Int = 50,
    onSetScrobbleThresholdValue: (Int) -> Unit = {},
    highRefreshRate: Boolean = true,
    onSetHighRefreshRate: (Boolean) -> Unit = {},
    alwaysShuffleMode: Boolean = false,
    onSetAlwaysShuffleMode: (Boolean) -> Unit = {},
    gesturePlaybackEnabled: Boolean = true,
    onSetGesturePlaybackEnabled: (Boolean) -> Unit = {},
    addToQueuePosition: String = "BOTTOM",
    onSetAddToQueuePosition: (String) -> Unit = {},
    playlistAddSongPosition: String = "BOTTOM",
    onSetPlaylistAddSongPosition: (String) -> Unit = {},
    homeSections: List<String> = emptyList(),
    onSetHomeSections: (List<String>) -> Unit = {},
    customHomeFeedPresets: List<com.example.data.local.CustomHomeFeedPreset> = emptyList(),
    onSaveCustomHomeFeedPreset: (String, List<String>) -> Unit = { _, _ -> },
    onDeleteCustomHomeFeedPreset: (String) -> Unit = {},
    recentSearches: List<String> = emptyList(),
    onDeleteRecentSearch: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    onAddFolder: (path: String, name: String, isExcluded: Boolean) -> Unit = { _, _, _ -> },
    onRemoveFolder: (path: String) -> Unit = {},
    onStartSleepTimer: (minutes: Int, finishLastSong: Boolean) -> Unit = { _, _ -> },
    onStartSongCountTimer: (songs: Int) -> Unit = {},
    onAddSleepTimerMinutes: (minutes: Int) -> Unit = {},
    onAddSleepTimerSongs: (songs: Int) -> Unit = {},
    onSetSleepTimerFinishLastSong: (Boolean) -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    onScanLibrary: () -> Unit = {},
    onQuickScanAndCleanLibrary: () -> Unit = {},
    scanProgress: com.example.scanner.ScanProgress = com.example.scanner.ScanProgress(),
    userAccount: com.example.auth.UserAccount? = null,
    effectiveDisplayName: String = "Music Lover",
    customAvatarUri: String? = null,
    syncStatus: com.example.data.sync.SyncStatus = com.example.data.sync.SyncStatus.Idle,
    backupStatus: com.example.data.sync.BackupStatus = com.example.data.sync.BackupStatus.Idle,
    lastSyncedTimestamp: Long = 0L,
    lastBackupTimestamp: Long = 0L,
    automaticBackupEnabled: Boolean = true,
    backupFrequency: com.example.data.sync.BackupFrequency = com.example.data.sync.BackupFrequency.WEEKLY,
    onToggleAutomaticBackup: (Boolean) -> Unit = {},
    onSelectFrequency: (com.example.data.sync.BackupFrequency) -> Unit = {},
    onTriggerSync: () -> Unit = {},
    onTriggerBackup: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenAuth: () -> Unit = {},
    onSignInGoogle: () -> Unit = onOpenAuth,
    onBackClick: () -> Unit = {},
    initialTargetSection: String? = null,
    onTargetSectionConsumed: () -> Unit = {},
    updateCheckResult: UpdateCheckResult = UpdateCheckResult.Idle,
    updateNotificationsEnabled: Boolean = true,
    onSetUpdateNotificationsEnabled: (Boolean) -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onOpenUpdateDialog: () -> Unit = {},
    onResetToDefaultSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var highlightedSettingKey by remember { mutableStateOf<String?>(null) }
    var selectedSection by remember {
        mutableStateOf(
            when (initialTargetSection?.uppercase()) {
                "AUDIO", "EQUALIZER", "EQ" -> SettingsSection.AUDIO
                "UI", "THEME", "REFRESH_RATE" -> SettingsSection.UI
                "HOME", "FEED" -> SettingsSection.HOME_FEED
                "CACHE", "STORAGE" -> SettingsSection.CACHE
                "PLAYBACK", "TIMER", "SLEEP_TIMER" -> SettingsSection.PLAYBACK
                "LIBRARY", "FOLDERS" -> SettingsSection.LIBRARY
                "OTHER", "PRIVACY" -> SettingsSection.OTHER
                "CLOUD", "SYNC", "CLOUD_SYNC", "ACCOUNT" -> SettingsSection.CLOUD_SYNC
                else -> SettingsSection.ALL
            }
        )
    }

    val tabHistory = remember { mutableStateListOf<SettingsSection>() }
    var apiKeyInput by remember { mutableStateOf("") }
    var isEditingApiKey by remember { mutableStateOf(false) }
    var showKeyText by remember { mutableStateOf(false) }

    fun navigateToSection(newSection: SettingsSection) {
        if (selectedSection != newSection) {
            tabHistory.add(selectedSection)
            selectedSection = newSection
        }
    }

    var showLayoutStudio by remember { mutableStateOf(false) }
    var layoutStudioInitialTab by remember { mutableStateOf("NOW_PLAYING") }
    var showWaveformPickerDialog by remember { mutableStateOf(false) }
    var showQueuePositionDialog by remember { mutableStateOf(false) }
    var showPlaylistPositionDialog by remember { mutableStateOf(false) }

    fun handleBack() {
        if (showLayoutStudio) {
            showLayoutStudio = false
            return
        }
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
            return
        }
        if (tabHistory.isNotEmpty()) {
            val previous = tabHistory.removeAt(tabHistory.lastIndex)
            selectedSection = previous
        } else if (selectedSection != SettingsSection.ALL) {
            selectedSection = SettingsSection.ALL
        } else {
            onBackClick()
        }
    }

    BackHandler(enabled = true) {
        handleBack()
    }

    var selectedLayoutCustomizerScreen by remember { mutableStateOf("NOW_PLAYING") }
    var isPickingForExclusion by remember { mutableStateOf(false) }
    var showColorPickerDialog by remember { mutableStateOf(false) }
    var showCustomTrackDialog by remember { mutableStateOf(false) }
    var showCustomMinutesDialog by remember { mutableStateOf(false) }
    var finishLastSongSettings by remember(sleepTimerState.finishLastSong) { mutableStateOf(sleepTimerState.finishLastSong) }
    var diagnosticsRefreshTrigger by remember { mutableIntStateOf(0) }
    var showHeadphonePermissionDialog by remember { mutableStateOf(false) }
    var showResetSettingsConfirmDialog by remember { mutableStateOf(false) }

    val bluetoothConnectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onSetAutoPlayOnHeadphoneConnection(true)
        } else {
            onSetAutoPlayOnHeadphoneConnection(false)
            android.widget.Toast.makeText(
                context,
                "Permission denied. Automatically play on headphone connection remains OFF.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(initialTargetSection) {
        val rawTarget = initialTargetSection?.trim()?.uppercase()
        if (!rawTarget.isNullOrEmpty()) {
            searchQuery = ""
            val targetSection = when (rawTarget) {
                "AUDIO", "EQUALIZER", "EQ" -> SettingsSection.AUDIO
                "UI", "THEME", "REFRESH_RATE" -> SettingsSection.UI
                "HOME", "FEED" -> SettingsSection.HOME_FEED
                "CACHE", "STORAGE" -> SettingsSection.CACHE
                "PLAYBACK", "TIMER", "SLEEP_TIMER" -> SettingsSection.PLAYBACK
                "LIBRARY", "FOLDERS" -> SettingsSection.LIBRARY
                "OTHER", "PRIVACY" -> SettingsSection.OTHER
                "CLOUD", "SYNC", "CLOUD_SYNC", "ACCOUNT" -> SettingsSection.CLOUD_SYNC
                else -> SettingsSection.ALL
            }
            if (targetSection != SettingsSection.ALL && selectedSection != targetSection) {
                selectedSection = targetSection
            }
            val highlightKey = when (rawTarget) {
                "SLEEP_TIMER", "TIMER" -> "SLEEP_TIMER"
                "EQUALIZER", "EQ" -> "EQUALIZER"
                "REFRESH_RATE" -> "REFRESH_RATE"
                else -> if (targetSection != SettingsSection.ALL) "SECTION_${targetSection.name}" else null
            }
            highlightedSettingKey = highlightKey
            if (targetSection != SettingsSection.ALL) {
                try {
                    listState.scrollToItem(0)
                } catch (_: Exception) {}
            }
            if (highlightKey != null) {
                kotlinx.coroutines.delay(1350L)
                if (highlightedSettingKey == highlightKey) {
                    highlightedSettingKey = null
                }
            }
            onTargetSectionConsumed()
        }
    }

    // Folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            val storageType = parts.firstOrNull() ?: ""
            val subPath = if (parts.size > 1) parts[1] else ""

            val resolvedPath = when {
                storageType.equals("primary", ignoreCase = true) -> {
                    if (subPath.isNotBlank()) "/storage/emulated/0/$subPath" else "/storage/emulated/0"
                }
                storageType.startsWith("raw:") -> {
                    storageType.removePrefix("raw:").trimEnd('/')
                }
                storageType.startsWith("/") -> {
                    if (subPath.isNotBlank()) "$storageType/$subPath" else storageType
                }
                else -> {
                    if (subPath.isNotBlank()) "/storage/$storageType/$subPath" else "/storage/$storageType"
                }
            }

            val folderName = if (subPath.isNotBlank()) {
                subPath.substringAfterLast('/')
            } else {
                resolvedPath.substringAfterLast('/').ifBlank { "Selected Folder" }
            }

            onAddFolder(resolvedPath, folderName, isPickingForExclusion)
        }
    }

    // Custom Track Count Sleep Timer Dialog
    if (showCustomTrackDialog) {
        var trackInput by remember { mutableStateOf("5") }
        var trackError by remember { mutableStateOf<String?>(null) }
        Dialog(onDismissRequest = { showCustomTrackDialog = false }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = BentoCardSurface,
                border = BorderStroke(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            BentoPrimary.copy(alpha = 0.65f),
                            BentoBorder.copy(alpha = 0.4f)
                        )
                    )
                ),
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(BentoPrimary.copy(alpha = 0.16f))
                            .border(BorderStroke(1.2.dp, BentoPrimary.copy(alpha = 0.4f)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Numbers, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(26.dp))
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Track-Based Sleep Timer",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Stop playback automatically after playing a specific count of complete songs (max 50 tracks).",
                            fontSize = 12.sp,
                            color = BentoTextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )
                    }

                    // Quick Track Count Presets
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(1, 5, 10, 25, 50).forEach { count ->
                            val isSelected = trackInput == count.toString()
                            Surface(
                                shape = CircleShape,
                                color = if (isSelected) BentoPrimary else BentoCardPurple,
                                border = BorderStroke(1.dp, if (isSelected) BentoPrimary else BentoBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        HapticUtils.performClickHaptic(context)
                                        trackInput = count.toString()
                                        trackError = null
                                    }
                            ) {
                                Text(
                                    text = "$count",
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) BentoOnPrimary else BentoTextPrimary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = trackInput,
                        onValueChange = {
                            trackInput = it.filter { ch -> ch.isDigit() }.take(3)
                            trackError = null
                        },
                        label = { Text("Or enter custom track count (1–50)") },
                        isError = trackError != null,
                        supportingText = trackError?.let { err -> { Text(err, color = BentoAccentPinkText) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BentoPrimary,
                            focusedLabelColor = BentoPrimary,
                            unfocusedContainerColor = BentoCardPurple.copy(alpha = 0.5f),
                            focusedContainerColor = BentoCardPurple
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCustomTrackDialog = false },
                            shape = CircleShape,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = BentoTextSecondary)
                        }
                        Button(
                            onClick = {
                                val count = trackInput.toIntOrNull() ?: 0
                                when {
                                    count <= 0 -> trackError = "Track count must be > 0"
                                    count > com.example.service.SleepTimerManager.MAX_TRACK_COUNT -> {
                                        trackError = "Maximum is ${com.example.service.SleepTimerManager.MAX_TRACK_COUNT} tracks"
                                    }
                                    else -> {
                                        trackError = null
                                        onStartSongCountTimer(count)
                                        showCustomTrackDialog = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary),
                            shape = CircleShape,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Set Timer", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Custom Minutes Sleep Timer Dialog
    if (showCustomMinutesDialog) {
        var minInput by remember { mutableStateOf("30") }
        var minError by remember { mutableStateOf<String?>(null) }
        Dialog(onDismissRequest = { showCustomMinutesDialog = false }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = BentoCardSurface,
                border = BorderStroke(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            BentoPrimary.copy(alpha = 0.7f),
                            BentoBorder.copy(alpha = 0.4f)
                        )
                    )
                ),
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(BentoPrimary.copy(alpha = 0.16f))
                            .border(BorderStroke(1.2.dp, BentoPrimary.copy(alpha = 0.4f)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Timer, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(26.dp))
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Time-Based Sleep Timer",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Turn off music automatically after the specified duration (max 3 hours / 180 mins).",
                            fontSize = 12.sp,
                            color = BentoTextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )
                    }

                    // Quick Minutes Presets
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(listOf(10, 15, 30, 45, 60, 90, 120, 180), key = { it }, contentType = { "min_chip" }) { mins ->
                            val isSelected = minInput == mins.toString()
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) BentoPrimary else BentoCardPurple,
                                border = BorderStroke(1.dp, if (isSelected) BentoPrimary else BentoBorder),
                                modifier = Modifier.clickable {
                                    HapticUtils.performClickHaptic(context)
                                    minInput = mins.toString()
                                    minError = null
                                }
                            ) {
                                Text(
                                    text = if (mins >= 60 && mins % 60 == 0) "${mins / 60}h" else "${mins}m",
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) BentoOnPrimary else BentoTextPrimary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = minInput,
                        onValueChange = {
                            minInput = it.filter { ch -> ch.isDigit() }.take(3)
                            minError = null
                        },
                        label = { Text("Or enter custom minutes (1–180)") },
                        isError = minError != null,
                        supportingText = minError?.let { err -> { Text(err, color = BentoAccentPinkText) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BentoPrimary,
                            focusedLabelColor = BentoPrimary,
                            unfocusedContainerColor = BentoCardPurple.copy(alpha = 0.5f),
                            focusedContainerColor = BentoCardPurple
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BentoCardPurple.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                HapticUtils.performClickHaptic(context)
                                val updated = !finishLastSongSettings
                                finishLastSongSettings = updated
                                onSetSleepTimerFinishLastSong(updated)
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Checkbox(
                                checked = finishLastSongSettings,
                                onCheckedChange = {
                                    finishLastSongSettings = it
                                    onSetSleepTimerFinishLastSong(it)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = BentoPrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "Finish last track before stopping",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoTextPrimary
                                )
                                Text(
                                    text = "Wait until the currently playing song completes",
                                    fontSize = 11.sp,
                                    color = BentoTextSecondary
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCustomMinutesDialog = false },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = BentoTextSecondary)
                        }
                        Button(
                            onClick = {
                                val mins = minInput.toIntOrNull() ?: 0
                                when {
                                    mins <= 0 -> minError = "Duration must be > 0 mins"
                                    mins > com.example.service.SleepTimerManager.MAX_TIME_MINUTES -> {
                                        minError = "Maximum duration is 3 hours (${com.example.service.SleepTimerManager.MAX_TIME_MINUTES} mins)"
                                    }
                                    else -> {
                                        minError = null
                                        onStartSleepTimer(mins, finishLastSongSettings)
                                        showCustomMinutesDialog = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Set Timer", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showHeadphonePermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showHeadphonePermissionDialog = false
                onSetAutoPlayOnHeadphoneConnection(false)
            },
            title = {
                Text(
                    text = "Nearby Devices Permission",
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
            },
            text = {
                Text(
                    text = "To detect when wireless Bluetooth headphones or earbuds connect and automatically start or resume music, Android requires permission to find nearby devices.",
                    color = BentoTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showHeadphonePermissionDialog = false
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            bluetoothConnectLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            onSetAutoPlayOnHeadphoneConnection(true)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                ) {
                    Text("Grant Permission", color = BentoOnPrimary)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showHeadphonePermissionDialog = false
                        onSetAutoPlayOnHeadphoneConnection(false)
                    }
                ) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            }
        )
    }

    // Interactive HSV Color Wheel + Hex + RGB Accent Color Picker Dialog
    if (showColorPickerDialog) {
        com.example.ui.components.RgbColorPickerDialog(
            initialColorArgb = customAccentColor,
            onReset = {
                onSetCustomAccentColor(0)
                showColorPickerDialog = false
            },
            onApply = { argb ->
                onSetCustomAccentColor(argb)
                onSetDynamicArtworkTheme(false)
                showColorPickerDialog = false
            },
            onDismiss = { showColorPickerDialog = false }
        )
    }

    if (showWaveformPickerDialog) {
        com.example.ui.components.WaveformStylePickerDialog(
            currentStyle = waveformStyle,
            onSelectStyle = { styleId ->
                HapticUtils.performClickHaptic(context)
                onSetWaveformStyle(styleId)
            },
            onDismiss = { showWaveformPickerDialog = false }
        )
    }

    if (showQueuePositionDialog) {
        val queueOptions = listOf(
            Triple("BOTTOM", "Bottom of Queue", "Append new tracks to the end of the Up-Next queue"),
            Triple("TOP_NEXT", "Top Next (Play Next)", "Insert immediately after the currently playing track")
        )
        AlertDialog(
            onDismissRequest = { showQueuePositionDialog = false },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(24.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Add to Queue Position",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    queueOptions.forEach { (value, title, desc) ->
                        val isSelected = addToQueuePosition.equals(value, ignoreCase = true)
                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onSetAddToQueuePosition(value)
                                showQueuePositionDialog = false
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) BentoPrimary.copy(alpha = 0.14f) else BentoCardPurple.copy(alpha = 0.45f),
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) BentoPrimary.copy(alpha = 0.7f) else BentoBorder.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("queue_position_radio_${value.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = BentoPrimary,
                                        unselectedColor = BentoTextSecondary
                                    )
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = title,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isSelected) BentoPrimary else BentoTextPrimary
                                    )
                                    Text(
                                        text = desc,
                                        fontSize = 11.5.sp,
                                        color = BentoTextSecondary,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQueuePositionDialog = false }) {
                    Text("Close", color = BentoPrimary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showPlaylistPositionDialog) {
        val playlistOptions = listOf(
            Triple("BOTTOM", "Bottom (End)", "Append newly added songs to the end of the playlist"),
            Triple("TOP", "Top (Beginning)", "Insert newly added songs at the top of the playlist")
        )
        AlertDialog(
            onDismissRequest = { showPlaylistPositionDialog = false },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(24.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlaylistAdd,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Add to Playlist Position",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    playlistOptions.forEach { (value, title, desc) ->
                        val isSelected = playlistAddSongPosition.equals(value, ignoreCase = true)
                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onSetPlaylistAddSongPosition(value)
                                showPlaylistPositionDialog = false
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) BentoPrimary.copy(alpha = 0.14f) else BentoCardPurple.copy(alpha = 0.45f),
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) BentoPrimary.copy(alpha = 0.7f) else BentoBorder.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("playlist_position_radio_${value.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = BentoPrimary,
                                        unselectedColor = BentoTextSecondary
                                    )
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = title,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isSelected) BentoPrimary else BentoTextPrimary
                                    )
                                    Text(
                                        text = desc,
                                        fontSize = 11.5.sp,
                                        color = BentoTextSecondary,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaylistPositionDialog = false }) {
                    Text("Close", color = BentoPrimary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showLayoutStudio) {
        UiLayoutStudioScreen(
            initialTabId = layoutStudioInitialTab,
            currentNowPlayingLayout = nowPlayingLayout,
            currentHomeScreenLayout = homeScreenLayout,
            currentPlaylistsLayout = playlistsLayout,
            currentLyricsLayout = lyricsLayout,
            waveformStyle = waveformStyle,
            themeMode = themeMode,
            isGradientEffect = isGradientEffect,
            isGlassEffect = isGlassEffect,
            useDarkColors = useDarkColors,
            showVolumeBar = showNowPlayingVolumeBar,
            showAdvancedSongInfo = showAdvancedSongInfo,
            homeSections = homeSections,
            liveSong = livePreviewSong,
            liveIsPlaying = livePreviewIsPlaying,
            liveLyrics = livePreviewLyrics,
            livePlaylists = livePreviewPlaylists,
            livePlaylistNames = livePreviewPlaylistNames,
            liveRecentSongs = livePreviewRecentSongs,
            liveUserDisplayName = effectiveDisplayName,
            liveHomeStats = livePreviewHomeStats,
            isOnboardingShadowMode = false,
            onConfirmLayouts = { np, home, pl, lyr ->
                if (!np.equals(nowPlayingLayout, true)) onSetNowPlayingLayout(np)
                if (!home.equals(homeScreenLayout, true)) onSetHomeScreenLayout(home)
                if (!pl.equals(playlistsLayout, true)) onSetPlaylistsLayout(pl)
                if (!lyr.equals(lyricsLayout, true)) onSetLyricsLayout(lyr)
            },
            onBack = { showLayoutStudio = false },
            modifier = modifier
        )
        return
    }

    val searchTokens = remember(searchQuery) { tokenizeSettingsQuery(searchQuery) }
    val isHubSpacing = selectedSection == SettingsSection.ALL && searchTokens.isEmpty()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp)
                .testTag("settings_lazy_column"),
            contentPadding = PaddingValues(top = 10.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(if (isHubSpacing) 4.dp else 14.dp)
        ) {
            val isSearching = searchTokens.isNotEmpty()

            // Account tile matcher
            val matchAccountTile = matchesSettingsTokens(
                searchTokens,
                "account", "profile", "sign in", "sign out", "login", "avatar", "guest profile", "user"
            )

            // Section 1: Audio & Sound granular matchers
            val matchAudioCrossfade = matchesSettingsTokens(
                searchTokens,
                "crossfade", "crossfade audio duration", "blend tracks", "playback transitions", "overlap"
            )
            val matchAudioFadeIn = matchesSettingsTokens(
                searchTokens,
                "play fade-in", "fade-in", "fade in", "ramp up track volume", "smooth resume"
            )
            val matchAudioFadeOut = matchesSettingsTokens(
                searchTokens,
                "pause fade-out", "fade-out", "fade out", "abrupt audio cuts", "smooth pause"
            )
            val matchAudioPauseZero = matchesSettingsTokens(
                searchTokens,
                "pause on zero volume", "zero volume", "0%", "mute", "silent pause"
            )
            val matchAudioAutoResume = matchesSettingsTokens(
                searchTokens,
                "auto resume on volume increase", "auto resume", "volume increase", "raising volume", "unmute"
            )
            val matchAudioGapless = matchesSettingsTokens(
                searchTokens,
                "gapless", "gapless audio playback", "silent gaps", "concert", "seamless"
            )
            val matchAudioNormalize = matchesSettingsTokens(
                searchTokens,
                "normalize", "normalization", "normalize audio", "loudness", "balance volume levels", "replaygain", "gain"
            )
            val matchAudioDucking = matchesSettingsTokens(
                searchTokens,
                "duck", "ducking", "duck volume for notifications", "notification chimes", "lower volume"
            )
            val matchAudioTranscribe = matchesSettingsTokens(
                searchTokens,
                "transcribe", "translate", "gemini", "api key", "tamil", "tanglish", "hindi", "malayalam", "lyrics translation", "ai lyrics"
            )
            val matchAudioBalance = matchesSettingsTokens(
                searchTokens,
                "stereo", "channel balance", "stereo channel balance", "left", "right", "pan", "mono"
            )
            val matchAudioEqualizer = matchesSettingsTokens(
                searchTokens,
                "equalizer", "eq", "5-band", "bands", "bass", "bassboost", "virtualizer", "3d", "spatial",
                "preset", "rock", "pop", "classical", "jazz", "dance", "electronic", "acoustic", "flat", "decibel", "hz"
            )
            val anyAudioItemMatches = matchAudioCrossfade || matchAudioFadeIn || matchAudioFadeOut ||
                matchAudioGapless || matchAudioNormalize ||
                matchAudioDucking ||
                matchAudioTranscribe || matchAudioBalance || matchAudioEqualizer

            // Section 2: UI & Themes granular matchers
            val matchUiScreenLayouts = matchesSettingsTokens(
                searchTokens,
                "screen layout", "screen layout architecture", "layout architecture", "wireframe",
                "now playing layout", "home screen layout", "playlists layout", "lyrics layout", "lyrics ui",
                "bento", "vinyl", "turntable", "minimal glass", "hero spotlight", "magazine",
                "minimal stream", "studio deck", "neon studio", "compact list",
                "classic bento", "fluid glow", "studio timeline", "editorial timeline"
            )
            val matchUiDisplayMode = matchesSettingsTokens(
                searchTokens,
                "display mode", "theme", "themes", "dark", "light", "extra dark", "pitch black",
                "amoled", "oled", "pure black", "obsidian", "midnight", "system"
            )
            val matchUiDisplayScale = matchesSettingsTokens(
                searchTokens,
                "display scale", "ui size", "font size", "compact", "comfort", "standard", "zoom", "density"
            )
            val matchUiAccentColor = matchesSettingsTokens(
                searchTokens,
                "accent color", "custom accent", "color picker", "rgb", "hsv", "hex", "custom color", "tint", "palette"
            )
            val matchUiDynamicArtwork = matchesSettingsTokens(
                searchTokens,
                "dynamic artwork", "dynamic artwork theming", "artwork theme", "atmospheric", "adaptive color"
            )
            val matchUiDarkColors = matchesSettingsTokens(
                searchTokens,
                "use dark colors", "dark colors", "dark color", "palette lite", "pastel", "rich dark", "deep colors"
            )
            val matchUiGradientEffect = matchesSettingsTokens(
                searchTokens,
                "gradient", "gradient effect", "ambient gradient", "aurora", "color wash", "background gradient"
            )
            val matchUiGlassEffect = matchesSettingsTokens(
                searchTokens,
                "glass", "glass effect", "frosted", "frosted glass", "glassmorphism", "blur", "translucent", "see through"
            )
            val matchUiGradientGlass = matchUiGradientEffect || matchUiGlassEffect
            val matchUiHighResArtwork = matchesSettingsTokens(
                searchTokens,
                "online high-res", "high-res", "high res", "prefer hi-res", "online artwork", "1920px", "hd cover"
            )
            val matchUiLyricsAutoScroll = matchesSettingsTokens(
                searchTokens,
                "lyrics auto-scroll", "auto-scroll", "autoscroll", "synced lyrics", "lrc"
            )
            val matchUiVolumeBar = matchesSettingsTokens(
                searchTokens,
                "volume seek bar", "volume bar", "volume slider", "volume control", "now-playing", "now playing"
            )
            val matchUiAdvancedSongInfo = matchesSettingsTokens(
                searchTokens,
                "advanced song information", "song info", "audio format", "bitrate", "frequency", "sample rate",
                "bit depth", "128", "192", "320", "khz", "flac", "kbps"
            )
            val matchUiWaveform = matchesSettingsTokens(
                searchTokens,
                "waveform", "waveform visualizer", "visualizer style", "sleek line", "snake", "symmetric", "dotted", "bars", "wave", "wavy", "squiggly", "bar", "seekbar"
            )
            val matchUiRefreshRate = matchesSettingsTokens(
                searchTokens,
                "refresh rate", "high refresh", "high refresh rate", "90", "120hz", "60hz", "hz", "fluid", "smooth animations", "fps"
            )
            val matchUiMiniPlayer = matchesSettingsTokens(
                searchTokens,
                "mini player", "mini player customization", "floating pill", "docked", "bottom bar",
                "artwork glass", "compact slim", "swipe to skip", "swipe up", "circular artwork",
                "progress bar", "previous track button", "next track button", "favorite button"
            )
            val anyUiItemMatches = matchUiScreenLayouts || matchUiDisplayMode ||
                matchUiDisplayScale || matchUiAccentColor || matchUiDynamicArtwork || matchUiDarkColors ||
                matchUiGradientGlass || matchUiHighResArtwork || matchUiLyricsAutoScroll || matchUiVolumeBar ||
                matchUiAdvancedSongInfo || matchUiWaveform || matchUiRefreshRate || matchUiMiniPlayer

            // Section 3: Home Feed granular matcher
            val matchHomeFeedItem = matchesSettingsTokens(
                searchTokens,
                "home feed", "home screen feed", "section ordering", "reorder", "speed dial",
                "continue listening", "most played", "recently added", "recently played", "top albums",
                "top artists", "smart playlist", "recents first", "discovery"
            )

            // Section 4: Playback granular matchers
            val matchPlaybackSleepTimer = matchesSettingsTokens(
                searchTokens,
                "sleep timer", "sleep", "timer", "countdown", "stop timer", "minute", "minutes",
                "finish last song", "finish playing", "track-based", "track count"
            )
            val matchPlaybackCompletion = matchesSettingsTokens(
                searchTokens,
                "track completion", "completion criteria", "scrobble", "threshold", "percentage", "count track as played"
            )
            val matchPlaybackShake = matchesSettingsTokens(
                searchTokens,
                "shake", "shake to skip", "sensor", "physically shake", "accelerometer"
            )
            val matchPlaybackAlwaysShuffle = matchesSettingsTokens(
                searchTokens,
                "always shuffle", "shuffle mode", "shuffle order", "random"
            )
            val matchPlaybackStartupAutoplay = matchesSettingsTokens(
                searchTokens,
                "autoplay on app startup", "autoplay", "app startup", "launch play"
            )
            val matchPlaybackGestures = matchesSettingsTokens(
                searchTokens,
                "album art gestures", "gesture", "gestures", "swipe to skip", "tap to play", "album art gesture"
            )
            val matchPlaybackAddToQueuePos = matchesSettingsTokens(
                searchTokens,
                "add to queue position", "add to queue", "up-next queue", "queue position", "top", "bottom"
            )
            val matchPlaybackAddToPlaylistPos = matchesSettingsTokens(
                searchTokens,
                "add to playlist position", "add to playlist", "custom playlist arrangement"
            )
            val matchPlaybackHeadphones = matchesSettingsTokens(
                searchTokens,
                "headphone", "headphones", "headset", "earbud", "bluetooth", "wired", "wireless", "automatically play on headphone"
            )
            val matchPlaybackKeepAwake = matchesSettingsTokens(
                searchTokens,
                "keep screen awake", "screen awake", "awake", "prevent device display from sleeping", "wakelock"
            )
            val anyPlaybackItemMatches = matchPlaybackSleepTimer || matchPlaybackCompletion ||
                matchPlaybackShake || matchPlaybackAlwaysShuffle || matchPlaybackStartupAutoplay ||
                matchPlaybackGestures || matchPlaybackAddToQueuePos || matchPlaybackAddToPlaylistPos ||
                matchPlaybackHeadphones || matchPlaybackKeepAwake ||
                matchAudioPauseZero || matchAudioAutoResume

            // Section 5: Cache & Database granular matchers
            val matchCacheTotal = matchesSettingsTokens(
                searchTokens,
                "total cache", "clear all", "offline cache", "cache occupied", "cleanup", "clear cache"
            )
            val matchCacheLyrics = matchesSettingsTokens(
                searchTokens,
                "cache synced lyrics", "lyrics cache", "clear lyrics", "lrc cache"
            )
            val matchCacheAlbumArt = matchesSettingsTokens(
                searchTokens,
                "cache album artwork", "album artwork", "album art", "clear album art"
            )
            val matchCacheSongArt = matchesSettingsTokens(
                searchTokens,
                "cache song artwork", "song artwork", "song art", "clear song art"
            )
            val matchCacheDatabase = matchesSettingsTokens(
                searchTokens,
                "database", "sqlite", "room", "storage tier", "capacity tier", "wal", "optimize", "quota"
            )
            val anyCacheCardMatches = matchCacheTotal || matchCacheLyrics || matchCacheAlbumArt || matchCacheSongArt
            val anyCacheItemMatches = anyCacheCardMatches || matchCacheDatabase

            // Section 6: Library & Folders granular matchers
            val matchLibFullScan = matchesSettingsTokens(
                searchTokens,
                "full-device scan", "full device scan", "entire device", "deep scan"
            )
            val matchLibIgnoreShort = matchesSettingsTokens(
                searchTokens,
                "ignore short", "short audio", "audio duration filter", "ringtone", "voice note", "seconds"
            )
            val matchLibQuickScan = matchesSettingsTokens(
                searchTokens,
                "quick scan", "quick scan & clean", "prune", "rescan", "media scan", "clean", "discover"
            )
            val matchLibCustomFolders = matchesSettingsTokens(
                searchTokens,
                "custom music directories", "music directory", "folders", "folder", "directories", "directory", "exclude", "add folder", "remove folder"
            )
            val matchLibStoreLocal = matchesSettingsTokens(
                searchTokens,
                "update & store indexed songs in local storage too", "update and store indexed songs", "indexed songs", "local storage", "store indexed", "batch", "rename"
            )
            val anyLibraryItemMatches = matchLibFullScan || matchLibIgnoreShort || matchLibQuickScan || matchLibCustomFolders || matchLibStoreLocal

            // Section 7: Other & Privacy granular matchers
            val matchOtherSearchHistory = matchesSettingsTokens(
                searchTokens,
                "recent search", "recent searches", "search history", "clear history", "search queries"
            )
            val matchOtherDiagnostics = matchesSettingsTokens(
                searchTokens,
                "diagnostics", "crash", "crash report", "error report", "logs", "log", "system health"
            )
            val matchOtherUpdates = matchesSettingsTokens(
                searchTokens,
                "update", "updates", "github", "version", "release", "releases", "apk", "changelog", "new version", "notification"
            )
            val matchOtherResetDefaults = matchesSettingsTokens(
                searchTokens,
                "reset", "reset to default", "reset to default settings", "default settings", "factory defaults", "restore defaults"
            )
            val anyOtherItemMatches = matchOtherSearchHistory || matchOtherDiagnostics || matchOtherUpdates || matchOtherResetDefaults

            // Section 8: Cloud Sync granular matcher
            val matchCloudSyncItem = matchesSettingsTokens(
                searchTokens,
                "cloud", "sync", "synchronize", "backup", "restore", "cross-device", "cross device", "firebase", "google", "lyrics offset"
            )

            val anySpecificSettingMatched = anyAudioItemMatches || anyUiItemMatches || matchHomeFeedItem ||
                anyPlaybackItemMatches || anyCacheItemMatches || anyLibraryItemMatches ||
                anyOtherItemMatches || matchCloudSyncItem || matchAccountTile

            // Broad category fallback only when no specific setting matched (e.g., searching "audio" or "ui")
            val broadAudioMatch = !anySpecificSettingMatched && matchesSettingsTokens(searchTokens, "audio", "sound")
            val broadUiMatch = !anySpecificSettingMatched && matchesSettingsTokens(searchTokens, "ui", "visual", "appearance", "display")
            val broadHomeFeedMatch = !anySpecificSettingMatched && matchesSettingsTokens(searchTokens, "home", "feed")
            val broadPlaybackMatch = !anySpecificSettingMatched && matchesSettingsTokens(searchTokens, "playback", "controls")
            val broadCacheMatch = !anySpecificSettingMatched && matchesSettingsTokens(searchTokens, "cache", "storage", "disk")
            val broadLibraryMatch = !anySpecificSettingMatched && matchesSettingsTokens(searchTokens, "library", "scan")
            val broadOtherMatch = !anySpecificSettingMatched && matchesSettingsTokens(searchTokens, "privacy", "security", "other", "update", "github")

            val audioMatches = anyAudioItemMatches || broadAudioMatch
            val uiMatches = anyUiItemMatches || broadUiMatch
            val homeFeedMatches = matchHomeFeedItem || broadHomeFeedMatch
            val playbackMatches = anyPlaybackItemMatches || broadPlaybackMatch
            val cacheMatches = anyCacheItemMatches || broadCacheMatch
            val libraryMatches = anyLibraryItemMatches || broadLibraryMatch
            val otherMatches = anyOtherItemMatches || broadOtherMatch
            val cloudSyncMatches = matchCloudSyncItem

            val anyMatches = audioMatches || uiMatches || homeFeedMatches || playbackMatches || cacheMatches || libraryMatches || otherMatches || cloudSyncMatches || (isSearching && matchAccountTile)

            val matchedSectionsCount = if (!isSearching) 0 else listOf(
                audioMatches, uiMatches, homeFeedMatches, playbackMatches,
                cacheMatches, libraryMatches, otherMatches, cloudSyncMatches, matchAccountTile
            ).count { it }

            val isTopLevelHub = selectedSection == SettingsSection.ALL && !isSearching

            val showAudio = if (isSearching) audioMatches else (!isTopLevelHub && selectedSection == SettingsSection.AUDIO)
            val showUi = if (isSearching) uiMatches else (!isTopLevelHub && selectedSection == SettingsSection.UI)
            val showHomeFeed = if (isSearching) homeFeedMatches else (!isTopLevelHub && selectedSection == SettingsSection.HOME_FEED)
            val showPlayback = if (isSearching) playbackMatches else (!isTopLevelHub && selectedSection == SettingsSection.PLAYBACK)
            val showCache = if (isSearching) cacheMatches else (!isTopLevelHub && selectedSection == SettingsSection.CACHE)
            val showLibrary = if (isSearching) libraryMatches else (!isTopLevelHub && selectedSection == SettingsSection.LIBRARY)
            val showOther = if (isSearching) otherMatches else (!isTopLevelHub && selectedSection == SettingsSection.OTHER)
            val showCloudSync = if (isSearching) cloudSyncMatches else (!isTopLevelHub && selectedSection == SettingsSection.CLOUD_SYNC)

            if (isTopLevelHub || isSearching) {
                // Clean, borderless User Account header row
                if (!isSearching || matchAccountTile) {
                    item(key = "settings_account_tile") {
                        val avatarPhoto = customAvatarUri ?: userAccount?.photoUrl
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        if (userAccount != null) onOpenAccount() else onOpenAuth()
                                    }
                                    .padding(horizontal = 4.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .background(BentoPrimary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!avatarPhoto.isNullOrBlank()) {
                                        coil.compose.AsyncImage(
                                            model = avatarPhoto,
                                            contentDescription = "Profile Photo",
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                                        )
                                    } else if (userAccount != null) {
                                        Text(
                                            text = (effectiveDisplayName.firstOrNull() ?: 'U').uppercase(),
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoPrimary
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.AccountCircle,
                                            contentDescription = "Profile Photo",
                                            tint = BentoPrimary,
                                            modifier = Modifier.size(30.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = if (userAccount != null) effectiveDisplayName else "Account & Profile",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (userAccount != null) BentoPrimary.copy(alpha = 0.14f) else Color(0xFF00E676).copy(alpha = 0.14f)
                                        ) {
                                            Text(
                                                text = if (userAccount != null) userAccount.friendlyProviderName else "Offline Mode",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (userAccount != null) BentoPrimary else Color(0xFF00E676),
                                                maxLines = 1,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = userAccount?.email ?: "Tap to sign in or create an account",
                                        fontSize = 12.5.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = BentoTextSubtle.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            HorizontalDivider(
                                thickness = 0.6.dp,
                                color = BentoBorder.copy(alpha = 0.22f),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }

                // 1. Minimal Borderless Top Search Bar with Clean Back Icon
                item(key = "settings_hero_dashboard") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                handleBack()
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = BentoCardSurface.copy(alpha = 0.72f),
                            border = if (searchQuery.isNotBlank()) {
                                BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f))
                            } else {
                                null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = if (searchQuery.isNotBlank()) BentoPrimary else BentoTextSubtle,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                TextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = {
                                        Text(
                                            "Search equalizer, sleep timer, cache, themes...",
                                            fontSize = 13.sp,
                                            color = BentoTextSubtle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedTextColor = BentoTextPrimary,
                                        unfocusedTextColor = BentoTextPrimary,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { searchQuery = "" },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = BentoTextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isSearching && anyMatches) {
                item(key = "settings_search_results_badge") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BentoPrimary.copy(alpha = 0.08f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Found in $matchedSectionsCount ${if (matchedSectionsCount == 1) "category" else "categories"} for \"$searchQuery\"",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = { searchQuery = "" },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Clear", fontSize = 12.sp, color = BentoPrimary)
                        }
                    }
                }
            }

            if (isTopLevelHub) {
                item(key = "settings_hub_title") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "CATEGORIES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSecondary,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = "8 categories",
                            fontSize = 11.sp,
                            color = BentoTextSubtle
                        )
                    }
                }

                itemsIndexed(
                    items = SETTINGS_CATEGORY_CARD_ITEMS,
                    key = { _, item -> item.section.name },
                    contentType = { _, _ -> "settings_category_card" }
                ) { index, catItem ->
                    val sec = catItem.section
                    val title = catItem.title
                    val subtitle = catItem.subtitle
                    val icon = catItem.icon
                    val accent = androidx.compose.ui.graphics.lerp(catItem.accentColor, BentoPrimary, 0.52f)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    HapticUtils.performClickHaptic(context)
                                    navigateToSection(sec)
                                }
                                .padding(horizontal = 6.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(accent.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = subtitle,
                                    fontSize = 12.sp,
                                    color = BentoTextSecondary,
                                    lineHeight = 16.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = BentoTextSubtle.copy(alpha = 0.75f),
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        if (index < SETTINGS_CATEGORY_CARD_ITEMS.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.6.dp,
                                color = BentoBorder.copy(alpha = 0.20f),
                                modifier = Modifier.padding(start = 60.dp, end = 6.dp)
                            )
                        }
                    }
                }
            } else if (!isSearching && selectedSection != SettingsSection.ALL) {
                item(key = "settings_category_subscreen_header") {
                    val catItem = SETTINGS_CATEGORY_CARD_ITEMS.find { it.section == selectedSection }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    handleBack()
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to categories",
                                    tint = BentoTextPrimary,
                                    modifier = Modifier.size(21.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedSection.title,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (catItem != null && catItem.subtitle.isNotBlank()) {
                                    Text(
                                        text = catItem.subtitle,
                                        fontSize = 12.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        HorizontalDivider(
                            color = BentoBorder.copy(alpha = 0.22f),
                            thickness = 0.75.dp
                        )
                    }
                }
            }

            // ==========================================
            // SECTION 1: AUDIO & SOUND (Segregated into 4 clean, harmonized groups)
            // ==========================================
            if (showAudio) {
                if (isSearching) {
                    item(key = "settings_audio_header") {
                        SettingsSectionHeader(
                            title = "Audio & Sound",
                            subtitle = "Transitions, volume focus, stereo balance & equalizer",
                            kicker = "AUDIO & SOUND ENGINE",
                            icon = Icons.Default.Equalizer,
                            accentColor = BentoPrimary
                        )
                    }
                }

                // Group 1A: Playback Transitions & Fading
                val showAudioTransitions = !isSearching || broadAudioMatch || matchAudioCrossfade || matchAudioFadeIn || matchAudioFadeOut || matchAudioGapless
                if (showAudioTransitions) {
                    item(key = "settings_audio_transitions_card") {
                        SettingsHighlightWrap(isHighlighted = highlightedSettingKey == "SECTION_AUDIO") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsSubGroupLabel(title = "TRANSITIONS & FADING")
                                SettingsCardContainer(accentGlow = BentoPrimary) {
                                    if (!isSearching || matchAudioCrossfade || broadAudioMatch) {
                                        SettingsSliderRow(
                                            title = "Crossfade Audio Duration",
                                            subtitle = "Smoothly blend tracks into one another during playback transitions",
                                            value = crossfadeDurationSec.toFloat(),
                                            onValueChange = { onSetCrossfadeDurationSec(it.toInt()) },
                                            valueRange = 0f..12f,
                                            steps = 11,
                                            valueDisplay = if (crossfadeDurationSec == 0) "Off" else "${crossfadeDurationSec}s",
                                            icon = Icons.Default.Shuffle,
                                            accentColor = BentoPrimary
                                        )
                                        if (!isSearching || broadAudioMatch) SettingsDivider()
                                    }

                                    if (!isSearching || matchAudioFadeIn || broadAudioMatch) {
                                        SettingsSliderRow(
                                            title = "Play Fade-In Duration",
                                            subtitle = "Gradually ramp up track volume when starting, resuming, or entering a track",
                                            value = fadeInMs.toFloat(),
                                            onValueChange = { onSetFadeInMs(it.toInt()) },
                                            valueRange = 0f..5000f,
                                            steps = 9,
                                            valueDisplay = if (fadeInMs == 0) "Instant" else "${fadeInMs}ms",
                                            icon = Icons.Default.VolumeUp,
                                            accentColor = BentoPrimary
                                        )
                                        if (!isSearching || broadAudioMatch) SettingsDivider()
                                    }

                                    if (!isSearching || matchAudioFadeOut || broadAudioMatch) {
                                        SettingsSliderRow(
                                            title = "Pause Fade-Out Duration",
                                            subtitle = "Softly lower volume when pausing or as a track ends",
                                            value = fadeOutMs.toFloat(),
                                            onValueChange = { onSetFadeOutMs(it.toInt()) },
                                            valueRange = 0f..5000f,
                                            steps = 9,
                                            valueDisplay = if (fadeOutMs == 0) "Instant" else "${fadeOutMs}ms",
                                            icon = Icons.Default.VolumeDown,
                                            accentColor = BentoPrimary
                                        )
                                        if (!isSearching || broadAudioMatch) SettingsDivider()
                                    }

                                    if (!isSearching || matchAudioGapless || broadAudioMatch) {
                                        SettingsToggleRow(
                                            title = "Gapless Audio Playback",
                                            subtitle = "Eliminate silent gaps between live concert albums and progressive tracks",
                                            checked = gaplessPlayback,
                                            onCheckedChange = onSetGaplessPlayback,
                                            icon = Icons.Default.MusicNote,
                                            accentColor = BentoPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Group 1B: Volume & Audio Focus
                val showAudioVolume = !isSearching || broadAudioMatch || matchAudioNormalize || matchAudioDucking
                if (showAudioVolume) {
                    item(key = "settings_audio_volume_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "VOLUME & AUDIO FOCUS")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                if (!isSearching || matchAudioNormalize || broadAudioMatch) {
                                    SettingsToggleRow(
                                        title = "Normalize Audio",
                                        subtitle = "Balance volume levels across tracks to prevent sudden loudness differences",
                                        checked = normalizeAudio,
                                        onCheckedChange = onSetNormalizeAudio,
                                        icon = Icons.Default.GraphicEq,
                                        accentColor = BentoPrimary
                                    )
                                    if (!isSearching || broadAudioMatch) SettingsDivider()
                                }

                                if (!isSearching || matchAudioDucking || broadAudioMatch) {
                                    SettingsToggleRow(
                                        title = "Duck Volume for Notifications",
                                        subtitle = "Instantly lower music volume for notifications without stacking on top of built-in system ducking",
                                        checked = reduceVolumeOnNotification,
                                        onCheckedChange = onSetReduceVolumeOnNotification,
                                        icon = Icons.Default.NotificationsActive,
                                        accentColor = BentoPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Group 1C: Stereo Balance & Master Equalizer
                val showAudioEq = !isSearching || broadAudioMatch || matchAudioBalance || matchAudioEqualizer
                if (showAudioEq) {
                    item(key = "settings_audio_eq_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "STEREO & EQUALIZER")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                if (!isSearching || matchAudioBalance || broadAudioMatch) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f, fill = false),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(BentoPrimary.copy(alpha = 0.15f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Headphones, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
                                                }
                                                Column {
                                                    Text("Stereo Channel Balance", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                                    Text("Adjust relative output between left and right channels", fontSize = 11.5.sp, color = BentoTextSecondary)
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                if (kotlin.math.abs(audioBalance) > 0.05f) {
                                                    Surface(
                                                        onClick = {
                                                            HapticUtils.performClickHaptic(context)
                                                            onSetAudioBalance(0f)
                                                        },
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = BentoCardPurple,
                                                        border = BorderStroke(1.dp, BentoBorder)
                                                    ) {
                                                        Text(
                                                            text = "Reset",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = BentoTextSecondary,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                        )
                                                    }
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = BentoPrimary.copy(alpha = 0.16f),
                                                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f))
                                                ) {
                                                    Text(
                                                        text = when {
                                                            audioBalance < -0.05f -> "Left ${(-audioBalance * 100).toInt()}%"
                                                            audioBalance > 0.05f -> "Right ${(audioBalance * 100).toInt()}%"
                                                            else -> "Centered"
                                                        },
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = BentoPrimary,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = BentoCardPurple.copy(alpha = 0.55f),
                                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.35f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "L (Left)",
                                                        fontSize = 11.sp,
                                                        fontWeight = if (audioBalance < -0.05f) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (audioBalance < -0.05f) BentoPrimary else BentoTextSecondary
                                                    )
                                                    Text(
                                                        text = "• CENTER •",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 1.sp,
                                                        color = if (kotlin.math.abs(audioBalance) <= 0.05f) BentoPrimary else BentoTextSubtle
                                                    )
                                                    Text(
                                                        text = "R (Right)",
                                                        fontSize = 11.sp,
                                                        fontWeight = if (audioBalance > 0.05f) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (audioBalance > 0.05f) BentoPrimary else BentoTextSecondary
                                                    )
                                                }

                                                Slider(
                                                    value = audioBalance,
                                                    onValueChange = { raw ->
                                                        val snapped = if (kotlin.math.abs(raw) < 0.06f) 0f else raw
                                                        onSetAudioBalance(snapped)
                                                    },
                                                    valueRange = -1.0f..1.0f,
                                                    colors = SliderDefaults.colors(
                                                        thumbColor = BentoPrimary,
                                                        activeTrackColor = BentoPrimary,
                                                        inactiveTrackColor = BentoBorder.copy(alpha = 0.65f)
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                    if (!isSearching || broadAudioMatch) SettingsDivider()
                                }

                                if (!isSearching || matchAudioEqualizer || broadAudioMatch) {
                                    SettingsHighlightWrap(isHighlighted = highlightedSettingKey == "EQUALIZER") {
                                        EqualizerVisualizer(
                                            currentPreset = equalizerPreset,
                                            customBands = customEqBands,
                                            bassBoostStrength = customBassBoost,
                                            virtualizerStrength = customVirtualizer,
                                            onPresetChange = onSetEqualizerPreset,
                                            onBandsChange = onSetCustomEqBands,
                                            onBassBoostChange = onSetCustomBassBoost,
                                            onVirtualizerChange = onSetCustomVirtualizer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Group 1D: AI Lyrics Transcription & Translation
                if (!isSearching || matchAudioTranscribe || broadAudioMatch) {
                    item(key = "settings_audio_ai_lyrics_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "AI LYRICS TRANSCRIPTION & TRANSLATION")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f, fill = false),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(BentoPrimary.copy(alpha = 0.15f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Translate,
                                                    contentDescription = null,
                                                    tint = BentoPrimary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            Column {
                                                Text(
                                                    text = "Enable Transcribe/Translate Lyrics",
                                                    fontSize = 14.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = BentoTextPrimary
                                                )
                                                Text(
                                                    text = if (isTranscribeTranslateEnabled) "Transcribe vocals or translate lyrics via Gemini AI" else "Default OFF — no Gemini API requests",
                                                    fontSize = 11.5.sp,
                                                    color = BentoTextSecondary
                                                )
                                            }
                                        }

                                        Switch(
                                            checked = isTranscribeTranslateEnabled,
                                            onCheckedChange = { enable ->
                                                HapticUtils.performClickHaptic(context)
                                                if (enable) {
                                                    onSetTranscribeTranslateEnabled(true)
                                                    if (!hasUserApiKey) {
                                                        isEditingApiKey = true
                                                    }
                                                } else {
                                                    isEditingApiKey = false
                                                    onSetTranscribeTranslateEnabled(false)
                                                }
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = BentoOnPrimary,
                                                checkedTrackColor = BentoPrimary,
                                                uncheckedThumbColor = BentoTextSubtle,
                                                uncheckedTrackColor = BentoBorder
                                            )
                                        )
                                    }

                                    if (isTranscribeTranslateEnabled || isEditingApiKey) {
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = BentoCardPurple.copy(alpha = 0.55f),
                                            border = BorderStroke(1.dp, if (hasUserApiKey && !isEditingApiKey) BentoPrimary.copy(alpha = 0.35f) else BentoBorder.copy(alpha = 0.5f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(14.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                if (hasUserApiKey && !isEditingApiKey) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Default.VpnKey,
                                                                contentDescription = null,
                                                                tint = BentoPrimary,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Column {
                                                                Text(
                                                                    text = "Gemini API Key",
                                                                    fontSize = 13.sp,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    color = BentoTextPrimary
                                                                )
                                                                Text(
                                                                    text = maskedApiKey ?: "••••••••••••",
                                                                    fontSize = 11.5.sp,
                                                                    color = BentoTextSecondary
                                                                )
                                                            }
                                                        }

                                                        Surface(
                                                            shape = RoundedCornerShape(6.dp),
                                                            color = Color(0xFF00E676).copy(alpha = 0.15f)
                                                        ) {
                                                            Text(
                                                                text = "Configured",
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color(0xFF00E676),
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }

                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        OutlinedButton(
                                                            onClick = {
                                                                apiKeyInput = ""
                                                                isEditingApiKey = true
                                                                onClearKeyValidationError()
                                                            },
                                                            modifier = Modifier.weight(1f),
                                                            shape = RoundedCornerShape(10.dp),
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                            border = BorderStroke(1.dp, BentoBorder)
                                                        ) {
                                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(13.dp), tint = BentoTextPrimary)
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("Edit / Replace", fontSize = 11.sp, color = BentoTextPrimary)
                                                        }

                                                        OutlinedButton(
                                                            onClick = {
                                                                onDeleteUserApiKey()
                                                                isEditingApiKey = false
                                                                apiKeyInput = ""
                                                            },
                                                            modifier = Modifier.weight(1f),
                                                            shape = RoundedCornerShape(10.dp),
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                            border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f))
                                                        ) {
                                                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color(0xFFFF5252))
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("Delete Key", fontSize = 11.sp, color = Color(0xFFFF5252))
                                                        }
                                                    }
                                                } else {
                                                    Text(
                                                        text = if (isEditingApiKey && hasUserApiKey) "Replace Gemini API Key" else "Configure Gemini API Key",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = BentoTextPrimary
                                                    )
                                                    Text(
                                                        text = "Enter your Google Gemini API key to enable lyrics transcription and translation. Your key is stored securely on device (AES-256 GCM) and is never synchronized to Firestore or cloud backup.",
                                                        fontSize = 11.sp,
                                                        color = BentoTextSecondary
                                                    )

                                                    OutlinedTextField(
                                                        value = apiKeyInput,
                                                        onValueChange = {
                                                            apiKeyInput = it
                                                            if (keyValidationErrorMessage != null) {
                                                                onClearKeyValidationError()
                                                            }
                                                        },
                                                        label = { Text("Gemini API Key", fontSize = 12.sp) },
                                                        placeholder = { Text("AIzaSy...", fontSize = 12.sp, color = BentoTextSubtle) },
                                                        singleLine = true,
                                                        visualTransformation = if (showKeyText) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                                        trailingIcon = {
                                                            IconButton(onClick = { showKeyText = !showKeyText }, modifier = Modifier.size(28.dp)) {
                                                                Icon(
                                                                    imageVector = if (showKeyText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                                    contentDescription = if (showKeyText) "Hide key" else "Show key",
                                                                    tint = BentoTextSecondary,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                            }
                                                        },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        isError = keyValidationErrorMessage != null,
                                                        colors = OutlinedTextFieldDefaults.colors(
                                                            focusedBorderColor = BentoPrimary,
                                                            unfocusedBorderColor = BentoBorder,
                                                            focusedLabelColor = BentoPrimary,
                                                            errorBorderColor = Color(0xFFFF5252)
                                                        )
                                                    )

                                                    if (keyValidationErrorMessage != null) {
                                                        Surface(
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = Color(0xFFFF5252).copy(alpha = 0.15f),
                                                            border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                                                            modifier = Modifier.fillMaxWidth()
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(8.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(14.dp))
                                                                Text(
                                                                    text = keyValidationErrorMessage ?: "",
                                                                    fontSize = 11.sp,
                                                                    color = BentoTextPrimary,
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                                IconButton(onClick = onClearKeyValidationError, modifier = Modifier.size(20.dp)) {
                                                                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = BentoTextSecondary, modifier = Modifier.size(12.dp))
                                                                }
                                                            }
                                                        }
                                                    }

                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        if (isEditingApiKey && hasUserApiKey) {
                                                            OutlinedButton(
                                                                onClick = {
                                                                    isEditingApiKey = false
                                                                    apiKeyInput = ""
                                                                    onClearKeyValidationError()
                                                                },
                                                                modifier = Modifier.weight(1f),
                                                                shape = RoundedCornerShape(10.dp),
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                                            ) {
                                                                Text("Cancel", fontSize = 11.5.sp, color = BentoTextSecondary)
                                                            }
                                                        }

                                                        Button(
                                                            onClick = {
                                                                onSaveUserApiKey(apiKeyInput.trim()) { success, _ ->
                                                                    if (success) {
                                                                        isEditingApiKey = false
                                                                        apiKeyInput = ""
                                                                    }
                                                                }
                                                            },
                                                            enabled = apiKeyInput.isNotBlank() && !isKeyValidating,
                                                            modifier = Modifier.weight(1.2f),
                                                            shape = RoundedCornerShape(10.dp),
                                                            colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                        ) {
                                                            if (isKeyValidating) {
                                                                CircularProgressIndicator(
                                                                    color = BentoOnPrimary,
                                                                    strokeWidth = 2.dp,
                                                                    modifier = Modifier.size(14.dp)
                                                                )
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Text("Validating...", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                                            } else {
                                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Text("Save & Validate Key", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Default Transcribe / Translate Language",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoTextSecondary
                                        )
                                        val selectedTranscribeLang = TranscribeLanguage.fromCode(audioTranscribeTargetLang)
                                        TranscribeLanguage.entries.chunked(3).forEach { rowLangs ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                rowLangs.forEach { lang ->
                                                    val isSelected = selectedTranscribeLang == lang
                                                    Surface(
                                                        onClick = {
                                                            HapticUtils.performClickHaptic(context)
                                                            onSetAudioTranscribeTargetLang(lang.code)
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = if (isSelected) BentoPrimary.copy(alpha = 0.2f) else BentoCardPurple,
                                                        border = BorderStroke(
                                                            1.dp,
                                                            if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.4f)
                                                        )
                                                    ) {
                                                        Column(
                                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                                            horizontalAlignment = Alignment.CenterHorizontally
                                                        ) {
                                                            Text(
                                                                text = lang.englishName,
                                                                fontSize = 11.5.sp,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                                color = if (isSelected) BentoPrimary else BentoTextPrimary
                                                            )
                                                            Text(
                                                                text = lang.nativeName,
                                                                fontSize = 10.sp,
                                                                color = if (isSelected) BentoPrimary.copy(alpha = 0.85f) else BentoTextSecondary
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
                    }
                }
            }

            // ==========================================
            // SECTION 2: UI & THEME (Segregated into 5 clean, scannable groups)
            // ==========================================
            if (showUi) {
                if (isSearching) {
                    item(key = "settings_ui_header") {
                        SettingsSectionHeader(
                            title = "UI & Theme",
                            subtitle = "Live layout studio, colors, waveform styles & display scale",
                            kicker = "VISUAL & DISPLAY STUDIO",
                            icon = Icons.Default.Palette,
                            accentColor = BentoPrimary
                        )
                    }
                }

                // Group 2A: Screen Layout Studio Entry Card (forwards to dedicated swipeable UiLayoutStudioScreen)
                if (!isSearching || matchUiScreenLayouts || broadUiMatch) {
                    item(key = "settings_ui_layout_studio_entry") {
                        SettingsHighlightWrap(isHighlighted = highlightedSettingKey == "SECTION_UI") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsSubGroupLabel(title = "SCREEN LAYOUT ARCHITECTURE")
                                SettingsCardContainer(accentGlow = BentoPrimary) {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                                Text(
                                                    text = "Live Screen Layout Studio",
                                                    fontSize = 15.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BentoTextPrimary
                                                )
                                                Text(
                                                    text = "Swipe realistic live previews with your active theme, gradient & home feed",
                                                    fontSize = 11.5.sp,
                                                    color = BentoTextSecondary
                                                )
                                            }
                                            Button(
                                                onClick = {
                                                    HapticUtils.performClickHaptic(context)
                                                    layoutStudioInitialTab = "NOW_PLAYING"
                                                    showLayoutStudio = true
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = BentoPrimary,
                                                    contentColor = BentoOnPrimary
                                                ),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                                modifier = Modifier.testTag("open_layout_studio_button")
                                            ) {
                                                Text("Preview", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                        }

                                        // 2x2 Quick-Jump Tiles for Now Playing, Home Screen, Playlists, Lyrics UI
                                        val layoutTargets = listOf(
                                            Triple("NOW_PLAYING", "Now Playing", NowPlayingLayout.fromId(nowPlayingLayout).displayName),
                                            Triple("HOME", "Home Screen", HomeScreenLayout.fromId(homeScreenLayout).displayName),
                                            Triple("PLAYLISTS", "Playlists", PlaylistsLayout.fromId(playlistsLayout).displayName),
                                            Triple("LYRICS", "Lyrics UI", LyricsLayout.fromId(lyricsLayout).displayName)
                                        )
                                        layoutTargets.chunked(2).forEach { pair ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                pair.forEach { (tabId, screenLabel, activeStyleName) ->
                                                    Surface(
                                                        onClick = {
                                                            HapticUtils.performClickHaptic(context)
                                                            layoutStudioInitialTab = tabId
                                                            showLayoutStudio = true
                                                        },
                                                        shape = RoundedCornerShape(14.dp),
                                                        color = BentoCardPurple.copy(alpha = 0.65f),
                                                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .testTag("layout_entry_${tabId.lowercase()}")
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = screenLabel,
                                                                    fontSize = 12.5.sp,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    color = BentoTextPrimary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                Text(
                                                                    text = activeStyleName,
                                                                    fontSize = 11.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = BentoPrimary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                            Icon(
                                                                imageVector = Icons.Default.ChevronRight,
                                                                contentDescription = null,
                                                                tint = BentoTextSubtle,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // Direct Combo Suite Preview shortcut
                                        Surface(
                                            onClick = {
                                                HapticUtils.performClickHaptic(context)
                                                layoutStudioInitialTab = "COMBO"
                                                showLayoutStudio = true
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            color = BentoPrimary.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("layout_entry_combo")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.AutoAwesomeMosaic,
                                                    contentDescription = null,
                                                    tint = BentoPrimary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "View Combined Multi-Screen Live Preview",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = BentoPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Group 2B: Theme, Colors & Effects
                val showUiColorsGroup = !isSearching || broadUiMatch || matchUiDisplayMode || matchUiAccentColor || matchUiDynamicArtwork || matchUiDarkColors || matchUiGradientGlass
                if (showUiColorsGroup) {
                    item(key = "settings_ui_theme_colors_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "THEME, COLORS & EFFECTS")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                if (!isSearching || matchUiDisplayMode || broadUiMatch) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text("Display Mode", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                                Text(
                                                    text = when (themeMode.uppercase()) {
                                                        "AMOLED" -> "Pure extra-dark pitch black optimized for OLED displays"
                                                        "LIGHT" -> "Clean high-contrast daylight slate surfaces"
                                                        "SYSTEM" -> "Automatically follows your device appearance"
                                                        else -> "Midnight Obsidian lite-dark slate surfaces"
                                                    },
                                                    fontSize = 11.5.sp,
                                                    color = BentoTextSecondary
                                                )
                                            }
                                        }
                                        SettingsSegmentedSelector(
                                            options = listOf(
                                                "DARK" to "Dark",
                                                "LIGHT" to "Light",
                                                "AMOLED" to "AMOLED",
                                                "SYSTEM" to "System"
                                            ),
                                            selectedOption = themeMode.uppercase(),
                                            onSelectOption = { newMode ->
                                                HapticUtils.performClickHaptic(context)
                                                onSetThemeMode(newMode)
                                            },
                                            accentColor = BentoPrimary
                                        )
                                    }
                                    if (!isSearching || broadUiMatch) SettingsDivider()
                                }

                                if (!isSearching || matchUiAccentColor || broadUiMatch) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text("Accent Color Palette", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                                Text("Choose a preset swatch or open the color wheel & hex picker", fontSize = 11.5.sp, color = BentoTextSecondary)
                                            }
                                            Surface(
                                                onClick = {
                                                    HapticUtils.performClickHaptic(context)
                                                    showColorPickerDialog = true
                                                },
                                                shape = CircleShape,
                                                color = if (customAccentColor != 0) BentoPrimary.copy(alpha = 0.18f) else BentoCardPurple,
                                                border = BorderStroke(1.dp, if (customAccentColor != 0) BentoPrimary else BentoBorder.copy(alpha = 0.7f)),
                                                modifier = Modifier.testTag("settings_open_rgb_picker_button")
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    Icon(
                                                        Icons.Default.Palette,
                                                        contentDescription = "Custom Color Wheel",
                                                        tint = if (customAccentColor != 0) Color(customAccentColor) else BentoPrimary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "Color Picker",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = BentoTextPrimary
                                                    )
                                                }
                                            }
                                        }

                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            contentPadding = PaddingValues(vertical = 4.dp)
                                        ) {
                                            items(PRESET_ACCENT_COLORS, key = { it.first }, contentType = { "accent_circle" }) { (name, color) ->
                                                val isSelected = accentColorPreset.equals(name, ignoreCase = true) && customAccentColor == 0 && !isDynamicArtworkTheme
                                                Box(
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .clip(CircleShape)
                                                        .background(color)
                                                        .border(
                                                            width = if (isSelected) 3.dp else 1.dp,
                                                            color = if (isSelected) Color.White else BentoBorder,
                                                            shape = CircleShape
                                                        )
                                                        .clickable {
                                                            HapticUtils.performClickHaptic(context)
                                                            onSetAccentColorPreset(name)
                                                            onSetCustomAccentColor(0)
                                                            onSetDynamicArtworkTheme(false)
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = "Selected",
                                                            tint = if (color.luminance() > 0.45f) Color.Black else Color.White,
                                                            modifier = Modifier.size(19.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (!isSearching || broadUiMatch) SettingsDivider()
                                }

                                if (!isSearching || matchUiDynamicArtwork || broadUiMatch) {
                                    SettingsToggleRow(
                                        title = "Dynamic Artwork Theming",
                                        subtitle = "Adapt UI accents and background atmospheric glows to current song cover",
                                        checked = isDynamicArtworkTheme,
                                        onCheckedChange = onSetDynamicArtworkTheme,
                                        icon = Icons.Default.AutoAwesome,
                                        accentColor = BentoPrimary
                                    )
                                    if (!isSearching || broadUiMatch) SettingsDivider()
                                }

                                if (!isSearching || matchUiDarkColors || broadUiMatch) {
                                    SettingsToggleRow(
                                        title = "Use dark colors",
                                        subtitle = "Turn off to keep using palette lite colors",
                                        checked = useDarkColors,
                                        onCheckedChange = onSetUseDarkColors,
                                        icon = Icons.Default.DarkMode,
                                        accentColor = BentoPrimary,
                                        modifier = Modifier.testTag("settings_use_dark_colors_toggle")
                                    )
                                    if (!isSearching || broadUiMatch) SettingsDivider()
                                }

                                if (!isSearching || matchUiGradientEffect || matchUiGradientGlass || broadUiMatch) {
                                    SettingsToggleRow(
                                        title = "Gradient Effect",
                                        subtitle = "Apply dynamic artwork and theme ambient color gradients across Home, Now Playing, Library, Lyrics, Playlists, Search, Stats, and Queue",
                                        checked = isGradientEffect,
                                        onCheckedChange = onSetGradientEffect,
                                        icon = Icons.Default.Gradient,
                                        accentColor = BentoPrimary
                                    )
                                    if (!isSearching || broadUiMatch) SettingsDivider()
                                }

                                if (!isSearching || matchUiGlassEffect || matchUiGradientGlass || broadUiMatch) {
                                    SettingsToggleRow(
                                        title = "Glass Effect",
                                        subtitle = "Apply translucent see-through frosted glass surfaces and specular borders to cards, menus, bottom sheets, and Up Next queue",
                                        checked = isGlassEffect,
                                        onCheckedChange = onSetGlassEffect,
                                        icon = Icons.Default.BlurOn,
                                        accentColor = BentoPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Group 2C: Now Playing & Waveform
                val showUiNowPlayingGroup = !isSearching || broadUiMatch || matchUiWaveform || matchUiVolumeBar || matchUiAdvancedSongInfo || matchUiLyricsAutoScroll || matchUiHighResArtwork
                if (showUiNowPlayingGroup) {
                    item(key = "settings_ui_now_playing_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "NOW PLAYING & WAVEFORM")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                // Visual Waveform Style Picker Trigger Row (opens 2-column preview dialog with all 7 styles)
                                if (!isSearching || matchUiWaveform || broadUiMatch) {
                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            showWaveformPickerDialog = true
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        color = BentoCardPurple.copy(alpha = 0.55f),
                                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.32f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("settings_waveform_picker_trigger")
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
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Waveform Visualizer Style",
                                                        fontSize = 14.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = BentoTextPrimary
                                                    )
                                                    Text(
                                                        text = "${com.example.ui.components.waveformStyleDisplayName(waveformStyle)} • Tap to compare all 7 styles",
                                                        fontSize = 11.5.sp,
                                                        color = BentoTextSecondary
                                                    )
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(999.dp),
                                                    color = BentoPrimary.copy(alpha = 0.18f)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = com.example.ui.components.waveformStyleDisplayName(waveformStyle),
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = BentoPrimary
                                                        )
                                                        Spacer(modifier = Modifier.width(2.dp))
                                                        Icon(
                                                            imageVector = Icons.Default.ChevronRight,
                                                            contentDescription = null,
                                                            tint = BentoPrimary,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            com.example.ui.components.WaveformMiniPreviewCanvas(
                                                styleId = waveformStyle,
                                                accentColor = BentoPrimary,
                                                trackColor = BentoTextPrimary.copy(alpha = 0.22f),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(26.dp)
                                            )
                                        }
                                    }
                                    if (!isSearching || broadUiMatch) SettingsDivider()
                                }

                                if (!isSearching || matchUiVolumeBar || broadUiMatch) {
                                    SettingsToggleRow(
                                        title = "Include Volume Seek bar in Now-Playing screen",
                                        subtitle = "Display a sleek media volume slider across all 4 Now Playing screen styles",
                                        checked = showNowPlayingVolumeBar,
                                        onCheckedChange = onSetShowNowPlayingVolumeBar,
                                        icon = Icons.Default.VolumeUp,
                                        accentColor = BentoPrimary
                                    )
                                    if (!isSearching || broadUiMatch) SettingsDivider()
                                }

                                if (!isSearching || matchUiAdvancedSongInfo || broadUiMatch) {
                                    SettingsToggleRow(
                                        title = "Display advanced song information in the Now-Playing screen",
                                        subtitle = "Show audio codec, bit depth, sample frequency, and bitrate in small typography",
                                        checked = showAdvancedSongInfo,
                                        onCheckedChange = onSetShowAdvancedSongInfo,
                                        icon = Icons.Default.GraphicEq,
                                        accentColor = BentoPrimary
                                    )
                                    if (!isSearching || broadUiMatch) SettingsDivider()
                                }

                                if (!isSearching || matchUiLyricsAutoScroll || broadUiMatch) {
                                    SettingsToggleRow(
                                        title = "Auto-Scroll Synced Lyrics",
                                        subtitle = "Keep the active singing lyric timestamp smoothly centered",
                                        checked = lyricsAutoScroll,
                                        onCheckedChange = onSetLyricsAutoScroll,
                                        icon = Icons.Default.FormatQuote,
                                        accentColor = BentoPrimary
                                    )
                                    if (!isSearching || broadUiMatch) SettingsDivider()
                                }

                                if (!isSearching || matchUiHighResArtwork || broadUiMatch) {
                                    SettingsToggleRow(
                                        title = "Prefer High-Res Online Artwork",
                                        subtitle = if (fetchOnlineArtworkHighRes) "High-res covers up to 1920px are fetched online & cached locally" else "Local embedded audio covers are prioritized for fast offline access",
                                        checked = fetchOnlineArtworkHighRes,
                                        onCheckedChange = onSetFetchOnlineArtworkHighRes,
                                        icon = Icons.Default.Image,
                                        accentColor = BentoPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Group 2D: Display & Motion
                val showUiDisplayGroup = !isSearching || broadUiMatch || matchUiDisplayScale || matchUiRefreshRate
                if (showUiDisplayGroup) {
                    item(key = "settings_ui_display_motion_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "DISPLAY & MOTION")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                if (!isSearching || matchUiDisplayScale || broadUiMatch) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Display Scale", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                            Text(
                                                text = when (displayScale.uppercase()) {
                                                    "COMPACT" -> "Compact (0.86x)"
                                                    "COMFORT" -> "Comfort (1.08x)"
                                                    else -> "Standard (1.0x)"
                                                },
                                                fontSize = 12.sp,
                                                color = BentoTextSecondary
                                            )
                                        }
                                        Text(
                                            "Controls overall UI component, font, and button sizing across all screens",
                                            fontSize = 11.5.sp,
                                            color = BentoTextSecondary
                                        )
                                        SettingsSegmentedSelector(
                                            options = listOf(
                                                "COMPACT" to "Compact",
                                                "DEFAULT" to "Standard",
                                                "COMFORT" to "Comfort"
                                            ),
                                            selectedOption = displayScale.uppercase(),
                                            onSelectOption = { newScale ->
                                                HapticUtils.performClickHaptic(context)
                                                onSetDisplayScale(newScale)
                                            },
                                            accentColor = BentoPrimary
                                        )
                                    }
                                    if (!isSearching || broadUiMatch) SettingsDivider()
                                }

                                if (!isSearching || matchUiRefreshRate || broadUiMatch) {
                                    SettingsHighlightWrap(isHighlighted = highlightedSettingKey == "REFRESH_RATE") {
                                        SettingsToggleRow(
                                            title = "High Refresh Rate (90/120Hz)",
                                            subtitle = "Enable ultra-smooth 120Hz display transitions & physics animations",
                                            checked = highRefreshRate,
                                            onCheckedChange = onSetHighRefreshRate,
                                            icon = Icons.Default.Speed,
                                            accentColor = BentoPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Group 2E: Mini Player Customization
                if (!isSearching || matchUiMiniPlayer || broadUiMatch) {
                    item(key = "settings_ui_mini_player_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "MINI PLAYER")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("Mini Player Style", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                        Text(
                                            text = when (miniPlayerStyle.uppercase()) {
                                                "BOTTOM_BAR" -> "Full-width docked bar anchored cleanly above navigation"
                                                "ARTWORK_GLASS" -> "Frosted glass wash with accent glow and swipe-up handle"
                                                "COMPACT_MINIMAL" -> "Ultra-slim floating pill for distraction-free browsing"
                                                else -> "Rounded floating pill card with balanced controls"
                                            },
                                            fontSize = 11.5.sp,
                                            color = BentoTextSecondary
                                        )
                                    }

                                    // 2x2 Style Selector Pills
                                    val miniStyles = listOf(
                                        Triple("FLOATING", "Floating Pill", Icons.Default.RoundedCorner),
                                        Triple("BOTTOM_BAR", "Bottom Bar", Icons.Default.Dock),
                                        Triple("ARTWORK_GLASS", "Artwork Glass", Icons.Default.BlurOn),
                                        Triple("COMPACT_MINIMAL", "Compact Slim", Icons.Default.ViewStream)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        miniStyles.chunked(2).forEach { pair ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                pair.forEach { (styleId, label, icon) ->
                                                    val selected = miniPlayerStyle.equals(styleId, ignoreCase = true)
                                                    Surface(
                                                        onClick = {
                                                            HapticUtils.performClickHaptic(context)
                                                            onSetMiniPlayerStyle(styleId)
                                                        },
                                                        shape = RoundedCornerShape(14.dp),
                                                        color = if (selected) BentoPrimary.copy(alpha = 0.18f) else BentoCardPurple.copy(alpha = 0.55f),
                                                        border = BorderStroke(
                                                            width = if (selected) 1.5.dp else 1.dp,
                                                            color = if (selected) BentoPrimary else BentoBorder.copy(alpha = 0.45f)
                                                        ),
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .testTag("settings_mini_player_style_${styleId.lowercase()}")
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = icon,
                                                                contentDescription = null,
                                                                tint = if (selected) BentoPrimary else BentoTextSecondary,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Text(
                                                                text = label,
                                                                fontSize = 12.sp,
                                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                                color = if (selected) BentoPrimary else BentoTextPrimary,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    SettingsDivider()

                                    Text("Behavior & Gestures", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BentoTextSecondary)
                                    SettingsToggleRow(
                                        title = "Swipe Left / Right to Skip",
                                        subtitle = "Drag horizontally on the mini player to jump to next or previous track",
                                        checked = miniPlayerSwipeToSkip,
                                        onCheckedChange = onSetMiniPlayerSwipeToSkip,
                                        icon = Icons.Default.Swipe,
                                        accentColor = BentoPrimary,
                                        modifier = Modifier.testTag("settings_mini_player_swipe_skip")
                                    )
                                    SettingsToggleRow(
                                        title = "Swipe Up to Open Now Playing",
                                        subtitle = "Pull upward on the mini player to expand the full player screen",
                                        checked = miniPlayerSwipeUpToOpen,
                                        onCheckedChange = onSetMiniPlayerSwipeUpToOpen,
                                        icon = Icons.Default.KeyboardArrowUp,
                                        accentColor = BentoPrimary,
                                        modifier = Modifier.testTag("settings_mini_player_swipe_up")
                                    )
                                    SettingsToggleRow(
                                        title = "Swipe Down to Dismiss & Stop Music",
                                        subtitle = "Pull downward on the mini player to clear the queue and stop playback",
                                        checked = miniPlayerSwipeDownToDismiss,
                                        onCheckedChange = onSetMiniPlayerSwipeDownToDismiss,
                                        icon = Icons.Default.KeyboardArrowDown,
                                        accentColor = BentoPrimary,
                                        modifier = Modifier.testTag("settings_mini_player_swipe_down_dismiss")
                                    )
                                    SettingsToggleRow(
                                        title = "Show Live Progress Bar",
                                        subtitle = "Display a real-time playback progress line along the mini player edge",
                                        checked = miniPlayerShowProgress,
                                        onCheckedChange = onSetMiniPlayerShowProgress,
                                        icon = Icons.Default.LinearScale,
                                        accentColor = BentoPrimary,
                                        modifier = Modifier.testTag("settings_mini_player_progress")
                                    )
                                    SettingsToggleRow(
                                        title = "Circular Album Artwork",
                                        subtitle = "Render the mini player cover art as a vinyl-inspired circular disc",
                                        checked = miniPlayerCircularArt,
                                        onCheckedChange = onSetMiniPlayerCircularArt,
                                        icon = Icons.Default.Album,
                                        accentColor = BentoPrimary,
                                        modifier = Modifier.testTag("settings_mini_player_circular_art")
                                    )

                                    SettingsDivider()

                                    Text("Visible Action Buttons", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BentoTextSecondary)
                                    SettingsToggleRow(
                                        title = "Show Previous Track Button",
                                        subtitle = "Quick previous button on the persistent mini player",
                                        checked = miniPlayerShowPrevious,
                                        onCheckedChange = onSetMiniPlayerShowPrevious,
                                        icon = Icons.Default.SkipPrevious,
                                        accentColor = BentoPrimary
                                    )
                                    SettingsToggleRow(
                                        title = "Show Next Track Button",
                                        subtitle = "Quick next track button on the persistent mini player",
                                        checked = miniPlayerShowNext,
                                        onCheckedChange = onSetMiniPlayerShowNext,
                                        icon = Icons.Default.SkipNext,
                                        accentColor = BentoPrimary
                                    )
                                    SettingsToggleRow(
                                        title = "Show Favorite Button",
                                        subtitle = "Instant heart/favorite action on the persistent mini player",
                                        checked = miniPlayerShowFavorite,
                                        onCheckedChange = onSetMiniPlayerShowFavorite,
                                        icon = Icons.Default.Favorite,
                                        accentColor = BentoPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 3: HOME FEED & CATEGORIES
            // ==========================================
            if (showHomeFeed) {
                if (isSearching) {
                    item(key = "settings_home_feed_header") {
                        SettingsSectionHeader(
                            title = "Home Feed",
                            subtitle = "Customize section ordering, toggle categories & custom feed presets",
                            kicker = "HOME FEED & DISCOVERY",
                            icon = Icons.Default.DashboardCustomize,
                            accentColor = BentoPrimary
                        )
                    }
                }

                item(key = "settings_home_feed_card") {
                    SettingsHighlightWrap(isHighlighted = highlightedSettingKey == "SECTION_HOME_FEED") {
                        HomeScreenSectionsManagerCard(
                            currentSections = homeSections,
                            onUpdateSections = onSetHomeSections,
                            customPresets = customHomeFeedPresets,
                            onSaveCustomPreset = onSaveCustomHomeFeedPreset,
                            onDeleteCustomPreset = onDeleteCustomHomeFeedPreset
                        )
                    }
                }
            }

            // ==========================================
            // SECTION 4: PLAYBACK CONTROLS & TIMERS
            // ==========================================
            if (showPlayback) {
                if (isSearching) {
                    item(key = "settings_playback_header") {
                        SettingsSectionHeader(
                            title = "Playback & Timers",
                            subtitle = "Sleep timer, playback automation, gestures & queue behavior",
                            kicker = "PLAYBACK CONTROLS & TIMERS",
                            icon = Icons.Default.PlayCircle,
                            accentColor = BentoPrimary
                        )
                    }
                }

                // Group 4A: Sleep Timer
                if (!isSearching || matchPlaybackSleepTimer || broadPlaybackMatch) {
                    item(key = "settings_playback_sleep_timer_card") {
                        SettingsHighlightWrap(isHighlighted = highlightedSettingKey == "SECTION_PLAYBACK" || highlightedSettingKey == "SLEEP_TIMER") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsSubGroupLabel(title = "SLEEP TIMER")
                                SettingsCardContainer(accentGlow = BentoPrimary) {
                                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = if (sleepTimerState.isActive) BentoPrimary.copy(alpha = 0.12f) else BentoCardPurple.copy(alpha = 0.55f),
                                            border = BorderStroke(1.dp, if (sleepTimerState.isActive) BentoPrimary.copy(alpha = 0.4f) else BentoBorder.copy(alpha = 0.45f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(14.dp),
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
                                                            .size(40.dp)
                                                            .clip(CircleShape)
                                                            .background(if (sleepTimerState.isActive) BentoPrimary.copy(alpha = 0.2f) else BentoCardSurface),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = if (sleepTimerState.isActive) Icons.Default.HourglassTop else Icons.Default.Bedtime,
                                                            contentDescription = null,
                                                            tint = if (sleepTimerState.isActive) BentoPrimary else BentoTextSecondary,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                    Column {
                                                        Text("Sleep Timer Status", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                                        Text(
                                                            text = if (sleepTimerState.isActive) {
                                                                if (sleepTimerState.mode == "SONGS") {
                                                                    if (sleepTimerState.isFinalSleepTrack) {
                                                                        "Active: Final track (${sleepTimerState.elapsedSongs}/${sleepTimerState.totalSongs}) - stopping after track"
                                                                    } else if (sleepTimerState.elapsedSongs > 0) {
                                                                        "Active: Track ${sleepTimerState.elapsedSongs} of ${sleepTimerState.totalSongs} (${sleepTimerState.remainingSongs} remaining)"
                                                                    } else {
                                                                        "Active: ${sleepTimerState.remainingSongs} of ${sleepTimerState.totalSongs} track(s) remaining"
                                                                    }
                                                                } else {
                                                                    if (sleepTimerState.isWaitingForCurrentTrackToEnd || sleepTimerState.remainingSeconds <= 0L) {
                                                                        "Active: Finishing current track before stopping"
                                                                    } else {
                                                                        val mins = sleepTimerState.remainingSeconds / 60
                                                                        val secs = sleepTimerState.remainingSeconds % 60
                                                                        "Active: ${mins}m ${secs}s remaining${if (sleepTimerState.finishLastSong) " (finish track)" else ""}"
                                                                    }
                                                                }
                                                            } else "Timer inactive • Select duration or track count below",
                                                            fontSize = 11.5.sp,
                                                            color = if (sleepTimerState.isActive) BentoPrimary else BentoTextSecondary
                                                        )
                                                    }
                                                }

                                                if (sleepTimerState.isActive) {
                                                    IconButton(
                                                        onClick = {
                                                            HapticUtils.performClickHaptic(context)
                                                            onCancelSleepTimer()
                                                        }
                                                    ) {
                                                        Icon(Icons.Default.StopCircle, contentDescription = "Cancel Timer", tint = BentoError, modifier = Modifier.size(28.dp))
                                                    }
                                                }
                                            }
                                        }

                                        // Active timer extension buttons (TIME mode — bounded by 180 mins pending)
                                        if (sleepTimerState.isActive && sleepTimerState.mode == "TIME") {
                                            val atMaxPendingTime = sleepTimerState.remainingSeconds >= com.example.service.SleepTimerManager.MAX_TIME_SECONDS
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text("Extend Time", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BentoTextSubtle)
                                                    Text(
                                                        text = if (atMaxPendingTime) "Max 3h pending reached" else "Max 3h (180m) pending",
                                                        fontSize = 10.5.sp,
                                                        color = if (atMaxPendingTime) BentoAccentPinkText else BentoTextSecondary
                                                    )
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    listOf(5 to "+5m", 10 to "+10m", 15 to "+15m", 30 to "+30m").forEach { (extraMins, label) ->
                                                        val canAdd = com.example.service.SleepTimerManager.canAddMinutes(sleepTimerState, extraMins)
                                                        Surface(
                                                            onClick = {
                                                                if (canAdd) {
                                                                    HapticUtils.performClickHaptic(context)
                                                                    onAddSleepTimerMinutes(extraMins)
                                                                }
                                                            },
                                                            enabled = canAdd,
                                                            shape = CircleShape,
                                                            color = if (canAdd) BentoPrimary.copy(alpha = 0.16f) else BentoBorder.copy(alpha = 0.18f),
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .height(34.dp)
                                                        ) {
                                                            Box(
                                                                modifier = Modifier.fillMaxSize(),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Text(
                                                                    text = label,
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = if (canAdd) BentoPrimary else BentoTextSecondary.copy(alpha = 0.45f)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // Active timer extension buttons (SONGS mode — bounded by 50 tracks pending)
                                        if (sleepTimerState.isActive && sleepTimerState.mode == "SONGS") {
                                            val atMaxPendingSongs = sleepTimerState.remainingSongs >= com.example.service.SleepTimerManager.MAX_TRACK_COUNT
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text("Extend Tracks", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BentoTextSubtle)
                                                    Text(
                                                        text = if (atMaxPendingSongs) "Max 50 pending reached" else "Max 50 tracks pending",
                                                        fontSize = 10.5.sp,
                                                        color = if (atMaxPendingSongs) BentoAccentPinkText else BentoTextSecondary
                                                    )
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    listOf(1 to "+1 track", 2 to "+2 tracks", 5 to "+5 tracks").forEach { (extraSongs, label) ->
                                                        val canAdd = com.example.service.SleepTimerManager.canAddSongs(sleepTimerState, extraSongs)
                                                        Surface(
                                                            onClick = {
                                                                if (canAdd) {
                                                                    HapticUtils.performClickHaptic(context)
                                                                    onAddSleepTimerSongs(extraSongs)
                                                                }
                                                            },
                                                            enabled = canAdd,
                                                            shape = CircleShape,
                                                            color = if (canAdd) BentoPrimary.copy(alpha = 0.16f) else BentoBorder.copy(alpha = 0.18f),
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .height(34.dp)
                                                        ) {
                                                            Box(
                                                                modifier = Modifier.fillMaxSize(),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Text(
                                                                    text = label,
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = if (canAdd) BentoPrimary else BentoTextSecondary.copy(alpha = 0.45f)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // Finish Last Track Checkbox
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    val updated = !finishLastSongSettings
                                                    finishLastSongSettings = updated
                                                    onSetSleepTimerFinishLastSong(updated)
                                                }
                                                .padding(vertical = 2.dp)
                                        ) {
                                            Checkbox(
                                                checked = finishLastSongSettings,
                                                onCheckedChange = {
                                                    finishLastSongSettings = it
                                                    onSetSleepTimerFinishLastSong(it)
                                                },
                                                colors = CheckboxDefaults.colors(checkedColor = BentoPrimary, checkmarkColor = BentoOnPrimary)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Finish playing current track completely before stopping",
                                                fontSize = 12.5.sp,
                                                color = BentoTextPrimary
                                            )
                                        }

                                        // Time-Based Quick Chips
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("Time-Based Duration", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BentoTextSubtle)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf(15 to "15m", 30 to "30m", 45 to "45m", 60 to "60m").forEach { (mins, label) ->
                                                    val isThisActive = sleepTimerState.isActive && sleepTimerState.mode == "TIME" && sleepTimerState.initialMinutes == mins
                                                    Surface(
                                                        onClick = {
                                                            HapticUtils.performClickHaptic(context)
                                                            onStartSleepTimer(mins, finishLastSongSettings)
                                                        },
                                                        shape = CircleShape,
                                                        color = if (isThisActive) BentoPrimary else BentoCardPurple.copy(alpha = 0.55f),
                                                        border = BorderStroke(1.dp, if (isThisActive) BentoPrimary else BentoBorder.copy(alpha = 0.5f)),
                                                        modifier = Modifier.weight(1f).height(38.dp)
                                                    ) {
                                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                            Text(
                                                                text = label,
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (isThisActive) BentoOnPrimary else BentoTextPrimary
                                                            )
                                                        }
                                                    }
                                                }
                                                Surface(
                                                    onClick = {
                                                        HapticUtils.performClickHaptic(context)
                                                        showCustomMinutesDialog = true
                                                    },
                                                    shape = CircleShape,
                                                    color = BentoPrimary.copy(alpha = 0.10f),
                                                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                                                    modifier = Modifier.weight(1.15f).height(38.dp)
                                                ) {
                                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                        Text(
                                                            text = "Custom",
                                                            fontSize = 11.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = BentoPrimary
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Track-Based Quick Chips
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("Track-Based Counter", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BentoTextSubtle)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf(1 to "1 Trk", 2 to "2 Trks", 5 to "5 Trks", 10 to "10 Trks").forEach { (count, label) ->
                                                    val isThisActive = sleepTimerState.isActive && sleepTimerState.mode == "SONGS" && sleepTimerState.totalSongs == count
                                                    Surface(
                                                        onClick = {
                                                            HapticUtils.performClickHaptic(context)
                                                            onStartSongCountTimer(count)
                                                        },
                                                        shape = CircleShape,
                                                        color = if (isThisActive) BentoPrimary else BentoCardPurple.copy(alpha = 0.55f),
                                                        border = BorderStroke(1.dp, if (isThisActive) BentoPrimary else BentoBorder.copy(alpha = 0.5f)),
                                                        modifier = Modifier.weight(1f).height(38.dp)
                                                    ) {
                                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                            Text(
                                                                text = label,
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (isThisActive) BentoOnPrimary else BentoTextPrimary
                                                            )
                                                        }
                                                    }
                                                }
                                                Surface(
                                                    onClick = {
                                                        HapticUtils.performClickHaptic(context)
                                                        showCustomTrackDialog = true
                                                    },
                                                    shape = CircleShape,
                                                    color = BentoPrimary.copy(alpha = 0.10f),
                                                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                                                    modifier = Modifier.weight(1.15f).height(38.dp)
                                                ) {
                                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                        Text(
                                                            text = "Custom",
                                                            fontSize = 11.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = BentoPrimary
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
                }

                // Group 4B: Playback Automation (including Zero-Volume Pause/Resume)
                val showPlaybackAutomation = !isSearching || broadPlaybackMatch ||
                    matchPlaybackStartupAutoplay || matchPlaybackHeadphones ||
                    matchAudioPauseZero || matchAudioAutoResume
                if (showPlaybackAutomation) {
                    item(key = "settings_playback_automation_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "PLAYBACK AUTOMATION")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                if (!isSearching || matchPlaybackStartupAutoplay || broadPlaybackMatch) {
                                    SettingsToggleRow(
                                        title = "Autoplay music on app startup",
                                        subtitle = "Automatically begin music playback when the app is launched",
                                        checked = autoPlayOnAppStartup,
                                        onCheckedChange = onSetAutoPlayOnAppStartup,
                                        icon = Icons.Default.PlayCircle,
                                        accentColor = BentoPrimary
                                    )
                                    if (!isSearching || broadPlaybackMatch) SettingsDivider()
                                }

                                if (!isSearching || matchPlaybackHeadphones || broadPlaybackMatch) {
                                    SettingsToggleRow(
                                        title = "Automatically Play on Headphone Connection",
                                        subtitle = "Automatically start or resume music when wired or wireless headphones are connected",
                                        checked = autoPlayOnHeadphoneConnection,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                                    val isGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                                                        context,
                                                        android.Manifest.permission.BLUETOOTH_CONNECT
                                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                    if (isGranted) {
                                                        onSetAutoPlayOnHeadphoneConnection(true)
                                                    } else {
                                                        showHeadphonePermissionDialog = true
                                                    }
                                                } else {
                                                    onSetAutoPlayOnHeadphoneConnection(true)
                                                }
                                            } else {
                                                onSetAutoPlayOnHeadphoneConnection(false)
                                            }
                                        },
                                        icon = Icons.Default.Headphones,
                                        accentColor = BentoPrimary
                                    )
                                    if (!isSearching || broadPlaybackMatch) SettingsDivider()
                                }

                                if (!isSearching || matchAudioPauseZero || broadPlaybackMatch) {
                                    SettingsToggleRow(
                                        title = "Pause on Zero Volume",
                                        subtitle = "Automatically pause media playback when device volume drops to 0%",
                                        checked = pauseOnZeroVolume,
                                        onCheckedChange = onSetPauseOnZeroVolume,
                                        icon = Icons.Default.VolumeMute,
                                        accentColor = BentoPrimary
                                    )
                                    if (!isSearching || broadPlaybackMatch) SettingsDivider()
                                }

                                if (!isSearching || matchAudioAutoResume || broadPlaybackMatch) {
                                    SettingsToggleRow(
                                        title = "Auto Resume on Volume Increase",
                                        subtitle = "Automatically resume music when raising volume back above 0%",
                                        checked = autoResumeOnVolumeIncrease,
                                        onCheckedChange = onSetAutoResumeOnVolumeIncrease,
                                        icon = Icons.Default.PlayArrow,
                                        accentColor = BentoPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Group 4C: Gestures, Shuffle & Display
                val showPlaybackControls = !isSearching || broadPlaybackMatch ||
                    matchPlaybackAlwaysShuffle || matchPlaybackGestures ||
                    matchPlaybackShake || matchPlaybackKeepAwake
                if (showPlaybackControls) {
                    item(key = "settings_playback_controls_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "GESTURES, SHUFFLE & DISPLAY")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                if (!isSearching || matchPlaybackAlwaysShuffle || broadPlaybackMatch) {
                                    SettingsToggleRow(
                                        title = "Always Shuffle Mode",
                                        subtitle = "Automatically start playlist and album playback in shuffle order",
                                        checked = alwaysShuffleMode,
                                        onCheckedChange = onSetAlwaysShuffleMode,
                                        icon = Icons.Default.Shuffle,
                                        accentColor = BentoPrimary
                                    )
                                    if (!isSearching || broadPlaybackMatch) SettingsDivider()
                                }

                                if (!isSearching || matchPlaybackGestures || broadPlaybackMatch) {
                                    SettingsToggleRow(
                                        title = "Album Art Gestures",
                                        subtitle = "Swipe horizontally on album art to skip tracks (left: previous, right: next) or tap to play/pause",
                                        checked = gesturePlaybackEnabled,
                                        onCheckedChange = onSetGesturePlaybackEnabled,
                                        icon = Icons.Default.TouchApp,
                                        accentColor = BentoPrimary
                                    )
                                    if (!isSearching || broadPlaybackMatch) SettingsDivider()
                                }

                                if (!isSearching || matchPlaybackShake || broadPlaybackMatch) {
                                    SettingsToggleRow(
                                        title = "Shake to Skip Track",
                                        subtitle = "Physically shake the device to advance to the next song in the queue",
                                        checked = shakeToSkip,
                                        onCheckedChange = onSetShakeToSkip,
                                        icon = Icons.Default.Vibration,
                                        accentColor = BentoPrimary
                                    )
                                    if (!isSearching || broadPlaybackMatch) SettingsDivider()
                                }

                                if (!isSearching || matchPlaybackKeepAwake || broadPlaybackMatch) {
                                    SettingsToggleRow(
                                        title = "Keep Screen Awake",
                                        subtitle = "Prevent device display from sleeping while lyrics or player is active",
                                        checked = keepScreenAwake,
                                        onCheckedChange = onSetKeepScreenAwake,
                                        icon = Icons.Default.WbSunny,
                                        accentColor = BentoPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Group 4D: Queue, Playlists & Track Completion
                val showPlaybackQueueAndTracking = !isSearching || broadPlaybackMatch ||
                    matchPlaybackAddToQueuePos || matchPlaybackAddToPlaylistPos || matchPlaybackCompletion
                if (showPlaybackQueueAndTracking) {
                    item(key = "settings_playback_queue_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "QUEUE, PLAYLISTS & TRACKING")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                if (!isSearching || matchPlaybackAddToQueuePos || broadPlaybackMatch) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable {
                                                HapticUtils.performClickHaptic(context)
                                                showQueuePositionDialog = true
                                            }
                                            .padding(vertical = 4.dp)
                                            .testTag("settings_add_to_queue_position_row"),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(BentoPrimary.copy(alpha = 0.14f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.QueueMusic,
                                                    contentDescription = null,
                                                    tint = BentoPrimary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Column {
                                                Text("Add to Queue Position", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                                Text(
                                                    text = if (addToQueuePosition == "TOP_NEXT") "Top Next • Play after current track" else "Bottom • Append to end of queue",
                                                    fontSize = 11.5.sp,
                                                    color = BentoTextSecondary
                                                )
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = BentoPrimary.copy(alpha = 0.14f),
                                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f))
                                            ) {
                                                Text(
                                                    text = if (addToQueuePosition == "TOP_NEXT") "Top Next" else "Bottom",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BentoPrimary,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = "Choose Add to Queue Position",
                                                tint = BentoTextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (!isSearching || broadPlaybackMatch) SettingsDivider()
                                }

                                if (!isSearching || matchPlaybackAddToPlaylistPos || broadPlaybackMatch) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable {
                                                HapticUtils.performClickHaptic(context)
                                                showPlaylistPositionDialog = true
                                            }
                                            .padding(vertical = 4.dp)
                                            .testTag("settings_add_to_playlist_position_row"),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(BentoPrimary.copy(alpha = 0.14f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PlaylistAdd,
                                                    contentDescription = null,
                                                    tint = BentoPrimary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Column {
                                                Text("Add to Playlist Position", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                                Text(
                                                    text = if (playlistAddSongPosition == "TOP") "Top (Beginning) • Insert at top" else "Bottom (End) • Append to end",
                                                    fontSize = 11.5.sp,
                                                    color = BentoTextSecondary
                                                )
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = BentoPrimary.copy(alpha = 0.14f),
                                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f))
                                            ) {
                                                Text(
                                                    text = if (playlistAddSongPosition == "TOP") "Top" else "Bottom",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BentoPrimary,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = "Choose Add to Playlist Position",
                                                tint = BentoTextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (!isSearching || broadPlaybackMatch) SettingsDivider()
                                }

                                if (!isSearching || matchPlaybackCompletion || broadPlaybackMatch) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text("Track Completion Criteria", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                                Text(
                                                    text = if (scrobbleThresholdType == "PERCENTAGE") "Count track as played after ${scrobbleThresholdValue}% completed" else "Count track as played after ${scrobbleThresholdValue} seconds",
                                                    fontSize = 11.5.sp,
                                                    color = BentoTextSecondary
                                                )
                                            }
                                            Surface(
                                                shape = CircleShape,
                                                color = BentoPrimary.copy(alpha = 0.14f),
                                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f))
                                            ) {
                                                Text(
                                                    text = if (scrobbleThresholdType == "PERCENTAGE") "${scrobbleThresholdValue}%" else "${scrobbleThresholdValue}s",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BentoPrimary,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                                )
                                            }
                                        }

                                        SettingsSegmentedSelector(
                                            options = listOf(
                                                "SECONDS" to "By Duration (Sec)",
                                                "PERCENTAGE" to "By Percentage (%)"
                                            ),
                                            selectedOption = scrobbleThresholdType,
                                            onSelectOption = {
                                                onSetScrobbleThresholdType(it)
                                                if (it == "PERCENTAGE" && scrobbleThresholdValue > 100) {
                                                    onSetScrobbleThresholdValue(50)
                                                } else if (it == "SECONDS" && scrobbleThresholdValue < 10) {
                                                    onSetScrobbleThresholdValue(30)
                                                }
                                            },
                                            accentColor = BentoPrimary
                                        )

                                        if (scrobbleThresholdType == "PERCENTAGE") {
                                            Slider(
                                                value = scrobbleThresholdValue.coerceIn(10, 100).toFloat(),
                                                onValueChange = { onSetScrobbleThresholdValue(it.toInt()) },
                                                valueRange = 10f..100f,
                                                steps = 8,
                                                colors = SliderDefaults.colors(
                                                    thumbColor = BentoPrimary,
                                                    activeTrackColor = BentoPrimary,
                                                    inactiveTrackColor = BentoBorder.copy(alpha = 0.65f)
                                                )
                                            )
                                        } else {
                                            Slider(
                                                value = scrobbleThresholdValue.coerceIn(5, 180).toFloat(),
                                                onValueChange = { onSetScrobbleThresholdValue(it.toInt()) },
                                                valueRange = 5f..180f,
                                                steps = 34,
                                                colors = SliderDefaults.colors(
                                                    thumbColor = BentoPrimary,
                                                    activeTrackColor = BentoPrimary,
                                                    inactiveTrackColor = BentoBorder.copy(alpha = 0.65f)
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 5: CACHE & OFFLINE STORAGE
            // ==========================================
            if (showCache) {
                item(key = "settings_cache_refresh_trigger") {
                    LaunchedEffect(Unit) {
                        onRefreshCacheSizes()
                        onRefreshDatabaseStats()
                    }
                    if (isSearching) {
                        SettingsSectionHeader(
                            title = "Cache & Storage",
                            subtitle = "Lyrics & artwork caches plus local SQLite database capacity",
                            kicker = "OFFLINE STORAGE & CACHE",
                            icon = Icons.Default.Storage,
                            accentColor = BentoPrimary
                        )
                    }
                }

                // Group 5A: Media Cache
                if (!isSearching || anyCacheCardMatches || broadCacheMatch) {
                    item(key = "settings_cache_card") {
                        SettingsHighlightWrap(isHighlighted = highlightedSettingKey == "SECTION_CACHE") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsSubGroupLabel(title = "MEDIA CACHE")
                                SettingsCardContainer(accentGlow = BentoPrimary) {
                                    if (!isSearching || matchCacheTotal || broadCacheMatch) {
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = BentoCardPurple.copy(alpha = 0.55f),
                                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.3f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("Total Cache Occupied", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextSecondary)
                                                    Text(totalCacheSize, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = BentoPrimary)
                                                }
                                                Button(
                                                    onClick = {
                                                        HapticUtils.performClickHaptic(context)
                                                        onClearAllCaches()
                                                        android.widget.Toast.makeText(context, "All media caches cleared", android.widget.Toast.LENGTH_SHORT).show()
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary),
                                                    shape = CircleShape
                                                ) {
                                                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Clear All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                        if (!isSearching || broadCacheMatch) SettingsDivider()
                                    }

                                    if (!isSearching || matchCacheLyrics || broadCacheMatch) {
                                        SettingsToggleRow(
                                            title = "Cache Synced Lyrics",
                                            subtitle = "Store time-synchronized lyrics locally for offline display ($lyricsCacheSize)",
                                            checked = cacheLyricsEnabled,
                                            onCheckedChange = onSetCacheLyricsEnabled,
                                            icon = Icons.Default.FormatQuote,
                                            accentColor = BentoPrimary
                                        )
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            OutlinedButton(
                                                onClick = {
                                                    HapticUtils.performClickHaptic(context)
                                                    onClearLyricsCache()
                                                    android.widget.Toast.makeText(context, "Lyrics cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                shape = CircleShape,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary),
                                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f))
                                            ) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text("Clear Lyrics ($lyricsCacheSize)", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                        if (!isSearching || broadCacheMatch) SettingsDivider()
                                    }

                                    if (!isSearching || matchCacheAlbumArt || broadCacheMatch) {
                                        SettingsToggleRow(
                                            title = "Cache Album Artwork",
                                            subtitle = if (cacheAlbumArtEnabled) "High-res covers are stored on disk for instant offline loading ($albumArtCacheSize)" else "Artwork is fetched live from network on every song change",
                                            checked = cacheAlbumArtEnabled,
                                            onCheckedChange = onSetCacheAlbumArtEnabled,
                                            icon = Icons.Default.Album,
                                            accentColor = BentoPrimary
                                        )
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            OutlinedButton(
                                                onClick = {
                                                    HapticUtils.performClickHaptic(context)
                                                    onClearAlbumArtCache()
                                                    android.widget.Toast.makeText(context, "Album artwork cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                shape = CircleShape,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary),
                                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f))
                                            ) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text("Clear Album Art ($albumArtCacheSize)", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                        if (!isSearching || broadCacheMatch) SettingsDivider()
                                    }

                                    if (!isSearching || matchCacheSongArt || broadCacheMatch) {
                                        SettingsToggleRow(
                                            title = "Cache Song Artwork",
                                            subtitle = if (cacheSongArtWorkEnabled) "Track-specific custom covers are persisted locally ($songArtCacheSize)" else "Individual song artwork is fetched dynamically from the network",
                                            checked = cacheSongArtWorkEnabled,
                                            onCheckedChange = onSetCacheSongArtWorkEnabled,
                                            icon = Icons.Default.MusicNote,
                                            accentColor = BentoPrimary
                                        )
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            OutlinedButton(
                                                onClick = {
                                                    HapticUtils.performClickHaptic(context)
                                                    onClearSongArtCache()
                                                    android.widget.Toast.makeText(context, "Song artwork cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                shape = CircleShape,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary),
                                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f))
                                            ) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text("Clear Song Art ($songArtCacheSize)", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Group 5B: Database Engine & Capacity
                if (!isSearching || matchCacheDatabase || broadCacheMatch) {
                    item(key = "settings_database_storage_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "DATABASE ENGINE & CAPACITY")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(BentoPrimary.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Dns,
                                            contentDescription = null,
                                            tint = BentoPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = "Room SQLite Database Engine",
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoTextPrimary
                                        )
                                        Text(
                                            text = "Expanded storage capacity, WAL caching & optimization",
                                            fontSize = 11.5.sp,
                                            color = BentoTextSecondary
                                        )
                                    }
                                }

                                // Responsive DB Metrics Grid
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = BentoCardPurple.copy(alpha = 0.55f),
                                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp)
                                    ) {
                                        val isWide = maxWidth >= 420.dp
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("Database Size", fontSize = 11.sp, color = BentoTextSecondary)
                                                    Text(databaseStorageStats?.formattedMainDbSize ?: "—", fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = BentoPrimary)
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("WAL Journal", fontSize = 11.sp, color = BentoTextSecondary)
                                                    Text(databaseStorageStats?.formattedWalSize ?: "—", fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = BentoPrimary)
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("Total Storage", fontSize = 11.sp, color = BentoTextSecondary)
                                                    Text(databaseStorageStats?.formattedTotalDbSize ?: "—", fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                                }
                                            }
                                            HorizontalDivider(color = BentoBorder.copy(alpha = 0.45f))
                                            if (isWide) {
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text("Indexed Songs", fontSize = 11.sp, color = BentoTextSecondary)
                                                        Text("${databaseStorageStats?.songCount ?: 0}", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                                    }
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text("History Sessions", fontSize = 11.sp, color = BentoTextSecondary)
                                                        Text("${databaseStorageStats?.historySessionCount ?: 0}", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                                    }
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text("Quota Remaining", fontSize = 11.sp, color = BentoTextSecondary)
                                                        Text("${databaseStorageStats?.quotaRemaining ?: 50000}", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = BentoPrimary)
                                                    }
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text("Lyrics Cached", fontSize = 11.sp, color = BentoTextSecondary)
                                                        Text("${databaseStorageStats?.cachedLyricsCount ?: 0}", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                                    }
                                                }
                                            } else {
                                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text("Indexed Songs", fontSize = 11.sp, color = BentoTextSecondary)
                                                            Text("${databaseStorageStats?.songCount ?: 0}", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                                        }
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text("History Sessions", fontSize = 11.sp, color = BentoTextSecondary)
                                                            Text("${databaseStorageStats?.historySessionCount ?: 0}", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                                        }
                                                    }
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text("Quota Remaining", fontSize = 11.sp, color = BentoTextSecondary)
                                                            Text("${databaseStorageStats?.quotaRemaining ?: 50000}", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = BentoPrimary)
                                                        }
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text("Lyrics Cached", fontSize = 11.sp, color = BentoTextSecondary)
                                                            Text("${databaseStorageStats?.cachedLyricsCount ?: 0}", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                                        }
                                                    }
                                                }
                                            }

                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        "Local Database Usage: ${databaseStorageStats?.historySessionCount ?: 0} / ${databaseStorageStats?.quotaLimit ?: 50000} records",
                                                        fontSize = 11.sp,
                                                        color = BentoTextSecondary,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text(
                                                        "${((databaseStorageStats?.quotaUsagePercentage ?: 0f) * 100).toInt()}%",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if ((databaseStorageStats?.quotaUsagePercentage ?: 0f) > 0.9f) BentoAccentPinkText else BentoPrimary
                                                    )
                                                }
                                                LinearProgressIndicator(
                                                    progress = { databaseStorageStats?.quotaUsagePercentage ?: 0f },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(5.dp)
                                                        .clip(CircleShape),
                                                    color = if ((databaseStorageStats?.quotaUsagePercentage ?: 0f) > 0.9f) BentoAccentPinkText else BentoPrimary,
                                                    trackColor = BentoBorder.copy(alpha = 0.5f)
                                                )
                                            }
                                        }
                                    }
                                }

                                SettingsDivider()

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Database Capacity Tier", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                    Text("Configure playback history retention and caching limits for this device's local database", fontSize = 11.5.sp, color = BentoTextSecondary)
                                }

                                SettingsSegmentedSelector(
                                    options = listOf(
                                        "STANDARD" to "Standard (10K)",
                                        "EXPANDED" to "Expanded (50K)",
                                        "MAXIMUM" to "Maximum (100K)"
                                    ),
                                    selectedOption = databaseStorageTier,
                                    onSelectOption = {
                                        HapticUtils.performClickHaptic(context)
                                        onSetDatabaseStorageTier(it)
                                        android.widget.Toast.makeText(context, "Local database storage set to $it tier", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    accentColor = BentoPrimary
                                )

                                Text(
                                    text = when (databaseStorageTier) {
                                        "MAXIMUM" -> "Maximum: Retains up to 100,000 history sessions, 50,000 cached lyrics, and 90-day tracking data on this device. Best for massive libraries."
                                        "STANDARD" -> "Standard: Retains up to 10,000 history sessions, 3,000 lyrics, and 30-day tracking data on this device. Minimal storage footprint."
                                        else -> "Expanded: Retains up to 50,000 history sessions, 15,000 lyrics, and 60-day tracking data on this device. Recommended balance of deep stats and speed."
                                    },
                                    fontSize = 11.5.sp,
                                    color = BentoTextSecondary,
                                    lineHeight = 16.sp
                                )

                                SettingsDivider()

                                Button(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onOptimizeDatabase { _, message ->
                                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    enabled = !isOptimizingDatabase,
                                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary),
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                ) {
                                    if (isOptimizingDatabase) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BentoOnPrimary, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Optimizing...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Optimize DB", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 6: LIBRARY & FOLDERS
            // ==========================================
            if (showLibrary) {
                if (isSearching) {
                    item(key = "settings_library_header") {
                        SettingsSectionHeader(
                            title = "Library & Folders",
                            subtitle = "Audio indexing, short audio duration filter & custom music folders",
                            kicker = "LIBRARY SCANNING & FOLDERS",
                            icon = Icons.Default.Folder,
                            accentColor = BentoPrimary
                        )
                    }
                }

                // Group 6A: Library Scanning
                val showLibScanning = !isSearching || matchLibQuickScan || matchLibFullScan || broadLibraryMatch
                if (showLibScanning) {
                    item(key = "settings_library_scanning_card") {
                        var showFullDeviceScanConfirmDialog by remember { mutableStateOf(false) }
                        val isCurrentlyScanning = scanProgress.isScanning || scanProgress.status == com.example.scanner.ScanStatus.SCANNING

                        SettingsHighlightWrap(isHighlighted = highlightedSettingKey == "SECTION_LIBRARY") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsSubGroupLabel(title = "LIBRARY SCANNING")
                                SettingsCardContainer(accentGlow = BentoPrimary) {
                                    if (scanProgress.isScanning || scanProgress.status == com.example.scanner.ScanStatus.SCANNING) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = BentoPrimary.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = BentoPrimary)
                                                        Text("${scanProgress.displayTracksCount} tracks found", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BentoPrimary)
                                                    }
                                                    Text("Scanning in progress...", fontSize = 11.5.sp, color = BentoTextSecondary)
                                                }
                                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = BentoPrimary)
                                            }
                                        }
                                        SettingsDivider()
                                    } else if (scanProgress.status == com.example.scanner.ScanStatus.COMPLETED && scanProgress.isFinished) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color(0xFF00E676).copy(alpha = 0.10f),
                                            border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.3f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("${scanProgress.displayTracksCount} tracks found", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                                                Text("Scan completed", fontSize = 11.5.sp, color = BentoTextSecondary)
                                            }
                                        }
                                        SettingsDivider()
                                    } else if (scanProgress.status == com.example.scanner.ScanStatus.ERROR) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = BentoError.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, BentoError.copy(alpha = 0.35f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(scanProgress.errorMessage ?: "Scan error", fontSize = 12.5.sp, color = BentoError, modifier = Modifier.weight(1f))
                                                Text("Error", fontSize = 11.5.sp, color = BentoTextSecondary)
                                            }
                                        }
                                        SettingsDivider()
                                    }

                                    if (!isSearching || matchLibQuickScan || broadLibraryMatch) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                                Text("Quick Scan & Clean", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                                Text("Quickly prune missing audio files and index newly downloaded tracks without disturbing edits", fontSize = 11.5.sp, color = BentoTextSecondary)
                                            }
                                            Button(
                                                onClick = {
                                                    HapticUtils.performClickHaptic(context)
                                                    onQuickScanAndCleanLibrary()
                                                    android.widget.Toast.makeText(context, "Quick scan & clean started", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                enabled = !isCurrentlyScanning,
                                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary),
                                                shape = CircleShape
                                            ) {
                                                Text("Quick Scan", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }
                                        if (!isSearching || broadLibraryMatch) SettingsDivider()
                                    }

                                    if (!isSearching || matchLibFullScan || broadLibraryMatch) {
                                        SettingsToggleRow(
                                            title = "Full-Device Scan",
                                            subtitle = if (fullDeviceScan) "Search the entire device file system for audio tracks" else "Restricted strictly to configured music directories",
                                            checked = fullDeviceScan,
                                            onCheckedChange = { checked ->
                                                if (checked && folders.isNotEmpty()) {
                                                    showFullDeviceScanConfirmDialog = true
                                                } else {
                                                    onSetFullDeviceScan(checked)
                                                    onScanLibrary()
                                                }
                                            },
                                            icon = Icons.Default.Devices,
                                            accentColor = BentoPrimary
                                        )
                                    }
                                }
                            }
                        }

                        if (showFullDeviceScanConfirmDialog) {
                            val incCount = folders.count { !it.isExcluded }
                            val excCount = folders.count { it.isExcluded }
                            AlertDialog(
                                onDismissRequest = { showFullDeviceScanConfirmDialog = false },
                                title = {
                                    Text(
                                        text = "Confirm Full-Device Scan",
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                },
                                text = {
                                    Text(
                                        text = "You have $incCount included and $excCount excluded folder(s) configured.\n\nFull-Device Scan may also include folders that you added in the exclude list and perform a scan to fetch tracks from all folders on your device. Do you want to confirm?",
                                        fontSize = 13.5.sp,
                                        color = BentoTextSecondary,
                                        lineHeight = 19.sp
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showFullDeviceScanConfirmDialog = false
                                            onSetFullDeviceScan(true)
                                            onScanLibrary()
                                        },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary)
                                    ) {
                                        Text("Confirm & Scan All", fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showFullDeviceScanConfirmDialog = false }) {
                                        Text("Cancel", color = BentoTextSecondary)
                                    }
                                },
                                containerColor = BentoCardSurface,
                                shape = RoundedCornerShape(22.dp)
                            )
                        }
                    }
                }

                // Group 6B: Audio Filter & Local Storage
                val showLibFilterAndLocal = !isSearching || matchLibIgnoreShort || matchLibStoreLocal || broadLibraryMatch
                if (showLibFilterAndLocal) {
                    item(key = "settings_library_filter_card") {
                        var showStoreIndexedLocallyConfirmDialog by remember { mutableStateOf(false) }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "AUDIO FILTER & LOCAL STORAGE")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                if (!isSearching || matchLibIgnoreShort || broadLibraryMatch) {
                                    AudioDurationFilterSetting(
                                        ignoreAudioDuration = ignoreAudioDuration,
                                        onSetIgnoreAudioDuration = onSetIgnoreAudioDuration
                                    )
                                    if (!isSearching || broadLibraryMatch) SettingsDivider()
                                }

                                if (!isSearching || matchLibStoreLocal || broadLibraryMatch) {
                                    SettingsToggleRow(
                                        title = "Update & store indexed songs in local storage too",
                                        subtitle = if (storeIndexedSongsLocally) {
                                            "Automatically updates and stores cleaned app song names to local storage in sequential batches"
                                        } else {
                                            "Indexed song names are shown in the app only without modifying local device storage"
                                        },
                                        checked = storeIndexedSongsLocally,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                showStoreIndexedLocallyConfirmDialog = true
                                            } else {
                                                onSetStoreIndexedSongsLocally(false)
                                            }
                                        },
                                        icon = Icons.Default.Save,
                                        accentColor = BentoPrimary
                                    )

                                    if (localSongSyncProgress.isSyncing) {
                                        val fraction = if (localSongSyncProgress.totalCount > 0) {
                                            (localSongSyncProgress.processedCount.toFloat() / localSongSyncProgress.totalCount.toFloat()).coerceIn(0f, 1f)
                                        } else 0f
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = BentoPrimary.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = BentoPrimary)
                                                        Text(
                                                            text = "Updating ${localSongSyncProgress.processedCount} / ${localSongSyncProgress.totalCount} songs",
                                                            fontSize = 12.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = BentoPrimary
                                                        )
                                                    }
                                                    Text(
                                                        text = "${localSongSyncProgress.updatedCount} updated",
                                                        fontSize = 11.5.sp,
                                                        color = BentoTextSecondary
                                                    )
                                                }
                                                LinearProgressIndicator(
                                                    progress = { fraction },
                                                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                                                    color = BentoPrimary
                                                )
                                            }
                                        }
                                    } else if (storeIndexedSongsLocally && localSongSyncProgress.isCompleted && localSongSyncProgress.totalCount > 0) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color(0xFF00E676).copy(alpha = 0.10f),
                                            border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.3f)),
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Synced ${localSongSyncProgress.totalCount} songs locally",
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF00E676)
                                                )
                                                Text(
                                                    text = "${localSongSyncProgress.updatedCount} updated",
                                                    fontSize = 11.5.sp,
                                                    color = BentoTextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (showStoreIndexedLocallyConfirmDialog) {
                            AlertDialog(
                                onDismissRequest = { showStoreIndexedLocallyConfirmDialog = false },
                                title = {
                                    Text(
                                        text = "Update & Store Indexed Songs Locally",
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Turning this on will result in changes to all songs represented in the app by updating and storing their indexed song names in your local device storage in sequential batches.\n\nYour Recently Added and Modified song order will be preserved across both your device and the app. Do you want to proceed?",
                                        fontSize = 13.5.sp,
                                        color = BentoTextSecondary,
                                        lineHeight = 19.sp
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showStoreIndexedLocallyConfirmDialog = false
                                            onSetStoreIndexedSongsLocally(true)
                                        },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary)
                                    ) {
                                        Text("Confirm & Turn-On", fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showStoreIndexedLocallyConfirmDialog = false }) {
                                        Text("Cancel", color = BentoTextSecondary)
                                    }
                                },
                                containerColor = BentoCardSurface,
                                shape = RoundedCornerShape(22.dp)
                            )
                        }
                    }
                }

                // Group 6C: Custom Music Directories
                if (!isSearching || matchLibCustomFolders || broadLibraryMatch) {
                    item(key = "settings_library_folders_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "CUSTOM MUSIC DIRECTORIES")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text("Include or Exclude Folders", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                        Text("Select explicit directories to include or exclude from indexing", fontSize = 11.5.sp, color = BentoTextSecondary)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        IconButton(
                                            onClick = {
                                                isPickingForExclusion = false
                                                folderPickerLauncher.launch(null)
                                            },
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(BentoPrimary.copy(alpha = 0.14f))
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = "Add Folder", tint = BentoPrimary)
                                        }
                                        IconButton(
                                            onClick = {
                                                isPickingForExclusion = true
                                                folderPickerLauncher.launch(null)
                                            },
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(BentoError.copy(alpha = 0.12f))
                                        ) {
                                            Icon(Icons.Default.Block, contentDescription = "Exclude Folder", tint = BentoError)
                                        }
                                    }
                                }

                                if (folders.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        folders.forEach { folder ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(BentoCardPurple.copy(alpha = 0.55f))
                                                    .border(BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
                                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                    Icon(
                                                        imageVector = if (folder.isExcluded) Icons.Default.Block else Icons.Default.Folder,
                                                        contentDescription = null,
                                                        tint = if (folder.isExcluded) BentoError else BentoPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column {
                                                        Text(folder.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                                        Text(folder.path, fontSize = 11.sp, color = BentoTextSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                                IconButton(
                                                    onClick = { onRemoveFolder(folder.path) },
                                                    modifier = Modifier.size(34.dp)
                                                ) {
                                                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = BentoTextSecondary, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 7: OTHER & PRIVACY
            // ==========================================
            if (showOther) {
                if (isSearching) {
                    item(key = "settings_other_header") {
                        SettingsSectionHeader(
                            title = "Other & Privacy",
                            subtitle = "Search history, diagnostics, app updates & reset defaults",
                            kicker = "PRIVACY & SYSTEM HEALTH",
                            icon = Icons.Default.Security,
                            accentColor = BentoPrimary
                        )
                    }
                }

                // Group 7A: Privacy & Diagnostics
                val showOtherPrivacy = !isSearching || matchOtherSearchHistory || matchOtherDiagnostics || broadOtherMatch
                if (showOtherPrivacy) {
                    item(key = "settings_other_privacy_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "PRIVACY & DIAGNOSTICS")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                if (!isSearching || matchOtherSearchHistory || broadOtherMatch) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                            Text("Recent Search Queries", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                            Text("${recentSearches.size} search queries recorded locally on device", fontSize = 11.5.sp, color = BentoTextSecondary)
                                        }
                                        if (recentSearches.isNotEmpty()) {
                                            OutlinedButton(
                                                onClick = {
                                                    HapticUtils.performClickHaptic(context)
                                                    onClearRecentSearches()
                                                },
                                                shape = CircleShape,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoError),
                                                border = BorderStroke(1.dp, BentoError.copy(alpha = 0.5f))
                                            ) {
                                                Text("Clear All", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                    if (!isSearching || broadOtherMatch) SettingsDivider()
                                }

                                if (!isSearching || matchOtherDiagnostics || broadOtherMatch) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        val allCrashReports = remember(diagnosticsRefreshTrigger) {
                                            try { com.example.util.CrashReporter.getAllCrashReports(context) } catch (_: Exception) { emptyList() }
                                        }
                                        val crashReportCount = allCrashReports.size

                                        Column {
                                            Text("Diagnostics & Error Reports", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                            Text(
                                                if (crashReportCount > 0) "$crashReportCount error report(s) available on device" else "No errors recorded. Engine operating normally.",
                                                fontSize = 11.5.sp,
                                                color = if (crashReportCount > 0) Color(0xFFFFB74D) else BentoTextSecondary
                                            )
                                        }

                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    try {
                                                        val txtFile = com.example.util.CrashReporter.exportReportsAsTxt(context)
                                                        com.example.util.CrashReporter.shareExportedReport(context, txtFile, "text/plain")
                                                    } catch (e: Exception) {
                                                        android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                shape = CircleShape,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary),
                                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text("Export .TXT", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    try {
                                                        val jsonFile = com.example.util.CrashReporter.exportReportsAsJson(context)
                                                        com.example.util.CrashReporter.shareExportedReport(context, jsonFile, "application/json")
                                                    } catch (e: Exception) {
                                                        android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                shape = CircleShape,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary),
                                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text("Export .JSON", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                            }

                                            if (crashReportCount > 0) {
                                                OutlinedButton(
                                                    onClick = {
                                                        com.example.util.CrashReporter.clearCrashReports(context)
                                                        diagnosticsRefreshTrigger++
                                                        android.widget.Toast.makeText(context, "Diagnostics logs cleared", android.widget.Toast.LENGTH_SHORT).show()
                                                    },
                                                    shape = CircleShape,
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoError),
                                                    border = BorderStroke(1.dp, BentoError.copy(alpha = 0.5f)),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                ) {
                                                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(5.dp))
                                                    Text("Clear", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Group 7B: App Updates & Releases
                if (!isSearching || matchOtherUpdates || broadOtherMatch) {
                    item(key = "settings_updates_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "APP UPDATES & RELEASES")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Installed App Version",
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoTextPrimary
                                        )
                                        Text(
                                            text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                                            fontSize = 12.sp,
                                            color = BentoTextSecondary
                                        )
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = BentoPrimary.copy(alpha = 0.14f),
                                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f))
                                    ) {
                                        Text(
                                            text = "v${BuildConfig.VERSION_NAME}",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoPrimary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                SettingsDivider()

                                SettingsToggleRow(
                                    title = "Update Announcements",
                                    subtitle = "Receive notifications when a new stable release is published",
                                    checked = updateNotificationsEnabled,
                                    onCheckedChange = onSetUpdateNotificationsEnabled,
                                    icon = Icons.Default.SystemUpdate,
                                    accentColor = BentoPrimary
                                )

                                SettingsDivider()

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    val isChecking = updateCheckResult is UpdateCheckResult.Checking
                                    val availableUpdate = updateCheckResult as? UpdateCheckResult.UpdateAvailable

                                    Column {
                                        Text(
                                            text = "Release Channel Status",
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoTextPrimary
                                        )
                                        val statusText = when (val res = updateCheckResult) {
                                            is UpdateCheckResult.Checking -> "Checking GitHub Releases..."
                                            is UpdateCheckResult.UpdateAvailable -> "Version ${res.releaseInfo.tag} is available to install"
                                            is UpdateCheckResult.UpToDate -> "Up to date (${res.currentVersion})"
                                            is UpdateCheckResult.RateLimited -> "GitHub API rate limited (${res.message})"
                                            is UpdateCheckResult.Offline -> "Offline — update check requires internet"
                                            is UpdateCheckResult.Error -> "Check failed: ${res.message}"
                                            UpdateCheckResult.Idle -> "Check GitHub Releases for newer versions"
                                        }
                                        val statusColor = when (updateCheckResult) {
                                            is UpdateCheckResult.UpdateAvailable -> BentoPrimary
                                            is UpdateCheckResult.UpToDate -> BentoTextSecondary
                                            is UpdateCheckResult.Checking -> BentoPrimary
                                            is UpdateCheckResult.RateLimited -> Color(0xFFFFB74D)
                                            is UpdateCheckResult.Offline, is UpdateCheckResult.Error -> BentoError
                                            UpdateCheckResult.Idle -> BentoTextSecondary
                                        }
                                        Text(
                                            text = statusText,
                                            fontSize = 11.5.sp,
                                            color = statusColor,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                HapticUtils.performClickHaptic(context)
                                                onCheckForUpdates()
                                            },
                                            enabled = !isChecking,
                                            shape = CircleShape,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary),
                                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                                            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 6.dp)
                                        ) {
                                            if (isChecking) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    strokeWidth = 2.dp,
                                                    color = BentoPrimary
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Checking...", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                            } else {
                                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text("Check for Updates", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }

                                        if (availableUpdate != null) {
                                            Button(
                                                onClick = {
                                                    HapticUtils.performClickHaptic(context)
                                                    onOpenUpdateDialog()
                                                },
                                                shape = CircleShape,
                                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary),
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                            ) {
                                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text("View Update", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                HapticUtils.performClickHaptic(context)
                                                try {
                                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Paul00755/OffGrid-Music"))
                                                    context.startActivity(browserIntent)
                                                } catch (_: Exception) {
                                                    android.widget.Toast.makeText(context, "Could not open browser", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            shape = CircleShape,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoTextSecondary),
                                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.65f)),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text("GitHub Repo", fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Group 7C: Reset Preferences
                if (!isSearching || matchOtherResetDefaults || broadOtherMatch) {
                    item(key = "settings_other_reset_card") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSubGroupLabel(title = "RESET")
                            SettingsCardContainer(accentGlow = BentoPrimary) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.RestartAlt,
                                                contentDescription = null,
                                                tint = BentoError,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Reset to Default Settings",
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoTextPrimary
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "Restore all audio, theme, layout, playback, and library preferences to their original defaults. Your playlists, favorites, listening history, and stored Gemini API key are safely preserved (AI Transcribe/Translate toggle will be turned off).",
                                            fontSize = 11.5.sp,
                                            color = BentoTextSecondary,
                                            lineHeight = 16.sp
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            showResetSettingsConfirmDialog = true
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoError),
                                        border = BorderStroke(1.dp, BentoError.copy(alpha = 0.55f)),
                                        shape = CircleShape,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RestartAlt,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text("Reset", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (showResetSettingsConfirmDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showResetSettingsConfirmDialog = false },
                                        icon = {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .background(BentoError.copy(alpha = 0.14f))
                                                    .border(BorderStroke(1.dp, BentoError.copy(alpha = 0.35f)), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.RestartAlt,
                                                    contentDescription = null,
                                                    tint = BentoError,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        },
                                        title = {
                                            Text(
                                                text = "Reset All Settings to Default?",
                                                fontWeight = FontWeight.Bold,
                                                color = BentoTextPrimary,
                                                fontSize = 17.sp
                                            )
                                        },
                                        text = {
                                            Text(
                                                text = "This will reset all Audio & DSP, UI & Themes, Home Feed, Playback, Cache, Library, and Privacy settings back to their default state.\n\n• Your stored Gemini API key will NOT be deleted (the Transcribe/Translate toggle will simply be turned OFF).\n• Your songs, playlists, favorites, lyrics, and cloud account will remain untouched.",
                                                fontSize = 13.sp,
                                                color = BentoTextSecondary,
                                                lineHeight = 18.5.sp
                                            )
                                        },
                                        confirmButton = {
                                            Button(
                                                onClick = {
                                                    HapticUtils.performClickHaptic(context)
                                                    showResetSettingsConfirmDialog = false
                                                    onResetToDefaultSettings()
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "All settings restored to defaults (API key & library preserved)",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = BentoError,
                                                    contentColor = Color.White
                                                ),
                                                shape = CircleShape
                                            ) {
                                                Text("Reset to Defaults", fontWeight = FontWeight.Bold)
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showResetSettingsConfirmDialog = false }) {
                                                Text("Cancel", color = BentoTextSecondary)
                                            }
                                        },
                                        containerColor = BentoCardSurface,
                                        shape = RoundedCornerShape(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 8: CLOUD & CROSS-DEVICE SYNC
            // ==========================================
            if (showCloudSync) {
                if (isSearching) {
                    item(key = "settings_cloud_sync_header") {
                        SettingsSectionHeader(
                            title = "Cloud & Sync",
                            subtitle = "Google & Email backup for favorites, playlists, statistics and settings",
                            kicker = "CLOUD & DATA BACKUP",
                            icon = Icons.Default.CloudSync,
                            accentColor = BentoPrimary
                        )
                    }
                }

                // Group 8A: Connected Profile
                item(key = "settings_cloud_profile_card") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsSubGroupLabel(title = "CONNECTED PROFILE")
                        SettingsCardContainer(accentGlow = BentoPrimary) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val avatarPhoto = customAvatarUri ?: userAccount?.photoUrl
                                Surface(
                                    shape = CircleShape,
                                    color = BentoPrimary.copy(alpha = 0.14f),
                                    border = BorderStroke(1.5.dp, BentoPrimary.copy(alpha = 0.4f)),
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    if (!avatarPhoto.isNullOrBlank()) {
                                        coil.compose.AsyncImage(
                                            model = avatarPhoto,
                                            contentDescription = "Avatar",
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                                        )
                                    } else if (userAccount != null) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = (effectiveDisplayName.firstOrNull() ?: 'U').uppercase(),
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoPrimary
                                            )
                                        }
                                    } else {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.AccountCircle,
                                                contentDescription = "Avatar",
                                                tint = BentoPrimary,
                                                modifier = Modifier.size(30.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = if (userAccount != null) effectiveDisplayName else "Local Guest Profile",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Surface(
                                            shape = CircleShape,
                                            color = if (userAccount != null) BentoPrimary.copy(alpha = 0.14f) else Color(0xFF00E676).copy(alpha = 0.14f)
                                        ) {
                                            Text(
                                                text = if (userAccount != null) userAccount.friendlyProviderName else "Offline Only",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (userAccount != null) BentoPrimary else Color(0xFF00E676),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = userAccount?.email ?: "Sign in with Google or Email to back up favorites, playlists & stats",
                                        fontSize = 12.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            SettingsDivider()

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (userAccount != null) {
                                    OutlinedButton(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onOpenAccount()
                                        },
                                        shape = CircleShape,
                                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary),
                                        modifier = Modifier.weight(1f).heightIn(min = 42.dp)
                                    ) {
                                        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Account Details", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onSignOut()
                                        },
                                        shape = CircleShape,
                                        border = BorderStroke(1.dp, BentoError.copy(alpha = 0.5f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoError),
                                        modifier = Modifier.weight(1f).heightIn(min = 42.dp)
                                    ) {
                                        Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Sign Out", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onOpenAuth()
                                        },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = BentoPrimary,
                                            contentColor = BentoOnPrimary
                                        ),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                    ) {
                                        Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Sign In or Register with Cloud", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // Group 8B: Synchronization & Backup
                item(key = "settings_cloud_sync_card") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsSubGroupLabel(title = "SYNCHRONIZATION & BACKUP")
                        SettingsCardContainer(accentGlow = BentoPrimary) {
                            com.example.ui.components.CloudSyncBackupSection(
                                automaticBackupEnabled = automaticBackupEnabled,
                                onToggleAutomaticBackup = onToggleAutomaticBackup,
                                backupFrequency = backupFrequency,
                                onSelectFrequency = onSelectFrequency,
                                lastSyncedTimestamp = lastSyncedTimestamp,
                                lastBackupTimestamp = lastBackupTimestamp,
                                syncStatus = syncStatus,
                                backupStatus = backupStatus,
                                onSyncNow = onTriggerSync,
                                onBackupNow = onTriggerBackup,
                                isUserSignedIn = userAccount != null,
                                onOpenAuth = onOpenAuth,
                                onSignInGoogle = onSignInGoogle,
                                showOuterCard = false
                            )
                        }
                    }
                }

                // Group 8C: Included in Cloud Backup (Responsive 2x2 on phones, 4-col on tablets)
                item(key = "settings_cloud_included_card") {
                    val syncSummaryItems = remember {
                        listOf(
                            Triple(Icons.Default.Favorite, "Favorites", "Hearted songs"),
                            Triple(Icons.AutoMirrored.Filled.QueueMusic, "Playlists", "Custom playlists"),
                            Triple(Icons.Default.Insights, "Statistics", "Listening history"),
                            Triple(Icons.Default.Tune, "Preferences", "Theme & audio EQ")
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsSubGroupLabel(title = "INCLUDED IN CLOUD BACKUP")
                        SettingsCardContainer(accentGlow = BentoPrimary) {
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val useSingleRow = maxWidth >= 460.dp
                                if (useSingleRow) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        syncSummaryItems.forEach { (icon, title, desc) ->
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = BentoCardPurple.copy(alpha = 0.55f),
                                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(10.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Icon(imageVector = icon, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(18.dp))
                                                    Text(text = title, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary, textAlign = TextAlign.Center, maxLines = 1)
                                                    Text(text = desc, fontSize = 10.sp, color = BentoTextSecondary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        syncSummaryItems.chunked(2).forEach { pair ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                pair.forEach { (icon, title, desc) ->
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = BentoCardPurple.copy(alpha = 0.55f),
                                                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                        ) {
                                                            Icon(imageVector = icon, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(18.dp))
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary, maxLines = 1)
                                                                Text(text = desc, fontSize = 10.5.sp, color = BentoTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // No Search Results State
            if (isSearching && !anyMatches) {
                item(key = "settings_empty_search") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = BentoTextSubtle, modifier = Modifier.size(36.dp))
                            Text(
                                text = "No settings matching \"$searchQuery\"",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextPrimary,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Try searching for terms like: audio, crossfade, lyrics, awake, sleep, equalizer, theme, cache...",
                                fontSize = 12.sp,
                                color = BentoTextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = { searchQuery = "" },
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                            ) {
                                Text("Clear Search", color = BentoOnPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ThemePreviewCard(
    theme: AppUiTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardBorderColor = if (isSelected) {
        theme.previewPrimary
    } else if (theme.isDark) {
        BentoBorder.copy(alpha = 0.55f)
    } else {
        Color(0xFFCBD5E1)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = theme.previewBg,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = cardBorderColor
        ),
        modifier = modifier
            .width(168.dp)
            .height(160.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Top: Theme Accent Dots and Active checkmark
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(theme.previewPrimary))
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(theme.previewAccent))
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(theme.previewSurface))
                    }
                    if (isSelected) {
                        Surface(
                            shape = CircleShape,
                            color = theme.previewPrimary,
                            modifier = Modifier.size(20.dp)
                        ) {
                            val lum = 0.299f * theme.previewPrimary.red + 0.587f * theme.previewPrimary.green + 0.114f * theme.previewPrimary.blue
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Active",
                                tint = if (lum > 0.55f) Color.Black else Color.White,
                                modifier = Modifier.padding(3.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(20.dp))
                    }
                }

                // Middle: Mockup UI Card
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = theme.previewSurface,
                    border = BorderStroke(0.75.dp, theme.previewPrimary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(theme.previewAccent)
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .height(5.dp)
                                    .clip(CircleShape)
                                    .background(if (theme.isDark) Color.White.copy(alpha = 0.9f) else Color(0xFF1E293B))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.55f)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(theme.previewPrimary)
                            )
                        }
                    }
                }

                // Bottom: Title & Subtitle with guaranteed spacing and overflow protection
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = theme.displayName,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (theme.isDark) Color.White else Color(0xFF0F172A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = theme.subtitle,
                        fontSize = 10.sp,
                        color = if (theme.isDark) Color(0xFFA1A1AA) else Color(0xFF64748B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioDurationFilterSetting(
    ignoreAudioDuration: Int,
    onSetIgnoreAudioDuration: (Int) -> Unit
) {
    val presets = listOf(
        0 to "All Audio",
        15 to "15s",
        30 to "30s",
        45 to "45s",
        60 to "1m",
        120 to "2m"
    )

    var currentSliderValue by remember(ignoreAudioDuration) {
        mutableFloatStateOf(ignoreAudioDuration.toFloat().coerceIn(0f, 180f))
    }
    var isDragging by remember { mutableStateOf(false) }

    val displayDuration = (if (isDragging) currentSliderValue.roundToInt() else ignoreAudioDuration).coerceIn(0, 180)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(BentoPrimary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Column {
                    Text(
                        text = "Filter Short Audio Clips",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "Ignore audio files shorter than the selected duration during library scan",
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (displayDuration > 0) BentoPrimary.copy(alpha = 0.16f) else BentoCardDarkPurple)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = when {
                        displayDuration == 0 -> "All Files"
                        displayDuration < 60 -> "< ${displayDuration}s"
                        displayDuration % 60 == 0 -> "< ${displayDuration / 60}m"
                        else -> "< ${displayDuration / 60}m ${displayDuration % 60}s"
                    },
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (displayDuration > 0) BentoPrimary else BentoTextSecondary
                )
            }
        }

        // Quick Preset Chips (0s, 15s, 30s, 45s, 1m, 2m)
        val filterContext = LocalContext.current
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(presets, key = { it.first }, contentType = { "duration_chip" }) { (sec, label) ->
                val isSelected = displayDuration == sec
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) BentoPrimary else BentoCardPurple.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.5f)),
                    modifier = Modifier.clickable {
                        HapticUtils.performClickHaptic(filterContext)
                        isDragging = false
                        currentSliderValue = sec.toFloat()
                        onSetIgnoreAudioDuration(sec)
                    }
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) BentoOnPrimary else BentoTextSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // Continuous Smooth Slider for custom durations up to 180s (3m)
        Slider(
            value = currentSliderValue,
            onValueChange = { newVal ->
                isDragging = true
                currentSliderValue = newVal
            },
            onValueChangeFinished = {
                isDragging = false
                val finalSec = currentSliderValue.roundToInt().coerceIn(0, 180)
                onSetIgnoreAudioDuration(finalSec)
            },
            valueRange = 0f..180f,
            colors = SliderDefaults.colors(
                thumbColor = BentoPrimary,
                activeTrackColor = BentoPrimary,
                inactiveTrackColor = BentoBorder.copy(alpha = 0.65f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingsSubGroupLabel(
    title: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 6.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(BentoPrimary)
        )
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            color = BentoTextSecondary
        )
    }
}

