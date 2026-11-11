package com.example.monetization

import android.content.Context
import com.google.android.gms.ads.MobileAds

/**
 * AdMob configuration and initialization.
 * Uses official Google sample test ad units for zero-risk open-source development,
 * and decouples payment/acceleration services.
 */
object AdMobConfig {

    // Official Google AdMob sample test banner ad unit ID
    const val SAMPLE_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

    @Volatile
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val requestConfig = com.google.android.gms.ads.RequestConfiguration.Builder()
                .setTestDeviceIds(listOf(com.google.android.gms.ads.AdRequest.DEVICE_ID_EMULATOR))
                .build()
            MobileAds.setRequestConfiguration(requestConfig)

            MobileAds.initialize(context) {
                isInitialized = true
            }
        } catch (e: Exception) {
            // Graceful fallback if Google Play services is unavailable in container/emulator
        }
    }
}

/**
 * Decoupled interface for future P2P download acceleration and micropayment gateways (e.g. USDC / Solana).
 */
interface AccelerationGateway {
    data class AccelerationTier(
        val tierId: String,
        val name: String,
        val speedMultiplier: Double,
        val priceUsdc: Double,
        val prioritySeeding: Boolean
    )

    suspend fun getAvailableTiers(): List<AccelerationTier>
    suspend fun initiateMicropayment(tierId: String, walletAddress: String): Result<String>
}

class DefaultAccelerationGateway : AccelerationGateway {
    override suspend fun getAvailableTiers(): List<AccelerationGateway.AccelerationTier> {
        return listOf(
            AccelerationGateway.AccelerationTier(
                tierId = "tier_free",
                name = "AresNet Mesh Comunitario",
                speedMultiplier = 1.0,
                priceUsdc = 0.0,
                prioritySeeding = false
            ),
            AccelerationGateway.AccelerationTier(
                tierId = "tier_speed_turbo",
                name = "AresNet Turbo Swarm (Solana/USDC)",
                speedMultiplier = 5.0,
                priceUsdc = 0.50,
                prioritySeeding = true
            )
        )
    }

    override suspend fun initiateMicropayment(tierId: String, walletAddress: String): Result<String> {
        return Result.success("0x_simulated_solana_signature_$tierId")
    }
}
