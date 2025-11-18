package com.example.unicitywallet.ui.settings.contacts

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.unicitywallet.R
import com.example.unicitywallet.databinding.FragmentContactsBinding
import com.example.unicitywallet.ui.settings.ContactsActivity
import com.example.unicitywallet.ui.wallet.ContactAdapter

class ContactsFragment : Fragment(R.layout.fragment_contacts) {

    private lateinit var binding: FragmentContactsBinding
    private lateinit var contactAdapter: ContactAdapter
    private val viewModel: ContactsViewModel by activityViewModels {
        (requireActivity() as ContactsActivity).viewModelFactory
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentContactsBinding.bind(view)

        setupRecyclerView()

        viewModel.filteredContacts.observe(viewLifecycleOwner, Observer { contacts ->
            contactAdapter.submitList(contacts)
        })

        binding.btnAddContact.setOnClickListener {
            (activity as? ContactsActivity)?.showAddContactScreen()
        }

        binding.btnBack.setOnClickListener {
            activity?.finish()
        }

        binding.etSearchContacts.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.filterContacts(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupRecyclerView() {
        contactAdapter = ContactAdapter { contact ->
            (activity as? ContactsActivity)?.showContactDetailScreen(contact.id)
        }
        binding.rvContacts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = contactAdapter
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshContacts()
    }

    companion object {
        const val REQUEST_CODE_CONTACTS = 1001
    }
}