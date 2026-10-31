package com.remotemouse.qr

import com.remotemouse.discovery.DiscoveredHost
import com.remotemouse.discovery.DiscoverySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit Tests for Android QrCodeParser and Discovery Model.
 */
class QrCodeParserTest {

    private val sampleFp = "a1b2c3d4e5f67890123456789abcdef0123456789abcdef0123456789abcdef0"
    private val samplePin = "482910"

    @Test
    fun testParseValidIpv4QrUri() {
        val uri = "rmpp://192.168.1.100:54321?fp=$sampleFp&pin=$samplePin&v=2"
        val parsed = QrCodeParser.parse(uri)

        assertNotNull(parsed)
        assertEquals("192.168.1.100", parsed?.ip)
        assertEquals(54321, parsed?.port)
        assertEquals(sampleFp, parsed?.fingerprint)
        assertEquals(samplePin, parsed?.pin)
        assertEquals(2, parsed?.version)
    }

    @Test
    fun testParseValidIpv6QrUri() {
        val uri = "rmpp://[2001:db8::1]:54321?fp=$sampleFp&pin=$samplePin&v=2"
        val parsed = QrCodeParser.parse(uri)

        assertNotNull(parsed)
        assertEquals("2001:db8::1", parsed?.ip)
        assertEquals(54321, parsed?.port)
        assertEquals(sampleFp, parsed?.fingerprint)
        assertEquals(samplePin, parsed?.pin)
    }

    @Test
    fun testRejectInvalidScheme() {
        val uri = "http://192.168.1.100:54321?fp=$sampleFp&pin=$samplePin&v=2"
        assertNull(QrCodeParser.parse(uri))
    }

    @Test
    fun testRejectPortOutOfBounds() {
        val uriLow = "rmpp://192.168.1.100:0?fp=$sampleFp&pin=$samplePin&v=2"
        val uriHigh = "rmpp://192.168.1.100:65536?fp=$sampleFp&pin=$samplePin&v=2"
        assertNull(QrCodeParser.parse(uriLow))
        assertNull(QrCodeParser.parse(uriHigh))
    }

    @Test
    fun testRejectInvalidFingerprint() {
        val uriShort = "rmpp://192.168.1.100:54321?fp=deadbeef&pin=$samplePin&v=2"
        val uriNonHex = "rmpp://192.168.1.100:54321?fp=${"z".repeat(64)}&pin=$samplePin&v=2"
        assertNull(QrCodeParser.parse(uriShort))
        assertNull(QrCodeParser.parse(uriNonHex))
    }

    @Test
    fun testRejectInvalidPin() {
        val uri5Digits = "rmpp://192.168.1.100:54321?fp=$sampleFp&pin=12345&v=2"
        val uriLetters = "rmpp://192.168.1.100:54321?fp=$sampleFp&pin=abcdef&v=2"
        assertNull(QrCodeParser.parse(uri5Digits))
        assertNull(QrCodeParser.parse(uriLetters))
    }

    @Test
    fun testRejectWrongVersion() {
        val uriV1 = "rmpp://192.168.1.100:54321?fp=$sampleFp&pin=$samplePin&v=1"
        assertNull(QrCodeParser.parse(uriV1))
    }

    @Test
    fun testSanitizeHidesPinAndTruncatesFp() {
        val uri = "rmpp://192.168.1.100:54321?fp=$sampleFp&pin=$samplePin&v=2"
        val sanitized = QrCodeParser.sanitize(uri)

        assertFalse(sanitized.contains(samplePin))
        assertTrue(sanitized.contains("pin=******"))
        assertFalse(sanitized.contains(sampleFp))
        assertTrue(sanitized.contains(sampleFp.take(8)))
    }

    @Test
    fun testDiscoveredHostDeduplicationKey() {
        val host1 = DiscoveredHost(
            name = "PC-1",
            hostAddress = "192.168.1.5",
            port = 54321,
            fingerprint = sampleFp,
            source = DiscoverySource.NSD_MDNS
        )
        val host2 = DiscoveredHost(
            name = "PC-1",
            hostAddress = "192.168.1.5",
            port = 54321,
            fingerprint = sampleFp.uppercase(),
            source = DiscoverySource.UDP_FALLBACK
        )

        assertEquals(host1.discoveryKey, host2.discoveryKey)
    }
}
