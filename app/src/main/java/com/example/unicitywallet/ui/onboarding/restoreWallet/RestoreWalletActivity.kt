package com.example.unicitywallet.ui.onboarding.restoreWallet

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.unicitywallet.R
import com.example.unicitywallet.databinding.ActivityImportWalletBinding
import com.example.unicitywallet.identity.IdentityManager
import com.example.unicitywallet.ui.onboarding.welcome.WelcomeActivity
import com.example.unicitywallet.ui.wallet.WalletActivity
import kotlinx.coroutines.launch
import java.util.Locale

class RestoreWalletActivity : AppCompatActivity() {

    private lateinit var identityManager: IdentityManager
    private lateinit var binding: ActivityImportWalletBinding

    private val fields by lazy {
        listOf<EditText>(
            findViewById(R.id.word1), findViewById(R.id.word2),
            findViewById(R.id.word3), findViewById(R.id.word4),
            findViewById(R.id.word5), findViewById(R.id.word6),
            findViewById(R.id.word7), findViewById(R.id.word8),
            findViewById(R.id.word9), findViewById(R.id.word10),
            findViewById(R.id.word11), findViewById(R.id.word12)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportWalletBinding.inflate(layoutInflater)
        setContentView(binding.root)

        identityManager = IdentityManager(applicationContext)

        binding.word12.imeOptions = EditorInfo.IME_ACTION_DONE

        binding.btnImport.setOnClickListener {
            val words = fields.map { it.text.toString().trim().lowercase(Locale.US) }
            val missingIndex = words.indexOfFirst { it.isEmpty() }
            if (missingIndex != -1) {
                fields[missingIndex].requestFocus()
                fields[missingIndex].error = "Enter word: ${missingIndex + 1}"
                return@setOnClickListener
            }

            lifecycleScope.launch {
                importWallet(words)
            }
        }

        binding.btnCancel.setOnClickListener {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
        }
    }

    private suspend fun importWallet(words: List<String>){
        val identity = identityManager.restoreFromSeedPhrase(words)
        if(identity != null){
            startActivity(Intent(this, WalletActivity::class.java))
            finish()
        } else {
            Toast.makeText(this, "Wrong recovery phrase", Toast.LENGTH_SHORT).show()
        }
    }
}