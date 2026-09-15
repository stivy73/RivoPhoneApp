package com.grinch.rivo4.controller.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Drive v3, narrow drive.file scope. No HTTP body, token, file name or account logging. */
class DriveClient(private val token: String, private val origin: String = "https://www.googleapis.com", private val checkSession: () -> Unit = {}) {
    private val api = "$origin/drive/v3"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }
        })
    }
    private data class Reply(val code: Int, val body: String, val location: String?, val range: String?)
    private suspend fun request(url: String, method: String = "GET", data: ByteArray? = null,
                                headers: Map<String, String> = emptyMap()): Reply {
        currentCoroutineContext().ensureActive(); checkSession()
        val uri = URL(url)
        val expected = URL(origin)
        if (uri.protocol != expected.protocol || uri.host != expected.host || uri.port != expected.port)
            throw DriveFailure(BackupError.CONFIGURATION)
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
        headers.forEach { (key, value) -> builder.header(key, value) }
        builder.method(method, data?.toRequestBody())
        execute(builder.build()).use { response ->
            val body = response.body?.let { body ->
                if (body.contentLength() > 2_000_000) throw DriveFailure(BackupError.UNKNOWN)
                val source = body.source()
                source.request(2_000_001)
                if (source.buffer.size > 2_000_000) throw DriveFailure(BackupError.UNKNOWN)
                source.readUtf8()
            }.orEmpty()
            currentCoroutineContext().ensureActive(); checkSession()
            val code = response.code
            if (code !in 200..299 && code != 308 && code != 404 && code != 409) {
                val reason = runCatching { JSONObject(body).getJSONObject("error").getJSONArray("errors").getJSONObject(0).getString("reason") }.getOrDefault("")
                throw DriveFailure(BackupPolicy.httpError(code, reason))
            }
            return Reply(code, body, response.header("Location"), response.header("Range"))
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private suspend fun json(url: String, body: JSONObject? = null): JSONObject {
        val result = request(url, if (body == null) "GET" else "POST", body?.toString()?.toByteArray(),
            if (body == null) emptyMap() else mapOf("Content-Type" to "application/json; charset=UTF-8"))
        if (result.code !in 200..299) throw DriveFailure(BackupError.CONFIGURATION)
        return JSONObject(result.body)
    }
    suspend fun account(): Pair<String, String> {
        val user = json("$api/about?fields=user(permissionId,emailAddress)").getJSONObject("user")
        return user.getString("permissionId") to user.getString("emailAddress")
    }
    suspend fun folder(): String {
        val query = "trashed = false and mimeType = 'application/vnd.google-apps.folder' and appProperties has { key='rivoBackup' and value='1' }"
        val files = json("$api/files?q=${encode(query)}&fields=files(id)&pageSize=100").getJSONArray("files")
        if (files.length() > 0) return files.getJSONObject(0).getString("id")
        return json("$api/files?fields=id", JSONObject().put("name", "Rivo Personal Recordings")
            .put("mimeType", "application/vnd.google-apps.folder")
            .put("appProperties", JSONObject().put("rivoBackup", "1"))).getString("id")
    }
    suspend fun newId(): String = json("$api/files/generateIds?count=1&space=drive&type=files").getJSONArray("ids").getString(0)
    suspend fun recordings(folder: String): List<JSONObject> {
        val files = mutableListOf<JSONObject>(); var page = ""
        do {
            val query = "'${folder.replace("'", "\\'")}' in parents and trashed = false and appProperties has { key='rivoBackup' and value='1' }"
            val result = json("$api/files?q=${encode(query)}&fields=nextPageToken,files(id,name,size,md5Checksum,appProperties)&pageSize=100&pageToken=${encode(page)}")
            val array = result.getJSONArray("files")
            for (i in 0 until array.length()) files += array.getJSONObject(i)
            page = result.optString("nextPageToken")
        } while (page.isNotEmpty())
        return files
    }
    suspend fun upload(file: File, mime: String, sha: String, folder: String, id: String) {
        val metadata = JSONObject().put("id", id).put("name", file.name).put("mimeType", mime)
            .put("parents", org.json.JSONArray().put(folder))
            .put("appProperties", JSONObject().put("rivoBackup", "1").put("sha256", sha)
                .put("modifiedMillis", file.lastModified().toString()))
        val start = request("$origin/upload/drive/v3/files?uploadType=resumable&fields=id,size,md5Checksum", "POST",
            metadata.toString().toByteArray(), mapOf("Content-Type" to "application/json; charset=UTF-8", "X-Upload-Content-Type" to mime,
                "X-Upload-Content-Length" to file.length().toString()))
        if (start.code == 409) {
            val existing = json("$api/files/$id?fields=size,md5Checksum,trashed,parents")
            val parents = existing.optJSONArray("parents")
            val inFolder = parents != null && (0 until parents.length()).any { parents.optString(it) == folder }
            if (existing.optBoolean("trashed", true) || !inFolder) throw DriveFailure(BackupError.CONFIGURATION)
            verify(file, existing)
            return
        }
        val url = start.location ?: throw DriveFailure(BackupError.UNKNOWN)
        var offset = 0L
        RandomAccessFile(file, "r").use { input ->
            while (offset < input.length()) {
                val chunk = ByteArray(minOf(1024L * 1024, input.length() - offset).toInt())
                input.seek(offset); input.readFully(chunk)
                val reply = request(url, "PUT", chunk, mapOf("Content-Type" to mime,
                    "Content-Range" to "bytes $offset-${offset + chunk.size - 1}/${input.length()}"))
                if (reply.code in 200..299) { verify(file, JSONObject(reply.body)); return }
                if (reply.code != 308) throw DriveFailure(BackupError.NETWORK)
                val next = reply.range?.substringAfterLast('-')?.toLongOrNull()?.plus(1)
                    ?: throw DriveFailure(BackupError.NETWORK)
                if (next <= offset || next > offset + chunk.size) throw DriveFailure(BackupError.INTEGRITY)
                offset = next
            }
        }
        throw DriveFailure(BackupError.INTEGRITY)
    }
    fun verify(file: File, metadata: JSONObject) {
        val md5 = MessageDigest.getInstance("MD5")
        file.inputStream().use { input -> val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; md5.update(buffer, 0, n) }
        }
        val actual = md5.digest().joinToString("") { "%02x".format(it) }
        if (metadata.optLong("size", -1) != file.length() || metadata.optString("md5Checksum") != actual)
            throw DriveFailure(BackupError.INTEGRITY)
    }
    suspend fun download(metadata: JSONObject, destination: File) {
        currentCoroutineContext().ensureActive(); checkSession()
        val size = metadata.optLong("size", -1)
        if (size <= 0 || size > destination.parentFile!!.usableSpace - 10_000_000) throw DriveFailure(BackupError.STORAGE)
        val id = metadata.getString("id")
        require(id.matches(Regex("[a-zA-Z0-9_-]+")))
        val request = Request.Builder().url("$api/files/$id?alt=media").header("Authorization", "Bearer $token").build()
        execute(request).use { response ->
            if (response.code != 200) throw DriveFailure(BackupPolicy.httpError(response.code, ""))
            val body = response.body ?: throw DriveFailure(BackupError.INTEGRITY)
            body.byteStream().use { input -> destination.outputStream().use { out ->
                val buffer = ByteArray(65536); var count = 0L
                while (true) {
                    currentCoroutineContext().ensureActive(); checkSession()
                    val n = input.read(buffer); if (n < 0) break
                    count += n; if (count > size) throw DriveFailure(BackupError.INTEGRITY)
                    out.write(buffer, 0, n)
                }
                out.fd.sync()
            } }
            verify(destination, metadata)
            val sha = metadata.getJSONObject("appProperties").getString("sha256")
            if (BackupPolicy.digest(destination) != sha) throw DriveFailure(BackupError.INTEGRITY)
        }
    }
}
