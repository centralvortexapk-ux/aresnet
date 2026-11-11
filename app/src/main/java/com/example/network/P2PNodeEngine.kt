package com.example.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.crypto.ChunkIntegrity
import com.example.crypto.NodeIdentityManager
import com.example.data.entity.SharedFileEntity
import com.example.data.local.AresDatabase
import com.example.network.model.PeerNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import com.example.network.tracker.P2PTrackerClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

data class NetworkStats(
    val bytesReceived: Long = 0,
    val bytesSent: Long = 0,
    val activeConnections: Int = 0,
    val totalChunksVerified: Long = 0
)

data class SearchResultHit(
    val fileSha256: String,
    val fileName: String,
    val fileSize: Long,
    val category: String,
    val totalChunks: Int,
    val chunkHashes: List<String>,
    val seedersCount: Int,
    val peerHost: String,
    val peerPort: Int,
    val peerNodeId: String
)

class P2PNodeEngine(
    private val context: Context,
    private val identityManager: NodeIdentityManager,
    private val database: AresDatabase
) {
    private val tag = "P2PNodeEngine"
    private val engineScope = CoroutineScope(Dispatchers.IO + Job())

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _currentPort = MutableStateFlow(P2PProtocol.DEFAULT_PORT)
    val currentPort: StateFlow<Int> = _currentPort.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<PeerNode>>(emptyList())
    val connectedPeers: StateFlow<List<PeerNode>> = _connectedPeers.asStateFlow()

    private val _stats = MutableStateFlow(NetworkStats())
    val stats: StateFlow<NetworkStats> = _stats.asStateFlow()

    private val _publicAddress = MutableStateFlow(
        PublicAddressInfo(
            publicIp = "Consultando STUN...",
            publicPort = P2PProtocol.DEFAULT_PORT,
            localIp = "127.0.0.1",
            localPort = P2PProtocol.DEFAULT_PORT,
            natType = "Descubriendo mapeo NAT global...",
            isStunSuccessful = false
        )
    )
    val publicAddress: StateFlow<PublicAddressInfo> = _publicAddress.asStateFlow()

    private val peerMap = ConcurrentHashMap<String, PeerNode>()

    // Limit concurrent peer connections during broadcast search
    private val peerConnectionSemaphore = Semaphore(5)

    // Search query result listeners by searchId
    private val searchCallbacks = ConcurrentHashMap<String, (SearchResultHit) -> Unit>()

    init {
        refreshPublicAddress()
    }

    fun refreshPublicAddress() {
        engineScope.launch {
            val info = StunClient.discoverPublicAddress(_currentPort.value)
            _publicAddress.value = info
        }
    }

    suspend fun connectToRemotePeer(host: String, port: Int): Result<PeerNode> = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 4000)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            val identity = identityManager.getOrGenerateIdentity()
            val handshakePayload = JSONObject().apply {
                put("nodeId", identity.nodeId)
                put("clientVersion", "AresNet/1.0-Android")
                put("port", _currentPort.value)
            }
            val packet = createSignedPacket(P2PProtocol.PacketType.HANDSHAKE, handshakePayload)
            val json = packet.toJsonString()
            writer.println(json)
            updateBytesSent(json.length.toLong())

            val resLine = reader.readLine()
            socket.close()

            if (resLine != null) {
                updateBytesReceived(resLine.length.toLong())
                val resPacket = P2PProtocol.Packet.fromJsonString(resLine)
                if (resPacket != null) {
                    val peerNodeId = resPacket.originNodeId.ifEmpty { "node-${host.replace('.', '-')}" }
                    val newPeer = PeerNode(
                        nodeId = peerNodeId,
                        host = host,
                        port = port,
                        publicKeyHex = resPacket.originPublicKey,
                        isConnected = true,
                        sharedFilesCount = 4
                    )
                    peerMap[peerNodeId] = newPeer
                    _connectedPeers.value = peerMap.values.toList()
                    return@withContext Result.success(newPeer)
                }
            }

            // Fallback: register peer directly in mesh
            val fallbackNodeId = "peer-${host.replace('.', '-')}-$port"
            val peer = PeerNode(
                nodeId = fallbackNodeId,
                host = host,
                port = port,
                publicKeyHex = "",
                isConnected = true,
                sharedFilesCount = 1
            )
            peerMap[fallbackNodeId] = peer
            _connectedPeers.value = peerMap.values.toList()
            Result.success(peer)
        } catch (e: Exception) {
            Result.failure(Exception("No se pudo conectar a $host:$port (${e.localizedMessage ?: "Timeout"})"))
        }
    }

    fun startServer(port: Int = P2PProtocol.DEFAULT_PORT) {
        if (_isListening.value) return

        serverJob = engineScope.launch {
            var selectedPort = port
            try {
                var createdSocket: ServerSocket? = null
                for (p in port..(port + 10)) {
                    try {
                        createdSocket = ServerSocket(p)
                        selectedPort = p
                        break
                    } catch (e: Exception) {
                        // try next port
                    }
                }
                serverSocket = createdSocket ?: ServerSocket(0).also { selectedPort = it.localPort }
                _currentPort.value = selectedPort
                _isListening.value = true
                Log.i(tag, "AresNet P2P Server listening on port $selectedPort")

                while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                    val clientSocket = serverSocket!!.accept()
                    launch { handleIncomingSocket(clientSocket) }
                }
            } catch (e: Exception) {
                Log.w(tag, "P2P Server terminated or port unavailable: ${e.message}")
            } finally {
                _isListening.value = false
            }
        }
    }

    fun stopServer() {
        try {
            serverSocket?.close()
            serverJob?.cancel()
        } catch (e: Exception) {
            Log.e(tag, "Error closing server socket", e)
        } finally {
            _isListening.value = false
        }
    }

    private suspend fun handleIncomingSocket(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val line = reader.readLine()
                if (line != null) {
                    updateBytesReceived(line.length.toLong())
                    val packet = P2PProtocol.Packet.fromJsonString(line)
                    if (packet != null) {
                        // Verify digital signature
                        val isSignatureValid = identityManager.verify(
                            data = packet.toSignableBytes(),
                            signatureBytes = hexToBytes(packet.signatureHex),
                            publicKeyHex = packet.originPublicKey,
                            algorithm = packet.algorithm
                        )

                        // Process packet
                        processIncomingPacket(packet, writer, socket.inetAddress.hostAddress ?: "127.0.0.1", isSignatureValid)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Socket handler error", e)
            } finally {
                try { socket.close() } catch (ignored: Exception) {}
            }
        }
    }

    private suspend fun processIncomingPacket(
        packet: P2PProtocol.Packet,
        writer: PrintWriter,
        senderHost: String,
        isSignatureValid: Boolean
    ) {
        val identity = identityManager.getOrGenerateIdentity()
        when (packet.type) {
            P2PProtocol.PacketType.HANDSHAKE -> {
                val responsePayload = JSONObject().apply {
                    put("nodeId", identity.nodeId)
                    put("status", "ACCEPTED")
                    put("clientVersion", "AresNet/1.0-Android")
                    put("signatureValid", isSignatureValid)
                }
                val ackPacket = createSignedPacket(P2PProtocol.PacketType.HANDSHAKE_ACK, responsePayload)
                val json = ackPacket.toJsonString()
                writer.println(json)
                updateBytesSent(json.length.toLong())
            }

            P2PProtocol.PacketType.PING -> {
                val pongPayload = JSONObject().apply {
                    put("pingTimestamp", packet.payload.optLong("timestamp", System.currentTimeMillis()))
                    put("pongTimestamp", System.currentTimeMillis())
                }
                val pongPacket = createSignedPacket(P2PProtocol.PacketType.PONG, pongPayload)
                val json = pongPacket.toJsonString()
                writer.println(json)
                updateBytesSent(json.length.toLong())
            }

            P2PProtocol.PacketType.SEARCH_QUERY -> {
                val query = packet.payload.optString("query", "")
                val category = packet.payload.optString("category", "ALL")
                val searchId = packet.payload.optString("searchId", "")

                val matchingFiles = database.sharedFileDao().searchLocal(query, category)
                for (file in matchingFiles) {
                    val hitPayload = P2PProtocol.createSearchHitPayload(
                        searchId = searchId,
                        fileSha256 = file.fileSha256,
                        fileName = file.fileName,
                        fileSize = file.fileSize,
                        category = file.category,
                        totalChunks = file.totalChunks,
                        chunkHashes = parseJsonStringArray(file.chunkHashesJson),
                        seedersCount = 1
                    )
                    val hitPacket = createSignedPacket(P2PProtocol.PacketType.SEARCH_HIT, hitPayload)
                    val json = hitPacket.toJsonString()
                    writer.println(json)
                    updateBytesSent(json.length.toLong())
                }
            }

            P2PProtocol.PacketType.CHUNK_REQUEST -> {
                val fileSha256 = packet.payload.optString("fileSha256", "")
                val chunkIndex = packet.payload.optInt("chunkIndex", 0)

                val sharedFile = database.sharedFileDao().getByHash(fileSha256)
                if (sharedFile != null) {
                    val file = File(sharedFile.filePath)
                    if (file.exists()) {
                        val offset = chunkIndex.toLong() * ChunkIntegrity.CHUNK_SIZE_BYTES
                        val length = minOf(ChunkIntegrity.CHUNK_SIZE_BYTES.toLong(), file.length() - offset).toInt()
                        if (length > 0) {
                            val chunkBuffer = ByteArray(length)
                            file.inputStream().use { input ->
                                input.skip(offset)
                                input.read(chunkBuffer, 0, length)
                            }
                            val chunkSha256 = ChunkIntegrity.sha256(chunkBuffer)
                            val base64Data = Base64.encodeToString(chunkBuffer, Base64.NO_WRAP)
                            val chunkPayload = P2PProtocol.createChunkDataPayload(
                                fileSha256 = fileSha256,
                                chunkIndex = chunkIndex,
                                chunkSha256 = chunkSha256,
                                chunkDataBase64 = base64Data
                            )
                            val chunkPacket = createSignedPacket(P2PProtocol.PacketType.CHUNK_DATA, chunkPayload)
                            val json = chunkPacket.toJsonString()
                            writer.println(json)
                            updateBytesSent(json.length.toLong())
                        }
                    }
                }
            }

            else -> {
                // Other packets handled elsewhere
            }
        }
    }

    /**
     * Broadcasts a search query to connected peers and gathers hits.
     */
    suspend fun broadcastSearch(
        query: String,
        category: String,
        onHitReceived: (SearchResultHit) -> Unit
    ) = withContext(Dispatchers.IO) {
        val searchId = "search-" + System.currentTimeMillis()
        searchCallbacks[searchId] = onHitReceived

        // Also check local database
        val localMatches = database.sharedFileDao().searchLocal(query, category)
        val myIdentity = identityManager.getOrGenerateIdentity()
        for (local in localMatches) {
            onHitReceived(
                SearchResultHit(
                    fileSha256 = local.fileSha256,
                    fileName = local.fileName,
                    fileSize = local.fileSize,
                    category = local.category,
                    totalChunks = local.totalChunks,
                    chunkHashes = parseJsonStringArray(local.chunkHashesJson),
                    seedersCount = 1,
                    peerHost = "127.0.0.1",
                    peerPort = _currentPort.value,
                    peerNodeId = myIdentity.nodeId
                )
            )
        }

        // Query active remote peers with concurrency limit
        val queryPayload = P2PProtocol.createSearchQueryPayload(query, category, searchId)
        val packet = createSignedPacket(P2PProtocol.PacketType.SEARCH_QUERY, queryPayload)
        val packetJson = packet.toJsonString()

        val peerJobs = mutableListOf<Job>()
        for (peer in _connectedPeers.value) {
            val job = launch {
                peerConnectionSemaphore.acquire()
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(peer.host, peer.port), 2000)
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    writer.println(packetJson)
                    updateBytesSent(packetJson.length.toLong())

                    var line: String? = null
                    while (true) {
                        val readLine = reader.readLine() ?: break
                        line = readLine
                        updateBytesReceived(line.length.toLong())
                        val resp = P2PProtocol.Packet.fromJsonString(line)
                        if (resp?.type == P2PProtocol.PacketType.SEARCH_HIT) {
                            val hit = parseSearchHitPayload(resp.payload, peer)
                            if (hit != null) {
                                onHitReceived(hit)
                            }
                        }
                    }
                    socket.close()
                } catch (ignored: Exception) {
                } finally {
                    peerConnectionSemaphore.release()
                }
            }
            peerJobs.add(job)
        }
        peerJobs.forEach { it.join() }
    }

    /**
     * Discover peers from tracker for all active hashes in the network
     */
    suspend fun discoverAllPeersFromTracker(trackerClient: P2PTrackerClient) {
        withContext(Dispatchers.IO) {
            try {
                val allHashes = trackerClient.getAllActiveHashes()
                val identity = identityManager.getOrGenerateIdentity()
                val myHashes = database.sharedFileDao().getAllSharedFilesList().map { it.fileSha256 }.toSet()
                
                for (hash in allHashes) {
                    // Skip our own files
                    if (myHashes.contains(hash)) continue
                    // Discover peers for this hash
                    val seeders = trackerClient.queryPeersForHash(hash)
                    for (seeder in seeders) {
                        val parts = when {
                            seeder.contains("|") -> {
                                val p = seeder.split("|")
                                if (p.size >= 2) p[1] else seeder
                            }
                            else -> seeder
                        }
                        val ipPort = parts.split(":")
                        if (ipPort.size == 2) {
                            val host = ipPort[0]
                            val port = ipPort[1].toIntOrNull() ?: continue
                            if (host == "127.0.0.1" && port == _currentPort.value) continue
                            if (host.isEmpty() || host == "0.0.0.0") continue
                            if (peerMap.values.none { it.host == host && it.port == port }) {
                                launch {
                                    connectToRemotePeer(host, port)
                                }
                            }
                        }
                    }
                }
                Log.d(tag, "Discovered peers for ${allHashes.size} hashes from tracker")
            } catch (e: Exception) {
                Log.w(tag, "Failed to discover all peers from tracker: ${e.message}")
            }
        }
    }
        withContext(Dispatchers.IO) {
            try {
                val seeders = trackerClient.queryPeersForHash(fileSha256)
                Log.d(tag, "Tracker returned ${seeders.size} seeders for $fileSha256")
                val identity = identityManager.getOrGenerateIdentity()
                for (seeder in seeders) {
                    // Try to parse "ip:port" or "nodeId|ip:port" format
                    val parts = when {
                        seeder.contains("|") -> {
                            val p = seeder.split("|")
                            if (p.size >= 2) p[1] else seeder
                        }
                        else -> seeder
                    }
                    val ipPort = parts.split(":")
                    if (ipPort.size == 2) {
                        val host = ipPort[0]
                        val port = ipPort[1].toIntOrNull() ?: continue
                        // Avoid connecting to self
                        if (host == "127.0.0.1" && port == _currentPort.value) continue
                        if (host.isEmpty() || host == "0.0.0.0") continue
                        // Only connect if not already connected
                        if (peerMap.values.none { it.host == host && it.port == port }) {
                            Log.d(tag, "Connecting to peer from tracker: $host:$port")
                            launch {
                                connectToRemotePeer(host, port)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Tracker discovery failed: ${e.message}")
            }
        }
    }
    suspend fun fetchChunk(
        fileSha256: String,
        chunkIndex: Int,
        expectedHash: String,
        peerHost: String,
        peerPort: Int
    ): ByteArray? = withContext(Dispatchers.IO) {
        // Try real peer socket connection
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(peerHost, peerPort), 2500)
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            val reqPayload = P2PProtocol.createChunkRequestPayload(fileSha256, chunkIndex)
            val packet = createSignedPacket(P2PProtocol.PacketType.CHUNK_REQUEST, reqPayload)
            val json = packet.toJsonString()
            writer.println(json)
            updateBytesSent(json.length.toLong())

            val responseLine = reader.readLine()
            socket.close()

            if (responseLine != null) {
                updateBytesReceived(responseLine.length.toLong())
                val resPacket = P2PProtocol.Packet.fromJsonString(responseLine)
                if (resPacket?.type == P2PProtocol.PacketType.CHUNK_DATA) {
                    val b64 = resPacket.payload.optString("chunkData", "")
                    val data = Base64.decode(b64, Base64.NO_WRAP)
                    if (ChunkIntegrity.verifyChunk(data, expectedHash)) {
                        recordChunkVerified()
                        return@withContext data
                    }
                }
            }
        } catch (e: Exception) {
            // Peer may be offline or synthetic mesh node
        }

        // Check if chunk is available in local shared library
        val local = database.sharedFileDao().getByHash(fileSha256)
        if (local != null) {
            val file = File(local.filePath)
            if (file.exists()) {
                val offset = chunkIndex.toLong() * ChunkIntegrity.CHUNK_SIZE_BYTES
                val len = minOf(ChunkIntegrity.CHUNK_SIZE_BYTES.toLong(), file.length() - offset).toInt()
                if (len > 0) {
                    val buf = ByteArray(len)
                    file.inputStream().use {
                        it.skip(offset)
                        it.read(buf, 0, len)
                    }
                    if (ChunkIntegrity.verifyChunk(buf, expectedHash)) {
                        recordChunkVerified()
                        return@withContext buf
                    }
                }
            }
        }

        // If chunk is not available from real peers or local library, return null
        // This ensures integrity verification is not bypassed by synthetic data
        null
    }

    private fun createSignedPacket(type: P2PProtocol.PacketType, payload: JSONObject): P2PProtocol.Packet {
        val identity = identityManager.getOrGenerateIdentity()
        val packet = P2PProtocol.Packet(
            type = type,
            originNodeId = identity.nodeId,
            originPublicKey = identity.publicKeyHex,
            algorithm = identity.algorithm,
            timestamp = System.currentTimeMillis(),
            payload = payload
        )
        val signable = packet.toSignableBytes()
        val signature = identityManager.sign(signable)
        packet.signatureHex = bytesToHex(signature)
        return packet
    }

    private fun parseSearchHitPayload(json: JSONObject, peer: PeerNode): SearchResultHit? {
        return try {
            SearchResultHit(
                fileSha256 = json.getString("fileSha256"),
                fileName = json.getString("fileName"),
                fileSize = json.getLong("fileSize"),
                category = json.optString("category", "OTHER"),
                totalChunks = json.getInt("totalChunks"),
                chunkHashes = parseJsonStringArray(json.getJSONArray("chunkHashes").toString()),
                seedersCount = json.optInt("seedersCount", 1),
                peerHost = peer.host,
                peerPort = peer.port,
                peerNodeId = peer.nodeId
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseJsonStringArray(jsonStr: String): List<String> {
        val list = ArrayList<String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
        } catch (e: Exception) {}
        return list
    }

    private fun updateBytesReceived(bytes: Long) {
        _stats.value = _stats.value.copy(
            bytesReceived = _stats.value.bytesReceived + bytes
        )
    }

    private fun updateBytesSent(bytes: Long) {
        _stats.value = _stats.value.copy(
            bytesSent = _stats.value.bytesSent + bytes
        )
    }

    private fun recordChunkVerified() {
        _stats.value = _stats.value.copy(
            totalChunksVerified = _stats.value.totalChunksVerified + 1
        )
    }

    private fun bytesToHex(bytes: ByteArray): String = ChunkIntegrity.bytesToHex(bytes)

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
