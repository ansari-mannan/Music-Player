package com.andres.musicplayer.ui.downloads

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.andres.musicplayer.data.local.entity.DownloadEntryWithSource
import com.andres.musicplayer.data.local.entity.DownloadState
import com.andres.musicplayer.data.prefs.DownloadPreferences
import com.andres.musicplayer.download.DownloadProgress
import com.andres.musicplayer.ui.youtube.SyncStatusLine
import com.andres.musicplayer.ui.youtube.YouTubeSyncState
import java.util.Locale

private enum class DownloadTab(val label: String) {
    ACTIVE("Active"),
    FAILED("Failed"),
    COMPLETED("Completed"),
}

/**
 * The Download Manager: what's downloading, what failed and why, and what finished.
 *
 * Tabs rather than one long scroll — the Completed list grows without limit once a
 * playlist is syncing, and it would otherwise bury the active queue at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloads: DownloadsState,
    youtube: YouTubeSyncState,
    onManagePlaylists: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(DownloadTab.ACTIVE) }

    Column(modifier = modifier.fillMaxSize()) {
        // Above the tabs rather than inside one: syncing is what fills all three of
        // them, and the queue being empty is most often a question about the sync.
        SyncSummaryCard(youtube = youtube, onManagePlaylists = onManagePlaylists)

        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            DownloadTab.entries.forEach { entry ->
                val count = when (entry) {
                    DownloadTab.ACTIVE -> downloads.active.size
                    DownloadTab.FAILED -> downloads.failed.size
                    DownloadTab.COMPLETED -> downloads.completed.size
                }
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    text = {
                        Text(if (count > 0) "${entry.label} ($count)" else entry.label)
                    },
                )
            }
        }

        when (tab) {
            DownloadTab.ACTIVE -> ActiveTab(downloads)
            DownloadTab.FAILED -> FailedTab(downloads)
            DownloadTab.COMPLETED -> CompletedTab(downloads)
        }
    }
}

/**
 * Where the queue comes from, and how to make it check right now.
 *
 * [SyncedPlaylistsScreen] stays where it is under Settings — this is a shortcut into it,
 * not a second copy of it. Which playlists to watch is a setup decision made once; what
 * they have produced lately belongs next to the downloads themselves.
 */
@Composable
private fun SyncSummaryCard(
    youtube: YouTubeSyncState,
    onManagePlaylists: () -> Unit,
) {
    if (!youtube.isConnected) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = "No YouTube account connected",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Connect one in Settings to have new songs on your playlists " +
                    "download automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        return
    }

    Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlistCountLabel(youtube.watchedCount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                SyncStatusLine(youtube)
            }
            IconButton(
                onClick = { youtube.checkNow() },
                enabled = !youtube.isWorking,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Check for new songs now")
            }
        }
        Row {
            TextButton(onClick = onManagePlaylists) { Text("Manage synced playlists") }
        }
    }
    if (youtube.isWorking) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    HorizontalDivider()
}

internal fun playlistCountLabel(watched: Int) = when (watched) {
    0 -> "No playlists synced"
    1 -> "Syncing 1 playlist"
    else -> "Syncing $watched playlists"
}

@Composable
private fun ActiveTab(downloads: DownloadsState) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (downloads.waitingForWifi) {
            item {
                Text(
                    text = "Waiting for Wi-Fi — \"Download on Wi-Fi only\" is on in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        item { ConcurrencyRow(downloads) }

        if (downloads.active.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (downloads.active.any { it.entry.state == DownloadState.PAUSED }) {
                        TextButton(onClick = { downloads.resumeAll() }) { Text("Resume all") }
                    }
                    if (downloads.active.any { it.entry.state != DownloadState.PAUSED }) {
                        TextButton(onClick = { downloads.pauseAll() }) { Text("Pause all") }
                    }
                }
            }
            items(downloads.active, key = { it.entry.id }) { row ->
                ActiveRow(
                    row = row,
                    live = downloads.progress[row.entry.id],
                    onPause = { downloads.pause(row.entry.id) },
                    onResume = { downloads.resume(row.entry.id) },
                    onCancel = { downloads.cancel(row.entry.id) },
                )
            }
        } else {
            item {
                TabMessage(
                    "Nothing downloading. New songs on your synced playlists appear " +
                        "here automatically.",
                )
            }
        }

        item { ManualDownload(downloads) }
    }
}

