package com.example.unicitywallet.utils

import org.apache.commons.codec.binary.Hex


/**
 * Utility functions for hex encoding/decoding.
 * Wraps Apache Commons Codec to handle Android compatibility.
 *
 * CRITICAL: Android's system framework has an OLD Apache Commons Codec (in /system/framework/org.apache.http.legacy.jar)
 * that only has these methods:
 * - Hex.decodeHex(char[]) returns byte[]
 * - Hex.encodeHex(byte[]) returns char[]
 *
 * The newer methods (decodeHex(String), encodeHexString(byte[])) are NOT available on Android 12!
 * This wrapper provides String-based API using the old char[] methods.
 */
object HexUtils {
    /**
     * Decode a hex string to bytes.
     * Android-compatible - uses char[] API internally.
     */
    @JvmStatic
    fun decodeHex(hex: String): ByteArray {
        return Hex.decodeHex(hex.toCharArray())
    }

    /**
     * Encode bytes to a hex string.
     * Android-compatible - uses char[] API (encodeHex) and converts to String.
     */
    @JvmStatic
    fun encodeHexString(bytes: ByteArray): String {
        return String(Hex.encodeHex(bytes))
    }
}