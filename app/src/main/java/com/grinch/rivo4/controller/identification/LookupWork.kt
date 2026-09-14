package com.grinch.rivo4.controller.identification

import kotlinx.coroutines.*

/** Owns event-driven lookup jobs; duplicate calls join the existing work instead of issuing requests. */
internal class LookupWork(private val scope: CoroutineScope) {
    private val jobs = mutableMapOf<String, Job>()
    @Synchronized fun start(key: String, block: suspend CoroutineScope.() -> Unit) {
        if (jobs[key]?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { block() } finally {
                synchronized(this@LookupWork) { if (jobs[key] == coroutineContext[Job]) jobs.remove(key) }
            }
        }
        jobs[key] = job
        job.start()
    }
    @Synchronized fun cancelAll() { jobs.values.toList().forEach { it.cancel() }; jobs.clear() }
}

internal suspend fun parallelProviders(block: suspend CoroutineScope.(String) -> Unit) = supervisorScope {
    listOf("google", "ipqs").forEach { provider -> launch { block(provider) } }
}
