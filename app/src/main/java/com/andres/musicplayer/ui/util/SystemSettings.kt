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
