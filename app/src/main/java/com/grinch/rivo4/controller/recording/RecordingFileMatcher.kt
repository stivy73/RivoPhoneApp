package com.grinch.rivo4.controller.recording

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object RecordingFileMatcher {
    private val timestampSuffix = Regex("_(\\d{8})_(\\d{9})$")

    fun callerLabel(file: File): String = callerLabel(file.nameWithoutExtension)

    fun callerLabel(fileNameWithoutExtension: String): String {
        val match = timestampSuffix.find(fileNameWithoutExtension)
        return if (match != null) fileNameWithoutExtension.removeRange(match.range) else fileNameWithoutExtension
    }

    fun recordedAt(file: File): Long {
        val match = timestampSuffix.find(file.nameWithoutExtension) ?: return file.lastModified()
        return runCatching {
            SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).apply { isLenient = false }
                .parse("${match.groupValues[1]}_${match.groupValues[2]}")
                ?.time
        }.getOrNull() ?: file.lastModified()
    }

    fun forContact(
        recordings: List<File>,
        displayName: String,
        phoneNumbers: Collection<String>
    ): List<File> {
        val cleanDisplay = sanitizeLabel(displayName)
        val phoneDigits = phoneNumbers.map(::digits).filter { it.length >= 6 }
        return recordings.filter { file ->
            val label = callerLabel(file)
            val labelDigits = digits(label)
            val matchesName = cleanDisplay.length >= 3 &&
                (label.contains(cleanDisplay, ignoreCase = true) || cleanDisplay.contains(label, ignoreCase = true))
            val matchesPhone = phoneDigits.any { phone ->
                labelDigits.contains(phone) || (labelDigits.length >= 6 && phone.contains(labelDigits))
            }
            matchesName || matchesPhone
        }
    }

    fun forCall(recordings: List<File>, callStartMillis: Long, durationSeconds: Long): List<File> {
        val earliest = callStartMillis - 30_000L
        val latest = callStartMillis + durationSeconds.coerceAtLeast(0L) * 1_000L + 300_000L
        return recordings.filter { recordedAt(it) in earliest..latest }
            .sortedBy { kotlin.math.abs(recordedAt(it) - callStartMillis) }
    }

    private fun sanitizeLabel(value: String): String =
        value.replace(Regex("[^\\p{L}\\p{N}]"), "_").trim('_')

    private fun digits(value: String): String = value.filter(Char::isDigit)
}
