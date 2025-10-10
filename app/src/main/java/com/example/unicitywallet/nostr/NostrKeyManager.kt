package com.example.unicitywallet.nostr

import android.content.Context
import android.util.Log
import com.example.unicitywallet.identity.IdentityManager
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.runBlocking
import org.spongycastle.crypto.digests.SHA256Digest
import org.spongycastle.util.encoders.Hex
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NostrKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "NostrKeyManager"
        private const val NOSTR_PREFS = "NostrPrefs"
        private const val KEY_NOSTR_INITIALIZED = "nostr_initialized"
    }

    private val identityManager = IdentityManager(context)
    private val prefs = context.getSharedPreferences(NOSTR_PREFS, Context.MODE_PRIVATE)
    private val secp256k1 = Secp256k1.get()

    // Cached keys from wallet identity
    private var privateKeyBytes: ByteArray? = null
    private var publicKeyBytes: ByteArray? = null
    private var publicKeyHex: String? = null

    /**
     * Initialize Nostr keys from wallet identity
     * This reuses the wallet's existing secp256k1 keys
     */
    fun initializeKeys() {
        try {
            // Get current wallet identity
            val identity = runBlocking {
                identityManager.getCurrentIdentity()
            }

            if (identity != null) {
                // Use wallet's existing keys
                privateKeyBytes = hexToBytes(identity.privateKey)

                // The wallet stores compressed public key (33 bytes)
                // For Nostr, we need the X coordinate only (32 bytes)
                val walletPubKey = hexToBytes(identity.publicKey)
                publicKeyBytes = if (walletPubKey.size == 33) {
                    // Extract X coordinate (skip first compression byte)
                    walletPubKey.sliceArray(1..32)
                } else {
                    walletPubKey
                }

                publicKeyHex = Hex.toHexString(publicKeyBytes)

                // Mark as initialized
                prefs.edit().putBoolean(KEY_NOSTR_INITIALIZED, true).apply()

                Log.d(TAG, "Initialized Nostr with wallet identity")
                Log.d(TAG, "Nostr public key (npub): ${toBech32PublicKey()}")
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
     * Get the private key as hex string
     */
    fun getPrivateKey(): String {
        ensureInitialized()
        return privateKeyBytes?.let { Hex.toHexString(it) } ?: ""
    }

    /**
     * Get the private key as byte array
     */
    fun getPrivateKeyBytes(): ByteArray {
        ensureInitialized()
        return privateKeyBytes ?: throw IllegalStateException("Private key not initialized")
    }

    /**
     * Get the public key as hex string (32 bytes, X coordinate only for Nostr)
     */
    fun getPublicKey(): String {
        ensureInitialized()
        return publicKeyHex ?: ""
    }

    /**
     * Get the public key as byte array
     */
    fun getPublicKeyBytes(): ByteArray {
        ensureInitialized()
        return publicKeyBytes ?: throw IllegalStateException("Public key not initialized")
    }

    /**
     * Sign a message (32-byte hash) with the private key
     * Returns Schnorr signature for Nostr events
     */
    fun sign(messageHash: ByteArray): ByteArray {
        ensureInitialized()
        require(messageHash.size == 32) { "Message hash must be 32 bytes" }

        val privKey = privateKeyBytes ?: throw IllegalStateException("Private key not initialized")

        // Use secp256k1 to create Schnorr signature
        return try {
            secp256k1.signSchnorr(messageHash, privKey, null)
        } catch (e: Exception) {
            Log.e(TAG, "Schnorr signing failed, falling back to ECDSA", e)
            // Fallback to ECDSA if Schnorr is not available
            secp256k1.sign(messageHash, privKey)
        }
    }

    /**
     * Verify a signature
     */
    fun verify(signature: ByteArray, messageHash: ByteArray, publicKey: ByteArray): Boolean {
        require(messageHash.size == 32) { "Message hash must be 32 bytes" }

        return try {
            // Try Schnorr verification first
            if (signature.size == 64) {
                secp256k1.verifySchnorr(signature, messageHash, publicKey)
            } else {
                // Fall back to ECDSA
                secp256k1.verify(signature, messageHash, publicKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed", e)
            false
        }
    }

    /**
     * Derive a shared secret for NIP-04 encryption using proper ECDH
     * This implements the actual ECDH: sharedPoint = theirPublicKey * ourPrivateKey
     */
    fun deriveSharedSecret(theirPublicKey: ByteArray): ByteArray {
        ensureInitialized()
        val privKey = privateKeyBytes ?: throw IllegalStateException("Private key not initialized")

        return try {
            // Ensure their public key is in the right format (33 bytes compressed)
            val theirPubKeyCompressed = when (theirPublicKey.size) {
                32 -> {
                    // X-coordinate only, add compression byte (0x02 for even Y)
                    byteArrayOf(0x02) + theirPublicKey
                }
                33 -> theirPublicKey  // Already compressed
                64 -> {
                    // Uncompressed, need to compress it
                    val x = theirPublicKey.sliceArray(0..31)
                    val y = theirPublicKey.sliceArray(32..63)
                    // Check if Y is even or odd for compression byte
                    val compressionByte = if ((y[31].toInt() and 1) == 0) 0x02 else 0x03
                    byteArrayOf(compressionByte.toByte()) + x
                }
                else -> throw IllegalArgumentException("Invalid public key size: ${theirPublicKey.size}")
            }

            // Perform ECDH: multiply their public key by our private key
            val sharedPoint = secp256k1.pubKeyTweakMul(theirPubKeyCompressed, privKey)

            // Extract X coordinate from the shared point (for NIP-04 compatibility)
            val sharedX = if (sharedPoint.size == 33) {
                sharedPoint.sliceArray(1..32)
            } else {
                sharedPoint
            }

            // Hash the X coordinate to get the shared secret (NIP-04 standard)
            val digest = SHA256Digest()
            digest.update(sharedX, 0, sharedX.size)
            val sharedSecret = ByteArray(32)
            digest.doFinal(sharedSecret, 0)

            sharedSecret
        } catch (e: Exception) {
            Log.e(TAG, "ECDH failed", e)
            throw IllegalStateException("Failed to derive shared secret", e)
        }
    }

    /**
     * Encrypt a message for NIP-04 (encrypted direct messages)
     * Uses AES-256-CBC with the shared secret
     */
    fun encryptMessage(message: String, theirPublicKey: ByteArray): String {
        val sharedSecret = deriveSharedSecret(theirPublicKey)

        // Generate random IV
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)

        // Encrypt with AES-256-CBC
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(sharedSecret, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)

        val encrypted = cipher.doFinal(message.toByteArray(Charsets.UTF_8))

        // Format: base64(encrypted) + "?iv=" + base64(iv) (NIP-04 format)
        val encryptedBase64 = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        val ivBase64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)

        return "$encryptedBase64?iv=$ivBase64"
    }

    /**
     * Decrypt a NIP-04 encrypted message
     */
    fun decryptMessage(encryptedContent: String, theirPublicKey: ByteArray): String {
        val sharedSecret = deriveSharedSecret(theirPublicKey)

        // Parse the encrypted content
        val parts = encryptedContent.split("?iv=")
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid encrypted message format")
        }

        val encrypted = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
        val iv = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)

        // Decrypt with AES-256-CBC
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(sharedSecret, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * Convert public key to Bech32 format (npub...)
     */
    fun toBech32PublicKey(): String {
        val pubKeyHex = getPublicKey()
        if (pubKeyHex.isEmpty()) return ""

        return try {
            Bech32.encode("npub", Hex.decode(pubKeyHex))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode public key to bech32", e)
            ""
        }
    }

    /**
     * Convert private key to Bech32 format (nsec...)
     * Should only be used for backup/export with user consent
     */
    fun toBech32PrivateKey(): String {
        val privKeyHex = getPrivateKey()
        if (privKeyHex.isEmpty()) return ""

        return try {
            Bech32.encode("nsec", Hex.decode(privKeyHex))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode private key to bech32", e)
            ""
        }
    }

    /**
     * Parse a Bech32 encoded key (npub or nsec)
     */
    fun fromBech32(bech32: String): Pair<String, ByteArray> {
        return Bech32.decode(bech32)
    }

    /**
     * Check if a public key belongs to us
     */
    fun isOurPublicKey(publicKeyHex: String): Boolean {
        ensureInitialized()
        return publicKeyHex.equals(this.publicKeyHex, ignoreCase = true)
    }

    /**
     * Clear cached keys from memory (for security)
     */
    fun clearKeys() {
        privateKeyBytes?.fill(0)
        publicKeyBytes?.fill(0)
        privateKeyBytes = null
        publicKeyBytes = null
        publicKeyHex = null
    }

    private fun ensureInitialized() {
        if (privateKeyBytes == null || publicKeyBytes == null) {
            initializeKeys()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}

/**
 * Bech32 encoding utility for Nostr keys (NIP-19)
 * Reference: https://github.com/nostr-protocol/nips/blob/master/19.md
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV = CHARSET.mapIndexed { i, c -> c to i }.toMap()

    fun encode(hrp: String, data: ByteArray): String {
        val values = convertBits(data, 8, 5, true)
        val checksum = createChecksum(hrp, values)
        val combined = values + checksum
        return hrp + "1" + combined.map { CHARSET[it] }.joinToString("")
    }

    fun decode(bech32: String): Pair<String, ByteArray> {
        val pos = bech32.lastIndexOf('1')
        require(pos >= 0) { "Invalid bech32 string" }

        val hrp = bech32.substring(0, pos).lowercase()
        val data = bech32.substring(pos + 1).map {
            CHARSET_REV[it.lowercaseChar()] ?: throw IllegalArgumentException("Invalid character in bech32 string")
        }.toIntArray()

        require(verifyChecksum(hrp, data)) { "Invalid checksum" }

        val values = data.dropLast(6).toIntArray()
        val bytes = convertBits(values, 5, 8, false)

        return hrp to bytes
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val ret = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1
        val max_acc = (1 shl (fromBits + toBits - 1)) - 1

        for (value in data) {
            val b = value.toInt() and 0xFF
            acc = ((acc shl fromBits) or b) and max_acc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add((acc shr bits) and maxv)
            }
        }

        if (pad) {
            if (bits > 0) {
                ret.add((acc shl (toBits - bits)) and maxv)
            }
        } else {
            require(bits < fromBits && ((acc shl (toBits - bits)) and maxv) == 0) {
                "Invalid padding in convertBits"
            }
        }

        return ret.toIntArray()
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val ret = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        val max_acc = (1 shl (fromBits + toBits - 1)) - 1

        for (value in data) {
            acc = ((acc shl fromBits) or value) and max_acc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add(((acc shr bits) and maxv).toByte())
            }
        }

        if (pad) {
            if (bits > 0) {
                ret.add(((acc shl (toBits - bits)) and maxv).toByte())
            }
        } else {
            require(bits < fromBits && ((acc shl (toBits - bits)) and maxv) == 0) {
                "Invalid padding in convertBits"
            }
        }

        return ret.toByteArray()
    }

    private fun createChecksum(hrp: String, values: IntArray): IntArray {
        val enc = hrpExpand(hrp) + values + IntArray(6)
        val mod = polymod(enc) xor 1
        return IntArray(6) { i -> (mod shr (5 * (5 - i))) and 31 }
    }

    private fun verifyChecksum(hrp: String, values: IntArray): Boolean {
        return polymod(hrpExpand(hrp) + values) == 1
    }

    private fun hrpExpand(hrp: String): IntArray {
        return hrp.map { it.code shr 5 }.toIntArray() +
                IntArray(1) +
                hrp.map { it.code and 31 }.toIntArray()
    }

    private fun polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (value in values) {
            val b = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor value
            for (i in 0..4) {
                if (((b shr i) and 1) != 0) {
                    chk = chk xor gen[i]
                }
            }
        }
        return chk
    }
}