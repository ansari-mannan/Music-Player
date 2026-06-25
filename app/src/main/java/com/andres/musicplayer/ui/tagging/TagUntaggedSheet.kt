package com.andres.musicplayer.ui.tagging

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.data.local.entity.Tag
import com.andres.musicplayer.ui.art.SongArtwork
import com.andres.musicplayer.ui.songs.TagSheetContent
import kotlinx.coroutines.launch

/**
 * A postpone-able flow for putting moods on untagged songs. Steps through the songs
 * that had no tags when it opened, one at a time. Tag as many as you like, then close
 * ("Done for now") and pick it back up later — the queue is a fixed snapshot, so songs
 * you tag stay in place rather than vanishing under you mid-session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagUntaggedSheet(
    repo: SongRepository,
    onDismiss: () -> Unit,
) {
    val allSongs by repo.allSongsWithTags.collectAsState(initial = emptyList())
    val allTags by repo.allTags.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Snapshot the untagged ids once so the list doesn't shrink as we tag each one.
    val queueIds = remember { mutableStateListOf<Long>() }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(allSongs) {
        if (!initialized && allSongs.isNotEmpty()) {
            queueIds.addAll(allSongs.filter { it.tags.isEmpty() }.map { it.song.id })
            initialized = true
        }
    }
    var index by remember { mutableStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val currentId = queueIds.getOrNull(index)
        val current = allSongs.find { it.song.id == currentId }

        when {
            !initialized -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            current == null -> {
                // Either there were none, or we've reached the end of the queue.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("All caught up!", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (queueIds.isEmpty()) {
                            "Every song already has at least one mood."
                        } else {
                            "That's the last one. Nice work."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onDismiss) { Text("Done") }
                }
            }

            else -> {
                Text(
                    text = "Tag untagged songs · ${index + 1} of ${queueIds.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SongArtwork(uri = current.song.uri, size = 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = current.song.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = current.song.artist ?: "Unknown artist",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                TagSheetContent(
                    title = "Moods",
                    subtitle = "Tap to apply. Create a new mood below.",
                    allTags = allTags,
                    appliedTagIds = current.tags.map { it.id }.toSet(),
                    onToggleTag = { tagId, applied ->
                        scope.launch {
                            if (applied) repo.untagSong(current.song.id, tagId)
                            else repo.tagSong(current.song.id, tagId)
                        }
                    },
                    onCreateTag = { name ->
                        if (name.isNotBlank()) {
                            scope.launch {
                                val newId = repo.insertTag(Tag(name = name))
                                val tagId = if (newId != -1L) newId else repo.getTagByName(name)?.id
                                if (tagId != null) repo.tagSong(current.song.id, tagId)
                            }
                        }
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val currentSongId = current.song.id
                    // Save whatever moods are on this song into its file as we leave it.
                    fun persistCurrent() {
                        scope.launch { repo.writeMetadataToFile(currentSongId) }
                    }
                    TextButton(onClick = { persistCurrent(); onDismiss() }) { Text("Done for now") }
                    Spacer(Modifier.weight(1f))
                    val isLast = index >= queueIds.lastIndex
                    val tagged = current.tags.isNotEmpty()
                    Button(onClick = { persistCurrent(); index++ }) {
                        Text(
                            when {
                                isLast -> "Finish"
                                tagged -> "Next"
                                else -> "Skip"
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
