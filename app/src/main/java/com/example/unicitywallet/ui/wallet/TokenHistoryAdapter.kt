package com.example.unicitywallet.ui.wallet

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.unicitywallet.R
import com.example.unicitywallet.data.model.AggregatedAsset
import com.example.unicitywallet.data.model.TransactionEvent
import com.example.unicitywallet.data.model.TransactionType
import com.example.unicitywallet.databinding.ItemHistoryBinding
import com.example.unicitywallet.token.UnicityTokenRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TokenHistoryAdapter(
    private val transactionEvents: List<TransactionEvent>,
    private val aggregatedAssets: List<AggregatedAsset>
) : RecyclerView.Adapter<TokenHistoryAdapter.HistoryViewHolder>() {

    private val aggregatedMap = aggregatedAssets.associateBy { it.coinId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(transactionEvents[position])
    }

    override fun getItemCount(): Int = transactionEvents.size

    inner class HistoryViewHolder(binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        private val transactionTypeText: TextView = binding.tvTransactionType
        private val amountText: TextView = binding.tvAmount
        private val dateText: TextView = binding.tvDate
        private val tokenIcon: ImageView = binding.imgTxType

        fun bind(event: TransactionEvent) {
            val token = event.token
            val dateFormatter = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            val date = dateFormatter.format(Date(event.timestamp))

            // Format amount with proper decimals
            val asset = token.coinId?.let { aggregatedMap[it] }
            val decimals = asset?.decimals ?: token.coinId?.let { coinIdHex ->
                try {
                    val registry = UnicityTokenRegistry.getInstance(itemView.context)
                    registry.getCoinDefinition(coinIdHex)?.decimals
                } catch (e: Exception) {
                    null
                }
            } ?: 0

            val amount = token.getAmountAsBigInteger()?.let { bigIntAmount ->
                val divisor = Math.pow(10.0, decimals.toDouble())
                val value = bigIntAmount.toDouble() / divisor
                String.format("%.${Math.min(decimals, 8)}f", value).trimEnd('0').trimEnd('.')
            } ?: "0"


            val context = itemView.context

            when (event.type) {
                TransactionType.SENT -> {
                    transactionTypeText.text = "Sent"
                    amountText.text = "- $amount ${token.symbol}"
                    amountText.setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    tokenIcon.setImageResource(R.drawable.ic_send)
                }
                TransactionType.RECEIVED -> {
                    transactionTypeText.text = "Received"
                    amountText.text = "+ $amount ${token.symbol}"
                    val greenColor = Color.parseColor("#4CAF50")
                    amountText.setTextColor(greenColor)
                    tokenIcon.setImageResource(R.drawable.ic_receive)
                }
            }
            dateText.text = date
        }
    }
}