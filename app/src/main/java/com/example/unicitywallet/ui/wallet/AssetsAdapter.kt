package com.example.unicitywallet.ui.wallet

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.unicitywallet.R
import com.example.unicitywallet.data.model.AggregatedAsset
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.example.unicitywallet.utils.IconCacheManager
import kotlin.compareTo

class AssetsAdapter(
    private val currency: String = "USD" // Can be switched if needed
) : ListAdapter<AggregatedAsset, AssetsAdapter.AssetViewHolder>(AssetsDiffCallback()) {

    class AssetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.imgTokenIcon)
        val name: TextView = itemView.findViewById(R.id.tvTokenName)
        val symbol: TextView = itemView.findViewById(R.id.tvTokenSymbol)
        val value: TextView = itemView.findViewById(R.id.tvTokenValue)
        val change: TextView = itemView.findViewById(R.id.tvTokenChange)

        fun bind(
            asset: AggregatedAsset,
            currency: String,
        ) {
            // Load icon from URL if available
            if (asset.iconUrl != null) {
                val iconManager = IconCacheManager.getInstance(itemView.context)
                iconManager.loadIcon(
                    url = asset.iconUrl,
                    imageView = icon,
                    placeholder = R.drawable.unicity_logo,
                    error = R.drawable.unicity_logo
                )

                icon.apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(8, 8, 8, 8)
                    imageTintList = null
                    background = null
                }
            } else {
                icon.setImageResource(R.drawable.unicity_logo)
            }

            // Capitalize first letter of name
            name.text = (asset.name ?: asset.symbol).replaceFirstChar { it.uppercase() }

            // Format as "SYMBOL · amount"
            symbol.text = "${asset.symbol} · ${asset.getFormattedAmount()}"

            // Show fiat value
            value.text = asset.getFormattedFiatValue(currency)

            // Show price change
            change.text = asset.getFormattedChange()
            change.visibility = View.VISIBLE

            // Set change color
            val changeColor = if (asset.change24h >= 0) "#00FF7F" else "#FF4C4C"
            change.setTextColor(changeColor.toColorInt())
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_token, parent, false)
        return AssetViewHolder(view)
    }

    override fun onBindViewHolder(holder: AssetViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, currency)
    }
}

class AssetsDiffCallback : DiffUtil.ItemCallback<AggregatedAsset>() {
    override fun areItemsTheSame(oldItem: AggregatedAsset, newItem: AggregatedAsset): Boolean {
        return oldItem.coinId == newItem.coinId
    }

    override fun areContentsTheSame(oldItem: AggregatedAsset, newItem: AggregatedAsset): Boolean {
        return oldItem == newItem
    }
}
