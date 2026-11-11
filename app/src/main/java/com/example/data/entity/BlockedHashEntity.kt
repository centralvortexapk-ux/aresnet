package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_hashes")
data class BlockedHashEntity(
    @PrimaryKey
    val sha256Hex: String,
    val fileNameHint: String,
    val reason: String, // "COPYRIGHT_DMCA", "MALWARE", "POLICY_VIOLATION", "USER_REPORT"
    val dateAdded: Long = System.currentTimeMillis()
)
