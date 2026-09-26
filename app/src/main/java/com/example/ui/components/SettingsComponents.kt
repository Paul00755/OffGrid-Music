package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * Subtle and elegant section header with minimal icon badge,
 * gentle category caption, refined title, and clear subtitle.
 */
@Composable
fun SettingsSectionHeader(
    title: String,
    subtitle: String,
    kicker: String,
    icon: ImageVector,
    accentColor: Color = BentoPrimary,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accentColor.copy(alpha = 0.10f))
                .border(
                    BorderStroke(1.dp, accentColor.copy(alpha = 0.20f)),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            if (kicker.isNotBlank()) {
                Text(
                    text = kicker.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor.copy(alpha = 0.85f),
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = BentoTextPrimary,
                letterSpacing = (-0.1).sp
            )
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = BentoTextSecondary,
                    maxLines = 2,
                    lineHeight = 16.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Subtle and elegant Bento card container with clean surface,
 * delicate border, and whisper-soft accent lighting.
 */
@Composable
fun SettingsCardContainer(
    modifier: Modifier = Modifier,
    accentGlow: Color = BentoPrimary,
    shapeRadius: Dp = 18.dp,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = remember(shapeRadius) { RoundedCornerShape(shapeRadius) }
    val borderColor = BentoBorder
    val borderBrush = remember(accentGlow, borderColor) {
        Brush.verticalGradient(
            listOf(
                accentGlow.copy(alpha = 0.22f),
                borderColor.copy(alpha = 0.28f),
                accentGlow.copy(alpha = 0.08f)
            )
        )
    }
    val backgroundBrush = remember(accentGlow) {
        Brush.verticalGradient(
            listOf(
                accentGlow.copy(alpha = 0.035f),
                Color.Transparent
            ),
            startY = 0f,
            endY = 220f
        )
    }

    Surface(
        shape = shape,
        color = BentoCardSurface,
        border = BorderStroke(1.dp, borderBrush),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundBrush)
        ) {
            Column(
                modifier = Modifier.padding(padding),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content
            )
        }
    }
}

/**
 * Sleek interactive toggle row with icon avatar, title, subtitle, and modern switch.
 */
@Composable
fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    accentColor: Color = BentoPrimary,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = { onCheckedChange(!checked) }
            )
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (icon != null) {
                val iconBg by animateColorAsState(
                    targetValue = if (checked) accentColor.copy(alpha = 0.12f) else BentoCardPurple.copy(alpha = 0.5f),
                    animationSpec = tween(200),
                    label = "icon_bg"
                )
                val iconBorder by animateColorAsState(
                    targetValue = if (checked) accentColor.copy(alpha = 0.28f) else BentoBorder.copy(alpha = 0.25f),
                    animationSpec = tween(200),
                    label = "icon_border"
                )
                val iconTint by animateColorAsState(
                    targetValue = if (checked) accentColor else BentoTextSecondary,
                    animationSpec = tween(200),
                    label = "icon_tint"
                )

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconBg)
                        .border(BorderStroke(1.dp, iconBorder), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = BentoTextPrimary
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = subtitle,
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BentoOnPrimary,
                checkedTrackColor = accentColor,
                uncheckedThumbColor = BentoTextSecondary,
                uncheckedTrackColor = BentoCardPurple.copy(alpha = 0.6f),
                uncheckedBorderColor = BentoBorder.copy(alpha = 0.4f)
            )
        )
    }
}

/**
 * Modern slider row with icon avatar, title, description, real-time value pill badge, and custom track.
 */
@Composable
fun SettingsSliderRow(
    title: String,
    subtitle: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDisplay: String,
    steps: Int = 0,
    icon: ImageVector? = null,
    accentColor: Color = BentoPrimary,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accentColor.copy(alpha = 0.10f))
                            .border(BorderStroke(1.dp, accentColor.copy(alpha = 0.22f)), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BentoTextPrimary
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary
                        )
                    }
                }
            }

            // Subtle numeric pill badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = accentColor.copy(alpha = 0.10f),
                border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.25f))
            ) {
                Text(
                    text = valueDisplay,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.5.dp)
                )
            }
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = BentoCardPurple.copy(alpha = 0.6f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Segmented Pill selector (e.g. for Display Theme, Navigation Modes, etc.)
 */
@Composable
fun <T> SettingsSegmentedSelector(
    options: List<Pair<T, String>>,
    selectedOption: T,
    onSelectOption: (T) -> Unit,
    enabledOptions: Set<T>? = null,
    accentColor: Color = BentoPrimary,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BentoCardPurple.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            options.forEach { (option, label) ->
                val isSelected = option == selectedOption
                val isEnabled = enabledOptions == null || enabledOptions.contains(option)
                val animBg by animateColorAsState(
                    targetValue = if (isSelected) accentColor else Color.Transparent,
                    animationSpec = tween(150),
                    label = "pill_bg"
                )

                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = animBg,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (isEnabled) {
                                Modifier.clickable { onSelectOption(option) }
                            } else {
                                Modifier.alpha(0.35f)
                            }
                        )
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                !isEnabled -> BentoTextSecondary.copy(alpha = 0.5f)
                                isSelected -> BentoOnPrimary
                                else -> BentoTextPrimary
                            },
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sleek interactive action row with optional icon, title, description, and trailing component.
 */
@Composable
fun SettingsActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    accentColor: Color = BentoPrimary,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = ripple(),
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.10f))
                        .border(BorderStroke(1.dp, accentColor.copy(alpha = 0.22f)), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = BentoTextPrimary
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = subtitle,
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        trailingContent()
    }
}

/**
 * Subtle horizontal divider with low opacity
 */
@Composable
fun SettingsDivider(
    color: Color = BentoBorder.copy(alpha = 0.22f),
    modifier: Modifier = Modifier
) {
    HorizontalDivider(
        color = color,
        thickness = 0.8.dp,
        modifier = modifier
    )
}
