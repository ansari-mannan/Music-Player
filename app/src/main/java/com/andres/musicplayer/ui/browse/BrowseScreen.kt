package com.andres.musicplayer.ui.browse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.data.browse.BrowseResult
import com.andres.musicplayer.data.browse.MediaStoreBrowser
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.common.LoadingState
import com.andres.musicplayer.ui.player.PlayerState
import com.andres.musicplayer.ui.playlists.AddToPlaylistDialog
import com.andres.musicplayer.ui.songs.SongListCapability
import com.andres.musicplayer.ui.songs.UnifiedSongList
import kotlinx.coroutines.launch

private val audioPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/** Browse all audio on the device by folder, and play any file. */
@Composable
fun BrowseScreen(
    player: PlayerState,
    repo: SongRepository,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by repo.playlistSummaries.collectAsState(initial = emptyList())
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    if (!hasPermission) {
        EmptyState(
            title = "Browse your device",
            body = "Allow access to your audio to browse all music on this device.",
            buttonText = "Allow access",
            onButtonClick = { launcher.launch(audioPermission) },
            modifier = modifier,
        )
        return
    }

    val browser = remember { MediaStoreBrowser(context) }
    var result by remember { mutableStateOf<BrowseResult?>(null) }
    LaunchedEffect(Unit) { result = browser.loadAll() }

    var selectedFolder by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = selectedFolder != null) { selectedFolder = null }

    when (val r = result) {
        null -> LoadingState(modifier = modifier)

        BrowseResult.PermissionDenied -> EmptyState(
            title = "Access needed",
            body = "Allow audio access in system settings to browse music on this device.",
            buttonText = "Allow access",
            onButtonClick = { launcher.launch(audioPermission) },
            modifier = modifier,
        )

        BrowseResult.Empty -> EmptyState(
            title = "No audio found",
            body = "No music files were found on this device.",
            modifier = modifier,
        )

        is BrowseResult.Success -> {
            val all = r.items
            if (selectedFolder == null) {
                val folders = remember(all) {
                    all.groupingBy { it.folder }.eachCount().toList()
                        .sortedBy { it.first.lowercase() }
                }
                LazyColumn(modifier = modifier.fillMaxSize()) {
                    items(items = folders, key = { it.first }) { (name, count) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFolder = name }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (count == 1) "1 track" else "$count tracks",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            } else {
                val folderSongs = remember(all, selectedFolder) {
                    all.filter { it.folder == selectedFolder }.map { it.toSong() }
                }
                var showFolderPicker by remember(selectedFolder) { mutableStateOf(false) }
                Column(modifier = modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { selectedFolder = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(
                            selectedFolder ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showFolderPicker = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = "Add folder to playlist",
                            )
                        }
                    }
                    UnifiedSongList(
                        songs = folderSongs,
                        player = player,
                        capabilities = setOf(
                            SongListCapability.PLAY,
                            SongListCapability.MULTI_SELECT,
                            SongListCapability.QUEUE_ACTIONS,
                        ),
                        repo = repo,
                        importForPlaylist = true,
                    )
                }

                if (showFolderPicker) {
                    AddToPlaylistDialog(
                        playlists = playlists,
                        onPick = { playlistId ->
                            val toAdd = folderSongs
                            scope.launch {
                                val ids = repo.importAndGetIds(toAdd)
                                repo.addSongsToPlaylist(playlistId, ids)
                            }
                            showFolderPicker = false
                        },
                        onCreateNew = { name ->
                            val toAdd = folderSongs
                            scope.launch {
                                val ids = repo.importAndGetIds(toAdd)
                                val id = repo.createPlaylist(name)
                                repo.addSongsToPlaylist(id, ids)
                            }
                            showFolderPicker = false
                        },
                        onDismiss = { showFolderPicker = false },
                    )
                }
            }
        }
    }
}
