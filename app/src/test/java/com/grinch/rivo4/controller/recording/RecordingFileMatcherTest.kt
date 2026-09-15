package com.grinch.rivo4.controller.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class RecordingFileMatcherTest {
    @Test
    fun extractsCallerLabelWithoutDroppingUnderscoresInName() {
        assertEquals(
            "Mario_Rossi",
            RecordingFileMatcher.callerLabel("Mario_Rossi_20260914_101530123")
        )
    }

    @Test
    fun matchesContactBySanitizedNameOrPhoneNumber() {
        val recordings = listOf(
            File("Mario_Rossi_20260914_101530123.ogg"),
            File("+393331234567_20260914_111530123.ogg"),
            File("Altro_20260914_121530123.ogg")
        )

        val matches = RecordingFileMatcher.forContact(
            recordings = recordings,
            displayName = "Mario Rossi",
            phoneNumbers = listOf("+39 333 123 4567")
        )

        assertEquals(recordings.take(2), matches)
    }

    @Test
    fun linksOnlyRecordingsInsideCallTimeWindow() {
        val callStart = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US)
            .parse("20260914_101500000")!!.time
        val duringCall = File("Mario_Rossi_20260914_101530123.ogg")
        val laterCall = File("Mario_Rossi_20260914_121530123.ogg")

        val matches = RecordingFileMatcher.forCall(
            recordings = listOf(duringCall, laterCall),
            callStartMillis = callStart,
            durationSeconds = 120
        )

        assertEquals(listOf(duringCall), matches)
        assertTrue(RecordingFileMatcher.recordedAt(duringCall) >= callStart)
    }
}
