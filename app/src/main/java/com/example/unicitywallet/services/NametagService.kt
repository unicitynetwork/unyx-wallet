package com.example.unicitywallet.services


import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import cash.z.ecc.android.random.SecureRandom
import com.example.unicitywallet.identity.IdentityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.unicitylabs.sdk.StateTransitionClient
import org.unicitylabs.sdk.address.DirectAddress
import org.unicitylabs.sdk.api.SubmitCommitmentResponse
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
import org.unicitylabs.sdk.transaction.MintCommitment
import org.unicitylabs.sdk.transaction.MintTransactionReason
import org.unicitylabs.sdk.transaction.NametagMintTransactionData
import org.unicitylabs.sdk.util.InclusionProofUtils
import org.unicitylabs.sdk.verification.VerificationException
import java.io.File
import java.util.concurrent.TimeUnit

class NametagService(
    private val context: Context,
    private val stateTransitionClient: StateTransitionClient = ServiceProvider.stateTransitionClient,
    private val rootTrustBase: RootTrustBase = ServiceProvider.getRootTrustBase()
) {
    private val identityManager = IdentityManager(context)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        private const val TAG = "NametagService"
        private const val NAMETAG_FILE_PREFIX = "nametag_"
        private const val NAMETAG_FILE_SUFFIX = ".json"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    suspend fun mintNameTag(
        nametag: String,
        ownerAddress: DirectAddress
    ): Token<*>? = withContext(Dispatchers.IO){
        try {
            Log.d(TAG, "Minting nametag: $nametag")
            if(!isNetworkAvailable()){
                Log.e(TAG, "No network connection")
                throw IllegalStateException("No network connection. Please check your internet connection")
            }

            val existingNameTag = loadNameTag(nametag)
            if(existingNameTag != null) {
                Log.d(TAG, "Nametag already exists locally: $nametag")
                return@withContext existingNameTag
            }

            val identity = identityManager.getCurrentIdentity() ?: throw IllegalStateException("No wallet identity found")

            val secret = hexToBytes(identity.privateKey)

            val signingService = SigningService.createFromSecret(secret)

            val nametagTokenId = TokenId(ByteArray(32).apply {
                SecureRandom().nextBytes(this)
            })
            val nametagTokenType = TokenType(ByteArray(32).apply {
                SecureRandom().nextBytes(this)
            })
            val nonce = ByteArray(32).apply {
                SecureRandom().nextBytes(this)
            }
            val nametagPredicate = UnmaskedPredicate.create(
                nametagTokenId,
                nametagTokenType,
                signingService,
                HashAlgorithm.SHA256,
                nonce
            )

            val nametagAddress = nametagPredicate.reference.toAddress()

            var submitResponse: SubmitCommitmentResponse? = null
            var lastException: Exception? = null
            var mintCommitment: MintCommitment<NametagMintTransactionData<MintTransactionReason>>? = null

            for(attempt in 1..MAX_RETRY_ATTEMPTS){
                try {
                    val salt = ByteArray(32).apply {
                        SecureRandom().nextBytes(this)
                    }

                    val mintTransactionData: NametagMintTransactionData<MintTransactionReason> = NametagMintTransactionData(
                        nametag,
                        nametagTokenType,
                        nametagAddress,
                        salt,
                        ownerAddress
                    )

                    mintCommitment = MintCommitment.create(mintTransactionData)

                    Log.d(TAG, "Submitting mint commitment (attempt $attempt/$MAX_RETRY_ATTEMPTS)")
                    submitResponse = stateTransitionClient.submitCommitment(mintCommitment).await()

                    if (submitResponse.status == SubmitCommitmentStatus.SUCCESS) {
                        Log.d(TAG, "Mint commitment submitted successfully on attempt $attempt")
                        break
                    } else {
                        Log.w(TAG, "Mint commitment failed with status: ${submitResponse.status} (attempt $attempt)")
                        if (attempt < MAX_RETRY_ATTEMPTS) {
                            delay(RETRY_DELAY_MS * attempt)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error submitting mint commitment (attempt $attempt): ${e.message}", e)
                    lastException = e

                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        Log.d(TAG, "Retrying after ${RETRY_DELAY_MS * attempt}ms...")
                        delay(RETRY_DELAY_MS * attempt) // Exponential backoff

                        // Re-test network and DNS before retry
                        if (!isNetworkAvailable()) {
                            Log.e(TAG, "Network lost during retry")
                            throw IllegalStateException("Network connection lost. Please check your internet connection.")
                        }
                    }
                }
            }

            if (submitResponse?.status != SubmitCommitmentStatus.SUCCESS) {
                val errorMsg = lastException?.message ?: "Unknown error"
                Log.e(TAG, "Failed to submit nametag mint commitment after $MAX_RETRY_ATTEMPTS attempts: $errorMsg")
                throw lastException ?: IllegalStateException("Failed to mint nametag after $MAX_RETRY_ATTEMPTS attempts")
            }

            Log.d(TAG, "Nametag mint commitment submitted successfully")

            val inclusionProof = try {
                withContext(Dispatchers.IO) {
                    InclusionProofUtils.waitInclusionProof(
                        stateTransitionClient,
                        rootTrustBase,
                        mintCommitment
                    ).get(30, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get inclusion proof: ${e.message}", e)
                throw IllegalStateException("Failed to get inclusion proof: ${e.message}")
            }

            val genesisTransaction = mintCommitment?.toTransaction(inclusionProof)

            val trustBase = ServiceProvider.getRootTrustBase()

            val nametagToken = try {
                Token.create(
                    trustBase,
                    TokenState(nametagPredicate, null), // tokenData should be null for nametags
                    genesisTransaction
                )
            } catch (e: VerificationException) {
                // Log the detailed verification error
                Log.e(TAG, "Token verification failed: ${e.message}")
                Log.e(TAG, "VerificationResult: ${e.verificationResult}")
                Log.e(TAG, "Full exception: ", e)
                throw e
            }

            saveNametag(nametag, nametagToken, nonce)
            Log.d(TAG, "Nametag minted and saved successfully: $nametag")

            return@withContext nametagToken
        } catch (e: Exception) {
            Log.e(TAG, "Error minting nametag: ${e.message}", e)
            return@withContext null
        }
    }

    private fun saveNametag(
        nametag: String,
        nametagToken: Token<*>,
        nonce: ByteArray
    ) {
        try {
            val file = getNametagFile(nametag)

            // Create a .txf-compatible JSON structure that includes both token and nonce
            // This format can be exported/imported as a .txf file
            val nametagData = mapOf(
                "nametag" to nametag,
                "token" to UnicityObjectMapper.JSON.writeValueAsString(nametagToken),
                "nonce" to nonce.encodeToString(),
                "timestamp" to System.currentTimeMillis(),
                "format" to "txf",
                "version" to "1.0"
            )

            val jsonData = UnicityObjectMapper.JSON.writeValueAsString(nametagData)
            file.writeText(jsonData)

            Log.d(TAG, "Nametag saved to file: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving nametag: ${e.message}", e)
            throw e
        }
    }
    suspend fun importNametag(
        nametagString: String,
        jsonData: String,
        nonce: ByteArray
    ): Token<*>? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Importing nametag: $nametagString")

            val token = try {
                val wrapper = UnicityObjectMapper.JSON.readTree(jsonData)
                if (wrapper.has("token")) {
                    val tokenJson = wrapper.get("token").asText()
                    UnicityObjectMapper.JSON.readValue(tokenJson, Token::class.java)
                } else {
                    UnicityObjectMapper.JSON.readValue(jsonData, Token::class.java)
                }
            } catch (e: Exception) {
                UnicityObjectMapper.JSON.readValue(jsonData, Token::class.java)
            }

            // Save the imported nametag
            saveNametag(nametagString, token, nonce)

            Log.d(TAG, "Nametag imported successfully: $nametagString")
            return@withContext token

        } catch (e: Exception) {
            Log.e(TAG, "Error importing nametag: ${e.message}", e)
            return@withContext null
        }
    }

    suspend fun loadNameTag(nametag: String): Token<*>? = withContext(Dispatchers.IO) {
        try {
            val file = getNametagFile(nametag)
            if (!file.exists()) {
                return@withContext null
            }

            val jsonData = file.readText()
            val nametagData = UnicityObjectMapper.JSON.readTree(jsonData)

            // Extract token data - it's stored as a string, not an object
            val tokenJson = nametagData.get("token").asText()
            val token = UnicityObjectMapper.JSON.readValue(tokenJson, Token::class.java)

            Log.d(TAG, "Nametag loaded from storage: $nametag")
            return@withContext token

        } catch (e: Exception) {
            Log.e(TAG, "Error loading nametag: ${e.message}", e)
            return@withContext null
        }
    }

    private fun getNametagFile(nametagString: String): File {
        val nametagDir = File(context.filesDir, "nametags")
        if (!nametagDir.exists()) {
            nametagDir.mkdirs()
        }

        val filename = "$NAMETAG_FILE_PREFIX${nametagString.hashCode()}$NAMETAG_FILE_SUFFIX"
        return File(nametagDir, filename)
    }

    private fun ByteArray.encodeToString(): String {
        return android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
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