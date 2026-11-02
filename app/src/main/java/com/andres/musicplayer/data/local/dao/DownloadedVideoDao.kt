package com.andres.musicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andres.musicplayer.data.local.entity.DownloadedVideo
import kotlinx.coroutines.flow.Flow

/** The "already fetched, never fetch again" ledger the sync diff reads. */
@Dao
interface DownloadedVideoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(video: DownloadedVideo)

    /** The other half of the sync diff (3.3), alongside `DownloadDao.allVideoIds`. */
    @Query("SELECT videoId FROM downloaded_videos")
    suspend fun allVideoIds(): List<String>

    @Query("SELECT * FROM downloaded_videos WHERE videoId = :videoId")
    suspend fun getById(videoId: String): DownloadedVideo?

    @Query("SELECT * FROM downloaded_videos ORDER BY downloadedAt DESC")
    fun getAll(): Flow<List<DownloadedVideo>>

    /**
     * Forgets a video so a later sync fetches it again — the escape hatch for when the
     * user deletes the MP3 from the library and wants it back.
     */
    @Query("DELETE FROM downloaded_videos WHERE videoId = :videoId")
    suspend fun deleteById(videoId: String)
}
