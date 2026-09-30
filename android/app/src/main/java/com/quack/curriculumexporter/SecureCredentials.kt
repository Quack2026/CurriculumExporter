package com.quack.curriculumexporter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 使用 Android Keystore 保存可选的密码，不把明文写入 SharedPreferences。 */
object SecureCredentials {
    private const val ALIAS = "curriculum_exporter_credentials"
    private const val PREFS = "secure_credentials"
    private const val KEY_CIPHER = "encrypted_password"
    private const val KEY_IV = "password_iv"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun savePassword(context: Context, password: String) {
        if (password.isEmpty()) {
            clearPassword(context)
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CIPHER, Base64.encodeToString(cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun loadPassword(context: Context): String? = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(KEY_CIPHER, null) ?: return null
        val iv = Base64.decode(prefs.getString(KEY_IV, ""), Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT)), StandardCharsets.UTF_8)
    } catch (_: Exception) {
        clearPassword(context)
        null
    }

    fun clearPassword(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
