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
        val contrastAnchor = if (glass.glassEnabled) {
            lerp(base, if (glass.isDark) Color(0xFF0A0A12) else Color.White, 0.25f)
        } else {
            base
        }
        val tinted = if (glass.gradientEnabled) {
            lerp(contrastAnchor, glass.tintColor, if (glass.isDark) 0.11f else 0.06f)
        } else {
            contrastAnchor
        }
        return if (glass.glassEnabled) {
            tinted.copy(alpha = if (glass.isDark) 0.70f else 0.84f)
        } else {
            tinted
        }
    }

val BentoCardPurple: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        val base = MaterialTheme.colorScheme.surfaceVariant
        val contrastAnchor = if (glass.glassEnabled) {
            lerp(base, if (glass.isDark) Color(0xFF0C0C16) else Color.White, 0.22f)
        } else {
            base
        }
        val tinted = if (glass.gradientEnabled) {
            lerp(contrastAnchor, glass.tintColor, if (glass.isDark) 0.14f else 0.08f)
        } else {
            contrastAnchor
        }
        return if (glass.glassEnabled) {
            tinted.copy(alpha = if (glass.isDark) 0.74f else 0.86f)
        } else {
            tinted
        }
    }

val BentoCardDarkPurple: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        val base = MaterialTheme.colorScheme.surfaceVariant
        val contrastAnchor = if (glass.glassEnabled) {
            lerp(base, if (glass.isDark) Color(0xFF0C0C16) else Color.White, 0.22f)
        } else {
            base
        }
        val tinted = if (glass.gradientEnabled) {
            lerp(contrastAnchor, glass.secondaryTintColor, if (glass.isDark) 0.13f else 0.07f)
        } else {
            contrastAnchor
        }
        return if (glass.glassEnabled) {
            tinted.copy(alpha = if (glass.isDark) 0.76f else 0.88f)
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
    get() {
        val glass = LocalGlassThemeConfig.current
        return if (glass.glassEnabled || glass.gradientEnabled) {
            if (glass.isDark) Color(0xFFFFFFFF) else Color(0xFF0B0F19)
        } else {
            MaterialTheme.colorScheme.onBackground
        }
    }

val BentoTextSecondary: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        val base = MaterialTheme.colorScheme.onSurfaceVariant
        return if (glass.glassEnabled || glass.gradientEnabled) {
            if (glass.isDark) {
                lerp(base, Color(0xFFF1F5F9), 0.52f)
            } else {
                lerp(base, Color(0xFF1E293B), 0.45f)
            }
        } else {
            base
        }
    }

val BentoTextSubtle: Color
    @Composable
    get() {
        val glass = LocalGlassThemeConfig.current
        return if (glass.glassEnabled || glass.gradientEnabled) {
            BentoTextSecondary.copy(alpha = if (glass.isDark) 0.86f else 0.82f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        }
    }

val BentoAccentPinkText: Color
    @Composable
    get() = MaterialTheme.colorScheme.primary

val BentoAccentPinkBg: Color
    @Composable
    get() = MaterialTheme.colorScheme.primaryContainer

val BentoError: Color
    @Composable
    get() = MaterialTheme.colorScheme.error

