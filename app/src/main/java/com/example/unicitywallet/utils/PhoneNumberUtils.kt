package com.example.unicitywallet.utils

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.security.MessageDigest
import java.util.Locale

object PhoneNumberUtils {
    private val phoneUtil = PhoneNumberUtil.getInstance()

    /**
     * Normalize a phone number to E.164 format for deterministic hashing.
     * E.164 format: +[country code][area code][local number]
     * Example: +14155552671 (always starts with +, no spaces or special chars)
     *
     * @param phoneNumber The phone number in any format (with or without country code)
     * @param context Android context for getting default country
     * @return Normalized E.164 phone number or null if invalid
     */
    fun normalizePhoneNumber(phoneNumber: String, context: Context): String? {
        // Clean basic formatting
        val cleaned = phoneNumber.trim()
        if (cleaned.isEmpty()) return null

        // Get default country from device settings
        val defaultCountry = getDefaultCountryCode(context)

        return try {
            // Parse the phone number with default country
            val parsedNumber = if (cleaned.startsWith("+")) {
                // Already has country code
                phoneUtil.parse(cleaned, null)
            } else {
                // Use default country for local numbers
                phoneUtil.parse(cleaned, defaultCountry)
            }

            // Validate the parsed number
            if (!phoneUtil.isValidNumber(parsedNumber)) {
                return null
            }

            // Format to E.164 (e.g., +14155552671)
            phoneUtil.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (e: NumberParseException) {
            // Invalid phone number format
            null
        }
    }

    /**
     * Get the default country code from device settings.
     * Falls back to US if unable to determine.
     */
    private fun getDefaultCountryCode(context: Context): String {
        return try {
            // Try to get from telephony manager (SIM card)
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val simCountry = tm?.simCountryIso?.uppercase(Locale.ROOT)

            if (!simCountry.isNullOrEmpty()) {
                return simCountry
            }

            // Fall back to network country
            val networkCountry = tm?.networkCountryIso?.uppercase(Locale.ROOT)
            if (!networkCountry.isNullOrEmpty()) {
                return networkCountry
            }

            // Fall back to locale
            val localeCountry = Locale.getDefault().country
            if (localeCountry.isNotEmpty()) {
                return localeCountry
            }

            // Default fallback
            "US"
        } catch (e: Exception) {
            "US"
        }
    }

    /**
     * Create a deterministic hash of a normalized phone number for privacy-preserving discovery.
     * Uses SHA-256 with a standard salt prefix.
     *
     * @param normalizedPhone E.164 formatted phone number
     * @return Hex-encoded hash of the phone number
     */
    fun hashPhoneNumber(normalizedPhone: String): String {
        val salt = "unicity:phone:"
        val input = salt + normalizedPhone

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))

        return hashBytes.joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    /**
     * Convenience method to normalize and hash a phone number in one step.
     *
     * @param phoneNumber Phone number in any format
     * @param context Android context
     * @return Hashed phone number or null if normalization failed
     */
    fun normalizeAndHash(phoneNumber: String, context: Context): String? {
        val normalized = normalizePhoneNumber(phoneNumber, context)
        return normalized?.let { hashPhoneNumber(it) }
    }

    /**
     * Format a phone number for display purposes.
     * Attempts to format in national format for local numbers.
     *
     * @param phoneNumber Phone number in any format
     * @param context Android context
     * @return Formatted phone number for display
     */
    fun formatForDisplay(phoneNumber: String, context: Context): String {
        val normalized = normalizePhoneNumber(phoneNumber, context) ?: return phoneNumber
        val defaultCountry = getDefaultCountryCode(context)

        return try {
            val parsed = phoneUtil.parse(normalized, null)

            // Check if it's a local number
            val countryCode = phoneUtil.getRegionCodeForNumber(parsed)
            if (countryCode == defaultCountry) {
                // Format as national number (no country code)
                phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
            } else {
                // Format as international
                phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
            }
        } catch (e: Exception) {
            phoneNumber
        }
    }

    /**
     * Extract country code from a normalized phone number.
     *
     * @param normalizedPhone E.164 formatted phone number
     * @return Country calling code (e.g., "1" for US/Canada, "44" for UK)
     */
    fun getCountryCode(normalizedPhone: String): String? {
        return try {
            val parsed = phoneUtil.parse(normalizedPhone, null)
            parsed.countryCode.toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if two phone numbers are the same after normalization.
     * Handles different formats of the same number.
     *
     * @param phone1 First phone number
     * @param phone2 Second phone number
     * @param context Android context
     * @return true if both numbers normalize to the same E.164 format
     */
    fun areSameNumber(phone1: String, phone2: String, context: Context): Boolean {
        val normalized1 = normalizePhoneNumber(phone1, context)
        val normalized2 = normalizePhoneNumber(phone2, context)

        return normalized1 != null && normalized1 == normalized2
    }
}