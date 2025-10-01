package com.example.unicitywallet.data.repository

import android.content.SharedPreferences
import android.content.Context
import com.example.unicitywallet.data.model.Token
import com.example.unicitywallet.data.model.Wallet
import com.example.unicitywallet.identity.IdentityManager
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import androidx.core.content.edit

class WalletRepository(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("wallet_prefs", android.content.Context.MODE_PRIVATE)

    private val gson = Gson()

    private val identityManager = IdentityManager(context)

    private val _wallet = MutableStateFlow<Wallet?>(null)
    val wallet: StateFlow<Wallet?> = _wallet.asStateFlow()

    private val _tokens = MutableStateFlow<List<Token>>(emptyList())
    val tokens: StateFlow<List<Token>> = _tokens.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    companion object {
        private const val TAG = "WalletRepository"
    }

    init {
        loadWallet()
    }

    private fun loadWallet() {
        val walletJson = sharedPreferences.getString("wallet", null)
        if (walletJson != null) {
            val wallet = gson.fromJson(walletJson, Wallet::class.java)
            _wallet.value = wallet
            _tokens.value = wallet.tokens
        } else {
            // Create new wallet if none exists
            createNewWallet()
        }
    }

    private fun createNewWallet() {
        val baseTime = System.currentTimeMillis()
        val newWallet = Wallet(
            id = UUID.randomUUID().toString(),
            name = "My Wallet",
            address = "unicity_wallet_${baseTime}",
            tokens = emptyList() // Start with empty tokens
        )
        saveWallet(newWallet)
    }

    private fun saveWallet(wallet: Wallet) {
        _wallet.value = wallet
        _tokens.value = wallet.tokens
        val walletJson = gson.toJson(wallet)
        sharedPreferences.edit {
            putString("wallet", walletJson)
        }
    }
}