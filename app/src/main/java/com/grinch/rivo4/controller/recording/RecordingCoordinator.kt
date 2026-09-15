package com.grinch.rivo4.controller.recording

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordingPhase { IDLE, STARTING, RECORDING, STOPPING, ERROR }
enum class RecordingError { SHIZUKU_MISSING, SHIZUKU_STOPPED, PERMISSION, CONNECTION_TIMEOUT, SERVER,
    NULL_PIPE, AUDIO_TIMEOUT, STREAM, WRITE, STORAGE, FOREGROUND, CLEANUP }
class RecordingFailure(val reason: RecordingError) : Exception(reason.name)
data class RecordingSnapshot(
    val sessionId: Long = 0,
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val durationSeconds: Long = 0,
    val error: RecordingError? = null
) {
    val busy: Boolean get() = phase in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.STOPPING)
}

/** One session owns every resource; no callback can address a later recording. */
interface RecordingPipeline {
    suspend fun open(onMediaWritten: () -> Unit, onFailure: (RecordingError) -> Unit)
    fun checkHealth()
    suspend fun close(keepRecording: Boolean, durationSeconds: Long)
}

class RecordingCoordinator(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val startupTimeoutMs: Long = 20_000,
    private val onTransition: (RecordingSnapshot) -> Unit = {}
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(RecordingSnapshot())
    val state = mutableState.asStateFlow()
    private var sequence = 0L
    private var active: Session? = null
    private class Session(val id: Long, val pipeline: RecordingPipeline) {
        val ready = CompletableDeferred<Unit>()
        val stop = CompletableDeferred<Unit>()
        var job: Job? = null
        var error: RecordingError? = null
        var firstAudioAt: Long? = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(factory: () -> RecordingPipeline): Boolean = synchronized(lock) {
        if (active != null) return false
        val session = Session(++sequence, factory())
        active = session
        publish(RecordingSnapshot(session.id, RecordingPhase.STARTING))
        session.job = scope.launch(start = CoroutineStart.ATOMIC) { runSession(session) }
        true
    }

    fun stop(): Boolean = synchronized(lock) {
        val session = active ?: return false
        if (mutableState.value.phase == RecordingPhase.STOPPING) return false
        val starting = mutableState.value.phase == RecordingPhase.STARTING
        publish(mutableState.value.copy(phase = RecordingPhase.STOPPING))
        session.stop.complete(Unit)
        // Cancel cancellable bind/first-audio waits, but close resources in NonCancellable.
        if (starting) session.job?.cancel()
        true
    }

    private fun media(session: Session): Unit = synchronized(lock) {
        if (active !== session || mutableState.value.phase != RecordingPhase.STARTING) return
        session.firstAudioAt = nowMillis()
        publish(mutableState.value.copy(phase = RecordingPhase.RECORDING))
        session.ready.complete(Unit)
        Unit
    }

    private fun fail(session: Session, error: RecordingError) = synchronized(lock) {
        if (active !== session || mutableState.value.phase == RecordingPhase.STOPPING) return
        session.error = error
        val starting = mutableState.value.phase == RecordingPhase.STARTING
        publish(mutableState.value.copy(phase = RecordingPhase.STOPPING, error = error))
        session.stop.complete(Unit)
        if (starting) session.job?.cancel()
    }

    private suspend fun runSession(session: Session) {
        var ticker: Job? = null
        try {
            // A stop can arrive before this atomic job enters its body.
            if (session.stop.isCompleted) return
            withTimeout(startupTimeoutMs) {
                session.pipeline.open({ media(session) }, { fail(session, it) })
                session.ready.await()
            }
            ticker = scope.launch {
                while (isActive) {
                    delay(250)
                    try { session.pipeline.checkHealth() }
                    catch (e: RecordingFailure) { fail(session, e.reason) }
                    catch (_: Exception) { fail(session, RecordingError.SHIZUKU_STOPPED) }
                    synchronized(lock) {
                        if (active === session && mutableState.value.phase == RecordingPhase.RECORDING) {
                            val elapsed = ((nowMillis() - (session.firstAudioAt ?: nowMillis())) / 1000).coerceAtLeast(0)
                            mutableState.value = mutableState.value.copy(durationSeconds = elapsed)
                        }
                    }
                }
            }
            session.stop.await()
        } catch (_: TimeoutCancellationException) {
            synchronized(lock) { if (session.error == null) session.error = RecordingError.AUDIO_TIMEOUT }
        } catch (_: CancellationException) {
            // User stop or lifecycle destruction. Cancellation never implies successful audio.
        } catch (e: RecordingFailure) {
            synchronized(lock) { if (session.error == null) session.error = e.reason }
        } catch (_: Exception) {
            synchronized(lock) { if (session.error == null) session.error = RecordingError.STREAM }
        } finally {
            ticker?.cancel()
            withContext(NonCancellable) {
                synchronized(lock) {
                    if (active === session) publish(mutableState.value.copy(phase = RecordingPhase.STOPPING, error = session.error))
                }
                val duration = synchronized(lock) { mutableState.value.durationSeconds }
                try { session.pipeline.close(session.firstAudioAt != null && session.error == null, duration) }
                catch (e: RecordingFailure) { if (session.error == null) session.error = e.reason }
                catch (_: Exception) { if (session.error == null) session.error = RecordingError.CLEANUP }
                synchronized(lock) {
                    if (active === session) {
                        active = null
                        publish(RecordingSnapshot(session.id,
                            if (session.error == null) RecordingPhase.IDLE else RecordingPhase.ERROR,
                            error = session.error))
                    }
                }
            }
        }
    }

    private fun publish(snapshot: RecordingSnapshot) {
        mutableState.value = snapshot
        onTransition(snapshot)
    }
}
