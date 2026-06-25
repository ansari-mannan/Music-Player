package com.andres.musicplayer.data.prefs

import android.content.Context

/**
 * Remembers which folder the user picked to watch for MP3s. Stores the SAF tree
 * URI as a string in SharedPreferences so the choice survives app restarts
 * (paired with a persisted URI permission taken at pick time).
 */
class FolderPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var watchedFolderUri: String?
        get() = prefs.getString(KEY_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_FOLDER_URI, value).apply()

    companion object {
        private const val PREFS_NAME = "library_prefs"
        private const val KEY_FOLDER_URI = "watched_folder_uri"
    }
}
