package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.Song
import com.example.service.transcription.AudioTranscribeMode
import com.example.service.transcription.TranscribeLanguage
import com.example.ui.theme.*
import com.example.util.HapticUtils

@Composable
fun TranscribeLanguageDialog(
    song: Song,
    initialMode: AudioTranscribeMode,
    initialLanguage: TranscribeLanguage,
    hasExistingLyrics: Boolean,
    onDismiss: () -> Unit,
    onStartOperation: (mode: AudioTranscribeMode, language: TranscribeLanguage) -> Unit
) {
    val context = LocalContext.current
    var selectedMode by remember {
        mutableStateOf(
            if (initialMode != AudioTranscribeMode.OFF) initialMode
            else if (hasExistingLyrics) AudioTranscribeMode.TRANSLATE
            else AudioTranscribeMode.TRANSCRIBE
        )
    }

    var selectedLang by remember { mutableStateOf(initialLanguage) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = BentoCardSurface,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .widthIn(max = 460.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Studio Header
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
                                .size(42.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(BentoPrimary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (selectedMode == AudioTranscribeMode.TRANSLATE) {
                                    Icons.Default.Translate
                                } else {
                                    Icons.Default.AutoAwesome
                                },
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (selectedMode == AudioTranscribeMode.TRANSLATE) "Translate Lyrics" else "Transcribe Audio",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (selectedMode == AudioTranscribeMode.TRANSLATE) {
                                    "Line-by-line synced lyrics translation"
                                } else {
                                    "Vocal recognition & timed LRC generation"
                                },
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
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(BentoCardDarkPurple)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = BentoTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // 2. Target Song Preview Banner
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = BentoCardDarkPurple,
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AlbumArtView(
                            song = song,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.displayTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = song.displayArtist,
                                fontSize = 11.5.sp,
                                color = BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = BentoPrimary.copy(alpha = 0.15f),
                            border = BorderStroke(0.8.dp, BentoPrimary.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = if (hasExistingLyrics) "LYRICS READY" else "AUDIO TRACK",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // 3. Destination Language Selection
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Destination Language",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextSecondary
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = BentoCardDarkPurple,
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "${selectedLang.englishName} • ${selectedLang.nativeName}",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoPrimary,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                            )
                        }
                    }

                    val allLanguages = TranscribeLanguage.entries
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        allLanguages.chunked(3).forEach { rowLangs ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowLangs.forEach { lang ->
                                    val isSelected = selectedLang == lang
                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            selectedLang = lang
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isSelected) BentoPrimary.copy(alpha = 0.18f) else BentoCardDarkPurple,
                                        border = BorderStroke(
                                            width = if (isSelected) 1.5.dp else 1.dp,
                                            color = if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.4f)
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = lang.englishName,
                                                    fontSize = 12.5.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                                    color = if (isSelected) BentoPrimary else BentoTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = null,
                                                        tint = BentoPrimary,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = lang.nativeName,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isSelected) BentoPrimary.copy(alpha = 0.88f) else BentoTextSecondary,
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

                // 4. Operation Selection (Transcribe Audio vs Translate Lyrics - both always selectable)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Operation",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val isTranscribeSelected = selectedMode == AudioTranscribeMode.TRANSCRIBE
                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                selectedMode = AudioTranscribeMode.TRANSCRIBE
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = if (isTranscribeSelected) BentoPrimary.copy(alpha = 0.18f) else BentoCardDarkPurple,
                            border = BorderStroke(
                                width = if (isTranscribeSelected) 1.5.dp else 1.dp,
                                color = if (isTranscribeSelected) BentoPrimary else BentoBorder.copy(alpha = 0.45f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isTranscribeSelected) BentoPrimary else BentoTextSecondary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Transcribe Audio",
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isTranscribeSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isTranscribeSelected) BentoPrimary else BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        val isTranslateSelected = selectedMode == AudioTranscribeMode.TRANSLATE
                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                selectedMode = AudioTranscribeMode.TRANSLATE
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = if (isTranslateSelected) BentoPrimary.copy(alpha = 0.18f) else BentoCardDarkPurple,
                            border = BorderStroke(
                                width = if (isTranslateSelected) 1.5.dp else 1.dp,
                                color = if (isTranslateSelected) BentoPrimary else BentoBorder.copy(alpha = 0.45f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Translate,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isTranslateSelected) BentoPrimary else BentoTextSecondary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Translate Lyrics",
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isTranslateSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isTranslateSelected) BentoPrimary else BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // 5. Operation Context Info Card
                if (selectedMode == AudioTranscribeMode.TRANSLATE) {
                    if (hasExistingLyrics) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = BentoCardDarkPurple,
                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(BentoPrimary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = null,
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Timestamps & Sync Preservation",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoPrimary
                                    )
                                    Text(
                                        text = "Original timestamps are strictly preserved. Transliterated lyrics are translated line-by-line in ${selectedLang.englishName} without modifying audio playback.",
                                        fontSize = 11.5.sp,
                                        lineHeight = 16.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = BentoAccentPinkText.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, BentoAccentPinkText.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(BentoAccentPinkText.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = BentoAccentPinkText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Lyrics Required for Translation",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoAccentPinkText
                                    )
                                    Text(
                                        text = "Translation requires existing lyrics text. No lyrics currently exist for this track. Please select 'Transcribe Audio' to generate timed lyrics from the song audio first.",
                                        fontSize = 11.5.sp,
                                        lineHeight = 16.sp,
                                        color = BentoTextPrimary
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = BentoCardDarkPurple,
                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(BentoPrimary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Audio Source: Song Audio File",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoPrimary
                                )
                                Text(
                                    text = "Vocals will be recognized directly from ${song.displayTitle} audio file to generate synchronized timed lyrics in ${selectedLang.englishName}.",
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                    color = BentoTextSecondary
                                )
                            }
                        }
                    }
                }

                // 6. Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = BentoCardDarkPurple,
                            contentColor = BentoTextPrimary
                        ),
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = "Cancel",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                    }

                    val isTranslateWithoutLyrics = selectedMode == AudioTranscribeMode.TRANSLATE && !hasExistingLyrics
                    Button(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onStartOperation(selectedMode, selectedLang)
                            onDismiss()
                        },
                        enabled = !isTranslateWithoutLyrics,
                        modifier = Modifier
                            .weight(1.35f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary,
                            disabledContainerColor = BentoCardDarkPurple,
                            disabledContentColor = BentoTextSubtle
                        )
                    ) {
                        Icon(
                            imageVector = if (selectedMode == AudioTranscribeMode.TRANSLATE) {
                                Icons.Default.Translate
                            } else {
                                Icons.Default.AutoAwesome
                            },
                            contentDescription = null,
                            tint = if (isTranslateWithoutLyrics) BentoTextSubtle else BentoOnPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (selectedMode == AudioTranscribeMode.TRANSLATE) {
                                if (hasExistingLyrics) "Translate" else "Lyrics Required"
                            } else "Transcribe",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isTranslateWithoutLyrics) BentoTextSubtle else BentoOnPrimary
                        )
                    }
                }
            }
        }
    }
}
