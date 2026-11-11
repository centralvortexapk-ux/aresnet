package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shared_files")
data class SharedFileEntity(
    @PrimaryKey
    val fileSha256: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val category: String, // "MUSIC", "SOFTWARE", "IMAGE", "VIDEO", "DOCUMENT"
    val totalChunks: Int,
    val chunkHashesJson: String,
    val addedAt: Long = System.currentTimeMillis(),
    val isSeeding: Boolean = true
)
