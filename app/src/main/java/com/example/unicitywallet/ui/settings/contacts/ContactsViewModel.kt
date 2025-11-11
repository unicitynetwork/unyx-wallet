package com.example.unicitywallet.ui.settings.contacts

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.unicitywallet.data.model.Contact
import com.example.unicitywallet.data.repository.ContactRepository
import kotlinx.coroutines.launch

class ContactsViewModel(private val repository: ContactRepository) : ViewModel() {
    private val _allContacts = MutableLiveData<List<Contact>>()
    val filteredContacts: LiveData<List<Contact>> get() = _allContacts
    private val _selectedContact = MutableLiveData<Contact?>()
    val selectedContact: LiveData<Contact?> get() = _selectedContact
    private var fullContactList: List<Contact> = emptyList()

    init {
        refreshContacts()
    }

    fun refreshContacts() {
        viewModelScope.launch {
            fullContactList = repository.getMergedContacts()
            _allContacts.postValue(fullContactList)
        }
    }

    /**
     * Filters contacts depending on the request
     */
    fun filterContacts(query: String) {
        if (query.isEmpty()) {
            _allContacts.postValue(fullContactList)
            return
        }
        val filtered = fullContactList.filter { contact ->
            contact.name.contains(query, ignoreCase = true) ||
                    (contact.unicityId?.contains(query, ignoreCase = true) == true)
        }
        _allContacts.postValue(filtered)
    }

    /**
     * Gets one contact by id
     */
    fun loadContactById(id: String) {
        viewModelScope.launch {
            val contact = repository.getContactById(id)
            _selectedContact.postValue(contact)
        }
    }

    /**
     * Adds new contact
     */
    fun addNewContact(name: String, unicityId: String) {
        viewModelScope.launch {
            repository.saveNewContact(name, unicityId)
            refreshContacts()
        }
    }

    /**
     * Updates existing contact
     */
    fun updateContact(contactId: String, newName: String, newUnicityId: String) {
        viewModelScope.launch {
            repository.updateContact(contactId, newName, newUnicityId)
            refreshContacts()
        }
    }
}