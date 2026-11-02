package com.andres.musicplayer.ui.youtube

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.andres.musicplayer.data.local.entity.WatchedPlaylist
import com.andres.musicplayer.ui.common.EmptyState

/**
 * Playlist picker: every playlist on the connected YouTube account, each with a switch
 * choosing whether this app watches it for new videos.
 *
 * Selection is opt-in — nothing is watched until it's switched on here — because an
 * account's playlists are rarely all music (mixed-content playlists, saved talks, and
 * so on), and watching one means downloading its audio.
 */
@Composable
fun SyncedPlaylistsScreen(
    youtube: YouTubeSyncState,
    modifier: Modifier = Modifier,
) {
    // Opening the screen is an explicit request to see the current list, so pull it
    // fresh; the mirrored copy shown meanwhile keeps the screen usable offline.
    LaunchedEffect(youtube.isConnected) {
        if (youtube.isConnected) youtube.refresh()
    }

    if (!youtube.isConnected) {
        EmptyState(
            title = "No YouTube account connected",
            body = "Connect an account in Settings to choose which playlists to sync.",
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (youtube.isWorking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Playlists to sync",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = youtube.accountLabel?.let { "Signed in as $it" }
                        ?: "Connected to YouTube",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { youtube.refresh() },
                enabled = !youtube.isWorking,
            ) { Text("Refresh") }
        }

        val watchedCount = youtube.watchedCount
        Text(
            text = if (watchedCount == 0) {
                "Nothing is being synced yet. Switch on a playlist to watch it for new songs."
            } else {
                "Watching $watchedCount of ${youtube.playlists.size} playlists."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // "Refresh" above re-reads the *list* of playlists; this checks their contents
        // for new songs. Two different things, so two buttons rather than one that
        // guesses which the user meant.
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { youtube.checkNow() },
                enabled = !youtube.isWorking && watchedCount > 0,
            ) { Text("Check for new songs now") }
        }
        SyncStatusLine(youtube, modifier = Modifier.padding(horizontal = 16.dp))

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        if (youtube.playlists.isEmpty()) {
            EmptyState(
                title = if (youtube.hasLoaded) "No playlists" else "Loading playlists…",
                body = if (youtube.hasLoaded) {
                    "This account doesn't own any playlists. Watch Later and Liked Videos " +
                        "aren't listed by YouTube and can't be synced."
                } else {
                    ""
                },
            )
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(youtube.playlists, key = { it.playlistId }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    onToggle = { youtube.setWatched(playlist.playlistId, it) },
                )
            }
        }
    }
}

/**
 * When the playlists were last checked and what came of it, or why a check was declined.
 *
 * Shared with the Downloads tab's card so both say the same thing — a manual check that
 * quietly did nothing is the one outcome a user would otherwise have no way to tell from
 * a check that ran and found nothing.
 */
@Composable
fun SyncStatusLine(
    youtube: YouTubeSyncState,
    modifier: Modifier = Modifier,
) {
    val notice = youtube.syncNotice
    val text = when {
        notice != null -> notice
        youtube.lastSyncAt > 0 -> {
            val relative = DateUtils.getRelativeTimeSpanString(
                youtube.lastSyncAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            youtube.lastSyncSummary?.let { "Checked $relative · $it" } ?: "Checked $relative"
        }
        else -> "Not checked yet."
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (notice != null) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier,
    )
}

/** Thumbnail, title, item count, and the "sync this playlist" switch. */
@Composable
private fun PlaylistRow(
    playlist: WatchedPlaylist,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!playlist.isWatched) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistThumbnail(playlist.thumbnailUrl)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
            )
            Text(
                text = itemCountLabel(playlist.itemCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = playlist.isWatched, onCheckedChange = onToggle)
    }
}

@Composable
private fun PlaylistThumbnail(url: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 48.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isEmpty()) {
            // Brand-new playlists have no thumbnail until they hold a video.
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
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

private fun itemCountLabel(count: Int) = when (count) {
    0 -> "Empty"
    1 -> "1 video"
    else -> "$count videos"
}
