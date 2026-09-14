package com.grinch.rivo4.controller

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.grinch.rivo4.BuildConfig
import com.grinch.rivo4.R
import com.grinch.rivo4.controller.recording.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Foreground lifetime follows the one Rivo recorder, including initialization/drain. */
class CallRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var token = 0L
    private val destroyed = CompletableDeferred<Unit>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        instance = java.lang.ref.WeakReference(this)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.call_recording_notification_title), NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        scope.launch {
            CallRecorder.state.collect { state ->
                if (token == state.sessionId && state.busy) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_SESSION, 0) ?: 0
        if (intent?.action == ACTION_STOP) {
            CallRecorder.stopSession(id)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_FINISH) {
            if (token == id) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            return START_NOT_STICKY
        }
        token = id
        try {
            startForeground(NOTIFICATION_ID, notification(CallRecorder.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            val current = CallRecorder.state.value
            if (current.sessionId != id || !current.busy) {
                pending.remove(id)?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            } else pending.remove(id)?.complete(Unit)
        } catch (_: Exception) {
            pending.remove(id)?.completeExceptionally(RecordingFailure(RecordingError.FOREGROUND))
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    private fun notification(state: RecordingSnapshot): Notification {
        val action = Intent(this, CallRecordingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_SESSION, token)
        val pendingStop = PendingIntent.getService(this, token.toInt(), action, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.call_recording_notification_title))
            .setContentText(getString(RecordingStrings.phase(state.phase)))
            .setOngoing(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.action_stop_recording), pendingStop).build()
    }
    override fun onDestroy() {
        scope.cancel()
        pending.remove(token)?.cancel()
        CallRecorder.stopSession(token)
        stopForeground(STOP_FOREGROUND_REMOVE)
        instance = null
        destroyed.complete(Unit)
        super.onDestroy()
    }
    companion object {
        const val CHANNEL_ID = "call_recording_channel"
        const val NOTIFICATION_ID = 4040
        private const val EXTRA_SESSION = "recording_session"
        private const val ACTION_START = BuildConfig.APPLICATION_ID + ".START_RECORDING"
        private const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".STOP_RECORDING"
        private const val ACTION_FINISH = BuildConfig.APPLICATION_ID + ".FINISH_RECORDING"
        private val pending = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
        suspend fun startAndAwait(context: Context, sessionId: Long) {
            val ready = CompletableDeferred<Unit>()
            pending[sessionId] = ready
            try {
                context.startForegroundService(Intent(context, CallRecordingService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_SESSION, sessionId))
                withTimeout(5_000) { ready.await() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { throw RecordingFailure(RecordingError.FOREGROUND) }
            finally { pending.remove(sessionId) }
        }
        private var instance: java.lang.ref.WeakReference<CallRecordingService>? = null
        suspend fun finish(context: Context, sessionId: Long) {
            val completion = withContext(Dispatchers.Main.immediate) {
                pending.remove(sessionId)?.cancel()
                val service = instance?.get()
                if (service != null && service.token == sessionId) {
                    service.stopForeground(STOP_FOREGROUND_REMOVE)
                    service.stopSelf()
                    service.destroyed
                } else {
                    // Cancel an enqueued START without starting another service just to stop it.
                    if (CallRecorder.state.value.sessionId == sessionId) context.stopService(Intent(context, CallRecordingService::class.java))
                    null
                }
            }
            withTimeoutOrNull(1_000) { completion?.await() }
        }
    }
}
