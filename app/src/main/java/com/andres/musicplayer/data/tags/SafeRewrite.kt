package com.andres.musicplayer.data.tags

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Makes an in-place rewrite of a song file survivable.
 *
 * Tag writers have to truncate the document and stream the new bytes into it — SAF has no
 * atomic replace. If the app dies, or storage errors, halfway through, the song would be
 * left cut short. So before every rewrite the original is copied into app storage with a
 * journal entry naming its uri:
 *
 * - write succeeds → backup and journal are deleted;
 * - write fails → the original is copied back straight away;
 * - process dies mid-write → [recover] finds the journal on the next launch and copies
 *   the original back.
 *
 * The worst case is therefore losing the *edit*, never the song.
 */
object SafeRewrite {

    private const val TAG = "SafeRewrite"
    private const val DIR = "pending_rewrites"

    /**
     * Runs [rewrite] (which overwrites the document at [uriString]) with a backup in place.
     * Returns whatever [rewrite] returned; on false or an exception the original is restored.
     */
    fun run(context: Context, uriString: String, rewrite: () -> Boolean): Boolean {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val key = "${Integer.toHexString(uriString.hashCode())}_${System.nanoTime()}"
        val backup = File(dir, "$key.bak")
        val journal = File(dir, "$key.uri")
        val uri = Uri.parse(uriString)

        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                backup.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
        if (!copied || backup.length() == 0L) {
            backup.delete()
            return false
        }
        // Only now does a backup worth restoring exist.
        journal.writeText(uriString)

        val ok = try {
            rewrite()
        } catch (e: Exception) {
            Log.w(TAG, "Rewrite threw for $uriString", e)
            false
        }
        if (ok) {
            journal.delete()
            backup.delete()
            return true
        }
        if (restore(context, uri, backup)) {
            journal.delete()
            backup.delete()
        }
        return false
    }

    /**
     * Puts back any file whose rewrite was interrupted by the process dying. Call once at
     * startup, before anything reads the library's files. Returns how many were restored.
     */
    fun recover(context: Context): Int {
        val dir = File(context.filesDir, DIR)
        val files = dir.listFiles() ?: return 0
        var restored = 0
        files.filter { it.name.endsWith(".uri") }.forEach { journal ->
            val backup = File(dir, journal.name.removeSuffix(".uri") + ".bak")
            val uriString = runCatching { journal.readText() }.getOrNull()
            if (uriString == null || !backup.exists()) {
                journal.delete()
                backup.delete()
                return@forEach
            }
            if (restore(context, Uri.parse(uriString), backup)) {
                restored++
                journal.delete()
                backup.delete()
            }
            // A failed restore keeps both, to try again next launch.
        }
        // A backup with no journal was still being copied when the process died; the
        // original was never touched.
        dir.listFiles()
            ?.filter { it.name.endsWith(".bak") && !File(dir, it.name.removeSuffix(".bak") + ".uri").exists() }
            ?.forEach { it.delete() }
        return restored
    }

    private fun restore(context: Context, uri: Uri, backup: File): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            backup.inputStream().use { it.copyTo(out) }
        } != null
    }.onFailure { Log.w(TAG, "Couldn't restore $uri", it) }.getOrDefault(false)
}
