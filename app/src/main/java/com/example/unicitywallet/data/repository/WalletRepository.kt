package com.example.unicitywallet.data.repository

import android.content.SharedPreferences
import android.content.Context
import android.util.Log
import com.example.unicitywallet.data.model.Token
import com.example.unicitywallet.data.model.Wallet
import com.example.unicitywallet.identity.IdentityManager
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import androidx.core.content.edit
import com.example.unicitywallet.data.model.TokenStatus
import com.example.unicitywallet.sdk.UnicityIdentity
import com.example.unicitywallet.sdk.UnicityJavaSdkService
import com.example.unicitywallet.sdk.UnicityMintResult
import com.example.unicitywallet.sdk.UnicityTokenData
import com.example.unicitywallet.utils.JsonMapper
import kotlinx.coroutines.delay
import org.unicitylabs.sdk.serializer.UnicityObjectMapper

class WalletRepository(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("wallet_prefs", android.content.Context.MODE_PRIVATE)

    private val unicitySdkService = UnicityJavaSdkService()

    private val identityManager = IdentityManager(context)

    private val _wallet = MutableStateFlow<Wallet?>(null)
    val wallet: StateFlow<Wallet?> = _wallet.asStateFlow()

    private val _tokens = MutableStateFlow<List<Token>>(emptyList())
    val tokens: StateFlow<List<Token>> = _tokens.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    companion object {
        private const val TAG = "WalletRepository"
        @Volatile
        private var INSTANCE: WalletRepository? = null

        fun getInstance(context: Context): WalletRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WalletRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    init {
        loadWallet()
    }

    private fun loadWallet() {
        Log.d(TAG, "Starting wallet loading")
        val walletJson = sharedPreferences.getString("wallet", null)
        if (walletJson != null) {
            Log.d(TAG, "Wallet JSON is not null")
            val wallet = JsonMapper.fromJson(walletJson, Wallet::class.java)
            _wallet.value = wallet
            _tokens.value = wallet.tokens
            Log.d(TAG, "Here are the tokens: ${_tokens.value}")
        } else {
            // Create new wallet if none exists
            createNewWallet()
        }
    }

    private fun createNewWallet() {
        val baseTime = System.currentTimeMillis()
        val newWallet = Wallet(
            id = UUID.randomUUID().toString(),
            name = "My Wallet",
            address = "unicity_wallet_${baseTime}",
            tokens = emptyList() // Start with empty tokens
        )
        saveWallet(newWallet)
    }

    private fun saveWallet(wallet: Wallet) {
        _wallet.value = wallet
        _tokens.value = wallet.tokens
        val walletJson = JsonMapper.toJson(wallet)
        sharedPreferences.edit {
            putString("wallet", walletJson)
        }
    }

    fun addToken(token: Token) {
        Log.d(TAG, "=== addToken called ===")
        Log.d(TAG, "Token to add: ${token.name}, type: ${token.type}, id: ${token.id}")

        val currentWallet = _wallet.value
        if (currentWallet == null) {
            Log.e(TAG, "Current wallet is null! Cannot add token")
            return
        }

        // Check for duplicates by token ID
        if (currentWallet.tokens.any { it.id == token.id }) {
            Log.w(TAG, "Token with ID ${token.id} already exists, skipping duplicate")
            return
        }

        // CRITICAL: Also check for duplicate SDK token IDs (prevent blockchain REQUEST_ID_EXISTS)
        if (token.jsonData != null) {
            try {
                val newSdkToken = org.unicitylabs.sdk.token.Token.fromJson(
                    token.jsonData
                )
                val newSdkTokenId = newSdkToken.id.bytes.joinToString("") { "%02x".format(it) }

                val hasDuplicateSdkToken = currentWallet.tokens.any { existingToken ->
                    existingToken.jsonData?.let { jsonData ->
                        try {
                            val existingSdkToken = org.unicitylabs.sdk.token.Token.fromJson(
                                jsonData
                            )
                            val existingSdkTokenId = existingSdkToken.id.bytes.joinToString("") { "%02x".format(it) }
                            existingSdkTokenId == newSdkTokenId
                        } catch (e: Exception) {
                            false
                        }
                    } ?: false
                }

                if (hasDuplicateSdkToken) {
                    Log.w(TAG, "⚠️ SDK token with ID ${newSdkTokenId.take(16)}... already exists in wallet!")
                    Log.w(TAG, "Skipping duplicate to prevent REQUEST_ID_EXISTS errors on blockchain")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for duplicate SDK token", e)
            }
        }

        Log.d(TAG, "Current tokens count: ${currentWallet.tokens.size}")

        // Add new token at the beginning (newest first)
        val updatedWallet = currentWallet.copy(
            tokens = listOf(token) + currentWallet.tokens
        )

        Log.d(TAG, "Updated tokens count: ${updatedWallet.tokens.size}")

        saveWallet(updatedWallet)

        Log.d(TAG, "Token added and wallet saved")
    }

    fun removeToken(tokenId: String) {
        val currentWallet = _wallet.value ?: return
        val updatedWallet = currentWallet.copy(
            tokens = currentWallet.tokens.filter { it.id != tokenId }
        )
        saveWallet(updatedWallet)
    }

    fun updateToken(token: Token) {
        val currentWallet = _wallet.value ?: return
        val updatedWallet = currentWallet.copy(
            tokens = currentWallet.tokens.map { if (it.id == token.id) token else it }
        )
        saveWallet(updatedWallet)
    }

    suspend fun refreshTokens() {
        // Add a small delay to show refresh animation
        delay(300)
        // Force reload from storage
        loadWallet()
    }

    fun clearWallet() {
        Log.d(TAG, "=== clearWallet called ===")
        val beforeCount = _wallet.value?.tokens?.size ?: 0
        Log.d(TAG, "Tokens before clear: $beforeCount")

        // Clear existing wallet data
        sharedPreferences.edit(commit = true) { clear() } // Use commit() for immediate write
        Log.d(TAG, "SharedPreferences cleared")

        // Note: We do NOT clear the BIP-39 identity here
        // The identity persists across wallet resets for recovery purposes
        // If you need to clear identity, use identityManager.clearIdentity() separately

        // Create new empty wallet
        createNewWallet()
        Log.d(TAG, "New empty wallet created with ${_wallet.value?.tokens?.size ?: 0} tokens")
    }

    suspend fun mintNewToken(name: String, data: String, amount: Long = 100): Result<Token> {
        Log.d(TAG, "=== mintNewToken started ===")
        Log.d(TAG, "Minting token: name=$name, data=$data, amount=$amount")

        _isLoading.value = true
        return try {
            // Generate identity for the token
            Log.d(TAG, "Generating identity...")
            val identity = generateIdentity()
            Log.d(TAG, "Identity generated: ${identity.privateKey.take(8)}...")

            // Create token data
            val tokenData = UnicityTokenData(data, amount)

            // Mint the token using Unicity SDK
            Log.d(TAG, "Calling SDK mintToken...")
            val mintResult = mintToken(identity, tokenData)
            Log.d(TAG, "Mint result received")

            // Extract the complete token JSON from the mint result
            // The JS SDK returns a finalized, self-contained token that includes:
            // - Token state with unlock predicate
            // - Genesis (mint) transaction with inclusion proof
            // - Complete transaction history
            // This is the same format used for .txf file exports
            val mintResultObj = JsonMapper.fromJson(mintResult.toJson(), Map::class.java)
            val tokenJson = JsonMapper.toJson(mintResultObj["token"])

            // Check if this is a pending token
            val status = if (mintResultObj["status"] == "pending") {
                TokenStatus.PENDING
            } else {
                TokenStatus.CONFIRMED
            }

            // Create Token object with the complete, self-contained token data
            val token = Token(
                name = name,
                type = "Unicity Token",
                unicityAddress = identity.privateKey.take(16), // Use part of private key as address
                jsonData = tokenJson, // Store the complete token in .txf format
                sizeBytes = tokenJson.length,
                status = status
            )

            // Only add to wallet if minting was successful
            Log.d(TAG, "Adding token to wallet...")
            addToken(token)

            Log.d(TAG, "Token minting completed successfully")
            Result.success(token)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mint token", e)
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun generateIdentity(): UnicityIdentity {
        // Check if we already have an identity
        val existingIdentity = identityManager.getCurrentIdentity()
        if (existingIdentity != null) {
            // Return the existing BIP-39 derived identity
            return UnicityIdentity(existingIdentity.privateKey, existingIdentity.nonce)
        }

        // Generate a new BIP-39 identity if none exists
        val (identity, _) = identityManager.generateNewIdentity()
        return UnicityIdentity(identity.privateKey, identity.nonce)
    }

    private suspend fun mintToken(identity: UnicityIdentity, tokenData: UnicityTokenData): UnicityMintResult {
        val token = unicitySdkService.mintToken(
            tokenData.amount,
            tokenData.data,
            identity.privateKey.toByteArray(),
            identity.nonce.toByteArray()
        )

        return if (token != null) {
            try {
                // Convert token to JSON for storage using UnicityObjectMapper
                val tokenJson = token.toJson()
                UnicityMintResult.success(tokenJson)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serialize minted token", e)
                throw Exception("Failed to serialize minted token: ${e.message}", e)
            }
        } else {
            Log.e(TAG, "Failed to mint token")
            throw Exception("Failed to mint token")
        }
    }

    fun getSdkService() = unicitySdkService

    fun getIdentityManager() = identityManager
}