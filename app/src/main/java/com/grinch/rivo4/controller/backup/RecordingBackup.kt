package com.grinch.rivo4.controller.backup

import android.content.Context
import androidx.work.*
import com.grinch.rivo4.controller.CallRecorder
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Only non-secret preferences; OAuth tokens are never stored by Rivo. */
object RecordingBackup {
    const val WORK = "rivo-recording-backup"
    fun prefs(context: Context) = context.getSharedPreferences("drive_backup", Context.MODE_PRIVATE)
    fun epoch(context: Context) = prefs(context).getString("epoch", "").orEmpty()
    fun localFiles(context: Context): List<File> {
        val directories = (listOf(CallRecorder.getRecordingsDirectory(context)) +
            CallRecorder.getAllRecordingDirectories(context).filter { it.name == CallRecorder.DIRECTORY_NAME })
            .distinctBy { it.absolutePath }
        return directories.flatMap { it.listFiles().orEmpty().filter(BackupPolicy::eligible) }
            .sortedBy { it.lastModified() }
    }
    fun scheduleAutomatic(context: Context) {
        if (DriveAuth.available && prefs(context).getBoolean("automatic", false)) enqueue(context, automatic = true)
    }
    fun enqueue(context: Context, restore: Boolean = false, automatic: Boolean = false) {
        val p = prefs(context)
        if (!DriveAuth.available || p.getString("accountId", null) == null) return
        val work = OneTimeWorkRequestBuilder<RecordingBackupWorker>()
            .setInputData(workDataOf("restore" to restore, "automatic" to automatic, "epoch" to epoch(context)))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(
                if (p.getBoolean("wifi", true)) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK).build()
        // One chain for uploads and restores: never overwrite/download a file being uploaded.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        p.edit().putString("status", "QUEUED").apply()
    }
    fun periodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork("$WORK-reconcile")
        if (prefs(context).getBoolean("automatic", false)) {
            val request = PeriodicWorkRequestBuilder<RecordingBackupWorker>(12, TimeUnit.HOURS)
                .setInputData(workDataOf("automatic" to true, "epoch" to epoch(context)))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(
                    if (prefs(context).getBoolean("wifi", true)) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .addTag(WORK).build()
            wm.enqueueUniquePeriodicWork("$WORK-reconcile", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
    fun disconnect(context: Context) {
        // Invalidate before cancellation, so delayed OAuth/HTTP callbacks cannot update state.
        prefs(context).edit().clear().putString("epoch", UUID.randomUUID().toString()).commit()
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK)
    }
    suspend fun connect(context: Context, token: String, expectedEpoch: String) = withContext(Dispatchers.IO) {
        val client = DriveClient(token) { check(epoch(context) == expectedEpoch) }
        val (id, email) = client.account()
        val folder = client.folder()
        check(epoch(context) == expectedEpoch)
        val p = prefs(context)
        if (p.getString("accountId", null) != id) p.edit().clear().commit()
        // Reconnecting also recovers from a deleted/trashed remote destination. Existing
        // completed files are rediscovered by checksum before any fresh ID is uploaded.
        val resetIds = p.edit()
        p.all.keys.filter { it.startsWith("id_") }.forEach(resetIds::remove)
        resetIds.commit()
        p.edit().putString("accountId", id).putString("accountEmail", email).putString("folder", folder)
            .putString("epoch", UUID.randomUUID().toString()).putString("status", "READY").commit()
        periodic(context)
    }
}

class RecordingBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { private val lock = kotlinx.coroutines.sync.Mutex() }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        lock.lock()
        try { execute() } finally { lock.unlock() }
    }
    private suspend fun execute(): Result {
        val context = applicationContext
        val p = RecordingBackup.prefs(context)
        val epoch = inputData.getString("epoch") ?: return Result.success()
        val automatic = inputData.getBoolean("automatic", false)
        fun valid() = BackupPolicy.shouldRun(epoch, RecordingBackup.epoch(context), p.contains("accountId"),
            automatic, p.getBoolean("automatic", false))
        if (!DriveAuth.available || !valid()) return Result.success()
        var activeHash: String? = null
        var activeToken: String? = null
        val restore = inputData.getBoolean("restore", false)
        return try {
            p.edit().putString("status", if (restore) "RESTORING" else "UPLOADING").apply()
            suspend fun client(): DriveClient {
                val grant = try { withTimeout(30_000) { DriveAuth.request(context, p.getString("accountEmail", null)) } }
                    catch (_: TimeoutCancellationException) { throw DriveFailure(BackupError.NETWORK) }
                if (grant.resolution != null || grant.token.isNullOrBlank()) throw DriveFailure(BackupError.AUTH)
                activeToken = grant.token
                return DriveClient(grant.token) { if (!valid()) throw CancellationException() }.also {
                    if (it.account().first != p.getString("accountId", null)) throw DriveFailure(BackupError.AUTH)
                }
            }
            var drive = client()
            val folder = p.getString("folder", null) ?: throw DriveFailure(BackupError.CONFIGURATION)
            val remote = drive.recordings(folder)
            val start = System.currentTimeMillis()
            if (restore) {
                p.edit().putInt("pending", remote.size).apply()
                for ((index, metadata) in remote.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (!valid()) return Result.success()
                    val name = metadata.getString("name")
                    if (!BackupPolicy.safeName(name)) throw DriveFailure(BackupError.INTEGRITY)
                    val sha = metadata.getJSONObject("appProperties").getString("sha256")
                    if (!sha.matches(Regex("[0-9a-f]{64}"))) throw DriveFailure(BackupError.INTEGRITY)
                    val dir = CallRecorder.getRecordingsDirectory(context)
                    var target = File(dir, name)
                    if (target.exists() && BackupPolicy.digest(target) != sha) {
                        target = File(dir, BackupPolicy.conflictName(name, sha))
                    }
                    if (!target.exists()) {
                        val partial = File(dir, ".restore_$sha.pending")
                        try {
                            drive = client()
                            drive.download(metadata, partial)
                            if (!valid()) {
                                return Result.success()
                            }
                            if (target.exists() || !partial.renameTo(target)) {
                                throw DriveFailure(BackupError.STORAGE)
                            }
                            metadata.getJSONObject("appProperties").optString("modifiedMillis").toLongOrNull()
                                ?.takeIf { it > 0 }?.let { target.setLastModified(it) }
                            CallRecorder.recordingSaved()
                        } finally { partial.delete() }
                    } else if (BackupPolicy.digest(target) != sha) {
                        throw DriveFailure(BackupError.INTEGRITY)
                    }
                    p.edit().putInt("pending", remote.size - index - 1).apply()
                    if (System.currentTimeMillis() - start > 7 * 60_000) return Result.retry()
                }
            } else {
                val files = RecordingBackup.localFiles(context)
                p.edit().putInt("pending", files.size).apply()
                for ((index, file) in files.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (!valid()) return Result.success()
                    val sha = BackupPolicy.digest(file)
                    val key = BackupPolicy.identity(file.name, sha)
                    activeHash = key
                    p.edit().putString("file_$key", "UPLOADING").apply()
                    val existing = remote.firstOrNull { it.optJSONObject("appProperties")?.optString("sha256") == sha && it.optString("name") == file.name }
                    if (existing != null) drive.verify(file, existing) else {
                        drive = client()
                        val id = p.getString("id_$key", null) ?: drive.newId().also {
                            if (!p.edit().putString("id_$key", it).commit()) throw DriveFailure(BackupError.STORAGE)
                        }
                        drive.upload(file, CallRecorder.mimeType(file), sha, folder, id)
                    }
                    if (!valid()) return Result.success()
                    p.edit().putString("file_$key", "COPIED").putInt("pending", files.size - index - 1).apply()
                    if (System.currentTimeMillis() - start > 7 * 60_000) return Result.retry()
                }
            }
            if (valid()) p.edit().putString("status", "DONE").putInt("pending", 0)
                .putLong("lastSuccess", System.currentTimeMillis()).apply()
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!valid()) return Result.success()
            val reason = (e as? DriveFailure)?.reason ?: if (e is java.io.IOException) BackupError.NETWORK else BackupError.UNKNOWN
            if (reason == BackupError.AUTH) activeToken?.let { DriveAuth.clearToken(context, it) }
            p.edit().putString("status", reason.name).apply()
            activeHash?.let { p.edit().putString("file_$it", "ERROR").apply() }
            if (BackupPolicy.shouldRetry(reason, runAttemptCount)) Result.retry() else Result.failure()
        }
    }
}
