package com.example.unicitywallet.p2p

import android.content.Context
import android.util.Log
import com.example.unicitywallet.nostr.NostrP2PService

/**
 * Factory to create and manage P2P service instances
 * Supports WebSocket-based P2P for local network and Nostr for global messaging
 */
object P2PServiceFactory {
    private const val TAG = "P2PServiceFactory"

    enum class ServiceType {
        WEBSOCKET,
        NOSTR
    }

    private var currentServiceType: ServiceType = ServiceType.NOSTR  // Default to Nostr for cross-network support
    private var currentInstance: IP2PService? = null

    /**
     * Get or create a P2P service instance.
     *
     * @param context Optional context - if null, returns existing instance or null
     * @param userTag Required when creating new instance
     * @param userPublicKey Required when creating new instance
     * @return P2P service instance or null if not initialized and context is null
     */
    @JvmStatic
    fun getInstance(
        context: Context? = null,
        userTag: String? = null,
        userPublicKey: String? = null
    ): IP2PService? {
        // If we already have an instance, return it
        currentInstance?.let { return it }

        // No instance exists - need context to create one
        if (context == null) {
            Log.d(TAG, "No existing P2P service instance and no context provided")
            return null
        }

        // We have context, so create a new instance
        require(userTag != null) { "userTag required when creating new P2P service instance" }
        require(userPublicKey != null) { "userPublicKey required when creating new P2P service instance" }

        // Always use Nostr for cross-network support
        // Later we can add a preference if needed, but for now force Nostr
        currentServiceType = ServiceType.NOSTR

        // Ensure Nostr preference is set
        val prefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("use_nostr_p2p", true).apply()

        Log.d(TAG, "Creating new ${currentServiceType.name} P2P service instance")

        currentInstance = when (currentServiceType) {
            ServiceType.NOSTR -> {
                Log.d(TAG, "Initializing Nostr P2P service")
                val nostrService = NostrP2PService.getInstance(context)
                if (nostrService != null) {
                    Log.d(TAG, "Nostr service created successfully")
                    // Start the service if not already running
                    if (!nostrService.isRunning()) {
                        nostrService.start()
                        Log.d(TAG, "Nostr service started")
                    }
                } else {
                    Log.e(TAG, "Failed to create Nostr service")
                }
                nostrService
            }
            ServiceType.WEBSOCKET -> {
                Log.d(TAG, "Initializing WebSocket P2P service")
                P2PMessagingService.getInstance(context, userTag, userPublicKey)
            }
        }

        return currentInstance
    }

    /**
     * Reset the current instance (useful for testing or service restart)
     */
    fun reset() {
        val instance = currentInstance
        if (instance != null) {
            try {
                if (instance.isRunning()) {
                    instance.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping P2P service", e)
            }
        }
        currentInstance = null
        Log.d(TAG, "P2P service instance reset")
    }

    /**
     * Set the preferred P2P service type for future instances
     */
    fun setServiceType(context: Context, type: ServiceType) {
        val prefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("use_nostr_p2p", type == ServiceType.NOSTR).apply()
        Log.d(TAG, "P2P service type preference set to: ${type.name}")

        // Note: This doesn't affect the current instance - call reset() to apply changes
    }

    /**
     * Get the current service type
     */
    fun getCurrentServiceType(): ServiceType = currentServiceType

    /**
     * Check if a service instance exists
     */
    fun hasInstance(): Boolean = currentInstance != null
}