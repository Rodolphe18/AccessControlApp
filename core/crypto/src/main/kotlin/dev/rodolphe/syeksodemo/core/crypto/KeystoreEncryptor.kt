package dev.rodolphe.syeksodemo.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Symmetric encryption backed by a non-exportable AES-256-GCM key in the Android Keystore.
 *
 * The key is generated once, lives in the Keystore (hardware-backed where available), and never
 * leaves it — we only ever ask the Keystore to encrypt or decrypt. So the ciphertext we persist is
 * useless to anyone who reads it off disk without the device.
 *
 * Output layout is `[12-byte IV][ciphertext+tag]`: GCM needs a fresh IV per message, and prepending
 * it is the standard way to carry it alongside the ciphertext.
 *
 * This is the same Keystore reflex the door keys use later, minus the biometric gate — a session
 * token doesn't warrant a fingerprint on every read, but it must not sit on disk in the clear.
 */
class KeystoreEncryptor(private val keyAlias: String) {

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LENGTH) { "Ciphertext too short to contain an IV" }
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
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
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
