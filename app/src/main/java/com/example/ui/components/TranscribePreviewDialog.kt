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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.service.transcription.AudioTranscribeMode
import com.example.service.transcription.TranscribeLyricsPreview
import com.example.ui.theme.*
import com.example.util.LrcParser

@Composable
fun TranscribePreviewDialog(
    preview: TranscribeLyricsPreview,
    onSave: (TranscribeLyricsPreview) -> Unit,
    onDiscard: () -> Unit
) {
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

    Dialog(onDismissRequest = onDiscard) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
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
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF00E5FF).copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isTranslate) Icons.Default.Translate else Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (isTranslate) "Lyrics Translated" else "Lyrics Transcribed",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = if (parsedSyncedLines.isNotEmpty()) Color(0xFF00E5FF).copy(alpha = 0.2f) else BentoCardPurple
                                ) {
                                    Text(
                                        text = if (parsedSyncedLines.isNotEmpty()) "SYNCED" else "PLAIN TEXT",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (parsedSyncedLines.isNotEmpty()) Color(0xFF00E5FF) else BentoTextSecondary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "${preview.song.displayTitle} • ${preview.targetLanguage.englishName}",
                                fontSize = 11.5.sp,
                                color = BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDiscard,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(BentoCardPurple)
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
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEditMode) Color(0xFF00E5FF) else BentoTextSecondary
                        )
                        if (isModified) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFFFB300).copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "MODIFIED",
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFB300),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
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
                                onClick = { editableLyrics = initialFormatted },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = BentoTextSecondary, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Reset", fontSize = 10.5.sp, color = BentoTextSecondary)
                            }
                        }

                        // Toggle Edit / Preview button
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isEditMode) Color(0xFF00E5FF).copy(alpha = 0.15f) else BentoCardPurple,
                            border = BorderStroke(1.dp, if (isEditMode) Color(0xFF00E5FF).copy(alpha = 0.5f) else BentoBorder.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { isEditMode = !isEditMode }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isEditMode) Icons.Default.Visibility else Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = if (isEditMode) Color(0xFF00E5FF) else BentoTextPrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (isEditMode) "Preview" else "Edit Lyrics",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isEditMode) Color(0xFF00E5FF) else BentoTextPrimary
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
                        shape = RoundedCornerShape(12.dp),
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
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = BentoBorder.copy(alpha = 0.6f),
                            focusedContainerColor = BentoCardDarkPurple,
                            unfocusedContainerColor = BentoCardDarkPurple,
                            cursorColor = Color(0xFF00E5FF)
                        )
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BentoCardDarkPurple,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 260.dp)
                            .clip(RoundedCornerShape(12.dp))
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
                                            color = Color(0xFF00E5FF).copy(alpha = 0.8f)
                                        )
                                        Text(
                                            text = line.second,
                                            fontSize = 12.sp,
                                            color = BentoTextPrimary
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = editableLyrics.ifBlank { "No lyrics content. Tap 'Edit Lyrics' to write lines." },
                                    fontSize = 12.sp,
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
                    fontSize = 10.5.sp,
                    color = BentoTextSecondary,
                    lineHeight = 14.sp
                )

                // Actions: Save or Discard/Undo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDiscard,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Discard / Undo", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            val (parsedPlain, parsedSynced) = LrcParser.parseLrcContent(editableLyrics)
                            val finalPlain = parsedPlain ?: editableLyrics.trim()
                            val finalSynced = parsedSynced ?: if (editableLyrics.contains("[")) preview.syncedLyricsJson else null
                            val finalPreview = preview.copy(
                                plainLyrics = finalPlain,
                                syncedLyricsJson = finalSynced
                            )
                            onSave(finalPreview)
                        },
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(if (isModified) "Save Edited" else "Save Lyrics", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }
    }
}
