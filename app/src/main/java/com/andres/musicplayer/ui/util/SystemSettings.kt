package com.andres.musicplayer.ui.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings

/**
 * Toggles Android's built-in "Mono audio" downmix in the background, without leaving the
 * app or interrupting playback. This writes the system [master_mono] flag.
 *
 * Android locks master_mono behind WRITE_SECURE_SETTINGS — the ordinary "Modify system
 * settings" (WRITE_SETTINGS) permission is *not* enough (the OS silently rejects the
 * write), and no in-app popup can grant WRITE_SECURE_SETTINGS. So it must be granted once
 * over adb: `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`. After
 * that the toggle works instantly, in the background, and survives app updates.
 */
object MonoAudio {
    // Settings.System.MASTER_MONO is @hide; the underlying key is "master_mono".
    private const val KEY = "master_mono"

    const val GRANT_COMMAND =
        "adb shell pm grant com.andres.musicplayer android.permission.WRITE_SECURE_SETTINGS"

    fun isEnabled(context: Context): Boolean {
        val cr = context.contentResolver
        // OEM ROMs put master_mono in different namespaces — treat "on" as set anywhere.
        return Settings.System.getInt(cr, KEY, 0) == 1 ||
            Settings.Secure.getInt(cr, KEY, 0) == 1 ||
            Settings.Global.getInt(cr, KEY, 0) == 1
    }

    /** Whether the WRITE_SECURE_SETTINGS grant is in place (i.e. the toggle will work). */
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Sets mono on/off and returns whether it actually took effect (verified by reading
     * the value back). Different OEM ROMs classify master_mono in the System, Secure, or
     * Global namespace, so we attempt all three and let the read-back decide.
     */
    fun setEnabled(context: Context, on: Boolean): Boolean {
        val cr = context.contentResolver
        val v = if (on) 1 else 0
        val writes = listOf<() -> Unit>(
            { Settings.System.putInt(cr, KEY, v) },
            { Settings.Secure.putInt(cr, KEY, v) },
            { Settings.Global.putInt(cr, KEY, v) },
        )
        writes.forEach { write ->
            runCatching { write() }.onFailure {
                android.util.Log.w("MonoAudio", "master_mono write rejected: ${it.message}")
            }
        }
        return isEnabled(context) == on
    }
}

/**
 * Extracts a song's embedded cover art and saves it to the phone's gallery
 * (Pictures/MusicPlayer). Returns true on success, false if the file has no embedded
 * art or the write failed. On Android 10+ this uses scoped storage (no permission).
 */
fun saveArtworkToGallery(context: Context, songUri: String, title: String): Boolean {
    val retriever = MediaMetadataRetriever()
    val bytes = try {
        retriever.setDataSource(context, Uri.parse(songUri))
        retriever.embeddedPicture
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    } ?: return false

    val safeName = title.ifBlank { "cover" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$safeName.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/MusicPlayer",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    return runCatching {
        val item = resolver.insert(collection, values) ?: return false
        resolver.openOutputStream(item)?.use { it.write(bytes) } ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(item, values, null, null)
        }
        true
    }.getOrDefault(false)
}
