package com.example.unicitywallet.data.repository

import android.util.Log
import com.example.unicitywallet.data.contact.ContactDao
import com.example.unicitywallet.data.contact.ContactEntity
import com.example.unicitywallet.data.model.Contact
import com.example.unicitywallet.utils.ContactsHelper


class ContactRepository(
    private val contactsHelper: ContactsHelper,
    private val contactDao: ContactDao
) {
    suspend fun getMergedContacts(): List<Contact> {
        // 1. Get contacts from the phone
        val phoneContacts = try {
            contactsHelper.loadPhoneContacts()
        } catch (e: SecurityException) {
            Log.w("ContactRepository", "No READ_CONTACTS permission. Returning only DB contacts.")
            emptyList()
        }

        // 2. Get saved / changed contacts
        val dbContacts = contactDao.getAll().associateBy { it.id }

        // 3. Merge lists
        val mergedList = mutableListOf<Contact>()

        for (phoneContact in phoneContacts) {
            val dbOverride = dbContacts[phoneContact.id] // Find the contact by id

            if (dbOverride != null) {
                // Contact from the Phone is found in db
                // Utilize contact from the db if it's changed
                mergedList.add(
                    Contact(
                        id = phoneContact.id,
                        name = dbOverride.name, // Apply name from db
                        unicityId = dbOverride.unicityId, // Apply unicity id from db
                        isFromPhone = true
                    )
                )
            } else {
                // Contact was not modified by the user
                mergedList.add(
                    Contact(
                        id = phoneContact.id,
                        name = phoneContact.name,
                        unicityId = phoneContact.unicityId,
                        isFromPhone = true
                    )
                )
            }
        }

        // 4 Add app created contacts
        val appCreatedContacts = dbContacts.values.filter { it.isAppCreated }
        for (appContact in appCreatedContacts) {
            mergedList.add(
                Contact(
                    id = appContact.id,
                    name = appContact.name,
                    unicityId = appContact.unicityId,
                    isFromPhone = false
                )
            )
        }

        return mergedList.sortedBy { it.name }
    }

    suspend fun getContactById(contactId: String) : Contact? {
        val dbContact = contactDao.getById(contactId)
        if (dbContact != null) {
            return Contact(
                id = dbContact.id,
                name = dbContact.name,
                unicityId = dbContact.unicityId,
                isFromPhone = !dbContact.isAppCreated
            )
        }
        val phoneContacts = contactsHelper.loadPhoneContacts().associateBy { it.id }
        return phoneContacts[contactId]
    }

    // Update contact
    suspend fun updateContact(contactId: String, newName: String, newUnicityId: String) {
        val existingEntity = contactDao.getById(contactId)
        val isAppCreated = existingEntity?.isAppCreated ?: false

        val entity = ContactEntity(
            id = contactId,
            name = newName,
            unicityId = newUnicityId,
            isAppCreated = isAppCreated
        )
        contactDao.insertOrUpdate(entity) // Room's @Insert(onConflict = OnConflictStrategy.REPLACE)
    }

    // Create new contact
    suspend fun saveNewContact(name: String, unicityId: String) {
        val entity = ContactEntity(
            id = java.util.UUID.randomUUID().toString(), // Generate new ID
            name = name,
            unicityId = unicityId,
            isAppCreated = true
        )
        contactDao.insertOrUpdate(entity)
    }
}