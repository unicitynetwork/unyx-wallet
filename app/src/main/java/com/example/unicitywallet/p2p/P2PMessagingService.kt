package com.example.unicitywallet.p2p

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.unicitywallet.data.chat.ChatConversation
import com.example.unicitywallet.data.chat.ChatDatabase
import com.example.unicitywallet.data.chat.ChatMessage
import com.example.unicitywallet.data.chat.MessageStatus
import com.example.unicitywallet.data.chat.MessageType
import com.example.unicitywallet.utils.JsonMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.collections.set

class P2PMessagingService private constructor(
    private val context: Context,
    private val userTag: String,
    private val userPublicKey: String
) : IP2PService {
    companion object {
        private const val TAG = "P2PMessagingService"
        private const val SERVICE_TYPE = "_unicity-chat._tcp"
        private const val SERVICE_NAME_PREFIX = "unicity-"
        private const val WS_PORT_MIN = 9000
        private const val WS_PORT_MAX = 9999
        private const val NOTIFICATION_CHANNEL_ID = "unicity_chat"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_HANDSHAKE_REQUEST = "org.unicitylabs.nfcwalletdemo.ACTION_HANDSHAKE_REQUEST"
        const val EXTRA_FROM_TAG = "from_tag"
        const val EXTRA_FROM_NAME = "from_name"

        @Volatile
        private var INSTANCE: P2PMessagingService? = null

        fun getInstance(
            context: Context,
            userTag: String,
            userPublicKey: String
        ): P2PMessagingService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: P2PMessagingService(context, userTag, userPublicKey).also {
                    INSTANCE = it
                }
            }
        }

        fun getExistingInstance(): P2PMessagingService? = INSTANCE
    }

    // Using shared JsonMapper.mapper
    private val chatDatabase = ChatDatabase.getDatabase(context)
    private val messageDao = chatDatabase.messageDao()
    private val conversationDao = chatDatabase.conversationDao()

    private var webSocketServer: WebSocketServer? = null
    private val activeConnections = ConcurrentHashMap<String, WebSocketClient>()
    private val serverConnections = ConcurrentHashMap<String, WebSocket>() // Incoming connections
    private val pendingMessages = ConcurrentHashMap<String, MutableList<P2PMessage>>()

    // Track NSD listeners for proper cleanup
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null

    private val _connectionStatus = MutableStateFlow<Map<String, IP2PService.ConnectionStatus>>(emptyMap())
    override val connectionStatus: StateFlow<Map<String, IP2PService.ConnectionStatus>> = _connectionStatus

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class P2PMessage(
        val messageId: String = UUID.randomUUID().toString(),
        val from: String,
        val to: String,
        val type: MessageType,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val signature: String? = null
    )

    init {
        Log.d(TAG, "P2PMessagingService initializing for user: $userTag")

        // Create notification channel synchronously (lightweight operation)
        createNotificationChannel()

        // Check if user is an agent
        val sharedPrefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val isAgent = sharedPrefs.getBoolean("is_agent", false)
        Log.d(TAG, "User is agent: $isAgent")

        // Initialize service components asynchronously to avoid blocking main thread
        scope.launch {
            try {
                // Everyone needs a WebSocket server to receive messages
                startWebSocketServer()
                delay(500) // Give WebSocket server time to start

                // Both agents and non-agents need to discover others
                try {
                    startNsdDiscovery()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start NSD discovery", e)
                }

                processPendingMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing P2P service", e)
                // Service will still be created but might not be fully functional
            }
        }
    }

    private fun startWebSocketServer() {
        val port = findAvailablePort()
        Log.d(TAG, "Starting WebSocket server on port $port")
        // Bind to all interfaces (0.0.0.0) instead of localhost
        val address = InetSocketAddress("0.0.0.0", port)

        webSocketServer = object : WebSocketServer(address) {
            override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
                Log.d(TAG, "WebSocket server connection opened from: ${conn?.remoteSocketAddress}")
                // Wait for identification message to know who connected
            }

            override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket connection closed: $reason")
                // Remove from server connections
                serverConnections.values.remove(conn)
            }

            override fun onMessage(conn: WebSocket?, message: String?) {
                Log.d(TAG, "WebSocket server received message: $message")
                message?.let {
                    try {
                        val p2pMessage = JsonMapper.fromJson(it, P2PMessage::class.java)
                        // Store the connection mapping when we receive the first message
                        if (!serverConnections.containsKey(p2pMessage.from)) {
                            conn?.let { ws ->
                                serverConnections[p2pMessage.from] = ws
                                Log.d(TAG, "Mapped incoming connection from ${p2pMessage.from}")
                            }
                        }
                        handleIncomingMessage(it)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse incoming message", e)
                    }
                }
            }

            override fun onError(conn: WebSocket?, ex: Exception?) {
                Log.e(TAG, "WebSocket error", ex)
            }

            override fun onStart() {
                Log.d(TAG, "WebSocket server started on port $port")
                // Verify server is actually listening
                scope.launch {
                    delay(100) // Small delay to ensure server is ready
                    try {
                        val testSocket = java.net.Socket()
                        testSocket.connect(InetSocketAddress("127.0.0.1", port), 1000)
                        testSocket.close()
                        Log.d(TAG, "WebSocket server verified listening on port $port")
                    } catch (e: Exception) {
                        Log.e(TAG, "WebSocket server NOT listening on port $port", e)
                    }
                }

                // Only register NSD service for agents (to be discoverable)
                val sharedPrefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
                val isAgent = sharedPrefs.getBoolean("is_agent", false)
                if (isAgent) {
                    Log.d(TAG, "Agent mode - registering NSD service")
                    registerNsdService(port)
                } else {
                    Log.d(TAG, "Non-agent mode - NOT registering NSD service (not discoverable)")
                }
            }
        }

        try {
            webSocketServer?.isReuseAddr = true
            webSocketServer?.start()
            Log.d(TAG, "WebSocket server start() called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket server", e)
            // Don't throw - just log the error to prevent crashes
        }
    }

    private fun findAvailablePort(): Int {
        // Try a smaller range first to avoid blocking
        val startPort = WS_PORT_MIN + (System.currentTimeMillis() % 100).toInt()
        for (i in 0..20) {
            val port = startPort + i
            if (port > WS_PORT_MAX) break
            try {
                val socket = java.net.ServerSocket(port)
                socket.close()
                return port
            } catch (e: Exception) {
                // Port is in use, try next
            }
        }

        Log.e(TAG, "Could not find available port in range $startPort-${startPort + 20}")
        // Return a default port and let the server handle the error
        return WS_PORT_MIN
    }

    private fun registerNsdService(port: Int) {
        // Unregister any existing service first
        try {
            registrationListener?.let {
                Log.d(TAG, "Unregistering existing NSD service before new registration")
                nsdManager.unregisterService(it)
                registrationListener = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering existing service", e)
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME_PREFIX$userTag"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("tag", userTag)
            setAttribute("pubkey", userPublicKey)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${serviceInfo.serviceName} on port $port")
                // Log network interfaces for debugging
                try {
                    val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val networkInterface = interfaces.nextElement()
                        if (networkInterface.isUp && !networkInterface.isLoopback) {
                            val addresses = networkInterface.inetAddresses
                            while (addresses.hasMoreElements()) {
                                val address = addresses.nextElement()
                                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                                    Log.d(TAG, "Network interface ${networkInterface.name}: ${address.hostAddress}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error listing network interfaces", e)
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed: $errorCode")
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun startNsdDiscovery() {
        // Acquire multicast lock for NSD to work properly
        acquireMulticastLock()

        // Stop any existing discovery first
        try {
            discoveryListener?.let {
                Log.d(TAG, "Stopping existing NSD discovery before starting new one")
                nsdManager.stopServiceDiscovery(it)
                discoveryListener = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping existing discovery", e)
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                // Check if it's a Unicity service and not our own
                if (serviceInfo.serviceName.startsWith(SERVICE_NAME_PREFIX)) {
                    val foundServiceName = serviceInfo.serviceName
                    val ownServiceName = "$SERVICE_NAME_PREFIX$userTag"

                    if (foundServiceName != ownServiceName) {
                        Log.d(TAG, "Resolving service: ${serviceInfo.serviceName}")
                        resolveService(serviceInfo)
                    } else {
                        Log.d(TAG, "Ignoring own service: ${serviceInfo.serviceName}")
                    }
                } else {
                    Log.d(TAG, "Ignoring non-Unicity service: ${serviceInfo.serviceName}")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                val agentTag = serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                updateConnectionStatus(agentTag, false)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${serviceInfo.serviceName}")
                val agentTag = serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                val host = serviceInfo.host
                val port = serviceInfo.port
                Log.d(TAG, "Agent: $agentTag, Host: $host, Port: $port")

                // Log all available addresses for debugging
                try {
                    val addresses = host.hostAddress
                    Log.d(TAG, "Host addresses: $addresses")
                    val hostname = host.hostName
                    Log.d(TAG, "Host name: $hostname")
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting host info", e)
                }

                // Update connection status to show peer is available
                updateConnectionStatus(agentTag, false, true)

                val hostAddress = host.hostAddress
                if (hostAddress != null) {
                    Log.d(TAG, "Discovered peer at $hostAddress:$port")
                    Log.d(TAG, "My user tag: $userTag, peer tag: $agentTag")

                    // Only connect if we don't already have a connection (either direction)
                    if (!activeConnections.containsKey(agentTag) && !serverConnections.containsKey(agentTag)) {
                        Log.d(TAG, "Attempting to connect to peer at $hostAddress:$port")
                        connectToPeer(agentTag, hostAddress, port)
                    } else {
                        Log.d(TAG, "Already have connection with $agentTag, skipping new connection")
                        Log.d(TAG, "Active connections: ${activeConnections.keys}")
                        Log.d(TAG, "Server connections: ${serverConnections.keys}")
                    }
                } else {
                    Log.e(TAG, "No host address for resolved service")
                }
            }
        }

        nsdManager.resolveService(serviceInfo, resolveListener)
    }

    private fun connectToPeer(agentTag: String, host: String, port: Int, retryCount: Int = 0) {
        Log.d(TAG, "connectToPeer called - agentTag: $agentTag, host: $host, port: $port, retry: $retryCount")

        // Don't connect to self
        if (agentTag == userTag) {
            Log.d(TAG, "Skipping connection to self")
            return
        }

        if (activeConnections.containsKey(agentTag)) {
            Log.d(TAG, "Already connected to $agentTag")
            return // Already connected
        }

        // Check if we have a server connection from this peer already
        if (serverConnections.containsKey(agentTag)) {
            Log.d(TAG, "Already have incoming connection from $agentTag, skipping outgoing connection")
            return
        }

        try {
            // For emulator connections, try to detect and use the proper address
            var connectHost = host

            // If the host is a link-local or emulator address, we might need special handling
            if (host.startsWith("10.0.2.") || host.startsWith("fe80:")) {
                Log.d(TAG, "Detected emulator/link-local address: $host")
                // For emulators, the host machine is at 10.0.2.2
                // But we should use the actual discovered address
            }

            val uri = URI("ws://$connectHost:$port")
            Log.d(TAG, "Creating WebSocket client to $uri")
            val client = object : WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake?) {
                    Log.d(TAG, "Connected to $agentTag")
                    activeConnections[agentTag] = this
                    updateConnectionStatus(agentTag, true)

                    // Send identification message so server knows who we are
                    val identMessage = P2PMessage(
                        from = userTag,
                        to = agentTag,
                        type = MessageType.IDENTIFICATION,
                        content = userPublicKey
                    )
                    send(JsonMapper.toJson(identMessage))
                    Log.d(TAG, "Sent identification message to $agentTag")

                    // Send any pending messages
                    sendPendingMessages(agentTag)
                }

                override fun onMessage(message: String?) {
                    Log.d(TAG, "WebSocket client received message: $message")
                    message?.let { handleIncomingMessage(it) }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "Disconnected from $agentTag: $reason")
                    activeConnections.remove(agentTag)
                    updateConnectionStatus(agentTag, false)
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "Connection error with $agentTag: ${ex?.message}", ex)
                    activeConnections.remove(agentTag)
                    updateConnectionStatus(agentTag, false)

                    // Retry connection after a delay
                    if (retryCount < 3) {
                        scope.launch {
                            delay(2000) // Wait 2 seconds before retry
                            Log.d(TAG, "Retrying connection to $agentTag (attempt ${retryCount + 1}/3)")
                            connectToPeer(agentTag, host, port, retryCount + 1)
                        }
                    } else {
                        Log.e(TAG, "Failed to connect to $agentTag after 3 attempts")
                    }
                }
            }

            Log.d(TAG, "Connecting WebSocket client...")
            client.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $agentTag", e)
        }
    }

    private fun handleIncomingMessage(messageJson: String) {
        Log.d(TAG, "Received message: $messageJson")
        Log.d(TAG, "Current userTag: $userTag")
        scope.launch {
            try {
                Log.d(TAG, "Inside coroutine, parsing message...")
                val p2pMessage = JsonMapper.fromJson(messageJson, P2PMessage::class.java)
                Log.d(TAG, "Parsed message - from: ${p2pMessage.from}, to: ${p2pMessage.to}, type: ${p2pMessage.type}")

                // Verify message is for us
                Log.d(TAG, "Checking if message is for us - to: ${p2pMessage.to}, userTag: $userTag, equals: ${p2pMessage.to == userTag}")
                if (p2pMessage.to != userTag) {
                    Log.d(TAG, "Message not for us - ignoring (to: ${p2pMessage.to}, our tag: $userTag)")
                    return@launch
                }

                // Handle different message types
                Log.d(TAG, "Message is for us, handling type: ${p2pMessage.type}")
                Log.d(TAG, "MessageType enum check - HANDSHAKE_REQUEST: ${MessageType.HANDSHAKE_REQUEST}, equals: ${p2pMessage.type == MessageType.HANDSHAKE_REQUEST}")
                when (p2pMessage.type) {
                    MessageType.IDENTIFICATION -> {
                        // Don't save identification messages, they're just for connection mapping
                        Log.d(TAG, "Received identification from ${p2pMessage.from}")
                    }
                    MessageType.HANDSHAKE_REQUEST -> handleHandshakeRequest(p2pMessage)
                    MessageType.HANDSHAKE_ACCEPT -> handleHandshakeAccept(p2pMessage)
                    MessageType.AVAILABILITY_UPDATE -> handleAvailabilityUpdate(p2pMessage)
                    else -> saveIncomingMessage(p2pMessage)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error handling message: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }

    private suspend fun handleHandshakeRequest(message: P2PMessage) {
        Log.d(TAG, "handleHandshakeRequest called for message from: ${message.from}")
        // Create or update conversation
        var conversation = conversationDao.getConversation(message.from)
        Log.d(TAG, "Existing conversation: ${conversation != null}")
        if (conversation == null) {
            conversation = ChatConversation(
                conversationId = message.from,
                agentTag = message.from,
                agentPublicKey = message.content, // Public key in content
                lastMessageTime = message.timestamp,
                lastMessageText = "New chat request",
                isApproved = false
            )
            conversationDao.insertConversation(conversation)
            Log.d(TAG, "Created new conversation for ${message.from}")
        }

        // Save handshake message
        Log.d(TAG, "Saving handshake message...")
        saveIncomingMessage(message)
        Log.d(TAG, "Handshake request handled successfully - awaiting agent approval")
    }

    private suspend fun handleHandshakeAccept(message: P2PMessage) {
        conversationDao.updateApprovalStatus(message.from, true)
        saveIncomingMessage(message)
    }

    private suspend fun handleAvailabilityUpdate(message: P2PMessage) {
        val isAvailable = message.content.toBoolean()
        conversationDao.updateAvailability(message.from, isAvailable)
        updateConnectionStatus(message.from, activeConnections.containsKey(message.from), isAvailable)
    }

    private suspend fun saveIncomingMessage(p2pMessage: P2PMessage) {
        Log.d(TAG, "saveIncomingMessage called - type: ${p2pMessage.type}, from: ${p2pMessage.from}")

        // Ensure conversation exists before saving message
        var conversation = conversationDao.getConversation(p2pMessage.from)
        if (conversation == null) {
            Log.d(TAG, "Creating conversation for ${p2pMessage.from} before saving message")
            conversation = ChatConversation(
                conversationId = p2pMessage.from,
                agentTag = p2pMessage.from,
                agentPublicKey = null, // Will be updated when we get identification or handshake
                lastMessageTime = p2pMessage.timestamp,
                lastMessageText = p2pMessage.content,
                isApproved = true // Auto-approve for regular messages
            )
            conversationDao.insertConversation(conversation)
        }

        val chatMessage = ChatMessage(
            messageId = p2pMessage.messageId,
            conversationId = p2pMessage.from,
            content = p2pMessage.content,
            timestamp = p2pMessage.timestamp,
            isFromMe = false,
            status = MessageStatus.DELIVERED,
            type = p2pMessage.type,
            signature = p2pMessage.signature
        )

        messageDao.insertMessage(chatMessage)

        // Update conversation
        conversationDao.incrementUnreadCount(p2pMessage.from)
        val updatedConversation = conversationDao.getConversation(p2pMessage.from)
        updatedConversation?.let {
            conversationDao.updateConversation(
                it.copy(
                    lastMessageTime = p2pMessage.timestamp,
                    lastMessageText = when (p2pMessage.type) {
                        MessageType.LOCATION -> "📍 Location shared"
                        MessageType.MEETING_REQUEST -> "🤝 Meeting requested"
                        MessageType.TRANSACTION_CONFIRM -> "✅ Transaction confirmed"
                        else -> p2pMessage.content
                    }
                )
            )
        }

        // Show notification for new message
        //TODO implement
        //showMessageNotification(p2pMessage)
    }

    override fun sendMessage(toTag: String, content: String) {
        sendMessage(toTag, content, MessageType.TEXT)
    }

    fun sendMessage(agentTag: String, content: String, type: MessageType = MessageType.TEXT): String {
        Log.d(TAG, "sendMessage called - to: $agentTag, type: $type, content: $content")

        // Don't send messages to self
        if (agentTag == userTag) {
            Log.w(TAG, "Cannot send message to self")
            return ""
        }

        val messageId = UUID.randomUUID().toString()
        val p2pMessage = P2PMessage(
            messageId = messageId,
            from = userTag,
            to = agentTag,
            type = type,
            content = content,
            signature = signMessage(content)
        )

        scope.launch {
            // Ensure conversation exists before saving message
            var conversation = conversationDao.getConversation(agentTag)
            if (conversation == null) {
                Log.d(TAG, "Creating conversation for outgoing message to $agentTag")
                conversation = ChatConversation(
                    conversationId = agentTag,
                    agentTag = agentTag,
                    agentPublicKey = null,
                    lastMessageTime = p2pMessage.timestamp,
                    lastMessageText = content,
                    isApproved = true
                )
                conversationDao.insertConversation(conversation)
            }

            // Save to database as pending
            val chatMessage = ChatMessage(
                messageId = messageId,
                conversationId = agentTag,
                content = content,
                timestamp = p2pMessage.timestamp,
                isFromMe = true,
                status = MessageStatus.PENDING,
                type = type,
                signature = p2pMessage.signature
            )
            messageDao.insertMessage(chatMessage)

            // Try to send
            if (sendP2PMessage(agentTag, p2pMessage)) {
                messageDao.updateMessageStatus(messageId, MessageStatus.SENT)
            } else {
                // Queue for later
                pendingMessages.getOrPut(agentTag) { mutableListOf() }.add(p2pMessage)
                Log.w(TAG, "Message queued for later delivery to $agentTag")
            }
        }

        return messageId
    }

    private fun sendP2PMessage(agentTag: String, message: P2PMessage): Boolean {
        Log.d(TAG, "sendP2PMessage - looking for connection to: $agentTag")
        Log.d(TAG, "Active connections: ${activeConnections.keys}")
        Log.d(TAG, "Server connections: ${serverConnections.keys}")

        // Check both outgoing and incoming connections
        val client = activeConnections[agentTag]
        val serverConn = serverConnections[agentTag]

        return when {
            client != null -> {
                // Use outgoing connection
                try {
                    val messageJson = JsonMapper.toJson(message)
                    Log.d(TAG, "Sending message via client connection: $messageJson")
                    client.send(messageJson)
                    Log.d(TAG, "Message sent successfully via client")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send via client connection", e)
                    false
                }
            }
            serverConn != null -> {
                // Use incoming connection
                try {
                    val messageJson = JsonMapper.toJson(message)
                    Log.d(TAG, "Sending message via server connection: $messageJson")
                    serverConn.send(messageJson)
                    Log.d(TAG, "Message sent successfully via server")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send via server connection", e)
                    false
                }
            }
            else -> {
                Log.w(TAG, "No connection available to $agentTag")
                // Try to rediscover and reconnect
                scope.launch {
                    Log.d(TAG, "Attempting to rediscover $agentTag")
                    // Force a new discovery
                    startNsdDiscovery()
                }
                false
            }
        }
    }

    private fun sendPendingMessages(agentTag: String) {
        scope.launch {
            // Send queued P2P messages
            pendingMessages[agentTag]?.let { messages ->
                messages.forEach { message ->
                    if (sendP2PMessage(agentTag, message)) {
                        messageDao.updateMessageStatus(message.messageId, MessageStatus.SENT)
                    }
                }
                pendingMessages.remove(agentTag)
            }

            // Send database pending messages
            val pendingDbMessages = messageDao.getMessagesByStatus(agentTag, MessageStatus.PENDING)
            pendingDbMessages.forEach { chatMessage ->
                val p2pMessage = P2PMessage(
                    messageId = chatMessage.messageId,
                    from = userTag,
                    to = agentTag,
                    type = chatMessage.type,
                    content = chatMessage.content,
                    timestamp = chatMessage.timestamp,
                    signature = chatMessage.signature
                )

                if (sendP2PMessage(agentTag, p2pMessage)) {
                    messageDao.updateMessageStatus(chatMessage.messageId, MessageStatus.SENT)
                }
            }
        }
    }

    private fun processPendingMessages() {
        scope.launch {
            while (isActive) {
                delay(30000) // Check every 30 seconds

                // Try to send all pending messages
                activeConnections.keys.forEach { agentTag ->
                    sendPendingMessages(agentTag)
                }
            }
        }
    }

    private fun updateConnectionStatus(agentTag: String, isConnected: Boolean, isAvailable: Boolean = false) {
        val currentStatus = _connectionStatus.value.toMutableMap()
        currentStatus[agentTag] = IP2PService.ConnectionStatus(
            isConnected = isConnected,
            isAvailable = isAvailable,
            lastSeen = if (isConnected) System.currentTimeMillis() else currentStatus[agentTag]?.lastSeen ?: 0
        )
        _connectionStatus.value = currentStatus
    }

    private fun signMessage(content: String): String {
        // TODO: Implement proper signing with wallet keys
        return MessageDigest.getInstance("SHA-256")
            .digest("$content$userTag".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    override fun initiateHandshake(agentTag: String) {
        Log.d(TAG, "initiateHandshake called for: $agentTag")

        // Create conversation on sender side
        scope.launch {
            var conversation = conversationDao.getConversation(agentTag)
            if (conversation == null) {
                conversation = ChatConversation(
                    conversationId = agentTag,
                    agentTag = agentTag,
                    agentPublicKey = null, // Will be updated when we get response
                    lastMessageTime = System.currentTimeMillis(),
                    lastMessageText = "Handshake sent",
                    isApproved = false // Will be true when we get acceptance
                )
                conversationDao.insertConversation(conversation)
                Log.d(TAG, "Created conversation for handshake to $agentTag")
            }
        }

        sendMessage(agentTag, userPublicKey, MessageType.HANDSHAKE_REQUEST)
    }

    override fun acceptHandshake(fromTag: String) {
        scope.launch {
            conversationDao.updateApprovalStatus(fromTag, true)
            sendMessage(fromTag, "accepted", MessageType.HANDSHAKE_ACCEPT)
        }
    }

    override fun updateAvailability(isAvailable: Boolean) {
        // Broadcast to all connected peers
        val message = P2PMessage(
            from = userTag,
            to = "", // Will be filled per peer
            type = MessageType.AVAILABILITY_UPDATE,
            content = isAvailable.toString()
        )

        // Send to outgoing connections
        activeConnections.forEach { (agentTag, client) ->
            try {
                val personalizedMessage = message.copy(to = agentTag)
                client.send(JsonMapper.toJson(personalizedMessage))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send availability update to $agentTag (client)", e)
            }
        }

        // Send to incoming connections
        serverConnections.forEach { (agentTag, conn) ->
            try {
                val personalizedMessage = message.copy(to = agentTag)
                conn.send(JsonMapper.toJson(personalizedMessage))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send availability update to $agentTag (server)", e)
            }
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("UnicityChatMulticast").apply {
                setReferenceCounted(false)
                acquire()
                Log.d(TAG, "Multicast lock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Multicast lock released")
                }
            }
            multicastLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing multicast lock", e)
        }
    }

    override fun shutdown() {
        Log.d(TAG, "Shutting down P2P service")

        // Release multicast lock
        releaseMulticastLock()

        // Stop NSD discovery
        try {
            discoveryListener?.let {
                Log.d(TAG, "Stopping NSD discovery")
                nsdManager.stopServiceDiscovery(it)
                discoveryListener = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }

        // Unregister NSD service
        try {
            registrationListener?.let {
                Log.d(TAG, "Unregistering NSD service")
                nsdManager.unregisterService(it)
                registrationListener = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering service", e)
        }

        // Cancel coroutines
        scope.cancel()

        // Stop WebSocket server
        webSocketServer?.stop()

        // Close all connections
        activeConnections.values.forEach { it.close() }
        activeConnections.clear()
        serverConnections.clear()

        // Clear instance
        INSTANCE = null
    }

    override fun rejectHandshake(fromTag: String) {
        // For now, rejecting means just not accepting
        // Could send a rejection message if needed
        Log.d(TAG, "Rejecting handshake from $fromTag")
    }

    override fun start() {
        // Service starts automatically in init block
        Log.d(TAG, "P2P service start requested")
    }

    override fun stop() {
        // Stop discovery but keep server running for incoming connections
        try {
            discoveryListener?.let {
                nsdManager.stopServiceDiscovery(it)
                discoveryListener = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }
    }

    override fun isRunning(): Boolean {
        return webSocketServer != null
    }

    override fun broadcastLocation(latitude: Double, longitude: Double) {
        // For WebSocket implementation, broadcast location to all connected peers on local network
        scope.launch {
            try {
                val locationMessage = mapOf(
                    "type" to "location",
                    "from" to userTag,
                    "latitude" to latitude,
                    "longitude" to longitude,
                    "timestamp" to System.currentTimeMillis()
                )

                val json = JsonMapper.toJson(locationMessage)

                // Broadcast to all active client connections
                activeConnections.forEach { (peerTag, client) ->
                    try {
                        if (client.isOpen) {
                            client.send(json)
                            Log.d(TAG, "Sent location to $peerTag: ($latitude, $longitude)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send location to $peerTag", e)
                    }
                }

                // Also broadcast to incoming server connections
                serverConnections.forEach { (peerTag, conn) ->
                    try {
                        if (conn.isOpen) {
                            conn.send(json)
                            Log.d(TAG, "Sent location to server connection $peerTag: ($latitude, $longitude)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send location to server connection $peerTag", e)
                    }
                }

                val totalPeers = activeConnections.size + serverConnections.size
                Log.d(TAG, "Broadcasting location to $totalPeers peers")
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting location", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Unicity Chat Messages"
            val descriptionText = "Notifications for new chat messages"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
//TODO implement

//    private fun showMessageNotification(message: P2PMessage) {
//        Log.d(TAG, "Showing notification for message from ${message.from}")
//
//        // Check if we're currently in ChatActivity with this user
//        val prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
//        val currentChatPartner = prefs.getString("current_chat_partner", null)
//
//        if (currentChatPartner == message.from) {
//            Log.d(TAG, "User is currently chatting with ${message.from}, skipping notification")
//            return
//        }
//
//        // Special handling for handshake requests - show dialog instead of notification
//        if (message.type == MessageType.HANDSHAKE_REQUEST) {
//            showHandshakeDialog(message)
//            return
//        }
//
//        // Create intent to open chat
//        val intent = Intent(context, ChatActivity::class.java).apply {
//            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
//            putExtra("extra_agent_tag", message.from)
//            putExtra("extra_agent_name", "${message.from}@unicity")
//        }
//
//        val pendingIntent = PendingIntent.getActivity(
//            context,
//            message.from.hashCode(),
//            intent,
//            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
//        )
//
//        val notificationText = when (message.type) {
//            MessageType.HANDSHAKE_REQUEST -> "New chat request from ${message.from}"
//            MessageType.HANDSHAKE_ACCEPT -> "${message.from} accepted your chat request"
//            MessageType.LOCATION -> "${message.from} shared their location"
//            MessageType.MEETING_REQUEST -> "${message.from} requested a meeting"
//            MessageType.TRANSACTION_CONFIRM -> "${message.from} confirmed the transaction"
//            else -> message.content
//        }
//
//        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
//            .setSmallIcon(R.drawable.ic_chat_bubble)
//            .setContentTitle("${message.from}@unicity")
//            .setContentText(notificationText)
//            .setPriority(NotificationCompat.PRIORITY_HIGH)
//            .setAutoCancel(true)
//            .setContentIntent(pendingIntent)
//            .build()
//
//        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
//            try {
//                NotificationManagerCompat.from(context).notify(
//                    message.from.hashCode(),
//                    notification
//                )
//            } catch (e: SecurityException) {
//                Log.e(TAG, "Failed to show notification - missing permission", e)
//                showInAppAlert(message)
//            }
//        } else {
//            Log.w(TAG, "Notifications are disabled")
//            showInAppAlert(message)
//        }
//    }

    private fun showInAppAlert(message: P2PMessage) {
        Log.d(TAG, "Showing in-app alert for message from ${message.from}")

        // Check if we're currently chatting with this user
        val prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
        val currentChatPartner = prefs.getString("current_chat_partner", null)

        if (currentChatPartner == message.from) {
            Log.d(TAG, "User is currently chatting with ${message.from}, skipping in-app alert")
            return
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            val alertText = when (message.type) {
                MessageType.HANDSHAKE_REQUEST -> "New chat request from ${message.from}"
                MessageType.HANDSHAKE_ACCEPT -> "${message.from} accepted your chat request"
                MessageType.LOCATION -> "${message.from} shared their location"
                MessageType.MEETING_REQUEST -> "${message.from} requested a meeting"
                MessageType.TRANSACTION_CONFIRM -> "${message.from} confirmed the transaction"
                else -> "New message from ${message.from}: ${message.content}"
            }

            android.widget.Toast.makeText(
                context,
                alertText,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }


//    private fun showHandshakeDialog(message: P2PMessage) {
//        Log.d(TAG, "Showing handshake dialog for ${message.from}")
//
//        // Send a broadcast to show the dialog in the current activity
//        val intent = Intent(ACTION_HANDSHAKE_REQUEST).apply {
//            putExtra(EXTRA_FROM_TAG, message.from)
//            putExtra(EXTRA_FROM_NAME, "${message.from}@unicity")
//        }
//        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
//
//        // Also show a notification as fallback
//        showHandshakeNotification(message)
//    }

//    private fun showHandshakeNotification(message: P2PMessage) {
//        // Create intent to open chat
//        val intent = Intent(context, ChatActivity::class.java).apply {
//            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
//            putExtra("extra_agent_tag", message.from)
//            putExtra("extra_agent_name", "${message.from}@unicity")
//        }
//
//        val pendingIntent = PendingIntent.getActivity(
//            context,
//            message.from.hashCode(),
//            intent,
//            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
//        )
//
//        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
//            .setSmallIcon(R.drawable.ic_chat_bubble)
//            .setContentTitle("New Chat Request")
//            .setContentText("${message.from} is trying to chat")
//            .setPriority(NotificationCompat.PRIORITY_HIGH)
//            .setAutoCancel(true)
//            .setContentIntent(pendingIntent)
//            .addAction(
//                R.drawable.ic_chat_bubble,
//                "Accept",
//                pendingIntent
//            )
//            .build()
//
//        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
//            try {
//                NotificationManagerCompat.from(context).notify(
//                    message.from.hashCode(),
//                    notification
//                )
//            } catch (e: SecurityException) {
//                Log.e(TAG, "Failed to show notification - missing permission", e)
//            }
//        }
//    }
}
