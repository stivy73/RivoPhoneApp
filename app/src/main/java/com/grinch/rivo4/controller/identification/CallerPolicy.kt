package com.grinch.rivo4.controller.identification

/** Pure rules shared by Android presentation and lookup; independent of audio/call state. */
object CallerPolicy {
    fun mayLookup(contactLookupSucceeded: Boolean, hasContact: Boolean, hasCustom: Boolean, online: Boolean) =
        contactLookupSucceeded && !hasContact && !hasCustom && online

    fun acceptsResult(requestEpoch: Int, currentEpoch: Int, requestProviderVersion: Int,
        currentProviderVersion: Int, enabled: Boolean, online: Boolean) =
        requestEpoch == currentEpoch && requestProviderVersion == currentProviderVersion && enabled && online

    fun select(contact: CallerLabel?, custom: String?, online: Boolean, googleEnabled: Boolean,
        ipqsEnabled: Boolean, google: CallerLabel?, ipqs: CallerLabel?, showSpam: Boolean, now: Long): CallerLabel? {
        contact?.let { return it }
        custom?.takeIf { it.isNotBlank() }?.let { return CallerLabel(it, "custom") }
        if (!online) return null
        val g = google?.takeIf { googleEnabled && it.expires > now && it.name != null }
        val i = ipqs?.takeIf { ipqsEnabled && it.expires > now }
        return (g ?: i)?.let { if (showSpam) it.copy(risk = i?.risk, spam = i?.spam == true)
            else it.copy(risk = null, spam = false) }
    }
}
