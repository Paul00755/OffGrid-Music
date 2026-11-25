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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import com.example.util.HapticUtils

private const val AI_STUDIO_DOCS_API_KEY_URL = "https://aistudio.google.com/docs/api-key"
private const val AI_STUDIO_API_KEYS_URL = "https://aistudio.google.com/api-keys"

/**
 * Step-by-step instructions dialog showing users how to find or generate a Gemini API key
 * and reassuring them about on-device privacy.
 */
@Composable
fun GeminiApiKeyGuideDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val isDarkSurface = BentoCardSurface.luminance() < 0.5f
    val cardBg = if (isDarkSurface) BentoBackground.copy(alpha = 0.62f) else BentoCardPurple.copy(alpha = 0.38f)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 460.dp)
                .wrapContentHeight()
                .padding(vertical = 20.dp)
                .testTag("gemini_api_key_guide_dialog"),
            shape = RoundedCornerShape(26.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BentoPrimary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "How to Find & Add Your API Key",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "4 quick steps to set up Lyrics Transcribe & Translate",
                            fontSize = 12.sp,
                            color = BentoTextSecondary
                        )
                    }
                }

                // Steps Container
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = cardBg,
                    border = BorderStroke(0.75.dp, BentoBorder.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 410.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ApiKeyGuideStepRow(
                            stepNumber = "1",
                            text = "Visit the Google AI Studio API Key guide:",
                            linkLabel = AI_STUDIO_DOCS_API_KEY_URL,
                            onLinkClick = {
                                HapticUtils.performClickHaptic(context)
                                runCatching { uriHandler.openUri(AI_STUDIO_DOCS_API_KEY_URL) }
                            }
                        )

                        ApiKeyGuideStepRow(
                            stepNumber = "2",
                            text = "Click \"Create or view a Gemini API key\" on that page."
                        )

                        ApiKeyGuideStepRow(
                            stepNumber = "3",
                            text = "Once redirected to the API Keys page, click \"Create API key\" to generate a new key — or use an existing key already listed there:",
                            linkLabel = AI_STUDIO_API_KEYS_URL,
                            onLinkClick = {
                                HapticUtils.performClickHaptic(context)
                                runCatching { uriHandler.openUri(AI_STUDIO_API_KEYS_URL) }
                            }
                        )

                        ApiKeyGuideStepRow(
                            stepNumber = "4",
                            text = "Copy your API key and paste it into the Gemini API Key box in the app, then tap \"Save & Validate Key\"."
                        )

                        // Privacy Note Banner
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = BentoPrimary.copy(alpha = if (isDarkSurface) 0.12f else 0.10f),
                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.32f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(18.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "Privacy Note",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoPrimary
                                    )
                                    Text(
                                        text = "Your API key won't be backed up or stored anywhere outside this device. We also request you not to share your API key with anyone.",
                                        fontSize = 12.sp,
                                        lineHeight = 17.5.sp,
                                        color = BentoTextPrimary.copy(alpha = 0.9f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onDismiss()
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("api_key_guide_close_button")
                    ) {
                        Text(
                            text = "Got it",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiKeyGuideStepRow(
    stepNumber: String,
    text: String,
    linkLabel: String? = null,
    onLinkClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(BentoPrimary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = BentoPrimary
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                lineHeight = 18.5.sp,
                color = BentoTextPrimary.copy(alpha = 0.92f)
            )
            if (linkLabel != null && onLinkClick != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = BentoPrimary.copy(alpha = 0.13f),
                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.34f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onLinkClick)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = linkLabel,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open link",
                            tint = BentoPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
