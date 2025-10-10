package com.example.unicitywallet.data.model

data class AggregatedAsset(
    val coinId: String,
    val symbol: String,
    val name: String?,
    val totalAmount: Long,  // Sum of all amounts for this coinId (in smallest units)
    val decimals: Int,      // Number of decimal places from registry (e.g., 9 for SOL)
    val tokenCount: Int,    // Number of individual tokens containing this coin
    val iconUrl: String?,
    val priceUsd: Double = 0.0,  // Price per unit in USD
    val priceEur: Double = 0.0,  // Price per unit in EUR
    val change24h: Double = 0.0  // 24h price change percentage
) {
    fun getFormattedAmount(): String {
        // Convert from smallest units to human-readable amount using decimals
        val amountAsDecimal = getAmountAsDecimal()

        return if (amountAsDecimal % 1 == 0.0) {
            amountAsDecimal.toInt().toString()
        } else {
            // Show up to 'decimals' decimal places, trimming trailing zeros
            String.format("%.${decimals}f", amountAsDecimal).trimEnd('0').trimEnd('.')
        }
    }

    fun getAmountAsDecimal(): Double {
        val divisor = Math.pow(10.0, decimals.toDouble())
        return totalAmount / divisor
    }

    fun getTotalFiatValue(currency: String): Double {
        val price = if (currency == "EUR") priceEur else priceUsd
        return getAmountAsDecimal() * price
    }

    fun getFormattedFiatValue(currency: String): String {
        val symbol = if (currency == "EUR") "€" else "$"
        val value = getTotalFiatValue(currency)
        return "$symbol${String.format("%,.2f", value)}"
    }

    fun getFormattedChange(): String {
        val sign = if (change24h >= 0) "+" else ""
        return "$sign${String.format("%.2f", change24h)}%"
    }
}