package com.grinch.rivo4.controller.recording

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingCoordinatorTest {
    private class FakePipeline : RecordingPipeline {
        var opens = 0; var closes = 0; var keep = false; var duration = -1L
        var openError: RecordingError? = null
        var closeError: RecordingError? = null
        var healthError: RecordingError? = null
        var blockOpen = false
        lateinit var media: () -> Unit
        lateinit var fail: (RecordingError) -> Unit
        override suspend fun open(onMediaWritten: () -> Unit, onFailure: (RecordingError) -> Unit) {
            opens++; media = onMediaWritten; fail = onFailure
            openError?.let { throw RecordingFailure(it) }
            if (blockOpen) awaitCancellation()
        }
        override fun checkHealth() { healthError?.let { throw RecordingFailure(it) } }
        override suspend fun close(keepRecording: Boolean, durationSeconds: Long) {
            closes++; keep = keepRecording; duration = durationSeconds
            closeError?.let { throw RecordingFailure(it) }
        }
    }
    private fun TestScope.coordinator() = RecordingCoordinator(backgroundScope, { testScheduler.currentTime }, 1000)
    @Test fun idleToStartingAndDoubleStart() = runTest {
        val c = coordinator(); val p = FakePipeline()
        assertEquals(RecordingPhase.IDLE, c.state.value.phase)
        assertTrue(c.start { p }); assertFalse(c.start { error("duplicate factory invoked") })
        assertEquals(RecordingPhase.STARTING, c.state.value.phase)
        runCurrent(); assertEquals(1,p.opens); c.stop(); runCurrent(); assertEquals(1,p.closes)
    }
    @Test fun recordingOnlyAfterFirstRealAudioAndTimer() = runTest {
        val c = coordinator(); val p = FakePipeline(); c.start { p }; runCurrent()
        advanceTimeBy(400); runCurrent(); assertEquals(RecordingPhase.STARTING,c.state.value.phase)
        assertEquals(0,c.state.value.durationSeconds)
        p.media(); runCurrent(); assertEquals(RecordingPhase.RECORDING,c.state.value.phase)
        advanceTimeBy(2250); runCurrent(); assertEquals(2,c.state.value.durationSeconds)
        p.media(); assertEquals(2,c.state.value.durationSeconds) // second packet cannot reset timer
        assertTrue(c.stop()); assertEquals(RecordingPhase.STOPPING,c.state.value.phase)
        runCurrent(); assertEquals(RecordingPhase.IDLE,c.state.value.phase)
        assertTrue(p.keep); assertEquals(2,p.duration); assertEquals(0,c.state.value.durationSeconds)
    }
    @Test fun stopDuringStartingAndDoubleStop() = runTest {
        val c=coordinator();val p=FakePipeline().apply { blockOpen=true };c.start { p };runCurrent()
        assertTrue(c.stop());assertFalse(c.stop());runCurrent()
        assertEquals(1,p.closes);assertFalse(p.keep);assertEquals(RecordingPhase.IDLE,c.state.value.phase)
        advanceTimeBy(5000);runCurrent();assertEquals(RecordingPhase.IDLE,c.state.value.phase)
    }
    @Test fun stopBeforeStartCoroutineEntersStillCleansUp() = runTest {
        val c=coordinator();val p=FakePipeline();c.start { p };c.stop();runCurrent()
        assertEquals(0,p.opens);assertEquals(1,p.closes);assertEquals(RecordingPhase.IDLE,c.state.value.phase)
    }
    @Test fun startupError() = runTest {
        val c=coordinator();val p=FakePipeline().apply { openError=RecordingError.SERVER };c.start { p };runCurrent()
        assertEquals(RecordingPhase.ERROR,c.state.value.phase);assertEquals(RecordingError.SERVER,c.state.value.error)
        assertEquals(1,p.closes);assertFalse(p.keep)
    }
    @Test fun shizukuTimeout() = runTest {
        val c=coordinator();val p=FakePipeline().apply { openError=RecordingError.CONNECTION_TIMEOUT };c.start { p };runCurrent()
        assertEquals(RecordingError.CONNECTION_TIMEOUT,c.state.value.error);assertEquals(1,p.closes)
    }
    @Test fun nullPipe() = runTest {
        val c=coordinator();val p=FakePipeline().apply { openError=RecordingError.NULL_PIPE };c.start { p };runCurrent()
        assertEquals(RecordingError.NULL_PIPE,c.state.value.error);assertFalse(p.keep);assertEquals(1,p.closes)
    }
    @Test fun firstAudioTimeout() = runTest {
        val c=coordinator();val p=FakePipeline();c.start { p };runCurrent();advanceTimeBy(1001);runCurrent()
        assertEquals(RecordingError.AUDIO_TIMEOUT,c.state.value.error);assertEquals(1,p.closes)
    }
    @Test fun streamFailureWhileStarting() = runTest {
        val c=coordinator();val p=FakePipeline();c.start { p };runCurrent();p.fail(RecordingError.STREAM);runCurrent()
        assertEquals(RecordingError.STREAM,c.state.value.error);assertFalse(p.keep)
    }
    @Test fun unexpectedEofWhileRecording() = runTest {
        val c=coordinator();val p=FakePipeline();c.start { p };runCurrent();p.media();runCurrent()
        p.fail(RecordingError.STREAM);runCurrent();assertEquals(RecordingPhase.ERROR,c.state.value.phase)
        assertFalse(p.keep);assertEquals(1,p.closes)
    }
    @Test fun writeFailureStopsTimer() = runTest {
        val c=coordinator();val p=FakePipeline();c.start { p };runCurrent();p.media();runCurrent()
        advanceTimeBy(2000);runCurrent();p.fail(RecordingError.WRITE);runCurrent()
        assertEquals(RecordingError.WRITE,c.state.value.error);assertFalse(p.keep)
        advanceTimeBy(3000);runCurrent();assertEquals(0,c.state.value.durationSeconds)
    }
    @Test fun permissionRevocation() = runTest {
        val c=coordinator();val p=FakePipeline();c.start { p };runCurrent();p.media();runCurrent()
        p.healthError=RecordingError.PERMISSION;advanceTimeBy(251);runCurrent()
        assertEquals(RecordingError.PERMISSION,c.state.value.error);assertEquals(1,p.closes)
    }
    @Test fun cleanupFailureIsNotSuccess() = runTest {
        val c=coordinator();val p=FakePipeline().apply { closeError=RecordingError.CLEANUP }
        c.start { p };runCurrent();p.media();runCurrent();c.stop();runCurrent()
        assertEquals(RecordingError.CLEANUP,c.state.value.error)
    }
    @Test fun oldCallbacksCannotAffectNextCallAndDurationResets() = runTest {
        val c=coordinator();val first=FakePipeline();c.start { first };runCurrent();first.media();runCurrent()
        advanceTimeBy(2000);runCurrent();c.stop();runCurrent()
        val second=FakePipeline();assertTrue(c.start { second });runCurrent()
        first.media();first.fail(RecordingError.STREAM)
        assertEquals(RecordingPhase.STARTING,c.state.value.phase);assertEquals(0,c.state.value.durationSeconds)
        second.media();runCurrent();advanceTimeBy(1250);runCurrent();assertEquals(1,c.state.value.durationSeconds)
        c.stop();runCurrent();assertEquals(1,first.closes);assertEquals(1,second.closes)
    }
    @Test fun latePacketDuringStopCannotRestart() = runTest {
        val c=coordinator();val p=FakePipeline();c.start { p };runCurrent();c.stop();p.media();runCurrent()
        assertEquals(RecordingPhase.IDLE,c.state.value.phase);assertFalse(p.keep)
    }
}
