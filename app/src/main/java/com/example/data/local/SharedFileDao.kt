package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entity.SharedFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedFileDao {
    @Query("SELECT * FROM shared_files ORDER BY addedAt DESC")
    fun getAllSharedFiles(): Flow<List<SharedFileEntity>>

    @Query("SELECT * FROM shared_files ORDER BY addedAt DESC")
    suspend fun getAllSharedFilesList(): List<SharedFileEntity>

    @Query("SELECT category, COUNT(*) as count FROM shared_files GROUP BY category")
    fun getSharedCountByCategory(): Flow<Map<@MapColumn(columnName = "category") String, @MapColumn(columnName = "count") Int>>

    @Query("SELECT * FROM shared_files WHERE category = :category ORDER BY addedAt DESC")
    fun getSharedFilesByCategory(category: String): Flow<List<SharedFileEntity>>

    @Query("SELECT * FROM shared_files WHERE fileSha256 = :sha256 LIMIT 1")
    suspend fun getByHash(sha256: String): SharedFileEntity?

    @Query("SELECT * FROM shared_files WHERE fileName LIKE '%' || :query || '%' AND (:category = 'ALL' OR category = :category)")
    suspend fun searchLocal(query: String, category: String): List<SharedFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(file: SharedFileEntity)

    @Update
    suspend fun update(file: SharedFileEntity)

    @Delete
    suspend fun delete(file: SharedFileEntity)
}
