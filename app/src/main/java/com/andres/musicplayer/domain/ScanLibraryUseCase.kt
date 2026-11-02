package com.andres.musicplayer.domain

import android.net.Uri
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.data.scan.MediaScanner

/**
 * Scans the watched folder for new MP3s, adds them to the library, and removes
 * library entries whose files have disappeared from the tree.
 * Returns a small summary for the UI to show.
 */
class ScanLibraryUseCase(
    private val scanner: MediaScanner,
    private val repository: SongRepository,
) {
    data class Summary(
        val added: Int,
        val removed: Int,
        val totalFound: Int,
        /** How many new songs were auto-added to a subfolder-named playlist. */
        val autoPlaylisted: Int,
    )

    suspend operator fun invoke(
        treeUri: Uri,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): Summary {
        val known = repository.getKnownUris() // Map<uri, id>
        val result = scanner.scan(treeUri, known.keys, onProgress)

        // Remove library entries whose files no longer exist in the scanned tree.
        // Skip device-imported (Browse) songs — their content://media uris aren't part
        // of the watched SAF tree, so they'd otherwise be wiped on every scan.
        val foundUris = result.allFoundUris.toSet()
        val orphans = known.entries
            .filter { it.key !in foundUris && !it.key.startsWith("content://media") }
        // Safety net: if the scan found files but recognised *none* of the songs already
        // in the library from this same folder, the likelier story is that file uris
        // changed shape, not that every song was deleted — and wiping the library would
        // take every playlist link with it. Keep them and let the user decide.
        val treePrefix = "$treeUri/document/"
        val knownInTree = known.keys.count { it.startsWith(treePrefix) }
        val orphansInTree = orphans.count { it.key.startsWith(treePrefix) }
        // Inserting would only duplicate the whole library under the new uris, so the
        // scan changes nothing at all in that case.
        if (foundUris.isNotEmpty() && knownInTree > 0 && orphansInTree == knownInTree) {
            return Summary(added = 0, removed = 0, totalFound = result.totalAudioFound, autoPlaylisted = 0)
        }
        val orphanIds = orphans.map { it.value }
        if (orphanIds.isNotEmpty()) repository.deleteSongs(orphanIds)

        // Insert new songs; ids come back in the same order, so we can pair them back up
        // with the subfolder each came from.
        val newScanned = result.newSongs
        val insertedIds = repository.insertSongs(newScanned.map { it.song })

        val insertedPairs = newScanned.zip(insertedIds).filter { it.second != -1L }

        // Auto-add to a playlist named after each song's immediate root subfolder.
        var autoPlaylisted = 0
        insertedPairs
            .filter { it.first.sourceSubfolder != null }
            .groupBy { it.first.sourceSubfolder!! }
            .forEach { (subfolder, entries) ->
                val playlistId = repository.getPlaylistByName(subfolder)?.id
                    ?: repository.createPlaylist(subfolder)
                repository.addSongsToPlaylist(playlistId, entries.map { it.second })
                autoPlaylisted += entries.size
            }

        return Summary(
            added = newScanned.size,
            removed = orphanIds.size,
            totalFound = result.totalAudioFound,
            autoPlaylisted = autoPlaylisted,
        )
    }
}
