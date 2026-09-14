package com.grinch.rivo4.controller.identification

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LookupWorkTest {
    @Test fun duplicateLookupRunsOnceThenCanRefresh() = runTest {
        val work = LookupWork(this)
        var requests = 0
        repeat(2) { work.start("same") { requests++; delay(100) } }
        advanceUntilIdle(); assertEquals(1, requests)
        work.start("same") { requests++ }
        advanceUntilIdle(); assertEquals(2, requests)
    }
    @Test fun cancellationCleansUpAndOldCallbackCannotDeleteNewJob() = runTest {
        val work = LookupWork(this)
        var cancelled = false
        work.start("same") { try { delay(1000) } finally { cancelled = true } }
        runCurrent(); work.cancelAll()
        var requests = 0
        work.start("same") { requests++; delay(100) }
        runCurrent(); work.start("same") { requests++ }
        advanceUntilIdle(); assertTrue(cancelled); assertEquals(1, requests)
    }
    @Test fun onlyGoogleIsDispatched() = runTest {
        val providers = mutableListOf<String>()
        googleProvider { providers += it }
        assertEquals(listOf("google"), providers)
    }
    @Test fun googleDeadline() = runTest {
        val client = DirectProviders({ emptyMap() }, ProviderExchange { _, _, _, _ -> delay(4000); buildJsonObject {} })
        try { client.verify("google", "synthetic"); fail() } catch (e: ProviderFailure) { assertEquals(ProviderStatus.TIMEOUT, e.status) }
        assertEquals(3000L, currentTime)
    }
}
