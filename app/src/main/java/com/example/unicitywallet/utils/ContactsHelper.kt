package com.example.unicitywallet.utils

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import com.example.unicitywallet.data.model.Contact
import com.example.unicitywallet.nostr.NostrSdkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.unicitylabs.sdk.api.RequestId
import org.unicitylabs.sdk.token.TokenId

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

            contactsMap.values.forEach { contactInfo ->

                val hasUnicityInNotes = contactInfo.notes?.contains("@unicity", ignoreCase = true) ?: false
                val phone = contactInfo.phoneNumber
                Log.d("WALLET HELPER", "$phone")
                var contactUnicityId: String? = null

                if (hasUnicityInNotes && contactInfo.notes != null) {
                    val notes = contactInfo.notes ?: ""
                    // Match word characters, hyphens, and dots before @unicity
                    val noteMatch = Regex("([\\w.-]+)@unicity", RegexOption.IGNORE_CASE).find(notes)
                    if (noteMatch != null) {
                        contactUnicityId = noteMatch.groupValues[0] // Get the full match including @unicity
                    }
                }

                if (contactUnicityId == null && phone != null && isPhoneNumberAUnicityId(phone)) {
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


    private suspend fun isPhoneNumberAUnicityId(phoneNumber: String): Boolean {
        val cleanedPhoneNumber = phoneNumber.replace(Regex("[^\\d+]"), "")

        if (cleanedPhoneNumber.isEmpty()) {
            return false
        }

        val nostrService = NostrSdkService.getInstance(context)
        if (nostrService == null) {
            Log.w("ContactsHelper", "Nostr service not available")
            return false
        }

        if (!nostrService.isRunning()) {
            nostrService.start()
            delay(2000)
        }

        val recipientPubkey = nostrService.queryPubkeyByNametag(cleanedPhoneNumber)

        if (recipientPubkey == null) {
            return false
        }

        return true
    }

    private data class ContactInfo(
        val id: String,
        val name: String,
        val photoUri: String?,
        var phoneNumber: String?,
        var notes: String? = null
    )
}