package com.andres.musicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andres.musicplayer.data.local.entity.ExcludedSong
import com.andres.musicplayer.data.local.entity.IgnoredVideo
import kotlinx.coroutines.flow.Flow

/** Things the user removed on purpose, so automatic processes don't bring them back. */
@Dao
interface ExclusionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun excludeSongs(songs: List<ExcludedSong>)

    @Query("SELECT * FROM excluded_songs")
    suspend fun excludedSongs(): List<ExcludedSong>

    @Query("SELECT COUNT(*) FROM excluded_songs")
    fun excludedSongCount(): Flow<Int>

    @Query("DELETE FROM excluded_songs")
    suspend fun clearExcludedSongs()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun ignoreVideo(video: IgnoredVideo)

    @Query("DELETE FROM ignored_videos WHERE videoId = :videoId")
    suspend fun unignoreVideo(videoId: String)

    @Query("SELECT videoId FROM ignored_videos")
    suspend fun ignoredVideoIds(): List<String>

    @Query("SELECT COUNT(*) FROM ignored_videos WHERE videoId = :videoId")
    suspend fun isIgnored(videoId: String): Int
}
