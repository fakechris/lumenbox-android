package com.lumenopen.lumenbox.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lumenopen.lumenbox.storage.KeyStoreVault
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed [KeyStoreVault] (`docs/110 §0.2, §3.2`, INV-2254).
 *
 * Wraps every `(namespace, key)` secret under a non-exportable 256-bit AES-GCM master key in
 * `AndroidKeyStore`, persisted inside [Context.getNoBackupFilesDir] (`allowBackup="false"`).
 * Deleting a namespace (`conn.<id>`) removes its entire directory immediately on remote wipe or unpair.
 */
class AndroidKeyStoreVault(
    context: Context,
    private val keyAlias: String = DEFAULT_MASTER_ALIAS,
) : KeyStoreVault {
    companion object {
        const val DEFAULT_MASTER_ALIAS = "com.lumenopen.lumenbox.vault.aes256"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }

    private val lock = Any()
    private val vaultRoot = File(context.noBackupFilesDir, "lumenbox_vault").apply { mkdirs() }
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun write(namespace: String, key: String, value: ByteArray) {
        synchronized(lock) {
            val dir = namespaceDir(namespace).apply { mkdirs() }
            val target = File(dir, "${sha256Hex(key)}.gcm")
            val tmp = File(dir, "${target.name}.tmp")

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            cipher.updateAAD(aadBytes(namespace, key))
            val iv = cipher.iv
            require(iv.size == GCM_IV_BYTES) { "Unexpected AES-GCM IV length: ${iv.size}" }
            val ciphertext = cipher.doFinal(value)

            FileOutputStream(tmp).use { out ->
                out.write(iv)
                out.write(ciphertext)
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun read(namespace: String, key: String): ByteArray? = synchronized(lock) {
        val target = File(namespaceDir(namespace), "${sha256Hex(key)}.gcm")
        if (!target.exists()) return null
        val raw = target.readBytes()
        if (raw.size < GCM_IV_BYTES + GCM_TAG_BITS / 8) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_BITS, raw, 0, GCM_IV_BYTES),
            )
            cipher.updateAAD(aadBytes(namespace, key))
            cipher.doFinal(raw, GCM_IV_BYTES, raw.size - GCM_IV_BYTES)
        }.getOrNull()
    }

    override fun deleteNamespace(namespace: String) {
        synchronized(lock) {
            namespaceDir(namespace).deleteRecursively()
        }
    }

    private fun namespaceDir(namespace: String): File =
        File(vaultRoot, sha256Hex(namespace))

    private fun aadBytes(namespace: String, key: String): ByteArray =
        "$namespace\u0000$key".toByteArray(StandardCharsets.UTF_8)

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
