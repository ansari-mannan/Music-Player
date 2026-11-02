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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.andres.musicplayer.data.prefs.AccentColor
import com.andres.musicplayer.data.prefs.DownloadPreferences
import com.andres.musicplayer.data.prefs.SettingsState
import com.andres.musicplayer.data.prefs.SyncInterval
import com.andres.musicplayer.data.prefs.SyncPreferences
import com.andres.musicplayer.data.prefs.ThemeMode
import com.andres.musicplayer.data.prefs.musicPlayerTitle
import com.andres.musicplayer.download.PlaylistSyncWorker
import com.andres.musicplayer.ui.util.rememberMonoController
import com.andres.musicplayer.ui.youtube.YouTubeSyncState

/** App settings: appearance, audio, and the music folder. */
@Composable
fun SettingsScreen(
    settings: SettingsState,
    folderUri: String?,
    youtube: YouTubeSyncState,
    onPickFolder: () -> Unit,
    onScan: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onSyncMetadata: () -> Unit,
    onManageSyncedPlaylists: () -> Unit,
    onOpenDownloads: () -> Unit,
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
        val mono = rememberMonoController()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { mono.toggle() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Mono audio", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Combines left and right channels into one — toggled instantly, in the " +
                        "background, without interrupting playback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = mono.isOn,
                enabled = mono.granted,
                onCheckedChange = { mono.set(it) },
            )
        }
        if (!mono.granted) {
            // Android restricts this one setting to WRITE_SECURE_SETTINGS, which only a
            // one-time adb command can grant (no in-app popup can). Shown so it's copyable.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    "One-time setup: connect to a PC with USB debugging and run this once " +
                        "(Android locks this setting; no in-app prompt can grant it):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        com.andres.musicplayer.ui.util.MonoAudio.GRANT_COMMAND,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(8.dp),
                    )
                }
            }
        }

        HorizontalDivider()
        SectionHeader("Library")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSyncMetadata)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("Save song info to files", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Write every song's title, artist and album into the file itself, " +
                    "so they survive a reinstall. MP3 and M4A only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        SectionHeader("YouTube sync")
        YouTubeSection(
            youtube = youtube,
            onManagePlaylists = onManageSyncedPlaylists,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenDownloads)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("Downloads", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Queue, progress, and paused downloads. Also in the Downloads tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ConcurrencySetting()

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

/**
 * Connection status for the YouTube account, plus the way in to the playlist picker.
 *
 * Connecting is a one-time act: after the first approval Google hands back a fresh
 * token silently on every later launch, so this section normally just reads
 * "Connected as …" forever.
 */
@Composable
private fun YouTubeSection(
    youtube: YouTubeSyncState,
    onManagePlaylists: () -> Unit,
) {
    var confirmDisconnect by remember { mutableStateOf(false) }

    if (youtube.isConnected) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                text = youtube.accountLabel?.let { "Connected as $it" } ?: "Connected",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "New songs on the playlists you pick are downloaded into your " +
                    "music folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onManagePlaylists)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("Manage synced playlists", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Choose which of your YouTube playlists to watch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BackgroundCheckSetting()
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect") }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !youtube.isWorking) { youtube.connect() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("Connect YouTube account", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Read-only access to your playlists, so new songs on them can be " +
                    "downloaded automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect YouTube?") },
            text = {
                Text(
                    "The app will stop checking your playlists. Songs already " +
                        "downloaded stay in your library. To also revoke access on " +
                        "Google's side, remove this app at myaccount.google.com.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        youtube.disconnect()
                        confirmDisconnect = false
                    },
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * How many downloads may run at once (Section 4.4).
 *
 * The Download Manager screen has the same control; both read and write the one
 * [DownloadPreferences] value, so they can't disagree. Changing it never interrupts a
 * transfer already in flight — the engine only applies the new limit to what starts next.
 */
@Composable
private fun ConcurrencySetting() {
    val context = LocalContext.current
    val prefs = remember { DownloadPreferences(context) }
    val current by prefs.concurrency.collectAsState()

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Simultaneous downloads", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "Lowering this won't stop downloads already in progress — it only " +
                "limits new ones. Raising it starts more straight away.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    (DownloadPreferences.MIN..DownloadPreferences.MAX).forEach { value ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = current == value,
                    onClick = { prefs.setConcurrency(value) },
                )
                .padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = current == value, onClick = null)
            Spacer(Modifier.width(12.dp))
            Text(if (value == 1) "One at a time" else "$value at a time")
        }
    }
}

/**
 * How often the app checks watched playlists while it isn't open.
 *
 * The copy is deliberate about this being best-effort. Android alone decides when
 * periodic work runs — Doze, battery optimisation and standby buckets can all delay or
 * skip a period — so promising "every 15 minutes" would be a promise the OS doesn't keep.
 */
@Composable
private fun BackgroundCheckSetting() {
    val context = LocalContext.current
    val prefs = remember { SyncPreferences(context) }
    var interval by remember { mutableStateOf(prefs.interval) }
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text("Check for new songs", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = interval.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Playlists are always checked when you open the app. This extra " +
                "background check is up to Android — battery optimisation can delay or " +
                "skip it, so treat it as a bonus rather than a schedule.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        prefs.lastSyncSummary?.let { summary ->
            Text(
                text = "Last check: $summary",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (expanded) {
        SyncInterval.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = interval == option,
                        onClick = {
                            interval = option
                            prefs.interval = option
                            PlaylistSyncWorker.schedule(context, option)
                            expanded = false
                        },
                    )
                    .padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = interval == option, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(option.label)
            }
        }
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
