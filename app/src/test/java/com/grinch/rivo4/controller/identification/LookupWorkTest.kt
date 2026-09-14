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
    private fun scenario(googleDelay: Long, ipqsDelay: Long, failures: Set<String> = emptySet()) = runTest {
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()
        parallelProviders { provider ->
            try {
                delay(if (provider == "google") googleDelay else ipqsDelay)
                if (provider in failures) throw ProviderFailure(ProviderStatus.UNREACHABLE)
                results += provider
            } catch (_: ProviderFailure) { errors += provider }
        }
        assertEquals(failures, errors.toSet())
        assertEquals(setOf("google", "ipqs") - failures, results.toSet())
        if (failures.isEmpty()) assertEquals(if (googleDelay < ipqsDelay) "google" else "ipqs", results.first())
        assertEquals(maxOf(googleDelay, ipqsDelay), currentTime)
    }
    @Test fun googleSlowIpqsFast() = scenario(2000, 20)
    @Test fun ipqsSlowGoogleFast() = scenario(20, 2000)
    @Test fun googleFailsIpqsSurvives() = scenario(10, 20, setOf("google"))
    @Test fun ipqsFailsGoogleSurvives() = scenario(20, 10, setOf("ipqs"))
    @Test fun bothFailSafely() = scenario(10, 20, setOf("ipqs", "google"))
    @Test fun bothSucceed() = scenario(10, 20)
    @Test fun deadlinesAreIndependent() = runTest {
        val client = DirectProviders({ emptyMap() }, ProviderExchange { url, _, _, _ ->
            if ("googleapis" in url) { delay(4000); buildJsonObject {} }
            else { delay(20); buildJsonObject { put("success", true); put("valid", true) } }
        })
        val result = mutableMapOf<String, String>()
        parallelProviders { provider ->
            try { client.lookup(provider, "synthetic", "+390212345678") { it }; result[provider] = "ok" }
            catch (e: ProviderFailure) { result[provider] = e.status.name }
        }
        assertEquals("ok", result["ipqs"]); assertEquals("TIMEOUT", result["google"])
        assertEquals(3000L, currentTime)
    }
    @Test fun ipqsDeadline() = runTest {
        val client = DirectProviders({ emptyMap() }, ProviderExchange { _, _, _, _ -> delay(4000); buildJsonObject {} })
        try { client.verify("ipqs", "synthetic"); fail() } catch (e: ProviderFailure) { assertEquals(ProviderStatus.TIMEOUT, e.status) }
    }
}
