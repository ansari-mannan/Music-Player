package com.andres.musicplayer.data.prefs

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Accent colour identifier for the Material theme. [DYNAMIC] follows the wallpaper (Android 12+).
 * [label] is the human-readable name shown in Settings.
 *
 * [seed] is the representative swatch shown in the accent picker. For the simple
 * accents it is also the single colour fed into Material 3's auto-derivation. The
 * custom themes below (CURRENTS … RADIOACTIVE_AMBER) instead supply a full explicit
 * light + dark [androidx.compose.material3.ColorScheme] in Theme.kt's lightSchemeFor /
 * darkSchemeFor, so for those the seed is used only as the picker swatch.
 */
enum class AccentColor(val seed: Long, val label: String, val isTheme: Boolean = false) {
    DYNAMIC(0L, "Dynamic"),
    MOLTEN_AMBER(0xFFC97800, "Molten Amber"),
    BURNT_ORANGE(0xFFEA580C, "Burnt Orange"),
    CORAL_FIRE(0xFFFF3A3A, "Coral Fire"),
    SUNSET_ROSE(0xFFF43F5E, "Sunset Rose"),
    PEACH(0xFFFB7A1A, "Peach"),
    WARM_LIME(0xFF84CC16, "Warm Lime"),
    GOLDEN_GREEN(0xFF65A30D, "Golden Green"),
    WARM_MINT(0xFF1DBF72, "Warm Mint"),
    TERRACOTTA(0xFFDC6B3F, "Terracotta"),
    MAUVE(0xFFC084FC, "Mauve"),
    DUSTY_ROSE(0xFFE8396A, "Dusty Rose"),
    SAFFRON(0xFFF59500, "Saffron"),

    // Full designed themes ([isTheme] = true) — each ships an explicit light + dark
    // ColorScheme (see Theme.kt) rather than tinting from one seed, and is shown in a
    // separate "Themes" section in Settings. Each seed below is only the picker swatch
    // (the theme's light-mode primary); Blueprint's light primary is pure white, so its
    // swatch uses the blueprint blue #1E4D8C to stay visible.
    CURRENTS(0xFFD6486B, "Currents", isTheme = true),
    IN_RAINBOWS(0xFF8A6D3C, "In Rainbows", isTheme = true),
    TRUE_BLACK(0xFF3D5AFE, "True Black", isTheme = true),
    MONOCHROME(0xFF1A1A1A, "Monochrome", isTheme = true),
    COLD_SLATE(0xFF3E6B8C, "Cold Slate", isTheme = true),
    MOSS_STONE(0xFF4C6B45, "Moss & Stone", isTheme = true),
    INK_RUST(0xFF8A3E2C, "Ink & Rust", isTheme = true),
    DEEP_VIOLET(0xFF5B3FA0, "Deep Violet", isTheme = true),
    BLEACHED_SAND(0xFFA68A5E, "Bleached Sand", isTheme = true),
    ACID_TERMINAL(0xFF39FF6A, "Acid Terminal", isTheme = true),
    VAPORWAVE(0xFFE64FD9, "Vaporwave", isTheme = true),
    BLUEPRINT(0xFF1E4D8C, "Blueprint", isTheme = true),
    CANDY_PASTEL(0xFFFF9ECF, "Candy Pastel", isTheme = true),
    RADIOACTIVE_AMBER(0xFFA88E00, "Radioactive Amber", isTheme = true),
}

/**
 * App-wide settings backed by SharedPreferences, exposed as Compose state so the
 * UI recomposes when they change. Each setter persists immediately.
 */
@Stable
class SettingsState(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM),
    )
        private set

    var accentColor by mutableStateOf(
        runCatching { AccentColor.valueOf(prefs.getString(KEY_ACCENT, null) ?: "DYNAMIC") }
            .getOrDefault(AccentColor.DYNAMIC),
    )
        private set

    /** Whose player this is — drives the in-app title "<name>'s Music Player". */
    var displayName by mutableStateOf(prefs.getString(KEY_NAME, null) ?: DEFAULT_NAME)
        private set

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun updateAccentColor(color: AccentColor) {
        accentColor = color
        prefs.edit().putString(KEY_ACCENT, color.name).apply()
    }

    fun updateDisplayName(name: String) {
        displayName = name
        prefs.edit().putString(KEY_NAME, name).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_NAME = "display_name"
        const val DEFAULT_NAME = "Andres"
    }
}

/**
 * Builds the app's display title from a person's name, e.g. "Andres' Music Player"
 * or "John's Music Player". Falls back to the default name when blank, and uses a
 * bare apostrophe for names already ending in s (Andres' rather than Andres's).
 */
fun musicPlayerTitle(name: String): String {
    val n = name.trim().ifBlank { SettingsState.DEFAULT_NAME }
    val possessive = if (n.endsWith("s", ignoreCase = true)) "'" else "'s"
    return "$n$possessive Music Player"
}

@Composable
fun rememberSettingsState(): SettingsState {
    val context = LocalContext.current
    return remember { SettingsState(context) }
}
