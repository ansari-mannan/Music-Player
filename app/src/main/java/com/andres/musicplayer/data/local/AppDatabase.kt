package com.andres.musicplayer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.andres.musicplayer.data.local.dao.PlaylistDao
import com.andres.musicplayer.data.local.dao.SongDao
import com.andres.musicplayer.data.local.dao.TagDao
import com.andres.musicplayer.data.local.entity.Playlist
import com.andres.musicplayer.data.local.entity.PlaylistSongCrossRef
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.data.local.entity.SongTagCrossRef
import com.andres.musicplayer.data.local.entity.Tag

@Database(
    entities = [
        Song::class,
        Tag::class,
        SongTagCrossRef::class,
        Playlist::class,
        PlaylistSongCrossRef::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun tagDao(): TagDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        private const val DB_NAME = "andres_music_player.db"

        /** Adds the nullable `album` column without touching existing rows. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN album TEXT")
            }
        }

        /** Adds the playlist tables. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlists` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_song_cross_ref` " +
                        "(`playlistId` INTEGER NOT NULL, `songId` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `songId`), " +
                        "FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`songId`) REFERENCES `songs`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_playlist_song_cross_ref_songId` " +
                        "ON `playlist_song_cross_ref` (`songId`)",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
