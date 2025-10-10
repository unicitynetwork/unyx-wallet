package com.example.unicitywallet.data.api

import retrofit2.http.GET
import retrofit2.http.Query

interface CryptoPriceApi {
    // Using CoinGecko API (free tier, no API key required)
    @GET("api/v3/simple/price")
    suspend fun getCryptoPrices(
        @Query("ids") ids: String,
        @Query("vs_currencies") currencies: String,
        @Query("include_24hr_change") includeChange: Boolean = true
    ): Map<String, CryptoPrice>
}

data class CryptoPrice(
    val usd: Double?,
    val eur: Double?,
    val usd_24h_change: Double?,
    val eur_24h_change: Double?
)