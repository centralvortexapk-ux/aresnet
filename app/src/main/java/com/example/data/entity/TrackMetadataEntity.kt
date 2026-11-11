package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Spotify-like metadata catalog separating metadata from P2P chunked audio data.
 * Associated uniquely to the audio file via its SHA-256 hash.
 */
@Entity(tableName = "track_metadata")
data class TrackMetadataEntity(
    @PrimaryKey
    val fileSha256: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val genre: String, // "Synthwave", "Cyber Lo-Fi", "Electronic", "Ambient", "Podcast"
    val coverGradientStart: Long = 0xFF00E5FF,
    val coverGradientEnd: Long = 0xFF8B5CF6,
    val coverSymbol: String = "🎵",
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0,
    val isCachedOffline: Boolean = false,
    val totalChunks: Int = 5,
    val fileSize: Long = 1024 * 1024 + 256 * 1024,
    val categoryId: String = "music"
)
