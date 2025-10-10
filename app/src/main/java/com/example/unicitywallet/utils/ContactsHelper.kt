package com.example.unicitywallet.utils

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import com.example.unicitywallet.data.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactsHelper(private val context: Context) {

    companion object {
        private const val TAG = "ContactsHelper"
    }

    suspend fun loadPhoneContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<Contact>()
        val contactsMap = mutableMapOf<String, ContactInfo>()

        try {
            val contentResolver: ContentResolver = context.contentResolver

            // First, get all contacts with their display names
            val contactsCursor: Cursor? = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER,
                    ContactsContract.Contacts.PHOTO_URI
                ),
                null,
                null,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
            )

            contactsCursor?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)) ?: continue
                    val photoUri = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI))

                    contactsMap[id] = ContactInfo(id, name, photoUri)
                }
            }

            // Then get email addresses
            val emailCursor: Cursor? = contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.ADDRESS
                ),
                null,
                null,
                null
            )

            emailCursor?.use { cursor ->
                while (cursor.moveToNext()) {
                    val contactId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID))
                    val email = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS))

                    contactsMap[contactId]?.let { contactInfo ->
                        if (!email.isNullOrBlank()) {
                            contactInfo.emails.add(email)
                        }
                    }
                }
            }

            // Get phone numbers
            val phoneCursor: Cursor? = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )

            phoneCursor?.use { cursor ->
                while (cursor.moveToNext()) {
                    val contactId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID))
                    val phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))

                    contactsMap[contactId]?.let { contactInfo ->
                        if (!phoneNumber.isNullOrBlank()) {
                            contactInfo.phoneNumbers.add(phoneNumber)
                        }
                    }
                }
            }

            // Get notes
            val notesCursor: Cursor? = contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Note.NOTE
                ),
                ContactsContract.Data.MIMETYPE + " = ?",
                arrayOf(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
                null
            )

            notesCursor?.use { cursor ->
                while (cursor.moveToNext()) {
                    val contactId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID))
                    val note = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Note.NOTE))

                    contactsMap[contactId]?.let { contactInfo ->
                        if (!note.isNullOrBlank()) {
                            contactInfo.notes = note
                        }
                    }
                }
            }

            // Convert to Contact objects
            contactsMap.values.forEach { contactInfo ->
                // Check if notes contain @unicity and extract the tag
                val hasUnicityInNotes = contactInfo.notes?.contains("@unicity", ignoreCase = true) ?: false
                var unicityTagFromNotes: String? = null

                if (hasUnicityInNotes && contactInfo.notes != null) {
                    val notes = contactInfo.notes ?: ""
                    val noteMatch = Regex("(\\w+)@unicity", RegexOption.IGNORE_CASE).find(notes)
                    if (noteMatch != null) {
                        unicityTagFromNotes = noteMatch.groupValues[0] // Get the full match including @unicity
                    }
                }

                // Create a contact for each email
                contactInfo.emails.forEach { email ->
                    contacts.add(
                        Contact(
                            id = "${contactInfo.id}_email_$email",
                            name = contactInfo.name,
                            address = if (hasUnicityInNotes && !email.contains("@unicity", ignoreCase = true) && unicityTagFromNotes != null) {
                                "$email ($unicityTagFromNotes)"
                            } else {
                                email
                            },
                            avatarUrl = contactInfo.photoUri,
                            isUnicityUser = email.contains("@unicity", ignoreCase = true) || hasUnicityInNotes
                        )
                    )
                }

                // Create a contact for each phone number if no emails
                if (contactInfo.emails.isEmpty() && contactInfo.phoneNumbers.isNotEmpty()) {
                    contacts.add(
                        Contact(
                            id = "${contactInfo.id}_phone",
                            name = contactInfo.name,
                            address = if (hasUnicityInNotes && unicityTagFromNotes != null) {
                                "${contactInfo.phoneNumbers.first()} ($unicityTagFromNotes)"
                            } else {
                                contactInfo.phoneNumbers.first()
                            },
                            avatarUrl = contactInfo.photoUri,
                            isUnicityUser = hasUnicityInNotes
                        )
                    )
                }

                // If no emails or phones, still add the contact with just the name
                if (contactInfo.emails.isEmpty() && contactInfo.phoneNumbers.isEmpty()) {
                    contacts.add(
                        Contact(
                            id = contactInfo.id,
                            name = contactInfo.name,
                            address = if (hasUnicityInNotes && unicityTagFromNotes != null) {
                                unicityTagFromNotes
                            } else {
                                "No contact info"
                            },
                            avatarUrl = contactInfo.photoUri,
                            isUnicityUser = hasUnicityInNotes
                        )
                    )
                }
            }

            Log.d(TAG, "Loaded ${contacts.size} contacts from phone")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading contacts", e)
        }

        contacts.sortedBy { it.name }
    }

    private data class ContactInfo(
        val id: String,
        val name: String,
        val photoUri: String?,
        val emails: MutableList<String> = mutableListOf(),
        val phoneNumbers: MutableList<String> = mutableListOf(),
        var notes: String? = null
    )
}