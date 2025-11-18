package com.example.unicitywallet.ui.settings.contacts

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.unicitywallet.R
import com.example.unicitywallet.databinding.FragmentAddContactBinding
import com.example.unicitywallet.ui.settings.ContactsActivity

class AddContactFragment : Fragment(R.layout.fragment_add_contact) {

    private lateinit var binding: FragmentAddContactBinding
    private val viewModel: ContactsViewModel by activityViewModels {
        (requireActivity() as ContactsActivity).viewModelFactory
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentAddContactBinding.bind(view)

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnAdd.setOnClickListener {
            val name = binding.etContactName.text.toString().trim()
            val unicityId = binding.etUnicityId.text.toString().trim()

            if (name.isEmpty() || unicityId.isEmpty()) {
                Toast.makeText(requireContext(), "Please fill all fields", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.addNewContact(name, unicityId)
                Toast.makeText(requireContext(), "Contact added", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
        }
    }
}