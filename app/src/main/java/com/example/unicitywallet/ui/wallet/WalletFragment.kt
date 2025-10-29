package com.example.unicitywallet.ui.wallet

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.VIBRATOR_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat.getColor
import androidx.fragment.app.viewModels
import androidx.fragment.app.Fragment
import com.example.unicitywallet.R
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.unicitywallet.data.model.AggregatedAsset
import com.example.unicitywallet.data.model.Contact
import com.example.unicitywallet.data.model.Token
import com.example.unicitywallet.databinding.FragmentWalletBinding
import com.example.unicitywallet.identity.IdentityManager
import com.example.unicitywallet.nostr.NostrP2PService
import com.example.unicitywallet.services.ServiceProvider
import com.example.unicitywallet.transfer.TokenSplitCalculator
import com.example.unicitywallet.transfer.TokenSplitExecutor
import com.example.unicitywallet.transfer.toHexString
import com.example.unicitywallet.viewmodel.WalletViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unicitylabs.sdk.address.ProxyAddress
import org.unicitylabs.sdk.api.SubmitCommitmentStatus
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.TokenId
import org.unicitylabs.sdk.transaction.TransferCommitment
import org.unicitylabs.sdk.util.InclusionProofUtils
import kotlin.getValue

class WalletFragment : Fragment(R.layout.fragment_wallet) {

    private lateinit var binding: FragmentWalletBinding
    private lateinit var tokensAdapter: TokensAdapter
    private lateinit var assetsAdapter: AssetsAdapter
    private lateinit var transferDetailsText: TextView
    private lateinit var confettiContainer: FrameLayout
    private var dialogDismissRunnable: Runnable? = null
    private val dialogHandler = Handler(Looper.getMainLooper())
    private var selectedCurrency = "USD"
    private val viewModel: WalletViewModel by viewModels()
    private var selectedIndex = 0

    private var currentContactDialog: ContactListDialog? = null

    private var currentNametagString: String? = null

