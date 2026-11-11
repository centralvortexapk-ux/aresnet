package com.example.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

data class PublicAddressInfo(
    val publicIp: String,
    val publicPort: Int,
    val localIp: String,
    val localPort: Int,
    val natType: String,
    val isStunSuccessful: Boolean
)

/**
 * Pure Kotlin STUN (RFC 5389 / RFC 3489) client for P2P NAT Traversal.
 * Resolves the device's public WAN IP address and external NAT port mapping
 * by querying public STUN servers without any external SDK dependencies.
 */
object StunClient {

    private const val TAG = "StunClient"
    private const val MAGIC_COOKIE = 0x2112A442

    private val PUBLIC_STUN_SERVERS = listOf(
        Pair("stun.l.google.com", 19302),
        Pair("stun1.l.google.com", 19302),
        Pair("stun2.l.google.com", 19302),
        Pair("stun.cloudflare.com", 3478)
    )

    suspend fun discoverPublicAddress(localPort: Int): PublicAddressInfo = withContext(Dispatchers.IO) {
        var localIp = "127.0.0.1"
        try {
            val s = DatagramSocket()
            s.connect(InetAddress.getByName("8.8.8.8"), 10002)
            localIp = s.localAddress.hostAddress ?: "127.0.0.1"
            s.close()
        } catch (e: Exception) {
            // fallback
        }

        for (server in PUBLIC_STUN_SERVERS) {
            try {
                val result = queryStunServer(server.first, server.second, localPort)
                if (result != null) {
                    return@withContext PublicAddressInfo(
                        publicIp = result.first,
                        publicPort = result.second,
                        localIp = localIp,
                        localPort = localPort,
                        natType = "NAT Traversal Activo (STUN ${server.first})",
                        isStunSuccessful = true
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "STUN server ${server.first} failed: ${e.message}")
            }
        }

        // Fallback if UDP is blocked by cellular carrier firewall
        PublicAddressInfo(
            publicIp = localIp,
            publicPort = localPort,
            localIp = localIp,
            localPort = localPort,
            natType = "Red Local (Sin respuesta STUN / NAT Simétrico)",
            isStunSuccessful = false
        )
    }

    private fun queryStunServer(host: String, port: Int, bindPort: Int): Pair<String, Int>? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(bindPort).apply {
                soTimeout = 2500
            }
        } catch (e: Exception) {
            try {
                socket = DatagramSocket().apply { soTimeout = 2500 }
            } catch (e2: Exception) {
                return null
            }
        }

        try {
            val address = InetAddress.getByName(host)
            val transactionId = ByteArray(12).apply { SecureRandom().nextBytes(this) }

            // RFC 5389 Header: 20 bytes
            val request = ByteArray(20)
            request[0] = 0x00 // Binding Request (0x0001)
            request[1] = 0x01
            request[2] = 0x00 // Message Length: 0
            request[3] = 0x00
            request[4] = 0x21 // Magic Cookie: 0x2112A442
            request[5] = 0x12
            request[6] = 0xA4.toByte()
            request[7] = 0x42
            System.arraycopy(transactionId, 0, request, 8, 12)

            val sendPacket = DatagramPacket(request, request.size, address, port)
            socket.send(sendPacket)

            val receiveBuf = ByteArray(512)
            val receivePacket = DatagramPacket(receiveBuf, receiveBuf.size)
            socket.receive(receivePacket)

            val data = receivePacket.data
            // Verify STUN Binding Response: 0x0101
            if (data[0] != 0x01.toByte() || data[1] != 0x01.toByte()) {
                return null
            }

            val msgLen = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            var offset = 20
            val end = 20 + msgLen

            while (offset + 4 <= end && offset + 4 <= receivePacket.length) {
                val attrType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                val attrLen = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
                offset += 4

                if (attrType == 0x0020) { // XOR-MAPPED-ADDRESS
                    val family = data[offset + 1].toInt() and 0xFF
                    if (family == 0x01) { // IPv4
                        val xorPort = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
                        val realPort = xorPort xor 0x2112

                        val ip0 = (data[offset + 4].toInt() and 0xFF) xor 0x21
                        val ip1 = (data[offset + 5].toInt() and 0xFF) xor 0x12
                        val ip2 = (data[offset + 6].toInt() and 0xFF) xor 0xA4
                        val ip3 = (data[offset + 7].toInt() and 0xFF) xor 0x42
                        val ipStr = "$ip0.$ip1.$ip2.$ip3"
                        return Pair(ipStr, realPort)
                    }
                } else if (attrType == 0x0001) { // MAPPED-ADDRESS (RFC 3489)
                    val family = data[offset + 1].toInt() and 0xFF
                    if (family == 0x01) { // IPv4
                        val realPort = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
                        val ip0 = data[offset + 4].toInt() and 0xFF
                        val ip1 = data[offset + 5].toInt() and 0xFF
                        val ip2 = data[offset + 6].toInt() and 0xFF
                        val ip3 = data[offset + 7].toInt() and 0xFF
                        val ipStr = "$ip0.$ip1.$ip2.$ip3"
                        return Pair(ipStr, realPort)
                    }
                }
                offset += attrLen
            }
        } catch (e: Exception) {
            Log.d(TAG, "STUN query error: ${e.message}")
        } finally {
            socket.close()
        }
        return null
    }
}
