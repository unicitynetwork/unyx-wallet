package com.example.unicitywallet.utils

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import com.example.unicitywallet.data.model.Contact // Убедитесь, что это НОВАЯ модель
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactsHelper(private val context: Context) {

    companion object {
        private const val TAG = "ContactsHelper"
    }

    @Throws(SecurityException::class)
    suspend fun loadPhoneContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<Contact>()
        val contactsMap = mutableMapOf<String, ContactInfo>()

        try {
            val contentResolver: ContentResolver = context.contentResolver

            val contactsCursor: Cursor? = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.PHOTO_URI
                ),
                null, null,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
            )

            contactsCursor?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)) ?: continue
                    val photoUri = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI))

                    contactsMap[id] = ContactInfo(id, name, photoUri, null)
                }
            }

            val phoneCursor: Cursor? = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )

            phoneCursor?.use { cursor ->
                while (cursor.moveToNext()) {
                    val contactId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID))
                    val phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))

                    contactsMap[contactId]?.let { contactInfo ->
                        if (!phoneNumber.isNullOrBlank() && contactInfo.phoneNumber == null) {
                            contactInfo.phoneNumber = phoneNumber
                        }
                    }
                }
            }

            contactsMap.values.forEach { contactInfo ->

                val phone = contactInfo.phoneNumber
                var contactUnicityId: String? = null

                if (phone != null && isPhoneNumberAUnicityId(phone)) {
                    contactUnicityId = phone
                }

                contacts.add(
                    Contact(
                        id = contactInfo.id,
                        name = contactInfo.name,
                        unicityId = contactUnicityId,
                        avatarUrl = contactInfo.photoUri,
                        isFromPhone = true
                    )
                )
            }

            Log.d(TAG, "Loaded ${contacts.size} contacts from phone")

        } catch (se: SecurityException) {
            Log.w(TAG, "No READ_CONTACTS permission.", se)
            throw se
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contacts", e)
        }

        return@withContext contacts.sortedBy { it.name }
    }


    private fun isPhoneNumberAUnicityId(phoneNumber: String): Boolean {
        //TODO Implement logic to check if there is unicity id minted to the phone number
        return phoneNumber.contains("555")
    }

    private data class ContactInfo(
        val id: String,
        val name: String,
        val photoUri: String?,
        var phoneNumber: String?
    )
}