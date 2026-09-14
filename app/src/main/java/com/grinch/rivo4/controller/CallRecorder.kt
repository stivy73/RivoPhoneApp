package com.grinch.rivo4.controller

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.grinch.rivo4.IShellService
import com.grinch.rivo4.controller.shizuku.ScrcpyAudioCodec
import com.grinch.rivo4.controller.shizuku.ScrcpyAudioMuxer
import com.grinch.rivo4.controller.shizuku.ScrcpyClient
import com.grinch.rivo4.controller.shizuku.ScrcpyConfig
import com.grinch.rivo4.controller.shizuku.ShizukuConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

import com.grinch.rivo4.controller.recording.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import android.os.SystemClock
import com.grinch.rivo4.R

object CallRecorder {
    private const val TAG = "CallRecorder"
    const val DIRECTORY_NAME = "Rivo Recordings"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coordinator = RecordingCoordinator(scope, SystemClock::elapsedRealtime,
        onTransition = { android.util.Log.i(TAG, "Session ${it.sessionId}: ${it.phase} error=${it.error}") })
    val state = coordinator.state
    val isRecording = state.map { it.phase == RecordingPhase.RECORDING }
        .stateIn(scope, SharingStarted.Eagerly, false)
    val durationSeconds = state.map { it.durationSeconds }.stateIn(scope, SharingStarted.Eagerly, 0L)
    private val savedRevision = MutableStateFlow(0L)
    val recordingsChanged = savedRevision.asStateFlow()
    fun recordingSaved() { savedRevision.value += 1 }
    fun start(context: Context, label: String) {
        coordinator.start { AudioRecordingEngine(context.applicationContext, label) }
    }
    fun stop() { coordinator.stop() }
    fun stopSession(sessionId: Long) {
        if (state.value.sessionId == sessionId && state.value.busy) coordinator.stop()
    }
    fun mimeType(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "ogg", "opus" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "3gp" -> "audio/3gpp"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isWritableDirectory(dir: File): Boolean {
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) return false
            val testFile = File(dir, ".probe_${System.currentTimeMillis()}")
            val created = testFile.createNewFile()
            if (created) {
                testFile.delete()
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun getRecordingsDirectory(context: Context): File {
        val folder = context.createDeviceProtectedStorageContext().getSharedPreferences("rivo_prefs", Context.MODE_PRIVATE)
            .getInt("call_recording_folder", 0)
        val selected = when (folder) {
            1 -> context.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)?.let { File(it, DIRECTORY_NAME) }
                ?: throw RecordingFailure(RecordingError.STORAGE)
            2 -> File(context.filesDir, DIRECTORY_NAME)
            3 -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS), DIRECTORY_NAME)
            else -> null
        }
        if (selected != null) {
            if (!isWritableDirectory(selected)) throw RecordingFailure(RecordingError.STORAGE)
            return selected
        }
        // 1. If All Files Access is granted, allow direct root storage
        if (hasStoragePermission(context)) {
            val directInternal = File(Environment.getExternalStorageDirectory(), DIRECTORY_NAME)
            if (isWritableDirectory(directInternal)) {
                return directInternal
            }
        }

