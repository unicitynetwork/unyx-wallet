package com.example.unicitywallet.ui.onboarding.welcome

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.unicitywallet.R
import com.example.unicitywallet.identity.IdentityManager
import com.example.unicitywallet.ui.onboarding.createNameTag.CreateNameTagActivity
import kotlinx.coroutines.launch

class WelcomeActivity : AppCompatActivity() {

    private lateinit var identityManager: IdentityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        identityManager = IdentityManager(applicationContext)


        findViewById<Button>(R.id.btnCreateWallet).setOnClickListener {
            createWallet()
        }

        findViewById<Button>(R.id.btnRecoverWallet).setOnClickListener {
            // TODO: implement wallet recovery
        }
    }

    private fun createWallet() {
        lifecycleScope.launch {
            try {
                identityManager.generateNewIdentity()
                startActivity(Intent(this@WelcomeActivity, CreateNameTagActivity::class.java))
                finish()

            } catch (e: Exception) {
                Log.e("Welcome", "Error creating a new wallet", e)
                Toast.makeText(this@WelcomeActivity, "Failed to create wallet: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}