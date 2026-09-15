/*
 * Derived from ShizuCallRecorder 1.3.3, dd940fe2caa8aa1b4c7143ad5123c9923b343abd.
 * Copyright (C) 2026-present kitsumed (Med)
 * GPLv3-or-later with Section 7 terms; assets/licenses/ShizuCallRecorder.txt.
 * Modified 2026-09-14 for Rivo Personal: recording-only binding, cancellable cleanup,
 * live permission checks, no server auto-management or app-op/role commands.
 * WITHOUT ANY WARRANTY.
 */
package com.grinch.rivo4.controller.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.grinch.rivo4.BuildConfig
import com.grinch.rivo4.IShellService
import com.grinch.rivo4.controller.recording.*
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ShizukuConnectionManager(private val context: Context, private val onBinderDied: () -> Unit = {}) {
    companion object {
        const val PERMISSION_REQUEST_CODE = 204846
        fun isAvailable() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        fun hasPermission(context: Context? = null) = runCatching {
            isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        fun getPackageName(context: Context): String? = runCatching {
            context.packageManager.getPermissionInfo(ShizukuProvider.PERMISSION, 0).packageName
        }.getOrNull()
        fun requestPermission() {
            if (isAvailable() && !hasPermission()) Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
            .daemon(false).processNameSuffix("ElevatedShellService").debuggable(BuildConfig.DEBUG)
            .version(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
    }
    private var connection: ServiceConnection? = null
    private var boundBinder: IBinder? = null
    private val death = IBinder.DeathRecipient { onBinderDied() }
    private val serverDeath = Shizuku.OnBinderDeadListener { onBinderDied() }
    @Volatile private var closed = false

    suspend fun getShellService(): IShellService = suspendCancellableCoroutine { continuation ->
        if (!isAvailable() || !hasPermission()) {
            continuation.resumeWithException(RecordingFailure(if (!isAvailable()) RecordingError.SHIZUKU_STOPPED else RecordingError.PERMISSION))
            return@suspendCancellableCoroutine
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                synchronized(this@ShizukuConnectionManager) {
                    if (closed || !continuation.isActive) return
                    if (binder == null) {
                        continuation.resumeWithException(RecordingFailure(RecordingError.SERVER))
                    } else {
                        try {
                            boundBinder = binder
                            binder.linkToDeath(death, 0)
                            AppLogger.i("Shizuku shell connected")
                            continuation.resume(IShellService.Stub.asInterface(binder))
                        } catch (_: Exception) {
                            continuation.resumeWithException(RecordingFailure(RecordingError.SHIZUKU_STOPPED))
                        }
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (!closed) onBinderDied()
            }
        }
        synchronized(this) {
            connection = conn
            Shizuku.addBinderDeadListener(serverDeath)
            try { Shizuku.bindUserService(args, conn) }
            catch (_: Exception) {
                continuation.resumeWithException(RecordingFailure(RecordingError.SERVER))
            }
        }
        continuation.invokeOnCancellation { unbind() }
    }
    @Synchronized fun unbind() {
        if (closed) return
        closed = true
        runCatching { boundBinder?.unlinkToDeath(death, 0) }
        Shizuku.removeBinderDeadListener(serverDeath)
        connection?.let { conn -> runCatching { boundedRemoteCall(2_000) { Shizuku.unbindUserService(args, conn, true) } } }
        connection = null
        boundBinder = null
        AppLogger.i("Shizuku shell released")
    }
}
