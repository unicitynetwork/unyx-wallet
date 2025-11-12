package com.example.unicitywallet.ui.settings.nametags

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.unicitywallet.databinding.ItemNametagBinding

class NametagAdapter(
    private val onItemClick: (NametagItemUi) -> Unit
) : ListAdapter<NametagItemUi, NametagAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNametagBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemNametagBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NametagItemUi) {
            binding.tvNametagName.text = item.name

            val limeGreen = Color.parseColor("#C4E454")
            val white = Color.WHITE

            if (item.isActive) {
                binding.tvNametagName.setTextColor(limeGreen)
                binding.tvActiveLabel.visibility = View.VISIBLE

                binding.ivStatus.visibility = View.VISIBLE
            } else {
                binding.tvNametagName.setTextColor(white)
                binding.tvActiveLabel.visibility = View.GONE
                binding.ivStatus.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<NametagItemUi>() {
        override fun areItemsTheSame(oldItem: NametagItemUi, newItem: NametagItemUi) = oldItem.name == newItem.name
        override fun areContentsTheSame(oldItem: NametagItemUi, newItem: NametagItemUi) = oldItem == newItem
    }
}