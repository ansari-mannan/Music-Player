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
 * Full-palette custom themes (see [explicitScheme]) supply every visually relevant
 * role explicitly. The simple accents go through [seedScheme], which derives a whole
 * consistent palette from the one seed colour — crucially it also tints
 * primaryContainer / secondary / tertiary, so accent-coloured surfaces (the logo
 * chip, the new-playlist / new-mood FABs) actually track the chosen accent instead
 * of keeping Material's default purple container.
 */
fun lightSchemeFor(accent: AccentColor): ColorScheme = when (accent) {
    AccentColor.CURRENTS -> explicitScheme(
        CurrentsLightPrimary, CurrentsLightOnPrimary,
        CurrentsLightPrimaryContainer, CurrentsLightOnPrimaryContainer,
        CurrentsLightBackground, CurrentsLightSurface,
        CurrentsLightOnBackground, CurrentsLightOnSurface,
    )
    AccentColor.IN_RAINBOWS -> explicitScheme(
        InRainbowsLightPrimary, InRainbowsLightOnPrimary,
        InRainbowsLightPrimaryContainer, InRainbowsLightOnPrimaryContainer,
        InRainbowsLightBackground, InRainbowsLightSurface,
        InRainbowsLightOnBackground, InRainbowsLightOnSurface,
    )
    AccentColor.TRUE_BLACK -> explicitScheme(
        TrueBlackLightPrimary, TrueBlackLightOnPrimary,
        TrueBlackLightPrimaryContainer, TrueBlackLightOnPrimaryContainer,
        TrueBlackLightBackground, TrueBlackLightSurface,
        TrueBlackLightOnBackground, TrueBlackLightOnSurface,
    )
    AccentColor.MONOCHROME -> explicitScheme(
        MonochromeLightPrimary, MonochromeLightOnPrimary,
        MonochromeLightPrimaryContainer, MonochromeLightOnPrimaryContainer,
        MonochromeLightBackground, MonochromeLightSurface,
        MonochromeLightOnBackground, MonochromeLightOnSurface,
    )
    AccentColor.COLD_SLATE -> explicitScheme(
        ColdSlateLightPrimary, ColdSlateLightOnPrimary,
        ColdSlateLightPrimaryContainer, ColdSlateLightOnPrimaryContainer,
        ColdSlateLightBackground, ColdSlateLightSurface,
        ColdSlateLightOnBackground, ColdSlateLightOnSurface,
    )
    AccentColor.MOSS_STONE -> explicitScheme(
        MossStoneLightPrimary, MossStoneLightOnPrimary,
        MossStoneLightPrimaryContainer, MossStoneLightOnPrimaryContainer,
        MossStoneLightBackground, MossStoneLightSurface,
        MossStoneLightOnBackground, MossStoneLightOnSurface,
    )
    AccentColor.INK_RUST -> explicitScheme(
        InkRustLightPrimary, InkRustLightOnPrimary,
        InkRustLightPrimaryContainer, InkRustLightOnPrimaryContainer,
        InkRustLightBackground, InkRustLightSurface,
        InkRustLightOnBackground, InkRustLightOnSurface,
    )
    AccentColor.DEEP_VIOLET -> explicitScheme(
        DeepVioletLightPrimary, DeepVioletLightOnPrimary,
        DeepVioletLightPrimaryContainer, DeepVioletLightOnPrimaryContainer,
        DeepVioletLightBackground, DeepVioletLightSurface,
        DeepVioletLightOnBackground, DeepVioletLightOnSurface,
    )
    AccentColor.BLEACHED_SAND -> explicitScheme(
        BleachedSandLightPrimary, BleachedSandLightOnPrimary,
        BleachedSandLightPrimaryContainer, BleachedSandLightOnPrimaryContainer,
        BleachedSandLightBackground, BleachedSandLightSurface,
        BleachedSandLightOnBackground, BleachedSandLightOnSurface,
    )
    AccentColor.ACID_TERMINAL -> explicitScheme(
        AcidTerminalLightPrimary, AcidTerminalLightOnPrimary,
        AcidTerminalLightPrimaryContainer, AcidTerminalLightOnPrimaryContainer,
        AcidTerminalLightBackground, AcidTerminalLightSurface,
        AcidTerminalLightOnBackground, AcidTerminalLightOnSurface,
    )
    AccentColor.VAPORWAVE -> explicitScheme(
        VaporwaveLightPrimary, VaporwaveLightOnPrimary,
        VaporwaveLightPrimaryContainer, VaporwaveLightOnPrimaryContainer,
        VaporwaveLightBackground, VaporwaveLightSurface,
        VaporwaveLightOnBackground, VaporwaveLightOnSurface,
    )
    AccentColor.BLUEPRINT -> explicitScheme(
        BlueprintLightPrimary, BlueprintLightOnPrimary,
        BlueprintLightPrimaryContainer, BlueprintLightOnPrimaryContainer,
        BlueprintLightBackground, BlueprintLightSurface,
        BlueprintLightOnBackground, BlueprintLightOnSurface,
    )
    AccentColor.CANDY_PASTEL -> explicitScheme(
        CandyPastelLightPrimary, CandyPastelLightOnPrimary,
        CandyPastelLightPrimaryContainer, CandyPastelLightOnPrimaryContainer,
        CandyPastelLightBackground, CandyPastelLightSurface,
        CandyPastelLightOnBackground, CandyPastelLightOnSurface,
    )
    AccentColor.RADIOACTIVE_AMBER -> explicitScheme(
        RadioactiveAmberLightPrimary, RadioactiveAmberLightOnPrimary,
        RadioactiveAmberLightPrimaryContainer, RadioactiveAmberLightOnPrimaryContainer,
        RadioactiveAmberLightBackground, RadioactiveAmberLightSurface,
        RadioactiveAmberLightOnBackground, RadioactiveAmberLightOnSurface,
    )
    // Every simple seed-based accent: derive a full light palette from its seed.
    else -> seedScheme(Color(accent.seed), dark = false)
}

