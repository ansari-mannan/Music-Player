package com.andres.musicplayer.data.browse

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.andres.musicplayer.data.local.entity.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One audio file discovered on the device (not necessarily in the library). */
data class BrowseAudio(
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val folder: String,
    val uri: String,
) {
    /** Transient [Song] for playback (negative id avoids clashing with library ids). */
    fun toSong(): Song = Song(
        id = -id,
        uri = uri,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        dateAdded = 0,
    )
}

/** Outcome of a device-wide audio scan: distinguishes "no permission" from "no files". */
sealed class BrowseResult {
    data class Success(val items: List<BrowseAudio>) : BrowseResult()
    object PermissionDenied : BrowseResult()
    object Empty : BrowseResult()
}

/** Reads all music on the device via MediaStore (needs READ_MEDIA_AUDIO). */
class MediaStoreBrowser(private val context: Context) {

    suspend fun loadAll(): BrowseResult = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val hasBucket = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val folderColumn =
            if (hasBucket) MediaStore.Audio.Media.BUCKET_DISPLAY_NAME else MediaStore.Audio.Media.DATA

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            folderColumn,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val out = mutableListOf<BrowseAudio>()
        val cursor = context.contentResolver.query(collection, projection, selection, null, sortOrder)
            ?: return@withContext BrowseResult.PermissionDenied
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val folderCol = c.getColumnIndexOrThrow(folderColumn)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val rawFolder = c.getString(folderCol)
                val folder = when {
                    rawFolder.isNullOrBlank() -> "Unknown folder"
                    hasBucket -> rawFolder
                    else -> File(rawFolder).parentFile?.name ?: "Unknown folder"
                }
                val title = c.getString(titleCol)?.takeIf { it.isNotBlank() }
                    ?: c.getString(nameCol) ?: "Unknown"
                val artist = c.getString(artistCol)
                    ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                out += BrowseAudio(
                    id = id,
                    title = title,
                    artist = artist,
                    album = c.getString(albumCol)?.takeIf { it.isNotBlank() },
                    durationMs = c.getLong(durationCol),
                    folder = folder,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                )
            }
        }
        if (out.isEmpty()) BrowseResult.Empty else BrowseResult.Success(out)
    }
}