        // 2. Standard public Recordings folder: /storage/emulated/0/Recordings/Rivo Recordings
        val pubRecordings = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS),
            DIRECTORY_NAME
        )
        if (isWritableDirectory(pubRecordings)) {
            return pubRecordings
        }

        // 3. Standard public Music folder: /storage/emulated/0/Music/Rivo Recordings
        val pubMusic = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            DIRECTORY_NAME
        )
        if (isWritableDirectory(pubMusic)) {
            return pubMusic
        }

        // 4. App-specific external storage (always writable)
        val extDir = context.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)
            ?: context.getExternalFilesDir(null)
        if (extDir != null) {
            val dir = File(extDir, DIRECTORY_NAME)
            if (isWritableDirectory(dir)) {
                return dir
            }
        }

        // 5. Ultimate fallback: internal app sandbox
        val internalDir = File(context.filesDir, DIRECTORY_NAME)
        if (!internalDir.exists()) internalDir.mkdirs()
        return internalDir
    }

    fun getAllRecordingDirectories(context: Context): List<File> {
        val dirs = mutableListOf<File>()
        runCatching {
            val d = File(Environment.getExternalStorageDirectory(), DIRECTORY_NAME)
            if (d.exists() && d.isDirectory) dirs.add(d)
        }
        runCatching {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS), DIRECTORY_NAME)
            if (d.exists() && d.isDirectory) dirs.add(d)
        }
        runCatching {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), DIRECTORY_NAME)
            if (d.exists() && d.isDirectory) dirs.add(d)
        }
        // Known OEM call recording directories
        val oemPaths = listOf(
            "MIUI/sound_recorder/call_rec",
            "Sounds/CallRecord",
            "Record/Call",
            "Recordings/Call Recordings",
            "Recordings/Phone",
            "Audio/CallRecordings",
            "CallRecordings"
        )
        for (sub in oemPaths) {
            runCatching {
                val d = File(Environment.getExternalStorageDirectory(), sub)
                if (d.exists() && d.isDirectory) dirs.add(d)
            }
        }
        runCatching {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS), "Voice Recorder")
            if (d.exists() && d.isDirectory) dirs.add(d)
        }
        runCatching {
            context.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)?.let {
                val d = File(it, DIRECTORY_NAME)
                if (d.exists() && d.isDirectory) dirs.add(d)
            }
        }
        runCatching {
            context.getExternalFilesDir(null)?.let {
                val d = File(it, DIRECTORY_NAME)
                if (d.exists() && d.isDirectory) dirs.add(d)
            }
        }
        runCatching {
            val d = File(context.filesDir, DIRECTORY_NAME)
            if (d.exists() && d.isDirectory) dirs.add(d)
        }
        return dirs.distinctBy { it.absolutePath }
    }

    fun listRecordings(context: Context): List<File> {
        val destinationDir = runCatching { getRecordingsDirectory(context) }.getOrElse { File(context.filesDir, DIRECTORY_NAME) }

        // 1. Recover any unmigrated staging files from cacheDir
        runCatching {
            context.cacheDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("staging_") && it.length() > 0 }
                ?.forEach { staging ->
                    try {
                        val recoveredName = staging.name.removePrefix("staging_")
                        val recoveredFile = File(destinationDir, recoveredName)
                        staging.copyTo(recoveredFile, overwrite = true)
                        staging.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed recovering a legacy staging file")
                    }
                }
        }

        // 2. Scan recording directories
        val dirs = getAllRecordingDirectories(context)
        val files = mutableListOf<File>()
        val supportedExts = listOf(".ogg", ".opus", ".m4a", ".mp3", ".aac", ".3gp", ".wav")
        for (dir in dirs) {
            dir.listFiles()
                ?.filter { file -> file.isFile && file.length() > 0 && supportedExts.any { ext -> file.name.endsWith(ext, ignoreCase = true) } }
                ?.let { files.addAll(it) }
        }

        // 3. Scan cacheDir for direct audio fallback files
        runCatching {
            context.cacheDir.listFiles()
                ?.filter { file -> file.isFile && file.length() > 0 && supportedExts.any { ext -> file.name.endsWith(ext, ignoreCase = true) } }
                ?.let { files.addAll(it) }
        }

        // Return distinct files sorted newest to oldest
        return files
            .distinctBy { it.name }
            .sortedByDescending { it.lastModified() }
    }

    fun delete(file: File): Boolean = file.delete().also { deleted ->
        if (deleted) savedRevision.value += 1
    }

    fun uriFor(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun share(context: Context, file: File, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(
                Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch share chooser: ${e.message}")
        }
    }
}
