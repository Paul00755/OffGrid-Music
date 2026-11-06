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
    get() = if (LocalGlassThemeConfig.current.gradientEnabled) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.background
    }

val BentoCardSurface: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        val base = MaterialTheme.colorScheme.surface
        val tinted = if (glass.gradientEnabled) {
            lerp(base, glass.tintColor, if (glass.isDark) 0.14f else 0.08f)
        } else {
            base
        }
        return if (glass.glassEnabled) {
            tinted.copy(alpha = if (glass.isDark) 0.58f else 0.74f)
        } else {
            tinted
        }
    }

val BentoCardPurple: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        val base = MaterialTheme.colorScheme.surfaceVariant
        val tinted = if (glass.gradientEnabled) {
            lerp(base, glass.tintColor, if (glass.isDark) 0.18f else 0.10f)
        } else {
            base
        }
        return if (glass.glassEnabled) {
            tinted.copy(alpha = if (glass.isDark) 0.62f else 0.78f)
        } else {
            tinted
        }
    }

val BentoCardDarkPurple: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        val base = MaterialTheme.colorScheme.surfaceVariant
        val tinted = if (glass.gradientEnabled) {
            lerp(base, glass.secondaryTintColor, if (glass.isDark) 0.16f else 0.09f)
        } else {
            base
        }
        return if (glass.glassEnabled) {
            tinted.copy(alpha = if (glass.isDark) 0.64f else 0.80f)
        } else {
            tinted
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
        return when {
            glass.glassEnabled && glass.gradientEnabled -> {
                lerp(base, glass.tintColor, 0.50f)
                    .copy(alpha = if (glass.isDark) 0.48f else 0.42f)
            }
            glass.glassEnabled -> {
                (if (glass.isDark) Color.White else Color.Black)
                    .copy(alpha = if (glass.isDark) 0.22f else 0.15f)
            }
            glass.gradientEnabled -> {
                lerp(base, glass.tintColor, 0.38f)
                    .copy(alpha = if (glass.isDark) 0.36f else 0.32f)
            }
            else -> base
        }
    }

val BentoBorder: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        val base = MaterialTheme.colorScheme.outline
        return when {
            glass.glassEnabled && glass.gradientEnabled -> {
                lerp(base, glass.tintColor, 0.46f)
                    .copy(alpha = if (glass.isDark) 0.45f else 0.38f)
            }
            glass.glassEnabled -> {
                (if (glass.isDark) Color.White else Color.Black)
                    .copy(alpha = if (glass.isDark) 0.20f else 0.14f)
            }
            glass.gradientEnabled -> {
                lerp(base, glass.tintColor, 0.35f)
                    .copy(alpha = if (glass.isDark) 0.34f else 0.30f)
            }
            else -> base
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

