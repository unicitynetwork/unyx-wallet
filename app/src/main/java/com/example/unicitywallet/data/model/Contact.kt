package com.example.unicitywallet.data.model

data class Contact(
    val id: String,
    val name: String,
    val unicityId: String?,
    val avatarUrl: String? = null,
    val isFromPhone: Boolean,
) {
    // Get initials for avatar
    fun getInitials(): String {
        val parts = name.split(" ")
        return when {
            parts.size >= 2 -> "${parts[0].firstOrNull()?.uppercaseChar() ?: ""}${parts[1].firstOrNull()?.uppercaseChar() ?: ""}"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "?"
        }
    }

    // Check if any field contains @unicity
    fun hasUnicityTag(): Boolean {
        return unicityId.isNullOrBlank()
    }

    // Extract the unicity tag from the contact
//    fun getUnicityTag(): String {
//        // Check if address contains the tag in parentheses (from notes)
//        val parenMatch = Regex("\\((\\w+)@unicity\\)", RegexOption.IGNORE_CASE).find(address)
//        if (parenMatch != null) {
//            return parenMatch.groupValues[1]
//        }
//
//        // Try to extract from address directly
//        val addressMatch = Regex("(\\w+)@unicity", RegexOption.IGNORE_CASE).find(address)
//        if (addressMatch != null) {
//            return addressMatch.groupValues[1]
//        }
//
//        // Try to extract from name
//        val nameMatch = Regex("(\\w+)@unicity", RegexOption.IGNORE_CASE).find(name)
//        if (nameMatch != null) {
//            return nameMatch.groupValues[1]
//        }
//
//        return ""
//    }
}