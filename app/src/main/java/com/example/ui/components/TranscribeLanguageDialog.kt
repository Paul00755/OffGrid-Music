package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.Song
import com.example.service.transcription.AudioTranscribeMode
import com.example.service.transcription.TranscribeLanguage
import com.example.ui.theme.*

@Composable
fun TranscribeLanguageDialog(
    song: Song,
    initialMode: AudioTranscribeMode,
    initialLanguage: TranscribeLanguage,
    hasExistingLyrics: Boolean,
    onDismiss: () -> Unit,
    onStartOperation: (mode: AudioTranscribeMode, language: TranscribeLanguage) -> Unit
) {
    var selectedMode by remember {
        mutableStateOf(
            if (initialMode != AudioTranscribeMode.OFF) initialMode
            else if (hasExistingLyrics) AudioTranscribeMode.TRANSLATE
            else AudioTranscribeMode.TRANSCRIBE
        )
    }

    var selectedLang by remember { mutableStateOf(initialLanguage) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.7f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                                .background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (selectedMode == AudioTranscribeMode.TRANSLATE) Icons.Default.Translate else Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = if (selectedMode == AudioTranscribeMode.TRANSLATE) "Translate Lyrics" else "Transcribe Audio",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = song.displayTitle,
                                fontSize = 11.5.sp,
                                color = BentoTextSecondary,
                                maxLines = 1
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = BentoTextSecondary, modifier = Modifier.size(18.dp))
                    }
                }

                // 1. Destination Language Selection
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Destination Language",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TranscribeLanguage.entries.forEach { lang ->
                            val isSelected = selectedLang == lang
                            Surface(
                                onClick = { selectedLang = lang },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.22f) else BentoCardPurple,
                                border = BorderStroke(1.dp, if (isSelected) Color(0xFF00E5FF) else BentoBorder.copy(alpha = 0.4f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = lang.englishName,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color(0xFF00E5FF) else BentoTextPrimary
                                    )
                                    Text(
                                        text = lang.nativeName,
                                        fontSize = 10.sp,
                                        color = if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.85f) else BentoTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Operation Selection (Transcribe Audio vs Translate Lyrics - both always selectable)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Operation",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = { selectedMode = AudioTranscribeMode.TRANSCRIBE },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = if (selectedMode == AudioTranscribeMode.TRANSCRIBE) Color(0xFF00E5FF).copy(alpha = 0.2f) else BentoCardPurple,
                            border = BorderStroke(1.dp, if (selectedMode == AudioTranscribeMode.TRANSCRIBE) Color(0xFF00E5FF) else BentoBorder.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (selectedMode == AudioTranscribeMode.TRANSCRIBE) Color(0xFF00E5FF) else BentoTextSecondary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Transcribe Audio",
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedMode == AudioTranscribeMode.TRANSCRIBE) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedMode == AudioTranscribeMode.TRANSCRIBE) Color(0xFF00E5FF) else BentoTextPrimary
                                )
                            }
                        }

                        Surface(
                            onClick = { selectedMode = AudioTranscribeMode.TRANSLATE },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = if (selectedMode == AudioTranscribeMode.TRANSLATE) Color(0xFF00E5FF).copy(alpha = 0.2f) else BentoCardPurple,
                            border = BorderStroke(1.dp, if (selectedMode == AudioTranscribeMode.TRANSLATE) Color(0xFF00E5FF) else BentoBorder.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Translate,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (selectedMode == AudioTranscribeMode.TRANSLATE) Color(0xFF00E5FF) else BentoTextSecondary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Translate Lyrics",
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedMode == AudioTranscribeMode.TRANSLATE) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedMode == AudioTranscribeMode.TRANSLATE) Color(0xFF00E5FF) else BentoTextPrimary
                                )
                            }
                        }
                    }
                }

                // 3. Operation Context Notes
                if (selectedMode == AudioTranscribeMode.TRANSLATE) {
                    if (hasExistingLyrics) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = BentoCardPurple.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "⏱️ Timestamps & Sync Preservation",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E5FF)
                                )
                                Text(
                                    text = "Original timestamps are strictly preserved. Transliterated lyrics are translated line-by-line in ${selectedLang.englishName} without modifying audio playback.",
                                    fontSize = 11.sp,
                                    color = BentoTextSecondary
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFFAB40).copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Color(0xFFFFAB40).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "ℹ️ Lyrics Required for Translation",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFAB40)
                                )
                                Text(
                                    text = "Translation requires existing lyrics text. No lyrics currently exist for this track. Please select 'Transcribe Audio' to generate timed lyrics from the song audio first.",
                                    fontSize = 11.sp,
                                    color = BentoTextPrimary
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = BentoCardPurple.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "🎵 Audio Source: Song Audio File",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF)
                            )
                            Text(
                                text = "Vocals will be recognized directly from ${song.displayTitle} audio file to generate synchronized timed lyrics in ${selectedLang.englishName}.",
                                fontSize = 11.sp,
                                color = BentoTextSecondary
                            )
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel", color = BentoTextSecondary)
                    }

                    val isTranslateWithoutLyrics = selectedMode == AudioTranscribeMode.TRANSLATE && !hasExistingLyrics
                    Button(
                        onClick = {
                            onStartOperation(selectedMode, selectedLang)
                            onDismiss()
                        },
                        enabled = !isTranslateWithoutLyrics,
                        modifier = Modifier.weight(1.3f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            disabledContainerColor = BentoCardPurple
                        )
                    ) {
                        Icon(
                            imageVector = if (selectedMode == AudioTranscribeMode.TRANSLATE) Icons.Default.Translate else Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = if (isTranslateWithoutLyrics) BentoTextSubtle else Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (selectedMode == AudioTranscribeMode.TRANSLATE) {
                                if (hasExistingLyrics) "Translate" else "Lyrics Required"
                            } else "Transcribe",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isTranslateWithoutLyrics) BentoTextSubtle else Color.Black
                        )
                    }
                }
            }
        }
    }
}
