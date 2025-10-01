package com.example.unicitywallet.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UserIdentity(
    val privateKey: String,
    val nonce: String,
    val publicKey: String,
    val address: String
) {
    /**
     * Returns a JSON representation of this identity
     */
    fun toJson(): String {
        return """{"privateKey":"$privateKey","nonce":"$nonce","publicKey":"$publicKey","address":"$address"}"""
    }
}