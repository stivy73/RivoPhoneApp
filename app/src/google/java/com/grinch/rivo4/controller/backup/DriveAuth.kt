package com.grinch.rivo4.controller.backup

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Tokens belong to Google Play services; Rivo never persists or logs them. */
object DriveAuth {
    const val available = true
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    suspend fun request(context: Context, account: String? = null): DriveGrant = suspendCancellableCoroutine { c ->
        val builder = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
        if (!account.isNullOrBlank()) builder.setAccount(Account(account, "com.google"))
        Identity.getAuthorizationClient(context).authorize(builder.build())
            .addOnSuccessListener { if (c.isActive) c.resume(DriveGrant(it.accessToken, it.pendingIntent)) }
            .addOnFailureListener { if (c.isActive) c.resumeWithException(DriveFailure(BackupError.AUTH)) }
    }
    fun clearToken(context: Context, token: String) {
        Identity.getAuthorizationClient(context).clearToken(
            com.google.android.gms.auth.api.identity.ClearTokenRequest.builder().setToken(token).build())
    }
    fun result(context: Context, intent: Intent?): DriveGrant {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
        return DriveGrant(result.accessToken, result.pendingIntent)
    }
}
