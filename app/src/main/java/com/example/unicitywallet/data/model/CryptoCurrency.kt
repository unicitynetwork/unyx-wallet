package com.example.unicitywallet.data.model

data class CryptoCurrency(
    val id: String,
    val symbol: String,
    val name: String,
    val balance: Double,
    val priceUsd: Double,
    val priceEur: Double,
    val change24h: Double,
    val iconResId: Int,
    val isDemo: Boolean = true, // Demo coins vs real Unicity tokens
    val iconUrl: String? = null // URL for icon (used for real tokens)
) {
    fun getBalanceInFiat(currency: String): Double {
        return when (currency) {
            "EUR" -> balance * priceEur
            else -> balance * priceUsd
        }
    }

    fun getFormattedBalance(): String {
        return if (balance % 1 == 0.0) {
            balance.toInt().toString()
        } else {
            String.format("%.8f", balance).trimEnd('0').trimEnd('.')
        }
    }

    fun getFormattedPrice(currency: String): String {
        val price = when (currency) {
            "EUR" -> priceEur
            else -> priceUsd
        }
        val symbol = when (currency) {
            "EUR" -> "€"
            else -> "$"
        }
        return "$symbol${String.format("%,.2f", price)}"
    }

    fun getFormattedBalanceInFiat(currency: String): String {
        val value = getBalanceInFiat(currency)
        val symbol = when (currency) {
            "EUR" -> "€"
            else -> "$"
        }
        return "$symbol${String.format("%,.2f", value)}"
    }

    fun getFormattedChange(): String {
        val sign = if (change24h >= 0) "+" else ""
        return "$sign${String.format("%.2f", change24h)}%"
    }
}