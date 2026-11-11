package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.entity.BlockedHashEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedHashDao {
    @Query("SELECT * FROM blocked_hashes ORDER BY dateAdded DESC")
    fun getAllBlockedHashes(): Flow<List<BlockedHashEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_hashes WHERE sha256Hex = :sha256 LIMIT 1)")
    suspend fun isHashBlocked(sha256: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlock(entity: BlockedHashEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<BlockedHashEntity>)

    @Query("DELETE FROM blocked_hashes WHERE sha256Hex = :sha256")
    suspend fun removeBlock(sha256: String)
}
