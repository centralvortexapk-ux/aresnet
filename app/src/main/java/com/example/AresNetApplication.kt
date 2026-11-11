package com.example

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.example.crypto.NodeIdentityManager
import com.example.data.local.AresDatabase
import com.example.data.repository.P2PRepository
import com.example.monetization.AdMobConfig
import com.example.network.P2PLocalStreamServer
import com.example.network.P2PNodeEngine
import com.example.player.AresMediaPlayer
import com.example.service.P2PForegroundService

class AresNetApplication : Application() {

    lateinit var database: AresDatabase
        private set

    lateinit var identityManager: NodeIdentityManager
        private set

    lateinit var nodeEngine: P2PNodeEngine
        private set

    lateinit var repository: P2PRepository
        private set

    lateinit var mediaPlayer: AresMediaPlayer
        private set

    lateinit var streamServer: P2PLocalStreamServer
        private set

    lateinit var trackerClient: com.example.network.tracker.P2PTrackerClient
        private set

    lateinit var nodeManager: com.example.network.P2PNodeManager
        private set

    private val TAG = "AresNetApplication"

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize local persistent database
        database = AresDatabase.getInstance(this)

        // 2. Initialize sovereign cryptographic identity (EC/ECDSA via AndroidKeystore)
        identityManager = NodeIdentityManager(this)

        // 3. Initialize P2P socket engine and repository
        nodeEngine = P2PNodeEngine(this, identityManager, database)
        repository = P2PRepository(this, database, nodeEngine)

        // 4. Initialize Local Progressive Streaming Proxy Server (Spotify-style buffer)
        streamServer = P2PLocalStreamServer(this, nodeEngine)

        // 5. Initialize AndroidX Media3 player
        mediaPlayer = AresMediaPlayer(this)

        // 6. Initialize P2P Tracker Client (Option 1 Rendezvous Server)
        trackerClient = com.example.network.tracker.P2PTrackerClient(this, identityManager, database)
        trackerClient.startHeartbeat(com.example.network.P2PProtocol.DEFAULT_PORT)

        // 7. Initialize P2P Node Manager (Socket Server & Peer Connections)
        nodeManager = com.example.network.P2PNodeManager(this, identityManager)

        // 8. Initialize AdMob with sample test configurations
        AdMobConfig.initialize(this)

        // 9. Start P2P engine server and announce to tracker
        try {
            nodeEngine.startServer(com.example.network.P2PProtocol.DEFAULT_PORT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start P2P server", e)
        }

        try {
            trackerClient.startHeartbeat(nodeEngine.currentPort.value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tracker heartbeat", e)
        }

        // 10. Start P2P engine in foreground service
        try {
            P2PForegroundService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start P2P foreground service", e)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // App is going to background, cleanup non-critical resources
            streamServer.purgeOldCacheIfNeeded()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        shutdownComponents()
    }

    private fun shutdownComponents() {
        try {
            trackerClient.stopHeartbeat()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping tracker heartbeat", e)
        }
        try {
            nodeManager.stopServer()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping node manager", e)
        }
        try {
            mediaPlayer.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing media player", e)
        }
        try {
            streamServer.stopServer()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping stream server", e)
        }
        try {
            nodeEngine.stopServer()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping node engine", e)
        }
    }
}
