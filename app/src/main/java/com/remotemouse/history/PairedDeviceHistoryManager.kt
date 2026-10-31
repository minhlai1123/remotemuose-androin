package com.remotemouse.history

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.remotemouse.security.SecureTokenStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Metadata record for a paired desktop host in connection history.
 * 
 * STRICT SECURITY AUDIT:
 * - This record NEVER contains the pairing token, PIN, or private key!
 * - Pairing tokens are stored separately and encrypted inside hardware KeyStore via SecureTokenStorage.
 */
data class PairedDevice(
    val uuid: String,
    val name: String,
    val lastIp: String,
    val port: Int,
    val fingerprint: String,
    val pairedAtMs: Long = System.currentTimeMillis(),
    val lastConnectedMs: Long = System.currentTimeMillis()
)

/**
 * Manages Paired Devices History for Android.
 * Allows selecting a device for instant auto-reconnect or forgetting/removing devices.
 * 
 * ARCHITECTURAL DISTINCTION:
 * - "forgetDevice(uuid)": Deletes local metadata and deletes local encrypted token from KeyStore.
 *   Does NOT revoke the token on the Host (requires Host Dashboard or active revoke protocol call).
 */
class PairedDeviceHistoryManager(
    private val context: Context,
    private val secureTokenStorage: SecureTokenStorage
) {
    companion object {
        private const val TAG = "RMPP.PairedHistory"
        private const val PREFS_NAME = "rmpp_paired_devices_history"
        private const val KEY_DEVICES_JSON = "devices_list"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Retrieves all saved paired devices.
     */
    @Synchronized
    fun getPairedDevices(): List<PairedDevice> {
        val jsonStr = prefs.getString(KEY_DEVICES_JSON, null) ?: return emptyList()
        val list = mutableListOf<PairedDevice>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    PairedDevice(
                        uuid = obj.getString("uuid"),
                        name = obj.getString("name"),
                        lastIp = obj.getString("lastIp"),
                        port = obj.getInt("port"),
                        fingerprint = obj.getString("fingerprint"),
                        pairedAtMs = obj.optLong("pairedAtMs", 0L),
                        lastConnectedMs = obj.optLong("lastConnectedMs", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing paired devices history", e)
        }
        return list
    }

    /**
     * Adds or updates a paired device record upon successful pairing or connection.
     */
    @Synchronized
    fun saveDevice(device: PairedDevice) {
        val current = getPairedDevices().toMutableList()
        val existingIndex = current.indexOfFirst { it.uuid == device.uuid }
        if (existingIndex >= 0) {
            current[existingIndex] = device
        } else {
            current.add(0, device)
        }
        persistList(current)
        Log.i(TAG, "Saved paired device history for '${device.name}' (${device.uuid.take(8)}...)")
    }

    /**
     * Forgets a device locally:
     * 1. Removes device record from local history.
     * 2. Clears encrypted token from Android KeyStore via SecureTokenStorage.
     * 
     * NOTE: This is a local forget operation. To revoke the token on the Host,
     * use Host revocation or a protocol REVOKE frame if authenticated.
     */
    @Synchronized
    fun forgetDevice(uuid: String) {
        val current = getPairedDevices().toMutableList()
        val removed = current.removeAll { it.uuid == uuid }
        if (removed) {
            persistList(current)
            // Delete associated pairing token from secure hardware storage
            secureTokenStorage.deleteToken(uuid)
            Log.i(TAG, "Device $uuid forgotten locally and token wiped from KeyStore.")
        }
    }

    private fun persistList(devices: List<PairedDevice>) {
        val array = JSONArray()
        for (d in devices) {
            val obj = JSONObject().apply {
                put("uuid", d.uuid)
                put("name", d.name)
                put("lastIp", d.lastIp)
                put("port", d.port)
                put("fingerprint", d.fingerprint)
                put("pairedAtMs", d.pairedAtMs)
                put("lastConnectedMs", d.lastConnectedMs)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_DEVICES_JSON, array.toString()).apply()
    }
}
