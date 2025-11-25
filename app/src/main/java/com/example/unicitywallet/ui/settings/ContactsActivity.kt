package com.example.unicitywallet.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.unicitywallet.R
import com.example.unicitywallet.data.repository.ContactRepository
import com.example.unicitywallet.data.contact.ContactDatabase
import com.example.unicitywallet.ui.settings.contacts.AddContactFragment
import com.example.unicitywallet.ui.settings.contacts.ContactDetailsFragment
import com.example.unicitywallet.ui.settings.contacts.ContactsFragment
import com.example.unicitywallet.ui.settings.contacts.ContactsViewModel
import com.example.unicitywallet.utils.ContactsHelper

class ContactsActivity : AppCompatActivity() {

    private lateinit var contactRepository: ContactRepository
    lateinit var viewModelFactory: ViewModelProvider.Factory
        private set
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                loadContactsFragment()
            } else {
                Toast.makeText(
                    this,
                    "Permission needed to read contacts",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)

        val db = ContactDatabase.getInstance(applicationContext)
        val helper = ContactsHelper(applicationContext)
        contactRepository = ContactRepository(helper, db.contactDao())

        viewModelFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(ContactsViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return ContactsViewModel(contactRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }

        if (savedInstanceState == null) {
            checkPermissionAndLoadFragment()
        }
    }
    private fun checkPermissionAndLoadFragment() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED -> {
                loadContactsFragment()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    private fun loadContactsFragment() {
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            showFragment(ContactsFragment(), isInitial = true)
        }
    }

    private fun showFragment(fragment: Fragment, isInitial: Boolean = false) {
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.fragment_container, fragment)
        if (!isInitial) {
            transaction.addToBackStack(null)
        }
        transaction.commit()
    }

    fun showAddContactScreen() {
        showFragment(AddContactFragment())
    }

    fun showContactDetailScreen(contactId: String) {
        val fragment = ContactDetailsFragment.newInstance(contactId)
        showFragment(fragment)
    }
}