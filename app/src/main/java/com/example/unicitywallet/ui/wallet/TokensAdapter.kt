package com.example.unicitywallet.ui.wallet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.unicitywallet.R
import com.example.unicitywallet.data.model.Token
import com.example.unicitywallet.databinding.ItemTokenBinding
import com.example.unicitywallet.token.UnicityTokenRegistry
import com.example.unicitywallet.utils.IconCacheManager

class TokensAdapter(
    private val onSendClick: (Token) -> Unit,
) : ListAdapter<Token, TokensAdapter.TokenViewHolder>(TokenDiffCallback()) {
    private var transferringTokenId: String? = null

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
        val isTransferring = transferringTokenId == token.id
        holder.bind(token, isTransferring)
    }

    inner class TokenViewHolder(
        private val binding: ItemTokenBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(token: Token, isTransferring: Boolean) {
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

            binding.itemToken.setOnClickListener {
                onSendClick(token)
            }

        }
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