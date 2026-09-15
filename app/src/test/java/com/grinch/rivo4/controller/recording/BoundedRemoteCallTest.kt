package com.grinch.rivo4.controller.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

class BoundedRemoteCallTest {
    @Test fun successfulDescriptorIsOwnedByCaller() {
        val disposed = AtomicInteger()
        val descriptor = Any()
        assertSame(descriptor, boundedRemoteCall(1000, { disposed.incrementAndGet() }) { descriptor })
        assertEquals(0, disposed.get())
    }
    @Test fun timeoutDisposesDescriptorArrivingAfterAbandonmentExactlyOnce() {
        val gate = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val count = AtomicInteger()
        try {
            boundedRemoteCall(30, disposeLate = { _: Any -> count.incrementAndGet(); disposed.countDown() }) {
                // Binder does not respond to Thread.interrupt; simulate its late result.
                while (true) {
                    try { gate.await(); break } catch (_: InterruptedException) { }
                }
                Any()
            }
            fail("Expected bounded timeout")
        } catch (_: TimeoutException) {
            gate.countDown()
        }
        assertTrue(disposed.await(2, TimeUnit.SECONDS))
        assertEquals(1, count.get())
    }
    @Test fun failedTransactionPropagatesAndNeverDisposesAnUncreatedDescriptor() {
        val count = AtomicInteger()
        try {
            boundedRemoteCall<Any>(1000, { count.incrementAndGet() }) { throw SecurityException() }
            fail("Expected transaction failure")
        } catch (e: ExecutionException) { assertTrue(e.cause is SecurityException) }
        assertEquals(0, count.get())
    }
}
