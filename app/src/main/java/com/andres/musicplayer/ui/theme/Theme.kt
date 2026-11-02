package com.andres.musicplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.andres.musicplayer.data.prefs.AccentColor
import com.andres.musicplayer.data.prefs.ThemeMode

@Composable
fun AndresMusicPlayerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentColor: AccentColor = AccentColor.DYNAMIC,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        accentColor == AccentColor.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        accentColor == AccentColor.DYNAMIC -> {
            // Pre-S devices can't read the wallpaper colour: fall back to burnt orange.
            if (darkTheme) darkSchemeFor(AccentColor.BURNT_ORANGE)
            else lightSchemeFor(AccentColor.BURNT_ORANGE)
        }
        else -> {
            if (darkTheme) darkSchemeFor(accentColor) else lightSchemeFor(accentColor)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

/**
 * The light-mode [ColorScheme] for a given accent.
 *
 * The user only likes the Candy Pastel look, so every theme/accent now shares that
 * soft pastel feel: pale tinted backgrounds, white surfaces, gentle pastel primaries
 * and light tinted containers. Each theme keeps its own hue (from its seed) so they
 * stay distinguishable, but they're all "candy pastel"-styled. Candy Pastel itself
 * keeps its hand-tuned reference palette.
 */
fun lightSchemeFor(accent: AccentColor): ColorScheme = when (accent) {
    AccentColor.CANDY_PASTEL -> explicitScheme(
        CandyPastelLightPrimary, CandyPastelLightOnPrimary,
        CandyPastelLightPrimaryContainer, CandyPastelLightOnPrimaryContainer,
        CandyPastelLightBackground, CandyPastelLightSurface,
        CandyPastelLightOnBackground, CandyPastelLightOnSurface,
    )
    // Every other accent/theme: a candy-pastel palette tinted by its own seed hue.
    else -> pastelScheme(Color(accent.seed), dark = false)
}

/**
 * The dark-mode [ColorScheme] for a given accent. Mirrors [lightSchemeFor]: Candy
 * Pastel keeps its explicit dark palette, everything else gets a pastel palette
 * derived from its seed hue.
 */
fun darkSchemeFor(accent: AccentColor): ColorScheme = when (accent) {
    AccentColor.CANDY_PASTEL -> explicitScheme(
        CandyPastelDarkPrimary, CandyPastelDarkOnPrimary,
        CandyPastelDarkPrimaryContainer, CandyPastelDarkOnPrimaryContainer,
        CandyPastelDarkBackground, CandyPastelDarkSurface,
        CandyPastelDarkOnBackground, CandyPastelDarkOnSurface,
    )
    else -> pastelScheme(Color(accent.seed), dark = true)
}

/**
 * Builds a complete [ColorScheme] from the eight roles a theme cares about, filling
 * in every remaining role (secondary/tertiary, the surface-container family, outlines,
 * inverse colours…) so nothing falls back to Material's baseline light/dark defaults.
 * Without this, themes whose surface is dark (Acid Terminal, True Black, Blueprint's
 * blue surface) would show light-grey bottom bars / chips and mismatched secondary
 * text. The light-vs-dark treatment is chosen from the surface's own luminance, so a
 * dark surface declared in a "light" slot is still handled correctly.
 */
private fun explicitScheme(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    background: Color,
    surface: Color,
    onBackground: Color,
    onSurface: Color,
): ColorScheme {
    val darkSurface = surface.luminance() < 0.5f
    val base = if (darkSurface) darkColorScheme() else lightColorScheme()

    // Tones derived from surface/onSurface so containers, dividers and secondary
    // text always sit at sensible contrast against this theme's actual surface.
    fun elevate(fraction: Float) = lerp(surface, onSurface, fraction)
    val onSurfaceVariant = onSurface.copy(alpha = 0.72f).compositeOver(surface)
    val outline = onSurface.copy(alpha = 0.38f).compositeOver(surface)
    val outlineVariant = onSurface.copy(alpha = 0.18f).compositeOver(surface)

    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = primaryContainer,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = primaryContainer,
        onSecondaryContainer = onPrimaryContainer,
        tertiary = primary,
        onTertiary = onPrimary,
        tertiaryContainer = primaryContainer,
        onTertiaryContainer = onPrimaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceTint = primary,
        surfaceVariant = elevate(0.06f),
        onSurfaceVariant = onSurfaceVariant,
        surfaceDim = if (darkSurface) surface else elevate(0.06f),
        surfaceBright = if (darkSurface) elevate(0.10f) else surface,
        surfaceContainerLowest = if (darkSurface) lerp(surface, Color.Black, 0.4f) else surface,
        surfaceContainerLow = elevate(0.03f),
        surfaceContainer = elevate(0.06f),
        surfaceContainerHigh = elevate(0.09f),
        surfaceContainerHighest = elevate(0.12f),
        inverseSurface = onSurface,
        inverseOnSurface = surface,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color.Black,
    )
}

/**
 * Builds a candy-pastel [ColorScheme] from a single seed hue. The seed is softened
 * toward white (pastel primary) and used to faintly tint the background/containers,
 * so every theme reads as the same gentle pastel style while keeping its own colour.
 * Reuses [explicitScheme] to fill in the full surface/outline family consistently.
 */
private fun pastelScheme(seed: Color, dark: Boolean): ColorScheme = if (dark) {
    val primary = lerp(seed, Color.White, 0.32f)
    explicitScheme(
        primary = primary,
        onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White,
        primaryContainer = lerp(seed, Color.Black, 0.62f),
        onPrimaryContainer = lerp(seed, Color.White, 0.62f),
        background = lerp(seed, Color.Black, 0.86f),
        surface = lerp(seed, Color.Black, 0.80f),
        onBackground = lerp(seed, Color.White, 0.86f),
        onSurface = lerp(seed, Color.White, 0.86f),
    )
} else {
    val primary = lerp(seed, Color.White, 0.30f)
    explicitScheme(
        primary = primary,
        onPrimary = if (primary.luminance() > 0.5f) lerp(seed, Color.Black, 0.55f) else Color.White,
        primaryContainer = lerp(seed, Color.White, 0.80f),
        onPrimaryContainer = lerp(seed, Color.Black, 0.50f),
        background = lerp(seed, Color.White, 0.92f),
        surface = Color.White,
        onBackground = lerp(seed, Color.Black, 0.68f),
        onSurface = lerp(seed, Color.Black, 0.68f),
    )
}
