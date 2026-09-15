package com.grinch.rivo4.controller.identification

import org.junit.Assert.*
import org.junit.Test

class CallerPolicyTest {
    @Test fun providerOffNeverDispatchesEvenWithVerifiedKey() {
        assertFalse(CallerPolicy.providerMayLookup(false, true, 1, 1))
    }
    @Test fun providerWithoutVerifiedKeyNeverDispatches() {
        assertFalse(CallerPolicy.providerMayLookup(true, false, 1, 1))
        assertFalse(CallerPolicy.providerMayLookup(true, true, 1, 2))
        assertTrue(CallerPolicy.providerMayLookup(true, true, 1, 1))
    }

    private val google = CallerLabel("Business", "google", expires = 100)
    private fun select(contact: CallerLabel? = null, custom: String? = null, online: Boolean = true,
        g: Boolean = true, now: Long = 0) =
        CallerPolicy.select(contact, custom, online, g, google, now)

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
    @Test fun disablingGoogleHidesCachedGoogle() {
        assertEquals("google", select()?.source)
        assertNull(select(g = false))
    }
    @Test fun disablingOnlineHidesAllOnlineNames() { assertNull(select(online = false)) }
    @Test fun expiryHidesStaleIdentities() { assertNull(select(now = 100)) }
    @Test fun staleSessionAndDisabledProviderResponsesAreRejected() {
        assertFalse(CallerPolicy.acceptsResult(1, 2, 0, 0, true, true))
        assertFalse(CallerPolicy.acceptsResult(1, 1, 0, 1, true, true))
        assertFalse(CallerPolicy.acceptsResult(1, 1, 0, 0, false, true))
        assertFalse(CallerPolicy.acceptsResult(1, 1, 0, 0, true, false))
        assertTrue(CallerPolicy.acceptsResult(1, 1, 0, 0, true, true))
    }

    @Test fun explicitSearchMayLookupSavedContactWithoutChangingDisplayPriority() {
        assertTrue(CallerPolicy.mayLookup(true, true, false, true, userRequested = true))
        val saved = CallerLabel("Saved name", "contact")
        assertEquals(saved, select(contact = saved))
    }
    @Test fun explicitSearchMayCompareCustomName() {
        assertTrue(CallerPolicy.mayLookup(true, false, true, true, userRequested = true))
        assertEquals("Private name", select(custom = "Private name")?.name)
    }
    @Test fun explicitSearchStillHonorsOnlineSwitchAndContactPermission() {
        assertFalse(CallerPolicy.mayLookup(true, true, false, false, userRequested = true))
        assertFalse(CallerPolicy.mayLookup(false, true, false, true, userRequested = true))
    }
}