@Composable
private fun FailedTab(downloads: DownloadsState) {
    if (downloads.failed.isEmpty()) {
        TabMessage("No failed downloads.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { downloads.retryAllFailed() }) { Text("Retry all") }
            }
        }
        items(downloads.failed, key = { it.entry.id }) { row ->
            DownloadRow(
                row = row,
                detail = {
                    Text(
                        text = row.entry.failureReason ?: "Failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                actions = {
                    IconButton(onClick = { downloads.retry(row.entry.id) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry")
                    }
                    IconButton(onClick = { downloads.cancel(row.entry.id) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                },
            )
        }
    }
}

@Composable
private fun CompletedTab(downloads: DownloadsState) {
    if (downloads.completed.isEmpty()) {
        TabMessage("Nothing downloaded yet.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(downloads.completed, key = { it.entry.id }) { row ->
            DownloadRow(
                row = row,
                // Only artwork that is really inside the file. The listing thumbnail is
                // always available and rendering it here claimed every download had a
                // cover, which is how a broken `covr` write stayed invisible.
                showArtwork = row.entry.hasEmbeddedArt,
                detail = {
                    Text(
                        text = completedLabel(row.entry.completedAt, row.entry.bytesDownloaded),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!row.entry.hasEmbeddedArt) {
                        Text(
                            text = "No artwork in file",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // History only. The downloaded file belongs to the library and is
                    // never touched from this screen — there is deliberately no bulk
                    // delete here either.
                    TextButton(onClick = { downloads.removeFromHistory(row.entry.id) }) {
                        Text("Remove")
                    }
                },
            )
        }
        item {
            Text(
                text = "Removing an item here only clears it from this list. The song " +
                    "itself stays in your music folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun ActiveRow(
    row: DownloadEntryWithSource,
    live: DownloadProgress?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val entry = row.entry
    val bytes = live?.bytesDownloaded ?: entry.bytesDownloaded
    val total = live?.totalBytes ?: entry.totalBytes
    val paused = entry.state == DownloadState.PAUSED

    DownloadRow(
        row = row,
        detail = {
            Text(
                text = when {
                    paused -> "Paused · ${sizeLabel(bytes, total)}"
                    entry.state == DownloadState.QUEUED -> "Queued"
                    else -> progressLabel(bytes, total)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (!paused && entry.state == DownloadState.DOWNLOADING) {
                // Size unknown until the server reports it; show motion, not a fake bar.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        actions = {
            IconButton(onClick = if (paused) onResume else onPause) {
                Icon(
                    imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (paused) "Resume" else "Pause",
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        },
    )
}

/**
 * Thumbnail, title, source playlist, then whatever [detail] and [actions] the tab wants.
 *
 * [showArtwork] false falls back to the placeholder icon. Queued and failed rows have no
 * file yet, so for them the listing thumbnail is simply the best picture of what is
 * coming; only the Completed tab, which is describing a file that exists, turns it off.
 */
@Composable
private fun DownloadRow(
    row: DownloadEntryWithSource,
    detail: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    showArtwork: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(if (showArtwork) row.entry.thumbnailUrl else "")
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text(
                text = sourceLabel(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            detail()
        }
        actions()
    }
}

@Composable
private fun ConcurrencyRow(downloads: DownloadsState) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = "Simultaneous downloads",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (DownloadPreferences.MIN..DownloadPreferences.MAX).forEach { value ->
                val selected = downloads.concurrency == value
                TextButton(onClick = { downloads.updateConcurrency(value) }) {
                    Text(
                        text = "$value",
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        Text(
            text = "Lowering this won't stop downloads already in progress — it only " +
                "limits new ones. Raising it starts more straight away.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

/**
 * Manual download by link. Not part of the spec — it exists because the download engine
 * was built and tested before playlist syncing could feed it, and it stayed because
 * grabbing one song without adding it to a playlist is genuinely useful. Placed last so
 * the automatic queue leads.
 */
@Composable
private fun ManualDownload(downloads: DownloadsState) {
    var link by remember { mutableStateOf("") }

    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Add a video by link",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = link,
            onValueChange = {
                link = it
                downloads.clearMessage()
            },
            singleLine = true,
            label = { Text("YouTube link or video id") },
            modifier = Modifier.fillMaxWidth(),
        )
        downloads.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                downloads.enqueue(link)
                link = ""
            },
            enabled = link.isNotBlank(),
        ) { Text("Download") }
    }
}

@Composable
private fun Thumbnail(url: String) {
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isBlank()) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TabMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** Where the download came from: a playlist name, or "Added by hand". */
internal fun sourceLabel(row: DownloadEntryWithSource): String = when {
    row.playlistTitle != null -> row.playlistTitle
    row.entry.sourcePlaylistId == null -> "Added by hand"
    // The playlist row is gone — deleted on YouTube, or the account was disconnected.
    else -> "Playlist no longer synced"
}

/** "42% · 3.4 MB of 8.1 MB", degrading gracefully while the total is unknown. */
internal fun progressLabel(bytes: Long, total: Long?): String {
    val size = sizeLabel(bytes, total)
    if (total == null || total <= 0) return size
    val percent = (bytes * 100 / total).coerceIn(0, 100)
    return "$percent% · $size"
}

internal fun sizeLabel(bytes: Long, total: Long?): String {
    val current = megabytes(bytes)
    return if (total != null && total > 0) "$current of ${megabytes(total)}" else current
}

private fun completedLabel(completedAt: Long?, bytes: Long): String {
    val size = megabytes(bytes)
    if (completedAt == null || completedAt <= 0) return size
    val relative = DateUtils.getRelativeTimeSpanString(
        completedAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    )
    return "$relative · $size"
}

private fun megabytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
