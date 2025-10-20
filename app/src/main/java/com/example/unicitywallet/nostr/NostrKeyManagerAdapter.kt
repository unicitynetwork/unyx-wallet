package com.example.unicitywallet.nostr

import android.content.Context
import android.util.Log
import com.example.unicitywallet.identity.IdentityManager
import kotlinx.coroutines.runBlocking
import org.apache.commons.codec.binary.Hex
import org.unicitylabs.nostr.crypto.NostrKeyManager as SdkKeyManager

/**
 * Android adapter for the SDK's NostrKeyManager.
 * Integrates with wallet's identity system and provides Android-specific initialization.
 */
class NostrKeyManagerAdapter(private val context: Context) {

    companion object {
        private const val TAG = "NostrKeyManagerAdapter"
    }

    private val identityManager = IdentityManager(context)
    private var sdkKeyManager: SdkKeyManager? = null

    /**
     * Initialize keys from wallet identity.
     * Creates the SDK's NostrKeyManager from the wallet's private key.
     */
    fun initializeKeys() {
        try {
            // Get current wallet identity
            val identity = runBlocking {
                identityManager.getCurrentIdentity()
            }

            if (identity != null) {
                // Use wallet's existing keys
                val privateKeyBytes = Hex.decodeHex(identity.privateKey.toCharArray())

                // Create SDK key manager
                sdkKeyManager = SdkKeyManager.fromPrivateKey(privateKeyBytes)

                Log.d(TAG, "Initialized Nostr with wallet identity")
                Log.d(TAG, "Nostr public key (npub): ${sdkKeyManager!!.npub}")
            } else {
                // No wallet identity exists yet - create one
                Log.d(TAG, "No wallet identity found, generating new one")
                val (newIdentity, _) = runBlocking {
                    identityManager.generateNewIdentity()
                }

                // Recursive call to initialize with new identity
                initializeKeys()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Nostr keys from wallet", e)
            throw IllegalStateException("Cannot initialize Nostr without wallet identity", e)
        }
    }

    /**
     * Get the public key as hex string (32 bytes, X coordinate only for Nostr).
     */
    fun getPublicKey(): String {
        ensureInitialized()
        return sdkKeyManager!!.publicKeyHex
    }

    /**
     * Get the public key as byte array.
     */
    fun getPublicKeyBytes(): ByteArray {
        ensureInitialized()
        return sdkKeyManager!!.publicKey
    }

    /**
     * Sign a message (32-byte hash) with the private key.
     * Returns Schnorr signature for Nostr events.
     */
    fun sign(messageHash: ByteArray): ByteArray {
        ensureInitialized()
        return try {
            sdkKeyManager!!.sign(messageHash)
        } catch (e: Exception) {
            Log.e(TAG, "Schnorr signing failed", e)
            throw e
        }
    }

    /**
     * Encrypt a message for NIP-04 (encrypted direct messages).
     * Uses AES-256-CBC with the shared secret.
     * Automatically compresses large messages.
     */
    fun encryptMessage(message: String, theirPublicKey: ByteArray): String {
        ensureInitialized()
        return try {
            sdkKeyManager!!.encrypt(message, theirPublicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            throw e
        }
    }

    /**
     * Decrypt a NIP-04 encrypted message.
     * Automatically decompresses if needed.
     */
    fun decryptMessage(encryptedContent: String, theirPublicKey: ByteArray): String {
        ensureInitialized()
        return try {
            sdkKeyManager!!.decrypt(encryptedContent, theirPublicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            throw e
        }
    }

    /**
     * Get the SDK key manager for direct access.
     */
    fun getSdkKeyManager(): SdkKeyManager {
        ensureInitialized()
        return sdkKeyManager!!
    }

    private fun ensureInitialized() {
        if (sdkKeyManager == null) {
            initializeKeys()
        }
    }
}