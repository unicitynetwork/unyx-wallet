package com.example.unicitywallet.ui.wallet

import android.Manifest
import com.example.unicitywallet.R
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.unicitywallet.data.model.Contact
import com.example.unicitywallet.data.repository.ContactRepository
import com.example.unicitywallet.databinding.DialogContactListBinding
import com.example.unicitywallet.utils.ContactsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactListDialog(
    context: Context,
    private val repository: ContactRepository,
    private val onContactSelected: (Contact) -> Unit,
    private val onRequestPermission: ((String, Int) -> Unit)? = null
) : Dialog(context, R.style.FullScreenDialog) {

    private lateinit var binding: DialogContactListBinding
    private lateinit var contactAdapter: ContactAdapter
    private var allContacts = listOf<Contact>()
    private var showOnlyUnicity = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DialogContactListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up full screen dialog
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }

        setupViews()
        loadContacts()
    }

    private fun setupViews() {
        // Set up back button
        binding.btnBack.setOnClickListener {
            dismiss()
        }

        // Set up RecyclerView
        contactAdapter = ContactAdapter { contact ->
            val selectedContact = contact

            onContactSelected(selectedContact)

            Handler(Looper.getMainLooper()).postDelayed({
                dismiss()
            }, 50)
        }

        binding.contactsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = contactAdapter
        }

        // Set up search
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterContacts(s?.toString() ?: "")
            }
        })

        // Set up Unicity filter toggle
        binding.unicityFilterSwitch.setOnCheckedChangeListener { _, isChecked ->
            showOnlyUnicity = isChecked
            filterContacts(binding.searchEditText.text?.toString() ?: "")
        }
    }

    private fun loadContacts() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            loadMergedContacts()
        } else {
            if (onRequestPermission != null) {
                Toast.makeText(
                    context,
                    "Contact permission needed to show your contacts",
                    Toast.LENGTH_LONG
                ).show()
                onRequestPermission.invoke(Manifest.permission.READ_CONTACTS, REQUEST_CODE_CONTACTS)
            }
            loadMergedContacts()
        }
    }

    private fun loadMergedContacts() {
        binding.progressBar.visibility = View.VISIBLE
        binding.contactsRecyclerView.visibility = View.GONE

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val mergedContacts = withContext(Dispatchers.IO) {
                    repository.getMergedContacts()
                }

                binding.progressBar.visibility = View.GONE
                binding.contactsRecyclerView.visibility = View.VISIBLE

                allContacts = mergedContacts
                filterContacts("")

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onPermissionResult(requestCode: Int, granted: Boolean) {
        if (requestCode == REQUEST_CODE_CONTACTS && granted) {
            loadMergedContacts()
        }
    }

    private fun filterContacts(query: String) {
        val filtered = allContacts.filter { contact ->
            val matchesQuery = query.isEmpty() ||
                    contact.name.contains(query, ignoreCase = true) ||
                    contact.unicityId!!.contains(query, ignoreCase = true)

            val matchesUnicityFilter = !showOnlyUnicity || contact.hasUnicityTag()

            matchesQuery && matchesUnicityFilter
        }
        contactAdapter.submitList(filtered)

        // Show/hide empty state
        binding.emptyStateLayout.visibility = if (filtered.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
    companion object {
        const val REQUEST_CODE_CONTACTS = 1001
    }
}