package com.example.unicitywallet.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class TokenStatus {
    PENDING,      // Offline transfer received but not submitted to network
    SUBMITTED,    // Submitted to network, waiting for confirmation
    TRANSFERRED,  // Token sent to another wallet (archived)
    CONFIRMED,    // Confirmed on network
    BURNED,       // Token burned (split/swap) - cannot be used
    FAILED        // Network submission failed
}
data class Token(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val unicityAddress: String? = null,
    val jsonData: String? = null,
    val sizeBytes: Int = 0,
    val status: TokenStatus? = TokenStatus.CONFIRMED,
    val transactionId: String? = null,
    val isOfflineTransfer: Boolean = false,
    val pendingOfflineData: String? = null,
    val amount: String? = null,           // Amount of fungible tokens (e.g., 1000 SOL)
    val coinId: String? = null,         // Hex string coin ID from registry
    val symbol: String? = null,         // e.g., "SOL"
    val iconUrl: String? = null
) {
    fun getFormattedSize(): String {
        return when {
            sizeBytes < 1024 -> "${sizeBytes}B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024}KB"
            else -> "${sizeBytes / (1024 * 1024)}MB"
        }
    }

    fun getAmountAsBigInteger(): java.math.BigInteger? {
        return try {
            amount?.let { java.math.BigInteger(it) }
        } catch (e: Exception) {
            null
        }
    }
}