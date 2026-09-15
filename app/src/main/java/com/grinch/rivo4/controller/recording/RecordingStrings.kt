package com.grinch.rivo4.controller.recording
import com.grinch.rivo4.R
object RecordingStrings {
    fun phase(value: RecordingPhase): Int = when (value) {
        RecordingPhase.IDLE -> R.string.recorder_idle
        RecordingPhase.STARTING -> R.string.recorder_starting
        RecordingPhase.RECORDING -> R.string.call_recording_in_progress
        RecordingPhase.STOPPING -> R.string.recorder_stopping
        RecordingPhase.ERROR -> R.string.recorder_error
    }
    fun error(value: RecordingError): Int = when (value) {
        RecordingError.SHIZUKU_MISSING -> R.string.recorder_error_shizuku_missing
        RecordingError.SHIZUKU_STOPPED -> R.string.recorder_error_shizuku_stopped
        RecordingError.PERMISSION -> R.string.recorder_error_permission
        RecordingError.CONNECTION_TIMEOUT -> R.string.recorder_error_connection_timeout
        RecordingError.SERVER -> R.string.recorder_error_server
        RecordingError.NULL_PIPE -> R.string.recorder_error_null_pipe
        RecordingError.AUDIO_TIMEOUT -> R.string.recorder_error_audio_timeout
        RecordingError.STREAM -> R.string.recorder_error_stream
        RecordingError.WRITE -> R.string.recorder_error_write
        RecordingError.STORAGE -> R.string.recorder_error_storage
        RecordingError.FOREGROUND -> R.string.recorder_error_foreground
        RecordingError.CLEANUP -> R.string.recorder_error_cleanup
    }
}
