package com.example.unicitywallet.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.unicitywallet.databinding.ActivitySettingsBinding
import com.example.unicitywallet.databinding.FragmentRecoveryPhraseBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)

        setContentView(binding.root)

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
}