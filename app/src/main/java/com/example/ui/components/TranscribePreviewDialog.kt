package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.transcription.AudioTranscribeMode
import com.example.service.transcription.TranscribeLyricsPreview
import com.example.ui.theme.*
import com.example.util.HapticUtils
import com.example.util.LrcParser

@Composable
fun TranscribePreviewDialog(
    preview: TranscribeLyricsPreview,
    onSave: (TranscribeLyricsPreview) -> Unit,
    onDiscard: () -> Unit,
    onRetranscribe: ((TranscribeLyricsPreview) -> Unit)? = null
) {
    val context = LocalContext.current
    val isTranslate = preview.mode == AudioTranscribeMode.TRANSLATE

    val initialFormatted = remember(preview) {
        if (!preview.syncedLyricsJson.isNullOrBlank()) {
            LrcParser.formatSyncedJsonToLrc(preview.syncedLyricsJson) ?: preview.plainLyrics
        } else {
            preview.plainLyrics
        }
    }

    var editableLyrics by remember(preview) { mutableStateOf(initialFormatted) }
    var isEditMode by remember { mutableStateOf(false) }

    // Live parsed lines for visual display
    val parsedSyncedLines: List<Pair<Long, String>> = remember(editableLyrics) {
        val (plain, syncedJson) = LrcParser.parseLrcContent(editableLyrics)
        val lyrics = com.example.data.model.Lyrics(
            songId = preview.song.id,
            plainLyrics = plain ?: editableLyrics,
            syncedLyricsJson = syncedJson
        )
        lyrics.parseSyncedLines()
    }

    val isModified = editableLyrics.trim() != initialFormatted.trim()

    Dialog(
        onDismissRequest = onDiscard,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = BentoCardSurface,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .widthIn(max = 480.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(BentoPrimary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isTranslate) Icons.Default.Translate else Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (isTranslate) "Lyrics Translated" else "Lyrics Transcribed",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = if (parsedSyncedLines.isNotEmpty()) BentoPrimary.copy(alpha = 0.18f) else BentoCardDarkPurple,
                                    border = BorderStroke(0.8.dp, if (parsedSyncedLines.isNotEmpty()) BentoPrimary.copy(alpha = 0.4f) else BentoBorder.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = if (parsedSyncedLines.isNotEmpty()) "SYNCED" else "PLAIN TEXT",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (parsedSyncedLines.isNotEmpty()) BentoPrimary else BentoTextSecondary,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "${preview.song.displayTitle} • ${preview.targetLanguage.englishName}",
                                fontSize = 12.sp,
                                color = BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onDiscard()
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(BentoCardDarkPurple)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Discard",
                            tint = BentoTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Subheader with Edit / Preview Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isEditMode) "Edit Lyrics Box (Tap to type)" else "Preview",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEditMode) BentoPrimary else BentoTextSecondary
                        )
                        if (isModified) {
                            Surface(
                                shape = CircleShape,
                                color = BentoPrimary.copy(alpha = 0.18f)
                            ) {
                                Text(
                                    text = "MODIFIED",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isModified) {
                            TextButton(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    editableLyrics = initialFormatted
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = BentoTextSecondary, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reset", fontSize = 11.sp, color = BentoTextSecondary)
                            }
                        }

                        // Toggle Edit / Preview button
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isEditMode) BentoPrimary.copy(alpha = 0.18f) else BentoCardDarkPurple,
                            border = BorderStroke(1.dp, if (isEditMode) BentoPrimary else BentoBorder.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    HapticUtils.performClickHaptic(context)
                                    isEditMode = !isEditMode
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (isEditMode) Icons.Default.Visibility else Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = if (isEditMode) BentoPrimary else BentoTextPrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (isEditMode) "Preview" else "Edit Lyrics",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isEditMode) BentoPrimary else BentoTextPrimary
                                )
                            }
                        }
                    }
                }

                // Lyrics Preview & Editing Area
                if (isEditMode) {
                    OutlinedTextField(
                        value = editableLyrics,
                        onValueChange = { editableLyrics = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 260.dp),
                        shape = RoundedCornerShape(14.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = BentoTextPrimary,
                            lineHeight = 18.sp
                        ),
                        placeholder = {
                            Text("Type or paste lyrics lines here...", color = BentoTextSecondary.copy(alpha = 0.5f), fontSize = 12.sp)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BentoPrimary,
                            unfocusedBorderColor = BentoBorder.copy(alpha = 0.6f),
                            focusedContainerColor = BentoCardDarkPurple,
                            unfocusedContainerColor = BentoCardDarkPurple,
                            cursorColor = BentoPrimary
                        )
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = BentoCardDarkPurple,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 260.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { isEditMode = true }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (parsedSyncedLines.isNotEmpty()) {
                                parsedSyncedLines.forEach { line ->
                                    val totalSec = line.first / 1000
                                    val min = totalSec / 60
                                    val sec = totalSec % 60
                                    val ms = (line.first % 1000) / 10
                                    val timeTag = String.format(java.util.Locale.US, "[%02d:%02d.%02d]", min, sec, ms)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = timeTag,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoPrimary
                                        )
                                        Text(
                                            text = line.second,
                                            fontSize = 12.5.sp,
                                            color = BentoTextPrimary
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = editableLyrics.ifBlank { "No lyrics content. Tap 'Edit Lyrics' to write lines." },
                                    fontSize = 12.5.sp,
                                    color = BentoTextPrimary,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }

                // Help caption
                Text(
                    text = if (isEditMode) "You can edit, add, or delete lyric lines and timestamps above. Click Save to apply changes." else "Review or tap 'Edit Lyrics' to modify any lines before saving.",
                    fontSize = 11.sp,
                    color = BentoTextSecondary,
                    lineHeight = 15.sp
                )

                // Actions: Discard/Undo, Re-transcribe, and Save
                if (onRetranscribe != null) {
                    OutlinedButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            val (parsedPlain, parsedSynced) = LrcParser.parseLrcContent(editableLyrics)
                            val currentPreview = preview.copy(
                                plainLyrics = parsedPlain ?: editableLyrics.trim(),
                                syncedLyricsJson = parsedSynced ?: if (editableLyrics.contains("[")) preview.syncedLyricsJson else null
                            )
                            onRetranscribe(currentPreview)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = BentoCardDarkPurple,
                            contentColor = BentoPrimary
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isTranslate) "Re-translate with Corrections" else "Re-transcribe with Corrections",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onDiscard()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, BentoAccentPinkText.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = BentoAccentPinkText.copy(alpha = 0.12f),
                            contentColor = BentoAccentPinkText
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Discard / Undo", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            val (parsedPlain, parsedSynced) = LrcParser.parseLrcContent(editableLyrics)
                            val finalPlain = parsedPlain ?: editableLyrics.trim()
                            val finalSynced = parsedSynced ?: if (editableLyrics.contains("[")) preview.syncedLyricsJson else null
                            val finalPreview = preview.copy(
                                plainLyrics = finalPlain,
                                syncedLyricsJson = finalSynced
                            )
                            onSave(finalPreview)
                        },
                        modifier = Modifier
                            .weight(1.25f)
                            .height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = BentoOnPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isModified) "Save Edited" else "Save Lyrics",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoOnPrimary
                        )
                    }
                }
            }
        }
    }
}
