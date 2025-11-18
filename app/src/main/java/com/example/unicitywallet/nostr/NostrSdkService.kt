package com.example.unicitywallet.nostr

import android.content.Context
import android.util.Log
import com.example.unicitywallet.data.repository.WalletRepository
import com.example.unicitywallet.p2p.IP2PService
import com.example.unicitywallet.services.NametagService
import com.example.unicitywallet.services.ServiceProvider
import com.example.unicitywallet.token.UnicityTokenRegistry
import com.example.unicitywallet.utils.HexUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unicitylabs.nostr.client.NostrClient
import org.unicitylabs.nostr.client.NostrEventListener
import org.unicitylabs.nostr.protocol.EventKinds
import org.unicitylabs.nostr.protocol.Filter
import org.unicitylabs.nostr.token.TokenTransferProtocol
import org.unicitylabs.sdk.address.AddressScheme
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import org.unicitylabs.sdk.token.Token
import org.unicitylabs.sdk.transaction.TransferTransaction
import org.unicitylabs.nostr.protocol.Event as SdkEvent

/**
 * Nostr service implementation using unicity-nostr-sdk.
 * Replaces the legacy NostrP2PService with proper SDK usage.
 *
 * This service wraps NostrClient and provides wallet-specific features:
 * - Token transfers with automatic finalization
 * - Nametag binding and queries
 * - P2P messaging
 * - Agent discovery and location broadcasting
 */
