package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BentoBackground: Color
    @Composable
    get() = MaterialTheme.colorScheme.background

val BentoCardSurface: Color
    @Composable
    get() = MaterialTheme.colorScheme.surface

val BentoCardPurple: Color
    @Composable
    get() = MaterialTheme.colorScheme.surfaceVariant

val BentoCardDarkPurple: Color
    @Composable
    get() = MaterialTheme.colorScheme.surfaceVariant

val BentoCardAccentPink: Color
    @Composable
    get() = MaterialTheme.colorScheme.primaryContainer

val BentoCardBorder: Color
    @Composable
    get() = MaterialTheme.colorScheme.outline

val BentoBorder: Color
    @Composable
    get() = MaterialTheme.colorScheme.outline

val DefaultBentoPrimary = Color(0xFF80D8FF)

val BentoPrimary: Color
    @Composable
    get() = MaterialTheme.colorScheme.primary

val BentoPrimaryContainer: Color
    @Composable
    get() = MaterialTheme.colorScheme.primaryContainer

val BentoOnPrimary: Color
    @Composable
    get() = MaterialTheme.colorScheme.onPrimary

val BentoOnPrimaryContainer: Color
    @Composable
    get() = MaterialTheme.colorScheme.onPrimaryContainer

val BentoSecondary: Color
    @Composable
    get() = MaterialTheme.colorScheme.secondary

val BentoTextPrimary: Color
    @Composable
    get() = MaterialTheme.colorScheme.onBackground

val BentoTextSecondary: Color
    @Composable
    get() = MaterialTheme.colorScheme.onSurfaceVariant

val BentoTextSubtle: Color
    @Composable
    get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)

val BentoAccentPinkText: Color
    @Composable
    get() = MaterialTheme.colorScheme.primary

val BentoAccentPinkBg: Color
    @Composable
    get() = MaterialTheme.colorScheme.primaryContainer

val BentoError: Color
    @Composable
    get() = MaterialTheme.colorScheme.error

