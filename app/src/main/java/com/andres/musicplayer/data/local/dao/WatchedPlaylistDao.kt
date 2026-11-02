package com.andres.musicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.andres.musicplayer.data.local.entity.WatchedPlaylist
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchedPlaylistDao {

    @Query("SELECT * FROM watched_playlists ORDER BY title COLLATE NOCASE ASC")
    fun getAll(): Flow<List<WatchedPlaylist>>

    /** The playlists the user opted into — what the sync pass (Section 3.3) walks. */
    @Query("SELECT * FROM watched_playlists WHERE isWatched = 1 ORDER BY title COLLATE NOCASE ASC")
    suspend fun getWatched(): List<WatchedPlaylist>

    @Query("UPDATE watched_playlists SET isWatched = :watched WHERE playlistId = :playlistId")
    suspend fun setWatched(playlistId: String, watched: Boolean)

    @Query("UPDATE watched_playlists SET lastCheckedAt = :at WHERE playlistId = :playlistId")
    suspend fun markChecked(playlistId: String, at: Long)

    @Query("DELETE FROM watched_playlists")
    suspend fun deleteAll()

    // --- Pieces of [replaceRemote]; not meant to be called individually. ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringExisting(playlists: List<WatchedPlaylist>)

    @Query(
        "UPDATE watched_playlists SET title = :title, thumbnailUrl = :thumbnailUrl, " +
            "itemCount = :itemCount WHERE playlistId = :playlistId",
    )
    suspend fun updateDetails(
        playlistId: String,
        title: String,
        thumbnailUrl: String,
        itemCount: Int,
    )

    @Query("DELETE FROM watched_playlists WHERE playlistId NOT IN (:playlistIds)")
    suspend fun deleteMissing(playlistIds: List<String>)

    /**
     * Reconciles the local mirror with what the API just returned: new playlists are
     * added unwatched, existing ones have their details refreshed (deliberately *not*
     * their `isWatched` or `lastCheckedAt` — those are local state), and playlists the
     * user deleted on YouTube are dropped.
     */
    @Transaction
    suspend fun replaceRemote(remote: List<WatchedPlaylist>) {
        if (remote.isEmpty()) {
            deleteAll()
            return
        }
        insertIgnoringExisting(remote)
        remote.forEach {
            updateDetails(it.playlistId, it.title, it.thumbnailUrl, it.itemCount)
        }
        deleteMissing(remote.map { it.playlistId })
    }
}
