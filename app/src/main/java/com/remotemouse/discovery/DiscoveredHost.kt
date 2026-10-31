package com.remotemouse.discovery

/**
 * Source of discovery for a candidate host.
 */
enum class DiscoverySource {
    NSD_MDNS,
    UDP_FALLBACK,
    MANUAL
}

/**
 * Candidate host discovered on local network.
 * Untrusted candidate locator: Identity MUST be verified via TLS 1.3 fingerprint pinning.
 */
data class DiscoveredHost(
    val name: String,
    val hostAddress: String,
    val port: Int,
    val fingerprint: String,
    val protocolVersion: Int = 2,
    val source: DiscoverySource,
    val lastSeenMs: Long = System.currentTimeMillis()
) {
    /**
     * Unique key for deduplication across mDNS and UDP discovery.
     */
    val discoveryKey: String
        get() = "${fingerprint.lowercase()}_$port"
}
