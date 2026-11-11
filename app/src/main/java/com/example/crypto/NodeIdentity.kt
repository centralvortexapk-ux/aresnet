package com.example.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Sovereign Node Identity for AresNet P2P.
 * Generates and securely maintains an asymmetric keypair (EC / ECDSA)
 * using AndroidKeystore for hardware-backed key protection.
 * All private key material is never exposed in plaintext.
 */
data class NodeIdentity(
    val nodeId: String,
    val publicKeyHex: String,
    val algorithm: String
)

class NodeIdentityManager(private val context: Context) {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val KEY_ALIAS = "aresnet_node_identity"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREF_NODE_ID = "node_identifier"
        private const val PREF_KEY_VERSION = "key_version"

        private const val CURRENT_KEY_VERSION = 4 // Bump version for AndroidKeystore migration

        private const val ALGO_EC = "EC"
        private const val SIG_ECDSA = "SHA256withECDSA"

        private const val KEY_SIZE = 256
    }

    @Volatile
    private var cachedKeyPair: KeyPair? = null

    @Volatile
    private var cachedNodeId: String? = null

    @Volatile
    private var sharedPreferences: android.content.SharedPreferences? = null

    init {
        loadOrCreateIdentity()
    }

    @Synchronized
    fun getOrGenerateIdentity(): NodeIdentity {
        val keyPair = loadOrCreateIdentity()
        val pubBytes = keyPair.public.encoded
        val pubHex = bytesToHex(pubBytes)
        val algorithm = ALGO_EC
        val nodeId = getOrCreateNodeId()
        return NodeIdentity(
            nodeId = nodeId,
            publicKeyHex = pubHex,
            algorithm = algorithm
        )
    }

    @Synchronized
    private fun loadOrCreateIdentity(): KeyPair {
        cachedKeyPair?.let { return it }

        if (keyStore.containsAlias(KEY_ALIAS)) {
            try {
                val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
                val publicKey = entry.certificate.publicKey
                val privateKey = entry.privateKey
                val pair = KeyPair(publicKey, privateKey)
                cachedKeyPair = pair
                return pair
            } catch (e: Exception) {
                // Corrupted entry, delete and regenerate
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }

        // Generate new EC keypair in AndroidKeystore
        val newPair = generateAsymmetricKeyPair()
        cachedKeyPair = newPair
        return newPair
    }

    private fun generateAsymmetricKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).apply {
            setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            setDigests(KeyProperties.DIGEST_SHA256)
            setUserAuthenticationRequired(false)
            setInvalidatedByBiometricEnrollment(true)
            setRandomizedEncryptionRequired(false)
        }.build()

        keyPairGenerator.initialize(spec)
        keyPairGenerator.generateKeyPair()
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    private fun getOrCreateNodeId(): String {
        cachedNodeId?.let { return it }

        val prefs = getSharedPreferences()
        val savedVersion = prefs.getInt(PREF_KEY_VERSION, 0)
        var nodeId = prefs.getString(PREF_NODE_ID, null)

        if (savedVersion != CURRENT_KEY_VERSION || nodeId == null) {
            val keyPair = cachedKeyPair ?: loadOrCreateIdentity()
            nodeId = "ares-" + generateSha256(keyPair.public.encoded).take(16)
            prefs.edit()
                .putInt(PREF_KEY_VERSION, CURRENT_KEY_VERSION)
                .putString(PREF_NODE_ID, nodeId)
                .commit()
        }

        cachedNodeId = nodeId
        return nodeId
    }

    private fun getSharedPreferences(): android.content.SharedPreferences {
        return sharedPreferences ?: context.getSharedPreferences("aresnet_prefs", Context.MODE_PRIVATE).also {
            sharedPreferences = it
        }
    }

    /**
     * Signs data bytes with the node's private key (hardware-backed).
     */
    fun sign(data: ByteArray): ByteArray {
        val keyPair = loadOrCreateIdentity()
        val signature = getSignatureInstance(keyPair.private.algorithm)
        signature.initSign(keyPair.private)
        signature.update(data)
        return signature.sign()
    }

    /**
     * Verifies data bytes signed with a given public key encoded in X.509 format.
     */
    fun verify(data: ByteArray, signatureBytes: ByteArray, publicKeyHex: String, algorithm: String): Boolean {
        return try {
            val pubBytes = hexToBytes(publicKeyHex)
            val keyFactory = KeyFactory.getInstance(algorithm)
            val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))
            val signature = getSignatureInstance(algorithm)
            signature.initVerify(pubKey)
            signature.update(data)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }

    private fun getSignatureInstance(algorithm: String): Signature {
        return try {
            if (algorithm.equals("Ed25519", ignoreCase = true) || algorithm.contains("Ed", ignoreCase = true)) {
                try {
                    Signature.getInstance("Ed25519")
                } catch (e: Exception) {
                    Signature.getInstance(SIG_ECDSA)
                }
            } else {
                Signature.getInstance(SIG_ECDSA)
            }
        } catch (e: Exception) {
            Signature.getInstance(SIG_ECDSA)
        }
    }

    fun getPublicKeyBytes(): ByteArray {
        return loadOrCreateIdentity().public.encoded
    }

    private fun generateSha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return bytesToHex(digest)
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