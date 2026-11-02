package com.andres.musicplayer.ui.songs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.ui.app.LocalAppTitle
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.common.LoadingState
import com.andres.musicplayer.ui.player.PlayerState

/** All songs in the library. */
@Composable
fun SongsScreen(
    repo: SongRepository,
    player: PlayerState,
    folderUri: String?,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // null = first load in progress (avoids an empty-state flash).
    val songs by repo.allSongs.collectAsState(initial = null)

    when {
        folderUri == null -> EmptyState(
            title = "Welcome to ${LocalAppTitle.current}",
            body = "Pick the folder your music is downloaded into to build your library.",
            buttonText = "Choose music folder",
            onButtonClick = onPickFolder,
            modifier = modifier,
        )

        songs == null -> LoadingState(modifier = modifier)

        songs!!.isEmpty() -> EmptyState(
            title = "No songs yet",
            body = "Tap the ⟳ button in the header to scan your folder for music.",
            modifier = modifier,
        )

        else -> UnifiedSongList(
            songs = songs!!,
            player = player,
            capabilities = setOf(
                SongListCapability.PLAY,
                SongListCapability.MULTI_SELECT,
                SongListCapability.EDIT_INFO,
                SongListCapability.DELETE_FROM_LIBRARY,
                SongListCapability.QUEUE_ACTIONS,
            ),
            repo = repo,
            modifier = modifier,
        )
    }
}
