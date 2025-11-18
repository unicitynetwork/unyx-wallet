package com.example.unicitywallet.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.unicitywallet.databinding.ActivitySettingsBinding
import com.example.unicitywallet.databinding.FragmentRecoveryPhraseBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var currentNametagString: String? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)

        setContentView(binding.root)

        updateNametagUI()

        binding.tvNametag.text = "$currentNametagString@unicity"
        binding.nametagManager.setOnClickListener {
            startActivity(Intent(this, NametagManagerActivity::class.java))
        }
        binding.btnBack.setOnClickListener {
            finish()
        }
        binding.rowRecovery.setOnClickListener {
            startActivity(Intent(this, RecoveryPhraseActivity::class.java))
        }

        binding.rowPin.setOnClickListener {
            //TODO
        }

        binding.rowManageContacts.setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateNametagUI()
    }

    private fun updateNametagUI() {
        val prefs = this.getSharedPreferences("UnicityWalletPrefs", Context.MODE_PRIVATE)
        currentNametagString = prefs.getString("unicity_tag", null)

        if (!currentNametagString.isNullOrEmpty()) {
            binding.tvNametag.text = "$currentNametagString@unicity"
        } else {
            binding.tvNametag.text = "No ID Selected"
        }
    }
}