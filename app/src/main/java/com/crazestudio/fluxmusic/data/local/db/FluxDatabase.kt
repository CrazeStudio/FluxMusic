package com.crazestudio.fluxmusic.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        RecentPlayEntity::class,
        SearchHistoryEntity::class,
        LyricsCacheEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class FluxDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun lyricsDao(): LyricsDao

    companion object {
        @Volatile
        private var instance: FluxDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stream_cache (
                        videoId TEXT NOT NULL,
                        quality TEXT NOT NULL,
                        streamUrl TEXT NOT NULL,
                        expiresAt INTEGER NOT NULL,
                        PRIMARY KEY(videoId, quality)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stream_cache ADD COLUMN userAgent TEXT NOT NULL DEFAULT ''")
                db.execSQL("DELETE FROM stream_cache")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lyrics_cache (
                        videoId TEXT NOT NULL PRIMARY KEY,
                        trackName TEXT NOT NULL,
                        artistName TEXT NOT NULL,
                        syncedLyrics TEXT,
                        plainLyrics TEXT,
                        instrumental INTEGER NOT NULL DEFAULT 0,
                        fetchedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Stream URLs are signed and session-bound; they are no longer persisted.
                db.execSQL("DROP TABLE IF EXISTS stream_cache")
            }
        }

        fun getInstance(context: Context): FluxDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FluxDatabase::class.java,
                    "flux_music.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
