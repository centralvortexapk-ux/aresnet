package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "station_categories")
data class StationCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val subtitle: String,
    val iconEmoji: String,
    val colorStart: Long,
    val colorEnd: Long,
    val orderIndex: Int,
    val isSystem: Boolean = true,
    val allowedExtensions: String
)