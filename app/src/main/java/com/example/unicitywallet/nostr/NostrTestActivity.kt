package com.example.unicitywallet.nostr

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.unicitywallet.p2p.P2PServiceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Test activity for Nostr P2P integration
 * This can be launched to test Nostr connectivity and messaging
 */
class NostrTestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NostrTest"
    }

    private lateinit var nostrService: NostrP2PService
    private lateinit var statusText: TextView
    private lateinit var publicKeyText: TextView
    private lateinit var messageInput: EditText
    private lateinit var recipientInput: EditText

    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create a simple test UI programmatically
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        statusText = TextView(this).apply {
            text = "Nostr Status: Not Connected"
            textSize = 16f
        }
        layout.addView(statusText)

        publicKeyText = TextView(this).apply {
            text = "Public Key: Loading..."
            textSize = 12f
            setPadding(0, 16, 0, 16)
        }
        layout.addView(publicKeyText)

        val connectButton = Button(this).apply {
            text = "Connect to Nostr"
            setOnClickListener { connectToNostr() }
        }
        layout.addView(connectButton)

        recipientInput = EditText(this).apply {
            hint = "Recipient Public Key (hex)"
            setSingleLine(true)
        }
        layout.addView(recipientInput)

        messageInput = EditText(this).apply {
            hint = "Enter message"
            setSingleLine(true)
        }
        layout.addView(messageInput)

        val sendButton = Button(this).apply {
            text = "Send Test Message"
            setOnClickListener { sendTestMessage() }
        }
        layout.addView(sendButton)

        val testEncryptionButton = Button(this).apply {
            text = "Test Encryption"
            setOnClickListener { testEncryption() }
        }
        layout.addView(testEncryptionButton)

        setContentView(layout)

        // Initialize Nostr service using P2PServiceFactory
        // Set Nostr as the service type for this test activity
        P2PServiceFactory.setServiceType(this, P2PServiceFactory.ServiceType.NOSTR)

        // Get or create the Nostr service instance with test parameters
        val service = P2PServiceFactory.getInstance(
            context = this,
            userTag = "nostr_test_user",
            userPublicKey = "test_public_key"
        )

        nostrService = if (service is NostrP2PService) {
            service
        } else {
            // Fall back to direct instantiation if factory returns wrong type
            NostrP2PService.getInstance(this) ?: throw IllegalStateException("Failed to create Nostr service")
        }
    }

    private fun connectToNostr() {
        scope.launch {
            try {
                // Start the service
                nostrService.start()
                statusText.text = "Nostr Status: Connecting..."

                // Get public key
                val keyManager = NostrKeyManager(this@NostrTestActivity)
                keyManager.initializeKeys()

                val publicKey = keyManager.getPublicKey()
                val npub = keyManager.toBech32PublicKey()

                withContext(Dispatchers.Main) {
                    publicKeyText.text = "Public Key:\nHex: $publicKey\nNpub: $npub"
                }

                // Monitor connection status
                launch {
                    nostrService.connectionStatus.collect { status ->
                        withContext(Dispatchers.Main) {
                            val connectedRelays = status.count { it.value.isConnected }
                            statusText.text = "Nostr Status: Connected to $connectedRelays relays"

                            if (connectedRelays > 0) {
                                Log.d(TAG, "Connected to relays: ${status.keys}")
                            }
                        }
                    }
                }

                Log.d(TAG, "Nostr service started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to Nostr", e)
                withContext(Dispatchers.Main) {
                    statusText.text = "Nostr Status: Failed - ${e.message}"
                }
            }
        }
    }

    private fun sendTestMessage() {
        val recipient = recipientInput.text.toString()
        val message = messageInput.text.toString()

        if (recipient.isEmpty() || message.isEmpty()) {
            statusText.text = "Please enter recipient and message"
            return
        }

        scope.launch {
            try {
                nostrService.sendMessage(recipient, message)

                withContext(Dispatchers.Main) {
                    statusText.text = "Message sent!"
                    messageInput.text.clear()
                }

                Log.d(TAG, "Test message sent to $recipient")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                withContext(Dispatchers.Main) {
                    statusText.text = "Send failed: ${e.message}"
                }
            }
        }
    }

    private fun testEncryption() {
        scope.launch {
            try {
                val keyManager = NostrKeyManager(this@NostrTestActivity)
                keyManager.initializeKeys()

                // Test message
                val testMessage = "Hello from Nostr encryption test!"

                // Use our own public key for testing (encrypt to ourselves)
                val ourPublicKey = keyManager.getPublicKeyBytes()

                // Encrypt
                val encrypted = keyManager.encryptMessage(testMessage, ourPublicKey)
                Log.d(TAG, "Encrypted message: $encrypted")

                // Decrypt
                val decrypted = keyManager.decryptMessage(encrypted, ourPublicKey)
                Log.d(TAG, "Decrypted message: $decrypted")

                withContext(Dispatchers.Main) {
                    if (decrypted == testMessage) {
                        statusText.text = "Encryption test PASSED ✓"
                    } else {
                        statusText.text = "Encryption test FAILED ✗"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Encryption test failed", e)
                withContext(Dispatchers.Main) {
                    statusText.text = "Encryption test error: ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()

        // Stop the service
        nostrService.stop()
    }
}