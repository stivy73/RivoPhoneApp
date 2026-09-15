/*
 * Derived from ShizuCallRecorder 1.3.3 AudioRecordingEngine (dd940fe2caa8aa1b4c7143ad5123c9923b343abd).
 * Copyright (C) 2026-present kitsumed (Med)
 * GNU GPLv3-or-later with Section 7 terms; assets/licenses/ShizuCallRecorder.txt.
 * Modified 2026-09-14: Rivo storage/call ownership, first-media confirmation and failure propagation.
 * WITHOUT ANY WARRANTY.
 */
package com.grinch.rivo4.controller.recording

import android.content.Context
import android.os.ParcelFileDescriptor
import com.grinch.rivo4.IShellService
import com.grinch.rivo4.controller.CallRecorder
import com.grinch.rivo4.controller.CallRecordingService
import com.grinch.rivo4.controller.shizuku.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecordingEngine(private val context: Context, private val label: String) : RecordingPipeline {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("rivo_prefs", Context.MODE_PRIVATE)
    private var manager: ShizukuConnectionManager? = null
    private var shell: IShellService? = null
    private var input: ParcelFileDescriptor? = null
    private var output: ParcelFileDescriptor? = null
    private var muxer: ScrcpyAudioMuxer? = null
    private var client: ScrcpyClient? = null
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var staging: File? = null
    private var target: File? = null
    private var codec = ScrcpyAudioCodec.OPUS
    @Volatile private var closing = false
    private var sessionId = 0L
    @Volatile private var writeFailed = false

    override suspend fun open(onMediaWritten: () -> Unit, onFailure: (RecordingError) -> Unit) {
        sessionId = CallRecorder.state.value.sessionId
        if (!ShizukuConnectionManager.isAvailable()) throw RecordingFailure(
            if (ShizukuConnectionManager.getPackageName(context) == null) RecordingError.SHIZUKU_MISSING else RecordingError.SHIZUKU_STOPPED)
        if (!ShizukuConnectionManager.hasPermission()) throw RecordingFailure(RecordingError.PERMISSION)
        CallRecordingService.startAndAwait(context, sessionId)
        manager = ShizukuConnectionManager(context) { if (!closing) onFailure(RecordingError.SHIZUKU_STOPPED) }
        shell = withTimeoutOrNull(10_000) { manager!!.getShellService() }
            ?: throw RecordingFailure(RecordingError.CONNECTION_TIMEOUT)
        currentCoroutineContext().ensureActive()
        codec = ScrcpyAudioCodec.fromKey(prefs.getString("call_recording_codec", "opus") ?: "opus")
        val source = ScrcpyAudioSource.fromKey(prefs.getString("call_recording_source", "voice-call") ?: "voice-call")
        val bitrate = prefs.getInt("call_recording_bitrate", codec.defaultBitRate).takeIf { it > 0 } ?: codec.defaultBitRate
        AppLogger.i("scrcpy=${ScrcpyConfig.SCRCPY_VERSION} source=${source.cliKey} codec=${codec.cliKey} bitrate=$bitrate")
        val serverPath = ScrcpyConfig.getServerPath(context)
        if (!ServerExtractor.ensureServerFile(context, serverPath)) throw RecordingFailure(RecordingError.SERVER)
        try {
            val directory = CallRecorder.getRecordingsDirectory(context)
            if (directory.usableSpace < 1024 * 1024) throw RecordingFailure(RecordingError.STORAGE)
            val safe = label.replace(Regex("[^\\p{L}\\p{N}+_-]"), "_").take(40).ifBlank { "call" }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
            target = File(directory, "${safe}_$stamp${codec.containerExtension}")
            staging = File(directory, ".${target!!.name}.pending")
            output = ParcelFileDescriptor.open(staging!!, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
            muxer = ScrcpyAudioMuxer(output!!.fileDescriptor, "recording")
            muxer!!.initialize(codec)
        } catch (e: RecordingFailure) { throw e }
        catch (_: Exception) { throw RecordingFailure(RecordingError.STORAGE) }
        currentCoroutineContext().ensureActive()
        input = try {
            boundedRemoteCall(8_000, disposeLate = { it?.close() }) {
                shell!!.startRecording(source.cliKey, codec.cliKey, bitrate, serverPath, false)
            }
        }
        catch (_: SecurityException) { throw RecordingFailure(RecordingError.PERMISSION) }
        catch (_: Exception) { throw RecordingFailure(RecordingError.SERVER) }
        val pipe = input ?: throw RecordingFailure(RecordingError.NULL_PIPE)
        currentCoroutineContext().ensureActive()
        client = ScrcpyClient(pipe, codec, object : ScrcpyClient.AudioPacketListener {
            override fun onMetadataReceived(codec: ScrcpyAudioCodec) { muxer!!.initialize(codec) }
            override fun onAudioPacket(packet: ScrcpyClient.AudioPacket) {
                try {
                    muxer!!.writePacket(packet, codec)
                    if (!packet.isConfigPacket && !closing) onMediaWritten()
                } catch (_: Exception) {
                    writeFailed = true
                    if (!closing) onFailure(RecordingError.WRITE)
                    throw RecordingFailure(RecordingError.WRITE)
                }
            }
            override fun onStreamEnd(error: String?) {
                AppLogger.i(if (closing) "Audio stream drained" else "Unexpected audio stream end")
                if (!closing) onFailure(RecordingError.STREAM)
            }
        })
        readerJob = readerScope.launch { client!!.start() }
    }

    override fun checkHealth() {
        if (!ShizukuConnectionManager.isAvailable()) throw RecordingFailure(RecordingError.SHIZUKU_STOPPED)
        if (!ShizukuConnectionManager.hasPermission()) throw RecordingFailure(RecordingError.PERMISSION)
    }

    override suspend fun close(keepRecording: Boolean, durationSeconds: Long) {
        closing = true
        var failed = false
        // Same release ordering as v1.3.3: shell stop, bounded drain, client/pipe, muxer, output.
        try { boundedRemoteCall(6_000) { shell?.stopRecording() } } catch (_: Exception) { failed = true; manager?.unbind() }
        withTimeoutOrNull(2_000) { readerJob?.join() }
        client?.stop()
        client?.close()
        runCatching { input?.close() }
        withTimeoutOrNull(1_000) { readerJob?.join() }
        readerScope.cancel()
        failed = failed || writeFailed
        try { muxer?.close() } catch (_: Exception) { failed = true }
        try { output?.close() } catch (_: Exception) { failed = true }
        manager?.unbind()
        CallRecordingService.finish(context, sessionId)
        val saved = staging
        val final = target
        if (saved != null) {
            val minDuration = prefs.getInt("call_recording_min_duration", 0)
            if (keepRecording && !failed && durationSeconds >= minDuration && saved.length() > 0 && final != null) {
                if (!saved.renameTo(final)) {
                    // Preserve the recoverable partial file, do not announce it as a saved recording.
                    throw RecordingFailure(RecordingError.WRITE)
                }
                CallRecorder.recordingSaved()
                // Enqueue only after muxer/output closure and atomic finalization. Backup failure
                // must never change the outcome of the completed recording.
                runCatching { com.grinch.rivo4.controller.backup.RecordingBackup.scheduleAutomatic(context) }
            } else if (!saved.delete() && saved.exists()) {
                failed = true
            }
        }
        input = null; output = null; muxer = null; client = null; shell = null; manager = null
        if (failed) throw RecordingFailure(RecordingError.CLEANUP)
    }
}
