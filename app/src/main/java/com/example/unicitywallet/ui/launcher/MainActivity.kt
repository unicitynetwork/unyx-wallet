package com.example.unicitywallet.ui.launcher

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.unicitywallet.identity.IdentityManager
import com.example.unicitywallet.ui.wallet.WalletActivity
import com.example.unicitywallet.ui.onboarding.welcome.WelcomeActivity

class MainActivity : AppCompatActivity() {
    private lateinit var identityManager: IdentityManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identityManager = IdentityManager(this)

        if (identityManager.hasIdentity()) {
            startActivity(Intent(this, WalletActivity::class.java))
        } else {
            startActivity(Intent(this, WelcomeActivity::class.java))
        }

        finish()
    }
}