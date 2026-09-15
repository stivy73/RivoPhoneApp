package com.grinch.rivo4.controller.recording

import java.util.concurrent.*

/** Binder transactions do not obey coroutine cancellation. Bound the caller's wait and
 * dispose a descriptor that arrives after timeout. The owner then destroys its user service.
 */
internal fun <T> boundedRemoteCall(timeoutMs: Long, disposeLate: (T) -> Unit = {}, call: () -> T): T {
    val lock = Any()
    var abandoned = false
    var result: T? = null
    var completed = false
    val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "RivoBinderCall").apply { isDaemon = true } }
    val future = executor.submit<T> {
        val value = call()
        synchronized(lock) {
            if (abandoned) disposeLate(value)
            else { result = value; completed = true }
        }
        value
    }
    var claimed = false
    try {
        return future.get(timeoutMs, TimeUnit.MILLISECONDS).also { claimed = true }
    } finally {
        synchronized(lock) {
            if (!claimed) {
                abandoned = true
                if (completed) {
                    @Suppress("UNCHECKED_CAST")
                    disposeLate(result as T)
                }
            }
        }
        future.cancel(true)
        executor.shutdownNow()
    }
}
