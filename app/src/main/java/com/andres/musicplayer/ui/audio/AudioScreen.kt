package com.andres.musicplayer.ui.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.ui.app.AudioTab
import com.andres.musicplayer.ui.artists.ArtistsScreen
import com.andres.musicplayer.ui.albums.AlbumsScreen
import com.andres.musicplayer.ui.mix.MixScreen
import com.andres.musicplayer.ui.moods.MoodsScreen
import com.andres.musicplayer.ui.player.PlayerState
import com.andres.musicplayer.ui.playlists.PlaylistsScreen
import com.andres.musicplayer.ui.songs.SongsScreen

/** The Audio section: top tabs over Artists / Albums / Tracks / Moods / Playlists. */
@Composable
fun AudioScreen(
    repo: SongRepository,
    player: PlayerState,
    folderUri: String?,
    onPickFolder: () -> Unit,
    onMoodClick: (tagId: Long) -> Unit,
    onArtistClick: (name: String) -> Unit,
    onAlbumClick: (name: String) -> Unit,
    onOpenPlaylist: (id: Long) -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = AudioTab.entries
    var selected by rememberSaveable { mutableStateOf(AudioTab.TRACKS) }

    Column(modifier = modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = selected.ordinal, edgePadding = 8.dp) {
            tabs.forEach { tab ->
                Tab(
                    selected = selected == tab,
                    onClick = { selected = tab },
                    text = { Text(tab.label) },
                )
            }
        }

        when (selected) {
            AudioTab.ARTISTS -> ArtistsScreen(repo = repo, onArtistClick = onArtistClick)
            AudioTab.ALBUMS -> AlbumsScreen(repo = repo, onAlbumClick = onAlbumClick)
            AudioTab.TRACKS -> SongsScreen(
                repo = repo,
                player = player,
                folderUri = folderUri,
                onPickFolder = onPickFolder,
            )
            AudioTab.MOODS -> MoodsScreen(repo = repo, onMoodClick = onMoodClick)
            AudioTab.MIXES -> MixScreen(
                repo = repo,
                player = player,
                onOpenNowPlaying = onOpenNowPlaying,
            )
            AudioTab.PLAYLISTS -> PlaylistsScreen(repo = repo, onOpenPlaylist = onOpenPlaylist)
        }
    }
}
