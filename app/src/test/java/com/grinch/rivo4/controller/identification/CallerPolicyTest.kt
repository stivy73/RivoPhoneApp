package com.grinch.rivo4.controller.identification

import org.junit.Assert.*
import org.junit.Test

class CallerPolicyTest {
    private val google = CallerLabel("Business", "google", expires = 100)
    private val ipqs = CallerLabel("Ambiguous name", "ipqs", risk = 95, expires = 100)
    private fun select(contact: CallerLabel? = null, custom: String? = null, online: Boolean = true,
        g: Boolean = true, i: Boolean = true, spam: Boolean = true, now: Long = 0) =
        CallerPolicy.select(contact, custom, online, g, i, google, ipqs, spam, now)

    @Test fun contactsAlwaysWin() {
        val contact = CallerLabel("Saved contact", "contact")
        assertEquals(contact, select(contact, "Correction"))
        assertFalse(CallerPolicy.mayLookup(true, true, false, true))
    }
    @Test fun failedContactLookupPreventsOnlineDisclosure() {
        assertFalse(CallerPolicy.mayLookup(false, false, false, true))
    }
    @Test fun customNamesWinOfflineAndPreventLookup() {
        assertEquals("Correction", select(custom = "Correction", online = false)?.name)
        assertFalse(CallerPolicy.mayLookup(true, false, true, true))
    }
    @Test fun unknownNumberMayBeLookedUpOnlyWhenEnabled() {
        assertTrue(CallerPolicy.mayLookup(true, false, false, true))
        assertFalse(CallerPolicy.mayLookup(true, false, false, false))
    }
    @Test fun disablingGoogleRevealsIpqsWithoutUsingCachedGoogle() {
        assertEquals("google", select()?.source)
        assertEquals("ipqs", select(g = false)?.source)
        assertNull(select(g = false, i = false))
    }
    @Test fun disablingOnlineHidesAllOnlineNames() { assertNull(select(online = false)) }
    @Test fun expiryHidesStaleIdentities() { assertNull(select(now = 100)) }
    @Test fun highRiskIsNotSpam() {
        assertEquals(95, select()?.risk)
        assertFalse(select()!!.spam)
    }
    @Test fun spamPreferenceDoesNotHideIdentity() {
        assertEquals("Business", select(spam = false)?.name)
        assertNull(select(spam = false)?.risk)
    }
    @Test fun staleSessionAndDisabledProviderResponsesAreRejected() {
        assertFalse(CallerPolicy.acceptsResult(1, 2, 0, 0, true, true))
        assertFalse(CallerPolicy.acceptsResult(1, 1, 0, 1, true, true))
        assertFalse(CallerPolicy.acceptsResult(1, 1, 0, 0, false, true))
        assertFalse(CallerPolicy.acceptsResult(1, 1, 0, 0, true, false))
        assertTrue(CallerPolicy.acceptsResult(1, 1, 0, 0, true, true))
    }
    @Test fun disablingAnotherProviderDoesNotInvalidateIpqs() {
        assertTrue(CallerPolicy.acceptsResult(1, 1, 4, 4, true, true))
    }
}
