package com.example.unicitywallet.p2p
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for P2P messaging services
 * Allows switching between different P2P implementations (WebSocket, WebRTC, etc.)
 */
interface IP2PService {

    /**
     * Connection status for each peer
     */
    val connectionStatus: StateFlow<Map<String, ConnectionStatus>>

    /**
     * Send a text message to another user
     */
    fun sendMessage(toTag: String, content: String)

    /**
     * Initiate handshake with an agent
     */
    fun initiateHandshake(agentTag: String)

    /**
     * Accept a handshake request
     */
    fun acceptHandshake(fromTag: String)

    /**
     * Reject a handshake request
     */
    fun rejectHandshake(fromTag: String)

    /**
     * Start the P2P service
     */
    fun start()

    /**
     * Stop the P2P service
     */
    fun stop()

    /**
     * Shutdown and cleanup
     */
    fun shutdown()

    /**
     * Check if service is running
     */
    fun isRunning(): Boolean

    /**
     * Broadcast agent location to network
     */
    fun broadcastLocation(latitude: Double, longitude: Double)

    /**
     * Update agent availability status
     */
    fun updateAvailability(isAvailable: Boolean)

    /**
     * Data class for connection status
     */
    data class ConnectionStatus(
        val isConnected: Boolean,
        val isAvailable: Boolean = false,
        val lastSeen: Long = 0
    )
}