package com.grinch.rivo4.controller.util

import android.content.res.Resources
import androidx.annotation.StringRes

/** Locale-aware text for service callbacks and non-Compose models; contains no Activity reference.
 * Compose-native resources continue to use stringResource. Application updates this on locale changes.
 */
object RivoText {
    lateinit var resources: Resources
    fun get(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)
}
