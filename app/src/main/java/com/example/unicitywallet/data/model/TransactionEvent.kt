package com.example.unicitywallet.data.model

data class TransactionEvent(
    val token: Token,
    val type: TransactionType,
    val timestamp: Long
)

enum class TransactionType {
    RECEIVED,  // Token was received
    SENT       // Token was sent
}