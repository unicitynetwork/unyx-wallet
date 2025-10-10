package com.example.unicitywallet.ui.onboarding.welcome

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.unicitywallet.data.model.Token
import com.example.unicitywallet.data.model.Wallet
import com.example.unicitywallet.data.repository.WalletRepository
import com.example.unicitywallet.databinding.ActivityWelcomeBinding
import com.example.unicitywallet.identity.IdentityManager
import com.example.unicitywallet.ui.onboarding.createNameTag.CreateNameTagActivity
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.core.content.edit

class WelcomeActivity : AppCompatActivity() {
    private lateinit var identityManager: IdentityManager
    private lateinit var binding: ActivityWelcomeBinding

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        identityManager = IdentityManager(applicationContext)
        sharedPreferences = getSharedPreferences("wallet_prefs", android.content.Context.MODE_PRIVATE)

        binding.btnCreateWallet.setOnClickListener {
            createWallet()
        }

        binding.btnRecoverWallet.setOnClickListener {
            // TODO: implement wallet recovery
        }
    }

    private fun createWallet() {
        lifecycleScope.launch {
            try {
                showLoading(true)

                val (identity, words) = identityManager.generateNewIdentity()

                val wallet = Wallet(
                    id = UUID.randomUUID().toString(),
                    name = "My Wallet",
                    address = identity.address,
                    tokens = emptyList()
                )

                val walletJson = Gson().toJson(wallet)
                sharedPreferences.edit { putString("wallet", walletJson) }

                startActivity(Intent(this@WelcomeActivity, CreateNameTagActivity::class.java))
                finish()

            } catch (e: Exception) {
                Log.e("Welcome", "Error creating a new wallet", e)
                Toast.makeText(this@WelcomeActivity, "Failed to create wallet: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        if(isLoading) {
            binding.btnCreateWallet.text = ""
            binding.btnCreateWallet.isEnabled = false
            binding.btnProgress.visibility = View.VISIBLE
        } else {
            binding.btnCreateWallet.text = "Create Wallet"
            binding.btnCreateWallet.isEnabled = true
            binding.btnProgress.visibility = View.GONE
        }

        binding.btnRecoverWallet.isEnabled = !isLoading
    }
}