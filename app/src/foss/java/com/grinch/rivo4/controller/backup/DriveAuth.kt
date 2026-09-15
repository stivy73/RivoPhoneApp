package com.grinch.rivo4.controller.backup
import android.content.Context
import android.content.Intent
object DriveAuth {
    const val available = false
    suspend fun request(context: Context, account: String? = null): DriveGrant = throw DriveFailure(BackupError.AUTH)
    fun clearToken(context: Context, token: String) = Unit
    fun result(context: Context, intent: Intent?): DriveGrant = throw DriveFailure(BackupError.AUTH)
}
