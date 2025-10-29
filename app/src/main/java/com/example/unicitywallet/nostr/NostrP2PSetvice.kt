package com.example.unicitywallet.nostr

import android.content.Context
import android.util.Log
import com.example.unicitywallet.data.model.Token
import com.example.unicitywallet.data.model.TokenStatus
import com.example.unicitywallet.data.repository.WalletRepository
import com.example.unicitywallet.identity.IdentityManager
import com.example.unicitywallet.p2p.IP2PService
import com.example.unicitywallet.services.NametagService
import com.example.unicitywallet.services.ServiceProvider
import com.example.unicitywallet.token.UnicityTokenRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import com.example.unicitywallet.utils.JsonMapper
import org.unicitylabs.nostr.nametag.NametagBinding
import org.unicitylabs.nostr.protocol.EventKinds
import org.unicitylabs.sdk.address.ProxyAddress
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.TokenState
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.collections.get

data class Event(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
)

/**
 * Nostr-based P2P service implementation for Unicity Wallet
 * Implements decentralized messaging using the Nostr protocol
 */
class NostrP2PService(
    private val context: Context
) : IP2PService {

    companion object {
        private const val TAG = "NostrP2PService"

        // Event Kinds (NIPs + Custom)
        const val KIND_PROFILE = 0
        const val KIND_TEXT_NOTE = 1
        const val KIND_ENCRYPTED_DM = 4
        const val KIND_GIFT_WRAP = 1059
        const val KIND_RELAY_LIST = 10002
        const val KIND_APP_DATA = 30078

        // Custom Unicity event kinds
        const val KIND_AGENT_PROFILE = 31111
        const val KIND_AGENT_LOCATION = 31112
        const val KIND_TOKEN_TRANSFER = 31113
        const val KIND_FILE_METADATA = 31114

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

        private var instance: NostrP2PService? = null

        @JvmStatic
        fun getInstance(context: Context?): NostrP2PService? {
            // If context is null and no instance exists, return null instead of throwing
            if (context == null && instance == null) {
                return null
            }

            return instance ?: synchronized(this) {
                instance ?: NostrP2PService(context!!).also { instance = it }
            }
        }
    }

    // Core components
    private val keyManager = NostrKeyManagerAdapter(context)
    // Using shared JsonMapper
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // WebSocket connections
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No read timeout for WebSocket
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS) // Send ping every 25 seconds to keep connection alive
        .build()

    // Relay connections
    private val relayConnections = mutableMapOf<String, WebSocket>()
    private val eventListeners = mutableListOf<(Event) -> Unit>()

    // State management
    private var isRunning = false
    private val _connectionStatus = MutableStateFlow<Map<String, IP2PService.ConnectionStatus>>(emptyMap())
    override val connectionStatus: StateFlow<Map<String, IP2PService.ConnectionStatus>> = _connectionStatus

    // Message queue for offline delivery
    private val messageQueue = mutableListOf<QueuedMessage>()

    data class QueuedMessage(
        val event: Event,
        val relays: List<String>,
        val timestamp: Long = System.currentTimeMillis()
    )

    override fun start() {
        if (isRunning) return

        isRunning = true
        Log.d(TAG, "Starting Nostr P2P Service")

        scope.launch {
            // Initialize keys
            keyManager.initializeKeys()

            // Connect to relays
            connectToRelays()

            // Publish profile
            publishProfile()

            // Start agent discovery if configured
            if (isAgentMode()) {
                startAgentDiscovery()
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping Nostr P2P Service")
        isRunning = false

        // Close all relay connections
        relayConnections.values.forEach { ws ->
            ws.close(1000, "Service stopped")
        }
        relayConnections.clear()
    }

    override fun shutdown() {
        stop()
        scope.cancel()
    }

    override fun isRunning(): Boolean = isRunning

    /**
     * Connect to Nostr relays
     */
    private suspend fun connectToRelays() {
        val relays = getAllRelays()

        relays.forEach { relayUrl ->
            connectToRelay(relayUrl)
        }
    }

    /**
     * Connect to a single relay
     */
    private fun connectToRelay(url: String) {
        // Check if already connected
        if (relayConnections.containsKey(url)) {
            Log.d(TAG, "Already connected to relay: $url")
            return
        }

        Log.d(TAG, "Connecting to relay: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        val webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to relay: $url")
                relayConnections[url] = webSocket
                updateConnectionStatus(url, true)

                // Send queued messages
                flushMessageQueue(url)

                // Subscribe to relevant events
                subscribeToEvents(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleRelayMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling relay message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Relay closing: $url - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Relay closed: $url")
                relayConnections.remove(url)
                updateConnectionStatus(url, false)

                // Reconnect if service is still running
                if (isRunning) {
                    scope.launch {
                        delay(5000)
                        connectToRelay(url)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Relay connection failed: $url", t)
                relayConnections.remove(url)
                updateConnectionStatus(url, false)

                // Reconnect if service is still running
                if (isRunning) {
                    scope.launch {
                        delay(10000)
                        connectToRelay(url)
                    }
                }
            }
        })
    }

    /**
     * Handle incoming relay messages
     */
    private fun handleRelayMessage(message: String) {
        try {
            val json = JsonMapper.fromJson(message, List::class.java)

            when (json[0]) {
                "EVENT" -> {
                    // Handle incoming event
                    val subscriptionId = json[1] as String
                    val eventData = json[2] as Map<*, *>
                    handleIncomingEvent(eventData)
                }
                "OK" -> {
                    // Event accepted
                    val eventId = json[1] as String
                    val success = json[2] as Boolean
                    val message = if (json.size > 3) json[3] as String else ""
                    Log.d(TAG, "Event $eventId: success=$success, message=$message")
                }
                "EOSE" -> {
                    // End of stored events
                    val subscriptionId = json[1] as String
                    Log.d(TAG, "End of stored events for subscription: $subscriptionId")
                }
                "NOTICE" -> {
                    // Server notice
                    val notice = json[1] as String
                    Log.i(TAG, "Relay notice: $notice")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing relay message", e)
        }
    }

    /**
     * Handle incoming event
     */
    private fun handleIncomingEvent(eventData: Map<*, *>) {
        val event = parseEvent(eventData)

        // Notify listeners (use toList() to avoid ConcurrentModificationException)
        eventListeners.toList().forEach { listener ->
            try {
                listener(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }

        // Handle based on event kind
        when (event.kind) {
            KIND_ENCRYPTED_DM -> handleEncryptedMessage(event)
            KIND_GIFT_WRAP -> handleGiftWrappedMessage(event)
            KIND_AGENT_LOCATION -> handleAgentLocation(event)
            KIND_TOKEN_TRANSFER -> handleTokenTransfer(event)
            KIND_FILE_METADATA -> handleFileMetadata(event)
        }
    }

    /**
     * Parse event from JSON data
     */
    private fun parseEvent(data: Map<*, *>): Event {
        return Event(
            id = data["id"] as String,
            pubkey = data["pubkey"] as String,
            created_at = when (val ts = data["created_at"]) {
                is Number -> ts.toLong()
                else -> 0L
            },
            kind = when (val k = data["kind"]) {
                is Number -> k.toInt()
                else -> 0
            },
            tags = (data["tags"] as List<*>).map { it as List<String> },
            content = data["content"] as String,
            sig = data["sig"] as String
        )
    }

    /**
     * Subscribe to relevant events
     */
    private fun subscribeToEvents(webSocket: WebSocket) {
        val publicKey = keyManager.getPublicKey()

        val prefs = context.getSharedPreferences("NostrP2PService", Context.MODE_PRIVATE)
        val lastCheckTimestamp = prefs.getLong("last_message_check", 0)
        val now = System.currentTimeMillis() / 1000

        val sinceTimestamp = if (lastCheckTimestamp == 0L || (now - lastCheckTimestamp) > 86400) {
            now - 86400  // Last 24 hours
        } else {
            lastCheckTimestamp
        }

        Log.d(TAG, "Subscribing to messages since: $sinceTimestamp (${(now - sinceTimestamp) / 3600} hours ago)")
        // Subscribe to messages for us
        val filters = listOf(
            mapOf(
                "kinds" to listOf(KIND_ENCRYPTED_DM, KIND_GIFT_WRAP, KIND_TOKEN_TRANSFER),
                "#p" to listOf(publicKey),
                "since" to sinceTimestamp // Fetch historical messages from last check
            ),
            mapOf(
                "kinds" to listOf(KIND_AGENT_LOCATION, KIND_AGENT_PROFILE),
                "#t" to listOf("unicity-agent"),
                "since" to (now - 3600) // Last hour for agent discovery
            )
        )

        val subscriptionId = UUID.randomUUID().toString().substring(0, 8)
        val request = mutableListOf<Any>("REQ", subscriptionId).apply {
            addAll(filters)
        }

        webSocket.send(JsonMapper.toJson(request))

        prefs.edit().putLong("last_message_check", now).apply()
    }

    /**
     * Publish user profile
     */
    private suspend fun publishProfile() {
        val profile = createProfileEvent()
        publishEvent(profile)
    }

    /**
     * Create profile event
     */
    private fun createProfileEvent(): Event {
        val content = mapOf(
            "name" to getUsername(),
            "about" to "Unicity Wallet user",
            "picture" to "",
            "nip05" to getNip05Identifier(),
            "unicity" to mapOf(
                "wallet_address" to getWalletAddress(),
                "agent_tag" to getAgentTag(),
                "services" to getAgentServices()
            )
        )

        return createEvent(
            kind = KIND_PROFILE,
            content = JsonMapper.toJson(content),
            tags = listOf()
        )
    }

    /**
     * Create and sign an event
     */
    private fun createEvent(kind: Int, content: String, tags: List<List<String>>): Event {
        val publicKey = keyManager.getPublicKey()
        val createdAt = System.currentTimeMillis() / 1000

        val event = Event(
            id = "",
            pubkey = publicKey,
            created_at = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = ""
        )

        // Calculate event ID
        val eventId = calculateEventId(event)

        // Sign event
        val signature = signEvent(eventId)

        return event.copy(
            id = eventId,
            sig = signature
        )
    }

    /**
     * Calculate event ID (SHA256 of serialized event)
     */
    private fun calculateEventId(event: Event): String {
        val serialized = listOf(
            0,
            event.pubkey,
            event.created_at,
            event.kind,
            event.tags,
            event.content
        )

        val json = JsonMapper.toJson(serialized)
        val hash = org.spongycastle.crypto.digests.SHA256Digest()
        val bytes = json.toByteArray()
        hash.update(bytes, 0, bytes.size)

        val result = ByteArray(32)
        hash.doFinal(result, 0)

        return String(org.apache.commons.codec.binary.Hex.encodeHex(result))
    }

    /**
     * Sign event with private key using Schnorr signature
     * Nostr uses Schnorr signatures (BIP-340) for events
     */
    private fun signEvent(eventId: String): String {
        val messageBytes = org.apache.commons.codec.binary.Hex.decodeHex(eventId.toCharArray())
        val signature = keyManager.sign(messageBytes)
        return String(org.apache.commons.codec.binary.Hex.encodeHex(signature))
    }

    /**
     * Publish event to relays
     */
    private fun publishEvent(event: Event) {
        val message = listOf("EVENT", event)
        val json = JsonMapper.toJson(message)

        relayConnections.values.forEach { ws ->
            ws.send(json)
        }

        // Queue if no connections
        if (relayConnections.isEmpty()) {
            messageQueue.add(QueuedMessage(event, getAllRelays()))
        }
    }

    // IP2PService implementation

    override fun sendMessage(toTag: String, content: String) {
        scope.launch {
            // Resolve recipient's public key
            val recipientPubkey = resolveTagToPubkey(toTag)

            if (recipientPubkey != null) {
                // Create encrypted message
                val event = createEncryptedMessage(recipientPubkey, content)
                publishEvent(event)

                Log.d(TAG, "Message sent to $toTag")
            } else {
                Log.e(TAG, "Could not resolve tag: $toTag")
            }
        }
    }

    override fun initiateHandshake(agentTag: String) {
        Log.d(TAG, "=== HANDSHAKE DEBUG: initiateHandshake called with agentTag: $agentTag ===")
        // In Nostr, handshakes are implicit through contact lists
        // We can create a contact request event
        scope.launch {
            Log.d(TAG, "=== HANDSHAKE DEBUG: Resolving tag to pubkey for: $agentTag ===")
            val recipientPubkey = resolveTagToPubkey(agentTag)
            Log.d(TAG, "=== HANDSHAKE DEBUG: Resolved pubkey: ${recipientPubkey?.take(20)}... ===")

            if (recipientPubkey != null) {
                val content = mapOf(
                    "type" to "handshake_request",
                    "from" to getAgentTag(),
                    "timestamp" to System.currentTimeMillis()
                )
                Log.d(TAG, "=== HANDSHAKE DEBUG: Creating encrypted message with content: $content ===")

                val event = createEncryptedMessage(recipientPubkey, JsonMapper.toJson(content))
                Log.d(TAG, "=== HANDSHAKE DEBUG: Created event with id: ${event.id}, publishing... ===")

                publishEvent(event)

                Log.d(TAG, "=== HANDSHAKE DEBUG: Handshake initiated with $agentTag ===")
            } else {
                Log.e(TAG, "=== HANDSHAKE DEBUG: Failed to resolve pubkey for $agentTag ===")
            }
        }
    }

    override fun acceptHandshake(fromTag: String) {
        // Accept contact request
        updateConnectionStatus(fromTag, true)
        Log.d(TAG, "Handshake accepted from $fromTag")
    }

    override fun rejectHandshake(fromTag: String) {
        // Reject contact request
        updateConnectionStatus(fromTag, false)
        Log.d(TAG, "Handshake rejected from $fromTag")
    }

    // Helper methods

    private fun getAllRelays(): List<String> {
        // Use ONLY our private AWS relay to ensure it's working
        return UNICITY_RELAYS
    }

    private fun updateConnectionStatus(identifier: String, isConnected: Boolean) {
        _connectionStatus.value = _connectionStatus.value.toMutableMap().apply {
            this[identifier] = IP2PService.ConnectionStatus(
                isConnected = isConnected,
                isAvailable = isConnected,
                lastSeen = System.currentTimeMillis()
            )
        }
    }

    private fun flushMessageQueue(relayUrl: String) {
        val ws = relayConnections[relayUrl] ?: return

        messageQueue.toList().forEach { queued ->
            if (relayUrl in queued.relays) {
                val message = listOf("EVENT", queued.event)
                ws.send(JsonMapper.toJson(message))
            }
        }
    }

    // Placeholder methods - to be implemented

    private fun isAgentMode(): Boolean {
        val prefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_agent", false)
    }

    private fun startAgentDiscovery() {
        // TODO: Implement agent discovery
    }

    private fun getUsername(): String {
        val prefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        return prefs.getString("username", "Anonymous") ?: "Anonymous"
    }

    private fun getNip05Identifier(): String {
        return "${getUsername()}@unicity.network"
    }

    private fun getWalletAddress(): String {
        val prefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        return prefs.getString("wallet_address", "") ?: ""
    }

    private fun getAgentTag(): String {
        val prefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        return prefs.getString("unicity_tag", "") ?: ""
    }

    private fun getAgentServices(): List<String> {
        return listOf("cash", "crypto", "transfer")
    }

    private suspend fun resolveTagToPubkey(tag: String): String? {
        Log.d(TAG, "=== RESOLVE DEBUG: Resolving tag '$tag' to pubkey ===")

        // First try to query the nametag binding from Nostr relay
        try {
            val pubkey = queryPubkeyByNametag(tag)
            if (pubkey != null) {
                Log.d(TAG, "=== RESOLVE DEBUG: Found pubkey from Nostr binding: ${pubkey.take(20)}... ===")
                return pubkey
            }
        } catch (e: Exception) {
            Log.w(TAG, "=== RESOLVE DEBUG: Failed to query Nostr binding for $tag: ${e.message} ===")
        }

        // Fallback to deterministic generation for backwards compatibility
        // This ensures both devices can derive the same pubkey for a given tag
        return try {
            val tagBytes = tag.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(tagBytes)
            val pubkey = String(org.apache.commons.codec.binary.Hex.encodeHex(hash))
            Log.d(TAG, "=== RESOLVE DEBUG: Generated fallback pubkey for $tag: ${pubkey.take(20)}... ===")
            pubkey
        } catch (e: Exception) {
            Log.e(TAG, "=== RESOLVE DEBUG: Failed to generate pubkey for $tag: ${e.message} ===")
            null
        }
    }

    private fun createEncryptedMessage(recipientPubkey: String, content: String): Event {
        // Implement NIP-04 encryption with auto-compression (SDK)
        val recipientPubkeyBytes = org.apache.commons.codec.binary.Hex.decodeHex(recipientPubkey.toCharArray())
        val encryptedContent = keyManager.encryptMessage(content, recipientPubkeyBytes)

        return createEvent(
            kind = KIND_ENCRYPTED_DM,
            content = encryptedContent,
            tags = listOf(listOf("p", recipientPubkey))
        )
    }

    private fun handleEncryptedMessage(event: Event) {
        // Decrypt NIP-04 message with auto-decompression (SDK)
        try {
            val senderPubkeyBytes = org.apache.commons.codec.binary.Hex.decodeHex(event.pubkey.toCharArray())
            val decryptedContent = keyManager.decryptMessage(event.content, senderPubkeyBytes)

            Log.d(TAG, "Received encrypted message from ${event.pubkey}: $decryptedContent")

            // TODO: Handle the decrypted message (e.g., store in database, notify UI)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt message from ${event.pubkey}", e)
        }
    }

    private fun handleGiftWrappedMessage(event: Event) {
        // TODO: Unwrap and handle message
        Log.d(TAG, "Received gift-wrapped message")
    }

    private fun handleAgentLocation(event: Event) {
        try {
            // Parse location from content (format: "lat,lon,timestamp,tag")
            val parts = event.content.split(",")
            if (parts.size >= 3) {
                val latitude = parts[0].toDoubleOrNull()
                val longitude = parts[1].toDoubleOrNull()
                val timestamp = parts[2].toLongOrNull()
                val agentTag = if (parts.size > 3) parts[3] else event.pubkey

                if (latitude != null && longitude != null && timestamp != null) {
                    Log.d(TAG, "Received location from $agentTag: ($latitude, $longitude) at $timestamp")

                    // Update connection status with location info
                    _connectionStatus.update { current ->
                        current + (agentTag to IP2PService.ConnectionStatus(
                            isConnected = true,
                            isAvailable = true,
                            lastSeen = timestamp
                        ))
                    }

                    // TODO: Notify UI about new agent location
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing agent location", e)
        }
    }

    private fun handleTokenTransfer(event: Event) {
        scope.launch {
            try {
                Log.d(TAG, "Processing token transfer from ${event.pubkey}")

                // Decrypt the message content
                // SDK handles both NIP-04 (new) and hex (legacy) automatically
                val decryptedContent = try {
                    // Try NIP-04 decryption first (SDK handles auto-decompression)
                    try {
                        val senderPubkeyBytes = org.apache.commons.codec.binary.Hex.decodeHex(event.pubkey.toCharArray())
                        keyManager.decryptMessage(event.content, senderPubkeyBytes)
                    } catch (nip04Error: Exception) {
                        // Fall back to simple hex decoding (legacy format)
                        try {
                            String(org.apache.commons.codec.binary.Hex.decodeHex(event.content.toCharArray()), Charsets.UTF_8)
                        } catch (hexError: Exception) {
                            throw Exception("Failed both NIP-04 and hex decryption", nip04Error)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt token transfer", e)
                    return@launch
                }

                // Check if it's a token_transfer message
                if (!decryptedContent.startsWith("token_transfer:")) {
                    Log.w(TAG, "Not a token_transfer message")
                    return@launch
                }

                // Extract payload
                val payload = decryptedContent.substring("token_transfer:".length)
                Log.d(TAG, "Received token transfer payload (${payload.length} chars)")

                // Parse payload
                val payloadObj = try {
                    JsonMapper.fromJson(payload, Map::class.java) as Map<*, *>
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse payload", e)
                    return@launch
                }

                // Check if this is proper transfer format with sourceToken and transferTx
                if (payloadObj.containsKey("sourceToken") && payloadObj.containsKey("transferTx")) {
                    // PROPER FORMAT: Transfer with finalization
                    handleProperTokenTransfer(payloadObj)
                } else {
                    Log.e(TAG, "Invalid token transfer format - missing sourceToken or transferTx")
                    Log.e(TAG, "Payload keys: ${payloadObj.keys}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error handling token transfer", e)
            }
        }
    }

    private fun showTokenReceivedNotification(amount: String?, symbol: String) {
        // TODO: Implement notification
        Log.i(TAG, "📬 New token received: $amount $symbol")
    }

    /**
     * Handle proper token transfer with source token and transfer transaction
     * Requires finalization with nametag for proxy address resolution
     */
    private fun handleProperTokenTransfer(payloadObj: Map<*, *>) {
        scope.launch {
            try {
                Log.d(TAG, "Processing proper token transfer with finalization...")
                Log.d(TAG, "Payload keys: ${payloadObj.keys}")

                // Extract source token and transfer transaction JSON strings
                val sourceTokenJson = payloadObj["sourceToken"] as? String
                val transferTxJson = payloadObj["transferTx"] as? String

                Log.d(TAG, "sourceTokenJson length: ${sourceTokenJson?.length}")
                Log.d(TAG, "transferTxJson length: ${transferTxJson?.length}")

                if (sourceTokenJson == null || transferTxJson == null) {
                    Log.e(TAG, "Missing source token or transfer transaction in payload")
                    Log.e(TAG, "Payload structure: ${payloadObj}")
                    return@launch
                }

                Log.d(TAG, "Parsing source token...")
                // Parse source token using UnicityObjectMapper
                val sourceToken = try {
                    UnicityObjectMapper.JSON.readValue(sourceTokenJson, org.unicitylabs.sdk.token.Token::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse source token", e)
                    Log.e(TAG, "Token JSON preview: ${sourceTokenJson.take(500)}")
                    e.printStackTrace()
                    return@launch
                }

                Log.d(TAG, "Parsing transfer transaction...")
                // Parse transfer transaction with proper type reference
                val transferTx = try {
                    val typeRef = object : com.fasterxml.jackson.core.type.TypeReference<org.unicitylabs.sdk.transaction.Transaction<org.unicitylabs.sdk.transaction.TransferTransactionData>>() {}
                    UnicityObjectMapper.JSON.readValue(transferTxJson, typeRef)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse transfer transaction", e)
                    Log.e(TAG, "Tx JSON preview: ${transferTxJson.take(500)}")
                    e.printStackTrace()
                    return@launch
                }

                Log.d(TAG, "✅ Parsed successfully!")
                Log.d(TAG, "Source token type: ${sourceToken.type}")
                Log.d(TAG, "Transfer recipient: ${transferTx.data.recipient}")

                // Finalize the transfer
                Log.d(TAG, "Starting finalization...")
                val finalizedToken = finalizeTransfer(sourceToken, transferTx)

                if (finalizedToken != null) {
                    Log.d(TAG, "✅ Finalization successful, saving token...")
                    // Save the finalized token
                    saveReceivedToken(finalizedToken)
                    Log.i(TAG, "✅ Token transfer completed and finalized successfully")
                } else {
                    Log.e(TAG, "❌ Failed to finalize token transfer - finalizeTransfer returned null")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error handling proper token transfer", e)
                e.printStackTrace()
            }
        }
    }

    /**
     * Handle receiving a demo crypto transfer (BTC, ETH, etc.)
     * Updates the receiver's cryptocurrency balance
     */
    private fun handleDemoCryptoTransfer(transferData: Map<*, *>) {
        try {
            val cryptoId = transferData["crypto_id"] as? String ?: return
            val cryptoSymbol = transferData["crypto_symbol"] as? String ?: return
            val cryptoName = transferData["crypto_name"] as? String ?: return
            val amount = (transferData["amount"] as? Number)?.toDouble() ?: return
            val isDemo = transferData["is_demo"] as? Boolean ?: true

            Log.d(TAG, "Received demo crypto transfer: $amount $cryptoSymbol (isDemo: $isDemo)")

            // Broadcast intent to MainActivity to update the crypto balance
            val intent = android.content.Intent("org.unicitylabs.wallet.ACTION_CRYPTO_RECEIVED")
            intent.putExtra("crypto_id", cryptoId)
            intent.putExtra("crypto_symbol", cryptoSymbol)
            intent.putExtra("crypto_name", cryptoName)
            intent.putExtra("amount", amount)
            intent.putExtra("is_demo", isDemo)

            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context)
                .sendBroadcast(intent)

            Log.i(TAG, "✅ Demo crypto received: $amount $cryptoSymbol")

            // Show notification
            showCryptoReceivedNotification(amount, cryptoSymbol)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling demo crypto transfer", e)
        }
    }

    private fun showCryptoReceivedNotification(amount: Double, symbol: String) {
        // TODO: Implement notification
        Log.i(TAG, "📬 New crypto received: $amount $symbol")
    }

    /**
     * Finalize a token transfer by resolving proxy address and creating proper ownership
     * This is the critical step that gives the recipient actual ownership of the token
     */
    private suspend fun finalizeTransfer(
        sourceToken: org.unicitylabs.sdk.token.Token<*>,
        transferTx: org.unicitylabs.sdk.transaction.Transaction<org.unicitylabs.sdk.transaction.TransferTransactionData>
    ): org.unicitylabs.sdk.token.Token<*>? {
        try {
            // Get recipient address from transfer
            val recipientAddress = transferTx.data.recipient
            Log.d(TAG, "Recipient address: ${recipientAddress.address}")
            Log.d(TAG, "Address scheme: ${recipientAddress.scheme}")

            // Check if this is a proxy address
            if (recipientAddress.scheme != org.unicitylabs.sdk.address.AddressScheme.PROXY) {
                Log.d(TAG, "Transfer is not to proxy address, no finalization needed")
                // For direct transfers, the token can be used as-is
                return sourceToken
            }

            Log.d(TAG, "Transfer is to PROXY address - finalization required")

            // Load ALL user's nametag tokens and find which one matches
            val nametagService = NametagService(context)
            val allNametags = nametagService.getAllNametagTokens()

            if (allNametags.isEmpty()) {
                Log.e(TAG, "No nametags configured for this wallet")
                return null
            }

            // Find which nametag this transfer is for by checking all proxy addresses
            var matchedNametag: String? = null
            var myNametagToken: org.unicitylabs.sdk.token.Token<*>? = null

            for ((nametagString, nametagToken) in allNametags) {
                val proxyAddress = ProxyAddress.create(nametagToken.id)
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
                return null
            }

            Log.d(TAG, "✅ Transfer is for my nametag: $matchedNametag")

            // Get identity and create signing service
            val identityManager = IdentityManager(context)
            val identity = identityManager.getCurrentIdentity()
            if (identity == null) {
                Log.e(TAG, "No wallet identity found")
                return null
            }

            val secret = hexToBytes(identity.privateKey)
            val signingService = SigningService.createFromSecret(secret)

            // Create recipient predicate
            val transferSalt = transferTx.data.salt

            Log.d(TAG, "Creating recipient predicate:")
            Log.d(TAG, "  Identity pubkey: ${identity.publicKey}")
            Log.d(TAG, "  Source TokenId: ${bytesToHex(sourceToken.id.bytes).take(16)}...")
            Log.d(TAG, "  TokenType: ${sourceToken.type}")
            Log.d(TAG, "  Transfer Salt: ${bytesToHex(transferSalt).take(16)}...")

            val recipientPredicate = UnmaskedPredicate.create(
                sourceToken.id,
                sourceToken.type,
                signingService,
                org.unicitylabs.sdk.hash.HashAlgorithm.SHA256,
                transferSalt
            )

            Log.d(TAG, "✅ Predicate created - PublicKey: ${bytesToHex(recipientPredicate.publicKey).take(32)}...")

            val recipientState = TokenState(recipientPredicate, null)

            Log.d(TAG, "Finalizing transfer with nametag token...")
            Log.d(TAG, "  Transfer Recipient: ${transferTx.data.recipient.address}")
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
                        listOf(myNametagToken)  // Include nametag for proxy resolution
                    )
                }
            } catch (ve: org.unicitylabs.sdk.verification.VerificationException) {
                // CRITICAL: Log verification details to debug the issue
                Log.e(TAG, "❌❌❌ VERIFICATION FAILED ❌❌❌")
                Log.e(TAG, "Verification Result: ${ve.verificationResult}")
                Log.e(TAG, "Full exception:", ve)

                // CRITICAL: Save failed transfer for recovery
                saveFailedTransfer(sourceToken, transferTx, matchedNametag, ve.verificationResult.toString())

                throw ve // Re-throw after logging
            }

            Log.i(TAG, "✅ Token finalized successfully!")
            return finalizedToken

        } catch (ve: org.unicitylabs.sdk.verification.VerificationException) {
            Log.e(TAG, "❌ CRITICAL: Token verification failed during finalization")
            Log.e(TAG, "VerificationResult: ${ve.verificationResult}")
            Log.e(TAG, "This transfer has been saved for manual recovery")
            ve.printStackTrace()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error finalizing transfer", e)
            e.printStackTrace()
            return null
        }
    }

    /**
     * CRITICAL: Save failed transfer for manual recovery
     * Never lose tokens due to verification errors
     */
    private suspend fun saveFailedTransfer(
        sourceToken: org.unicitylabs.sdk.token.Token<*>,
        transferTx: org.unicitylabs.sdk.transaction.Transaction<org.unicitylabs.sdk.transaction.TransferTransactionData>,
        nametag: String,
        verificationError: String
    ) {
        try {
            val failedTransferDir = java.io.File(context.filesDir, "failed_transfers")
            failedTransferDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val failedTransferFile = java.io.File(failedTransferDir, "failed_$timestamp.json")

            val failedTransferData = mapOf(
                "timestamp" to timestamp,
                "nametag" to nametag,
                "verificationError" to verificationError,
                "sourceToken" to UnicityObjectMapper.JSON.writeValueAsString(sourceToken),
                "transferTx" to UnicityObjectMapper.JSON.writeValueAsString(transferTx)
            )

            failedTransferFile.writeText(JsonMapper.toJson(failedTransferData))

            Log.e(TAG, "❌❌❌ FAILED TRANSFER SAVED: ${failedTransferFile.absolutePath}")
            Log.e(TAG, "Token is NOT lost - can be manually recovered from this file")

        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to save failed transfer - TOKEN MAY BE LOST!", e)
        }
    }

    /**
     * Save a received and finalized token to the wallet
     */
    private suspend fun saveReceivedToken(token: org.unicitylabs.sdk.token.Token<*>) {
        try {
            // Serialize token
            val tokenJson = UnicityObjectMapper.JSON.writeValueAsString(token)

            // Extract metadata from token for wallet display
            val registry = UnicityTokenRegistry.getInstance(context)
            var amount: String? = null // BigInteger as String (arbitrary precision)
            var coinIdHex: String? = null
            var symbol: String? = null
            var iconUrl: String? = null

            // Parse genesis to extract coin data
            val tokenJsonObj = JsonMapper.fromJson(tokenJson, Map::class.java) as Map<*, *>
            Log.d(TAG, "Token JSON keys: ${tokenJsonObj.keys}")

            val genesis = tokenJsonObj["genesis"] as? Map<*, *>
            Log.d(TAG, "Genesis keys: ${genesis?.keys}")

            if (genesis != null) {
                val genesisData = genesis["data"] as? Map<*, *>
                Log.d(TAG, "Genesis data keys: ${genesisData?.keys}")

                if (genesisData != null) {
                    // Coins are directly in genesis.data.coins as an array: [["coinId", "amount"]]
                    val coinsArray = genesisData["coins"] as? List<*>
                    Log.d(TAG, "Coins array: $coinsArray")

                    if (coinsArray != null && coinsArray.isNotEmpty()) {
                        // Each entry is [coinId, amount]
                        val firstCoin = coinsArray[0] as? List<*>
                        if (firstCoin != null && firstCoin.size >= 2) {
                            coinIdHex = firstCoin[0] as? String
                            // Store amount as String to support BigInteger (arbitrary precision)
                            amount = when (val amountValue = firstCoin[1]) {
                                is java.math.BigInteger -> amountValue.toString()
                                is java.math.BigDecimal -> amountValue.toBigInteger().toString()
                                is String -> amountValue
                                is Number -> amountValue.toString()
                                else -> {
                                    Log.e(TAG, "Unknown amount type: ${amountValue?.javaClass}")
                                    null
                                }
                            }

                            Log.d(TAG, "Extracted: coinId=$coinIdHex, amount=$amount")

                            if (coinIdHex != null) {
                                val coinDef = registry.getCoinDefinition(coinIdHex)
                                if (coinDef != null) {
                                    symbol = coinDef.symbol
                                    iconUrl = coinDef.getIconUrl()
                                    Log.d(TAG, "Found coin definition: ${coinDef.name} ($symbol)")
                                } else {
                                    Log.w(TAG, "Coin definition not found in registry for: $coinIdHex")
                                }
                            }
                        } else {
                            Log.w(TAG, "Invalid coin entry format: $firstCoin")
                        }
                    } else {
                        Log.w(TAG, "No coins found in genesis data")
                    }
                }
            }

            Log.d(TAG, "Final metadata: symbol=$symbol, amount=$amount, coinId=$coinIdHex")

            // Create wallet Token model
            val walletToken = Token(
                name = symbol ?: "Token",
                type = token.type.toString(),
                jsonData = tokenJson,
                sizeBytes = tokenJson.length,
                status = TokenStatus.CONFIRMED,
                amount = amount, // Store as string to support BigInteger
                coinId = coinIdHex,
                symbol = symbol,
                iconUrl = iconUrl
            )

            // Save to wallet repository
            val walletRepository = WalletRepository.getInstance(context)
            walletRepository.addToken(walletToken)

            Log.i(TAG, "✅ Finalized token saved: $amount $symbol")

            // Show notification
            showTokenReceivedNotification(amount, symbol ?: "tokens")

        } catch (e: Exception) {
            Log.e(TAG, "Error saving received token", e)
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

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun handleFileMetadata(event: Event) {
        // TODO: Handle file metadata
        Log.d(TAG, "Received file metadata from ${event.pubkey}")
    }

    /**
     * Send a token transfer to a recipient identified by their @unicity nametag
     * This creates an encrypted Nostr event containing the token JSON
     *
     * @param recipientNametag The recipient's @unicity nametag (e.g., "alice@unicity")
     * @param tokenJson The Unicity SDK token JSON to transfer
     * @param amount Optional amount for display purposes
     * @param symbol Optional symbol for display purposes
     * @return Result indicating success or failure
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

            // Create encrypted token transfer content
            // Format: "token_transfer:<token_json>"
            val content = "token_transfer:$tokenJson"

            // Encrypt the content for the recipient (SDK handles auto-compression)
            val recipientPubkeyBytes = org.apache.commons.codec.binary.Hex.decodeHex(recipientPubkey.toCharArray())
            val encryptedContent = keyManager.encryptMessage(content, recipientPubkeyBytes)

            // Create token transfer event
            val event = createEvent(
                kind = KIND_TOKEN_TRANSFER,
                content = encryptedContent,
                tags = listOf(
                    listOf("p", recipientPubkey), // Recipient pubkey
                    listOf("nametag", recipientNametag), // Recipient nametag for easier lookup
                    listOf("type", "token_transfer") // Event type
                ).let { tags ->
                    // Add optional amount/symbol tags if provided
                    if (amount != null && symbol != null) {
                        tags + listOf(
                            listOf("amount", amount.toString()),
                            listOf("symbol", symbol)
                        )
                    } else {
                        tags
                    }
                }
            )

            // Publish to relays
            publishEvent(event)

            Log.i(TAG, "✅ Token transfer sent successfully to $recipientNametag (event ID: ${event.id})")
            Result.success(event.id)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send token transfer to $recipientNametag", e)
            Result.failure(e)
        }
    }

    override fun broadcastLocation(latitude: Double, longitude: Double) {
        if (!isRunning) {
            Log.w(TAG, "Cannot broadcast location - service not running")
            return
        }

        scope.launch {
            try {
                // Get user info
                val sharedPrefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
                val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""

                // Create location content (format: "lat,lon,timestamp,tag")
                val timestamp = System.currentTimeMillis()
                val content = "$latitude,$longitude,$timestamp,$unicityTag"

                // Create location event
                val event = createEvent(KIND_AGENT_LOCATION, content, emptyList())

                // Broadcast to all connected relays
                publishEvent(event)

                Log.d(TAG, "Broadcasting location: ($latitude, $longitude)")
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting location", e)
            }
        }
    }

    override fun updateAvailability(isAvailable: Boolean) {
        // Store availability status
        val sharedPrefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("agent_available", isAvailable).apply()

        // Optionally broadcast availability status to network
        if (isRunning) {
            scope.launch {
                try {
                    val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""
                    val content = if (isAvailable) "$unicityTag:available" else "$unicityTag:unavailable"
                    val event = createEvent(KIND_AGENT_PROFILE, content, emptyList())
                    publishEvent(event)
                    Log.d(TAG, "Updated availability: $isAvailable")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating availability", e)
                }
            }
        }
    }

    /**
     * Publish a nametag binding to the Nostr relay
     * This creates a replaceable event that maps the user's Nostr pubkey to their Unicity nametag
     */
    suspend fun publishNametagBinding(nametagId: String, unicityAddress: String): Boolean {
        return try {
            Log.d(TAG, "Publishing nametag binding for: $nametagId")

            // Create binding event using SDK
            val sdkEvent = NametagBinding.createBindingEvent(
                keyManager.getSdkKeyManager(),
                nametagId,
                unicityAddress
            )

            // Convert SDK event to wallet Event format
            val bindingEvent = Event(
                id = sdkEvent.id,
                pubkey = sdkEvent.pubkey,
                created_at = sdkEvent.createdAt,
                kind = sdkEvent.kind,
                tags = sdkEvent.tags,
                content = sdkEvent.content,
                sig = sdkEvent.sig
            )

            // Publish the event to all connected relays
            publishEvent(bindingEvent)

            Log.d(TAG, "Nametag binding published successfully: $nametagId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish nametag binding", e)
            false
        }
    }

    /**
     * Query Nostr pubkey by nametag
     *
     * Purpose: Find which Nostr pubkey to send the encrypted message to
     * Note: Proxy address resolution (nametag → Unicity address) is done locally
     *       via TokenId.fromNameTag() - no Nostr lookup needed for that!
     *
     * @return Nostr public key (hex) if binding exists, null otherwise
     */
    suspend fun queryPubkeyByNametag(nametagId: String): String? {
        return try {
            // Create filter using SDK
            val sdkFilter = NametagBinding.createNametagToPubkeyFilter(nametagId)

            // Convert SDK filter to map for WebSocket
            val filter = mapOf(
                "kinds" to sdkFilter.kinds,
                "#t" to sdkFilter.tTags,
                "limit" to sdkFilter.limit
            )

            Log.d(TAG, "Querying pubkey for nametag: $nametagId")
            Log.d(TAG, "Filter: $filter")

            // Subscribe and wait for result
            val subscriptionId = "query-pubkey-${System.currentTimeMillis()}"
            var result: String? = null
            val receivedEvent = kotlinx.coroutines.CompletableDeferred<Event?>()

            // Add temporary listener for this query
            val listener: (Event) -> Unit = { event ->
                if (event.kind == EventKinds.APP_DATA) {
                    // Relay filter already ensures this is for our nametag
                    receivedEvent.complete(event)
                }
            }

            eventListeners.add(listener)

            try {
                // Send REQ message to all connected relays
                val reqMessage = listOf("REQ", subscriptionId, filter)
                val json = JsonMapper.toJson(reqMessage)
                relayConnections.values.forEach { ws ->
                    ws.send(json)
                }

                // Wait for response with timeout
                kotlinx.coroutines.withTimeout(5000) {
                    val event = receivedEvent.await()
                    result = event?.pubkey
                }

                if (result != null) {
                    Log.d(TAG, "Found pubkey: ${result!!.take(16)}...")
                } else {
                    Log.d(TAG, "No pubkey found for nametag")
                }

                result
            } finally {
                // Clean up
                eventListeners.remove(listener)
                // Send CLOSE message
                val closeMessage = listOf("CLOSE", subscriptionId)
                val closeJson = JsonMapper.toJson(closeMessage)
                relayConnections.values.forEach { ws ->
                    ws.send(closeJson)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query pubkey by nametag", e)
            null
        }
    }

    /**
     * Send a token transfer message to a Nostr pubkey
     * Uses simple hex encoding (compatible with faucet) and KIND_TOKEN_TRANSFER
     */
    fun sendDirectMessage(recipientPubkey: String, message: String): Boolean {
        return try {
            // Use simple hex encoding (compatible with faucet and wallet receiver)
            val hexEncodedContent = message.toByteArray().joinToString("") {
                "%02x".format(it)
            }

            // Create token transfer event (kind 31113, same as faucet)
            val event = createEvent(
                kind = KIND_TOKEN_TRANSFER,  // Use token transfer kind (31113)
                content = hexEncodedContent,  // Hex-encoded content
                tags = listOf(
                    listOf("p", recipientPubkey)  // Recipient pubkey tag
                )
            )

            // Publish the event
            publishEvent(event)

            Log.d(TAG, "Token transfer message sent to $recipientPubkey (kind $KIND_TOKEN_TRANSFER)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send token transfer message", e)
            false
        }
    }
}