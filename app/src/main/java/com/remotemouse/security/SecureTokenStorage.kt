package com.remotemouse.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

enum class StorageSecurityLevel {
    HARDWARE_BACKED,
    SOFTWARE_BACKED,
    UNAVAILABLE
}

data class PairingRecord(
    val serverHost: String,
    val clientUuid: String,
    val pairingToken: String,
    val hostFingerprint: String
)

/**
 * Secure Token Storage for Android using AndroidKeyStore (AES-256-GCM).
 * Protects pairing tokens against plaintext leakage, distinguishes hardware-backed
 * vs software-backed storage, and fails safely if the Keystore is unavailable.
 */
class SecureTokenStorage(private val context: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "RmppPairingMasterKey"
        private const val PREFS_NAME = "rmpp_paired_tokens_secure"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            val entry = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        // Generate new 256-bit AES Master Key in AndroidKeyStore
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val specBuilder = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    fun getSecurityLevel(): StorageSecurityLevel {
        return try {
            val key = getOrCreateMasterKey()
            val factory = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                    keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
                ) {
                    StorageSecurityLevel.HARDWARE_BACKED
                } else {
                    StorageSecurityLevel.SOFTWARE_BACKED
                }
            } else {
                @Suppress("DEPRECATION")
                if (keyInfo.isInsideSecureHardware) {
                    StorageSecurityLevel.HARDWARE_BACKED
                } else {
                    StorageSecurityLevel.SOFTWARE_BACKED
                }
            }
        } catch (e: Exception) {
            StorageSecurityLevel.UNAVAILABLE
        }
    }

    @Synchronized
    fun savePairing(serverHost: String, clientUuid: String, pairingToken: String, hostFingerprint: String): Boolean {
        return try {
            val masterKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val iv = cipher.iv

            val payloadJson = JSONObject().apply {
                put("serverHost", serverHost)
                put("clientUuid", clientUuid)
                put("pairingToken", pairingToken)
                put("hostFingerprint", hostFingerprint)
            }.toString().toByteArray(Charsets.UTF_8)

            val ciphertext = cipher.doFinal(payloadJson)

            // Buffer: 12-byte IV + Ciphertext (with 16-byte GCM tag)
            val combined = ByteBuffer.allocate(iv.size + ciphertext.size).apply {
                put(iv)
                put(ciphertext)
            }.array()

            val base64Payload = Base64.encodeToString(combined, Base64.NO_WRAP)
            prefs.edit().putString(serverHost, base64Payload).commit()
            true
        } catch (e: Exception) {
            // Fail-safe: NEVER write plaintext on Keystore error
            false
        }
    }

    @Synchronized
    fun getPairing(serverHost: String): PairingRecord? {
        val encryptedBase64 = prefs.getString(serverHost, null) ?: return null
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH_BYTES + 16) {
                clearPairing(serverHost)
                return null
            }

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)

            val masterKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            val decryptedBytes = cipher.doFinal(ciphertext)
            val json = JSONObject(String(decryptedBytes, Charsets.UTF_8))

            PairingRecord(
                serverHost = json.getString("serverHost"),
                clientUuid = json.getString("clientUuid"),
                pairingToken = json.getString("pairingToken"),
                hostFingerprint = json.getString("hostFingerprint")
            )
        } catch (e: Exception) {
            // Data corrupted or master key changed: purge invalid record safely
            clearPairing(serverHost)
            null
        }
    }

    @Synchronized
    fun clearPairing(serverHost: String) {
        prefs.edit().remove(serverHost).commit()
    }

    @Synchronized
    fun deleteToken(identifier: String) {
        clearPairing(identifier)
        try {
            val keysToRemove = mutableListOf<String>()
            for ((key, value) in prefs.all) {
                if (key == identifier) {
                    keysToRemove.add(key)
                } else if (value is String) {
                    val pairing = getPairing(key)
                    if (pairing != null && (pairing.clientUuid == identifier || pairing.serverHost == identifier)) {
                        keysToRemove.add(key)
                    }
                }
            }
            if (keysToRemove.isNotEmpty()) {
                val editor = prefs.edit()
                for (k in keysToRemove) {
                    editor.remove(k)
                }
                editor.commit()
            }
        } catch (_: Exception) {
            // Fail-safe: ignore reflection or parsing errors
        }
    }

    @Synchronized
    fun clearAll() {
        prefs.edit().clear().commit()
    }
}
