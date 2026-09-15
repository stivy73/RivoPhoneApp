package com.grinch.rivo4.controller.identification

import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.File

/** Uses an isolated temporary directory: never reads/replaces personal provider secrets. */
class ProviderSecretsTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDirectory = File(target.noBackupFilesDir, "provider_tests_" + java.util.UUID.randomUUID()).apply { mkdirs() }
    private val context = object : ContextWrapper(target) {
        override fun getNoBackupFilesDir(): File = testDirectory
    }
    private val secrets = ProviderSecrets(context)
    @After fun cleanup() { testDirectory.deleteRecursively() }
    @Test fun googleKeyRoundTripEncryptedOutsideBackup() {
        secrets.save("google", "test-only-google-secret")
        assertEquals("test-only-google-secret", secrets.read("google"))
        val file = File(context.noBackupFilesDir, "provider_keys/google")
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("test-only-google-secret"))
    }
    @Test fun legacyRemovalPreservesGoogleAndIsIdempotent() {
        secrets.save("google", "test-only-google-secret")
        val legacy = File(context.noBackupFilesDir, "provider_keys/ipqs")
        legacy.writeText("old-encrypted-data-fixture")
        repeat(2) { secrets.removeLegacyIpqs() }
        assertFalse(legacy.exists())
        assertEquals("test-only-google-secret", ProviderSecrets(context).read("google"))
        secrets.remove("google")
        assertEquals("", secrets.read("google"))
    }
    @Test fun tamperedCiphertextFailsClosed() {
        secrets.save("google", "test-only-google-secret")
        val file = File(context.noBackupFilesDir, "provider_keys/google")
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertTrue(runCatching { secrets.read("google") }.isFailure)
    }
    @Test fun randomizedCiphertextOnOverwrite() {
        secrets.save("google", "test-only-google-secret")
        val first = File(context.noBackupFilesDir, "provider_keys/google").readBytes()
        secrets.save("google", "test-only-google-secret")
        assertFalse(first.contentEquals(File(context.noBackupFilesDir, "provider_keys/google").readBytes()))
    }
    @Test fun browserLinksUseViewIntent() {
        var intent: Intent? = null
        val wrapper = object : ContextWrapper(context) { override fun startActivity(value: Intent) { intent = value } }
        assertTrue(ProviderLinks.open(wrapper, "google"))
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("developers.google.com", intent!!.data!!.host)
        assertFalse(ProviderLinks.open(wrapper, "ipqs"))
    }
    @Test fun noBrowserDoesNotCrash() {
        val wrapper = object : ContextWrapper(context) {
            override fun startActivity(value: Intent) { throw ActivityNotFoundException() }
        }
        assertFalse(ProviderLinks.open(wrapper, "google"))
    }
}
