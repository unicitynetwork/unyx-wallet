package com.example.unicitywallet.ui.onboarding.createNameTag

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.unicitywallet.R
import android.widget.Button
import android.widget.EditText
import android.util.Log
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
import com.example.unicitywallet.ui.wallet.WalletActivity

class CreateNameTagActivity : AppCompatActivity() {
    private lateinit var nametagService: NametagService
    private lateinit var identityManager: IdentityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_name)

        nametagService = NametagService(applicationContext)
        identityManager = IdentityManager(applicationContext)

        val etName = findViewById<EditText>(R.id.etUnicityName)
        val btnContinue = findViewById<Button>(R.id.btnContinue)

        val sharedPrefs = getSharedPreferences("UnicityWalletPrefs", MODE_PRIVATE)

        btnContinue.setOnClickListener {
            val nametag = etName.text.toString().trim()
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
            // Get the identity from IdentityManager
            val identity = identityManager.getCurrentIdentity()
            if (identity == null) {
                Log.e("CreateNameTagActivity", "No identity found")
                return null
            }

            // Convert hex strings to byte arrays
            val secret = hexToBytes(identity.privateKey)
            val nonce = hexToBytes(identity.nonce)

            // Create signing service and predicate
            val signingService = SigningService.createFromSecret(secret)

            // Use the chain's token type for the address
            val tokenType = TokenType(hexToBytes(WalletConstants.UNICITY_TOKEN_TYPE))
            val tokenId = TokenId(ByteArray(32).apply {
                SecureRandom().nextBytes(this)
            })

            val predicate = UnmaskedPredicate.create(
                tokenId,
                tokenType,
                signingService,
                HashAlgorithm.SHA256,
                nonce
            )

            // Return the address
            predicate.reference.toAddress()
        } catch (e: Exception) {
            Log.e("UserProfileActivity", "Error getting wallet address", e)
            null
        }
    }

    private suspend fun mintNametag(nametag: String) {
        try {
            val address = getWalletAddress()
            if (address != null){
                val nametagToken = nametagService.mintNameTag(nametag, address)
                if(nametagToken != null){
                    Toast.makeText(this, "Unicity tag saved: $nametag (displays as $nametag@unicity)", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@CreateNameTagActivity, WalletActivity::class.java))
                    finish()
                }
            } else {
                Log.d("CreateNameTagActivity", "Wallet is not set up")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to mint nametag: ${e.message}", Toast.LENGTH_LONG).show()
        }

    }
}