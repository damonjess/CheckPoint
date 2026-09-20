package com.yourcompany.facesearch.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SqlCipherKeyManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("secure_vault_prefs", Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        generateKeystoreKeyIfNeeded()
    }

    private fun generateKeystoreKeyIfNeeded() {
        if (!keyStore.containsAlias("checkpoint_vault_master")) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                "checkpoint_vault_master",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    fun getOrCreatePassphrase(): ByteArray {
        val storedEncryptedKey = prefs.getString("encrypted_db_key", null)
        if (storedEncryptedKey != null) {
            return decryptPassphrase(storedEncryptedKey)
        }

        // Generate a random 32-byte master passphrase for SQLCipher
        val rawPassphrase = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val encryptedStr = encryptPassphrase(rawPassphrase)
        prefs.edit().putString("encrypted_db_key", encryptedStr).apply()
        return rawPassphrase
    }

    private fun encryptPassphrase(data: ByteArray): String {
        val secretKey = keyStore.getKey("checkpoint_vault_master", null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encryptedBytes = cipher.doFinal(data)
        val combined = cipher.iv + encryptedBytes
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    private fun decryptPassphrase(encryptedStr: String): ByteArray {
        val combined = Base64.decode(encryptedStr, Base64.DEFAULT)
        val iv = combined.copyOfRange(0, 12)
        val encryptedBytes = combined.copyOfRange(12, combined.size)

        val secretKey = keyStore.getKey("checkpoint_vault_master", null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(encryptedBytes)
    }
}
