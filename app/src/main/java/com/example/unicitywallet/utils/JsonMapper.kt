package com.example.unicitywallet.utils

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object JsonMapper {

    /**
     * Shared ObjectMapper instance configured for application-wide use
     *
     * Configuration:
     * - Kotlin module enabled for proper Kotlin class support
     * - Fails on unknown properties disabled (lenient parsing like Gson default)
     * - Pretty printing disabled (compact JSON like Gson default)
     * - Null values not included in output
     */
    val mapper: ObjectMapper = ObjectMapper().apply {
        // Register Kotlin module for data classes, default parameters, etc.
        registerKotlinModule()

        // Don't fail on unknown JSON properties (Gson-like behavior)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        // Don't pretty print by default (compact JSON like Gson)
        configure(SerializationFeature.INDENT_OUTPUT, false)

        // Don't include null values in JSON output
        setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    }

    /**
     * Serialize object to JSON string
     */
    inline fun <reified T> toJson(value: T): String = mapper.writeValueAsString(value)

    /**
     * Deserialize JSON string to object
     */
    inline fun <reified T> fromJson(json: String): T = mapper.readValue(json, T::class.java)

    /**
     * Deserialize JSON string to object with explicit class
     */
    fun <T> fromJson(json: String, clazz: Class<T>): T = mapper.readValue(json, clazz)
}
