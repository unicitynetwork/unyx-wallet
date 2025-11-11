package com.example.unicitywallet.token

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Icon URL entry in the registry
 */
data class IconEntry(
    val url: String
)

/**
 * Data class representing a token or coin definition in the registry
 */
data class TokenDefinition(
    val network: String,
    val assetKind: String, // "fungible" or "non-fungible"
    val name: String,
    val symbol: String? = null,
    val decimals: Int? = null, // Number of decimal places (e.g., 9 for SOL)
    val description: String,
    val icon: String? = null,  // Legacy single icon field (deprecated)
    val icons: List<IconEntry>? = null, // New icons array
    val id: String // Hex string: tokenType for NFTs, coinId for fungible
) {
    /**
     * Get the best icon URL for display (prefer PNG over SVG)
     */
    fun getIconUrl(): String? {
        // Try new icons array first
        if (!icons.isNullOrEmpty()) {
            // Prefer PNG (usually second in array or contains ".png")
            val pngIcon = icons.find { it.url.contains(".png", ignoreCase = true) }
            if (pngIcon != null) return pngIcon.url

            // Fall back to first icon (usually SVG)
            return icons.first().url
        }

        // Fall back to legacy icon field
        return icon
    }
}

/**
 * Registry for Unicity token types and coin IDs
 * Caches registry from GitHub with fallback to bundled asset
 */
class UnicityTokenRegistry private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UnicityTokenRegistry"
        private const val REGISTRY_FILE = "unicity-ids.testnet.json"
        private const val REGISTRY_URL =
            "https://raw.githubusercontent.com/unicitynetwork/unicity-ids/refs/heads/main/unicity-ids.testnet.json"
        private const val CACHE_FILE_NAME = "unicity-ids-cache.json"
        private const val CACHE_VALIDITY_HOURS = 24

        @Volatile
        private var INSTANCE: UnicityTokenRegistry? = null

        fun getInstance(context: Context): UnicityTokenRegistry {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UnicityTokenRegistry(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private var tokenDefinitions: List<TokenDefinition>
    private var definitionsById: Map<String, TokenDefinition>
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    init {
        // Load registry (from cache or bundled asset)
        tokenDefinitions = loadRegistry()
        definitionsById = tokenDefinitions.associateBy { it.id }

        // Async update from GitHub if cache is stale
        updateRegistryIfStale()
    }

    private fun loadRegistry(): List<TokenDefinition> {
        val mapper = jacksonObjectMapper()

        // Try to load from cache first
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (cacheFile.exists() && !isCacheStale(cacheFile)) {
            try {
                Log.d(TAG, "Loading registry from cache: ${cacheFile.absolutePath}")
                return mapper.readValue(cacheFile)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load from cache, falling back to bundled asset", e)
            }
        }

        // Fall back to bundled asset
        Log.d(TAG, "Loading registry from bundled asset: $REGISTRY_FILE")
        val inputStream: InputStream = context.assets.open(REGISTRY_FILE)
        return mapper.readValue(inputStream)
    }

    private fun isCacheStale(cacheFile: File): Boolean {
        val ageMillis = System.currentTimeMillis() - cacheFile.lastModified()
        val ageHours = ageMillis / (1000 * 60 * 60)
        return ageHours > CACHE_VALIDITY_HOURS
    }

    private fun updateRegistryIfStale() {
        scope.launch {
            try {
                val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
                if (!cacheFile.exists() || isCacheStale(cacheFile)) {
                    Log.d(TAG, "Cache stale or missing, fetching from GitHub...")
                    fetchAndCacheRegistry()
                } else {
                    Log.d(TAG, "Cache is fresh, skipping update")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating registry", e)
            }
        }
    }

    private fun fetchAndCacheRegistry() {
        try {
            Log.d(TAG, "Fetching registry from: $REGISTRY_URL")

            val request = Request.Builder()
                .url(REGISTRY_URL)
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val jsonContent = response.body?.string()
                if (jsonContent != null) {
                    // Save to cache
                    val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
                    cacheFile.writeText(jsonContent)
                    Log.d(TAG, "Registry cached successfully")

                    // Reload definitions
                    val mapper = jacksonObjectMapper()
                    tokenDefinitions = mapper.readValue(jsonContent)
                    definitionsById = tokenDefinitions.associateBy { it.id }
                    Log.d(TAG, "Registry reloaded: ${tokenDefinitions.size} definitions")
                }
            } else {
                Log.w(TAG, "Failed to fetch registry: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching registry from GitHub", e)
        }
    }

    /**
     * Get token type definition by its hex ID
     */
    fun getTokenType(tokenTypeHex: String): TokenDefinition? {
        return definitionsById[tokenTypeHex]
    }

    /**
     * Get coin definition by its hex ID
     */
    fun getCoinDefinition(coinIdHex: String): TokenDefinition? {
        var definition = definitionsById[coinIdHex]
        if (definition != null) {
            return definition
        }

        Log.d(TAG, "Coin ID $coinIdHex not found in cache, refreshing from online registry...")
        fetchAndCacheRegistry()

        definition = definitionsById[coinIdHex]
        if (definition != null) {
            Log.d(TAG, "Found coin after refresh: ${definition.symbol}")
        }

        return definition
    }

    /**
     * Get all fungible token definitions
     */
    fun getFungibleTokens(): List<TokenDefinition> {
        return tokenDefinitions.filter { it.assetKind == "fungible" }
    }

    /**
     * Get all non-fungible token definitions
     */
    fun getNonFungibleTokens(): List<TokenDefinition> {
        return tokenDefinitions.filter { it.assetKind == "non-fungible" }
    }

    /**
     * Get all token definitions
     */
    fun getAllDefinitions(): List<TokenDefinition> {
        return tokenDefinitions
    }

    /**
     * Alias for getAllDefinitions() - returns all assets (tokens and coins)
     */
    fun getAllAssets(): List<TokenDefinition> {
        return getAllDefinitions()
    }

    /**
     * Alias for getCoinDefinition() - get coin by ID
     */
    fun getCoinById(coinIdHex: String): TokenDefinition? {
        return getCoinDefinition(coinIdHex)
    }
}
