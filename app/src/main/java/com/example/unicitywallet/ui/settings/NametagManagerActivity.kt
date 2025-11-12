package com.example.unicitywallet.ui.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.unicitywallet.databinding.ActivityNametagManagerBinding
import com.example.unicitywallet.services.NametagService
import com.example.unicitywallet.ui.settings.nametags.NametagAdapter
import com.example.unicitywallet.ui.settings.nametags.NametagViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NametagManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNametagManagerBinding
    private lateinit var viewModel: NametagViewModel
    private lateinit var adapter: NametagAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNametagManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val nametagService = NametagService(applicationContext)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return NametagViewModel(nametagService) as T
            }
        }
        viewModel = ViewModelProvider(this, factory)[NametagViewModel::class.java]

        adapter = NametagAdapter { selectedItem ->
            viewModel.selectNametag(selectedItem.name)
        }
        binding.rvNametags.layoutManager = LinearLayoutManager(this)
        binding.rvNametags.adapter = adapter

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnMint.setOnClickListener {
            val text = binding.etNametagInput.text.toString()
            if (text.isNotEmpty()) {
                viewModel.mintNametag(text)
            } else {
                Toast.makeText(this, "Please enter a Nametag", Toast.LENGTH_SHORT).show()
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                viewModel.loadData()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.progressBarMint.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                binding.btnMint.isEnabled = !state.isLoading
                binding.btnMint.text = if (state.isLoading) "" else "Mint"

                val query = binding.etSearch.text.toString()
                val listToShow = if (query.isBlank()) {
                    state.nametags
                } else {
                    state.nametags.filter { it.name.contains(query, true) }
                }
                adapter.submitList(listToShow)

                state.error?.let {
                    Toast.makeText(this@NametagManagerActivity, it, Toast.LENGTH_LONG).show()
                    viewModel.clearMessages()
                }

                state.successMessage?.let {
                    Toast.makeText(this@NametagManagerActivity, it, Toast.LENGTH_SHORT).show()
                    binding.etNametagInput.setText("")
                    viewModel.clearMessages()
                }
            }
        }
    }
}