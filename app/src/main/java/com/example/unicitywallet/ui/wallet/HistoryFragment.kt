package com.example.unicitywallet.ui.wallet

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.example.unicitywallet.R
import com.example.unicitywallet.databinding.FragmentTransactionHistoryBinding
import kotlin.getValue
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.unicitywallet.viewmodel.WalletViewModel
import androidx.recyclerview.widget.RecyclerView

class HistoryFragment : Fragment(R.layout.fragment_transaction_history) {
    private lateinit var binding: FragmentTransactionHistoryBinding
    private val viewModel: WalletViewModel by viewModels()
    private lateinit var tokenHistoryAdapter: TokenHistoryAdapter

    private var currentNametagString: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentTransactionHistoryBinding.bind(view)

        setupUI()
    }

    private fun setupUI(){

        val prefs = requireContext()
            .getSharedPreferences("UnicityWalletPrefs", Context.MODE_PRIVATE)
        currentNametagString = prefs.getString("unicity_tag", null)

        binding.tvNametag.text = "$currentNametagString@unicity"

        val transactionEvents = viewModel.transactionHistory.value

        if (transactionEvents.isEmpty()){
            binding.emptyStateContainer.visibility = View.VISIBLE
            return
        }

        tokenHistoryAdapter = TokenHistoryAdapter(transactionEvents, viewModel.aggregatedAssets.value)
        binding.rvAssets.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = tokenHistoryAdapter
        }

        tokenHistoryAdapter = TokenHistoryAdapter(transactionEvents, viewModel.aggregatedAssets.value)
        binding.rvAssets.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = tokenHistoryAdapter
        }

        binding.rvAssets.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                binding.swipeRefreshLayout.isEnabled = !recyclerView.canScrollVertically(-1)
            }
        })
    }
}