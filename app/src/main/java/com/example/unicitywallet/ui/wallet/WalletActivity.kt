package com.example.unicitywallet.ui.wallet

import android.animation.ObjectAnimator
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.unicitywallet.R
import com.example.unicitywallet.databinding.ActivityWalletHomeBinding
import com.example.unicitywallet.viewmodel.WalletViewModel
import kotlinx.coroutines.launch
import kotlin.getValue

class WalletActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWalletHomeBinding
    private val viewModel: WalletViewModel by viewModels()
    private var selectedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWalletHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Collect history flows to keep them active
        lifecycleScope.launch {
            viewModel.incomingHistory.collect { /* Keep flow active */ }
        }
        lifecycleScope.launch {
            viewModel.outgoingHistory.collect { /* Keep flow active */ }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, WalletFragment())
                .commit()
        }

        setupBottomNav()
    }

    private fun setupBottomNav(){
        val tabs = listOf(
            binding.tabWallet,
            binding.tabHistory,
            binding.tabChat,
            binding.tabNotifications,
            binding.tabProfile
        )

        val icons = listOf(
            binding.iconWallet,
            binding.iconHistory,
            binding.iconChat,
            binding.iconNotifications,
            binding.iconProfile
        )

        binding.bottomNav.post {
            val tabWidth = binding.bottomNav.width / tabs.size
            binding.activeIndicator.layoutParams.width = tabWidth
            binding.activeIndicator.requestLayout()
        }

        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                if (selectedIndex != index) {
                    moveIndicatorTo(index)
                    highlightTab(index, icons)
                    changeFragment(index)
                    selectedIndex = index
                }
            }
        }

        highlightTab(0, icons)
    }

    private fun moveIndicatorTo(index: Int) {
        val tabCount = 5
        val tabWidth = binding.bottomNav.width / tabCount
        val targetX = tabWidth * index

        ObjectAnimator.ofFloat(binding.activeIndicator, "translationX", targetX.toFloat()).apply {
            duration = 300
            start()
        }
    }
    private fun highlightTab(index: Int, icons: List<android.widget.ImageView>) {
        val activeColor = ContextCompat.getColor(this, R.color.active_tab)
        val inactiveColor = ContextCompat.getColor(this, R.color.inactive_tab)

        icons.forEachIndexed { i, icon ->
            icon.setColorFilter(if (i == index) activeColor else inactiveColor)
        }
    }

    private fun changeFragment(index: Int) {
        val fragment = when (index) {
            0 -> WalletFragment()
            1 -> HistoryFragment()
//            2 -> ChatFragment()
//            3 -> NotificationsFragment()
//            4 -> ProfileFragment()
            else -> WalletFragment()
        }

        supportFragmentManager.beginTransaction()
//            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}