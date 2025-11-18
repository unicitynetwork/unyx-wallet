package com.example.unicitywallet.ui.wallet

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.unicitywallet.data.model.Contact
import com.example.unicitywallet.databinding.ItemContactBinding
import kotlin.random.Random

class ContactAdapter(
    private val onContactClick: (Contact) -> Unit
) : ListAdapter<Contact, ContactAdapter.ContactViewHolder>(ContactDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ContactViewHolder(
        private val binding: ItemContactBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: Contact) {
            // Set contact info
            binding.contactName.text = contact.name

            // Parse address to separate phone/email from unicity tag
            val addressText = if (contact.hasUnicityTag()) {
                contact.unicityId
            } else {
                ""
            }
            if (addressText.isNullOrBlank()) {
                binding.contactAddress.visibility = View.GONE
            } else {
                binding.contactAddress.visibility = View.VISIBLE
                binding.contactAddress.text = addressText
            }

            // Set avatar
            binding.avatarText.text = contact.getInitials()
            val avatarColor = generateAvatarColor(contact.id)
            binding.avatarContainer.setBackgroundColor(avatarColor)

            // Set visual state based on whether contact has @unicity tag
            if (!contact.hasUnicityTag()) {
                // Make non-@unicity contacts appear slightly disabled
                binding.root.alpha = 0.6f
            } else {
                // Reset to normal appearance for @unicity contacts
                binding.root.alpha = 1.0f
            }

            // Set click listener
            binding.root.setOnClickListener {
                onContactClick(contact)
            }
        }

        private fun generateAvatarColor(seed: String): Int {
            val random = Random(seed.hashCode())
            val colors = listOf(
                Color.parseColor("#1976D2"), // Blue
                Color.parseColor("#388E3C"), // Green
                Color.parseColor("#D32F2F"), // Red
                Color.parseColor("#7B1FA2"), // Purple
                Color.parseColor("#F57C00"), // Orange
                Color.parseColor("#00796B"), // Teal
                Color.parseColor("#5D4037"), // Brown
                Color.parseColor("#455A64")  // Blue Grey
            )
            return colors[random.nextInt(colors.size)]
        }
    }

    class ContactDiffCallback : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(oldItem: Contact, newItem: Contact): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Contact, newItem: Contact): Boolean {
            return oldItem == newItem
        }
    }
}