/**
 * The dark-mode [ColorScheme] for a given accent. Mirrors [lightSchemeFor]: custom
 * themes return their explicit dark palette, all other accents derive from one seed.
 */
fun darkSchemeFor(accent: AccentColor): ColorScheme = when (accent) {
    AccentColor.CURRENTS -> explicitScheme(
        CurrentsDarkPrimary, CurrentsDarkOnPrimary,
        CurrentsDarkPrimaryContainer, CurrentsDarkOnPrimaryContainer,
        CurrentsDarkBackground, CurrentsDarkSurface,
        CurrentsDarkOnBackground, CurrentsDarkOnSurface,
    )
    AccentColor.IN_RAINBOWS -> explicitScheme(
        InRainbowsDarkPrimary, InRainbowsDarkOnPrimary,
        InRainbowsDarkPrimaryContainer, InRainbowsDarkOnPrimaryContainer,
        InRainbowsDarkBackground, InRainbowsDarkSurface,
        InRainbowsDarkOnBackground, InRainbowsDarkOnSurface,
    )
    AccentColor.TRUE_BLACK -> explicitScheme(
        TrueBlackDarkPrimary, TrueBlackDarkOnPrimary,
        TrueBlackDarkPrimaryContainer, TrueBlackDarkOnPrimaryContainer,
        TrueBlackDarkBackground, TrueBlackDarkSurface,
        TrueBlackDarkOnBackground, TrueBlackDarkOnSurface,
    )
    AccentColor.MONOCHROME -> explicitScheme(
        MonochromeDarkPrimary, MonochromeDarkOnPrimary,
        MonochromeDarkPrimaryContainer, MonochromeDarkOnPrimaryContainer,
        MonochromeDarkBackground, MonochromeDarkSurface,
        MonochromeDarkOnBackground, MonochromeDarkOnSurface,
    )
    AccentColor.COLD_SLATE -> explicitScheme(
        ColdSlateDarkPrimary, ColdSlateDarkOnPrimary,
        ColdSlateDarkPrimaryContainer, ColdSlateDarkOnPrimaryContainer,
        ColdSlateDarkBackground, ColdSlateDarkSurface,
        ColdSlateDarkOnBackground, ColdSlateDarkOnSurface,
    )
    AccentColor.MOSS_STONE -> explicitScheme(
        MossStoneDarkPrimary, MossStoneDarkOnPrimary,
        MossStoneDarkPrimaryContainer, MossStoneDarkOnPrimaryContainer,
        MossStoneDarkBackground, MossStoneDarkSurface,
        MossStoneDarkOnBackground, MossStoneDarkOnSurface,
    )
    AccentColor.INK_RUST -> explicitScheme(
        InkRustDarkPrimary, InkRustDarkOnPrimary,
        InkRustDarkPrimaryContainer, InkRustDarkOnPrimaryContainer,
        InkRustDarkBackground, InkRustDarkSurface,
        InkRustDarkOnBackground, InkRustDarkOnSurface,
    )
    AccentColor.DEEP_VIOLET -> explicitScheme(
        DeepVioletDarkPrimary, DeepVioletDarkOnPrimary,
        DeepVioletDarkPrimaryContainer, DeepVioletDarkOnPrimaryContainer,
        DeepVioletDarkBackground, DeepVioletDarkSurface,
        DeepVioletDarkOnBackground, DeepVioletDarkOnSurface,
    )
    AccentColor.BLEACHED_SAND -> explicitScheme(
        BleachedSandDarkPrimary, BleachedSandDarkOnPrimary,
        BleachedSandDarkPrimaryContainer, BleachedSandDarkOnPrimaryContainer,
        BleachedSandDarkBackground, BleachedSandDarkSurface,
        BleachedSandDarkOnBackground, BleachedSandDarkOnSurface,
    )
    AccentColor.ACID_TERMINAL -> explicitScheme(
        AcidTerminalDarkPrimary, AcidTerminalDarkOnPrimary,
        AcidTerminalDarkPrimaryContainer, AcidTerminalDarkOnPrimaryContainer,
        AcidTerminalDarkBackground, AcidTerminalDarkSurface,
        AcidTerminalDarkOnBackground, AcidTerminalDarkOnSurface,
    )
    AccentColor.VAPORWAVE -> explicitScheme(
        VaporwaveDarkPrimary, VaporwaveDarkOnPrimary,
        VaporwaveDarkPrimaryContainer, VaporwaveDarkOnPrimaryContainer,
        VaporwaveDarkBackground, VaporwaveDarkSurface,
        VaporwaveDarkOnBackground, VaporwaveDarkOnSurface,
    )
    AccentColor.BLUEPRINT -> explicitScheme(
        BlueprintDarkPrimary, BlueprintDarkOnPrimary,
        BlueprintDarkPrimaryContainer, BlueprintDarkOnPrimaryContainer,
        BlueprintDarkBackground, BlueprintDarkSurface,
        BlueprintDarkOnBackground, BlueprintDarkOnSurface,
    )
    AccentColor.CANDY_PASTEL -> explicitScheme(
        CandyPastelDarkPrimary, CandyPastelDarkOnPrimary,
        CandyPastelDarkPrimaryContainer, CandyPastelDarkOnPrimaryContainer,
        CandyPastelDarkBackground, CandyPastelDarkSurface,
        CandyPastelDarkOnBackground, CandyPastelDarkOnSurface,
    )
    AccentColor.RADIOACTIVE_AMBER -> explicitScheme(
        RadioactiveAmberDarkPrimary, RadioactiveAmberDarkOnPrimary,
        RadioactiveAmberDarkPrimaryContainer, RadioactiveAmberDarkOnPrimaryContainer,
        RadioactiveAmberDarkBackground, RadioactiveAmberDarkSurface,
        RadioactiveAmberDarkOnBackground, RadioactiveAmberDarkOnSurface,
    )
    // Every simple seed-based accent: derive a full dark palette from its seed.
    else -> seedScheme(Color(accent.seed), dark = true)
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
 * Derives a full palette from a single seed colour for the simple accents. Starts
 * from Material's neutral light/dark scheme (keeping its clean white/near-black
 * surfaces) but recolours primary AND primaryContainer / secondary / tertiary from
 * the seed — so the seed actually shows up on accent-coloured surfaces, not just on
 * the few components that read `primary`.
 */
private fun seedScheme(seed: Color, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val onPrimary = if (seed.luminance() > 0.5f) Color.Black else Color.White
    val container = if (dark) lerp(seed, base.surface, 0.74f) else lerp(seed, Color.White, 0.82f)
    val onContainer = if (dark) lerp(seed, Color.White, 0.70f) else lerp(seed, Color.Black, 0.55f)

    return base.copy(
        primary = seed,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        inversePrimary = container,
        secondary = seed,
        onSecondary = onPrimary,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = seed,
        onTertiary = onPrimary,
        tertiaryContainer = container,
        onTertiaryContainer = onContainer,
        surfaceTint = seed,
    )
}
