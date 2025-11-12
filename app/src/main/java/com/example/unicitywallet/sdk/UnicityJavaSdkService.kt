package com.example.unicitywallet.sdk

import android.util.Log
import com.example.unicitywallet.services.ServiceProvider
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.future.await
import org.unicitylabs.sdk.StateTransitionClient
import org.unicitylabs.sdk.api.SubmitCommitmentStatus
import org.unicitylabs.sdk.bft.RootTrustBase
import org.unicitylabs.sdk.hash.HashAlgorithm
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.Token
import org.unicitylabs.sdk.token.TokenId
import org.unicitylabs.sdk.token.TokenState
import org.unicitylabs.sdk.token.TokenType
import org.unicitylabs.sdk.token.fungible.CoinId
import org.unicitylabs.sdk.token.fungible.TokenCoinData
import org.unicitylabs.sdk.transaction.MintCommitment
import org.unicitylabs.sdk.transaction.MintTransaction
import org.unicitylabs.sdk.transaction.MintTransactionReason
import org.unicitylabs.sdk.util.InclusionProofUtils
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * Service for interacting with the Unicity Java SDK 1.1.
 * This service provides coroutine-based wrappers around the Java SDK's CompletableFuture APIs.
 */
class UnicityJavaSdkService(
    private val stateTransitionClient: StateTransitionClient = ServiceProvider.stateTransitionClient,
    private val rootTrustBase: RootTrustBase = ServiceProvider.getRootTrustBase()
) {
    companion object {
        private const val TAG = "UnicityJavaSdkService"

        @Volatile
        private var instance: UnicityJavaSdkService? = null

        fun getInstance(): UnicityJavaSdkService {
            return instance ?: synchronized(this) {
                instance ?: UnicityJavaSdkService().also { instance = it }
            }
        }

        /**
         * Creates a new instance with a custom StateTransitionClient.
         * Useful for testing or different environments.
         */
        fun createInstance(stateTransitionClient: StateTransitionClient): UnicityJavaSdkService {
            return UnicityJavaSdkService(stateTransitionClient)
        }
    }

    private val client: StateTransitionClient
        get() = stateTransitionClient

    private val objectMapper = ObjectMapper()

    /**
     * Mint a new token with the specified amount and data.
     * @param amount The token amount in wei (must be positive)
     * @param data Custom data to include with the token (e.g., currency type)
     * @param secret The wallet's secret key for signing
     * @param nonce The wallet's nonce for key derivation
     * @return The minted token or null if minting fails
     */
    suspend fun mintToken(
        amount: Long,
        data: String,
        secret: ByteArray,
        nonce: ByteArray
    ): Token<*>? {
        return try {
            Log.d(TAG, "Minting token with amount: $amount, data: $data")

            // Create token identifiers
            val random = SecureRandom()
            val tokenIdData = ByteArray(32)
            random.nextBytes(tokenIdData)
            val tokenId = TokenId(tokenIdData)

            val tokenTypeData = ByteArray(32)
            random.nextBytes(tokenTypeData)
            val tokenType = TokenType(tokenTypeData)

            // Create coin data - split amount into two coins
            val coinId1Data = ByteArray(32)
            val coinId2Data = ByteArray(32)
            random.nextBytes(coinId1Data)
            random.nextBytes(coinId2Data)

            val coins = mapOf(
                CoinId(coinId1Data) to BigInteger.valueOf(amount / 2),
                CoinId(coinId2Data) to BigInteger.valueOf(amount - (amount / 2))
            )
            val coinData = TokenCoinData(coins)

            // Create signing service and predicate using wallet identity
            val signingService = SigningService.createFromMaskedSecret(secret, nonce)

            // Create salt for UnmaskedPredicate nonce derivation
            val predicateSalt = ByteArray(32)
            random.nextBytes(predicateSalt)

            val predicate = UnmaskedPredicate.create(
                tokenId,
                tokenType,
                signingService,
                HashAlgorithm.SHA256,
                predicateSalt
            )

            // Get recipient address from predicate reference
            val recipientAddress = predicate.getReference().toAddress()

            // Create token data as byte array
            val tokenDataMap = mapOf(
                "data" to data,
                "amount" to amount
            )
            val tokenDataBytes = objectMapper.writeValueAsBytes(tokenDataMap)

            // Create salt for transaction
            val salt = ByteArray(32)
            random.nextBytes(salt)

            // Create mint transaction data (SDK 1.1 signature)
            val mintData = MintTransaction.Data<MintTransactionReason>(
                tokenId,
                tokenType,
                tokenDataBytes,
                coinData,
                recipientAddress,
                salt,
                null,  // No data hash - data is stored directly
                null   // No reason
            )

            // Create mint commitment
            val commitment = MintCommitment.create(mintData)

            // Submit commitment to network
            val submitResponse = client.submitCommitment(commitment).await()

            if (submitResponse.status != SubmitCommitmentStatus.SUCCESS) {
                Log.e(TAG, "Failed to submit mint commitment: ${submitResponse.status}")
                return null
            }

            Log.d(TAG, "Mint commitment submitted successfully")

            // Wait for inclusion proof (SDK 1.2 requires trustBase)
            val inclusionProof = InclusionProofUtils.waitInclusionProof(client, rootTrustBase, commitment).await()

            // Create transaction from commitment and proof
            val transaction = commitment.toTransaction(inclusionProof)

            // Create token state
            val tokenState = TokenState(predicate, ByteArray(0))

            // Create and return the token
            val trustBase = ServiceProvider.getRootTrustBase()
            val token = Token.create(trustBase, tokenState, transaction)

            Log.d(TAG, "Successfully minted token")
            token

        } catch (e: Exception) {
            Log.e(TAG, "Failed to mint token", e)
            null
        }
    }

    /**
     * Create an offline transfer package that can be shared via NFC.
     * This serializes a transfer commitment that the recipient can later submit.
     * @param tokenJson The serialized token to transfer
     * @param recipientAddress The recipient's address
     * @param amount The amount to transfer (optional, defaults to full token amount)
     * @param senderSecret The sender's secret key
     * @param senderNonce The sender's nonce
     * @return The serialized commitment package or null if creation fails
     */
    suspend fun createOfflineTransfer(
        tokenJson: String,
        recipientAddress: String,
        amount: Long? = null,
        senderSecret: ByteArray,
        senderNonce: ByteArray
    ): String? {
        return try {
            Log.d(TAG, "Creating offline transfer to $recipientAddress")

            // Parse the token JSON to extract necessary data
            val tokenNode = objectMapper.readTree(tokenJson)

            // Create signing service for sender
            val signingService = SigningService.createFromMaskedSecret(senderSecret, senderNonce)

            // Generate salt for the transfer
            val salt = ByteArray(32)
            SecureRandom().nextBytes(salt)

            // Create offline transfer package structure
            // This includes all data needed for recipient to complete the transfer
            val offlinePackage = mapOf(
                "type" to "offline_transfer",
                "version" to "1.1",
                "sender" to mapOf(
                    "address" to tokenNode.path("state").path("address").asText(),
                    "publicKey" to Base64.getEncoder().encodeToString(signingService.publicKey)
                ),
                "recipient" to recipientAddress,
                "token" to tokenJson,
                "commitment" to mapOf(
                    "salt" to Base64.getEncoder().encodeToString(salt),
                    "timestamp" to System.currentTimeMillis(),
                    "amount" to (amount ?: tokenNode.path("amount").asLong(0))
                ),
                "network" to "test"
            )

            // Serialize the package
            val packageJson = objectMapper.writeValueAsString(offlinePackage)

            Log.d(TAG, "Created offline transfer package (${packageJson.length} bytes)")
            packageJson

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create offline transfer", e)
            null
        }
    }

    /**
     * Complete an offline transfer by processing the package from sender.
     * This would typically submit the transfer commitment to the network.
     * @param offlinePackage The serialized package from the sender
     * @param recipientSecret The recipient's secret key
     * @param recipientNonce The recipient's nonce
     * @return The completed token or null if completion fails
     */
    suspend fun completeOfflineTransfer(
        offlinePackage: String,
        recipientSecret: ByteArray,
        recipientNonce: ByteArray
    ): Token<*>? {
        return try {
            Log.d(TAG, "Completing offline transfer")

            // Parse the offline package
            val packageNode = objectMapper.readTree(offlinePackage)

            // Verify package version
            val version = packageNode.path("version").asText()
            if (version != "1.1") {
                Log.e(TAG, "Unsupported package version: $version")
                return null
            }

            // Extract token and transfer data
            val tokenJson = packageNode.path("token").toString()
            val recipientAddress = packageNode.path("recipient").asText()

            // Create recipient's signing service and predicate
            val recipientSigningService = SigningService.createFromMaskedSecret(recipientSecret, recipientNonce)

            // In a real implementation, this would:
            // 1. Deserialize the sender's token
            // 2. Create and submit a transfer commitment
            // 3. Wait for inclusion proof
            // 4. Finalize the transaction with recipient's predicate
            // 5. Return the received token

            // For now, return null as full implementation requires more complex token deserialization
            Log.w(TAG, "Offline transfer completion not fully implemented in SDK 1.1")
            null

        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete offline transfer", e)
            null
        }
    }

    /**
     * Serialize a token to JSON for storage or transfer.
     */
    fun serializeToken(token: Token<*>?): String? {
        if (token == null) {
            return null
        }
        return try {
            // Use SDK's toJson() method
            token.toJson()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize token", e)
            null
        }
    }

    /**
     * Deserialize a token from JSON.
     * Note: This is complex in SDK 1.1 as it requires proper transaction reconstruction.
     */
    fun deserializeToken(tokenJson: String): Token<*>? {
        return try {
            // Token deserialization is complex and requires proper type handling
            // This would need to reconstruct the full token with its transaction history
            Log.w(TAG, "Token deserialization not fully implemented for SDK 1.1")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize token", e)
            null
        }
    }

    // Utility functions

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}