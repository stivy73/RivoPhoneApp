package com.grinch.rivo4.controller.identification

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ciphertext only, excluded from all backups by noBackupFilesDir. Never logs secrets/errors. */
class ProviderSecrets(context: Context) {
    private val directory = File(context.noBackupFilesDir, "provider_keys").apply { mkdirs() }
    private fun file(provider: String): AtomicFile {
        require(provider == "google")
        return AtomicFile(File(directory, provider))
    }
    @Synchronized fun removeLegacyIpqs() = AtomicFile(File(directory, "ipqs")).delete()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("rivo_provider_keys_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("rivo_provider_keys_v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(provider: String): String {
        if (!file(provider).baseFile.exists()) return ""
        val bytes = file(provider).readFully()
        require(bytes.size in 29..8192)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(provider.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    @Synchronized fun save(provider: String, value: String) {
        require(value.isNotBlank() && value.length <= 4096 && value.none { it.isISOControl() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(provider.toByteArray())
        val target = file(provider)
        val stream = target.startWrite()
        try {
            stream.write(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
            target.finishWrite(stream)
        } catch (e: Exception) { target.failWrite(stream); throw e }
    }
    @Synchronized fun remove(provider: String) = file(provider).delete()
}