class NostrSdkService(
    private val context: Context
) : IP2PService {

    companion object {
        private const val TAG = "NostrSdkService"

        // Default public relays
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.info",
            "wss://nostr-pub.wellorder.net"
        )

        // Unicity private relay on AWS
        val UNICITY_RELAYS = listOf(
            "ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080"
        )

        @Volatile
        private var instance: NostrSdkService? = null

        @JvmStatic
        fun getInstance(context: Context?): NostrSdkService? {
            if (context == null && instance == null) {
                return null
            }
            return instance ?: synchronized(this) {
                instance ?: NostrSdkService(context!!).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keyManager: NostrKeyManagerAdapter
    private val nostrClient: NostrClient
    private var isRunning = false

    private val _connectionStatus = MutableStateFlow<Map<String, IP2PService.ConnectionStatus>>(emptyMap())
    override val connectionStatus: StateFlow<Map<String, IP2PService.ConnectionStatus>> = _connectionStatus

    init {
        Log.d(TAG, "Initializing NostrSdkService with SDK NostrClient")

        // Initialize key manager adapter
        keyManager = NostrKeyManagerAdapter(context)
        keyManager.initializeKeys()

        // Create NostrClient with key manager
        nostrClient = NostrClient(keyManager.getSdkKeyManager())

        Log.d(TAG, "NostrSdkService initialized successfully")
    }

    override fun start() {
        if (isRunning) {
            Log.w(TAG, "Service already running")
            return
        }

        Log.d(TAG, "Starting NostrSdkService...")
        isRunning = true

        scope.launch {
            connectToRelays()
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping NostrSdkService")
        isRunning = false
    }

    override fun shutdown() {
        Log.d(TAG, "Shutting down NostrSdkService")
        isRunning = false
        nostrClient.disconnect()
        scope.cancel()
    }

    override fun isRunning(): Boolean = isRunning

    private suspend fun connectToRelays() {
        try {
            Log.d(TAG, "Connecting to ${UNICITY_RELAYS.size} Unicity relays")

            // Connect to relays using SDK
            val relayArray = UNICITY_RELAYS.toTypedArray()
            nostrClient.connect(*relayArray).await()

            Log.d(TAG, "Connected to relays: ${nostrClient.connectedRelays}")

            // Subscribe to relevant events
            subscribeToEvents()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to relays", e)
        }
    }

    private fun subscribeToEvents() {
        val myPubkey = keyManager.getPublicKey()
        Log.d(TAG, "Subscribing to events for pubkey: $myPubkey")

        // Subscribe to token transfers and encrypted messages
        val personalFilter = Filter().apply {
            kinds = listOf(EventKinds.ENCRYPTED_DM, EventKinds.GIFT_WRAP, EventKinds.TOKEN_TRANSFER)
            pTags = listOf(myPubkey)
        }

        nostrClient.subscribe(personalFilter, object : NostrEventListener {
            override fun onEvent(event: SdkEvent) {
                scope.launch {
                    handleIncomingEvent(event)
                }
            }

            override fun onEndOfStoredEvents(subscriptionId: String) {
                Log.d(TAG, "End of stored events for subscription: $subscriptionId")
            }
        })

        // Subscribe to agent locations and profiles (for P2P discovery)
        val agentFilter = Filter().apply {
            kinds = listOf(EventKinds.AGENT_LOCATION, EventKinds.AGENT_PROFILE)
        }

        nostrClient.subscribe(agentFilter, object : NostrEventListener {
            override fun onEvent(event: SdkEvent) {
                scope.launch {
                    handleAgentEvent(event)
                }
            }

            override fun onEndOfStoredEvents(subscriptionId: String) {
                // No action needed
            }
        })

        Log.d(TAG, "Subscriptions created successfully")
    }

    private suspend fun handleIncomingEvent(event: SdkEvent) {
        Log.d(TAG, "Received event kind=${event.kind} from=${event.pubkey.take(16)}...")

        when (event.kind) {
            EventKinds.TOKEN_TRANSFER -> handleTokenTransfer(event)
            EventKinds.ENCRYPTED_DM -> handleEncryptedMessage(event)
            EventKinds.GIFT_WRAP -> handleGiftWrappedMessage(event)
            else -> Log.d(TAG, "Unhandled event kind: ${event.kind}")
        }
    }

    private suspend fun handleAgentEvent(event: SdkEvent) {
        when (event.kind) {
            EventKinds.AGENT_LOCATION -> handleAgentLocation(event)
            EventKinds.AGENT_PROFILE -> handleAgentProfile(event)
        }
    }

    // ==================== Token Transfer Methods ====================

    /**
     * Send a token transfer using SDK TokenTransferProtocol
     */
    suspend fun sendTokenTransfer(recipientPubkey: String, transferPackage: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Sending token transfer to $recipientPubkey using SDK")

                // Use SDK's sendTokenTransfer method
                nostrClient.sendTokenTransfer(recipientPubkey, transferPackage).await()

                Log.d(TAG, "Token transfer sent successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send token transfer", e)
                false
            }
        }
    }

    /**
     * Send a token transfer by nametag (with metadata)
     * This is used for demo crypto transfers
     */
    suspend fun sendTokenTransfer(
        recipientNametag: String,
        tokenJson: String,
        amount: Long? = null,
        symbol: String? = null
    ): Result<String> {
        return try {
            // Resolve recipient's Nostr public key from their nametag
            val recipientPubkey = queryPubkeyByNametag(recipientNametag)
                ?: return Result.failure(Exception("Could not find Nostr public key for nametag: $recipientNametag"))

            Log.d(TAG, "Sending token transfer to $recipientNametag (pubkey: ${recipientPubkey.take(16)}...)")
            Log.d(TAG, "Token size: ${tokenJson.length} bytes, amount: $amount $symbol")

            // Send the token transfer using SDK
            val success = sendTokenTransfer(recipientPubkey, tokenJson)

            if (success) {
                Result.success("Token sent to $recipientNametag")
            } else {
                Result.failure(Exception("Failed to send token transfer"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send token transfer", e)
            Result.failure(e)
        }
    }

    /**
     * Handle incoming token transfer event
     */
    private suspend fun handleTokenTransfer(event: SdkEvent) {
        try {
            Log.d(TAG, "Processing token transfer from ${event.pubkey}")

            // Decrypt and parse using SDK TokenTransferProtocol
            val tokenJson = TokenTransferProtocol.parseTokenTransfer(event, keyManager.getSdkKeyManager())

            Log.d(TAG, "Token transfer decrypted successfully (${tokenJson.length} chars)")

            // Check if it's the new format: token_transfer:{json with sourceToken and transferTx}
            if (tokenJson.startsWith("{") && tokenJson.contains("sourceToken") && tokenJson.contains("transferTx")) {
                Log.d(TAG, "Processing proper token transfer with finalization...")

                val payloadObj = com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    tokenJson,
                    Map::class.java
                ) as Map<*, *>

                handleProperTokenTransfer(payloadObj)
            } else {
                Log.w(TAG, "Unknown token transfer format")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process token transfer", e)
        }
    }

    /**
     * Handle proper token transfer (sourceToken + transferTx format)
     * This is the format used by wallet-to-wallet and faucet transfers
     */
    private suspend fun handleProperTokenTransfer(payloadObj: Map<*, *>) {
        try {
            val sourceTokenJson = payloadObj["sourceToken"] as? String
            val transferTxJson = payloadObj["transferTx"] as? String

            if (sourceTokenJson == null || transferTxJson == null) {
                Log.e(TAG, "Missing sourceToken or transferTx in payload")
                return
            }

            Log.d(TAG, "Payload keys: ${payloadObj.keys}")
            Log.d(TAG, "sourceTokenJson length: ${sourceTokenJson.length}")
            Log.d(TAG, "transferTxJson length: ${transferTxJson.length}")

            // Parse using Unicity SDK
            Log.d(TAG, "Parsing source token...")
            val sourceToken = Token.fromJson(
                sourceTokenJson
            )

            Log.d(TAG, "Parsing transfer transaction...")
            val transferTx = TransferTransaction.fromJson(
                transferTxJson
            )
            Log.d(TAG, "✅ Parsed successfully!")
            Log.d(TAG, "Source token type: ${sourceToken.type}")
            Log.d(TAG, "Transfer recipient: ${transferTx.getData().recipient}")

            // Finalize the transfer
            finalizeTransfer(sourceToken, transferTx)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling proper token transfer", e)
        }
    }

    /**
     * Finalize a token transfer for the recipient
     */
    private suspend fun finalizeTransfer(
        sourceToken: org.unicitylabs.sdk.token.Token<*>,
        transferTx: org.unicitylabs.sdk.transaction.TransferTransaction
    ) {
        try {
            Log.d(TAG, "Starting finalization...")

            val recipientAddress = transferTx.getData().recipient
            Log.d(TAG, "Recipient address: ${recipientAddress.address}")

            val addressScheme = recipientAddress.scheme
            Log.d(TAG, "Address scheme: $addressScheme")

            // Check if transfer is to a PROXY address (nametag-based)
            if (addressScheme == AddressScheme.PROXY) {
                Log.d(TAG, "Transfer is to PROXY address - finalization required")

                // Load ALL user's nametag tokens and find which one matches
                val nametagService = NametagService(context)
                val allNametags = nametagService.getAllNametagTokens()

                if (allNametags.isEmpty()) {
                    Log.e(TAG, "No nametags configured for this wallet")
                    return
                }

                // Find which nametag this transfer is for by checking all proxy addresses
                var matchedNametag: String? = null
                var myNametagToken: org.unicitylabs.sdk.token.Token<*>? = null

                for ((nametagString, nametagToken) in allNametags) {
                    val proxyAddress = org.unicitylabs.sdk.address.ProxyAddress.create(nametagToken.id)
                    if (proxyAddress.address == recipientAddress.address) {
                        matchedNametag = nametagString
                        myNametagToken = nametagToken
                        break
                    }
                }

                if (myNametagToken == null || matchedNametag == null) {
                    Log.e(TAG, "Transfer is not for any of my nametags!")
                    Log.e(TAG, "Got: ${recipientAddress.address}")
                    Log.e(TAG, "My nametags: ${allNametags.keys.joinToString(", ")}")
                    return
                }

                Log.d(TAG, "✅ Transfer is for my nametag: $matchedNametag")

                // Get wallet identity (BIP-39)
                val walletRepository = WalletRepository.getInstance(context)
                val identityManager = walletRepository.getIdentityManager()
                val identity = identityManager.getCurrentIdentity()

                if (identity == null) {
                    Log.e(TAG, "No wallet identity found, cannot finalize transfer")
                    return
                }

                // Create signing service from wallet identity (using createFromSecret, not createFromMaskedSecret)
                val secret = HexUtils.decodeHex(identity.privateKey)
                val signingService = org.unicitylabs.sdk.signing.SigningService.createFromSecret(secret)

                // Create recipient predicate
                val transferSalt = transferTx.getData().salt

                Log.d(TAG, "Creating recipient predicate:")
                Log.d(TAG, "  Identity pubkey: ${identity.publicKey}")
                Log.d(TAG, "  Source TokenId: ${HexUtils.encodeHexString(sourceToken.id.bytes).take(16)}...")
                Log.d(TAG, "  TokenType: ${sourceToken.type}")
                Log.d(TAG, "  Transfer Salt: ${HexUtils.encodeHexString(transferSalt).take(16)}...")

                // Create UnmaskedPredicate using the same parameters as sender
                val recipientPredicate = org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate.create(
                    sourceToken.id,
                    sourceToken.type,
                    signingService,
                    org.unicitylabs.sdk.hash.HashAlgorithm.SHA256,
                    transferSalt
                )

                Log.d(TAG, "✅ Predicate created - PublicKey: ${HexUtils.encodeHexString(recipientPredicate.publicKey).take(32)}...")

                val recipientState = org.unicitylabs.sdk.token.TokenState(recipientPredicate, null)

                Log.d(TAG, "Finalizing transfer with nametag token...")
                Log.d(TAG, "  Transfer Recipient: ${transferTx.getData().recipient.address}")
                Log.d(TAG, "  My Nametag ProxyAddress: ${org.unicitylabs.sdk.address.ProxyAddress.create(myNametagToken.id).address}")

                // Get StateTransitionClient and trustBase
                val client = ServiceProvider.stateTransitionClient
                val trustBase = ServiceProvider.getRootTrustBase()

                // Finalize the transaction with nametag for proxy resolution
                val finalizedToken = try {
                    withContext(Dispatchers.IO) {
                        client.finalizeTransaction(
                            trustBase,
                            sourceToken,
                            recipientState,
                            transferTx,
                            listOf(myNametagToken)
                        )
                    }
                } catch (ve: org.unicitylabs.sdk.verification.VerificationException) {
                    Log.e(TAG, "❌ VERIFICATION FAILED")
                    Log.e(TAG, "Verification Result: ${ve.verificationResult}")
                    throw ve
                }

                Log.i(TAG, "✅ Token finalized successfully!")

                // Save the finalized token
                saveReceivedToken(finalizedToken)

            } else {
                // DIRECT address transfer - no finalization needed
                Log.d(TAG, "Transfer is to DIRECT address - saving without finalization")
                saveReceivedToken(sourceToken)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize transfer", e)
        }
    }

    /**
     * Save a received token to the wallet
     */
    private suspend fun saveReceivedToken(token: Token<*>) {
        try {
            // Convert SDK token to JSON for storage
            val tokenJson = UnicityObjectMapper.JSON.writeValueAsString(token)

            Log.d(TAG, "Token has coinData: ${token.getCoins().isPresent}")

            // Extract coin metadata if available
            val coinId: String?
            val amount: String?
            val symbol: String?
            val iconUrl: String?

            val coinsOpt = token.getCoins()
            if (coinsOpt.isPresent) {
                val coinData = coinsOpt.get()
                Log.d(TAG, "Coins map from SDK: ${coinData.coins}")

                val firstCoin = coinData.coins.entries.firstOrNull()
                if (firstCoin != null) {
                    coinId = firstCoin.key.bytes.joinToString("") { "%02x".format(it) }
                    amount = firstCoin.value.toString()

                    Log.d(TAG, "Extracted from SDK: coinId=$coinId, amount=$amount")

                    // Look up symbol from registry
                    val registry = UnicityTokenRegistry.getInstance(context)
                    val coinDef = registry.getCoinDefinition(coinId)
                    symbol = coinDef?.symbol ?: "UNKNOWN"
                    iconUrl = coinDef?.getIconUrl()

                    Log.d(TAG, "Found coin definition: ${coinDef?.name} ($symbol)")
                    Log.d(TAG, "Final metadata: symbol=$symbol, amount=$amount, coinId=$coinId")

                    // Show notification
                    showTokenReceivedNotification(amount, symbol)
                } else {
                    Log.w(TAG, "Token has coinData but no entries")
                    coinId = null
                    amount = null
                    symbol = null
                    iconUrl = null
                }
            } else {
                Log.w(TAG, "Token has no coinData (NFT or other type)")
                coinId = null
                amount = null
                symbol = null
                iconUrl = null
            }

            // Create Token model for wallet storage
            val tokenIdHex = token.id.bytes.joinToString("") { "%02x".format(it) }
            val walletToken = com.example.unicitywallet.data.model.Token(
                id = java.util.UUID.randomUUID().toString(),
                name = symbol ?: "Unicity Token",
                type = token.type.toString(),
                jsonData = tokenJson,
                sizeBytes = tokenJson.length,
                status = com.example.unicitywallet.data.model.TokenStatus.CONFIRMED,
                amount = amount,
                coinId = coinId,
                symbol = symbol,
                iconUrl = iconUrl
            )

            // Add to wallet repository
            val walletRepository = WalletRepository.getInstance(context)
            walletRepository.addToken(walletToken)

            Log.i(TAG, "✅ Finalized token saved: $amount $symbol")
            Log.i(TAG, "📬 New token received: $amount $symbol")
            Log.i(TAG, "✅ Token transfer completed and finalized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save received token", e)
        }
    }

    private fun showTokenReceivedNotification(amount: String?, symbol: String) {
        // TODO: Implement notification if needed
        Log.d(TAG, "Token received: $amount $symbol")
    }

    // ==================== Nametag Methods ====================

    /**
     * Publish a nametag binding using SDK
     */
    suspend fun publishNametagBinding(nametagId: String, unicityAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Publishing nametag binding: $nametagId → $unicityAddress")

                // Use SDK's publishNametagBinding method
                nostrClient.publishNametagBinding(nametagId, unicityAddress).await()

                Log.d(TAG, "Nametag binding published successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish nametag binding", e)
                false
            }
        }
    }

    /**
     * Query Nostr pubkey by nametag using SDK
     */
    suspend fun queryPubkeyByNametag(nametagId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Querying pubkey for nametag: $nametagId")

                // Use SDK's queryPubkeyByNametag method
                val pubkey = nostrClient.queryPubkeyByNametag(nametagId).await()

                if (pubkey != null) {
                    Log.d(TAG, "Found pubkey: ${pubkey.take(16)}...")
                } else {
                    Log.d(TAG, "No pubkey found for nametag: $nametagId")
                }

                pubkey
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query nametag", e)
                null
            }
        }
    }

    // ==================== P2P Messaging Methods ====================

    override fun sendMessage(toTag: String, content: String) {
        scope.launch {
            try {
                // Resolve nametag to pubkey
                val recipientPubkey = queryPubkeyByNametag(toTag)
                if (recipientPubkey == null) {
                    Log.e(TAG, "Cannot send message: nametag $toTag not found")
                    return@launch
                }

                // Use SDK's publishEncryptedMessage
                nostrClient.publishEncryptedMessage(recipientPubkey, content).await()

                Log.d(TAG, "Encrypted message sent to $toTag")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        }
    }

    override fun initiateHandshake(agentTag: String) {
        // TODO: Implement using SDK publishEvent
        Log.w(TAG, "initiateHandshake not yet implemented in SDK service")
    }

    override fun acceptHandshake(fromTag: String) {
        // TODO: Implement using SDK publishEvent
        Log.w(TAG, "acceptHandshake not yet implemented in SDK service")
    }

    override fun rejectHandshake(fromTag: String) {
        // TODO: Implement using SDK publishEvent
        Log.w(TAG, "rejectHandshake not yet implemented in SDK service")
    }

    override fun broadcastLocation(latitude: Double, longitude: Double) {
        // TODO: Implement using SDK publishEvent with EventKinds.AGENT_LOCATION
        Log.w(TAG, "broadcastLocation not yet implemented in SDK service")
    }

    override fun updateAvailability(isAvailable: Boolean) {
        // TODO: Implement using SDK publishEvent with EventKinds.AGENT_PROFILE
        Log.w(TAG, "updateAvailability not yet implemented in SDK service")
    }

    // ==================== Event Handlers (Stubs for now) ====================

    private fun handleEncryptedMessage(event: SdkEvent) {
        try {
            val senderPubkeyBytes = HexUtils.decodeHex(event.pubkey)
            val decryptedContent = keyManager.getSdkKeyManager().decrypt(event.content, senderPubkeyBytes)
            Log.d(TAG, "Received encrypted message: $decryptedContent")
            // TODO: Handle P2P messages
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt message", e)
        }
    }

    private fun handleGiftWrappedMessage(event: SdkEvent) {
        Log.d(TAG, "Gift-wrapped message received (not yet implemented)")
        // TODO: Implement gift wrap handling
    }

    private fun handleAgentLocation(event: SdkEvent) {
        Log.d(TAG, "Agent location received (not yet implemented)")
        // TODO: Implement agent location handling
    }

    private fun handleAgentProfile(event: SdkEvent) {
        Log.d(TAG, "Agent profile received (not yet implemented)")
        // TODO: Implement agent profile handling
    }

    // ==================== Helper Methods ====================

    fun getPublicKeyHex(): String {
        return keyManager.getPublicKey()
    }
}