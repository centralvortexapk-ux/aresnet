package com.example.crypto

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Handles file chunking and block-level SHA-256 verification.
 * Standard block size: 256 KB (262,144 bytes) for P2P swarming.
 */
object ChunkIntegrity {

    const val CHUNK_SIZE_BYTES = 256 * 1024 // 256 KB per block

    data class ChunkInfo(
        val index: Int,
        val offset: Long,
        val length: Int,
        val sha256Hex: String
    )

    data class FileManifest(
        val fileSha256Hex: String,
        val totalBytes: Long,
        val totalChunks: Int,
        val chunkHashes: List<String>
    )

    /**
     * Computes the SHA-256 hash of a byte array and formats as hex.
     */
    fun sha256(data: ByteArray, offset: Int = 0, length: Int = data.size): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data, offset, length)
        return bytesToHex(digest.digest())
    }

    /**
     * Verifies that incoming chunk data matches the expected SHA-256 hash.
     */
    fun verifyChunk(data: ByteArray, expectedSha256Hex: String): Boolean {
        val calculated = sha256(data)
        return calculated.equals(expectedSha256Hex, ignoreCase = true)
    }

    /**
     * Splits an input stream into chunk manifests with SHA-256 hashes per block.
     */
    fun calculateManifest(file: File): FileManifest {
        val totalBytes = file.length()
        val totalChunks = if (totalBytes == 0L) 1 else ((totalBytes + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES).toInt()
        val chunkHashes = ArrayList<String>(totalChunks)

        val fullFileDigest = MessageDigest.getInstance("SHA-256")
        val chunkDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_SIZE_BYTES)

        file.inputStream().use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                fullFileDigest.update(buffer, 0, bytesRead)
                chunkDigest.reset()
                chunkDigest.update(buffer, 0, bytesRead)
                chunkHashes.add(bytesToHex(chunkDigest.digest()))
            }
        }

        if (chunkHashes.isEmpty()) {
            chunkHashes.add(sha256(ByteArray(0)))
        }

        val fullFileHash = bytesToHex(fullFileDigest.digest())
        return FileManifest(
            fileSha256Hex = fullFileHash,
            totalBytes = totalBytes,
            totalChunks = chunkHashes.size,
            chunkHashes = chunkHashes
        )
    }

    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i shr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }
}
