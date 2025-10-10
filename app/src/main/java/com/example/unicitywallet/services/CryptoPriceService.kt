package com.example.unicitywallet.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.unicitywallet.utils.JsonMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory
import java.util.concurrent.TimeUnit
import com.example.unicitywallet.data.api.CryptoPriceApi

class CryptoPriceService(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("crypto_prices", Context.MODE_PRIVATE)

    private val api: CryptoPriceApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.coingecko.com/")
            .addConverterFactory(JacksonConverterFactory.create(JsonMapper.mapper))
            .build()
            .create(CryptoPriceApi::class.java)
    }

    companion object {
        private const val TAG = "CryptoPriceService"
        private const val CACHE_DURATION_MINUTES = 5L
        private const val KEY_LAST_UPDATE = "last_price_update"
        private const val KEY_PRICE_DATA = "price_data_"

        // Default prices (more realistic as of 2024)
        val DEFAULT_PRICES = mapOf(
            "bitcoin" to CryptoPriceData(
                priceUsd = 98500.0,
                priceEur = 91200.0,
                change24h = 2.3
            ),
            "ethereum" to CryptoPriceData(
                priceUsd = 3850.0,
                priceEur = 3560.0,
                change24h = 1.8
            ),
            "tether" to CryptoPriceData(
                priceUsd = 1.0,
                priceEur = 0.92,
                change24h = 0.01
            ),
            "solana" to CryptoPriceData(
                priceUsd = 220.0,
                priceEur = 218.92,
                change24h = 0.11
            ),
            "efranc" to CryptoPriceData(
                priceUsd = 0.00169, // 1 XAF = ~0.00169 USD (1 USD = 592 XAF)
                priceEur = 0.00152, // 1 XAF = ~0.00152 EUR (1 EUR = 656 XAF)
                change24h = 0.01 // Small fluctuation for stablecoin
            ),
            "enaira" to CryptoPriceData(
                priceUsd = 0.000647, // 1 NGN = ~0.000647 USD (1 USD = 1546.55 NGN)
                priceEur = 0.000564, // 1 NGN = ~0.000564 EUR (1 EUR = 1772.27 NGN)
                change24h = 0.02 // Small fluctuation for stablecoin
            )
        )
    }

    data class CryptoPriceData(
        val priceUsd: Double,
        val priceEur: Double,
        val change24h: Double,
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun fetchPrices(forceRefresh: Boolean = false): Map<String, CryptoPriceData> = withContext(Dispatchers.IO) {
        try {
            val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0)
            val now = System.currentTimeMillis()
            val cacheExpired = (now - lastUpdate) > TimeUnit.MINUTES.toMillis(CACHE_DURATION_MINUTES)

            // Return cached prices if not expired and not forcing refresh
            if (!forceRefresh && !cacheExpired) {
                val cachedPrices = loadCachedPrices()
                if (cachedPrices.isNotEmpty()) {
                    Log.d(TAG, "Returning cached prices")
                    return@withContext cachedPrices
                }
            }

            // Fetch fresh prices
            Log.d(TAG, "Fetching fresh prices from API")
            val response = api.getCryptoPrices(
                ids = "bitcoin,ethereum,tether,solana",
                currencies = "usd,eur",
                includeChange = true
            )

            val prices = mutableMapOf<String, CryptoPriceData>()

            response.forEach { (cryptoId, priceData) ->
                val priceUsd = priceData.usd ?: DEFAULT_PRICES[cryptoId]?.priceUsd ?: 0.0
                val priceEur = priceData.eur ?: DEFAULT_PRICES[cryptoId]?.priceEur ?: 0.0
                val change24h = priceData.usd_24h_change ?: DEFAULT_PRICES[cryptoId]?.change24h ?: 0.0

                prices[cryptoId] = CryptoPriceData(
                    priceUsd = priceUsd,
                    priceEur = priceEur,
                    change24h = change24h
                )
            }

            // Save to cache
            savePricesToCache(prices)

            Log.d(TAG, "Fetched prices: BTC=$${prices["bitcoin"]?.priceUsd}, ETH=$${prices["ethereum"]?.priceUsd}, SOL=$${prices["solana"]?.priceUsd}")
            return@withContext prices

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching prices: ${e.message}", e)

            // Try to load from cache even if expired
            val cachedPrices = loadCachedPrices()
            if (cachedPrices.isNotEmpty()) {
                Log.d(TAG, "Returning expired cached prices due to error")
                return@withContext cachedPrices
            }

            // Return default prices as last resort
            Log.d(TAG, "Returning default prices")
            return@withContext DEFAULT_PRICES
        }
    }

    private fun savePricesToCache(prices: Map<String, CryptoPriceData>) {
        prefs.edit().apply {
            putLong(KEY_LAST_UPDATE, System.currentTimeMillis())

            prices.forEach { (cryptoId, priceData) ->
                val json = JsonMapper.toJson(priceData)
                putString(KEY_PRICE_DATA + cryptoId, json)
            }

            apply()
        }
        Log.d(TAG, "Saved ${prices.size} prices to cache")
    }

    private fun loadCachedPrices(): Map<String, CryptoPriceData> {
        val prices = mutableMapOf<String, CryptoPriceData>()

        listOf("bitcoin", "ethereum", "tether").forEach { cryptoId ->
            val json = prefs.getString(KEY_PRICE_DATA + cryptoId, null)
            if (json != null) {
                try {
                    val priceData = JsonMapper.fromJson<CryptoPriceData>(json)
                    prices[cryptoId] = priceData
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing cached price for $cryptoId", e)
                }
            }
        }

        return prices
    }

    fun getCachedPrice(cryptoId: String): CryptoPriceData? {
        val json = prefs.getString(KEY_PRICE_DATA + cryptoId, null) ?: return null
        return try {
            JsonMapper.fromJson<CryptoPriceData>(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing cached price for $cryptoId", e)
            null
        }
    }
}