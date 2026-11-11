package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey
    val fileSha256: String,
    val fileName: String,
    val fileSize: Long,
    val category: String,
    val totalChunks: Int,
    val downloadedChunks: Int,
    val completedChunkIndicesJson: String, // e.g. "[0,1,2]"
    val chunkHashesJson: String, // e.g. "[\"hash1\", \"hash2\"]"
    val status: String, // "DOWNLOADING", "PAUSED", "COMPLETED", "FAILED", "BLOCKED"
    val destinationPath: String,
    val downloadSpeedBps: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0
)
