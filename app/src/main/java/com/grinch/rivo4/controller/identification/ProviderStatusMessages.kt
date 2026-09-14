package com.grinch.rivo4.controller.identification

import com.grinch.rivo4.R

/** Shared sanitized feedback for settings and individual lookup failures. */
fun ProviderStatus.messageResource(): Int = when (this) {
    ProviderStatus.NOT_CONFIGURED -> R.string.api_not_configured
    ProviderStatus.VERIFYING -> R.string.api_verifying
    ProviderStatus.CONFIGURED -> R.string.api_configured
    ProviderStatus.INVALID_KEY -> R.string.api_invalid
    ProviderStatus.API_DISABLED -> R.string.api_disabled
    ProviderStatus.BILLING -> R.string.api_billing
    ProviderStatus.QUOTA -> R.string.api_quota
    ProviderStatus.RESTRICTION -> R.string.api_restriction
    ProviderStatus.TIMEOUT -> R.string.api_timeout
    ProviderStatus.UNREACHABLE -> R.string.api_unreachable
    ProviderStatus.ERROR -> R.string.api_error
}
