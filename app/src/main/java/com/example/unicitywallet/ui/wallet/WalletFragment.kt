package com.example.unicitywallet.ui.wallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
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
        tokensAdapter = TokensAdapter()
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

    private fun showContactListDialog() {
        currentContactDialog = ContactListDialog(
            context = requireContext(),
            onContactSelected = { selectedContact ->
                // Check if contact has @unicity tag
                if (selectedContact.hasUnicityTag()) {
                    // After contact is selected, show asset selection dialog
                    showAssetSelectionDialog(selectedContact)
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

    private fun showAssetSelectionDialog(recipient: Contact) {
        try {
            Log.d("WalletFragment", "showAssetSelectionDialog called with recipient: ${recipient.name}")

            val dialogView = layoutInflater.inflate(R.layout.dialog_send_asset, null)

            val dialog = AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create()

            // Setup recipient info
            val recipientName = dialogView.findViewById<TextView>(R.id.recipientName)
            val recipientAddress = dialogView.findViewById<TextView>(R.id.recipientAddress)
            val recipientBadge = dialogView.findViewById<ImageView>(R.id.recipientUnicityBadge)

            recipientName.text = recipient.name
            recipientAddress.text = recipient.address
            recipientBadge.visibility = if (recipient.hasUnicityTag()) View.VISIBLE else View.GONE

            // Setup close button
            val btnClose = dialogView.findViewById<ImageButton>(R.id.btnClose)
            btnClose.setOnClickListener {
                dialog.dismiss()
            }

            // Setup asset selector
            val assetSelector = dialogView.findViewById<AutoCompleteTextView>(R.id.assetSelector)
            val assetNames = listOf("Bitcoin", "Ethereum", "eNaira", "eFranc")
            val assetAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, assetNames)
            assetSelector.setAdapter(assetAdapter)

            val availableBalanceText = dialogView.findViewById<TextView>(R.id.availableBalanceText)
            assetSelector.setOnItemClickListener { _, _, position, _ ->
                val selectedAsset = assetNames[position]
                when (selectedAsset) {
                    "Bitcoin" -> availableBalanceText.text = "Available: 0.025 BTC"
                    "Ethereum" -> availableBalanceText.text = "Available: 1.5 ETH"
                    "eNaira" -> availableBalanceText.text = "Available: 50,000 NGN"
                    "eFranc" -> availableBalanceText.text = "Available: 25,000 XAF"
                    else -> availableBalanceText.text = "Available balance will be shown when token is selected"
                }
            }

            // Replace the send button logic with crypto selection
            val btnSend = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSend)
            btnSend.visibility = View.GONE // Hide the old send button

            // Show crypto selection dialog immediately
            dialog.dismiss()
            showCryptoSelectionForRecipient(recipient)
        } catch (e: Exception) {
            Log.e("WalletFragment", "Error showing asset selection dialog", e)
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        val adapter = AggregatedAssetDialogAdapter(assets) { selectedAsset ->
            dialog.dismiss()
            showAssetSendAmountDialog(selectedAsset, recipient)
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

        // If only one token, skip picker and transfer immediately
        if (tokensForCoin.size == 1) {
            sendTokenViaNostr(tokensForCoin[0], selectedContact)
            return
        }

        // Show token selection dialog
        val tokenDescriptions = tokensForCoin.mapIndexed { index, token ->
            val amount = token.amount ?: 0
            val formattedAmount = amount.toDouble() / Math.pow(10.0, asset.decimals.toDouble())
            "Token #${index + 1}: $formattedAmount ${asset.symbol}"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Select Token to Send")
            .setItems(tokenDescriptions.toTypedArray()) { dialog, which ->
                val selectedToken = tokensForCoin[which]
                // Proceed with transfer
                sendTokenViaNostr(selectedToken, selectedContact)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
