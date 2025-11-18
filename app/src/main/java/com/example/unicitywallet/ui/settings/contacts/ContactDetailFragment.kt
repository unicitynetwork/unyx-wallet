package com.example.unicitywallet.ui.settings.contacts

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import com.example.unicitywallet.R
import com.example.unicitywallet.data.model.Contact
import com.example.unicitywallet.databinding.FragmentContactDetailBinding
import com.example.unicitywallet.ui.settings.ContactsActivity

class ContactDetailsFragment : Fragment(R.layout.fragment_contact_detail) {

    private lateinit var binding: FragmentContactDetailBinding
    private var contactId: String? = null
    private var currentContact: Contact? = null

    // 1. Получаем ViewModel, используя фабрику из Activity
    private val viewModel: ContactsViewModel by activityViewModels {
        (requireActivity() as ContactsActivity).viewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactId = arguments?.getString(ARG_CONTACT_ID)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentContactDetailBinding.bind(view)

        // (Весь остальной код остается БЕЗ ИЗМЕНЕНИЙ)
        setEditMode(false)

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnEdit.setOnClickListener {
            setEditMode(true)
        }

        binding.btnApplyChanges.setOnClickListener {
            saveChanges()
        }

        viewModel.selectedContact.observe(viewLifecycleOwner, Observer { contact ->
            if (contact != null) {
                currentContact = contact
                binding.tvContactNameTitle.text = contact.name
                binding.etContactName.setText(contact.name)
                binding.etUnicityId.setText(contact.unicityId ?: "")
            }
        })

        contactId?.let {
            viewModel.loadContactById(it)
        }
    }

    private fun setEditMode(isEditing: Boolean) {
        binding.etContactName.isEnabled = isEditing
        binding.etUnicityId.isEnabled = isEditing
        binding.btnEdit.visibility = if (isEditing) View.GONE else View.VISIBLE
        binding.btnApplyChanges.visibility = if (isEditing) View.VISIBLE else View.GONE
    }

    private fun saveChanges() {
        val newName = binding.etContactName.text.toString().trim()
        val newUnicityId = binding.etUnicityId.text.toString().trim()

        if (newName.isEmpty()) {
            Toast.makeText(requireContext(), "Name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        currentContact?.let {
            viewModel.updateContact(
                contactId = it.id,
                newName = newName,
                newUnicityId = newUnicityId,
            )
            Toast.makeText(requireContext(), "Contact updated", Toast.LENGTH_SHORT).show()
            setEditMode(false)
        }
    }

    companion object {
        private const val ARG_CONTACT_ID = "contact_id"

        fun newInstance(contactId: String): ContactDetailsFragment {
            val fragment = ContactDetailsFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_CONTACT_ID, contactId)
            }
            return fragment
        }
    }
}