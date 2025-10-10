package com.example.unicitywallet.nostr

import android.content.Context
import com.example.unicitywallet.utils.JsonMapper
import com.example.unicitywallet.utils.NametagUtils
import org.spongycastle.crypto.digests.SHA256Digest
import org.spongycastle.util.encoders.Hex

class NostrNametagBinding {

    companion object {
        private const val TAG = "NostrNametagBinding"

        // Kind 30078: Parameterized replaceable event for application-specific data
        const val KIND_NAMETAG_BINDING = 30078
        const val TAG_D_VALUE = "unicity-nametag"
    }

    /**
     * Create a binding event that maps a Nostr pubkey to a Unicity nametag
     * This is a replaceable event - newer events automatically replace older ones
     * Nametags are hashed for privacy (including phone numbers)
     */
    fun createBindingEvent(
        publicKeyHex: String,
        nametagId: String,
        unicityAddress: String,
        keyManager: NostrKeyManager,
        context: Context? = null
    ): Event {
        val createdAt = System.currentTimeMillis() / 1000

        // Hash the nametag for privacy (works for both regular nametags and phone numbers)
        val hashedNametag = NametagUtils.hashNametag(nametagId, context)

        // Create tags for the replaceable event
        val tags = mutableListOf<List<String>>()
        // IMPORTANT: Use hashed nametag as d-tag to allow multiple nametags per pubkey
        // Each nametag gets its own event: (pubkey, kind, d-tag) uniquely identifies the event
        tags.add(listOf("d", hashedNametag))  // Unique per nametag - allows multiple bindings
        tags.add(listOf("nametag", hashedNametag))  // Store HASHED nametag for privacy
        tags.add(listOf("t", hashedNametag))  // IMPORTANT: Use 't' tag which is indexed by relay
        tags.add(listOf("address", unicityAddress))  // Store Unicity address

        // Create content with binding information (don't include raw nametag for privacy)
        val contentData = mapOf(
            "nametag_hash" to hashedNametag,  // Only store hash
            "address" to unicityAddress,
            "verified" to System.currentTimeMillis()
        )
        val content = JsonMapper.toJson(contentData)

        // Create event data for ID calculation (canonical JSON array)
        val eventData = listOf(
            0,
            publicKeyHex,
            createdAt,
            KIND_NAMETAG_BINDING,
            tags,
            content
        )

        val eventJson = JsonMapper.toJson(eventData)

        // Calculate event ID (SHA-256 of canonical JSON)
        val digest = SHA256Digest()
        val eventJsonBytes = eventJson.toByteArray()
        digest.update(eventJsonBytes, 0, eventJsonBytes.size)
        val eventIdBytes = ByteArray(32)
        digest.doFinal(eventIdBytes, 0)
        val eventId = Hex.toHexString(eventIdBytes)

        // Sign with Schnorr signature using NostrKeyManager
        val signature = keyManager.sign(eventIdBytes)
        val signatureHex = Hex.toHexString(signature)

        return Event(
            id = eventId,
            pubkey = publicKeyHex,
            created_at = createdAt,
            kind = KIND_NAMETAG_BINDING,
            tags = tags,
            content = content,
            sig = signatureHex
        )
    }

    /**
     * Create a filter to find Nostr pubkey by nametag
     * This is the ONLY direction needed: nametag → pubkey
     * Used by senders to find where to send the Nostr message
     * Nametags are hashed before querying for privacy
     */
    fun createNametagToPubkeyFilter(nametagId: String, context: Context? = null): Map<String, Any> {
        // Hash the nametag for querying (works for both regular nametags and phone numbers)
        val hashedNametag = NametagUtils.hashNametag(nametagId, context)

        val filter = mutableMapOf<String, Any>()
        filter["kinds"] = listOf(KIND_NAMETAG_BINDING)
        filter["#t"] = listOf(hashedNametag)  // Query by HASHED nametag using indexed 't' tag
        filter["limit"] = 1
        return filter
    }
}
