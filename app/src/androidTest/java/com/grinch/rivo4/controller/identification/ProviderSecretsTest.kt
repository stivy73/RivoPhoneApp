package com.grinch.rivo4.controller.identification

import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.File

/** Uses the test APK context: never reads/replaces personal keys belonging to the installed app. */
class ProviderSecretsTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val secrets = ProviderSecrets(context)
    @After fun cleanup() { secrets.remove("google"); secrets.remove("ipqs") }
    @Test fun googleKeyRoundTripEncryptedOutsideBackup() {
        secrets.save("google", "test-only-google-secret")
        assertEquals("test-only-google-secret", secrets.read("google"))
        val file = File(context.noBackupFilesDir, "provider_keys/google")
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("test-only-google-secret"))
    }
    @Test fun ipqsKeyIndependentAndRemovalIsScoped() {
        secrets.save("google", "test-only-google-secret")
        secrets.save("ipqs", "test-only-ipqs-secret")
        secrets.remove("google")
        assertEquals("", secrets.read("google"))
        assertEquals("test-only-ipqs-secret", ProviderSecrets(context).read("ipqs"))
    }
    @Test fun tamperedCiphertextFailsClosed() {
        secrets.save("google", "test-only-google-secret")
        val file = File(context.noBackupFilesDir, "provider_keys/google")
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertTrue(runCatching { secrets.read("google") }.isFailure)
    }
    @Test fun randomizedCiphertextOnOverwrite() {
        secrets.save("ipqs", "test-only-ipqs-secret")
        val first = File(context.noBackupFilesDir, "provider_keys/ipqs").readBytes()
        secrets.save("ipqs", "test-only-ipqs-secret")
        assertFalse(first.contentEquals(File(context.noBackupFilesDir, "provider_keys/ipqs").readBytes()))
    }
    @Test fun browserLinksUseViewIntent() {
        var intent: Intent? = null
        val wrapper = object : ContextWrapper(context) { override fun startActivity(value: Intent) { intent = value } }
        assertTrue(ProviderLinks.open(wrapper, "google"))
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("developers.google.com", intent!!.data!!.host)
        assertTrue(ProviderLinks.open(wrapper, "ipqs"))
        assertEquals("https://www.ipqualityscore.com/login", intent!!.data.toString())
    }
    @Test fun noBrowserDoesNotCrash() {
        val wrapper = object : ContextWrapper(context) {
            override fun startActivity(value: Intent) { throw ActivityNotFoundException() }
        }
        assertFalse(ProviderLinks.open(wrapper, "google"))
    }
}
