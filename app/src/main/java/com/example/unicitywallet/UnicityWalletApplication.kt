package com.example.unicitywallet

import android.app.Application
import com.example.unicitywallet.services.ServiceProvider

class UnicityWalletApplication : Application(){
    override fun onCreate() {
        super.onCreate()

        // Initialize ServiceProvider with application context
        // This ensures trustbase is loaded from assets on app startup
        ServiceProvider.init(this)
    }
}