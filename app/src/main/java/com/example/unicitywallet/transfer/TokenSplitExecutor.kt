package com.example.unicitywallet.transfer

import android.util.Log
import kotlinx.coroutines.future.await
import org.unicitylabs.sdk.StateTransitionClient
import org.unicitylabs.sdk.address.Address
import org.unicitylabs.sdk.api.SubmitCommitmentStatus
import org.unicitylabs.sdk.bft.RootTrustBase
import org.unicitylabs.sdk.hash.HashAlgorithm
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicateReference
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.Token
import org.unicitylabs.sdk.token.TokenId
import org.unicitylabs.sdk.token.TokenState
import org.unicitylabs.sdk.token.fungible.CoinId
import org.unicitylabs.sdk.token.fungible.TokenCoinData
import org.unicitylabs.sdk.transaction.split.TokenSplitBuilder
import org.unicitylabs.sdk.util.InclusionProofUtils
import java.math.BigInteger

class TokenSplitExecutor(
    private val client: StateTransitionClient,
    private val trustBase: RootTrustBase
) {

    companion object {
        private const val TAG = "TokenSplitExecutor"
    }

    data class SplitExecutionResult(
        val tokensForRecipient: List<Token<*>>,   // Source tokens (before transfer to recipient)
        val tokensKeptBySender: List<Token<*>>,   // New tokens kept by sender (from splits)
        val burnedTokens: List<Token<*>>,         // Original tokens that were burned
        val recipientTransferTxs: List<org.unicitylabs.sdk.transaction.Transaction<org.unicitylabs.sdk.transaction.TransferTransactionData>>  // Transfer transactions for recipient tokens
    )

    /**
     * Executes a token split plan
     *
     * @param plan The split plan to execute
     * @param recipientAddress Address of the recipient (can be ProxyAddress with nametag)
     * @param signingService Signing service for the sender's wallet
     * @param secret The wallet secret (needed for masked predicates)
     * @param onTokenBurned Callback invoked immediately after token is burned (for UI updates)
     * @return Result containing the new tokens created from the split
     */
    suspend fun executeSplitPlan(
        plan: TokenSplitCalculator.SplitPlan,
        recipientAddress: Address,
        signingService: SigningService,
        secret: ByteArray,
        onTokenBurned: ((Token<*>) -> Unit)? = null
    ): SplitExecutionResult {
        Log.d(TAG, "Executing split plan: ${plan.describe()}")

        val tokensForRecipient = mutableListOf<Token<*>>()
        val tokensKeptBySender = mutableListOf<Token<*>>()
        val burnedTokens = mutableListOf<Token<*>>()
        val recipientTransferTxs = mutableListOf<org.unicitylabs.sdk.transaction.Transaction<org.unicitylabs.sdk.transaction.TransferTransactionData>>()

        // NOTE: Direct transfer tokens (plan.tokensToTransferDirectly) are NOT handled here
        // They must be handled by the caller to create transfer commitments

        // Execute split if required
        if (plan.requiresSplit && plan.tokenToSplit != null) {
            val splitResult = executeSingleTokenSplit(
                tokenToSplit = plan.tokenToSplit,
                splitAmount = plan.splitAmount!!,
                remainderAmount = plan.remainderAmount!!,
                coinId = plan.coinId,
                recipientAddress = recipientAddress,
                signingService = signingService,
                onTokenBurned = onTokenBurned
            )

            tokensForRecipient.add(splitResult.tokenForRecipient)
            tokensKeptBySender.add(splitResult.tokenForSender)
            burnedTokens.add(plan.tokenToSplit)
            recipientTransferTxs.add(splitResult.recipientTransferTx)
        }

        return SplitExecutionResult(
            tokensForRecipient = tokensForRecipient,
            tokensKeptBySender = tokensKeptBySender,
            burnedTokens = burnedTokens,
            recipientTransferTxs = recipientTransferTxs
        )
    }

    /**
     * Executes a single token split operation
     */
    private suspend fun executeSingleTokenSplit(
        tokenToSplit: Token<*>,
        splitAmount: BigInteger,
        remainderAmount: BigInteger,
        coinId: CoinId,
        recipientAddress: Address,
        signingService: SigningService,
        onTokenBurned: ((Token<*>) -> Unit)?
    ): SplitTokenResult {
        Log.d(TAG, "Splitting token ${tokenToSplit.id.toHexString().take(8)}...")
        Log.d(TAG, "Split amounts: $splitAmount (recipient), $remainderAmount (sender)")

        // Create the token split builder
        val builder = TokenSplitBuilder()

        // Generate deterministic token IDs and salts (for retry safety)
        // Use token ID + amounts to ensure same split always generates same IDs
        val seedString = "${tokenToSplit.id.toHexString()}_${splitAmount}_${remainderAmount}"
        val seed = java.security.MessageDigest.getInstance("SHA-256").digest(seedString.toByteArray())

        val recipientTokenId = TokenId(seed.copyOfRange(0, 32))
        val senderTokenId = TokenId(java.security.MessageDigest.getInstance("SHA-256")
            .digest((seedString + "_sender").toByteArray())
            .copyOfRange(0, 32))

        val recipientSalt = java.security.MessageDigest.getInstance("SHA-256")
            .digest((seedString + "_recipient_salt").toByteArray())
        val senderSalt = java.security.MessageDigest.getInstance("SHA-256")
            .digest((seedString + "_sender_salt").toByteArray())

        // Create sender's address (used for BOTH split tokens initially)
        val senderAddress = UnmaskedPredicateReference.create(
            tokenToSplit.type,
            signingService,
            HashAlgorithm.SHA256
        ).toAddress()

        // Create token for recipient (initially minted to sender, will transfer later)
        builder.createToken(
            recipientTokenId,
            tokenToSplit.type,
            null, // No additional data
            TokenCoinData(mapOf(coinId to splitAmount)),
            senderAddress, // Mint to sender first, then transfer
            recipientSalt,
            null // No recipient data hash
        )

        // Create token for sender (remainder)

        builder.createToken(
            senderTokenId,
            tokenToSplit.type,
            null, // No additional data
            TokenCoinData(mapOf(coinId to remainderAmount)),
            senderAddress,
            senderSalt,
            null // No recipient data hash
        )

        // Build the split
        val split = builder.build(tokenToSplit)

        // Step 1: Create and submit burn commitment
        // Use deterministic burn salt for retry safety
        val burnSalt = java.security.MessageDigest.getInstance("SHA-256")
            .digest((seedString + "_burn_salt").toByteArray())

        // Use the same signing service that owns the token
        val burnCommitment = split.createBurnCommitment(burnSalt, signingService)

        Log.d(TAG, "Submitting burn commitment...")
        val burnResponse = client.submitCommitment(burnCommitment).await()

        // Handle already-burned tokens (resilience for retries)
        val burnAlreadyExists = burnResponse.status == SubmitCommitmentStatus.REQUEST_ID_EXISTS
        if (burnAlreadyExists) {
            Log.w(TAG, "Token already burned (REQUEST_ID_EXISTS) - attempting recovery...")
        } else if (burnResponse.status != SubmitCommitmentStatus.SUCCESS) {
            throw Exception("Failed to burn token: ${burnResponse.status}")
        } else {
            Log.d(TAG, "Token burned successfully")
        }

        // Mark token as burned immediately (before minting) for UI safety
        onTokenBurned?.invoke(tokenToSplit)
        Log.d(TAG, "Token marked as burned in wallet")

        // Wait for inclusion proof (works even if already burned)
        val burnInclusionProof = InclusionProofUtils.waitInclusionProof(
            client,
            trustBase,
            burnCommitment
        ).await()

        val burnTransaction = burnCommitment.toTransaction(burnInclusionProof)
        Log.d(TAG, "Got burn transaction with proof")

        // Step 2: Create and submit mint commitments for new tokens
        val mintCommitments = split.createSplitMintCommitments(trustBase, burnTransaction)

        Log.d(TAG, "Submitting ${mintCommitments.size} mint commitments...")
        val mintedTokens = mutableListOf<MintedTokenInfo>()

        for ((index, mintCommitment) in mintCommitments.withIndex()) {
            val response = client.submitCommitment(mintCommitment).await()

            // Handle already-minted tokens (resilience for retries)
            val mintAlreadyExists = response.status == SubmitCommitmentStatus.REQUEST_ID_EXISTS
            if (mintAlreadyExists) {
                Log.w(TAG, "Split token $index already minted (REQUEST_ID_EXISTS) - recovering...")
            } else if (response.status != SubmitCommitmentStatus.SUCCESS) {
                throw Exception("Failed to mint split token $index: ${response.status}")
            } else {
                Log.d(TAG, "Split token $index minted successfully")
            }

            // Wait for inclusion proof (works even if already minted)
            val inclusionProof = InclusionProofUtils.waitInclusionProof(
                client,
                trustBase,
                mintCommitment
            ).await()

            // Determine if this is for recipient based on tokenId (both minted to sender now)
            val isForRecipient = mintCommitment.transactionData.tokenId == recipientTokenId

            mintedTokens.add(
                MintedTokenInfo(
                    commitment = mintCommitment,
                    inclusionProof = inclusionProof,
                    isForRecipient = isForRecipient,
                    tokenId = mintCommitment.transactionData.tokenId,
                    salt = mintCommitment.transactionData.salt
                )
            )
        }

        Log.d(TAG, "All split tokens minted successfully")

        // Create Token objects for the minted tokens
        val recipientTokenInfo = mintedTokens.find { it.isForRecipient }
            ?: throw Exception("Recipient token not found in minted tokens")
        val senderTokenInfo = mintedTokens.find { !it.isForRecipient }
            ?: throw Exception("Sender token not found in minted tokens")

        // Create and verify both split tokens (both owned by sender)
        val recipientTokenBeforeTransfer = createAndVerifySplitToken(recipientTokenInfo, signingService, "recipient (before transfer)")
        val senderToken = createAndVerifySplitToken(senderTokenInfo, signingService, "sender")

        // Step 3: Transfer recipient token to recipient's ProxyAddress
        Log.d(TAG, "Transferring recipient token to ${recipientAddress.address}...")
        val transferSalt = ByteArray(32)
        java.security.SecureRandom().nextBytes(transferSalt)

        val transferCommitment = org.unicitylabs.sdk.transaction.TransferCommitment.create(
            recipientTokenBeforeTransfer,
            recipientAddress,
            transferSalt,
            null,
            null,
            signingService
        )

        val transferResponse = client.submitCommitment(transferCommitment).await()
        if (transferResponse.status != SubmitCommitmentStatus.SUCCESS &&
            transferResponse.status != SubmitCommitmentStatus.REQUEST_ID_EXISTS) {
            throw Exception("Failed to transfer recipient token: ${transferResponse.status}")
        }

        val transferInclusionProof = InclusionProofUtils.waitInclusionProof(
            client,
            trustBase,
            transferCommitment
        ).await()

        val transferTransaction = transferCommitment.toTransaction(transferInclusionProof)

        Log.d(TAG, "Recipient token transferred successfully")

        return SplitTokenResult(
            tokenForRecipient = recipientTokenBeforeTransfer,
            tokenForSender = senderToken,
            recipientTransferTx = transferTransaction
        )
    }

    /**
     * Creates and verifies a split token
     *
     * Tokens are created using the Token constructor then explicitly verified
     * to ensure they are valid before being added to the wallet or sent.
     */
    private fun createAndVerifySplitToken(
        mintInfo: MintedTokenInfo,
        signingService: SigningService,
        tokenType: String
    ): Token<*> {
        val state = TokenState(
            UnmaskedPredicate.create(
                mintInfo.commitment.transactionData.tokenId,
                mintInfo.commitment.transactionData.tokenType,
                signingService,
                HashAlgorithm.SHA256,
                mintInfo.commitment.transactionData.salt
            ),
            null
        )

        // Create token with constructor
        val token = Token(
            state,
            mintInfo.commitment.toTransaction(mintInfo.inclusionProof),
            emptyList(),
            emptyList()
        )

        // Explicitly verify the token before returning
        val verifyResult = token.verify(trustBase)
        if (!verifyResult.isSuccessful) {
            Log.e(TAG, "===== Split token verification FAILED for $tokenType =====")
            Log.e(TAG, "Full verification result: ${verifyResult.toString()}")
            Log.e(TAG, "TokenId: ${mintInfo.tokenId.toHexString()}")
            Log.e(TAG, "Commitment data: tokenId=${mintInfo.commitment.transactionData.tokenId}, salt=${mintInfo.commitment.transactionData.salt.joinToString("") { "%02x".format(it) }}")
            throw Exception("Split token verification failed for $tokenType. Check logs for details: ${verifyResult.toString()}")
        }

        Log.d(TAG, "Split token created and verified: ${mintInfo.tokenId.toHexString().take(8)}... ($tokenType)")
        return token
    }

    /**
     * Helper class to track minted token information
     */
    private data class MintedTokenInfo(
        val commitment: org.unicitylabs.sdk.transaction.MintCommitment<*>,
        val inclusionProof: org.unicitylabs.sdk.transaction.InclusionProof,
        val isForRecipient: Boolean,
        val tokenId: TokenId,
        val salt: ByteArray
    )

    /**
     * Result of a single token split
     */
    data class SplitTokenResult(
        val tokenForRecipient: Token<*>,  // Source token (before transfer to recipient)
        val tokenForSender: Token<*>,
        val recipientTransferTx: org.unicitylabs.sdk.transaction.Transaction<org.unicitylabs.sdk.transaction.TransferTransactionData>  // Transfer transaction to recipient
    )
}