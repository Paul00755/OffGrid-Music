package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BentoCardBorder
import com.example.ui.theme.BentoCardSurface
import com.example.ui.theme.LocalGlassThemeConfig

@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = BentoCardSurface,
    gradient: Brush? = null,
    borderColor: Color = BentoCardBorder.copy(alpha = 0.36f),
    shape: Shape = RoundedCornerShape(14.dp),
    padding: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val glassConfig = LocalGlassThemeConfig.current
    val clickableModifier = if (onClick != null) {
        modifier
            .clip(shape)
            .clickable { onClick() }
    } else {
        modifier.clip(shape)
    }

    val effectiveGlassBrush = if (gradient != null) {
        gradient
    } else if (glassConfig.gradientEnabled) {
        remember(
            backgroundColor,
            glassConfig.tintColor,
            glassConfig.secondaryTintColor,
            glassConfig.isDark,
            glassConfig.glassEnabled
        ) {
            val topAlpha = if (glassConfig.glassEnabled) {
                if (glassConfig.isDark) 0.72f else 0.84f
            } else {
                1.0f
            }
            val bottomAlpha = if (glassConfig.glassEnabled) {
                if (glassConfig.isDark) 0.64f else 0.78f
            } else {
                1.0f
            }
            val topLeftTint = lerp(
                backgroundColor,
                glassConfig.tintColor,
                if (glassConfig.isDark) 0.13f else 0.08f
            ).copy(alpha = topAlpha)
            val bottomRightTint = lerp(
                backgroundColor,
                glassConfig.secondaryTintColor,
                if (glassConfig.isDark) 0.08f else 0.05f
            ).copy(alpha = bottomAlpha)
            Brush.linearGradient(
                colors = listOf(topLeftTint, backgroundColor, bottomRightTint)
            )
        }
    } else {
        null
    }

    val backgroundModifier = if (effectiveGlassBrush != null) {
        clickableModifier.background(effectiveGlassBrush, shape)
    } else {
        clickableModifier.background(backgroundColor, shape)
    }

    Box(
        modifier = backgroundModifier
            .border(1.dp, borderColor, shape)
            .padding(padding)
    ) {
        content()
    }
}

