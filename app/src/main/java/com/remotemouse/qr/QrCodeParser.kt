package com.remotemouse.qr

import java.net.URI
import java.util.regex.Pattern

/**
 * Validated QR Pairing Data.
 */
data class ParsedQrData(
    val ip: String,
    val port: Int,
    val fingerprint: String,
    val pin: String,
    val version: Int = 2
)

/**
 * QR Code URI Parser and Validator for Remote Mouse Pro (Android).
 * Conforms to ADR 3.4-C, ADR-01, and ADR-03.
 */
object QrCodeParser {
    const val SCHEME = "rmpp"
    private val FP_REGEX = Pattern.compile("^[0-9a-f]{64}$")
    private val PIN_REGEX = Pattern.compile("^\\d{6}$")

    /**
     * Parses and strictly validates a pairing QR URI string.
     * Expected format: rmpp://<ip>:<port>?fp=<sha256_hex_64>&pin=<pin_6_digits>&v=2
     * Returns ParsedQrData or null if invalid/malformed.
     */
    fun parse(uriString: String?): ParsedQrData? {
        if (uriString.isNullOrBlank()) return null

        return try {
            val trimmed = uriString.trim()
            val uri = URI(trimmed)

            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null

            val host = uri.host ?: return null
            val cleanHost = if (host.startsWith("[") && host.endsWith("]")) {
                host.substring(1, host.length - 1)
            } else {
                host
            }
            if (cleanHost.isBlank()) return null

            val port = uri.port
            if (port !in 1..65535) return null

            val query = uri.rawQuery ?: return null
            val params = parseQuery(query)

            val fp = params["fp"]?.trim()?.lowercase() ?: return null
            if (!FP_REGEX.matcher(fp).matches()) return null

            val pin = params["pin"]?.trim() ?: return null
            if (!PIN_REGEX.matcher(pin).matches()) return null

            val version = params["v"]?.toIntOrNull() ?: return null
            if (version != 2) return null

            ParsedQrData(
                ip = cleanHost,
                port = port,
                fingerprint = fp,
                pin = pin,
                version = version
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0 && idx < pair.length - 1) {
                val key = pair.substring(0, idx)
                val value = pair.substring(idx + 1)
                map[key] = value
            }
        }
        return map
    }

    /**
     * Sanitizes QR URI string for logging without exposing the 6-digit PIN.
     */
    fun sanitize(uriString: String?): String {
        val parsed = parse(uriString) ?: return "[INVALID_QR_URI]"
        val fpPrefix = parsed.fingerprint.take(8)
        return "$SCHEME://${parsed.ip}:${parsed.port}?fp=$fpPrefix...&pin=******&v=${parsed.version}"
    }
}
