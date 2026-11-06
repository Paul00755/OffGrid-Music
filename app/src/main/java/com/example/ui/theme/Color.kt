package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

val BentoBackground: Color
    @Composable
    get() = MaterialTheme.colorScheme.background

val BentoScreenBackground: Color
    @Composable
    get() = if (LocalGlassThemeConfig.current.enabled) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.background
    }

val BentoCardSurface: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        val base = MaterialTheme.colorScheme.surface
        return if (glass.enabled) {
            lerp(base, glass.tintColor, if (glass.isDark) 0.14f else 0.08f)
                .copy(alpha = if (glass.isDark) 0.68f else 0.80f)
        } else {
            base
        }
    }

val BentoCardPurple: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        val base = MaterialTheme.colorScheme.surfaceVariant
        return if (glass.enabled) {
            lerp(base, glass.tintColor, if (glass.isDark) 0.18f else 0.10f)
                .copy(alpha = if (glass.isDark) 0.72f else 0.82f)
        } else {
            base
        }
    }

val BentoCardDarkPurple: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        val base = MaterialTheme.colorScheme.surfaceVariant
        return if (glass.enabled) {
            lerp(base, glass.secondaryTintColor, if (glass.isDark) 0.16f else 0.09f)
                .copy(alpha = if (glass.isDark) 0.74f else 0.84f)
        } else {
            base
        }
    }

val BentoCardAccentPink: Color
    @Composable
    get() = MaterialTheme.colorScheme.primaryContainer

val BentoCardBorder: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        val base = MaterialTheme.colorScheme.outline
        return if (glass.enabled) {
            lerp(base, glass.tintColor, 0.48f)
                .copy(alpha = if (glass.isDark) 0.46f else 0.40f)
        } else {
            base
        }
    }

val BentoBorder: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        val base = MaterialTheme.colorScheme.outline
        return if (glass.enabled) {
            lerp(base, glass.tintColor, 0.45f)
                .copy(alpha = if (glass.isDark) 0.44f else 0.38f)
        } else {
            base
        }
    }

val DefaultBentoPrimary = Color(0xFFFF9100)

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

