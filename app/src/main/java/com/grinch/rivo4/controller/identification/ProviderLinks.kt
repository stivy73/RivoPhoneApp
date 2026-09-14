package com.grinch.rivo4.controller.identification

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

object ProviderLinks {
    fun open(context: Context, provider: String): Boolean {
        val url = when (provider) {
            "google" -> "https://developers.google.com/maps/documentation/places/web-service/get-api-key?hl=it"
            "ipqs" -> "https://www.ipqualityscore.com/login"
            else -> return false
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            true
        } catch (_: Exception) { false }
    }
}
