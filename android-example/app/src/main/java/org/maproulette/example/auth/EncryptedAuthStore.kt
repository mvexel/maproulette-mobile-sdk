package org.maproulette.example.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens never enter plaintext preferences. Clearing deletes the wrapping key as well. */
internal class EncryptedAuthStore(context: Context, private val binding: String) {
    private val bindingHash = MessageDigest.getInstance("SHA-256").digest(binding.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val alias = "maproulette-auth-$bindingHash"
    private val preferences = context.getSharedPreferences("mobile-auth-$bindingHash", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun read(): String? {
        val encoded = preferences.getString("encrypted", null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12)
        val key = keyStore.getKey(alias, null) as? SecretKey ?: error("Stored credential unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(binding.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    fun write(value: String) {
        val key = keyStore.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(binding.toByteArray())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString("encrypted", encoded).commit()) { "Credential storage failed" }
    }

    fun clear() {
        // Removing the key first makes stale ciphertext unusable even if a preferences write fails.
        val keyRemoved = runCatching { keyStore.deleteEntry(alias) }.isSuccess
        val preferencesRemoved = runCatching { preferences.edit().clear().commit() }.getOrDefault(false)
        check(keyRemoved || preferencesRemoved) { "Credential storage failed" }
    }
}
