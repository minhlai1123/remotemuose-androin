package com.remotemouse.network

import com.remotemouse.protocol.CompactFrame
import com.remotemouse.protocol.ExtendedFrame
import com.remotemouse.protocol.RmppConstants
import com.remotemouse.protocol.RmppStreamParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.math.min

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    TLS_HANDSHAKE,
    AUTHENTICATING,
    CONNECTED_CONTROLLER,
    CONNECTED_PASSIVE,
    RECONNECT_WAIT,
    PAIRING_REQUIRED,
    ERROR
}

sealed class AuthMode {
    data class Pin(val pin: String) : AuthMode()
    data class Token(val token: String) : AuthMode()
}

class RmppClient(
    private val onStatusChanged: (ConnectionStatus, String?) -> Unit = { _, _ -> },
    private val onPairingTokenReceived: (serverHost: String, token: String) -> Unit = { _, _ -> }
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val seqGenerator = AtomicLong(1)
    private val parser = RmppStreamParser()
    private var sendChannel: Channel<ByteArray>? = null
    private var senderJob: Job? = null
    private var reconnectJob: Job? = null

    // Session connection parameters for auto-reconnect
    private var currentHost: String? = null
    private var currentPort: Int = RmppConstants.DEFAULT_PORT
    private var currentClientUuid: String? = null
    private var currentAuthMode: AuthMode? = null
    private var currentFingerprint: String? = null
    private var isUserInitiatedDisconnect = false
    private var reconnectAttempts = 0

    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 10
        const val MAX_BACKOFF_MS = 10000L

        fun computeSha256Fingerprint(derBytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(derBytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun verifyCertificateFingerprint(cert: X509Certificate?, expectedFingerprint: String?) {
            if (expectedFingerprint.isNullOrBlank()) {
                throw CertificateException("MISSING_FINGERPRINT: Host certificate fingerprint is mandatory")
            }
            val cleanExpected = expectedFingerprint.replace(":", "").trim().lowercase()
            if (cleanExpected.length != 64 || !cleanExpected.all { it in '0'..'9' || it in 'a'..'f' }) {
                throw CertificateException("INVALID_FINGERPRINT_FORMAT: Host certificate fingerprint must be 64 hex characters")
            }
            if (cert == null) {
                throw CertificateException("MISSING_CERTIFICATE: Certificate chain is empty")
            }
            val computedFp = computeSha256Fingerprint(cert.encoded)
            if (computedFp != cleanExpected) {
                throw CertificateException("FINGERPRINT_MISMATCH: expected $cleanExpected, got $computedFp")
            }
        }
    }

    var status: ConnectionStatus = ConnectionStatus.DISCONNECTED
        private set(value) {
            field = value
            onStatusChanged(value, lastError)
        }

    var lastError: String? = null
        private set

    val isConnected: Boolean
        get() = status == ConnectionStatus.CONNECTED_CONTROLLER || status == ConnectionStatus.CONNECTED_PASSIVE

    fun connect(
        host: String,
        port: Int = RmppConstants.DEFAULT_PORT,
        clientUuid: String,
        pin: String,
        expectedFingerprint: String? = null
    ) {
        connectInternal(host, port, clientUuid, AuthMode.Pin(pin), expectedFingerprint)
    }

    fun connectWithToken(
        host: String,
        port: Int = RmppConstants.DEFAULT_PORT,
        clientUuid: String,
        pairingToken: String,
        expectedFingerprint: String
    ) {
        connectInternal(host, port, clientUuid, AuthMode.Token(pairingToken), expectedFingerprint)
    }

    private fun connectInternal(
        host: String,
        port: Int,
        clientUuid: String,
        authMode: AuthMode,
        expectedFingerprint: String?
    ) {
        if (isConnected || status == ConnectionStatus.CONNECTING || status == ConnectionStatus.TLS_HANDSHAKE) {
            return
        }

        if (expectedFingerprint.isNullOrBlank()) {
            lastError = "MISSING_FINGERPRINT: Host certificate fingerprint is mandatory"
            status = ConnectionStatus.ERROR
            return
        }
        val cleanExpected = expectedFingerprint.replace(":", "").trim().lowercase()
        if (cleanExpected.length != 64 || !cleanExpected.all { it in '0'..'9' || it in 'a'..'f' }) {
            lastError = "INVALID_FINGERPRINT_FORMAT: Host certificate fingerprint must be 64 hex characters"
            status = ConnectionStatus.ERROR
            return
        }

        // Cancel any pending reconnect job
        reconnectJob?.cancel()
        reconnectJob = null

        currentHost = host
        currentPort = port
        currentClientUuid = clientUuid
        currentAuthMode = authMode
        currentFingerprint = expectedFingerprint
        isUserInitiatedDisconnect = false

        executeConnection(host, port, clientUuid, authMode, cleanExpected)
    }

    private fun executeConnection(
        host: String,
        port: Int,
        clientUuid: String,
        authMode: AuthMode,
        cleanExpectedFingerprint: String
    ) {
        status = ConnectionStatus.CONNECTING
        lastError = null

        clientScope.launch {
            try {
                // 1. Establish raw TCP connection
                val rawSocket = Socket()
                rawSocket.tcpNoDelay = true
                rawSocket.connect(InetSocketAddress(host, port), 5000)

                // 2. TLS 1.3 Handshake & Strict SHA-256 Fingerprint Pinning
                status = ConnectionStatus.TLS_HANDSHAKE
                val trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        val cert = chain?.firstOrNull()
                        verifyCertificateFingerprint(cert, cleanExpectedFingerprint)
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }

                val sslContext = SSLContext.getInstance("TLSv1.3")
                sslContext.init(null, arrayOf(trustManager), SecureRandom())
                val sslSocket = sslContext.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
                sslSocket.enabledProtocols = arrayOf("TLSv1.3")

                sslSocket.startHandshake()

                socket = sslSocket
                outputStream = sslSocket.outputStream
                inputStream = sslSocket.inputStream

                status = ConnectionStatus.AUTHENTICATING

                when (authMode) {
                    is AuthMode.Pin -> {
                        executePinAuthentication(host, clientUuid, authMode.pin)
                    }
                    is AuthMode.Token -> {
                        executeResumeAuthentication(host, clientUuid, authMode.token)
                    }
                }

            } catch (e: Exception) {
                lastError = e.message ?: "Connection failed"
                handleConnectionFailure()
            }
        }
    }

    private suspend fun executePinAuthentication(host: String, clientUuid: String, pin: String) {
        val authPayload = JSONObject().apply {
            put("client_uuid", clientUuid)
            put("device_name", "Android Device")
            put("pin", pin)
        }.toString().toByteArray(Charsets.UTF_8)

        val authReq = ExtendedFrame(
            type = RmppConstants.MSG_AUTH_REQUEST,
            sequenceNumber = seqGenerator.getAndIncrement(),
            payload = authPayload
        )
        sendRawBytesDirect(authReq.serialize())

        val buffer = ByteArray(2048)
        val bytesRead = inputStream?.read(buffer) ?: -1
        if (bytesRead <= 0) {
            throw IllegalStateException("Connection closed before authentication response")
        }

        val frames = parser.feed(buffer.copyOfRange(0, bytesRead))
        val authResp = frames.firstOrNull { it.msgType == RmppConstants.MSG_AUTH_RESPONSE } as? ExtendedFrame
            ?: throw IllegalStateException("No AUTH_RESPONSE received")

        val respJson = JSONObject(authResp.payload.toString(Charsets.UTF_8))
        if (respJson.optString("status") == "SUCCESS") {
            // Save issued pairing token for subsequent resume connects
            val pairingToken = respJson.optString("pairing_token")
            if (pairingToken.isNotEmpty()) {
                currentAuthMode = AuthMode.Token(pairingToken)
                onPairingTokenReceived(host, pairingToken)
            }

            reconnectAttempts = 0
            val role = respJson.optString("role", "ACTIVE_CONTROLLER")
            status = if (role == "PASSIVE_STANDBY") ConnectionStatus.CONNECTED_PASSIVE else ConnectionStatus.CONNECTED_CONTROLLER
            startReadAndHeartbeatLoop()
        } else {
            val reason = respJson.optString("reason", "Unknown")
            lastError = "Authentication rejected: $reason"
            status = ConnectionStatus.ERROR
            cleanupSockets()
        }
    }

    private suspend fun executeResumeAuthentication(host: String, clientUuid: String, pairingToken: String) {
        // Step A: Send Challenge Request via MSG_AUTH_RESUME
        val challengeReq = ExtendedFrame(
            type = RmppConstants.MSG_AUTH_RESUME,
            sequenceNumber = seqGenerator.getAndIncrement(),
            payload = JSONObject().apply {
                put("client_uuid", clientUuid)
                put("device_name", "Android Device")
            }.toString().toByteArray(Charsets.UTF_8)
        )
        sendRawBytesDirect(challengeReq.serialize())

        // Read Challenge Response
        val buffer = ByteArray(2048)
        val bytesRead1 = inputStream?.read(buffer) ?: -1
        if (bytesRead1 <= 0) {
            throw IllegalStateException("Connection closed during challenge request")
        }

        val frames1 = parser.feed(buffer.copyOfRange(0, bytesRead1))
        val challengeFrame = frames1.firstOrNull { it.msgType == RmppConstants.MSG_AUTH_RESPONSE } as? ExtendedFrame
            ?: throw IllegalStateException("No challenge response received")

        val challengeJson = JSONObject(challengeFrame.payload.toString(Charsets.UTF_8))
        val challenge = challengeJson.optString("challenge")
        if (challengeJson.optString("status") != "CHALLENGE" || challenge.isEmpty()) {
            throw IllegalStateException("Invalid challenge response: ${challengeJson.optString("reason")}")
        }

        // Step B: Send Challenge Response with Token via MSG_AUTH_RESUME
        val resumeReq = ExtendedFrame(
            type = RmppConstants.MSG_AUTH_RESUME,
            sequenceNumber = seqGenerator.getAndIncrement(),
            payload = JSONObject().apply {
                put("client_uuid", clientUuid)
                put("challenge", challenge)
                put("pairing_token", pairingToken)
                put("device_name", "Android Device")
            }.toString().toByteArray(Charsets.UTF_8)
        )
        sendRawBytesDirect(resumeReq.serialize())

        // Read Auth Verdict
        val bytesRead2 = inputStream?.read(buffer) ?: -1
        if (bytesRead2 <= 0) {
            throw IllegalStateException("Connection closed before resume verdict")
        }

        val frames2 = parser.feed(buffer.copyOfRange(0, bytesRead2))
        val verdictFrame = frames2.firstOrNull { it.msgType == RmppConstants.MSG_AUTH_RESPONSE } as? ExtendedFrame
            ?: throw IllegalStateException("No resume verdict frame received")

        val verdictJson = JSONObject(verdictFrame.payload.toString(Charsets.UTF_8))
        if (verdictJson.optString("status") == "SUCCESS") {
            reconnectAttempts = 0
            val role = verdictJson.optString("role", "ACTIVE_CONTROLLER")
            status = if (role == "PASSIVE_STANDBY") ConnectionStatus.CONNECTED_PASSIVE else ConnectionStatus.CONNECTED_CONTROLLER
            startReadAndHeartbeatLoop()
        } else {
            val reason = verdictJson.optString("reason", "Unknown")
            lastError = "Resume rejected: $reason"
            if (reason in listOf("TOKEN_REVOKED", "DEVICE_NOT_PAIRED", "TOKEN_INVALID")) {
                status = ConnectionStatus.PAIRING_REQUIRED
            } else {
                status = ConnectionStatus.ERROR
            }
            cleanupSockets()
        }
    }

    private fun handleConnectionFailure() {
        cleanupSockets()

        val canAutoReconnect = !isUserInitiatedDisconnect &&
                status != ConnectionStatus.PAIRING_REQUIRED &&
                lastError?.contains("FINGERPRINT_MISMATCH") != true &&
                currentAuthMode != null &&
                currentHost != null &&
                reconnectAttempts < MAX_RECONNECT_ATTEMPTS

        if (canAutoReconnect) {
            reconnectAttempts++
            // Exponential backoff: 1s, 2s, 4s, 8s, max 10s
            val delayMs = min(1000L * (1L shl (reconnectAttempts - 1)), MAX_BACKOFF_MS)
            status = ConnectionStatus.RECONNECT_WAIT

            reconnectJob = clientScope.launch {
                delay(delayMs)
                val h = currentHost
                val u = currentClientUuid
                val a = currentAuthMode
                val fp = currentFingerprint
                if (h != null && u != null && a != null && fp != null && !isUserInitiatedDisconnect) {
                    val cleanFp = fp.replace(":", "").trim().lowercase()
                    executeConnection(h, currentPort, u, a, cleanFp)
                }
            }
        } else {
            if (status != ConnectionStatus.PAIRING_REQUIRED) {
                status = ConnectionStatus.ERROR
            }
        }
    }

    private fun startReadAndHeartbeatLoop() {
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        sendChannel = channel

        senderJob = clientScope.launch {
            try {
                for (packet in channel) {
                    outputStream?.apply {
                        write(packet)
                        flush()
                    }
                }
            } catch (e: Exception) {
                handleConnectionFailure()
            }
        }

        clientScope.launch {
            val buffer = ByteArray(4096)
            try {
                while (isConnected && isActive) {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead <= 0) break
                    parser.feed(buffer.copyOfRange(0, bytesRead))
                }
            } catch (e: Exception) {
                // Socket error
            } finally {
                handleConnectionFailure()
            }
        }

        // Heartbeat ping loop (every 1000ms)
        clientScope.launch {
            while (isConnected && isActive) {
                delay(1000)
                sendHeartbeatPing()
            }
        }
    }

    fun disconnect() {
        isUserInitiatedDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        cleanupSockets()
        status = ConnectionStatus.DISCONNECTED
    }

    private fun cleanupSockets() {
        senderJob?.cancel()
        sendChannel?.close()
        sendChannel = null

        try {
            outputStream?.close()
        } catch (e: Exception) {}
        try {
            inputStream?.close()
        } catch (e: Exception) {}
        try {
            socket?.close()
        } catch (e: Exception) {}

        socket = null
        outputStream = null
        inputStream = null
        parser.reset()
    }

    private suspend fun sendRawBytesDirect(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            outputStream?.apply {
                write(bytes)
                flush()
            }
        }
    }

    private fun sendRawBytes(bytes: ByteArray) {
        val channel = sendChannel
        if (channel != null && !channel.isClosedForSend) {
            channel.trySend(bytes)
        }
    }

    // Input dispatching - Strictly isolated to CONNECTED_CONTROLLER
    fun sendMouseMove(dx: Short, dy: Short) {
        if (status != ConnectionStatus.CONNECTED_CONTROLLER) return
        val frame = CompactFrame(
            type = RmppConstants.MSG_MOUSE_MOVE,
            sequenceNumber = seqGenerator.getAndIncrement(),
            data1 = dx,
            data2 = dy
        )
        sendRawBytes(frame.serialize())
    }

    fun sendMouseDown(button: Byte) {
        if (status != ConnectionStatus.CONNECTED_CONTROLLER) return
        val frame = CompactFrame(
            type = RmppConstants.MSG_MOUSE_DOWN,
            sequenceNumber = seqGenerator.getAndIncrement(),
            data1 = button.toShort(),
            data2 = 0
        )
        sendRawBytes(frame.serialize())
    }

    fun sendMouseUp(button: Byte) {
        if (status != ConnectionStatus.CONNECTED_CONTROLLER) return
        val frame = CompactFrame(
            type = RmppConstants.MSG_MOUSE_UP,
            sequenceNumber = seqGenerator.getAndIncrement(),
            data1 = button.toShort(),
            data2 = 0
        )
        sendRawBytes(frame.serialize())
    }

    fun sendMouseClick(button: Byte, clickCount: Byte = 1) {
        if (status != ConnectionStatus.CONNECTED_CONTROLLER) return
        val frame = CompactFrame(
            type = RmppConstants.MSG_MOUSE_CLICK,
            sequenceNumber = seqGenerator.getAndIncrement(),
            data1 = button.toShort(),
            data2 = clickCount.toShort()
        )
        sendRawBytes(frame.serialize())
    }

    fun sendMouseScroll(hWheel: Short, vWheel: Short) {
        if (status != ConnectionStatus.CONNECTED_CONTROLLER) return
        val frame = CompactFrame(
            type = RmppConstants.MSG_MOUSE_SCROLL,
            sequenceNumber = seqGenerator.getAndIncrement(),
            data1 = hWheel, // Data1: dwHWheel (horizontal scroll)
            data2 = vWheel  // Data2: dwWheel (vertical scroll)
        )
        sendRawBytes(frame.serialize())
    }

    // Keyboard Input - Strictly isolated to CONNECTED_CONTROLLER
    fun sendKeyDown(vkCode: Short) {
        if (status != ConnectionStatus.CONNECTED_CONTROLLER) return
        val frame = CompactFrame(
            type = RmppConstants.MSG_KEY_DOWN,
            sequenceNumber = seqGenerator.getAndIncrement(),
            data1 = vkCode,
            data2 = 0
        )
        sendRawBytes(frame.serialize())
    }

    fun sendKeyUp(vkCode: Short) {
        if (status != ConnectionStatus.CONNECTED_CONTROLLER) return
        val frame = CompactFrame(
            type = RmppConstants.MSG_KEY_UP,
            sequenceNumber = seqGenerator.getAndIncrement(),
            data1 = vkCode,
            data2 = 0
        )
        sendRawBytes(frame.serialize())
    }

    fun sendKeyClick(vkCode: Short) {
        sendKeyDown(vkCode)
        sendKeyUp(vkCode)
    }

    fun sendTextInput(text: String) {
        if (status != ConnectionStatus.CONNECTED_CONTROLLER) return
        if (text.isEmpty()) return
        // Unicode NFC Canonical Composition normalization
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        val payload = normalized.toByteArray(Charsets.UTF_8)
        if (payload.isEmpty() || payload.size > RmppConstants.MAX_PAYLOAD_SIZE) return
        val frame = ExtendedFrame(
            type = RmppConstants.MSG_TEXT_INPUT,
            sequenceNumber = seqGenerator.getAndIncrement(),
            payload = payload
        )
        sendRawBytes(frame.serialize())
    }

    fun sendSafetyReleaseAll() {
        val frame = CompactFrame(
            type = RmppConstants.MSG_SAFETY_RELEASE_ALL,
            sequenceNumber = seqGenerator.getAndIncrement()
        )
        sendRawBytes(frame.serialize())
    }

    private fun sendHeartbeatPing() {
        val frame = CompactFrame(
            type = RmppConstants.MSG_HEARTBEAT_PING,
            sequenceNumber = seqGenerator.getAndIncrement()
        )
        sendRawBytes(frame.serialize())
    }
}
