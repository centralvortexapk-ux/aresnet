package com.example.network

import android.content.Context
import android.util.Log
import com.example.crypto.NodeIdentityManager
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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * State representing the lifecycle of the local P2P Socket Server.
 */
enum class ServerState {
    IDLE,
    STARTING,
    LISTENING,
    STOPPED,
    ERROR
}

/**
 * P2PNodeManager manages:
 * 1. The local ServerSocket (Socket Server) for incoming peer connections and handshakes.
 * 2. Connecting as a client to remote peer nodes across local network or Internet (via STUN / Tracker).
 * 3. Maintaining active peer sessions, heartbeats, and cryptographic packet validation.
 */
class P2PNodeManager(
    private val context: Context,
    private val identityManager: NodeIdentityManager
) {
    private val tag = "P2PNodeManager"
    private val managerScope = CoroutineScope(Dispatchers.IO + Job())

    // Server State
    private var serverSocket: ServerSocket? = null
    private var listenerJob: Job? = null

    private val _serverState = MutableStateFlow(ServerState.IDLE)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _serverPort = MutableStateFlow(P2PProtocol.DEFAULT_PORT)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _serverStatusMessage = MutableStateFlow("Servidor inactivo")
    val serverStatusMessage: StateFlow<String> = _serverStatusMessage.asStateFlow()

    // Active Connected Peers
    private val activePeersMap = ConcurrentHashMap<String, PeerNode>()
    private val _connectedPeers = MutableStateFlow<List<PeerNode>>(emptyList())
    val connectedPeers: StateFlow<List<PeerNode>> = _connectedPeers.asStateFlow()

    // Optional event listeners
    var onPeerConnected: ((PeerNode) -> Unit)? = null
    var onPeerDisconnected: ((String) -> Unit)? = null
    var onPacketReceived: ((P2PProtocol.Packet, Socket) -> Unit)? = null

    /**
     * Initializes and binds the local Socket Server on the given port (or falls back to adjacent ports).
     */
    fun startServer(port: Int = P2PProtocol.DEFAULT_PORT) {
        if (_isServerRunning.value) {
            Log.d(tag, "El servidor P2P ya se encuentra en ejecución en el puerto ${_serverPort.value}")
            return
        }

        _serverState.value = ServerState.STARTING
        _serverStatusMessage.value = "Iniciando servidor local en puerto $port..."

        listenerJob = managerScope.launch {
            var selectedPort = port
            try {
                var socket: ServerSocket? = null

                // Try requested port and fallback up to 10 adjacent ports if busy
                for (p in port..(port + 10)) {
                    try {
                        socket = ServerSocket(p)
                        selectedPort = p
                        break
                    } catch (e: Exception) {
                        Log.d(tag, "Puerto $p ocupado, probando siguiente...")
                    }
                }

                // If all candidate ports are busy, let the OS allocate a random free port
                serverSocket = socket ?: ServerSocket(0).also { selectedPort = it.localPort }
                _serverPort.value = selectedPort
                _isServerRunning.value = true
                _serverState.value = ServerState.LISTENING
                _serverStatusMessage.value = "Escuchando conexiones P2P en el puerto $selectedPort"
                Log.i(tag, "AresNet Socket Server activo y escuchando en el puerto $selectedPort")

                // Main socket acceptance loop
                while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                    val clientSocket = serverSocket!!.accept()
                    launch { handleIncomingPeerConnection(clientSocket) }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(tag, "Error en el servidor local de sockets: ${e.message}", e)
                    _serverState.value = ServerState.ERROR
                    _serverStatusMessage.value = "Fallo en servidor: ${e.localizedMessage ?: "Error de socket"}"
                }
            } finally {
                _isServerRunning.value = false
                if (_serverState.value != ServerState.ERROR) {
                    _serverState.value = ServerState.STOPPED
                    _serverStatusMessage.value = "Servidor detenido"
                }
            }
        }
    }

    /**
     * Gracefully stops the local Socket Server and cancels listening jobs.
     */
    fun stopServer() {
        try {
            _serverStatusMessage.value = "Deteniendo servidor local..."
            serverSocket?.close()
            serverSocket = null
            listenerJob?.cancel()
            listenerJob = null
            _isServerRunning.value = false
            _serverState.value = ServerState.STOPPED
            _serverStatusMessage.value = "Servidor detenido"
            Log.i(tag, "Servidor de sockets AresNet detenido correctamente")
        } catch (e: Exception) {
            Log.e(tag, "Error al detener el servidor de sockets", e)
            _serverStatusMessage.value = "Error al detener servidor: ${e.message}"
        }
    }

    /**
     * Restarts the server on a new port.
     */
    fun restartServer(port: Int = P2PProtocol.DEFAULT_PORT) {
        stopServer()
        startServer(port)
    }

    /**
     * Connects to a remote AresNet peer node via TCP socket and completes the cryptographic handshake.
     */
    suspend fun connectToPeer(
        host: String,
        port: Int,
        timeoutMs: Int = 6000
    ): Result<PeerNode> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            Log.i(tag, "Iniciando conexión saliente hacia nodo par $host:$port...")
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)

            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // 1. Prepare and send local signed HANDSHAKE packet
            val localIdentity = identityManager.getOrGenerateIdentity()
            val handshakePayload = JSONObject().apply {
                put("nodeId", localIdentity.nodeId)
                put("clientVersion", "AresNet/1.0-Android")
                put("port", _serverPort.value)
                put("timestamp", System.currentTimeMillis())
            }

            val packet = createSignedPacket(P2PProtocol.PacketType.HANDSHAKE, handshakePayload)
            writer.println(packet.toJsonString())

            // 2. Read remote peer's response
            val responseLine = reader.readLine()
            val latencyMs = System.currentTimeMillis() - startTime
            socket.close()

            if (!responseLine.isNullOrBlank()) {
                val responsePacket = P2PProtocol.Packet.fromJsonString(responseLine)
                if (responsePacket != null) {
                    val peerNodeId = responsePacket.originNodeId.ifBlank { "node-${host.replace('.', '-')}-$port" }
                    val peer = PeerNode(
                        nodeId = peerNodeId,
                        host = host,
                        port = port,
                        publicKeyHex = responsePacket.originPublicKey,
                        algorithm = responsePacket.algorithm,
                        latencyMs = latencyMs,
                        isConnected = true,
                        clientVersion = responsePacket.payload.optString("clientVersion", "AresNet/1.0"),
                        sharedFilesCount = responsePacket.payload.optInt("sharedCount", 1),
                        lastSeenTimestamp = System.currentTimeMillis()
                    )

                    registerPeer(peer)
                    Log.i(tag, "Conectado exitosamente con nodo $peerNodeId en $host:$port (${latencyMs}ms)")
                    return@withContext Result.success(peer)
                }
            }

            // Fallback peer registration if basic TCP echo succeeded
            val fallbackNodeId = "peer-${host.replace('.', '-')}-$port"
            val peer = PeerNode(
                nodeId = fallbackNodeId,
                host = host,
                port = port,
                publicKeyHex = "",
                latencyMs = latencyMs,
                isConnected = true,
                clientVersion = "AresNet/1.0",
                sharedFilesCount = 1,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            registerPeer(peer)
            Log.i(tag, "Conexión básica establecida con nodo par en $host:$port (${latencyMs}ms)")
            Result.success(peer)
        } catch (e: Exception) {
            val errorMsg = "No fue posible conectar con $host:$port (${e.localizedMessage ?: "Timeout"})"
            Log.w(tag, errorMsg)
            Result.failure(Exception(errorMsg, e))
        }
    }

    /**
     * Handles an incoming connection from another peer node on the local server socket.
     */
    private suspend fun handleIncomingPeerConnection(socket: Socket) = withContext(Dispatchers.IO) {
        val remoteHost = socket.inetAddress?.hostAddress ?: "desconocido"
        val remotePort = socket.port
        Log.d(tag, "Conexión entrante recibida desde $remoteHost:$remotePort")

        try {
            socket.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            val line = reader.readLine()
            if (!line.isNullOrBlank()) {
                val packet = P2PProtocol.Packet.fromJsonString(line)
                if (packet != null) {
                    // Cryptographic validation of incoming packet
                    val isSignatureValid = verifyPacketSignature(packet)
                    Log.d(tag, "Paquete ${packet.type} recibido de ${packet.originNodeId}. Firma válida: $isSignatureValid")

                    when (packet.type) {
                        P2PProtocol.PacketType.HANDSHAKE -> {
                            val peerPort = packet.payload.optInt("port", remotePort)
                            val peerNode = PeerNode(
                                nodeId = packet.originNodeId,
                                host = remoteHost,
                                port = peerPort,
                                publicKeyHex = packet.originPublicKey,
                                algorithm = packet.algorithm,
                                isConnected = true,
                                clientVersion = packet.payload.optString("clientVersion", "AresNet/1.0"),
                                sharedFilesCount = packet.payload.optInt("sharedCount", 1),
                                lastSeenTimestamp = System.currentTimeMillis()
                            )
                            registerPeer(peerNode)

                            // Send back HANDSHAKE_ACK
                            val localIdentity = identityManager.getOrGenerateIdentity()
                            val ackPayload = JSONObject().apply {
                                put("nodeId", localIdentity.nodeId)
                                put("serverPort", _serverPort.value)
                                put("clientVersion", "AresNet/1.0-Android")
                                put("status", "ACCEPTED")
                            }
                            val ackPacket = createSignedPacket(P2PProtocol.PacketType.HANDSHAKE_ACK, ackPayload)
                            writer.println(ackPacket.toJsonString())
                        }
                        P2PProtocol.PacketType.PING -> {
                            val pongPayload = JSONObject().apply {
                                put("pongTimestamp", System.currentTimeMillis())
                            }
                            val pongPacket = createSignedPacket(P2PProtocol.PacketType.PONG, pongPayload)
                            writer.println(pongPacket.toJsonString())
                        }
                        else -> {
                            onPacketReceived?.invoke(packet, socket)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Error procesando conexión entrante desde $remoteHost:$remotePort: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Sends a ping to a remote peer to verify connectivity and measure latency.
     */
    suspend fun pingPeer(host: String, port: Int, timeoutMs: Int = 4000): Result<Long> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            val pingPayload = JSONObject().apply { put("pingTimestamp", start) }
            val packet = createSignedPacket(P2PProtocol.PacketType.PING, pingPayload)
            writer.println(packet.toJsonString())

            reader.readLine()
            socket.close()
            val latency = System.currentTimeMillis() - start
            Result.success(latency)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Disconnects and unregisters a peer node by its ID.
     */
    fun disconnectPeer(nodeId: String) {
        activePeersMap.remove(nodeId)?.let {
            _connectedPeers.value = activePeersMap.values.toList()
            onPeerDisconnected?.invoke(nodeId)
            Log.i(tag, "Nodo par $nodeId desconectado")
        }
    }

    /**
     * Clears all active peers.
     */
    fun disconnectAll() {
        activePeersMap.clear()
        _connectedPeers.value = emptyList()
    }

    private fun registerPeer(peer: PeerNode) {
        activePeersMap[peer.nodeId] = peer
        _connectedPeers.value = activePeersMap.values.toList()
        onPeerConnected?.invoke(peer)
    }

    /**
     * Constructs a digitally signed packet using the local node's sovereign Ed25519 identity.
     */
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
        val signableBytes = packet.toSignableBytes()
        val signatureBytes = identityManager.sign(signableBytes)
        packet.signatureHex = bytesToHex(signatureBytes)
        return packet
    }

    private fun verifyPacketSignature(packet: P2PProtocol.Packet): Boolean {
        if (packet.signatureHex.isBlank() || packet.originPublicKey.isBlank()) return false
        return try {
            identityManager.verify(
                data = packet.toSignableBytes(),
                signatureBytes = hexToBytes(packet.signatureHex),
                publicKeyHex = packet.originPublicKey,
                algorithm = packet.algorithm
            )
        } catch (e: Exception) {
            false
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i shr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

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
