package com.example.unicitywallet.data.model

data class AggregatedAsset(
    val coinId: String,
    val symbol: String,
    val name: String?,
    val totalAmount: String,  // Sum of all amounts for this coinId (in smallest units)
    val decimals: Int,      // Number of decimal places from registry (e.g., 9 for SOL)
    val tokenCount: Int,    // Number of individual tokens containing this coin
    val iconUrl: String?,
    val priceUsd: Double = 0.0,  // Price per unit in USD
    val priceEur: Double = 0.0,  // Price per unit in EUR
    val change24h: Double = 0.0  // 24h price change percentage
) {
    fun getAmountAsBigInteger(): java.math.BigInteger {
        return try {
            java.math.BigInteger(totalAmount)
        } catch (e: Exception) {
            java.math.BigInteger.ZERO
        }
    }
    fun getFormattedAmount(): String {
        // Convert from smallest units to human-readable amount using decimals
        val bigIntAmount = getAmountAsBigInteger()
        val divisor = java.math.BigDecimal.TEN.pow(decimals)
        val amountAsDecimal = java.math.BigDecimal(bigIntAmount).divide(divisor)

        return if (amountAsDecimal.scale() == 0) {
            amountAsDecimal.toPlainString()
        } else {
            // Show up to 'decimals' decimal places, trimming trailing zeros
            amountAsDecimal.stripTrailingZeros().toPlainString()
        }
    }

    fun getAmountAsDecimal(): Double {
        val bigIntAmount = getAmountAsBigInteger()
        val divisor = Math.pow(10.0, decimals.toDouble())
        return bigIntAmount.toDouble() / divisor
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