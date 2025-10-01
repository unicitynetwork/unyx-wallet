package com.example.unicitywallet.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class TokenStatus {
    PENDING,      // Offline transfer received but not submitted to network
    SUBMITTED,    // Submitted to network, waiting for confirmation
    CONFIRMED,    // Confirmed on network
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
    val pendingOfflineData: String? = null
) {
    fun getFormattedSize(): String {
        return when {
            sizeBytes < 1024 -> "${sizeBytes}B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024}KB"
            else -> "${sizeBytes / (1024 * 1024)}MB"
        }
    }
}