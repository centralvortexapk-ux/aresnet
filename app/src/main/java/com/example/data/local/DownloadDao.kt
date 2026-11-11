package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE fileSha256 = :fileSha256 LIMIT 1")
    suspend fun getDownload(fileSha256: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(download: DownloadEntity)

    @Update
    suspend fun update(download: DownloadEntity)

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("UPDATE downloads SET status = :status WHERE fileSha256 = :fileSha256")
    suspend fun updateStatus(fileSha256: String, status: String)

    @Query("UPDATE downloads SET downloadedChunks = :downloaded, completedChunkIndicesJson = :chunksJson, downloadSpeedBps = :speed WHERE fileSha256 = :fileSha256")
    suspend fun updateProgress(fileSha256: String, downloaded: Int, chunksJson: String, speed: Long)
}
