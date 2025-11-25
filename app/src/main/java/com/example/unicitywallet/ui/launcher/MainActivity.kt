package com.example.unicitywallet.ui.launcher

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.unicitywallet.identity.IdentityManager
import com.example.unicitywallet.ui.wallet.WalletActivity
import com.example.unicitywallet.ui.onboarding.welcome.WelcomeActivity
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var identityManager: IdentityManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identityManager = IdentityManager(this)


        val nametagsDir = File(filesDir, "nametags")

        val hasNametags = nametagsDir.exists() &&
                nametagsDir.isDirectory &&
                nametagsDir.listFiles()?.isNotEmpty() == true

        if (identityManager.hasIdentity() && hasNametags) {
            startActivity(Intent(this, WalletActivity::class.java))
        } else {
            startActivity(Intent(this, WelcomeActivity::class.java))
        }

        finish()
    }
}