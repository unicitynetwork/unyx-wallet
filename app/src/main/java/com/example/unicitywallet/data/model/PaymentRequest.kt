package com.example.unicitywallet.data.model

import java.math.BigInteger

/**
 * Payment request for Nostr-based token transfers
 * Encoded as URI in QR codes (e.g., unicity://pay?nametag=alice&coinId=...&amount=1000)
 */
data class PaymentRequest(
    val nametag: String,
    val coinId: String? = null,
    val amount: BigInteger? = null
) {
    companion object {
        fun fromUri(uri: String): PaymentRequest? {
            return try {
                val parsedUri = android.net.Uri.parse(uri)

                if (parsedUri.scheme != "unicity" || parsedUri.host != "pay") {
                    return null
                }

                val nametag = parsedUri.getQueryParameter("nametag") ?: return null
                val coinId = parsedUri.getQueryParameter("coinId")
                val amountStr = parsedUri.getQueryParameter("amount")
                val amount = if (amountStr != null) {
                    try {
                        BigInteger(amountStr)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }

                PaymentRequest(
                    nametag = nametag,
                    coinId = coinId,
                    amount = amount
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toUri(): String {
        val builder = StringBuilder("unicity://pay?nametag=$nametag")
        coinId?.let { builder.append("&coinId=$it") }
        amount?.let { builder.append("&amount=$it") }
        return builder.toString()
    }

    fun isSpecific(): Boolean = coinId != null && amount != null
}
