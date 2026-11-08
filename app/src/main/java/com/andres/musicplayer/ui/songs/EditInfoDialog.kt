package com.andres.musicplayer.ui.songs

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.ui.art.SongArtwork

/**
 * Edits a song's title / artist / album (handy for YouTube downloads with
 * missing tags). Saves to the library; blank artist/album are stored as none.
 * When [onChangeArtwork] is provided, the cover thumbnail is tappable to pick a new
 * image, which gets embedded into the file (even if it had no art before).
 */
@Composable
fun EditInfoDialog(
    song: Song,
    onDismiss: () -> Unit,
    onSave: (title: String, artist: String?, album: String?) -> Unit,
    /** Writes the picked image into the file; returns whether it worked. */
    onChangeArtwork: (suspend (Uri) -> Boolean)? = null,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist.orEmpty()) }
    var album by remember { mutableStateOf(song.album.orEmpty()) }
    // null = untouched, then saving / saved / failed.
    var artworkStatus by remember { mutableStateOf<String?>(null) }

    val artworkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null && onChangeArtwork != null) {
            artworkStatus = "Saving artwork…"
            scope.launch {
                artworkStatus = if (onChangeArtwork(uri)) {
                    "Artwork saved into the file."
                } else {
                    "Couldn't save artwork into this file (unsupported format or read-only)."
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit info") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (onChangeArtwork != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { artworkPicker.launch("image/*") },
                            contentAlignment = Alignment.BottomEnd,
                        ) {
                            SongArtwork(uri = song.uri, size = 72.dp)
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.PhotoCamera,
                                    contentDescription = "Change artwork",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = artworkStatus ?: "Tap the cover to choose a new thumbnail.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        title.trim(),
                        artist.trim().ifEmpty { null },
                        album.trim().ifEmpty { null },
                    )
                },
                enabled = title.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
