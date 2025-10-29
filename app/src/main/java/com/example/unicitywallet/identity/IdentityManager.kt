package com.example.unicitywallet.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import cash.z.ecc.android.random.SecureRandom
import com.example.unicitywallet.data.model.UserIdentity
import com.example.unicitywallet.utils.WalletConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import org.unicitylabs.sdk.hash.HashAlgorithm
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.TokenId
import org.unicitylabs.sdk.token.TokenType
import java.math.BigInteger
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.core.content.edit
import org.unicitylabs.sdk.address.DirectAddress
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicateReference

class IdentityManager(context: Context) {
    companion object {
        private const val TAG = "IdentityManager"
        private const val KEYSTORE_ALIAS = "UnicityWalletIdentity"
        private const val SHARED_PREFS = "identity_prefs"
        private const val KEY_ENCRYPTED_SEED = "encrypted_seed"
        private const val KEY_SEED_IV = "seed_iv"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val WORD_COUNT = 12 // Using 12-word seed phrase
        private const val CURVE_NAME = "secp256k1" // Same curve as used by SigningService
    }
    private val appContext = context.applicationContext
    private val sharedPrefs = appContext.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    suspend fun generateNewIdentity(): Pair<UserIdentity, List<String>> = withContext(Dispatchers.IO) {
        val mnemonicCode = Mnemonics.MnemonicCode(Mnemonics.WordCount.COUNT_12)
        val words = mnemonicCode.words.map { it.concatToString() }

        val seed = mnemonicCode.toSeed()

        val identity = deriveIdentityFromSeed(seed)

        storeSeedPhrase(words)

        Pair(identity, words)
    }

    suspend fun restoreFromSeedPhrase(words: List<String>): UserIdentity? = withContext(Dispatchers.IO) {
        try {
            if (words.size != WORD_COUNT) {
                Log.e(TAG, "Invalid seed phrase length: ${words.size}")
                return@withContext null
            }

            // Create mnemonic from words - join words into a phrase
            val phrase = words.joinToString(" ")
            val mnemonicCode = Mnemonics.MnemonicCode(phrase)

            // Derive seed
            val seed = mnemonicCode.toSeed()
            if (seed.isEmpty()) {
                Log.e(TAG, "Seed generation failed")
                return@withContext null
            }

            val identity = deriveIdentityFromSeed(seed)
            if (identity.privateKey.isEmpty()) {
                Log.e(TAG, "Identity derivation failed")
                return@withContext null
            }
            // Store the new seed phrase
            storeSeedPhrase(words)
            identity

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore from seed phrase", e)
            null
        }
    }

    suspend fun getCurrentIdentity(): UserIdentity? = withContext(Dispatchers.IO) {
        val seedPhrase = getStoredSeedPhrase()
        if (seedPhrase != null) {
            restoreFromSeedPhrase(seedPhrase)
        } else {
            null
        }
    }

    suspend fun getWalletAddress(): DirectAddress? = withContext(Dispatchers.IO) {
        val identity = getCurrentIdentity() ?: return@withContext null

        val secret = hexToBytes(identity.privateKey)

        val signingService = SigningService.createFromSecret(secret)

        val tokenType = TokenType(hexToBytes(WalletConstants.UNICITY_TOKEN_TYPE))

        val predicateRef = UnmaskedPredicateReference.create(
            tokenType,
            signingService,
            HashAlgorithm.SHA256
        )

        predicateRef.toAddress()
    }

    suspend fun getSeedPhrase(): List<String>? = withContext(Dispatchers.IO) {
        getStoredSeedPhrase()
    }

    fun hasIdentity(): Boolean {
        return sharedPrefs.contains(KEY_ENCRYPTED_SEED)
    }

    suspend fun clearIdentity() = withContext(Dispatchers.IO) {
        sharedPrefs.edit { clear() }
        // Note: We don't delete the keystore key as it might be used elsewhere
    }

    private fun deriveIdentityFromSeed(seed: ByteArray): UserIdentity {
        val secret = seed.take(32).toByteArray()

        val nonce = if(seed.size >= 64){
            seed.slice(32..63).toByteArray()
        } else {
            MessageDigest.getInstance("SHA-256").digest(seed)
        }

        val publicKey = derivePublicKey(secret)

        val address = deriveAddress(secret, nonce)

        Log.d(TAG, "Identity derived - Public key: ${bytesToHex(publicKey)}, Address: $address")

        return UserIdentity(
            privateKey = bytesToHex(secret),
            nonce = bytesToHex(nonce),
            publicKey = bytesToHex(publicKey),
            address = address
        )
    }

    private fun derivePublicKey(privateKeyBytes: ByteArray): ByteArray {
        try {
            val ecSpec = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
            val domainParams = ECDomainParameters(
                ecSpec.curve,
                ecSpec.g,
                ecSpec.n,
                ecSpec.h
            )

            val privateKey = ECPrivateKeyParameters(
                BigInteger(1, privateKeyBytes),
                domainParams
            )

            val publicKeyPoint = FixedPointCombMultiplier()
                .multiply(domainParams.g, privateKey.d)
                .normalize()

            return publicKeyPoint.getEncoded(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error deriving public key", e)
            return ByteArray(33)
        }
    }

    private fun deriveAddress(secret: ByteArray, nonce: ByteArray): String {
        try {
            val signingService = SigningService.createFromSecret(secret)

            val tokenType = TokenType(hexToBytes(WalletConstants.UNICITY_TOKEN_TYPE))
            val tokenId = TokenId(ByteArray(32).apply {
                SecureRandom().nextBytes(this)
            })

            val predicate = UnmaskedPredicate.create(
                tokenId,
                tokenType,
                signingService,
                HashAlgorithm.SHA256,
                nonce
            )

            val address = predicate.reference.toAddress()

            return address.toString()

        } catch (e: Exception) {
            Log.e(TAG, "Error deriving address", e)
            val publicKey = derivePublicKey(secret)
            val addressHash = MessageDigest.getInstance("SHA-256").digest(publicKey)
            return bytesToHex(addressHash.take(20).toByteArray())
        }
    }

    private fun storeSeedPhrase(words: List<String>){
        val seedPhrase = words.joinToString(" ")
        val encryptedData = encrypt(seedPhrase.toByteArray())

        sharedPrefs.edit()
            .putString(KEY_ENCRYPTED_SEED, Base64.encodeToString(encryptedData.ciphertext, Base64.DEFAULT))
            .putString(KEY_SEED_IV, Base64.encodeToString(encryptedData.iv, Base64.DEFAULT))
            .apply()
    }

    private fun getStoredSeedPhrase(): List<String>? {
        val encryptedSeed = sharedPrefs.getString(KEY_ENCRYPTED_SEED, null) ?: return null
        val iv = sharedPrefs.getString(KEY_SEED_IV, null) ?: return null

        val ciphertext = Base64.decode(encryptedSeed, Base64.DEFAULT)
        val ivBytes = Base64.decode(iv, Base64.DEFAULT)

        val decrypted = decrypt(ciphertext, ivBytes)
        return String(decrypted).split(" ")
    }

    private fun getOrCreateSecretKey(): SecretKey {
        if (keyStore.containsAlias(KEYSTORE_ALIAS)){
            return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            keyGenerator.init(keyGenParameterSpec)
            return keyGenerator.generateKey()
        }
    }

    private fun encrypt(data: ByteArray): EncryptedData {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val ciphertext = cipher.doFinal(data)
        val iv = cipher.iv

        return EncryptedData(ciphertext, iv)
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(ciphertext)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    private data class EncryptedData(
        val ciphertext: ByteArray,
        val iv: ByteArray
    )
}