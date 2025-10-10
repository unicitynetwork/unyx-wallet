package com.example.unicitywallet.utils

import android.content.Context
import java.security.MessageDigest

object NametagUtils {

    private const val NAMETAG_SALT = "unicity:nametag:"

    /**
     * Normalize and hash a nametag for privacy-preserving storage on Nostr.
     * Works for both regular nametags and phone numbers.
     *
     * @param nametag The nametag string (e.g., "alice" or "+14155552671")
     * @param context Android context (for phone normalization if needed)
     * @return Hex-encoded SHA-256 hash of the nametag
     */
    fun hashNametag(nametag: String, context: Context? = null): String {
        // Normalize the nametag
        val normalized = normalizeNametag(nametag, context)

        // Hash with salt
        val input = NAMETAG_SALT + normalized
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))

        return hashBytes.joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    /**
     * Normalize a nametag before hashing.
     * - If it looks like a phone number, normalize to E.164
     * - Otherwise, lowercase and trim
     *
     * @param nametag The raw nametag
     * @param context Android context (optional, for phone normalization)
     * @return Normalized nametag
     */
    fun normalizeNametag(nametag: String, context: Context? = null): String {
        val trimmed = nametag.trim()

        // Check if it looks like a phone number
        // Phone patterns: starts with +, or contains mostly digits
        if (isLikelyPhoneNumber(trimmed)) {
            // If we have context, try to normalize as phone
            if (context != null) {
                val normalized = PhoneNumberUtils.normalizePhoneNumber(trimmed, context)
                if (normalized != null) {
                    return normalized
                }
            }
            // If no context or normalization failed, just clean digits
            return trimmed.filter { it.isDigit() || it == '+' }
        }

        // For regular nametags: lowercase, no @ suffix
        return trimmed.lowercase().removeSuffix("@unicity")
    }

    /**
     * Check if a string looks like a phone number.
     * Simple heuristic: starts with + or has >50% digits
     */
    private fun isLikelyPhoneNumber(str: String): Boolean {
        if (str.startsWith("+")) return true

        val digitCount = str.count { it.isDigit() }
        val totalCount = str.length

        // More than 50% digits and at least 7 digits total
        return digitCount >= 7 && digitCount.toFloat() / totalCount > 0.5f
    }

    /**
     * Create a display-friendly version of a nametag.
     * Hides middle digits for phone numbers.
     *
     * @param nametag The nametag
     * @param context Android context (for phone formatting)
     * @return Display-friendly version
     */
    fun formatForDisplay(nametag: String, context: Context? = null): String {
        val normalized = normalizeNametag(nametag, context)

        // Check if it's a phone number
        if (isLikelyPhoneNumber(normalized)) {
            // Hide middle digits: +1415***2671
            if (normalized.length >= 10) {
                val start = normalized.take(5)
                val end = normalized.takeLast(4)
                return "$start***$end"
            }
        }

        // Regular nametag - return as-is
        return nametag
    }

    /**
     * Check if two nametags resolve to the same hash.
     * Useful for comparing different formats of the same identifier.
     *
     * @param tag1 First nametag
     * @param tag2 Second nametag
     * @param context Android context
     * @return true if both hash to the same value
     */
    fun areSameNametag(tag1: String, tag2: String, context: Context? = null): Boolean {
        return hashNametag(tag1, context) == hashNametag(tag2, context)
    }
}