    // Crypto received receiver (for demo crypto transfers via Nostr)
    private val cryptoReceivedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "org.unicitylabs.wallet.ACTION_CRYPTO_RECEIVED") {
                val cryptoId = intent.getStringExtra("crypto_id") ?: return
                val amount = intent.getDoubleExtra("amount", 0.0)
                val symbol = intent.getStringExtra("crypto_symbol") ?: ""

                Log.d("WalletFragment", "Crypto received broadcast: $amount $symbol (id: $cryptoId)")

                // Update the cryptocurrency balance
                val currentCryptos = viewModel.cryptocurrencies.value ?: emptyList()
                val crypto = currentCryptos.find { it.id == cryptoId }

                if (crypto != null) {
                    val newBalance = crypto.balance + amount
                    Log.d("WalletFragment", "Updating ${crypto.symbol} balance: ${crypto.balance} + $amount = $newBalance")
                    viewModel.updateCryptoBalance(cryptoId, newBalance)

                    requireActivity().runOnUiThread {
                        // Format amount nicely
                        val formattedAmount = if (amount % 1 == 0.0) {
                            amount.toInt().toString()
                        } else {
                            String.format("%.8f", amount).trimEnd('0').trimEnd('.')
                        }

                        showSuccessDialog("Received $formattedAmount $symbol!")
                    }
                } else {
                    Log.w("WalletFragment", "Crypto $cryptoId not found in local wallet")
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentWalletBinding.bind(view)

        startNostrService()

        setupUI()
        setupRecycler()
        setupTabs()
        setupSwipeRefresh()
        setupSuccessDialog()

        observeViewModel()

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            cryptoReceivedReceiver,
            IntentFilter("org.unicitylabs.wallet.ACTION_CRYPTO_RECEIVED")
        )
    }

    private fun setupUI(){
        val prefs = requireContext()
            .getSharedPreferences("UnicityWalletPrefs", Context.MODE_PRIVATE)
        currentNametagString = prefs.getString("unicity_tag", null)

        binding.tvNametag.text = currentNametagString
        binding.btnSend.setOnClickListener {
            if(selectedIndex == 0) {
                val assets = viewModel.aggregatedAssets.value
                if(assets.isNotEmpty()){
                    showSendDialog()
                } else {
                    Toast.makeText(requireContext(), "No assets to transfer", Toast.LENGTH_SHORT).show()
                }
            } else {
                val tokens = viewModel.tokens.value
                if (tokens.isNotEmpty()) {
                    Toast.makeText(requireContext(), "Select a token from the list to transfer", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "No tokens to transfer", Toast.LENGTH_SHORT).show()
                }
            }
        }

        updateBalanceDisplay()
    }

    private fun setupRecycler(){
        tokensAdapter = TokensAdapter(
            onSendClick = { token ->
                showTokenSendMethodDialog(token)
            }
        )
        assetsAdapter = AssetsAdapter(selectedCurrency)
        binding.rvAssets.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = assetsAdapter
        }
    }

    private fun setupTabs(){
        val tabSection = binding.tabSection

        tabSection.post {
            moveIndicatorTo(0, animate = false)
            updateListDisplay()
        }

        binding.btnTokens.setOnClickListener {
            if(selectedIndex != 0) {
                selectedIndex = 0
                moveIndicatorTo(0)
                updateTabColors()
                updateListDisplay()
            }
        }

        binding.btnCollectibles.setOnClickListener {
            if(selectedIndex != 1){
                selectedIndex = 1
                moveIndicatorTo(1)
                updateTabColors()
                updateListDisplay()
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.apply {
            setColorSchemeColors(
                getColor(requireContext(), R.color.primary_blue),
                getColor(requireContext(), R.color.green_positive),
                getColor(requireContext(), R.color.purple_accent)
            )
            setOnRefreshListener {
                refreshWallet()
            }
        }
    }

    private fun setupSuccessDialog() {
        try {
            confettiContainer = binding.confettiOverlay.confettiContainer
            transferDetailsText = binding.confettiOverlay.transferDetailsText

            // Initially hide dialog
            confettiContainer.visibility = View.GONE

            // Set up tap to dismiss
            confettiContainer.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    dismissSuccessDialog()
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("WalletFragment", "Error setting up success dialog", e)
        }
    }

    private fun updateListDisplay(){
        if(selectedIndex == 0){
            Log.d("WalletFragment", "Im updating views")
            binding.rvAssets.adapter = assetsAdapter
            val assets = viewModel.aggregatedAssets.value
            assetsAdapter.submitList(assets)
            binding.emptyStateContainer.visibility = if(assets.isEmpty()) View.VISIBLE else View.GONE
        } else {
            binding.rvAssets.adapter = tokensAdapter
            val tokens = viewModel.tokens.value
            tokensAdapter.submitList(tokens)
            binding.emptyStateContainer.visibility = if (tokens.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun moveIndicatorTo(index: Int, animate: Boolean = true) {
        val parent = view?.findViewById<FrameLayout>(R.id.tabSection) ?: return
        val tabWidth = parent.width / 2f
        val targetX = tabWidth * index

        val params = binding.activeTabBg.layoutParams
        params.width = tabWidth.toInt()
        binding.activeTabBg.layoutParams = params

        if (animate) {
            binding.activeTabBg.animate()
                .x(targetX)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator(0.3f))
                .start()
        } else {
            binding.activeTabBg.x = targetX
        }
    }

    private fun observeViewModel(){

        lifecycleScope.launch {
            viewModel.tokens.collect { tokens ->
                Log.d("WalletFragment", "=== Tokens collected: ${tokens.size} tokens, currentTab=$selectedIndex ===")
                tokens.forEach { token ->
                    Log.d("WalletFragment", "Token: ${token.name} (${token.type})")
                }

                // Always update the adapter so new tokens appear immediately
                Log.d("WalletFragment", "Updating Tokens adapter with ${tokens.size} tokens")
                tokensAdapter.submitList(tokens.toList()) // toList() creates new instance to trigger DiffUtil

                // Update visibility if we're on the tokens tab
                if (selectedIndex == 1) {
                    binding.emptyStateContainer.visibility = if (tokens.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
        // Observe aggregated assets for Assets tab
        lifecycleScope.launch {
            viewModel.aggregatedAssets.collect { assets ->
                if (selectedIndex == 0) {
                    assetsAdapter.submitList(assets)
                    binding.emptyStateContainer.visibility = if (assets.isEmpty()) View.VISIBLE else View.GONE
                }
                updateBalanceDisplay()
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                // Stop swipe refresh when loading is complete
                if (!isLoading) {
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    private fun updateTabColors() {
        val tokensText = binding.btnTokens.getChildAt(0) as TextView
        val collectiblesText = binding.btnCollectibles.getChildAt(0) as TextView

        if (selectedIndex == 0) {
            tokensText.setTextColor("#262626".toColorInt())
            collectiblesText.setTextColor("#75FFFFFF".toColorInt())
        } else {
            tokensText.setTextColor("#75FFFFFF".toColorInt())
            collectiblesText.setTextColor("#262626".toColorInt())
        }
    }

    private fun showSendDialog() {
        // First show contact list
        showContactListDialog()
    }

    private fun showTokenSendMethodDialog(token: Token) {
        currentContactDialog = ContactListDialog(
            context = requireContext(),
            onContactSelected = { selectedContact ->
                if (selectedContact.hasUnicityTag()) {
                    // Get asset info for this token
                    val asset = viewModel.aggregatedAssets.value.find { it.coinId == token.coinId }
                    if (asset != null) {
                        sendTokenViaNostr(token, selectedContact)
                    } else {
                        Toast.makeText(requireContext(), "Asset not found", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Cannot Send")
                        .setMessage("This contact doesn't have a @unicity nametag. Transfers require @unicity nametags.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        )
        currentContactDialog?.show()
    }

    private fun showContactListDialog() {
        currentContactDialog = ContactListDialog(
            context = requireContext(),
            onContactSelected = { selectedContact ->
                // Check if contact has @unicity tag
                if (selectedContact.hasUnicityTag()) {
                    // After contact is selected, show asset selection dialog
                    showCryptoSelectionForRecipient(selectedContact)
                } else {
                    // Show warning for non-@unicity contacts
                    AlertDialog.Builder(requireContext())
                        .setTitle("Cannot Send")
                        .setMessage("Unknown @unicity tag. You can only send to contacts with @unicity tag.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            },
            onRequestPermission = { permission, requestCode ->
                // Request the permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(arrayOf(permission), requestCode)
                }
            }
        )
        currentContactDialog?.show()
    }

    private fun showCryptoSelectionForRecipient(recipient: Contact) {
        val assets = viewModel.aggregatedAssets.value

        if (assets.isEmpty()) {
            Toast.makeText(requireContext(), "No assets available", Toast.LENGTH_SHORT).show()
            return
        }

        // If only one asset, skip picker and go to next step
        if (assets.size == 1) {
            showAssetSendAmountDialog(assets[0], recipient)
            return
        }

        // Create custom dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_asset, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvAssets)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val dialog = Dialog(requireContext(), R.style.FullScreenDialog)
        dialog.setContentView(dialogView)

        val adapter = AggregatedAssetDialogAdapter(assets) { selectedAsset ->
            showAssetSendAmountDialog(selectedAsset, recipient)

            Handler(Looper.getMainLooper()).postDelayed({
                dialog.dismiss()
            }, 50)
        }

        recyclerView.adapter = adapter

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAssetSendAmountDialog(asset: AggregatedAsset, selectedContact: Contact) {
        // Get all tokens for this coinId
        Log.d("WalletFragment", "Here is an asset to send ${asset} and coinId ${asset.coinId}")
        val tokensForCoin = viewModel.getTokensByCoinId(asset.coinId)

        if (tokensForCoin.isEmpty()) {
            Toast.makeText(requireContext(), "No tokens available for ${asset.symbol}", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_amount_input, null)
        val etAmount = dialogView.findViewById<EditText>(R.id.etAmount)
        val tvBalance = dialogView.findViewById<TextView>(R.id.tvBalance)
        val btnSend = dialogView.findViewById<Button>(R.id.btnSend)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val formattedBalance = asset.getFormattedAmount()
        tvBalance.text = "Balance: $formattedBalance ${asset.symbol}"

        val dialog = Dialog(requireContext(), R.style.FullScreenDialog)
        dialog.setContentView(dialogView)

        btnSend.setOnClickListener {
            val amountStr = etAmount.text.toString()
            if(amountStr.isNotEmpty()){
                try {
                    val amountDecimal = java.math.BigDecimal(amountStr)
                    val multiplier = java.math.BigDecimal.TEN.pow(asset.decimals)
                    val amountInSmallestUnitBD = amountDecimal.multiply(multiplier)
                    val amountInSmallestUnit = amountInSmallestUnitBD.toBigInteger()

                    if (amountInSmallestUnit <= java.math.BigInteger.ZERO) {
                        Toast.makeText(requireContext(), "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val totalBalanceBigInt = asset.getAmountAsBigInteger()
                    if (amountInSmallestUnit > totalBalanceBigInt) {
                        Toast.makeText(requireContext(), "Insufficient balance", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    sendTokensWithSplitting(
                        tokensForCoin = tokensForCoin,
                        targetAmount = amountInSmallestUnit,
                        asset = asset,
                        recipient = selectedContact
                    )

                    dialog.dismiss()
                } catch (e: NumberFormatException) {
                    Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

    }

    private fun sendTokensWithSplitting(
        tokensForCoin: List<Token>,
        targetAmount: java.math.BigInteger,
        asset: AggregatedAsset,
        recipient: Contact
    ) {
        lifecycleScope.launch {
            // Show progress dialog (outside try so it's always accessible for dismiss)
            val progressDialog = android.app.ProgressDialog(requireContext()).apply {
                setTitle("Processing Transfer")
                setMessage("Calculating optimal transfer strategy...")
                setCancelable(false)
                show()
            }

            try {

                // Step 1: Calculate optimal split plan
                val calculator = TokenSplitCalculator()

                Log.d("WalletFragment", "=== Starting split calculation ===")
                Log.d("WalletFragment", "Target amount: $targetAmount")
                Log.d("WalletFragment", "Asset coinId (hex string): ${asset.coinId}")
                Log.d("WalletFragment", "Available tokens: ${tokensForCoin.size}")

                // Convert hex string coinId to bytes (not UTF-8 bytes of the string!)
                val coinId = org.unicitylabs.sdk.token.fungible.CoinId(hexStringToByteArray(asset.coinId))
                Log.d("WalletFragment", "CoinId bytes: ${coinId.bytes.joinToString { it.toString() }}")

                // Convert wallet tokens to SDK tokens
                val sdkTokens = tokensForCoin.mapNotNull { token ->
                    try {
                        val sdkToken = UnicityObjectMapper.JSON.readValue(
                            token.jsonData,
                            org.unicitylabs.sdk.token.Token::class.java
                        )
                        Log.d("WalletFragment", "Parsed SDK token: id=${sdkToken.id.toHexString().take(8)}...")
                        val coins = sdkToken.getCoins()
                        if (coins.isPresent) {
                            Log.d("WalletFragment", "Token has coins: ${coins.get().coins}")
                        } else {
                            Log.d("WalletFragment", "Token has NO coins!")
                        }
                        sdkToken
                    } catch (e: Exception) {
                        Log.e("WalletFragment", "Failed to parse token: ${e.message}", e)
                        null
                    }
                }

                Log.d("WalletFragment", "Successfully parsed ${sdkTokens.size} SDK tokens")

                // Calculate total available balance
                val totalAvailable = sdkTokens.mapNotNull { token ->
                    val coins = token.getCoins()
                    if (coins.isPresent) {
                        coins.get().coins[coinId]
                    } else null
                }.fold(java.math.BigInteger.ZERO) { acc, amount -> acc.add(amount) }

                Log.d("WalletFragment", "Total available: $totalAvailable, requested: $targetAmount")

                // Check if insufficient balance BEFORE calling calculator
                if (totalAvailable < targetAmount) {
                    progressDialog.dismiss()
                    val availableDecimal = java.math.BigDecimal(totalAvailable).divide(java.math.BigDecimal.TEN.pow(asset.decimals)).stripTrailingZeros()
                    val requestedDecimal = java.math.BigDecimal(targetAmount).divide(java.math.BigDecimal.TEN.pow(asset.decimals)).stripTrailingZeros()
                    val message = "Insufficient balance!\n\nRequested: $requestedDecimal ${asset.symbol}\nAvailable: $availableDecimal ${asset.symbol}"
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    Log.e("WalletFragment", "Insufficient balance: requested=$requestedDecimal, available=$availableDecimal")
                    return@launch
                }

                val splitPlan = calculator.calculateOptimalSplit(sdkTokens, targetAmount, coinId)

                if (splitPlan == null) {
                    progressDialog.dismiss()
                    Log.e("WalletFragment", "Split calculator returned null - unexpected!")
                    Toast.makeText(requireContext(), "Cannot create transfer plan. This should not happen - check logs.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.d("WalletFragment", "Split plan created: ${splitPlan.describe()}")

                // Step 2: Extract recipient nametag
                val recipientNametag = recipient.address
                    ?.removePrefix("@")
                    ?.removeSuffix("@unicity")
                    ?.trim()

                if (recipientNametag.isNullOrEmpty()) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), "Invalid recipient nametag", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Step 3: Create recipient proxy address
                val recipientTokenId = TokenId.fromNameTag(recipientNametag)
                val recipientProxyAddress = ProxyAddress.create(recipientTokenId)

                // Step 4: Get signing service
                val identityManager = IdentityManager(requireContext())
                val identity = identityManager.getCurrentIdentity()
                if (identity == null) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), "Wallet identity not found", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val secret = hexStringToByteArray(identity.privateKey)
                val signingService = SigningService.createFromSecret(secret)

                // Step 5: Execute split if needed
//                val tokensToTransfer = mutableListOf<org.unicitylabs.sdk.token.Token<*>>()
                var successCount = 0 // Track successful regular transfers
                var splitResult: TokenSplitExecutor.SplitExecutionResult? = null

                // Get Nostr service early (needed for both paths)
                val nostrService = NostrP2PService.getInstance(requireContext().applicationContext)
                if (nostrService == null) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), "Nostr service not available", Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (!nostrService.isRunning()) {
                    nostrService.start()
                    kotlinx.coroutines.delay(2000)
                }

                val recipientPubkey = nostrService.queryPubkeyByNametag(recipientNametag)
                if (recipientPubkey == null) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), "Recipient not found", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Step 5a: Execute split if needed
                if (splitPlan.requiresSplit) {
                    progressDialog.setMessage("Executing token split...")

                    val executor = TokenSplitExecutor(
                        ServiceProvider.stateTransitionClient,
                        ServiceProvider.getRootTrustBase()
                    )

                    splitResult = executor.executeSplitPlan(
                        splitPlan,
                        recipientProxyAddress,
                        signingService,
                        secret,
                        onTokenBurned = { burnedToken ->
                            // Mark token as burned immediately so it disappears from UI
                            viewModel.markTokenAsBurned(burnedToken)
                        }
                    )

                    // Update local wallet with new sender tokens
                    splitResult.tokensKeptBySender.forEach { newToken ->
                        viewModel.addNewTokenFromSplit(newToken)
                    }
                }

                // Step 5b: Create transfer transactions for direct tokens (if any)
                // These need to be transferred whether or not a split happened
                if (splitPlan.tokensToTransferDirectly.isNotEmpty()) {
                    progressDialog.setMessage("Creating transfer transactions...")

                    for (tokenToTransfer in splitPlan.tokensToTransferDirectly) {
                        val salt = ByteArray(32)
                        java.security.SecureRandom().nextBytes(salt)

                        Log.d("WalletFragment", "Creating transfer commitment:")
                        Log.d("WalletFragment", "  Token: ${tokenToTransfer.id.bytes.joinToString("") { "%02x".format(it) }.take(16)}...")
                        Log.d("WalletFragment", "  Recipient ProxyAddress: ${recipientProxyAddress.address}")
                        Log.d("WalletFragment", "  RecipientPredicateHash: null (proxy transfer)")
                        Log.d("WalletFragment", "  RecipientDataHash: null")

                        val transferCommitment = withContext(Dispatchers.IO) {
                            TransferCommitment.create(
                                tokenToTransfer,
                                recipientProxyAddress,
                                salt,
                                null,  // recipientPredicateHash - null for proxy transfers
                                null,  // recipientDataHash
                                signingService
                            )
                        }

                        Log.d("WalletFragment", "Transfer commitment created successfully")

                        val client = ServiceProvider.stateTransitionClient
                        val response = withContext(Dispatchers.IO) {
                            client.submitCommitment(transferCommitment).get()
                        }

                        if (response.status == SubmitCommitmentStatus.SUCCESS) {
                            val inclusionProof = withContext(Dispatchers.IO) {
                                val trustBase = ServiceProvider.getRootTrustBase()
                                InclusionProofUtils.waitInclusionProof(
                                    client,
                                    trustBase,
                                    transferCommitment
                                ).get(30, java.util.concurrent.TimeUnit.SECONDS)
                            }

                            val transferTransaction = transferCommitment.toTransaction(inclusionProof)

                            // Create transfer package (like old sendTokenViaNostr)
                            val sourceTokenJson = UnicityObjectMapper.JSON.writeValueAsString(tokenToTransfer)
                            val transferTxJson = UnicityObjectMapper.JSON.writeValueAsString(transferTransaction)

                            val payload = mapOf(
                                "sourceToken" to sourceTokenJson,
                                "transferTx" to transferTxJson
                            )
                            val payloadJson = UnicityObjectMapper.JSON.writeValueAsString(payload)
                            val transferPackage = "token_transfer:$payloadJson"

                            Log.d("WalletFragment", "Transfer payload size: ${transferPackage.length / 1024}KB")

                            // Send via sendDirectMessage (not sendTokenTransfer) for proper format
                            val sent = try {
                                nostrService.sendDirectMessage(recipientPubkey!!, transferPackage)
                            } catch (e: Exception) {
                                Log.e("WalletFragment", "Failed to send token: ${e.message}", e)
                                false
                            }

                            if (sent) {
                                successCount++
                                // Only remove from wallet if send succeeded
                                // Note: This doesn't guarantee relay accepted it (size limits, etc)
                                // TODO: Wait for relay OK response before removing
                                viewModel.removeTokenAfterTransfer(tokenToTransfer)
                            } else {
                                Log.w("WalletFragment", "Token NOT removed from wallet - send failed")
                            }
                        } else {
                            Log.e("WalletFragment", "Failed to transfer token: ${response.status}")
                        }
                    }
                }

                // Step 6: Send SPLIT tokens via Nostr (if any)
                // Regular tokens already sent in the else block above
                if (splitPlan.requiresSplit && splitResult != null) {
                    progressDialog.setMessage("Sending split tokens to recipient...")

                    val nostrService = NostrP2PService.getInstance(requireContext().applicationContext)
                    if (nostrService != null && nostrService.isRunning()) {
                        // For split tokens, we need to send sourceToken + transferTx (same as regular transfers)
                        // The splitResult has parallel lists: tokensForRecipient and recipientTransferTxs
                        for ((index, sourceToken) in splitResult.tokensForRecipient.withIndex()) {
                            try {
                                val transferTx = splitResult.recipientTransferTxs[index]

                                Log.d("WalletFragment", "Sending split token ${sourceToken.id.toHexString().take(8)}... via Nostr")

                                val sourceTokenJson = UnicityObjectMapper.JSON.writeValueAsString(sourceToken)
                                val transferTxJson = UnicityObjectMapper.JSON.writeValueAsString(transferTx)

                                val payload = mapOf(
                                    "sourceToken" to sourceTokenJson,
                                    "transferTx" to transferTxJson
                                )
                                val payloadJson = UnicityObjectMapper.JSON.writeValueAsString(payload)
                                val transferPackage = "token_transfer:$payloadJson"

                                Log.d("WalletFragment", "Split token transfer payload size: ${transferPackage.length / 1024}KB")

                                val sent = nostrService.sendDirectMessage(recipientPubkey!!, transferPackage)
                                if (!sent) {
                                    Log.e("WalletFragment", "Failed to send split token via Nostr")
                                }
                            } catch (e: Exception) {
                                Log.e("WalletFragment", "Failed to send split token", e)
                            }
                        }
                    }
                }

                progressDialog.dismiss()

                // Calculate total sent: direct transfers + split tokens
                val totalSent = successCount + (splitResult?.tokensForRecipient?.size ?: 0)

                if (totalSent > 0) {
                    vibrateSuccess()
                    val formattedAmount = targetAmount.toDouble() / Math.pow(10.0, asset.decimals.toDouble())
                    showSuccessDialog("Successfully sent $formattedAmount ${asset.symbol} to ${recipient.name}")
                } else {
                    Toast.makeText(requireContext(), "Transfer failed", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e("WalletFragment", "Error in sendTokensWithSplitting", e)
                Toast.makeText(requireContext(), "Transfer error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun vibrateSuccess() {
        try {
            val vibrator = requireContext().getSystemService(VIBRATOR_SERVICE) as? Vibrator
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Double pulse for success
                    val pattern = longArrayOf(0, 100, 100, 100)
                    it.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(longArrayOf(0, 100, 100, 100), -1)
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to vibrate: ${e.message}")
        }
    }

    private fun sendTokenViaNostr(token: Token, recipient: Contact) {
        lifecycleScope.launch {
            try {
                Toast.makeText(requireContext(), "Sending to ${recipient.name}...", Toast.LENGTH_SHORT).show()

                // Step 1: Extract recipient nametag from contact
                val recipientNametag = recipient.address
                    ?.removePrefix("@")
                    ?.removeSuffix("@unicity")
                    ?.trim()

                if (recipientNametag.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "Invalid recipient nametag", Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.d("WalletFragment", "Starting transfer to nametag: $recipientNametag")

                // Step 2: Query recipient's Nostr pubkey
                val nostrService = NostrP2PService.getInstance(requireContext().applicationContext)
                if (nostrService == null) {
                    Toast.makeText(requireContext(), "Nostr service not available", Toast.LENGTH_LONG).show()
                    return@launch
                }

                if (!nostrService.isRunning()) {
                    nostrService.start()
                    kotlinx.coroutines.delay(2000)
                }

                val recipientPubkey = nostrService.queryPubkeyByNametag(recipientNametag)
                if (recipientPubkey == null) {
                    Toast.makeText(requireContext(), "Recipient not found", Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.d("WalletFragment", "Found recipient pubkey: ${recipientPubkey.take(16)}...")

                // Step 3: Create proxy address from recipient nametag
                val recipientTokenId = TokenId.fromNameTag(recipientNametag)
                val recipientProxyAddress = ProxyAddress.create(recipientTokenId)

                Log.d("WalletFragment", "Recipient proxy address: ${recipientProxyAddress.address}")

                // Step 4: Parse token and create transfer
                val sourceToken = UnicityObjectMapper.JSON.readValue(
                    token.jsonData,
                    org.unicitylabs.sdk.token.Token::class.java
                )

                val identityManager = IdentityManager(requireContext())
                val identity = identityManager.getCurrentIdentity()
                if (identity == null) {
                    Toast.makeText(requireContext(), "Wallet identity not found", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val secret = hexStringToByteArray(identity.privateKey)
                val signingService = SigningService.createFromSecret(secret)

                val salt = ByteArray(32)
                java.security.SecureRandom().nextBytes(salt)

                // Step 5-7: Create transfer, submit, wait for proof (no toasts - fast)
                val transferCommitment = withContext(Dispatchers.IO) {
                    TransferCommitment.create(
                        sourceToken,
                        recipientProxyAddress,
                        salt,
                        null,
                        null,
                        signingService
                    )
                }

                val client = ServiceProvider.stateTransitionClient
                val response = withContext(Dispatchers.IO) {
                    client.submitCommitment(transferCommitment).get()
                }

                if (response.status != SubmitCommitmentStatus.SUCCESS) {
                    Toast.makeText(requireContext(), "Transfer failed: ${response.status}", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val inclusionProof = withContext(Dispatchers.IO) {
                    val trustBase = ServiceProvider.getRootTrustBase()
                    InclusionProofUtils.waitInclusionProof(
                        client,
                        trustBase,
                        transferCommitment
                    ).get(30, java.util.concurrent.TimeUnit.SECONDS)
                }

                // Step 8-9: Create transfer package
                val transferTransaction = transferCommitment.toTransaction(inclusionProof)
                val sourceTokenJson = UnicityObjectMapper.JSON.writeValueAsString(sourceToken)
                val transferTxJson = UnicityObjectMapper.JSON.writeValueAsString(transferTransaction)

                val payload = mapOf(
                    "sourceToken" to sourceTokenJson,
                    "transferTx" to transferTxJson
                )
                val payloadJson = UnicityObjectMapper.JSON.writeValueAsString(payload)
                val transferPackage = "token_transfer:$payloadJson"

                Log.d("WalletFragment", "Transfer package created (${transferPackage.length} chars)")

                // Step 10: Send via Nostr
                val sent = nostrService.sendDirectMessage(recipientPubkey, transferPackage)

                if (sent) {
                    Toast.makeText(requireContext(), "✅ Sent to ${recipient.name}!", Toast.LENGTH_SHORT).show()
                    Log.i("WalletFragment", "Transfer completed to $recipientNametag")

                    viewModel.markTokenAsTransferred(token)
                    Log.d("WalletFragment", "Token archived")
                } else {
                    Toast.makeText(requireContext(), "Failed to send", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Log.e("WalletFragment", "Error sending token via Nostr", e)
                Toast.makeText(requireContext(), "Transfer failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSuccessDialog(message: String) {
        try {
            transferDetailsText.text = message
            confettiContainer.visibility = View.VISIBLE

            // Auto-dismiss after 3 seconds
            dialogDismissRunnable = Runnable {
                dismissSuccessDialog()
            }
            dialogHandler.postDelayed(dialogDismissRunnable!!, 3000)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing success dialog", e)
        }
    }

    private fun dismissSuccessDialog() {
        try {
            dialogDismissRunnable?.let { dialogHandler.removeCallbacks(it) }
            if (::confettiContainer.isInitialized) {
                confettiContainer.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error dismissing success dialog", e)
        }
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun updateBalanceDisplay() {
        val assets = viewModel.aggregatedAssets.value
        val totalBalance = assets.sumOf { it.getTotalFiatValue(selectedCurrency) }

        val symbol = if (selectedCurrency == "EUR") "€" else "$"
        binding.tvBalance.text = "$symbol${String.format("%,.1f", totalBalance)}"

        // Calculate 24h change
        val totalPreviousBalance = assets.sumOf {
            val previousPrice = when (selectedCurrency) {
                "EUR" -> it.priceEur / (1 + it.change24h / 100)
                else -> it.priceUsd / (1 + it.change24h / 100)
            }
            it.getAmountAsDecimal() * previousPrice
        }

        val changePercent = if (totalPreviousBalance > 0) {
            ((totalBalance - totalPreviousBalance) / totalPreviousBalance) * 100
        } else 0.0

        val changeAmount = totalBalance - totalPreviousBalance
        val changeSign = if (changePercent >= 0) "" else ""

        binding.tvChangeValue.text = "$symbol${changeSign}${String.format("%.1f", changeAmount)}"
        binding.tvChangePercent.text = "${changeSign}${String.format("%.2f", changePercent)}%"
        binding.tvChangeValue.setTextColor(
            if (changePercent >= 0) getColor(requireContext(),R.color.green_positive) else getColor(requireContext(), R.color.red_negative)
        )
        binding.tvChangePercent.setTextColor(
            if (changePercent >= 0) getColor(requireContext(),R.color.green_positive) else getColor(requireContext(), R.color.red_negative)
        )
        val drawable = binding.tvChangePercent.background.mutate()
        (drawable as GradientDrawable).setColor(
            if (changePercent >= 0) getColor(requireContext(), R.color.green_positive_bg) else getColor(requireContext(), R.color.red_negative_bg)
        )
    }

    private fun refreshWallet() {
        // Show refresh animation if initiated by swipe
        if (!binding.swipeRefreshLayout.isRefreshing) {
            binding.swipeRefreshLayout.isRefreshing = true
        }

        Log.d("MainActivity", "Refreshing wallet display and prices...")

        // Log all current crypto balances
        val cryptos = viewModel.cryptocurrencies.value
        Log.d("MainActivity", "=== CURRENT CRYPTO BALANCES ===")
        cryptos.forEach { crypto ->
            Log.d("MainActivity", "${crypto.symbol}: ${crypto.balance} (ID: ${crypto.id})")
        }
        Log.d("MainActivity", "==============================")

        // Refresh the tokens from repository (this doesn't change balances)
        lifecycleScope.launch {
            viewModel.refreshTokens()
        }

        // Refresh crypto prices from API
        viewModel.refreshPrices()

        // Refresh UI display without changing balances
        updateListDisplay()
        updateBalanceDisplay()


        // Add a small delay to show the refresh animation
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000) // Slightly longer delay to allow price fetch
            binding.swipeRefreshLayout.isRefreshing = false
            // Prices updated silently
            Log.d("MainActivity", "Wallet display and price refresh completed")
        }
    }

    private fun startNostrService() {
        try {
            val prefs = requireContext().getSharedPreferences("UnicityWalletPrefs", Context.MODE_PRIVATE)
            val unicityTag = prefs.getString("unicity_tag", "") ?: ""

            if (unicityTag.isNotEmpty()) {
                val nostrService = NostrP2PService.getInstance(requireContext().applicationContext)
                if (nostrService != null && !nostrService.isRunning()) {
                    nostrService.start()
                    Log.d("WalletFragment", "Nostr P2P service started to listen for token transfers")
                }
            } else {
                Log.d("WalletFragment", "No nametag yet, will start Nostr service after minting")
            }
        } catch (e: Exception) {
            Log.e("WalletFragment", "Failed to start Nostr service", e)
        }
    }
}
