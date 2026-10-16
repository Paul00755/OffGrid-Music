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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BentoCardBorder
import com.example.ui.theme.BentoCardSurface

@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    gradient: Brush? = null,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
    shape: Shape = RoundedCornerShape(14.dp),
    padding: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val clickableModifier = if (onClick != null) {
        modifier
            .clip(shape)
            .clickable { onClick() }
    } else {
        modifier.clip(shape)
    }

    val backgroundModifier = if (gradient != null) {
        clickableModifier.background(gradient, shape)
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
