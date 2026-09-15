package com.grinch.rivo4.controller.backup

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class DriveClientTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: DriveClient
    @Before fun setup() { server = MockWebServer(); server.start(); client = DriveClient("synthetic-token", server.url("/").toString().trimEnd('/')) }
    @After fun close() { server.shutdown() }
    private fun file(bytes: ByteArray = "synthetic audio fixture".toByteArray()): File = temp.newFile("call_20260915_100000000.ogg").apply { writeBytes(bytes) }
    private fun metadata(file: File) = JSONObject().put("size", file.length()).put("id", "testId")
        .put("trashed", false).put("parents", org.json.JSONArray().put("folder"))
        .put("md5Checksum", MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
        .put("appProperties", JSONObject().put("sha256", BackupPolicy.digest(file)))
    @Test fun finalizedFilesOnly() {
        assertFalse(BackupPolicy.eligible(temp.newFile(".audio.ogg.pending")))
        assertFalse(BackupPolicy.eligible(temp.newFile("empty.ogg")))
        assertTrue(BackupPolicy.eligible(file()))
    }
    @Test fun rejectUnsafeRestoreNames() {
        listOf("../x.ogg", "/x.ogg", "a\\x.ogg", ".pending.ogg", "x.txt", "a\nx.ogg").forEach { assertFalse(it, BackupPolicy.safeName(it)) }
    }
    @Test fun hashDetectsContentChange() {
        val file = file(); val sha = BackupPolicy.digest(file); file.appendText("changed"); assertNotEquals(sha, BackupPolicy.digest(file))
    }
    @Test fun limitedScopeFolderMetadata() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"files\":[]}"))
        server.enqueue(MockResponse().setBody("{\"id\":\"folder\"}"))
        assertEquals("folder", client.folder())
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("Bearer synthetic-token", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("rivoBackup"))
        assertFalse(request.path!!.contains("synthetic-token"))
    }
    @Test fun chunkedUploadAndChecksum() = runBlocking {
        val file = file(ByteArray(1_048_580) { (it % 251).toByte() })
        server.enqueue(MockResponse().setHeader("Location", server.url("/session")))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-1048575"))
        server.enqueue(MockResponse().setBody(metadata(file).toString()))
        client.upload(file, "audio/ogg", BackupPolicy.digest(file), "folder", "testId")
        assertTrue(server.takeRequest().path!!.contains("uploadType=resumable"))
        assertEquals("bytes 0-1048575/1048580", server.takeRequest().getHeader("Content-Range"))
        assertEquals("bytes 1048576-1048579/1048580", server.takeRequest().getHeader("Content-Range"))
    }
    @Test fun duplicateIdVerifiesExistingFile() = runBlocking {
        val file = file(); server.enqueue(MockResponse().setResponseCode(409)); server.enqueue(MockResponse().setBody(metadata(file).toString()))
        client.upload(file, "audio/ogg", BackupPolicy.digest(file), "folder", "testId")
        assertEquals(2, server.requestCount)
    }
    @Test fun checksumMismatchFails() = runBlocking {
        val file = file(); server.enqueue(MockResponse().setHeader("Location", server.url("/session")))
        server.enqueue(MockResponse().setBody(metadata(file).put("md5Checksum", "wrong").toString()))
        try { client.upload(file, "audio/ogg", BackupPolicy.digest(file), "folder", "testId"); fail() }
        catch (e: DriveFailure) { assertEquals(BackupError.INTEGRITY, e.reason) }
    }
    @Test fun rejectUploadRedirectToOtherHost() = runBlocking {
        server.enqueue(MockResponse().setHeader("Location", "https://attacker.example/upload"))
        val file = file()
        try { client.upload(file, "audio/ogg", BackupPolicy.digest(file), "folder", "testId"); fail() }
        catch (e: DriveFailure) { assertEquals(BackupError.CONFIGURATION, e.reason) }
        assertEquals(1, server.requestCount)
    }
    @Test fun authenticationAndQuotaErrorsAreDistinct() = runBlocking {
        for ((code, expected) in listOf(401 to BackupError.AUTH, 429 to BackupError.QUOTA, 503 to BackupError.NETWORK, 403 to BackupError.CONFIGURATION)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))
            try { client.account(); fail() } catch (e: DriveFailure) { assertEquals(expected, e.reason) }
        }
    }
    @Test fun paginatedRestoreListing() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"files\":[{\"id\":\"a\"}],\"nextPageToken\":\"next\"}"))
        server.enqueue(MockResponse().setBody("{\"files\":[{\"id\":\"b\"}]}"))
        assertEquals(2, client.recordings("folder").size)
    }
    @Test fun restoredFileHasExpectedHash() = runBlocking {
        val file = file(); val target = temp.newFile(".restore.pending")
        server.enqueue(MockResponse().setBody(okio.Buffer().write(file.readBytes())))
        client.download(metadata(file), target)
        assertEquals(BackupPolicy.digest(file), BackupPolicy.digest(target))
    }
    @Test fun truncatedRestoreRejected() = runBlocking {
        val file = file(); server.enqueue(MockResponse().setBody("short"))
        try { client.download(metadata(file), temp.newFile(".restore.pending")); fail() }
        catch (e: DriveFailure) { assertEquals(BackupError.INTEGRITY, e.reason) }
    }
    @Test fun staleSessionMakesNoRequest() = runBlocking {
        val stale = DriveClient("synthetic-token", server.url("/").toString().trimEnd('/')) { throw CancellationException() }
        try { stale.account(); fail() } catch (_: CancellationException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun offlineManualBackupDoesNotRequireAutomaticToggle() {
        assertTrue(BackupPolicy.shouldRun("session", "session", true, false, false))
        assertFalse(BackupPolicy.shouldRun("session", "session", true, true, false))
        assertFalse(BackupPolicy.shouldRun("old", "new", true, false, true))
        assertFalse(BackupPolicy.shouldRun("session", "session", false, false, true))
    }
    @Test fun permanentErrorsDoNotLoopAndRetryIsBounded() {
        assertTrue(BackupPolicy.shouldRetry(BackupError.NETWORK, 0))
        assertFalse(BackupPolicy.shouldRetry(BackupError.NETWORK, 8))
        assertFalse(BackupPolicy.shouldRetry(BackupError.AUTH, 0))
        assertFalse(BackupPolicy.shouldRetry(BackupError.INTEGRITY, 0))
    }
    @Test fun conflictKeepsCallTimestamp() {
        val name = BackupPolicy.conflictName("caller_20260915_100000000.ogg", "abcdef1234567890")
        assertTrue(name.endsWith("_20260915_100000000.ogg"))
        assertTrue(name.contains("abcdef123456"))
        assertTrue(BackupPolicy.safeName(name))
    }
    @Test fun cancellationDuringRequest() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))
        val request = launch { client.account() }
        delay(100)
        withTimeout(2000) { request.cancelAndJoin() }
        assertTrue(request.isCancelled)
    }
    @Test fun trashedRemoteFileIsNotReportedAsBackedUp() = runBlocking {
        val file = file()
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setBody(metadata(file).put("trashed", true).toString()))
        try { client.upload(file, "audio/ogg", BackupPolicy.digest(file), "folder", "testId"); fail() }
        catch (e: DriveFailure) { assertEquals(BackupError.CONFIGURATION, e.reason) }
    }
    @Test fun identicalAudioFromDifferentCallsKeepsBothIdentities() {
        val sha = "abc"
        assertNotEquals(BackupPolicy.identity("call1.ogg", sha), BackupPolicy.identity("call2.ogg", sha))
        assertEquals(BackupPolicy.identity("call1.ogg", sha), BackupPolicy.identity("call1.ogg", sha))
    }
}
