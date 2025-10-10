package com.example.unicitywallet.ui.onboarding.createNameTag

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.unicitywallet.R
import android.widget.Button
import android.widget.EditText
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.unicitywallet.identity.IdentityManager
import com.example.unicitywallet.services.NametagService
import com.example.unicitywallet.utils.WalletConstants
import kotlinx.coroutines.launch
import org.unicitylabs.sdk.address.DirectAddress
import org.unicitylabs.sdk.hash.HashAlgorithm
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.TokenId
import org.unicitylabs.sdk.token.TokenType
import java.security.SecureRandom
import androidx.core.content.edit
import com.example.unicitywallet.databinding.ActivityCreateNameBinding
import com.example.unicitywallet.databinding.ActivityWelcomeBinding
import com.example.unicitywallet.nostr.NostrP2PService
import com.example.unicitywallet.ui.wallet.WalletActivity
import kotlin.toString

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

        val sharedPrefs = getSharedPreferences("UnicityWalletPrefs", MODE_PRIVATE)

        binding.btnContinue.setOnClickListener {
            val nametag = binding.etUnicityName.text.toString().trim()
            if(nametag.isEmpty()){
                Toast.makeText(this, "Please enter a Unicity tag", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val cleanTag = nametag.removePrefix("@unicity").removePrefix("@")

            sharedPrefs.edit { putString("unicity_tag", cleanTag) }

            lifecycleScope.launch {
                if(!identityManager.hasIdentity()){
                    Log.w("CreateNameTagActivity", "No wallet identity found - user needs to create/restore wallet first")
                } else {
                    mintNametag(cleanTag)
                }
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    private suspend fun getWalletAddress(): DirectAddress? {
        return try {
            // Get the wallet's direct address from IdentityManager
            // This uses UnmaskedPredicateReference without TokenId
            identityManager.getWalletAddress()
        } catch (e: Exception) {
            Log.e("UserProfileActivity", "Error getting wallet address", e)
            null
        }
    }

    private suspend fun mintNametag(nametag: String) {
        try {
            showLoading(true)
            val address = getWalletAddress()
            if (address != null){
                val nametagToken = nametagService.mintNameTag(nametag, address)
                if(nametagToken != null){
                    try {
                        val nostrService = NostrP2PService.getInstance(this)
                        if(nostrService != null){
                            // Start the service if not running
                            if (!nostrService.isRunning()) {
                                Log.d("CreateNametagActivity", "Starting Nostr service for binding publication...")
                                nostrService.start()
                                // Wait for connection to establish
                                kotlinx.coroutines.delay(3000)
                                Log.d("CreateNametagActivity", "Nostr service connection delay complete")
                            }

                            val proxyAddress = nametagService.getProxyAddress(nametagToken)
                            Log.d("CreateNametagActivity", "Publishing nametag binding: $nametag -> $proxyAddress")

                            val published = nostrService.publishNametagBinding(
                                nametagId = nametag,
                                unicityAddress = proxyAddress.toString()
                            )

                            if (published) {
                                Log.d("CreateNametagActivity", "✅ Nametag binding published successfully!")
                                runOnUiThread {
                                    Toast.makeText(this, "Nametag binding published! You can now receive tokens.", Toast.LENGTH_LONG).show()
                                }
                                startActivity(Intent(this@CreateNameTagActivity, WalletActivity::class.java))
                                finish()
                            } else {
                                Log.w("CreateNametagActivity", "❌ Failed to publish nametag binding")
                                runOnUiThread {
                                    Toast.makeText(this, "Warning: Nametag binding may not have published", Toast.LENGTH_SHORT).show()
                                }
                            }

                        } else {
                            Log.w("CreateNametagActivity", "Nostr service not available, skipping nametag binding")
                        }
                    } catch (e: Exception) {
                        Log.e("CreateNametagActivity", "Error publishing nametag binding", e)
                    }
//                    Toast.makeText(this, "Unicity tag saved: $nametag (displays as $nametag@unicity)", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.d("CreateNameTagActivity", "Wallet is not set up")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to mint nametag: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            showLoading(false)
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