package com.andres.musicplayer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverters
import com.andres.musicplayer.data.local.dao.DownloadDao
import com.andres.musicplayer.data.local.dao.DownloadedVideoDao
import com.andres.musicplayer.data.local.dao.PlaylistDao
import com.andres.musicplayer.data.local.dao.SongDao
import com.andres.musicplayer.data.local.dao.WatchedPlaylistDao
import com.andres.musicplayer.data.local.entity.DownloadEntry
import com.andres.musicplayer.data.local.entity.DownloadedVideo
import com.andres.musicplayer.data.local.entity.Playlist
import com.andres.musicplayer.data.local.entity.PlaylistSongCrossRef
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.data.local.entity.WatchedPlaylist

@Database(
    entities = [
        Song::class,
        Playlist::class,
        PlaylistSongCrossRef::class,
        WatchedPlaylist::class,
        DownloadEntry::class,
        DownloadedVideo::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun watchedPlaylistDao(): WatchedPlaylistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun downloadedVideoDao(): DownloadedVideoDao

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

        /**
         * Drops the mood-tagging tables. Mood tagging was removed outright, so this is a
         * destructive migration by design — the mood labels themselves are gone, while
         * songs, playlists and their ordering are untouched. Moods previously written
         * into MP3 ID3 tags are deliberately left in those files.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `song_tag_cross_ref`")
                db.execSQL("DROP TABLE IF EXISTS `tags`")
            }
        }

        /**
         * Adds the YouTube playlist picker's table. Purely additive — nothing existing
         * is touched. The download tables from the task spec land in a later version,
         * once the download service that writes them exists.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watched_playlists` " +
                        "(`playlistId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`thumbnailUrl` TEXT NOT NULL, `itemCount` INTEGER NOT NULL, " +
                        "`isWatched` INTEGER NOT NULL, `lastCheckedAt` INTEGER, " +
                        "PRIMARY KEY(`playlistId`))",
                )
            }
        }

        /**
         * Adds the download queue and the downloaded-video ledger. Additive again —
         * nothing existing is touched.
         *
         * `download_entries.sourcePlaylistId` cascades from `watched_playlists`: the
         * queue is transient work-tracking, so deleting a playlist on YouTube discards
         * pending (and historical) entries for it. `downloaded_videos` deliberately has
         * no such key, so it keeps remembering what is already on disk and nothing gets
         * re-downloaded.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `download_entries` " +
                        "(`id` TEXT NOT NULL, `videoId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`thumbnailUrl` TEXT NOT NULL, `sourcePlaylistId` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, `bytesDownloaded` INTEGER NOT NULL, " +
                        "`totalBytes` INTEGER, `failureReason` TEXT, " +
                        "`addedAt` INTEGER NOT NULL, `completedAt` INTEGER, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`sourcePlaylistId`) REFERENCES `watched_playlists`(`playlistId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_download_entries_sourcePlaylistId` " +
                        "ON `download_entries` (`sourcePlaylistId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloaded_videos` " +
                        "(`videoId` TEXT NOT NULL, `sourcePlaylistId` TEXT NOT NULL, " +
                        "`filePath` TEXT NOT NULL, `downloadedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`videoId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_downloaded_videos_sourcePlaylistId` " +
                        "ON `downloaded_videos` (`sourcePlaylistId`)",
                )
            }
        }

        /**
         * Drops the `download_entries` → `watched_playlists` foreign key and makes
         * `sourcePlaylistId` nullable, so a hand-started download has something valid to
         * store and so unwatching a playlist stops cascading away its finished-download
         * history. SQLite can't alter a constraint in place, hence the recreate-and-copy.
         *
         * Also relaxes `downloaded_videos.sourcePlaylistId` to nullable for the same
         * reason. Both tables are empty in practice (v6 shipped with nothing writing to
         * them), but the copy is done properly rather than relying on that.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `download_entries_new` " +
                        "(`id` TEXT NOT NULL, `videoId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`thumbnailUrl` TEXT NOT NULL, `sourcePlaylistId` TEXT, " +
                        "`state` TEXT NOT NULL, `bytesDownloaded` INTEGER NOT NULL, " +
                        "`totalBytes` INTEGER, `failureReason` TEXT, " +
                        "`addedAt` INTEGER NOT NULL, `completedAt` INTEGER, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "INSERT INTO `download_entries_new` SELECT `id`, `videoId`, `title`, " +
                        "`thumbnailUrl`, `sourcePlaylistId`, `state`, `bytesDownloaded`, " +
                        "`totalBytes`, `failureReason`, `addedAt`, `completedAt` " +
                        "FROM `download_entries`",
                )
                db.execSQL("DROP TABLE `download_entries`")
                db.execSQL("ALTER TABLE `download_entries_new` RENAME TO `download_entries`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_download_entries_sourcePlaylistId` " +
                        "ON `download_entries` (`sourcePlaylistId`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloaded_videos_new` " +
                        "(`videoId` TEXT NOT NULL, `sourcePlaylistId` TEXT, " +
                        "`filePath` TEXT NOT NULL, `downloadedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`videoId`))",
                )
                db.execSQL(
                    "INSERT INTO `downloaded_videos_new` SELECT `videoId`, " +
                        "`sourcePlaylistId`, `filePath`, `downloadedAt` FROM `downloaded_videos`",
                )
                db.execSQL("DROP TABLE `downloaded_videos`")
                db.execSQL("ALTER TABLE `downloaded_videos_new` RENAME TO `downloaded_videos`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_downloaded_videos_sourcePlaylistId` " +
                        "ON `downloaded_videos` (`sourcePlaylistId`)",
                )
            }
        }

        /**
         * Adds `download_entries.hasEmbeddedArt`, so the Completed list can tell artwork
         * that is really in the file from the playlist listing's thumbnail.
         *
         * Existing rows default to 0, i.e. "no artwork". That is honest for the history
         * this lands on — every download taken before the m4a-first container fix was a
         * webm, which cannot carry a cover at all — and no attempt is made to go back and
         * inspect old files, which is a re-scan, not a migration.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `download_entries` " +
                        "ADD COLUMN `hasEmbeddedArt` INTEGER NOT NULL DEFAULT 0",
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
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
                    .build()
                    .also { instance = it }
            }
    }
}
