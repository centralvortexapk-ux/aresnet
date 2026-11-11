package com.example.network.model

/**
 * Representation of an AresNet P2P peer node in the mesh network.
 */
data class PeerNode(
    val nodeId: String,
    val host: String,
    val port: Int,
    val publicKeyHex: String = "",
    val algorithm: String = "EC",
    val latencyMs: Long = 0,
    val isConnected: Boolean = false,
    val clientVersion: String = "AresNet/1.0-Android",
    val sharedFilesCount: Int = 0,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)
