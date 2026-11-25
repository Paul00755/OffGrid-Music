package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.util.HapticUtils

const val OFFGRID_SUPPORT_EMAIL = "offgridmusicsupport@gmail.com"
const val BUY_ME_A_COFFEE_URL = "https://buymeacoffee.com/paulrajmes9"

internal fun buildBuyMeACoffeeIntent(url: String = BUY_ME_A_COFFEE_URL): Intent {
    return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

internal fun openBuyMeACoffeePage(context: Context, url: String = BUY_ME_A_COFFEE_URL) {
    try {
        context.startActivity(buildBuyMeACoffeeIntent(url))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "No browser found to open $url",
            Toast.LENGTH_LONG
        ).show()
    } catch (_: Exception) {
        Toast.makeText(
            context,
            "Could not open link. Visit $url in your browser.",
            Toast.LENGTH_LONG
        ).show()
    }
}

internal fun openSupportEmailIntent(context: Context) {
    val mailtoUri = Uri.parse(
        "mailto:$OFFGRID_SUPPORT_EMAIL?subject=${Uri.encode("OffGrid Music — Query / Bug Report / Feature Suggestion")}"
    )
    val intent = Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Support Email", OFFGRID_SUPPORT_EMAIL))
        Toast.makeText(
            context,
            "No email app found. Copied $OFFGRID_SUPPORT_EMAIL to clipboard.",
            Toast.LENGTH_LONG
        ).show()
    } catch (_: Exception) {
        Toast.makeText(
            context,
            "Please email us at $OFFGRID_SUPPORT_EMAIL",
            Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
fun OtherSupportFeedbackCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = BentoCardSurface,
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(BentoPrimary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Queries, Bug Reports & Feature Suggestions",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = OFFGRID_SUPPORT_EMAIL,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = BentoPrimary
                    )
                }
            }

            Text(
                text = "If you have any queries, face any bugs, or have suggestions for a new feature, send an email to $OFFGRID_SUPPORT_EMAIL and we will try to fulfill your request as soon as possible. If you face any errors, please try to attach an image or screenshot so we can help faster.",
                fontSize = 12.sp,
                color = BentoTextSecondary,
                lineHeight = 17.5.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        openSupportEmailIntent(context)
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BentoPrimary,
                        contentColor = BentoOnPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("settings_send_support_email_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MailOutline,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Send Email",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Support Email", OFFGRID_SUPPORT_EMAIL))
                        Toast.makeText(context, "Copied $OFFGRID_SUPPORT_EMAIL", Toast.LENGTH_SHORT).show()
                    },
                    shape = CircleShape,
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoTextPrimary),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("settings_copy_support_email_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy email address",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Copy",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun OtherFaqEntryCard(
    onOpenFaq: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = BentoCardSurface,
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable {
                HapticUtils.performClickHaptic(context)
                onOpenFaq()
            }
            .testTag("settings_open_faq_button")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(BentoPrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = BentoPrimary,
                    modifier = Modifier.size(21.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "Frequently Asked Questions (FAQ)",
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
                Text(
                    text = "App safety, Google Sign-In, cloud backups, Gemini API key help & more",
                    fontSize = 12.sp,
                    color = BentoTextSecondary,
                    lineHeight = 16.5.sp
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open FAQ",
                tint = BentoTextSubtle,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun SupportDeveloperContent(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val supportHelpBullets = listOf(
        "My educational expenses and study materials.",
        "Personal and day-to-day expenses.",
        "Internet, development tools and other project-related costs.",
        "The time and resources needed to maintain and improve OffGrid Music."
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Main Story & Message Card
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Headline + Intro
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "A little support goes a long way.",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary,
                        lineHeight = 26.sp
                    )
                    Text(
                        text = "OffGrid Music is something I've been building with passion, time and a genuine love for music. I'm an independent student developer, working to create a music player that people can enjoy without unnecessary ads, subscriptions or restrictions.",
                        fontSize = 13.5.sp,
                        color = BentoTextSecondary,
                        lineHeight = 20.sp
                    )
                }

                HorizontalDivider(thickness = 0.8.dp, color = BentoBorder.copy(alpha = 0.35f))

                // A little about me
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "A little about me",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "Behind this app is a student trying to balance academics, personal responsibilities and independent software development.",
                        fontSize = 13.sp,
                        color = BentoTextSecondary,
                        lineHeight = 19.5.sp
                    )
                    Text(
                        text = "My family is currently going through a financially unstable period. I'm working towards becoming more financially independent and helping my family, while also managing my own personal and educational expenses.",
                        fontSize = 13.sp,
                        color = BentoTextSecondary,
                        lineHeight = 19.5.sp
                    )
                    Text(
                        text = "Developing and maintaining OffGrid Music takes considerable time, effort and resources. I don't want to put essential features behind a paywall or make donations a requirement to enjoy the app.",
                        fontSize = 13.sp,
                        color = BentoTextSecondary,
                        lineHeight = 19.5.sp
                    )
                }

                HorizontalDivider(thickness = 0.8.dp, color = BentoBorder.copy(alpha = 0.35f))

                // How your support helps
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "How your support helps",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "Even a small contribution can make a meaningful difference. Your support can help me manage:",
                        fontSize = 13.sp,
                        color = BentoTextSecondary,
                        lineHeight = 19.5.sp
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        supportHelpBullets.forEach { bullet ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 7.dp)
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(BentoPrimary)
                                )
                                Text(
                                    text = bullet,
                                    fontSize = 13.sp,
                                    color = BentoTextSecondary,
                                    lineHeight = 19.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(thickness = 0.8.dp, color = BentoBorder.copy(alpha = 0.35f))

                // No pressure. No obligations.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "No pressure. No obligations.",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "If you enjoy using OffGrid Music and feel like supporting the person behind it, even a small contribution would genuinely mean a lot to me.",
                        fontSize = 13.sp,
                        color = BentoTextSecondary,
                        lineHeight = 19.5.sp
                    )
                    Text(
                        text = "If you cannot donate, that's completely okay. Using the app, sharing it with others, reporting bugs and suggesting improvements are already wonderful ways to support this project.",
                        fontSize = 13.sp,
                        color = BentoTextSecondary,
                        lineHeight = 19.5.sp
                    )
                }
            }
        }

        // Appreciation & Buy Me a Coffee Action Card
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.36f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Thank you for being part of OffGrid.",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Every bit of support is appreciated. You're helping an independent student developer keep building something meaningful.",
                    fontSize = 12.5.sp,
                    color = BentoTextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Buy me a coffee",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Optional support · Every contribution counts",
                    fontSize = 11.5.sp,
                    color = BentoTextSubtle,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        openBuyMeACoffeePage(context)
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BentoPrimary,
                        contentColor = BentoOnPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("support_developer_buy_me_a_coffee_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalCafe,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Buy Me a Coffee",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Text(
                    text = "Opens in your external browser · 100% optional",
                    fontSize = 11.sp,
                    color = BentoTextSubtle,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
