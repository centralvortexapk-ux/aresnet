package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.ChunkIntegrity
import com.example.crypto.NodeIdentityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches AresNet`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AresNet", appName)
    }

    @Test
    fun `sovereign identity generates keys and signs data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val identityManager = NodeIdentityManager(context)
        val identity = identityManager.getOrGenerateIdentity()

        assertNotNull(identity.nodeId)
        assertTrue(identity.nodeId.startsWith("ares-"))
        assertTrue(identity.publicKeyHex.isNotEmpty())

        val testData = "AresNet Sovereign Packet Data".toByteArray()
        val signature = identityManager.sign(testData)
        assertTrue(signature.isNotEmpty())

        val isValid = identityManager.verify(
            data = testData,
            signatureBytes = signature,
            publicKeyHex = identity.publicKeyHex,
            algorithm = identity.algorithm
        )
        assertTrue(isValid)
    }

    @Test
    fun `chunk integrity sha256 computes and verifies correctly`() {
        val chunkBytes = "Hello AresNet P2P Decentralized Network Chunk 0".toByteArray()
        val hash = ChunkIntegrity.sha256(chunkBytes)
        assertNotNull(hash)
        assertEquals(64, hash.length) // 64 hex characters for SHA-256

        val isVerified = ChunkIntegrity.verifyChunk(chunkBytes, hash)
        assertTrue(isVerified)
    }

    @Test
    fun `public address info data model formats correctly`() {
        val info = com.example.network.PublicAddressInfo(
            publicIp = "200.45.10.25",
            publicPort = 54321,
            localIp = "192.168.1.50",
            localPort = 8430,
            natType = "Full Cone NAT / STUN Activo",
            isStunSuccessful = true
        )
        assertEquals("200.45.10.25", info.publicIp)
        assertEquals(54321, info.publicPort)
        assertTrue(info.isStunSuccessful)
    }

    @Test
    fun `p2p node manager starts and stops server socket`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val identityManager = NodeIdentityManager(context)
        val nodeManager = com.example.network.P2PNodeManager(context, identityManager)

        assertNotNull(nodeManager)
        assertEquals(com.example.network.ServerState.IDLE, nodeManager.serverState.value)

        // Start server on dynamic port
        nodeManager.startServer(0)
        Thread.sleep(100)

        assertTrue(nodeManager.isServerRunning.value || nodeManager.serverPort.value >= 0)

        // Stop server
        nodeManager.stopServer()
        assertEquals(com.example.network.ServerState.STOPPED, nodeManager.serverState.value)
    }

    @Test
    fun `file security validator rejects dangerous extensions and double extensions`() {
        val badApk = com.example.crypto.FileSecurityValidator.validateFileName("super_game.apk")
        assertTrue(badApk is com.example.crypto.SecurityCheckResult.Rejected)

        val doubleExt = com.example.crypto.FileSecurityValidator.validateFileName("favorite_song.mp3.apk")
        assertTrue(doubleExt is com.example.crypto.SecurityCheckResult.Rejected)

        val pathTraversal = com.example.crypto.FileSecurityValidator.validateFileName("../../data/bad.mp3")
        assertTrue(pathTraversal is com.example.crypto.SecurityCheckResult.Rejected)

        val goodMp3 = com.example.crypto.FileSecurityValidator.validateFileName("cyber_rock.mp3")
        assertTrue(goodMp3 is com.example.crypto.SecurityCheckResult.Safe)
    }

    @Test
    fun `file security validator detects apk disguised as mp3 via magic bytes`() {
        // ZIP/APK header 'PK\x03\x04'
        val fakeChunk0 = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)
        val check = com.example.crypto.FileSecurityValidator.validateMagicBytes(fakeChunk0, "mp3")
        assertTrue(check is com.example.crypto.SecurityCheckResult.Rejected)
    }
}
