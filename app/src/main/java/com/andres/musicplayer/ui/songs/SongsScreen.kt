package com.andres.musicplayer.ui.songs

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.ui.app.LocalAppTitle
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.common.LoadingState
import com.andres.musicplayer.ui.player.PlayerState

/** How the Tracks list is ordered. Remembered between launches. */
enum class SongSort(val label: String) {
    RECENT("Recently added"),
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    LONGEST("Longest first"),
}

/** All songs in the library, with search and sorting. */
@Composable
fun SongsScreen(
    repo: SongRepository,
    player: PlayerState,
    folderUri: String?,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    // null = first load in progress (avoids an empty-state flash).
    val songs by repo.allSongs.collectAsState(initial = null)
    var query by rememberSaveable { mutableStateOf("") }
    var sort by remember { mutableStateOf(loadSort(context)) }
    var sortMenu by remember { mutableStateOf(false) }

    val shown = remember(songs, query, sort) { songs?.let { filterAndSort(it, query, sort) } }

    when {
        folderUri == null -> EmptyState(
            title = "Welcome to ${LocalAppTitle.current}",
            body = "Your library lives in a folder on your phone. Tap below — the picker opens " +
                "in your Music folder, so just tap \"Use this folder\" and then \"Allow\".",
            buttonText = "Use my Music folder",
            onButtonClick = onPickFolder,
            modifier = modifier,
        )

        songs == null || shown == null -> LoadingState(modifier = modifier)

        songs!!.isEmpty() -> EmptyState(
            title = "No songs yet",
            body = "Tap the ⟳ button in the header to scan your folder for music.",
            modifier = modifier,
        )

        else -> Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search ${songs!!.size} songs") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; focus.clearFocus() }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { if (shown!!.isNotEmpty()) player.playShuffled(shown!!) }) {
                    Icon(Icons.Default.Shuffle, contentDescription = "Shuffle these songs")
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SongSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = { RadioButton(selected = sort == option, onClick = null) },
                                onClick = {
                                    sort = option
                                    saveSort(context, option)
                                    sortMenu = false
                                },
                            )
                        }
                    }
                }
            }
            if (shown!!.isEmpty()) {
                Text(
                    text = "Nothing matches \"$query\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                UnifiedSongList(
                    songs = shown!!,
                    player = player,
                    capabilities = setOf(
                        SongListCapability.PLAY,
                        SongListCapability.MULTI_SELECT,
                        SongListCapability.EDIT_INFO,
                        SongListCapability.DELETE_FROM_LIBRARY,
                        SongListCapability.QUEUE_ACTIONS,
                    ),
                    repo = repo,
                )
            }
        }
    }
}

/** Matches every word of [query] against title, artist and album, in any order. */
internal fun filterAndSort(songs: List<Song>, query: String, sort: SongSort): List<Song> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val filtered = if (words.isEmpty()) {
        songs
    } else {
        songs.filter { song ->
            val haystack = "${song.title} ${song.artist.orEmpty()} ${song.album.orEmpty()}".lowercase()
            words.all { it in haystack }
        }
    }
    return when (sort) {
        SongSort.RECENT -> filtered.sortedByDescending { it.dateAdded }
        SongSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
        SongSort.ARTIST -> filtered.sortedWith(
            compareBy<Song>({ it.artist.isNullOrBlank() }, { it.artist?.lowercase() }, { it.title.lowercase() }),
        )
        SongSort.ALBUM -> filtered.sortedWith(
            compareBy<Song>({ it.album.isNullOrBlank() }, { it.album?.lowercase() }, { it.title.lowercase() }),
        )
        SongSort.LONGEST -> filtered.sortedByDescending { it.durationMs }
    }
}

private const val SORT_PREFS = "library_view"
private const val KEY_SORT = "tracks_sort"

private fun loadSort(context: Context): SongSort =
    context.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE).getString(KEY_SORT, null)
        ?.let { name -> SongSort.entries.firstOrNull { it.name == name } }
        ?: SongSort.RECENT

private fun saveSort(context: Context, sort: SongSort) =
    context.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SORT, sort.name).apply()
