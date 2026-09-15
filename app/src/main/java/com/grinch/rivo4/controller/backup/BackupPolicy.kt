package com.grinch.rivo4.controller.backup

import android.app.PendingIntent
import java.io.File
import java.security.MessageDigest

data class DriveGrant(val token: String?, val resolution: PendingIntent?)
enum class BackupError { AUTH, NETWORK, QUOTA, CONFIGURATION, STORAGE, INTEGRITY, UNKNOWN }
class DriveFailure(val reason: BackupError) : Exception(reason.name)
object BackupPolicy {
    fun shouldRun(epoch: String, currentEpoch: String, connected: Boolean, automatic: Boolean, enabled: Boolean) =
        epoch == currentEpoch && connected && (!automatic || enabled)
    fun shouldRetry(reason: BackupError, attempts: Int) =
        reason in setOf(BackupError.NETWORK, BackupError.QUOTA) && attempts < 8
    private val extensions = setOf("ogg", "opus", "m4a", "aac", "mp3", "wav", "3gp")
    fun safeName(name: String): Boolean = name.isNotBlank() && name.length <= 240 &&
        !name.startsWith(".") && !name.contains('/') && !name.contains('\\') &&
        name.none { it.isISOControl() } && name.substringAfterLast('.').lowercase() in extensions
    // Two distinct calls can contain identical audio (e.g. silence). Keep their names
    // and timestamps distinct, while copies of the same recording share an identity.
    fun identity(name: String, sha: String): String = MessageDigest.getInstance("SHA-256")
        .digest((name + "\u0000" + sha).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun conflictName(name: String, sha: String): String {
        val stem = name.substringBeforeLast('.')
        val suffix = Regex("_\\d{8}_\\d{9}$").find(stem)
        val renamed = if (suffix == null) "${stem}_${sha.take(12)}"
            else stem.substring(0, suffix.range.first) + "_${sha.take(12)}" + suffix.value
        return "$renamed.${name.substringAfterLast('.')}"
    }
    fun eligible(file: File): Boolean = file.isFile && file.length() > 0 && safeName(file.name)
    fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    fun httpError(code: Int, reason: String): BackupError = when {
        code == 401 -> BackupError.AUTH
        code == 429 || reason in setOf("storageQuotaExceeded", "dailyLimitExceeded", "rateLimitExceeded", "userRateLimitExceeded") -> BackupError.QUOTA
        code == 403 -> BackupError.CONFIGURATION
        code >= 500 -> BackupError.NETWORK
        else -> BackupError.UNKNOWN
    }
}
