package com.remotemouse.protocol

import com.remotemouse.network.RmppClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RmppProtocolTest {

    @Test
    fun testCrc16StandardVector() {
        val vector = "123456789".toByteArray(Charsets.US_ASCII)
        val crc = Crc16CcittFalse.calculate(vector)
        assertEquals(0x29B1, crc)
    }

    @Test
    fun testCompactFrameSerialization() {
        val frame = CompactFrame(
            type = RmppConstants.MSG_MOUSE_MOVE,
            sequenceNumber = 1L,
            data1 = 15,
            data2 = -25,
            flags = 0
        )
        val serialized = frame.serialize()
        assertEquals(16, serialized.size)

        val deserialized = CompactFrame.deserialize(serialized)
        assertEquals(RmppConstants.MSG_MOUSE_MOVE, deserialized.type)
        assertEquals(1L, deserialized.sequenceNumber)
        assertEquals(15.toShort(), deserialized.data1)
        assertEquals((-25).toShort(), deserialized.data2)
    }

    @Test
    fun testExtendedFrameSerialization() {
        val payload = "Xin chào Việt Nam 🇻🇳 🖱️".toByteArray(Charsets.UTF_8)
        val frame = ExtendedFrame(
            type = RmppConstants.MSG_TEXT_INPUT,
            sequenceNumber = 10L,
            payload = payload
        )
        val serialized = frame.serialize()
        assertEquals(14 + payload.size, serialized.size)

        val deserialized = ExtendedFrame.deserialize(serialized)
        assertEquals(RmppConstants.MSG_TEXT_INPUT, deserialized.type)
        assertEquals(10L, deserialized.sequenceNumber)
        assertEquals(String(payload, Charsets.UTF_8), String(deserialized.payload, Charsets.UTF_8))
    }

    @Test
    fun testAuthResumeFrameSerialization() {
        val payload = """{"client_uuid":"android-uuid-1","challenge":"a".repeat(64),"pairing_token":"b".repeat(64)}""".toByteArray(Charsets.UTF_8)
        val frame = ExtendedFrame(
            type = RmppConstants.MSG_AUTH_RESUME,
            sequenceNumber = 20L,
            payload = payload
        )
        val serialized = frame.serialize()
        assertEquals(14 + payload.size, serialized.size)

        val deserialized = ExtendedFrame.deserialize(serialized)
        assertEquals(RmppConstants.MSG_AUTH_RESUME, deserialized.type)
        assertEquals(20L, deserialized.sequenceNumber)
        assertEquals(String(payload, Charsets.UTF_8), String(deserialized.payload, Charsets.UTF_8))
    }

    @Test
    fun testStreamParser() {
        val parser = RmppStreamParser()
        val f1 = CompactFrame(RmppConstants.MSG_MOUSE_MOVE, 1, 10, 20).serialize()
        val f2 = CompactFrame(RmppConstants.MSG_MOUSE_DOWN, 2, 1, 0).serialize()

        // Test chunking byte-by-byte
        val frames1 = mutableListOf<RmppFrame>()
        for (b in f1) {
            frames1.addAll(parser.feed(byteArrayOf(b)))
        }
        assertEquals(1, frames1.size)
        assertTrue(frames1[0] is CompactFrame)
        assertEquals(RmppConstants.MSG_MOUSE_MOVE, frames1[0].msgType)

        // Test coalesced frames
        val frames2 = parser.feed(f1 + f2)
        assertEquals(2, frames2.size)
        assertEquals(RmppConstants.MSG_MOUSE_MOVE, frames2[0].msgType)
        assertEquals(RmppConstants.MSG_MOUSE_DOWN, frames2[1].msgType)
    }

    @Test
    fun testStreamParserUnknownTypeResync() {
        val parser = RmppStreamParser()
        // Unknown type 0x77 with bogus large payload length
        val fakeUnknown = byteArrayOf(
            0x52, 0x4D.toByte(), 0x01, 0x77.toByte(),
            0x00, 0x00, 0x00, 0x01,
            0xEA.toByte(), 0x60.toByte(), 0x00, 0x00
        )
        val valid = CompactFrame(RmppConstants.MSG_MOUSE_MOVE, 1, 10, 20).serialize()

        val frames = parser.feed(fakeUnknown + valid)
        assertEquals(1, frames.size)
        assertEquals(RmppConstants.MSG_MOUSE_MOVE, frames[0].msgType)
    }

    @Test
    fun testSha256FingerprintCalculation() {
        // Test vector: SHA-256 of ASCII "RemoteMousePro"
        val input = "RemoteMousePro".toByteArray(Charsets.US_ASCII)
        val fp = RmppClient.computeSha256Fingerprint(input)
        assertEquals(64, fp.length)
        assertTrue(fp.all { it in "0123456789abcdef" })
    }

    @Test
    fun testFingerprintVerificationSuccess() {
        val certBytes = "SampleCertificateDerBytes".toByteArray(Charsets.UTF_8)
        val dummyCert = DummyX509Certificate(certBytes)
        val expectedFp = RmppClient.computeSha256Fingerprint(certBytes)

        // Matching fingerprint must succeed without exception
        RmppClient.verifyCertificateFingerprint(dummyCert, expectedFp)
    }

    @Test
    fun testFingerprintVerificationColonSeparated() {
        val certBytes = "SampleCertificateDerBytes".toByteArray(Charsets.UTF_8)
        val dummyCert = DummyX509Certificate(certBytes)
        val rawFp = RmppClient.computeSha256Fingerprint(certBytes)
        val colonSeparated = rawFp.chunked(2).joinToString(":").uppercase()

        // Colon-separated uppercase format must be accepted
        RmppClient.verifyCertificateFingerprint(dummyCert, colonSeparated)
    }

    @Test
    fun testFingerprintVerificationMismatchThrows() {
        val certBytes = "SampleCertificateDerBytes".toByteArray(Charsets.UTF_8)
        val dummyCert = DummyX509Certificate(certBytes)
        val wrongFp = "0".repeat(64)

        try {
            RmppClient.verifyCertificateFingerprint(dummyCert, wrongFp)
            org.junit.Assert.fail("Expected CertificateException on mismatch")
        } catch (e: java.security.cert.CertificateException) {
            assertTrue(e.message?.contains("FINGERPRINT_MISMATCH") == true)
        }
    }

    @Test
    fun testFingerprintVerificationNullThrows() {
        val certBytes = "SampleCertificateDerBytes".toByteArray(Charsets.UTF_8)
        val dummyCert = DummyX509Certificate(certBytes)

        try {
            RmppClient.verifyCertificateFingerprint(dummyCert, null)
            org.junit.Assert.fail("Expected CertificateException on null fingerprint")
        } catch (e: java.security.cert.CertificateException) {
            assertTrue(e.message?.contains("MISSING_FINGERPRINT") == true)
        }
    }

    @Test
    fun testFingerprintVerificationBlankThrows() {
        val certBytes = "SampleCertificateDerBytes".toByteArray(Charsets.UTF_8)
        val dummyCert = DummyX509Certificate(certBytes)

        try {
            RmppClient.verifyCertificateFingerprint(dummyCert, "   ")
            org.junit.Assert.fail("Expected CertificateException on blank fingerprint")
        } catch (e: java.security.cert.CertificateException) {
            assertTrue(e.message?.contains("MISSING_FINGERPRINT") == true)
        }
    }

    @Test
    fun testFingerprintVerificationInvalidLengthThrows() {
        val certBytes = "SampleCertificateDerBytes".toByteArray(Charsets.UTF_8)
        val dummyCert = DummyX509Certificate(certBytes)

        try {
            RmppClient.verifyCertificateFingerprint(dummyCert, "abcd1234")
            org.junit.Assert.fail("Expected CertificateException on invalid length")
        } catch (e: java.security.cert.CertificateException) {
            assertTrue(e.message?.contains("INVALID_FINGERPRINT_FORMAT") == true)
        }
    }

    @Test
    fun testFingerprintVerificationInvalidHexThrows() {
        val certBytes = "SampleCertificateDerBytes".toByteArray(Charsets.UTF_8)
        val dummyCert = DummyX509Certificate(certBytes)
        val nonHex = "g".repeat(64)

        try {
            RmppClient.verifyCertificateFingerprint(dummyCert, nonHex)
            org.junit.Assert.fail("Expected CertificateException on non-hex characters")
        } catch (e: java.security.cert.CertificateException) {
            assertTrue(e.message?.contains("INVALID_FINGERPRINT_FORMAT") == true)
        }
    }

    @Test
    fun testFingerprintVerificationNullCertThrows() {
        val validFp = "a".repeat(64)

        try {
            RmppClient.verifyCertificateFingerprint(null, validFp)
            org.junit.Assert.fail("Expected CertificateException on null certificate")
        } catch (e: java.security.cert.CertificateException) {
            assertTrue(e.message?.contains("MISSING_CERTIFICATE") == true)
        }
    }

    private class DummyX509Certificate(private val der: ByteArray) : java.security.cert.X509Certificate() {
        override fun getEncoded(): ByteArray = der
        override fun checkValidity() {}
        override fun checkValidity(date: java.util.Date?) {}
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): java.math.BigInteger = java.math.BigInteger.ONE
        override fun getIssuerDN(): java.security.Principal? = null
        override fun getSubjectDN(): java.security.Principal? = null
        override fun getNotBefore(): java.util.Date? = null
        override fun getNotAfter(): java.util.Date? = null
        override fun getTBSCertificate(): ByteArray = ByteArray(0)
        override fun getSignature(): ByteArray = ByteArray(0)
        override fun getSigAlgName(): String = "SHA256withRSA"
        override fun getSigAlgOID(): String = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun toString(): String = "DummyX509Certificate"
        override fun getPublicKey(): java.security.PublicKey? = null
        override fun verify(key: java.security.PublicKey?) {}
        override fun verify(key: java.security.PublicKey?, sigProvider: String?) {}
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
        override fun hasUnsupportedCriticalExtension(): Boolean = false
    }
}
