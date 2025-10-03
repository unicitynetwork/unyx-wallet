package com.example.unicitywallet.ui.onboarding.welcome

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.unicitywallet.databinding.ActivityWelcomeBinding
import com.example.unicitywallet.identity.IdentityManager
import com.example.unicitywallet.ui.onboarding.createNameTag.CreateNameTagActivity
import kotlinx.coroutines.launch

class WelcomeActivity : AppCompatActivity() {

    private lateinit var identityManager: IdentityManager
    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        identityManager = IdentityManager(applicationContext)


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

                identityManager.generateNewIdentity()
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