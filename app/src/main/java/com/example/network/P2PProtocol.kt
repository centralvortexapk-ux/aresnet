package com.example.network

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Wire protocol for AresNet P2P data packets.
 * Every packet carries the sender's Node ID, public key, timestamp, and cryptographic signature.
 */
object P2PProtocol {

    const val DEFAULT_PORT = 8421
    const val PROTOCOL_VERSION = "ARES-1.0"

    enum class PacketType {
        HANDSHAKE,
        HANDSHAKE_ACK,
        PING,
        PONG,
        SEARCH_QUERY,
        SEARCH_HIT,
        CHUNK_REQUEST,
        CHUNK_DATA,
        BLOCKLIST_SYNC
    }

    data class Packet(
        val type: PacketType,
        val originNodeId: String,
        val originPublicKey: String,
        val algorithm: String = "EC",
        val timestamp: Long = System.currentTimeMillis(),
        val payload: JSONObject,
        var signatureHex: String = ""
    ) {
        /**
         * Serializes the packet data to be signed (all fields except signatureHex).
         */
        fun toSignableBytes(): ByteArray {
            val json = JSONObject()
            json.put("type", type.name)
            json.put("originNodeId", originNodeId)
            json.put("originPublicKey", originPublicKey)
            json.put("algorithm", algorithm)
            json.put("timestamp", timestamp)
            json.put("payload", payload)
            return json.toString().toByteArray(StandardCharsets.UTF_8)
        }

        fun toJsonString(): String {
            val json = JSONObject()
            json.put("type", type.name)
            json.put("originNodeId", originNodeId)
            json.put("originPublicKey", originPublicKey)
            json.put("algorithm", algorithm)
            json.put("timestamp", timestamp)
            json.put("payload", payload)
            json.put("signature", signatureHex)
            return json.toString()
        }

        companion object {
            fun fromJsonString(jsonStr: String): Packet? {
                return try {
                    val json = JSONObject(jsonStr)
                    val type = PacketType.valueOf(json.getString("type"))
                    val originNodeId = json.getString("originNodeId")
                    val originPublicKey = json.optString("originPublicKey", "")
                    val algorithm = json.optString("algorithm", "EC")
                    val timestamp = json.getLong("timestamp")
                    val payload = json.getJSONObject("payload")
                    val signature = json.optString("signature", "")
                    Packet(
                        type = type,
                        originNodeId = originNodeId,
                        originPublicKey = originPublicKey,
                        algorithm = algorithm,
                        timestamp = timestamp,
                        payload = payload,
                        signatureHex = signature
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    // Helper payload builders
    fun createSearchQueryPayload(query: String, category: String, searchId: String): JSONObject {
        val json = JSONObject()
        json.put("query", query)
        json.put("category", category)
        json.put("searchId", searchId)
        return json
    }

    fun createSearchHitPayload(
        searchId: String,
        fileSha256: String,
        fileName: String,
        fileSize: Long,
        category: String,
        totalChunks: Int,
        chunkHashes: List<String>,
        seedersCount: Int
    ): JSONObject {
        val json = JSONObject()
        json.put("searchId", searchId)
        json.put("fileSha256", fileSha256)
        json.put("fileName", fileName)
        json.put("fileSize", fileSize)
        json.put("category", category)
        json.put("totalChunks", totalChunks)
        val hashesArray = JSONArray()
        chunkHashes.forEach { hashesArray.put(it) }
        json.put("chunkHashes", hashesArray)
        json.put("seedersCount", seedersCount)
        return json
    }

    fun createChunkRequestPayload(fileSha256: String, chunkIndex: Int): JSONObject {
        val json = JSONObject()
        json.put("fileSha256", fileSha256)
        json.put("chunkIndex", chunkIndex)
        return json
    }

    fun createChunkDataPayload(
        fileSha256: String,
        chunkIndex: Int,
        chunkSha256: String,
        chunkDataBase64: String
    ): JSONObject {
        val json = JSONObject()
        json.put("fileSha256", fileSha256)
        json.put("chunkIndex", chunkIndex)
        json.put("chunkSha256", chunkSha256)
        json.put("chunkData", chunkDataBase64)
        return json
    }
}
