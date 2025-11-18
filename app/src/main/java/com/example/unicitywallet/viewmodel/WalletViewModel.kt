package com.example.unicitywallet.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.unicitywallet.R
import com.example.unicitywallet.data.model.AggregatedAsset
import com.example.unicitywallet.data.model.CryptoCurrency
import com.example.unicitywallet.data.model.Token
import com.example.unicitywallet.data.model.TokenStatus
import com.example.unicitywallet.data.model.TransactionEvent
import com.example.unicitywallet.data.model.TransactionType
import com.example.unicitywallet.data.repository.WalletRepository
import com.example.unicitywallet.services.CryptoPriceService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.example.unicitywallet.token.UnicityTokenRegistry
import com.example.unicitywallet.utils.HexUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.TokenId
import org.unicitylabs.sdk.token.TokenType
import java.math.BigInteger


class WalletViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WalletRepository.getInstance(application)
    private val prefs = application.getSharedPreferences("crypto_balances", android.content.Context.MODE_PRIVATE)
    private val priceService = CryptoPriceService(application)
    private var priceUpdateJob: Job? = null

    // Active tokens (exclude transferred tokens - they're in history only)
    val tokens: StateFlow<List<Token>> = repository.tokens
        .map { tokenList ->
            Log.d("WalletViewModel", "Filtering tokens: $tokenList")
            tokenList.filter { it.status != TokenStatus.TRANSFERRED && it.status != TokenStatus.BURNED }
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    val isLoading: StateFlow<Boolean> = repository.isLoading

    val transactionHistory: StateFlow<List<TransactionEvent>> = repository.tokens
        .map {
        tokenList ->
            Log.d("WalletViewModel", "Building transaction history from ${tokenList.size} tokens")
            val events = mutableListOf<TransactionEvent>()

            tokenList.forEach { token ->
                Log.d("WalletViewModel", "Token: ${token.name}, status=${token.status}, splitSentAmount=${token.splitSentAmount}")

                if(token.splitSentAmount != null){
                    val sentToken = token.copy(
                        amount = token.splitSentAmount,
                        status = TokenStatus.TRANSFERRED
                    )
                    val sentTimestamp = token.timestamp
                    events.add(
                        TransactionEvent(
                            token = sentToken,
                            type = TransactionType.SENT,
                            timestamp = sentTimestamp
                        )
                    )
                    Log.d("WalletViewModel", "Added SENT event for split: amount=${token.splitSentAmount} at $sentTimestamp")
                }
                // Skip burned tokens (never received by user)
                if (token.status == TokenStatus.BURNED) {
                    Log.d("WalletViewModel", "Skipping BURNED token (already processed splitSentAmount if present)")
                    return@forEach
                }

                if(token.splitSourceTokenId == null){
                    events.add(
                        TransactionEvent(
                            token = token,
                            type = TransactionType.RECEIVED,
                            timestamp = token.timestamp
                        )
                    )
                    Log.d("WalletViewModel", "Added RECEIVED event at ${token.timestamp}")
                }


                // If transferred, also add sent event
                if (token.status == TokenStatus.TRANSFERRED) {
                    val sentTimestamp = token.transferredAt ?: token.timestamp
                    events.add(
                        TransactionEvent(
                            token = token,
                            type = TransactionType.SENT,
                            timestamp = sentTimestamp
                        )
                    )
                    Log.d("WalletViewModel", "Added SENT event at $sentTimestamp")
                }
            }

            Log.d("WalletViewModel", "Total events created: ${events.size}")
            // Sort by timestamp (most recent first)
            events.sortedByDescending { it.timestamp }
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    // Outgoing transaction history (transferred tokens)
    val outgoingHistory: StateFlow<List<Token>> = repository.tokens
        .map { tokenList ->
            tokenList.filter { it.status == TokenStatus.TRANSFERRED }
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    // Incoming transaction history (all received tokens, sorted by timestamp)
    val incomingHistory: StateFlow<List<Token>> = repository.tokens
        .map { tokenList ->
            // All tokens that are not transferred (incoming), sorted by timestamp
            tokenList.filter {
                it.status != TokenStatus.TRANSFERRED
            }.sortedByDescending { it.timestamp }
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Gets all tokens for a specific coinId
     * Used for selecting which token to send when user picks an aggregated asset
     * Excludes transferred tokens
     */
    fun getTokensByCoinId(coinId: String): List<Token> {
        Log.d("WalletViewModel", "Here is the list of assets where we compare coinId ${tokens.value}")
        Log.d("WalletViewModel", "Here are tokens from repo ${repository.tokens.value}")
        return tokens.value.filter {
            it.coinId == coinId && it.status != TokenStatus.TRANSFERRED
        }
    }

    /**
     * Mark a token as transferred (archives it from active view)
     */
    fun markTokenAsTransferred(token: Token) {
        viewModelScope.launch {
            val updatedToken = token.copy(
                status = TokenStatus.TRANSFERRED,
                transferredAt = System.currentTimeMillis()
            )
            repository.updateToken(updatedToken)
        }
    }

    // Aggregated assets by coinId for Assets tab with price data
    val aggregatedAssets: StateFlow<List<AggregatedAsset>> = repository.tokens
        .map { tokenList ->
            // Filter tokens that have coins and are not transferred
            val tokensWithCoins = tokenList.filter {
                it.coinId != null && it.amount != null &&
                        it.status != TokenStatus.TRANSFERRED &&
                        it.status != TokenStatus.BURNED
            }

            // Get token registry for decimals lookup
            val registry = UnicityTokenRegistry.getInstance(getApplication())

            // CRITICAL: Deduplicate tokens by SDK token ID before aggregating
            // This prevents inflated balances from duplicate wallet entries
            val uniqueTokens = tokensWithCoins.distinctBy { token ->
                try {
                    token.jsonData?.let { jsonData ->
                        val sdkToken = org.unicitylabs.sdk.token.Token.fromJson(
                            jsonData
                        )
                        sdkToken.id.bytes.joinToString("") { "%02x".format(it) }
                    } ?: token.id // Fallback to wallet ID if no jsonData
                } catch (e: Exception) {
                    token.id // Fallback to wallet ID if parse fails
                }
            }

            if (tokensWithCoins.size != uniqueTokens.size) {
                Log.w("WalletViewModel", "⚠️ Assets: Removed ${tokensWithCoins.size - uniqueTokens.size} duplicate SDK tokens from balance calculation")
            }

            // Group by coinId and aggregate (using deduplicated tokens)
            uniqueTokens
                .groupBy { it.coinId!! }
                .map { (coinId, tokensForCoin) ->
                    val symbol = tokensForCoin.first().symbol ?: "UNKNOWN"

                    // Get coin definition from registry to retrieve decimals
                    val coinDef = registry.getCoinDefinition(coinId)
                    val decimals = coinDef?.decimals ?: 8 // Default to 8 if not specified

                    // Use coin name from registry directly as CoinGecko API ID
                    // Registry names are already in correct format (lowercase with hyphens)
                    val apiId = coinDef?.name ?: symbol.lowercase()

                    // Get price from service (cached or default)
                    val priceData = priceService.getCachedPrice(apiId)
                        ?: CryptoPriceService.CryptoPriceData(0.0, 0.0, 0.0)

                    AggregatedAsset(
                        coinId = coinId,
                        symbol = symbol,
                        name = coinDef?.name ?: tokensForCoin.firstOrNull()?.name,
                        totalAmount = tokensForCoin.mapNotNull { it.getAmountAsBigInteger() }
                            .fold(java.math.BigInteger.ZERO) { acc, amt -> acc + amt }
                            .toString(), // Keep as String to support arbitrary precision
                        decimals = decimals,
                        tokenCount = tokensForCoin.size,
                        iconUrl = coinDef?.getIconUrl() ?: tokensForCoin.first().iconUrl,
                        priceUsd = priceData.priceUsd,
                        priceEur = priceData.priceEur,
                        change24h = priceData.change24h
                    )
                }
                .sortedByDescending { it.getTotalFiatValue("USD") } // Sort by USD value descending
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    private val _selectedToken = MutableStateFlow<Token?>(null)
    val selectedToken: StateFlow<Token?> = _selectedToken.asStateFlow()

    private val _mintResult = MutableStateFlow<Result<Token>?>(null)
    val mintResult: StateFlow<Result<Token>?> = _mintResult.asStateFlow()

    private val _allCryptocurrencies = MutableStateFlow<List<CryptoCurrency>>(emptyList())

    // Aggregated token balances from Tokens tab
    private val aggregatedTokenBalances: StateFlow<List<CryptoCurrency>> = tokens
        .map { tokenList ->
            // Group tokens by coinId and sum amounts
            val registry = UnicityTokenRegistry.getInstance(application)
            tokenList
                .filter { it.coinId != null && it.amount != null }
                .groupBy { it.coinId!! }
                .mapNotNull { (coinId, tokensForCoin) ->
                    val coinDef = registry.getCoinDefinition(coinId)
                    if (coinDef != null) {
                        val totalBalance = tokensForCoin.mapNotNull { it.getAmountAsBigInteger() }
                            .fold(java.math.BigInteger.ZERO) { acc, amt -> acc + amt }
                            .toDouble()
                        CryptoCurrency(
                            id = coinId,
                            symbol = coinDef.symbol ?: coinId.take(4),
                            name = coinDef.name,
                            balance = totalBalance,
                            priceUsd = 0.0, // TODO: Get real price
                            priceEur = 0.0,
                            change24h = 0.0,
                            iconResId = R.drawable.unicity_logo,
                            isDemo = false,
                            iconUrl = coinDef.icon
                        )
                    } else null
                }
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    // Public cryptocurrencies flow that combines demo cryptos + aggregated tokens
    val cryptocurrencies: StateFlow<List<CryptoCurrency>> = kotlinx.coroutines.flow.combine(
        _allCryptocurrencies,
        aggregatedTokenBalances
    ) { demoCryptos, tokenCryptos ->
        (tokenCryptos + demoCryptos).filter { it.balance > 0.00000001 }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    init {
        Log.d("WalletViewModel", "ViewModel initialized")
        // Initialize with empty cryptocurrency list - only real Unicity tokens will be shown
        _allCryptocurrencies.value = emptyList()
        // Start price updates for bridged coins (BTC, ETH, SOL, etc.)
        startPriceUpdates()
    }

    fun selectToken(token: Token) {
        _selectedToken.value = token
    }

    fun removeToken(tokenId: String) {
        viewModelScope.launch {
            repository.removeToken(tokenId)
        }
    }

    fun addToken(token: Token) {
        viewModelScope.launch {
            repository.addToken(token)
        }
    }

    fun updateToken(token: Token) {
        viewModelScope.launch {
            repository.updateToken(token)
        }
    }

    suspend fun refreshTokens() {
        repository.refreshTokens()
    }

    fun clearWallet() {
        repository.clearWallet()
        // Clear saved balances
        prefs.edit().clear().apply()
        // Clear existing cryptocurrencies to force regeneration
        _allCryptocurrencies.value = emptyList()
        loadDemoCryptocurrencies()
    }

    private fun saveCryptocurrencies() {
        val cryptos = _allCryptocurrencies.value
        prefs.edit().apply {
            cryptos.forEach { crypto ->
                when (crypto.symbol) {
                    "eXAF" -> putFloat("efranc_balance", crypto.balance.toFloat())
                    "eNGN" -> putFloat("enaira_balance", crypto.balance.toFloat())
                    "BTC" -> putFloat("btc_balance", crypto.balance.toFloat())
                    "ETH" -> putFloat("eth_balance", crypto.balance.toFloat())
                    "USDT" -> putFloat("usdt_balance", crypto.balance.toFloat())
                    "SUB" -> putFloat("sub_balance", crypto.balance.toFloat())
                    "USDC" -> putFloat("usdc_balance", crypto.balance.toFloat())
                    "SOL" -> putFloat("sol_balance", crypto.balance.toFloat())
                }
            }
            apply()
        }
        Log.d("WalletViewModel", "Saved crypto balances")
    }

    fun loadDemoCryptocurrencies() {
        // Only generate new balances if the list is empty (first load or after reset)
        if (_allCryptocurrencies.value.isNotEmpty()) {
            Log.d("WalletViewModel", "Cryptocurrencies already loaded, skipping regeneration")
            return
        }

        // Get cached prices or use defaults
        val efrancPrice = priceService.getCachedPrice("efranc") ?: CryptoPriceService.DEFAULT_PRICES["efranc"]!!
        val enairaPrice = priceService.getCachedPrice("enaira") ?: CryptoPriceService.DEFAULT_PRICES["enaira"]!!
        val btcPrice = priceService.getCachedPrice("bitcoin") ?: CryptoPriceService.DEFAULT_PRICES["bitcoin"]!!
        val ethPrice = priceService.getCachedPrice("ethereum") ?: CryptoPriceService.DEFAULT_PRICES["ethereum"]!!
        val usdtPrice = priceService.getCachedPrice("tether") ?: CryptoPriceService.DEFAULT_PRICES["tether"]!!

        // Generate slightly randomized balances for more realistic appearance
        val efrancBalance = (250000.0 + kotlin.random.Random.nextDouble(-100000.0, 500000.0)).coerceAtLeast(25000.0)
        val enairaBalance = (500000.0 + kotlin.random.Random.nextDouble(-200000.0, 1000000.0)).coerceAtLeast(50000.0)
        val btcBalance = (1.0 + kotlin.random.Random.nextDouble(-0.8, 2.0)).coerceAtLeast(0.1)
        val ethBalance = (5.0 + kotlin.random.Random.nextDouble(-3.0, 10.0)).coerceAtLeast(0.1)
        val usdtBalance = (1200.0 + kotlin.random.Random.nextDouble(-800.0, 3000.0)).coerceAtLeast(50.0)
        val subBalance = (200.0 + kotlin.random.Random.nextDouble(-150.0, 500.0)).coerceAtLeast(10.0)

        _allCryptocurrencies.value = listOf(
            CryptoCurrency(
                id = "efranc",
                symbol = "eXAF",
                name = "eFranc",
                balance = kotlin.math.round(efrancBalance), // Round to whole numbers for Franc
                priceUsd = efrancPrice.priceUsd,
                priceEur = efrancPrice.priceEur,
                change24h = efrancPrice.change24h,
                iconResId = R.drawable.ic_franc
            ),
            CryptoCurrency(
                id = "enaira",
                symbol = "eNGN",
                name = "eNaira",
                balance = kotlin.math.round(enairaBalance), // Round to whole numbers for Naira
                priceUsd = enairaPrice.priceUsd,
                priceEur = enairaPrice.priceEur,
                change24h = enairaPrice.change24h,
                iconResId = R.drawable.ic_naira
            ),
            CryptoCurrency(
                id = "bitcoin",
                symbol = "BTC",
                name = "Bitcoin",
                balance = kotlin.math.round(btcBalance * 100) / 100.0, // Round to 2 decimals
                priceUsd = btcPrice.priceUsd,
                priceEur = btcPrice.priceEur,
                change24h = btcPrice.change24h,
                iconResId = R.drawable.ic_bitcoin
            ),
            CryptoCurrency(
                id = "ethereum",
                symbol = "ETH",
                name = "Ethereum",
                balance = kotlin.math.round(ethBalance * 100) / 100.0,
                priceUsd = ethPrice.priceUsd,
                priceEur = ethPrice.priceEur,
                change24h = ethPrice.change24h,
                iconResId = R.drawable.ic_ethereum
            ),
            CryptoCurrency(
                id = "tether",
                symbol = "USDT",
                name = "Tether USD",
                balance = kotlin.math.round(usdtBalance * 100) / 100.0,
                priceUsd = usdtPrice.priceUsd,
                priceEur = usdtPrice.priceEur,
                change24h = usdtPrice.change24h,
                iconResId = R.drawable.ic_tether
            ),
            CryptoCurrency(
                id = "subway",
                symbol = "SUB",
                name = "Subway",
                balance = kotlin.math.round(subBalance * 100) / 100.0,
                priceUsd = 1.0,
                priceEur = 0.91,
                change24h = 0.0, // Keep Subway stable
                iconResId = R.drawable.subway
            ),
            CryptoCurrency(
                id = "usd-coin",
                symbol = "USDC",
                name = "USD Coin",
                balance = 1000.0,
                priceUsd = 1.0,
                priceEur = 0.91,
                change24h = 0.0,
                iconResId = R.drawable.usdc
            ),
            CryptoCurrency(
                id = "solana",
                symbol = "SOL",
                name = "Solana",
                balance = 10.0,
                priceUsd = 100.0, // Will be updated from API
                priceEur = 91.0,  // Will be updated from API
                change24h = 0.0,
                iconResId = R.drawable.sol
            )
        )

        saveCryptocurrencies() // Save after generating new balances

        // Trigger price update in background
        viewModelScope.launch {
            updateCryptoPrices()
        }
    }

    fun updateCryptoBalance(cryptoId: String, newBalance: Double) {
        val oldBalance = _allCryptocurrencies.value.find { it.id == cryptoId }?.balance ?: 0.0
        Log.d("WalletViewModel", "Updating crypto balance for $cryptoId: $oldBalance -> $newBalance")

        _allCryptocurrencies.value = _allCryptocurrencies.value.map { crypto ->
            if (crypto.id == cryptoId) {
                crypto.copy(balance = newBalance)
            } else {
                crypto
            }
        }

        saveCryptocurrencies() // Save after balance update
    }

    fun deleteCrypto(cryptoId: String) {
        Log.d("WalletViewModel", "Deleting crypto: $cryptoId")

        _allCryptocurrencies.value = _allCryptocurrencies.value.filter { crypto ->
            crypto.id != cryptoId
        }

        saveCryptocurrencies() // Save after deletion
    }

    fun addReceivedCrypto(cryptoSymbol: String, amount: Double): Boolean {
        Log.d("WalletViewModel", "addReceivedCrypto called with: $cryptoSymbol, amount = $amount")

        val currentCryptos = _allCryptocurrencies.value.toMutableList()
        val existingCrypto = currentCryptos.find { it.symbol == cryptoSymbol }

        return if (existingCrypto != null) {
            // Add to existing balance
            val oldBalance = existingCrypto.balance
            val newBalance = oldBalance + amount
            Log.d("WalletViewModel", "Adding $amount $cryptoSymbol to existing balance: $oldBalance + $amount = $newBalance")

            val updatedCrypto = existingCrypto.copy(balance = newBalance)
            _allCryptocurrencies.value = currentCryptos.map { crypto ->
                if (crypto.id == existingCrypto.id) updatedCrypto else crypto
            }
            saveCryptocurrencies() // Save after receiving crypto
            true
        } else {
            // Crypto doesn't exist in wallet, return false to indicate it couldn't be added
            Log.e("WalletViewModel", "Crypto $cryptoSymbol not found in wallet!")
            false
        }
    }

    fun mintNewToken(name: String, data: String, amount: Long = 100) {
        viewModelScope.launch {
            val result = repository.mintNewToken(name, data, amount)
            _mintResult.value = result
        }
    }

    fun clearMintResult() {
        _mintResult.value = null
    }

    fun getSdkService() = repository.getSdkService()

    fun getIdentityManager() = repository.getIdentityManager()

    suspend fun testOfflineTransfer(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Find a Unicity token to test with
            val testToken = tokens.value.firstOrNull { it.type == "Unicity Token" }
                ?: return@withContext Result.failure(Exception("No Unicity tokens available for testing"))

            // Generate a test recipient identity
            val sdkService = getSdkService()
            val recipientSecretString = "test-recipient-${System.currentTimeMillis()}"
            // Create a test identity for recipient
            val recipientSecret = recipientSecretString.toByteArray()
            val recipientNonce = ByteArray(32).apply {
                java.security.SecureRandom().nextBytes(this)
            }

            // Parse token data to get tokenId and tokenType
            val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
            val tokenData = objectMapper.readTree(testToken.jsonData ?: "{}")
            val genesis = tokenData.get("genesis")
            val genesisData = genesis?.get("data")
            val tokenIdHex = genesisData?.get("tokenId")?.asText() ?: ""
            val tokenTypeHex = genesisData?.get("tokenType")?.asText() ?: ""

            // Create recipient's predicate to get correct address
            val recipientSigningService = SigningService.createFromMaskedSecret(recipientSecret, recipientNonce)
            val tokenType = TokenType(HexUtils.decodeHex(tokenTypeHex))
            val tokenId = TokenId(HexUtils.decodeHex(tokenIdHex))

            // Create salt for UnmaskedPredicate
            val salt = ByteArray(32)
            java.security.SecureRandom().nextBytes(salt)

            val recipientPredicate = org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate.create(
                tokenId,
                tokenType,
                recipientSigningService,
                org.unicitylabs.sdk.hash.HashAlgorithm.SHA256,
                salt
            )

            val recipientAddress = recipientPredicate.getReference().toAddress().toString()

            // Get sender identity
            val senderIdentity = getIdentityManager().getCurrentIdentity()
                ?: return@withContext Result.failure(Exception("No wallet identity found"))

            // Create offline transfer package
            val offlinePackage = sdkService.createOfflineTransfer(
                testToken.jsonData ?: "{}",
                recipientAddress,
                null, // Use full amount
                senderIdentity.privateKey.toByteArray(),
                senderIdentity.nonce.toByteArray()
            )

            if (offlinePackage == null) {
                return@withContext Result.failure(Exception("Failed to create offline package"))
            }

            // Complete the transfer (simulating receiver side)
            val receivedToken = sdkService.completeOfflineTransfer(
                offlinePackage,
                recipientSecret,
                recipientNonce
            )

            if (receivedToken != null) {
                // Remove the token from sender's wallet
                removeToken(testToken.id)

                Result.success(
                    "Offline transfer test successful!\n\n" +
                            "Token '${testToken.name}' was transferred offline and received successfully.\n" +
                            "Token has been removed from your wallet to simulate the transfer."
                )
            } else {
                Result.failure(
                    Exception("Failed to complete offline transfer")
                )
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun startPriceUpdates() {
        priceUpdateJob?.cancel()
        priceUpdateJob = viewModelScope.launch {
            while (true) {
                updateCryptoPrices()
                delay(60000) // Update every minute
            }
        }
    }

    private suspend fun updateCryptoPrices() {
        try {
            val prices = priceService.fetchPrices()
            val currentCryptos = _allCryptocurrencies.value

            if (currentCryptos.isNotEmpty()) {
                _allCryptocurrencies.value = currentCryptos.map { crypto ->
                    val priceData = prices[crypto.id]
                    if (priceData != null) {
                        crypto.copy(
                            priceUsd = priceData.priceUsd,
                            priceEur = priceData.priceEur,
                            change24h = priceData.change24h
                        )
                    } else {
                        crypto
                    }
                }
                Log.d("WalletViewModel", "Updated crypto prices from API/cache")
            }
        } catch (e: Exception) {
            Log.e("WalletViewModel", "Error updating prices: ${e.message}")
        }
    }

    fun refreshPrices() {
        viewModelScope.launch {
            try {
                val prices = priceService.fetchPrices(forceRefresh = true)
                val currentCryptos = _allCryptocurrencies.value

                if (currentCryptos.isNotEmpty()) {
                    _allCryptocurrencies.value = currentCryptos.map { crypto ->
                        val priceData = prices[crypto.id]
                        if (priceData != null) {
                            crypto.copy(
                                priceUsd = priceData.priceUsd,
                                priceEur = priceData.priceEur,
                                change24h = priceData.change24h
                            )
                        } else {
                            crypto
                        }
                    }
                    Log.d("WalletViewModel", "Force refreshed crypto prices")
                }
            } catch (e: Exception) {
                Log.e("WalletViewModel", "Error refreshing prices: ${e.message}")
            }
        }
    }

    fun markTokenAsBurned(sdkToken: org.unicitylabs.sdk.token.Token<*>) {
        viewModelScope.launch {
            try {
                // Find and mark the token as burned immediately
                val allTokens = repository.tokens.value
                val tokenToBurn = allTokens.find { walletToken ->
                    try {
                        walletToken.jsonData?.let { jsonData ->
                            val walletSdkToken = org.unicitylabs.sdk.token.Token.fromJson(
                                jsonData
                            )
                            walletSdkToken.id == sdkToken.id
                        } ?: false
                    } catch (e: Exception) {
                        false
                    }
                }

                tokenToBurn?.let { token ->
                    val burnedToken = token.copy(status = TokenStatus.BURNED)
                    repository.updateToken(burnedToken)
                    Log.d("WalletViewModel", "Marked token as BURNED: ${token.id}")
                }
            } catch (e: Exception) {
                Log.e("WalletViewModel", "Error marking token as burned", e)
            }
        }
    }

    fun addNewTokenFromSplit(sdkToken: org.unicitylabs.sdk.token.Token<*>, splitSourceTokenId: String? = null, splitSentAmount: BigInteger? = null) {
        viewModelScope.launch {
            try {
                Log.d("WalletViewModel", "=== addNewTokenFromSplit called ===")
                val tokenJson = sdkToken.toJson()

                // Extract coin info
                val coinsOpt = sdkToken.getCoins()
                if (!coinsOpt.isPresent) {
                    Log.e("WalletViewModel", "Split token has no coins!")
                    return@launch
                }

                val coinData = coinsOpt.get()
                val firstCoin = coinData.coins.entries.firstOrNull()
                if (firstCoin == null) {
                    Log.e("WalletViewModel", "Split token has no coin entries!")
                    return@launch
                }

                val coinIdBytes = firstCoin.key.bytes
                val coinIdHex = coinIdBytes.joinToString("") { "%02x".format(it) }
                val amount = firstCoin.value.toString() // BigInteger as String

                // Use actual SDK token ID (not random nonsense)
                val tokenIdHex = sdkToken.id.bytes.joinToString("") { "%02x".format(it) }

                Log.d("WalletViewModel", "Split token details:")
                Log.d("WalletViewModel", "  ID: ${tokenIdHex.take(16)}...")
                Log.d("WalletViewModel", "  Amount: $amount")
                Log.d("WalletViewModel", "  CoinId: $coinIdHex")

                // Get symbol and icon from registry
                val registry = UnicityTokenRegistry.getInstance(getApplication())
                val coinDef = registry.getCoinDefinition(coinIdHex)
                val symbol = coinDef?.symbol ?: "UNKNOWN"
                val iconUrl = coinDef?.getIconUrl()

                val walletToken = Token(
                    id = tokenIdHex, // Use actual token ID from SDK
                    name = symbol,
                    type = "FUNGIBLE",
                    coinId = coinIdHex,
                    amount = amount,
                    symbol = symbol,
                    iconUrl = iconUrl,
                    jsonData = tokenJson,
                    status = TokenStatus.CONFIRMED,
                    splitSourceTokenId = splitSourceTokenId,
                    splitSentAmount = splitSentAmount.toString()
                )

                repository.addToken(walletToken)
                Log.d("WalletViewModel", "✅ Added split token to wallet: $symbol amount=$amount id=${walletToken.id.take(16)}...")

                // Log current wallet state
                Log.d("WalletViewModel", "Current wallet tokens count: ${repository.tokens.value.size}")
                repository.tokens.value.forEach { token ->
                    Log.d("WalletViewModel", "  - ${token.symbol} ${token.amount} (status=${token.status}, id=${token.id.take(16)}...)")
                }
            } catch (e: Exception) {
                Log.e("WalletViewModel", "Error adding split token", e)
            }
        }
    }

    fun removeTokenAfterTransfer(sdkToken: org.unicitylabs.sdk.token.Token<*>) {
        viewModelScope.launch {
            try {
                // Find and mark the token as TRANSFERRED (for history)
                Log.d("WalletViewModel", "removeTokenAfterTransfer called for SDK token: ${sdkToken.id.bytes.joinToString("") { "%02x".format(it) }.take(16)}")
                val allTokens = repository.tokens.value
                Log.d("WalletViewModel", "Searching in ${allTokens.size} tokens")

                val tokenToTransfer = allTokens.find { walletToken ->
                    try {
                        walletToken.jsonData?.let { jsonData ->
                            val walletSdkToken = org.unicitylabs.sdk.token.Token.fromJson(jsonData)
                            val matches = walletSdkToken.id == sdkToken.id
                            if (matches) {
                                Log.d("WalletViewModel", "Found matching token: ${walletToken.id}")
                            }
                            matches
                        } ?: false
                    } catch (e: Exception) {
                        false
                    }
                }

                if (tokenToTransfer != null) {
                    val transferredToken = tokenToTransfer.copy(
                        status = TokenStatus.TRANSFERRED,
                        transferredAt = System.currentTimeMillis()
                    )
                    repository.updateToken(transferredToken)
                    Log.d("WalletViewModel", "✅ Marked token as TRANSFERRED: ${tokenToTransfer.id}, status=${transferredToken.status}, transferredAt=${transferredToken.transferredAt}")
                } else {
                    Log.e("WalletViewModel", "❌ Token not found in wallet for transfer marking!")
                }
            } catch (e: Exception) {
                Log.e("WalletViewModel", "Error marking token as transferred", e)
            }
        }
    }
}