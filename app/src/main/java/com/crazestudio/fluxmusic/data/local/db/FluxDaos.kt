package com.crazestudio.fluxmusic.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY cachedAt DESC")
    fun observeCachedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY favoritedAt DESC")
    fun observeFavoriteTracks(): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE title LIKE '%' || :query || '%'
           OR artist LIKE '%' || :query || '%'
           OR album LIKE '%' || :query || '%'
        ORDER BY isFavorite DESC, cachedAt DESC
        """
    )
    fun searchCachedTracks(query: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getTracksByIds(ids: List<String>): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET isFavorite = :isFavorite, favoritedAt = :favoritedAt WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean, favoritedAt: Long)

    @Query(
        """
        DELETE FROM tracks
        WHERE isFavorite = 0
          AND id NOT IN (SELECT DISTINCT trackId FROM playlist_tracks)
          AND id NOT IN (SELECT DISTINCT trackId FROM recent_plays)
        """
    )
    suspend fun clearUncachedOrphans()

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeCachedCount(): Flow<Int>
}

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT p.*,
               COUNT(pt.trackId) AS trackCount,
               (
                   SELECT t.thumbnailUrl
                   FROM playlist_tracks pt2
                   INNER JOIN tracks t ON t.id = pt2.trackId
                   WHERE pt2.playlistId = p.id AND t.thumbnailUrl != ''
                   ORDER BY pt2.position ASC
                   LIMIT 1
               ) AS coverThumbnailUrl
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id
        GROUP BY p.id
        ORDER BY p.updatedAt DESC
        """
    )
    fun observePlaylistSummaries(): Flow<List<PlaylistSummary>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON pt.trackId = t.id
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position ASC
        """
    )
    fun observePlaylistTracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE playlists SET updatedAt = :updatedAt WHERE id = :playlistId")
    suspend fun touchPlaylist(playlistId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getNextPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackRef(ref: PlaylistTrackCrossRef): Long

    @Transaction
    suspend fun addTrackToPlaylist(playlistId: Long, trackId: String) {
        val pos = getNextPosition(playlistId)
        insertTrackRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = pos))
        touchPlaylist(playlistId)
    }

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String)
}

@Dao
interface HistoryDao {
    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN recent_plays rp ON rp.trackId = t.id
        ORDER BY rp.playedAt DESC
        LIMIT 30
        """
    )
    fun observeRecentTracks(): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecentPlay(entry: RecentPlayEntity)

    @Query("DELETE FROM recent_plays")
    suspend fun clearRecentPlays()

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 12")
    fun observeSearchHistory(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchQuery(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteSearchQuery(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()
}
