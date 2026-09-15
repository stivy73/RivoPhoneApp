package com.grinch.rivo4.controller.shizuku

import android.util.Log

/** Recorder diagnostics only. Never pass contact labels, numbers, audio or file paths. */
object AppLogger {
    private const val TAG = "RivoRecorder"
    fun d(message: String) { runCatching { Log.d(TAG, message) } }
    fun i(message: String) { runCatching { Log.i(TAG, message) } }
    fun v(message: String) { runCatching { Log.v(TAG, message) } }
    fun w(message: String, cause: Throwable? = null) { runCatching { Log.w(TAG, safe(message, cause)) } }
    fun e(message: String, cause: Throwable? = null) { runCatching { Log.e(TAG, safe(message, cause)) } }
    private fun safe(message: String, cause: Throwable?): String =
        if (cause == null) message else "$message (${cause.javaClass.simpleName})"
}
