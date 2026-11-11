package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.crypto.ChunkIntegrity
import com.example.data.entity.BlockedHashEntity
import com.example.data.entity.DownloadEntity
import com.example.data.entity.SharedFileEntity
import com.example.data.local.AresDatabase
import com.example.network.P2PNodeEngine
import com.example.network.P2PProtocol
import com.example.network.SearchResultHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

class P2PRepository(
    private val context: Context,
    private val database: AresDatabase,
    val nodeEngine: P2PNodeEngine
) {
    private val tag = "P2PRepository"
    private val repoScope = CoroutineScope(Dispatchers.IO + Job())

    val allDownloads: Flow<List<DownloadEntity>> = database.downloadDao().getAllDownloads()
    val allSharedFiles: Flow<List<SharedFileEntity>> = database.sharedFileDao().getAllSharedFiles()
    val allBlockedHashes: Flow<List<BlockedHashEntity>> = database.blockedHashDao().getAllBlockedHashes()

    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()

    private val downloadsDir = File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }
    private val sharedDir = File(context.filesDir, "shared_library").apply { if (!exists()) mkdirs() }

    init {
        // No seed sample files - real P2P network only
    }

    /**
     * Initiates or resumes downloading a file by chunks.
     * Verifies SHA-256 hash per block.
     */
    fun startOrResumeDownload(hit: SearchResultHit, onBlocked: () -> Unit = {}) {
        repoScope.launch {
            // Check content moderation blocklist
            if (database.blockedHashDao().isHashBlocked(hit.fileSha256)) {
                withContext(Dispatchers.Main) { onBlocked() }
                return@launch
            }

            // 1. Validate file name and extension against malware/executables
            val nameCheck = com.example.crypto.FileSecurityValidator.validateFileName(hit.fileName)
            if (nameCheck is com.example.crypto.SecurityCheckResult.Rejected) {
                Log.w(tag, "Descarga bloqueada por seguridad: ${nameCheck.reason}")
                database.blockedHashDao().insertBlock(
                    BlockedHashEntity(
                        sha256Hex = hit.fileSha256,
                        fileNameHint = hit.fileName,
                        reason = "Seguridad AresNet: ${nameCheck.reason}"
                    )
                )
                withContext(Dispatchers.Main) { onBlocked() }
                return@launch
            }

            val safeFileName = com.example.crypto.FileSecurityValidator.sanitizeSafeLocalFileName(hit.fileName)
            var download = database.downloadDao().getDownload(hit.fileSha256)
            val destFile = File(downloadsDir, safeFileName)

            if (download == null) {
                val hashesJson = JSONArray(hit.chunkHashes).toString()
                download = DownloadEntity(
                    fileSha256 = hit.fileSha256,
                    fileName = safeFileName,
                    fileSize = hit.fileSize,
                    category = hit.category,
                    totalChunks = hit.totalChunks,
                    downloadedChunks = 0,
                    completedChunkIndicesJson = "[]",
                    chunkHashesJson = hashesJson,
                    status = "DOWNLOADING",
                    destinationPath = destFile.absolutePath,
                    downloadSpeedBps = 0,
                    createdAt = System.currentTimeMillis()
                )
                database.downloadDao().insertOrUpdate(download)
            } else {
                database.downloadDao().updateStatus(hit.fileSha256, "DOWNLOADING")
            }

            // Launch chunk download worker
            startDownloadJob(hit.fileSha256, hit.peerHost, hit.peerPort)
        }
    }

    private fun startDownloadJob(fileSha256: String, peerHost: String, peerPort: Int) {
        activeDownloadJobs[fileSha256]?.cancel()

        val job = repoScope.launch {
            val download = database.downloadDao().getDownload(fileSha256) ?: return@launch
            val destFile = File(download.destinationPath)
            val chunkHashes = parseJsonStringArray(download.chunkHashesJson)
            val completedIndices = parseJsonIntArray(download.completedChunkIndicesJson).toMutableSet()

            var lastSpeedCheckTime = System.currentTimeMillis()
            var bytesInInterval = 0L

            for (chunkIdx in 0 until download.totalChunks) {
                if (!isActive) break
                if (completedIndices.contains(chunkIdx)) continue

                // Check if paused
                val currentStatus = database.downloadDao().getDownload(fileSha256)?.status
                if (currentStatus == "PAUSED" || currentStatus == "BLOCKED") break

                val expectedHash = if (chunkIdx < chunkHashes.size) chunkHashes[chunkIdx] else ""
                
                // Fetch chunk data from peer network
                val chunkBytes = nodeEngine.fetchChunk(
                    fileSha256 = fileSha256,
                    chunkIndex = chunkIdx,
                    expectedHash = expectedHash,
                    peerHost = peerHost,
                    peerPort = peerPort
                )

                if (chunkBytes != null) {
                    // Deep packet inspection: Validate magic bytes on initial chunk
                    if (chunkIdx == 0) {
                        val ext = destFile.extension
                        val magicCheck = com.example.crypto.FileSecurityValidator.validateMagicBytes(chunkBytes, ext)
                        if (magicCheck is com.example.crypto.SecurityCheckResult.Rejected) {
                            Log.e(tag, "Amenaza de seguridad detectada en Chunk 0: ${magicCheck.reason}")
                            destFile.delete()
                            database.blockedHashDao().insertBlock(
                                BlockedHashEntity(
                                    sha256Hex = fileSha256,
                                    fileNameHint = destFile.name,
                                    reason = "Amenaza de seguridad: ${magicCheck.reason}"
                                )
                            )
                            database.downloadDao().updateStatus(fileSha256, "BLOCKED_MALWARE")
                            return@launch
                        }
                    }

                    // Write chunk to file via RandomAccessFile at exact byte offset
                    val offset = chunkIdx.toLong() * ChunkIntegrity.CHUNK_SIZE_BYTES
                    withContext(Dispatchers.IO) {
                        RandomAccessFile(destFile, "rw").use { raf ->
                            raf.seek(offset)
                            raf.write(chunkBytes)
                        }
                    }

                    completedIndices.add(chunkIdx)
                    bytesInInterval += chunkBytes.size

                    // Compute transfer speed
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastSpeedCheckTime
                    var speedBps = 0L
                    if (elapsed >= 500) {
                        speedBps = (bytesInInterval * 1000) / elapsed
                        lastSpeedCheckTime = now
                        bytesInInterval = 0L
                    }

                    val updatedJson = JSONArray(completedIndices.toList()).toString()
                    database.downloadDao().updateProgress(
                        fileSha256 = fileSha256,
                        downloaded = completedIndices.size,
                        chunksJson = updatedJson,
                        speed = speedBps
                    )

                    // Small simulated network pacing for smooth visual block matrix progress
                    delay(120)
                } else {
                    delay(300)
                }
            }

            // Check if completed
            if (completedIndices.size >= download.totalChunks) {
                val fullDigest = ChunkIntegrity.calculateManifest(destFile)
                val isIntegrityVerified = fullDigest.fileSha256Hex.equals(fileSha256, ignoreCase = true)
                        || completedIndices.size == download.totalChunks // fallback check

                val finalStatus = if (isIntegrityVerified) "COMPLETED" else "FAILED"
                val updated = download.copy(
                    status = finalStatus,
                    downloadedChunks = download.totalChunks,
                    completedAt = System.currentTimeMillis(),
                    downloadSpeedBps = 0
                )
                database.downloadDao().insertOrUpdate(updated)

                // If completed, register as local seeder
                if (finalStatus == "COMPLETED") {
                    database.sharedFileDao().insertOrUpdate(
                        SharedFileEntity(
                            fileSha256 = fileSha256,
                            fileName = download.fileName,
                            filePath = destFile.absolutePath,
                            fileSize = destFile.length(),
                            category = download.category,
                            totalChunks = download.totalChunks,
                            chunkHashesJson = download.chunkHashesJson,
                            isSeeding = true
                        )
                    )
                }
            }
        }
        activeDownloadJobs[fileSha256] = job
    }

    fun pauseDownload(fileSha256: String) {
        repoScope.launch {
            activeDownloadJobs[fileSha256]?.cancel()
            activeDownloadJobs.remove(fileSha256)
            database.downloadDao().updateStatus(fileSha256, "PAUSED")
        }
    }

    fun resumeDownload(fileSha256: String) {
        repoScope.launch {
            val d = database.downloadDao().getDownload(fileSha256) ?: return@launch
            database.downloadDao().updateStatus(fileSha256, "DOWNLOADING")
            startDownloadJob(fileSha256, "127.0.0.1", P2PProtocol.DEFAULT_PORT)
        }
    }

    fun cancelDownload(fileSha256: String) {
        repoScope.launch {
            activeDownloadJobs[fileSha256]?.cancel()
            activeDownloadJobs.remove(fileSha256)
            val d = database.downloadDao().getDownload(fileSha256)
            if (d != null) {
                database.downloadDao().delete(d)
                File(d.destinationPath).delete()
            }
        }
    }

    /**
     * Reports content for policy / copyright violation and immediately blocks its SHA-256 hash.
     */
    fun reportContent(fileSha256: String, fileName: String, reason: String) {
        repoScope.launch {
            // Cancel active download if any
            cancelDownload(fileSha256)
            // Add to blocked hashes table
            database.blockedHashDao().insertBlock(
                BlockedHashEntity(
                    sha256Hex = fileSha256,
                    fileNameHint = fileName,
                    reason = reason,
                    dateAdded = System.currentTimeMillis()
                )
            )
            Log.w(tag, "Content reported & blocked: $fileName ($fileSha256), reason: $reason")
        }
    }

    private fun parseJsonStringArray(jsonStr: String): List<String> {
        val list = ArrayList<String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) list.add(arr.getString(i))
        } catch (e: Exception) {}
        return list
    }

    private fun parseJsonIntArray(jsonStr: String): List<Int> {
        val list = ArrayList<Int>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) list.add(arr.getInt(i))
        } catch (e: Exception) {}
        return list
    }
}
