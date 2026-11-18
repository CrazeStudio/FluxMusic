package com.crazestudio.fluxmusic.data.local.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.crazestudio.fluxmusic.data.model.Track

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val thumbnailUrl: String,
    val isFavorite: Boolean = false,
    val favoritedAt: Long = 0L,
    val cachedAt: Long = System.currentTimeMillis(),
    val languageHint: String = ""
) {
    fun toDomain(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        isFavorite = isFavorite,
        languageHint = languageHint
    )

    companion object {
        fun fromDomain(track: Track, existingFavorite: Boolean = track.isFavorite, favoritedAt: Long = 0L): TrackEntity =
            TrackEntity(
                id = track.id,
                title = track.safeTitle,
                artist = track.safeArtist,
                album = track.safeAlbum,
                durationMs = track.durationMs,
                thumbnailUrl = track.thumbnailUrl,
                isFavorite = existingFavorite,
                favoritedAt = favoritedAt,
                cachedAt = System.currentTimeMillis(),
                languageHint = track.languageHint
            )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId"), Index("playlistId", "position")]
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: String,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "recent_plays")
data class RecentPlayEntity(
    @PrimaryKey val trackId: String,
    val playedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long = System.currentTimeMillis()
)

data class PlaylistSummary(
    @Embedded val playlist: PlaylistEntity,
    val trackCount: Int,
    val coverThumbnailUrl: String?
)

@Entity(
    tableName = "stream_cache",
    primaryKeys = ["videoId", "quality"]
)
data class StreamCacheEntity(
    val videoId: String,
    val quality: String,
    val streamUrl: String,
    val userAgent: String = "",
    val expiresAt: Long
)

@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey val videoId: String,
    val trackName: String,
    val artistName: String,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val instrumental: Boolean = false,
    val fetchedAt: Long = System.currentTimeMillis()
)
