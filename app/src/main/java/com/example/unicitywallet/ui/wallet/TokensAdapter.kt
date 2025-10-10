package com.example.unicitywallet.ui.wallet

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.unicitywallet.R
import com.example.unicitywallet.data.model.Token
import com.example.unicitywallet.databinding.ItemTokenBinding
import com.example.unicitywallet.token.UnicityTokenRegistry
import com.example.unicitywallet.utils.IconCacheManager
import com.example.unicitywallet.utils.JsonMapper
import com.fasterxml.jackson.databind.JsonNode
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TokensAdapter(
//    private val onSendClick: (Token) -> Unit,
//    private val onCancelClick: (Token) -> Unit,
//    private val onManualSubmitClick: (Token) -> Unit = {},
//    private val onShareClick: (Token) -> Unit = {}
) : ListAdapter<Token, TokensAdapter.TokenViewHolder>(TokenDiffCallback()) {

    private var expandedTokenId: String? = null
    private var transferringTokenId: String? = null
    private val transferProgress = mutableMapOf<String, Pair<Int, Int>>() // tokenId -> (current, total)
    // Using shared JsonMapper.mapper for JSON operations

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TokenViewHolder {
        val binding = ItemTokenBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TokenViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TokenViewHolder, position: Int) {
        val token = getItem(position)
        val isExpanded = expandedTokenId == token.id
        val isTransferring = transferringTokenId == token.id
        holder.bind(token, isExpanded, isTransferring)
    }

    fun expandToken(token: Token) {
        val wasExpanded = expandedTokenId == token.id
        val oldExpandedId = expandedTokenId

        expandedTokenId = if (wasExpanded) null else token.id

        // Notify changes for animation
        if (oldExpandedId != null) {
            val oldIndex = currentList.indexOfFirst { it.id == oldExpandedId }
            if (oldIndex != -1) notifyItemChanged(oldIndex)
        }

        if (!wasExpanded) {
            val newIndex = currentList.indexOfFirst { it.id == token.id }
            if (newIndex != -1) notifyItemChanged(newIndex)
        }
    }

    fun setTransferring(token: Token, isTransferring: Boolean) {
        transferringTokenId = if (isTransferring) token.id else null
        if (!isTransferring) {
            transferProgress.remove(token.id)
        }
        val index = currentList.indexOfFirst { it.id == token.id }
        if (index != -1) notifyItemChanged(index)
    }

    fun updateTransferProgress(token: Token, current: Int, total: Int) {
        transferProgress[token.id] = Pair(current, total)
        val index = currentList.indexOfFirst { it.id == token.id }
        if (index != -1) notifyItemChanged(index)
    }

    fun collapseAll() {
        val oldExpandedId = expandedTokenId
        expandedTokenId = null
        transferringTokenId = null

        if (oldExpandedId != null) {
            val index = currentList.indexOfFirst { it.id == oldExpandedId }
            if (index != -1) notifyItemChanged(index)
        }
    }

    private fun extractAmountFromTokenNode(tokenNode: JsonNode): Any? {
        // Try different paths where amount might be stored

        // Check in genesis transaction
        val genesis = tokenNode.get("genesis")
        val genesisData = genesis?.get("data")
        val mintData = genesisData?.get("data")

        if (mintData != null && !mintData.isNull) {
            // Direct amount field in our new format
            mintData.get("amount")?.let {
                if (!it.isNull) return if (it.isNumber) it.asLong() else it.asText()
            }

            // For TokenCoinData structure (coins field)
            val coins = mintData.get("coins")
            if (coins != null && !coins.isNull && coins.isObject) {
                // Sum all coin values
                var totalAmount = 0L
                coins.fields().forEach { entry ->
                    try {
                        val value = entry.value
                        totalAmount += when {
                            value.isNumber -> value.asLong()
                            value.isTextual -> value.asText().toLong()
                            else -> 0L
                        }
                    } catch (e: Exception) {
                        // Ignore parsing errors
                    }
                }
                if (totalAmount > 0) return totalAmount
            }

            // Other possible fields
            mintData.get("value")?.let {
                if (!it.isNull) return if (it.isNumber) it.asLong() else it.asText()
            }

            // Check message field for custom data that might contain amount
            val message = mintData.get("message")
            if (message != null && message.isTextual) {
                // Try to parse message as JSON
                try {
                    val messageData = JsonMapper.mapper.readTree(message.asText())
                    messageData?.get("amount")?.let {
                        if (!it.isNull) return if (it.isNumber) it.asLong() else it.asText()
                    }
                    messageData?.get("value")?.let {
                        if (!it.isNull) return if (it.isNumber) it.asLong() else it.asText()
                    }
                } catch (e: Exception) {
                    // Message is not JSON, ignore
                }
            }
        }

        return null
    }

//    private fun showTokenDetails(binding: ItemTokenBinding, token: Token) {
//        try {
//            // Find the views - they might not exist in older layouts
//            val tvAmount = binding.root.findViewById<TextView?>(R.id.tvTokenAmount)
//            val tvData = binding.root.findViewById<TextView?>(R.id.tvTokenData)
//
//            // If views don't exist, just return
//            if (tvAmount == null && tvData == null) return
//
//            // Hide by default
//            tvAmount?.visibility = View.GONE
//            tvData?.visibility = View.GONE
//
//            // Try to parse token data
//            token.jsonData?.let { jsonData ->
//                try {
//                    // Use UnicityObjectMapper for SDK-generated JSON
//                    val tokenNode: JsonNode = UnicityObjectMapper.JSON.readTree(jsonData)
//
//                    // Debug logging
//                    android.util.Log.d("TokenAdapter", "Token JSON structure: ${tokenNode.toString().take(500)}")
//                    val genesis = tokenNode.get("genesis")
//                    val genesisData = genesis?.get("data")
//                    android.util.Log.d("TokenAdapter", "Genesis data: $genesisData")
//                    val mintData = genesisData?.get("data")
//                    android.util.Log.d("TokenAdapter", "Mint data type: ${mintData?.javaClass?.name}, value: $mintData")
//
//                    // Try to extract amount
//                    val amount = extractAmountFromTokenNode(tokenNode)
//                    android.util.Log.d("TokenAdapter", "Extracted amount: $amount")
//                    if (amount != null && tvAmount != null) {
//                        tvAmount.visibility = View.VISIBLE
//                        tvAmount.text = "Amount: $amount"
//                    }
//
//                    // Try to extract data
//                    val data = extractDataFromTokenNode(tokenNode)
//                    android.util.Log.d("TokenAdapter", "Extracted data: $data")
//                    if (data != null && data.toString().isNotEmpty() && tvData != null) {
//                        tvData.visibility = View.VISIBLE
//                        tvData.text = "Data: $data"
//                    } else {
//                        tvData?.visibility = View.GONE
//                    }
//                } catch (e: Exception) {
//                    android.util.Log.e("TokenAdapter", "Error parsing token JSON", e)
//                }
//            }
//        } catch (e: Exception) {
//            android.util.Log.e("TokenAdapter", "Error showing token details", e)
//        }
//    }

    private fun extractDataFromTokenNode(tokenNode: JsonNode): Any? {
        // Try different paths where custom data might be stored

        // Check in genesis transaction
        val genesis = tokenNode.get("genesis")
        val genesisData = genesis?.get("data")
        val mintData = genesisData?.get("data")

        if (mintData != null && !mintData.isNull) {
            // Check for our structured data
            mintData.get("data")?.let { node ->
                if (!node.isNull) {
                    val str = if (node.isTextual) node.asText() else node.toString()
                    if (str.isNotEmpty() && str != "null") return str
                }
            }

            // Check message field - this is where we store custom data
            val message = mintData.get("message")
            if (message != null && message.isTextual) {
                val messageStr = message.asText()
                // Check if it's base64 encoded
                try {
                    val decoded = android.util.Base64.decode(messageStr, android.util.Base64.DEFAULT)
                    val decodedStr = String(decoded, java.nio.charset.StandardCharsets.UTF_8)
                    if (decodedStr.isNotEmpty()) return decodedStr
                } catch (e: Exception) {
                    // Not base64, use as is
                    if (messageStr.isNotEmpty() && messageStr != "null") return messageStr
                }
            }

            // Other possible locations
            mintData.get("customData")?.let { node ->
                if (!node.isNull) {
                    val str = if (node.isTextual) node.asText() else node.toString()
                    if (str.isNotEmpty() && str != "null") return str
                }
            }
            mintData.get("tokenData")?.let { node ->
                if (!node.isNull) {
                    val str = if (node.isTextual) node.asText() else node.toString()
                    if (str.isNotEmpty() && str != "null") return str
                }
            }
        }

        return null
    }

    inner class TokenViewHolder(
        private val binding: ItemTokenBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(token: Token, isExpanded: Boolean, isTransferring: Boolean) {
            // Basic token info - show amount and symbol if available
            android.util.Log.d("TokenAdapter", "Binding token: name=${token.name}, amount=${token.amount}, symbol=${token.symbol}, iconUrl=${token.iconUrl}")

            // Format amount with decimals like in Assets tab
            binding.tvTokenName.text = if (token.amount != null && token.symbol != null && token.coinId != null) {
                // Get decimals from registry
                val registry = UnicityTokenRegistry.getInstance(binding.root.context)
                val coinDef = registry.getCoinDefinition(token.coinId)
                val decimals = coinDef?.decimals ?: 8
                val value = token.amount.toDouble() / Math.pow(10.0, decimals.toDouble())
                val formattedAmount = String.format("%.${Math.min(decimals, 8)}f", value).trimEnd('0').trimEnd('.')
                "$formattedAmount ${token.symbol}"
            } else {
                token.name
            }
            binding.tvTokenSymbol.text = token.id.take(8)

            // Load token icon if available, otherwise use Unicity logo
            if (token.iconUrl != null) {
                try {
                    val iconManager = IconCacheManager.getInstance(binding.root.context)
                    iconManager.loadIcon(
                        url = token.iconUrl,
                        imageView = binding.imgTokenIcon,
                        placeholder = R.drawable.unicity_logo,
                        error = R.drawable.unicity_logo
                    )
                    binding.imgTokenIcon.background = null
                    binding.imgTokenIcon.setPadding(8, 8, 8, 8)
                    binding.imgTokenIcon.imageTintList = null
                    binding.imgTokenIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                } catch (e: Exception) {
                    // Fallback to Unicity logo on error
                    binding.imgTokenIcon.setImageResource(R.drawable.unicity_logo)
                }
            } else {
                // Set Unicity logo for token icon
                binding.imgTokenIcon.setImageResource(R.drawable.unicity_logo)
                binding.imgTokenIcon.background = null
                binding.imgTokenIcon.setPadding(0, 0, 0, 0)
                binding.imgTokenIcon.imageTintList = null
                binding.imgTokenIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }

            // Expanded details
//            binding.tvTokenId.text = "ID: ${token.id.take(12)}..."
//            binding.tvTokenTimestamp.text = "Created: ${formatDate(token.timestamp)}"
//            binding.tvUnicityAddress.text = if (token.unicityAddress != null) {
//                "Address: ${token.unicityAddress.take(12)}..."
//            } else {
//                "Address: Not set"
//            }
//            binding.tvTokenSize.text = "Size: ${token.getFormattedSize()}"

            // Try to show amount and data - simplified approach
            //showTokenDetails(binding, token)

            // Show token status
//            updateTokenStatus(token)

            // Set up expansion/collapse
//            binding.layoutExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE

            // Update expand icon rotation
//            val rotation = if (isExpanded) 90f else 0f
//            ObjectAnimator.ofFloat(binding.ivExpandIcon, "rotation", rotation).apply {
//                duration = 200
//                start()
//            }

            // Handle transfer state
//            if (isTransferring) {
//                binding.layoutTransferStatus.visibility = View.VISIBLE
//                binding.btnSend.visibility = View.GONE
//                binding.btnCancel.visibility = View.VISIBLE
//                binding.btnManualSubmit.visibility = View.GONE
//
//                // Check if we have progress info
//                val progress = transferProgress[token.id]
//                if (progress != null) {
//                    val (current, total) = progress
//                    binding.tvTransferStatus.text = if (total > 1) {
//                        "Sending chunk $current/$total..."
//                    } else {
//                        "Sending..."
//                    }
//                } else {
//                    binding.tvTransferStatus.text = "Waiting for tap..."
//                }
//            } else {
//                binding.layoutTransferStatus.visibility = View.GONE
//
//                // Show appropriate buttons based on token status
//                when (token.status ?: TokenStatus.CONFIRMED) {
//                    TokenStatus.PENDING, TokenStatus.FAILED -> {
//                        binding.btnSend.visibility = View.GONE
//                        binding.btnManualSubmit.visibility = View.VISIBLE
//                        binding.btnCancel.visibility = View.GONE
//                    }
//                    TokenStatus.SUBMITTED -> {
//                        binding.btnSend.visibility = View.GONE
//                        binding.btnManualSubmit.visibility = View.GONE
//                        binding.btnCancel.visibility = View.GONE
//                    }
//                    TokenStatus.CONFIRMED -> {
//                        binding.btnSend.visibility = View.VISIBLE
//                        binding.btnManualSubmit.visibility = View.GONE
//                        binding.btnCancel.visibility = View.GONE
//                    }
//                    TokenStatus.TRANSFERRED -> {
//                        binding.btnSend.visibility = View.GONE
//                        binding.btnManualSubmit.visibility = View.GONE
//                        binding.btnCancel.visibility = View.GONE
//                    }
//                }
//            }

        }

//        private fun updateTokenStatus(token: Token) {
//            when (token.status ?: TokenStatus.CONFIRMED) {
//                TokenStatus.PENDING -> {
//                    binding.tvTokenStatus.visibility = View.VISIBLE
//                    binding.ivStatusIcon.visibility = View.VISIBLE
//                    binding.tvTokenStatus.text = "Submitting to network..."
//                    binding.tvTokenStatus.setTextColor(binding.root.context.getColor(android.R.color.holo_orange_light))
//                    binding.ivStatusIcon.setImageResource(android.R.drawable.ic_dialog_info)
//                    binding.ivStatusIcon.setColorFilter(binding.root.context.getColor(android.R.color.holo_orange_light))
//                }
//                TokenStatus.SUBMITTED -> {
//                    binding.tvTokenStatus.visibility = View.VISIBLE
//                    binding.ivStatusIcon.visibility = View.VISIBLE
//                    binding.tvTokenStatus.text = "Awaiting confirmation..."
//                    binding.tvTokenStatus.setTextColor(binding.root.context.getColor(android.R.color.holo_blue_light))
//                    binding.ivStatusIcon.setImageResource(android.R.drawable.ic_dialog_info)
//                    binding.ivStatusIcon.setColorFilter(binding.root.context.getColor(android.R.color.holo_blue_light))
//                }
//                TokenStatus.FAILED -> {
//                    binding.tvTokenStatus.visibility = View.VISIBLE
//                    binding.ivStatusIcon.visibility = View.VISIBLE
//                    binding.tvTokenStatus.text = "Submit failed - retry manually"
//                    binding.tvTokenStatus.setTextColor(binding.root.context.getColor(android.R.color.holo_red_light))
//                    binding.ivStatusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
//                    binding.ivStatusIcon.setColorFilter(binding.root.context.getColor(android.R.color.holo_red_light))
//                }
//                TokenStatus.CONFIRMED -> {
//                    binding.tvTokenStatus.visibility = View.GONE
//                    binding.ivStatusIcon.visibility = View.GONE
//                }
//                TokenStatus.TRANSFERRED -> {
//                    binding.tvTokenStatus.visibility = View.VISIBLE
//                    binding.ivStatusIcon.visibility = View.VISIBLE
//                    binding.tvTokenStatus.text = "Transferred"
//                    binding.tvTokenStatus.setTextColor(binding.root.context.getColor(android.R.color.darker_gray))
//                    binding.ivStatusIcon.setImageResource(android.R.drawable.ic_menu_send)
//                    binding.ivStatusIcon.setColorFilter(binding.root.context.getColor(android.R.color.darker_gray))
//                }
//            }
//        }
//
//        private fun formatDate(timestamp: Long): String {
//            val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
//            return formatter.format(Date(timestamp))
//        }
    }

    class TokenDiffCallback : DiffUtil.ItemCallback<Token>() {
        override fun areItemsTheSame(oldItem: Token, newItem: Token): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Token, newItem: Token): Boolean {
            return oldItem == newItem
        }
    }
}