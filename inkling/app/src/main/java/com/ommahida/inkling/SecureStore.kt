package com.ommahida.inkling

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets (API keys) at rest using an AES-256-GCM key held in the Android Keystore, so the
 * raw key never touches disk in plaintext — it's protected against device backups and offline/root
 * extraction. The Keystore key is non-exportable (it lives in system-managed key material), and this
 * uses no external dependencies. Every method is best-effort: on any failure it returns null and the
 * caller falls back, so a Keystore hiccup can never lose or leak the user's key by crashing.
 */
object SecureStore {

    private const val TAG = "InklingSecureStore"
    private const val ALIAS = "inkling_secret_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** @return base64(iv‖ciphertext), or null if encryption is unavailable. */
    fun encrypt(plaintext: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    } catch (t: Throwable) {
        Log.w(TAG, "encrypt failed: ${t.javaClass.simpleName}")
        null
    }

    /** @return the plaintext, or null if [blob] isn't our ciphertext (e.g. a legacy plaintext value). */
    fun decrypt(blob: String): String? = try {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        }
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }
}
