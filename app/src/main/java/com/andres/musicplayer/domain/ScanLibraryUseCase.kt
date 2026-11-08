package com.andres.musicplayer.domain

import android.net.Uri
import android.provider.DocumentsContract
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.data.scan.MediaScanner

/**
 * Scans the watched folder: adds new songs, removes library entries whose files have
 * disappeared, and re-points entries whose file simply turned up under a new uri.
 * Returns a small summary for the UI to show.
 *
 * Removal is the dangerous half, so it only happens on evidence that can be trusted: a
 * listing that read every folder in the tree and found at least one file. Anything less
 * — a revoked permission, an unmounted SD card, a provider hiccup — would otherwise look
 * exactly like "every song was deleted", and take every playlist link with it.
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
        /** Songs whose file moved; they kept their playlists and edits. */
        val moved: Int = 0,
        /** True when part of the folder couldn't be read, so nothing was removed. */
        val incomplete: Boolean = false,
    ) {
        val changedAnything: Boolean get() = added > 0 || removed > 0 || moved > 0
    }

    suspend operator fun invoke(
        treeUri: Uri,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): Summary {
        val known = repository.getKnownUris() // Map<uri, id>
        val result = scanner.scan(treeUri, known.keys, onProgress)

        // Songs the user deleted from the library stay deleted, even though the file is
        // still sitting in the folder.
        val excluded = repository.excludedSongs()
        val excludedUris = excluded.map { it.uri }.toHashSet()
        val excludedDocs = excluded.mapNotNull { it.documentId }.toHashSet()
        val fresh = result.newSongs.filterNot {
            it.song.uri in excludedUris || documentIdOf(it.song.uri) in excludedDocs
        }

        // Library entries whose uri wasn't seen. Device-imported (Browse) songs live
        // outside the watched tree and are never candidates.
        val foundUris = result.allFoundUris.toHashSet()
        val orphans = known.filter { (uri, _) ->
            uri !in foundUris && !uri.startsWith("content://media")
        }.toMutableMap()

        // Before treating an orphan as deleted, see whether its file is simply one of the
        // "new" ones under another uri — the folder re-picked through a different tree
        // (same document id), or the file moved to another subfolder (same title and
        // length). Re-pointing keeps the song's id, so its playlists and edits survive.
        val toInsert = fresh.toMutableList()
        var moved = 0
        if (orphans.isNotEmpty() && toInsert.isNotEmpty()) {
            val orphanByDoc = orphans.entries
                .mapNotNull { (uri, id) -> documentIdOf(uri)?.let { it to id } }
                .toMap()
            val iterator = toInsert.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                val id = documentIdOf(candidate.song.uri)?.let { orphanByDoc[it] } ?: continue
                if (orphans.values.remove(id)) {
                    repository.remapSong(id, candidate.song.uri)
                    iterator.remove()
                    moved++
                }
            }

            if (orphans.isNotEmpty() && toInsert.isNotEmpty()) {
                val orphanKeys = repository.contentKeysFor(orphans.values.toSet())
                    .entries.groupBy({ it.value }, { it.key })
                    .filterValues { it.size == 1 }
                    .mapValues { it.value.single() }
                val newByKey = toInsert.groupBy { contentKey(it.song.title, it.song.durationMs) }
                newByKey.forEach { (key, candidates) ->
                    val id = orphanKeys[key] ?: return@forEach
                    // Only an unambiguous one-to-one match counts as the same file.
                    if (candidates.size != 1 || candidates.single().song.durationMs <= 0) return@forEach
                    if (orphans.values.remove(id)) {
                        repository.remapSong(id, candidates.single().song.uri)
                        toInsert.remove(candidates.single())
                        moved++
                    }
                }
            }
        }

        // Safety net, as before: found files but recognised none of the songs already in
        // the library from this folder, and nothing could be matched up either. The
        // likelier story is that uris changed shape, so change nothing at all.
        val treePrefix = "$treeUri/document/"
        val knownInTree = known.keys.count { it.startsWith(treePrefix) }
        val orphansInTree = orphans.keys.count { it.startsWith(treePrefix) }
        if (foundUris.isNotEmpty() && knownInTree > 0 && orphansInTree == knownInTree && moved == 0) {
            return Summary(added = 0, removed = 0, totalFound = result.totalAudioFound, autoPlaylisted = 0)
        }

        val canRemove = result.complete && foundUris.isNotEmpty()
        val orphanIds = if (canRemove) orphans.values.toList() else emptyList()
        if (orphanIds.isNotEmpty()) repository.deleteSongsFromScan(orphanIds)

        // Insert new songs; ids come back in the same order, so we can pair them back up
        // with the subfolder each came from.
        val insertedIds = repository.insertSongs(toInsert.map { it.song })
        val insertedPairs = toInsert.zip(insertedIds).filter { it.second != -1L }

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
            added = insertedPairs.size,
            removed = orphanIds.size,
            totalFound = result.totalAudioFound,
            autoPlaylisted = autoPlaylisted,
            moved = moved,
            incomplete = !result.complete,
        )
    }

    private fun documentIdOf(uri: String): String? =
        runCatching { DocumentsContract.getDocumentId(Uri.parse(uri)) }.getOrNull()

    companion object {
        /** Uri-independent identity for a track: normalised title + duration (to the second). */
        fun contentKey(title: String, durationMs: Long): String =
            "${title.trim().lowercase()}|${durationMs / 1000}"
    }
}
