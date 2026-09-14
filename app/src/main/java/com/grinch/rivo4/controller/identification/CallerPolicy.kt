package com.grinch.rivo4.controller.identification

/** Pure rules shared by Android presentation and lookup; independent of audio/call state. */
object CallerPolicy {
    fun providerMayLookup(enabled: Boolean, verifiedKey: Boolean, epoch: Int, currentEpoch: Int) =
        enabled && verifiedKey && epoch == currentEpoch

    fun mayLookup(contactLookupSucceeded: Boolean, hasContact: Boolean, hasCustom: Boolean, online: Boolean, userRequested: Boolean = false) =
        contactLookupSucceeded && online && (userRequested || (!hasContact && !hasCustom))

    fun acceptsResult(requestEpoch: Int, currentEpoch: Int, requestProviderVersion: Int,
        currentProviderVersion: Int, enabled: Boolean, online: Boolean) =
        requestEpoch == currentEpoch && requestProviderVersion == currentProviderVersion && enabled && online

    fun select(contact: CallerLabel?, custom: String?, online: Boolean, googleEnabled: Boolean,
        google: CallerLabel?, now: Long): CallerLabel? {
        contact?.let { return it }
        custom?.takeIf { it.isNotBlank() }?.let { return CallerLabel(it, "custom") }
        if (!online) return null
        return google?.takeIf { googleEnabled && it.expires > now && it.name != null }
    }
}
