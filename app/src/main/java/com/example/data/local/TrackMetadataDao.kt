package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entity.TrackMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackMetadataDao {
    @Query("SELECT * FROM track_metadata ORDER BY title ASC")
    fun getAllTracks(): Flow<List<TrackMetadataEntity>>

    @Query("SELECT * FROM track_metadata WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteTracks(): Flow<List<TrackMetadataEntity>>

    @Query("SELECT * FROM track_metadata WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 10")
    fun getRecentlyPlayed(): Flow<List<TrackMetadataEntity>>

    @Query("SELECT * FROM track_metadata WHERE fileSha256 = :hash LIMIT 1")
    suspend fun getTrackByHash(hash: String): TrackMetadataEntity?

    @Query("SELECT * FROM track_metadata WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR genre LIKE '%' || :query || '%'")
    suspend fun searchCatalog(query: String): List<TrackMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<TrackMetadataEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(track: TrackMetadataEntity)

    @Update
    suspend fun update(track: TrackMetadataEntity)

    @Query("UPDATE track_metadata SET isFavorite = CASE WHEN isFavorite = 1 THEN 0 ELSE 1 END WHERE fileSha256 = :hash")
    suspend fun toggleFavorite(hash: String)

    @Query("UPDATE track_metadata SET playCount = playCount + 1, lastPlayedAt = :timestamp WHERE fileSha256 = :hash")
    suspend fun recordPlayback(hash: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE track_metadata SET isCachedOffline = :cached WHERE fileSha256 = :hash")
    suspend fun updateOfflineStatus(hash: String, cached: Boolean)
}
