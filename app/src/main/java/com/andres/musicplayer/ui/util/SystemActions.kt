package com.andres.musicplayer.ui.util

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A song whose embedded cover we want to export to the gallery. */
data class ArtSaveRequest(val uri: String, val title: String)

/**
 * Mono audio, done inside the player (see [com.andres.musicplayer.playback.InAppEffectsProcessor]).
 * [isOn] is observable; [granted] is always true now that no special permission is needed,
 * and is kept so callers didn't have to change shape.
 */
class MonoController(val isOn: Boolean, val set: (Boolean) -> Unit) {
    val granted: Boolean get() = true
    fun toggle() = set(!isOn)
}

@Composable
fun rememberMonoController(): MonoController {
    val on by com.andres.musicplayer.playback.PlaybackPrefs.mono.collectAsState()
    return MonoController(isOn = on, set = { com.andres.musicplayer.playback.PlaybackPrefs.setMono(it) })
}

/**
 * Returns a function that saves the embedded artwork of one or more songs to the gallery,
 * handling the Android 9-and-below storage permission and showing a result toast. Use it
 * anywhere a song (or a selection of songs) is shown.
 */
@Composable
fun rememberArtworkSaver(): (List<ArtSaveRequest>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<ArtSaveRequest>>(emptyList()) }

    fun doSave(items: List<ArtSaveRequest>) {
        if (items.isEmpty()) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                items.count { saveArtworkToGallery(context, it.uri, it.title) }
            }
            val message = when {
                saved == 0 -> "No embedded artwork found."
                items.size == 1 -> "Saved cover to your gallery."
                saved == items.size -> "Saved $saved covers to your gallery."
                else -> "Saved $saved of ${items.size} covers."
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val p = pending
        pending = emptyList()
        if (granted) {
            doSave(p)
        } else {
            Toast.makeText(context, "Storage permission is needed to save artwork.", Toast.LENGTH_SHORT).show()
        }
    }

    return { items ->
        val needsLegacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (needsLegacy && !granted) {
            pending = items
            storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            doSave(items)
        }
    }
}
