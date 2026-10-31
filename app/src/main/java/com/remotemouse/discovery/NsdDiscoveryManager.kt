package com.remotemouse.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * mDNS / DNS-SD Network Service Discovery for Android using native NsdManager.
 * Discovers '_rmpp._tcp' services on local Wi-Fi network.
 * Conforms to ADR 3.4-A, ADR-01, and ADR-03.
 */
class NsdDiscoveryManager(
    private val context: Context,
    private val onHostFound: (DiscoveredHost) -> Unit,
    private val onHostLost: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "RMPP.NsdDiscovery"
        const val SERVICE_TYPE = "_rmpp._tcp."
    }

    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile
    var isDiscovering: Boolean = false
        private set

    private val discoveredHosts = ConcurrentHashMap<String, DiscoveredHost>()

    /**
     * Starts discovering RMPP desktop hosts on LAN.
     */
    @Synchronized
    fun startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Discovery already active.")
            return
        }

        val manager = nsdManager ?: run {
            Log.w(TAG, "NsdManager not available on this device.")
            return
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "mDNS discovery started for $regType")
                isDiscovering = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service candidate found: ${service.serviceName}")
                if (service.serviceType == SERVICE_TYPE || service.serviceType.contains("_rmpp._tcp")) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                val lostName = service.serviceName
                discoveredHosts.remove(lostName)?.let {
                    onHostLost(lostName)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "mDNS discovery stopped: $serviceType")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: error code $errorCode")
                isDiscovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: error code $errorCode")
                isDiscovering = false
            }
        }

        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate mDNS discovery", e)
            isDiscovering = false
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        try {
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${service.serviceName}: code $errorCode")
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val hostAddress = resolved.host?.hostAddress ?: return
                    val port = resolved.port
                    val attributes = resolved.attributes ?: emptyMap()

                    // Extract Fingerprint
                    val fpBytes = attributes["fp"]
                    val fingerprint = fpBytes?.toString(Charsets.UTF_8)?.trim()?.lowercase() ?: ""
                    if (fingerprint.length != 64) {
                        Log.w(TAG, "Discovered service has invalid fingerprint length (${fingerprint.length}); ignoring.")
                        return
                    }

                    // Extract Name
                    val nameBytes = attributes["name"]
                    val hostName = nameBytes?.toString(Charsets.UTF_8)?.trim()
                        ?: resolved.serviceName

                    // Extract Version
                    val vBytes = attributes["v"]
                    val version = vBytes?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 2

                    val host = DiscoveredHost(
                        name = hostName,
                        hostAddress = hostAddress,
                        port = port,
                        fingerprint = fingerprint,
                        protocolVersion = version,
                        source = DiscoverySource.NSD_MDNS
                    )

                    discoveredHosts[resolved.serviceName] = host
                    Log.i(TAG, "Resolved mDNS Host: $hostName at $hostAddress:$port (fp: ${fingerprint.take(8)}...)")
                    onHostFound(host)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Exception resolving service ${serviceInfo.serviceName}: ${e.message}")
        }
    }

    /**
     * Stops mDNS discovery cleanly.
     */
    @Synchronized
    fun stopDiscovery() {
        if (!isDiscovering) return
        val listener = discoveryListener ?: return
        val manager = nsdManager ?: return

        try {
            manager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping discovery", e)
        } finally {
            discoveryListener = null
            isDiscovering = false
        }
    }
}
