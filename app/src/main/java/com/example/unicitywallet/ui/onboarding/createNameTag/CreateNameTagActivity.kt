package com.example.unicitywallet.ui.onboarding.createNameTag

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.unicitywallet.identity.IdentityManager
import com.example.unicitywallet.services.NametagService
import kotlinx.coroutines.launch
import androidx.core.content.edit
import com.example.unicitywallet.databinding.ActivityCreateNameBinding
import com.example.unicitywallet.services.MintResult
import com.example.unicitywallet.ui.wallet.WalletActivity
class CreateNameTagActivity : AppCompatActivity() {
    private lateinit var nametagService: NametagService
    private lateinit var identityManager: IdentityManager
    private lateinit var binding: ActivityCreateNameBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateNameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nametagService = NametagService(applicationContext)
        identityManager = IdentityManager(applicationContext)

        binding.btnContinue.setOnClickListener {
            val nametag = binding.etUnicityName.text.toString().trim()
            val cleanTag = nametag.removePrefix("@unicity").removePrefix("@").trim()

            lifecycleScope.launch {
                showLoading(true)

                val result = nametagService.mintNameTagAndPublish(cleanTag)

                showLoading(false)

                when(result) {
                    is MintResult.Success -> {
                        Toast.makeText(this@CreateNameTagActivity, "Nametag created & published!", Toast.LENGTH_LONG).show()
                        getSharedPreferences("UnicityWalletPrefs", MODE_PRIVATE).edit {
                            putString("unicity_tag", cleanTag)
                        }
                        startActivity(Intent(this@CreateNameTagActivity, WalletActivity::class.java))
                        finish()
                    }
                    is MintResult.Warning -> {
                        Toast.makeText(this@CreateNameTagActivity, "Created with warning: ${result.message}", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this@CreateNameTagActivity, WalletActivity::class.java))
                        finish()
                    }
                    is MintResult.Error -> {
                        Toast.makeText(this@CreateNameTagActivity, "Error: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        if(isLoading) {
            binding.btnContinue.text = ""
            binding.btnContinue.isEnabled = false
            binding.btnProgress.visibility = View.VISIBLE
        } else {
            binding.btnContinue.text = "Continue"
            binding.btnContinue.isEnabled = true
            binding.btnProgress.visibility = View.GONE
        }
    }
}