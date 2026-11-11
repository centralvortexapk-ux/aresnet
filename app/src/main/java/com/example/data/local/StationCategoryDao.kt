package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.entity.StationCategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StationCategoryDao {
    @Query("SELECT * FROM station_categories ORDER BY orderIndex ASC")
    fun getAllCategories(): Flow<List<StationCategoryEntity>>

    @Query("SELECT * FROM station_categories ORDER BY orderIndex ASC")
    suspend fun getAllCategoriesList(): List<StationCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<StationCategoryEntity>)

    @Query("SELECT COUNT(*) FROM station_categories")
    suspend fun count(): Int
}