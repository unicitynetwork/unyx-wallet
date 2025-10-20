package com.example.unicitywallet.transfer

import android.util.Log
import org.unicitylabs.sdk.token.Token
import org.unicitylabs.sdk.token.TokenId
import org.unicitylabs.sdk.token.fungible.CoinId
import java.math.BigInteger
import kotlin.collections.forEach

class TokenSplitCalculator {

    companion object {
        private const val TAG = "TokenSplitCalculator"
    }

    /**
     * Represents a token split plan
     */
    data class SplitPlan(
        val tokensToTransferDirectly: List<Token<*>>,    // Tokens to send as-is
        val tokenToSplit: Token<*>? = null,               // Token that needs splitting
        val splitAmount: BigInteger? = null,           // Amount to split off for transfer
        val remainderAmount: BigInteger? = null,       // Amount that stays with sender
        val totalTransferAmount: BigInteger,           // Total amount being transferred
        val coinId: CoinId                             // The coin type being transferred
    ) {
        val requiresSplit: Boolean = tokenToSplit != null

        fun describe(): String {
            val builder = StringBuilder()
            builder.append("Transfer Plan:\n")
            builder.append("- Total amount to transfer: $totalTransferAmount\n")

            if (tokensToTransferDirectly.isNotEmpty()) {
                builder.append("- Tokens to transfer directly: ${tokensToTransferDirectly.size}\n")
                tokensToTransferDirectly.forEach { token ->
                    val amount = token.getCoins().map { it.coins[coinId] }.orElse(null) ?: BigInteger.ZERO
                    builder.append("  * Token ${token.id.toHexString().take(8)}...: $amount\n")
                }
            }

            if (requiresSplit) {
                builder.append("- Token to split: ${tokenToSplit?.id?.toHexString()?.take(8)}...\n")
                builder.append("  * Split into: $splitAmount (transfer) + $remainderAmount (keep)\n")
            }

            return builder.toString()
        }
    }

    /**
     * Calculates the optimal token selection and splitting strategy
     *
     * @param availableTokens List of available tokens with the target coin
     * @param targetAmount Amount to transfer
     * @param coinId The coin type to transfer
     * @return SplitPlan describing the optimal strategy, or null if transfer is not possible
     */
    fun calculateOptimalSplit(
        availableTokens: List<Token<*>>,
        targetAmount: BigInteger,
        coinId: CoinId
    ): SplitPlan? {

        Log.d(TAG, "=== calculateOptimalSplit ===")
        Log.d(TAG, "Target amount: $targetAmount")
        Log.d(TAG, "CoinId: ${coinId.toString()}")
        Log.d(TAG, "Available tokens count: ${availableTokens.size}")

        if (targetAmount <= BigInteger.ZERO) {
            Log.e(TAG, "Invalid target amount: $targetAmount")
            return null
        }

        // Extract tokens with the specified coin and their amounts
        val tokenAmounts = availableTokens.mapNotNull { token ->
            val coins = token.getCoins()
            Log.d(TAG, "Checking token ${token.id.toHexString().take(8)}... - has coins: ${coins.isPresent}")

            if (coins.isPresent) {
                val coinData = coins.get()
                Log.d(TAG, "Token coins: ${coinData.coins.keys.map { it.toString() }}")

                val amount = coinData.coins[coinId]
                Log.d(TAG, "Amount for our coinId: $amount")

                if (amount != null && amount > BigInteger.ZERO) {
                    TokenWithAmount(token, amount)
                } else {
                    Log.d(TAG, "Token doesn't have our coin or amount is zero")
                    null
                }
            } else {
                Log.d(TAG, "Token has no coins at all")
                null
            }
        }.sortedBy { it.amount } // Sort by amount ascending

        Log.d(TAG, "Found ${tokenAmounts.size} tokens with the target coin")

        if (tokenAmounts.isEmpty()) {
            Log.e(TAG, "No tokens found with coin: $coinId")
            return null
        }

        // Calculate total available amount
        val totalAvailable = tokenAmounts.fold(BigInteger.ZERO) { acc, ta -> acc + ta.amount }
        if (totalAvailable < targetAmount) {
            Log.e(TAG, "Insufficient funds. Available: $totalAvailable, Required: $targetAmount")
            return null
        }

        // Strategy 1: Try to find exact match (no split needed)
        val exactMatch = tokenAmounts.find { it.amount == targetAmount }
        if (exactMatch != null) {
            Log.d(TAG, "Found exact match token")
            return SplitPlan(
                tokensToTransferDirectly = listOf(exactMatch.token),
                totalTransferAmount = targetAmount,
                coinId = coinId
            )
        }

        // Strategy 2: Try to find combination that sums to exact amount (no split needed)
        val exactCombination = findExactCombination(tokenAmounts, targetAmount)
        if (exactCombination != null) {
            Log.d(TAG, "Found exact combination of ${exactCombination.size} tokens")
            return SplitPlan(
                tokensToTransferDirectly = exactCombination.map { it.token },
                totalTransferAmount = targetAmount,
                coinId = coinId
            )
        }

        // Strategy 3: Find optimal split (minimize number of operations)
        return findOptimalSplitStrategy(tokenAmounts, targetAmount, coinId)
    }

