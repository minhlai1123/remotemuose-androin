package com.remotemouse.discovery

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.regex.Pattern

/**
 * Fallback UDP Broadcast Discovery Listener for Android.
 * Listens on port 54322 for periodic discovery datagrams when mDNS is blocked by AP Isolation.
 * Conforms to ADR 3.4-B, ADR-01, and ADR-03.
 */
class UdpDiscoveryListener(
    private val port: Int = 54322,
    private val onHostFound: (DiscoveredHost) -> Unit
) {
    companion object {
        private const val TAG = "RMPP.UdpDiscovery"
        private const val MAX_DATAGRAM_SIZE = 1024
        private const val DISCOVERY_MAGIC = "RMPP_DISCOVERY"
        private val FP_PATTERN = Pattern.compile("^[0-9a-f]{64}$")
    }

    private var socket: DatagramSocket? = null
    private var listeningJob: Job? = null
    @Volatile
    var isListening: Boolean = false
        private set

    /**
     * Starts listening for UDP fallback broadcast announcements.
     */
    @Synchronized
    fun startListening(scope: CoroutineScope) {
        if (isListening) return

        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(port))
            }
            socket = sock
            isListening = true

            listeningJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(MAX_DATAGRAM_SIZE)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.i(TAG, "UDP Fallback Discovery listening on port $port")
                while (isActive && isListening) {
                    try {
                        sock.receive(packet)
                        val length = packet.length
                        if (length in 1..MAX_DATAGRAM_SIZE) {
                            val rawBytes = packet.data.copyOfRange(packet.offset, packet.offset + length)
                            val senderIp = packet.address?.hostAddress ?: continue
                            parseAndDispatch(rawBytes, senderIp)
                        }
                    } catch (e: Exception) {
                        if (isActive && isListening) {
                            Log.d(TAG, "UDP packet receive exception: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind UDP discovery socket on port $port", e)
            isListening = false
        }
    }

    private fun parseAndDispatch(data: ByteArray, senderIp: String) {
        try {
            val jsonStr = String(data, Charsets.UTF_8)
            val json = JSONObject(jsonStr)

            // Validate Magic
            if (json.optString("magic") != DISCOVERY_MAGIC) return

            // Validate Version
            val version = json.optInt("v", -1)
            if (version != 2) return

            // Validate Port
            val targetPort = json.optInt("port", -1)
            if (targetPort !in 1..65535) return

            // Validate Name
            val name = json.optString("name", "").trim()
            if (name.isEmpty() || name.length > 64) return

            // Validate Fingerprint
            val fp = json.optString("fp", "").trim().lowercase()
            if (!FP_PATTERN.matcher(fp).matches()) return

            val host = DiscoveredHost(
                name = name,
                hostAddress = senderIp,
                port = targetPort,
                fingerprint = fp,
                protocolVersion = version,
                source = DiscoverySource.UDP_FALLBACK
            )

            Log.i(TAG, "Discovered Host via UDP: $name at $senderIp:$targetPort (fp: ${fp.take(8)}...)")
            onHostFound(host)
        } catch (e: Exception) {
            Log.d(TAG, "Ignored invalid UDP discovery packet: ${e.message}")
        }
    }

    /**
     * Stops listening and cleans up UDP socket.
     */
    @Synchronized
    fun stopListening() {
        if (!isListening) return
        isListening = false
        listeningJob?.cancel()
        listeningJob = null
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.d(TAG, "Error closing UDP socket: ${e.message}")
        }
        socket = null
        Log.i(TAG, "UDP Discovery Listener stopped.")
    }
}
