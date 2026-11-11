package com.example.network.tracker

import android.content.Context
import android.util.Log
import com.example.crypto.NodeIdentityManager
import com.example.data.local.AresDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class TrackerStatus(
    val url: String = "https://aresnet.onrender.com",
    val isConnected: Boolean = false,
    val lastAnnounceTimestamp: Long = 0,
    val announcedHashesCount: Int = 0,
    val detectedPublicIp: String = "Desconocida",
    val statusMessage: String = "Sin sincronizar"
)

data class TrackerAnnounceResult(
    val success: Boolean,
    val detectedPublicIp: String,
    val swarmsRegistered: Int,
    val message: String
)

/**
 * Android client for communication with the AresNet P2P Tracker & Rendezvous Server.
 * Enables peers on different networks to announce their public IP/port and locate seeders.
 */
class P2PTrackerClient(
    private val context: Context,
    private val identityManager: NodeIdentityManager,
    private val database: AresDatabase
) {
    private val tag = "P2PTrackerClient"
    private val clientScope = CoroutineScope(Dispatchers.IO + Job())

    private val _trackerStatus = MutableStateFlow(TrackerStatus())
    val trackerStatus: StateFlow<TrackerStatus> = _trackerStatus.asStateFlow()

    private var heartbeatJob: Job? = null

    companion object {
        const val DEFAULT_TRACKER_URL = "https://aresnet.onrender.com"
        const val LOCAL_DEV_TRACKER_URL = "http://10.0.2.2:3000"
    }

    fun setTrackerUrl(newUrl: String) {
        val sanitized = newUrl.trim().removeSuffix("/")
        _trackerStatus.value = _trackerStatus.value.copy(
            url = sanitized,
            statusMessage = "URL actualizada: $sanitized"
        )
    }

    /**
     * Announces local node presence and currently shared files to the tracker.
     */
    suspend fun announce(
        trackerUrl: String = _trackerStatus.value.url,
        localPort: Int
    ): TrackerAnnounceResult = withContext(Dispatchers.IO) {
        try {
            val identity = identityManager.getOrGenerateIdentity()
            val sharedEntities = database.sharedFileDao().getAllSharedFiles()
            // We get hashes directly or from database query
            val hashes = mutableListOf<String>()
            try {
                // Read from local shared files
                val files = database.sharedFileDao().searchLocal("", "ALL")
                hashes.addAll(files.map { it.fileSha256 })
            } catch (e: Exception) {
                // If query fails, proceed with empty hashes
            }

            val payload = JSONObject().apply {
                put("nodeId", identity.nodeId)
                put("port", localPort)
                put("clientVersion", "AresNet/1.0-Android")
                put("sharedHashes", JSONArray(hashes))
            }

            val base = trackerUrl.trim().removeSuffix("/")
            val url = URL("$base/api/p2p/announce")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val responseStr = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(responseStr)
                val detectedIp = json.optString("detectedPublicIp", "IP Pública")
                val swarms = json.optInt("swarmsRegistered", hashes.size)

                _trackerStatus.value = _trackerStatus.value.copy(
                    isConnected = true,
                    lastAnnounceTimestamp = System.currentTimeMillis(),
                    announcedHashesCount = swarms,
                    detectedPublicIp = detectedIp,
                    statusMessage = "Conectado • IP pública $detectedIp:$localPort"
                )

                TrackerAnnounceResult(
                    success = true,
                    detectedPublicIp = detectedIp,
                    swarmsRegistered = swarms,
                    message = "Anuncio exitoso en Tracker"
                )
            } else {
                val errMessage = "Tracker HTTP $code"
                _trackerStatus.value = _trackerStatus.value.copy(
                    isConnected = false,
                    statusMessage = errMessage
                )
                TrackerAnnounceResult(false, "", 0, errMessage)
            }
        } catch (e: Exception) {
            val err = "Tracker no disponible (${e.localizedMessage ?: "Timeout"})"
            _trackerStatus.value = _trackerStatus.value.copy(
                isConnected = false,
                statusMessage = err
            )
            TrackerAnnounceResult(false, "", 0, err)
        }
    }

    /**
     * Queries tracker for list of peers seeding a specific file hash across Internet.
     */
    suspend fun queryPeersForHash(
        fileSha256: String,
        trackerUrl: String = _trackerStatus.value.url
    ): List<String> = withContext(Dispatchers.IO) {
        val seedersList = mutableListOf<String>()
        try {
            val base = trackerUrl.trim().removeSuffix("/")
            val url = URL("$base/api/p2p/peers?hash=$fileSha256")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("Accept", "application/json")
            }

            if (conn.responseCode in 200..299) {
                val responseStr = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(responseStr)
                val arr = json.optJSONArray("seeders")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        seedersList.add(arr.getString(i))
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "Tracker lookup failed: ${e.message}")
        }
        seedersList
    }

    /**
     * Queries tracker for all active file hashes registered in the network.
     */
    suspend fun queryActiveHashes(
        trackerUrl: String = _trackerStatus.value.url
    ): List<String> = withContext(Dispatchers.IO) {
        val hashesList = mutableListOf<String>()
        try {
            val base = trackerUrl.trim().removeSuffix("/")
            val url = URL("$base/api/p2p/hashes")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("Accept", "application/json")
            }

            if (conn.responseCode in 200..299) {
                val responseStr = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(responseStr)
                val arr = json.optJSONArray("hashes")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        hashesList.add(arr.getString(i))
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "Tracker hashes lookup failed: ${e.message}")
        }
        hashesList
    }

    /**
     * Reports an inappropriate hash to the tracker for community moderation.
     */
    suspend fun reportHash(
        fileSha256: String,
        reason: String,
        trackerUrl: String = _trackerStatus.value.url
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("hash", fileSha256)
                put("reason", reason)
            }
            val base = trackerUrl.trim().removeSuffix("/")
            val url = URL("$base/api/p2p/report")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Starts continuous background heartbeat announcing presence every 60 seconds.
     */
    fun startHeartbeat(localPort: Int, intervalSeconds: Long = 60) {
        heartbeatJob?.cancel()
        heartbeatJob = clientScope.launch {
            while (isActive) {
                try {
                    announce(localPort = localPort)
                } catch (e: Exception) {
                    // Suppress and retry next cycle
                }
                delay(intervalSeconds * 1000)
            }
        }
    }

    /**
     * Stops background heartbeat.
     */
    fun stopHeartbeat() {
        heartbeatJob?.cancel()
    }
}