    /**
     * Finds a combination of tokens that sum exactly to the target amount
     */
    private fun findExactCombination(
        tokens: List<TokenWithAmount>,
        targetAmount: BigInteger
    ): List<TokenWithAmount>? {
        // Use dynamic programming for subset sum problem
        // For simplicity and performance, limit search to combinations of up to 5 tokens
        val maxCombinationSize = minOf(5, tokens.size)

        for (size in 1..maxCombinationSize) {
            val combination = findCombinationOfSize(tokens, targetAmount, size)
            if (combination != null) {
                return combination
            }
        }
        return null
    }

    /**
     * Finds combination of specific size that sums to target
     */
    private fun findCombinationOfSize(
        tokens: List<TokenWithAmount>,
        targetAmount: BigInteger,
        size: Int
    ): List<TokenWithAmount>? {
        if (size == 1) {
            return tokens.find { it.amount == targetAmount }?.let { listOf(it) }
        }

        // Generate combinations recursively
        fun combinations(
            list: List<TokenWithAmount>,
            k: Int,
            start: Int = 0,
            current: List<TokenWithAmount> = emptyList()
        ): Sequence<List<TokenWithAmount>> = sequence {
            if (k == 0) {
                yield(current)
            } else {
                for (i in start until list.size) {
                    yieldAll(combinations(list, k - 1, i + 1, current + list[i]))
                }
            }
        }

        return combinations(tokens, size)
            .find { combo -> combo.fold(BigInteger.ZERO) { acc, ta -> acc + ta.amount } == targetAmount }
    }

    /**
     * Finds the optimal strategy when splitting is required
     */
    private fun findOptimalSplitStrategy(
        tokens: List<TokenWithAmount>,
        targetAmount: BigInteger,
        coinId: CoinId
    ): SplitPlan {
        // Sort tokens by amount (smallest first)
        val sortedTokens = tokens.sortedBy { it.amount }

        // Build up transfer amount by adding tokens until we need to split
        var accumulated = BigInteger.ZERO
        val tokensToTransferDirectly = mutableListOf<Token<*>>()

        for (tokenWithAmount in sortedTokens) {
            val newTotal = accumulated + tokenWithAmount.amount
            if (newTotal == targetAmount) {
                // Perfect match!
                tokensToTransferDirectly.add(tokenWithAmount.token)
                return SplitPlan(
                    tokensToTransferDirectly = tokensToTransferDirectly,
                    tokenToSplit = null,
                    splitAmount = null,
                    remainderAmount = null,
                    totalTransferAmount = targetAmount,
                    coinId = coinId
                )
            } else if (newTotal < targetAmount) {
                // Still need more - add this token and continue
                tokensToTransferDirectly.add(tokenWithAmount.token)
                accumulated = newTotal
            } else {
                // Adding this token would overshoot - split it
                val splitAmount = targetAmount - accumulated
                val remainderAmount = tokenWithAmount.amount - splitAmount

                return SplitPlan(
                    tokensToTransferDirectly = tokensToTransferDirectly,
                    tokenToSplit = tokenWithAmount.token,
                    splitAmount = splitAmount,
                    remainderAmount = remainderAmount,
                    totalTransferAmount = targetAmount,
                    coinId = coinId
                )
            }
        }

        // Should not reach here if validation passed
        throw IllegalStateException("Could not create split plan")
    }

    /**
     * Helper class to pair token with its amount for a specific coin
     */
    private data class TokenWithAmount(
        val token: Token<*>,
        val amount: BigInteger
    )
}

// Extension function to convert TokenId to hex string
fun TokenId.toHexString(): String {
    return this.bytes.joinToString("") { "%02x".format(it) }
}