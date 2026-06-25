package com.andres.musicplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.data.prefs.AccentColor
import com.andres.musicplayer.data.prefs.SettingsState
import com.andres.musicplayer.data.prefs.ThemeMode
import com.andres.musicplayer.data.prefs.musicPlayerTitle

/** App settings: appearance, audio, and the music folder. */
@Composable
fun SettingsScreen(
    settings: SettingsState,
    folderUri: String?,
    onPickFolder: () -> Unit,
    onScan: () -> Unit,
    onOpenEqualizer: () -> Unit,
    untaggedCount: Int,
    onTagUntagged: () -> Unit,
    onSyncTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Personalisation")
        OutlinedTextField(
            value = settings.displayName,
            onValueChange = { settings.updateDisplayName(it) },
            singleLine = true,
            label = { Text("Your name") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            text = "Shown across the app as “${musicPlayerTitle(settings.displayName)}”.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )

        SectionHeader("Appearance")
        Text(
            "Theme",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        )
        ThemeMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = settings.themeMode == mode,
                        onClick = { settings.updateThemeMode(mode) },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.themeMode == mode,
                    onClick = { settings.updateThemeMode(mode) },
                )
                Spacer(Modifier.width(12.dp))
                Text(themeLabel(mode))
            }
        }
        Spacer(Modifier.padding(top = 8.dp))
        // Simple accents tint the app from a single colour. Themes (below) are full
        // designed palettes. They share one selection, but are shown separately.
        Text(
            "Accent colour",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(AccentColor.entries.filter { !it.isTheme }) { accent ->
                AccentSwatch(
                    accent = accent,
                    isSelected = settings.accentColor == accent,
                    onClick = { settings.updateAccentColor(accent) },
                )
            }
        }

        Spacer(Modifier.padding(top = 12.dp))
        Text(
            "Themes",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(AccentColor.entries.filter { it.isTheme }) { accent ->
                AccentSwatch(
                    accent = accent,
                    isSelected = settings.accentColor == accent,
                    onClick = { settings.updateAccentColor(accent) },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
        SectionHeader("Audio")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenEqualizer)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("Equalizer", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Multi-band equalizer with presets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        SectionHeader("Library")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTagUntagged)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("Tag untagged songs", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (untaggedCount == 0) {
                    "All songs have a mood."
                } else {
                    "$untaggedCount ${if (untaggedCount == 1) "song needs" else "songs need"} a mood."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSyncTags)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("Save tags to files", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Write every song's info and moods into its MP3, so they survive a reinstall.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        SectionHeader("Music folder")
        Text(
            text = folderUri?.let { folderLabel(it) } ?: "No folder selected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = onPickFolder) {
                Text(if (folderUri == null) "Choose folder" else "Change folder")
            }
            if (folderUri != null) {
                TextButton(onClick = onScan) { Text("Scan for new songs") }
            }
        }
        Spacer(Modifier.padding(bottom = 16.dp))
    }
}

/** One labelled circular swatch in an accent / theme picker row. */
@Composable
private fun AccentSwatch(
    accent: AccentColor,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val circleColor = if (accent == AccentColor.DYNAMIC) {
        MaterialTheme.colorScheme.primary
    } else {
        Color(accent.seed)
    }
    // Pick black/white for the check & "A" so they stay visible on pale swatches.
    val onCircle = if (circleColor.luminance() > 0.5f) Color.Black else Color.White
    Column(
        modifier = Modifier
            .width(64.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(circleColor)
                .border(
                    width = if (isSelected) 3.dp else 0.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = onCircle)
            } else if (accent == AccentColor.DYNAMIC) {
                Text("A", color = onCircle, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.padding(top = 4.dp))
        Text(
            text = accent.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

/** Turns a SAF tree uri into something readable, e.g. "Music/Downloads". */
private fun folderLabel(uriString: String): String {
    val segment = android.net.Uri.parse(uriString).lastPathSegment ?: return uriString
    return segment.substringAfter(':', segment)
}
