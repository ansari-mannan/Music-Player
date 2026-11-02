package com.andres.musicplayer.data.prefs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the live play queue + position so playback can be restored after the
 * process is killed. Written from [com.andres.musicplayer.playback.PlaybackService]: the whole queue
 * when it changes, and just the position on a periodic tick while playing (so abrupt
 * death loses at most a few seconds) and read on startup by both the
 * service (to rebuild the ExoPlayer queue) and PlayerState (to rebuild the UI queue).
 *
 * Each item stores its uri + title + artist directly (not just a song id), so restore
 * needs no database lookup and still works if the song was later removed from the
 * library — and any item whose uri no longer resolves is simply skipped on restore.
 */
class PlaybackStatePreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Item(val id: Long, val uri: String, val title: String, val artist: String?)
    data class State(
        val items: List<Item>,
        val index: Int,
        val positionMs: Long,
        val wasPlaying: Boolean,
    )

    fun save(state: State) {
        val array = JSONArray()
        state.items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("uri", item.uri)
                    put("title", item.title)
                    put("artist", item.artist ?: JSONObject.NULL)
                },
            )
        }
        val obj = JSONObject().apply {
            put("index", state.index)
            put("position", state.positionMs)
            put("playing", state.wasPlaying)
            put("items", array)
        }
        prefs.edit()
            .putString(KEY_STATE, obj.toString())
            .putInt(KEY_INDEX, state.index)
            .putLong(KEY_POSITION, state.positionMs)
            .apply()
    }

    /**
     * Records only where playback is, leaving the saved queue alone. This is what the
     * periodic tick calls: re-serialising a long queue to JSON every few seconds just to
     * move one number was the bulk of this class's writes.
     */
    fun savePosition(index: Int, positionMs: Long) {
        prefs.edit()
            .putInt(KEY_INDEX, index)
            .putLong(KEY_POSITION, positionMs)
            .apply()
    }

    fun load(): State? {
        val raw = prefs.getString(KEY_STATE, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val array = obj.getJSONArray("items")
            val items = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Item(
                    id = o.getLong("id"),
                    uri = o.getString("uri"),
                    title = o.getString("title"),
                    artist = if (o.isNull("artist")) null else o.getString("artist"),
                )
            }
            State(
                items = items,
                // The standalone keys are newer than the JSON whenever both exist.
                index = prefs.getInt(KEY_INDEX, obj.getInt("index")),
                positionMs = prefs.getLong(KEY_POSITION, obj.getLong("position")),
                wasPlaying = obj.optBoolean("playing", false),
            )
        }.getOrNull()?.takeIf { it.items.isNotEmpty() }
    }

    fun clear() = prefs.edit().remove(KEY_STATE).remove(KEY_INDEX).remove(KEY_POSITION).apply()

    companion object {
        private const val PREFS_NAME = "playback_state_prefs"
        private const val KEY_STATE = "state_json"
        private const val KEY_INDEX = "state_index"
        private const val KEY_POSITION = "state_position"
    }
}
