package com.example.unicitywallet.ui.wallet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.unicitywallet.R
import com.example.unicitywallet.data.model.AggregatedAsset
import com.example.unicitywallet.utils.IconCacheManager

class AggregatedAssetDialogAdapter(
    private val assets: List<AggregatedAsset>,
    private val onAssetClick: (AggregatedAsset) -> Unit
) : RecyclerView.Adapter<AggregatedAssetDialogAdapter.AssetViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_asset_dialog, parent, false)
        return AssetViewHolder(view)
    }

    override fun onBindViewHolder(holder: AssetViewHolder, position: Int) {
        holder.bind(assets[position], onAssetClick)
    }

    override fun getItemCount(): Int = assets.size

    class AssetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val assetIcon: ImageView = itemView.findViewById(R.id.assetIcon)
        private val assetName: TextView = itemView.findViewById(R.id.assetName)
        private val assetSymbol: TextView = itemView.findViewById(R.id.assetSymbol)

        fun bind(asset: AggregatedAsset, onAssetClick: (AggregatedAsset) -> Unit) {
            // Load icon from URL if available
            if (asset.iconUrl != null) {
                val iconManager = IconCacheManager.getInstance(itemView.context)
                iconManager.loadIcon(
                    url = asset.iconUrl,
                    imageView = assetIcon,
                    placeholder = R.drawable.ic_coin_placeholder,
                    error = R.drawable.ic_coin_placeholder
                )
            } else {
                assetIcon.setImageResource(R.drawable.ic_coin_placeholder)
            }

            assetName.text = (asset.name ?: asset.symbol).replaceFirstChar { it.uppercase() }
            assetSymbol.text = "${asset.getFormattedAmount()} ${asset.symbol}"

            itemView.setOnClickListener {
                onAssetClick(asset)
            }
        }
    }
}
