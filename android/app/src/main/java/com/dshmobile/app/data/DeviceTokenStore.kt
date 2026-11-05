package com.dshmobile.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A durable per-install device credential. The raw token is encrypted with a
 * non-exportable Android Keystore AES key; only the ciphertext lives in
 * SharedPreferences.
 */
class DeviceTokenStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("dsh_device_token", Context.MODE_PRIVATE)

    data class SavedToken(
        val baseUrl: String,
        val deviceId: String,
        val token: String,
    )

    fun save(baseUrl: String, deviceId: String, token: String) {
        val encrypted = encrypt(token)
        preferences.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_TOKEN, encrypted)
            .apply()
    }

    fun load(baseUrl: String): SavedToken? {
        val storedBase = preferences.getString(KEY_BASE_URL, null) ?: return null
        if (storedBase != baseUrl) return null
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return null
        val encrypted = preferences.getString(KEY_TOKEN, null) ?: return null
        val token = runCatching { decrypt(encrypted) }.getOrNull() ?: run {
            clear()
            return null
        }
        return SavedToken(storedBase, deviceId, token)
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        if (bytes.size <= IV_BYTES) error("device token ciphertext is truncated")
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dsh_mobile_device_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BASE_URL = "base_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "token"
    }
}
