package com.example.unicitywallet.sdk

import com.example.unicitywallet.utils.JsonMapper
import com.fasterxml.jackson.annotation.JsonProperty

data class UnicityIdentity(
    val privateKey: String,
    val nonce: String
) {
    fun toJson(): String = JsonMapper.toJson(this)

    companion object {
        fun fromJson(json: String): UnicityIdentity = JsonMapper.fromJson(json)
    }
}

data class UnicityTokenData(
    val data: String = "Default token data",
    val amount: Long = 100,
    val stateData: String = "Default state"
) {
    fun toJson(): String = JsonMapper.toJson(this)

    companion object {
        fun fromJson(json: String): UnicityTokenData = JsonMapper.fromJson(json)
    }
}

data class UnicityMintResult(
    val token: Any,
    val identity: UnicityIdentity,
    val status: String? = null  // Add status field for pending tokens
) {
    fun toJson(): String = JsonMapper.toJson(this)

    companion object {
        fun fromJson(json: String): UnicityMintResult = JsonMapper.fromJson(json)

        fun success(tokenJson: String): UnicityMintResult {
            // Parse token JSON to extract identity if possible
            // For now, create a dummy identity since we need to refactor this
            val dummyIdentity = UnicityIdentity("", "")
            return UnicityMintResult(tokenJson, dummyIdentity, "success")
        }

        fun error(message: String): UnicityMintResult {
            val dummyIdentity = UnicityIdentity("", "")
            return UnicityMintResult(message, dummyIdentity, "error")
        }
    }
}

data class UnicityTransferResult(
    val token: Any,
    val transaction: Any,
    @JsonProperty("receiverPredicate")  // Jackson uses @JsonProperty instead of @SerializedName
    val receiverPredicate: UnicityIdentity
) {
    fun toJson(): String = JsonMapper.toJson(this)

    companion object {
        fun fromJson(json: String): UnicityTransferResult = JsonMapper.fromJson(json)
    }
}

data class UnicityToken(
    val id: String,
    val name: String,
    val data: String,
    val size: Int,
    val identity: UnicityIdentity? = null,
    val mintResult: UnicityMintResult? = null,
    val isReal: Boolean = true
) {
    fun toJson(): String = JsonMapper.toJson(this)

    companion object {
        fun fromJson(json: String): UnicityToken = JsonMapper.fromJson(json)
    }
}
