package com.hubgoogle.record.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Explicit, user-initiated at-rest encryption. AES-256-GCM key remains in
 * Android Keystore; encrypted audio contains a version header and random IV.
 * Originals are never deleted here: the caller must do so only after success.
 */
object SecureVault {
    private const val KEY_ALIAS = "com.hubgoogle.record.vault.aes256.v1"
    private const val PROVIDER = "AndroidKeyStore"
    private val MAGIC = "RCRDVLT1".toByteArray(Charsets.US_ASCII)
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    fun encrypt(clear: File): File {
        require(clear.isFile && !clear.name.endsWith(".enc"))
        val dest = File(clear.parentFile, clear.name + ".enc")
        check(!dest.exists()) { "Encrypted file already exists" }
        val tmp = File(clear.parentFile, dest.name + ".partial")
        check(!tmp.exists()) { "Incomplete encrypted output already exists" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        check(iv.size == IV_BYTES)
        try {
            FileOutputStream(tmp).use { raw ->
                raw.write(MAGIC)
                raw.write(iv)
                CipherOutputStream(BufferedOutputStream(raw), c).use { encrypted ->
                    FileInputStream(clear).use { it.copyTo(encrypted) }
                }
            }
            // Verify GCM authentication and exact plaintext content before any
            // caller is allowed to remove the original unencrypted file.
            check(hashPlain(clear).contentEquals(hashDecrypted(tmp))) {
                "Encrypted file could not be authenticated or decoded"
            }
            check(tmp.renameTo(dest)) { "Cannot publish encrypted file" }
            return dest
        } finally {
            tmp.delete()
        }
    }

    fun decrypt(encrypted: File): File {
        require(encrypted.isFile && encrypted.name.endsWith(".enc"))
        val dest = File(encrypted.parentFile, encrypted.name.removeSuffix(".enc"))
        check(!dest.exists()) { "Original file still exists; refusing overwrite" }
        val tmp = File(encrypted.parentFile, dest.name + ".partial")
        check(!tmp.exists()) { "Incomplete decrypted output already exists" }
        try {
            FileOutputStream(tmp).use { output ->
                withDecryptionStream(encrypted) { decrypted -> decrypted.copyTo(output) }
                output.fd.sync()
            }
            check(tmp.length() > 0)
            check(tmp.renameTo(dest)) { "Cannot publish decrypted file" }
            return dest
        } finally { tmp.delete() }
    }

    private fun hashPlain(source: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(source).use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }

    private fun hashDecrypted(source: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        withDecryptionStream(source) { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }

    private inline fun <T> withDecryptionStream(source: File, body: (java.io.InputStream) -> T): T =
        BufferedInputStream(FileInputStream(source)).use { input ->
            val magic = ByteArray(MAGIC.size)
            check(input.read(magic) == MAGIC.size && magic.contentEquals(MAGIC)) {
                "Unknown encrypted recording version"
            }
            val iv = ByteArray(IV_BYTES)
            check(input.read(iv) == IV_BYTES) { "Truncated encrypted file header" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            CipherInputStream(input, cipher).use(body)
        }
}

