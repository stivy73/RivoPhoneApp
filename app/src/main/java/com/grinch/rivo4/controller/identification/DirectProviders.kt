package com.grinch.rivo4.controller.identification

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URL
import java.net.URLEncoder
import java.net.SocketTimeoutException
import java.io.IOException
import javax.net.ssl.HttpsURLConnection

// Only typed, sanitized errors cross the transport boundary. No upstream text/URLs in exceptions.
enum class ProviderStatus { NOT_CONFIGURED, VERIFYING, CONFIGURED, INVALID_KEY, API_DISABLED,
    BILLING, QUOTA, RESTRICTION, TIMEOUT, UNREACHABLE, ERROR }
class ProviderFailure(val status: ProviderStatus) : Exception(status.name)

object ProviderPayloads {
    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }
        ?.content?.trim()?.takeIf { it.lowercase() !in listOf("", "n/a", "unknown", "null", "unavailable") }
        ?.filter { !it.isISOControl() }?.take(160)
    fun failure(code: Int, data: JsonObject): ProviderStatus? {
        val error = data["error"] as? JsonObject
        if (code in 200..299 && error == null && data["success"]?.jsonPrimitive?.booleanOrNull != false) return null
        // Inspect but never expose raw messages, which may contain credentials.
        val hint = (error?.toString() ?: data["message"]?.toString().orEmpty()).lowercase()
        return when {
            "billing" in hint -> ProviderStatus.BILLING
            "service_disabled" in hint || "not enabled" in hint || "has not been used" in hint -> ProviderStatus.API_DISABLED
            code == 429 || "quota" in hint || "credit" in hint || "exceeded" in hint -> ProviderStatus.QUOTA
            "blocked" in hint || "restriction" in hint || "android" in hint || "referer" in hint -> ProviderStatus.RESTRICTION
            code == 401 || "api_key_invalid" in hint || "invalid key" in hint || "invalid api" in hint || "unauthorized" in hint -> ProviderStatus.INVALID_KEY
            code >= 500 -> ProviderStatus.UNREACHABLE
            else -> ProviderStatus.ERROR
        }
    }
    fun google(data: JsonObject, number: String, normalize: (String) -> String?): JsonObject {
        val places = data["places"] as? JsonArray ?: if (data.isEmpty()) JsonArray(emptyList())
            else throw ProviderFailure(ProviderStatus.ERROR)
        val matches = places.map { it.jsonObject }.filter { place ->
            // A national number without its country is not sufficient evidence.
            place["internationalPhoneNumber"]?.jsonPrimitive?.contentOrNull?.let(normalize) == number
        }
        // A shared switchboard can match several businesses. Do not arbitrarily name one.
        val names = matches.mapNotNull { text((it["displayName"] as? JsonObject)?.get("text")) }.distinct()
        val place = if (names.size == 1) matches.firstOrNull {
            text((it["displayName"] as? JsonObject)?.get("text")) == names.single()
        } else null
        return buildJsonObject {
            put("number", number); put("provider", "google"); put("verified", place != null); put("ttlSeconds", 0)
            put("name", place?.let { text((it["displayName"] as? JsonObject)?.get("text")) }?.let(::JsonPrimitive) ?: JsonNull)
            put("url", place?.get("googleMapsUri") ?: JsonPrimitive(""))
            put("attributions", place?.get("attributions") ?: JsonArray(emptyList()))
            listOf("id", "internationalPhoneNumber", "nationalPhoneNumber", "primaryType").forEach {
                place?.get(it)?.let { value -> put(it, value) }
            }
        }
    }
    fun ipqs(data: JsonObject, number: String): JsonObject {
        if (data["success"]?.jsonPrimitive?.booleanOrNull != true || data["valid"]?.jsonPrimitive?.booleanOrNull == null)
            throw ProviderFailure(failure(200, data) ?: ProviderStatus.ERROR)
        return buildJsonObject {
            put("number", number); put("provider", "ipqs"); put("verified", false); put("ttlSeconds", 3600)
            put("name", text(data["name"])?.let(::JsonPrimitive) ?: JsonNull)
            put("spamReported", data["spammer"]?.jsonPrimitive?.booleanOrNull == true)
            put("risk", data["fraud_score"]?.jsonPrimitive?.intOrNull?.coerceIn(0,100)?.let(::JsonPrimitive) ?: JsonNull)
            listOf("valid", "formatted", "carrier", "line_type", "country", "spammer", "risky", "recent_abuse", "fraud_score").forEach {
                data[it]?.let { value -> put(it, value) }
            }
        }
    }
}

/** Independent 3-second deadlines, cancellable blocking I/O, fixed official HTTPS hosts. */
fun interface ProviderExchange {
    suspend fun request(url: String, headers: Map<String, String>, body: String?, contentType: String): JsonObject
}
class DirectProviders(private val androidHeaders: () -> Map<String, String>,
    private val exchange: ProviderExchange = HttpsProviderExchange()) {
    private suspend fun http(url: String, headers: Map<String, String> = emptyMap(), body: String? = null,
        contentType: String = "application/json"): JsonObject = try {
        withTimeout(3000) { exchange.request(url, headers, body, contentType) }
    } catch (_: TimeoutCancellationException) { throw ProviderFailure(ProviderStatus.TIMEOUT) }
    suspend fun lookup(provider: String, secret: String, number: String, normalize: (String) -> String?): JsonObject {
        if (secret.isBlank()) throw ProviderFailure(ProviderStatus.NOT_CONFIGURED)
        return if (provider == "google") ProviderPayloads.google(google(secret, number, false), number, normalize)
        else ProviderPayloads.ipqs(http("https://ipqualityscore.com/api/json/phone", mapOf("IPQS-KEY" to secret),
            "phone=" + URLEncoder.encode(number, "UTF-8"), "application/x-www-form-urlencoded"), number)
    }
    suspend fun verify(provider: String, secret: String) {
        if (secret.isBlank()) throw ProviderFailure(ProviderStatus.NOT_CONFIGURED)
        if (provider == "google") {
            val result = google(secret, "Google", true)
            if (result.isNotEmpty() && result["places"] !is JsonArray) throw ProviderFailure(ProviderStatus.ERROR)
        } else {
            // Account endpoint consumes no phone lookup and sends no real telephone number.
            val data = http("https://www.ipqualityscore.com/api/json/account/" + URLEncoder.encode(secret, "UTF-8"))
            if (data["success"]?.jsonPrimitive?.booleanOrNull != true || data["credits"]?.jsonPrimitive?.longOrNull == null)
                throw ProviderFailure(ProviderStatus.ERROR)
            if (data.getValue("credits").jsonPrimitive.long <= 0) throw ProviderFailure(ProviderStatus.QUOTA)
        }
    }
    private suspend fun google(secret: String, query: String, verify: Boolean) = http(
        "https://places.googleapis.com/v1/places:searchText",
        androidHeaders() + mapOf("X-Goog-Api-Key" to secret, "X-Goog-FieldMask" to if (verify) "places.id" else
            "places.id,places.displayName,places.internationalPhoneNumber,places.nationalPhoneNumber,places.primaryType,places.attributions,places.googleMapsUri"),
        buildJsonObject { put("textQuery", query); put("pageSize", if (verify) 1 else 5); put("languageCode", "it") }.toString())

 }

internal class HttpsProviderExchange : ProviderExchange {
    override suspend fun request(url: String, headers: Map<String, String>, body: String?,
        contentType: String): JsonObject {
        try {
            return withTimeout(3000) {
                withContext(Dispatchers.IO) {
                    val connection = URL(url).openConnection() as HttpsURLConnection
                    // Separate child performs disconnect on cancellation/deadline even during a blocking read.
                    val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { connection.disconnect() }
                    }
                    try {
                        ensureActive()
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 1500; connection.readTimeout = 2500
                        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                        if (body != null) {
                            connection.requestMethod = "POST"; connection.doOutput = true
                            connection.setRequestProperty("Content-Type", contentType)
                            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val bytes = stream?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (output.size() <= 65536) {
                                val count = input.read(buffer, 0, minOf(buffer.size, 65537 - output.size()))
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: byteArrayOf()
                        if (bytes.size > 65536) throw ProviderFailure(ProviderStatus.ERROR)
                        val data = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrElse {
                            throw ProviderFailure(when { code == 429 -> ProviderStatus.QUOTA; code >= 500 -> ProviderStatus.UNREACHABLE; else -> ProviderStatus.ERROR })
                        }
                        ProviderPayloads.failure(code, data)?.let { throw ProviderFailure(it) }
                        data
                    } finally { closer.cancel(); connection.disconnect() }
                }
            }
        } catch (_: TimeoutCancellationException) { throw ProviderFailure(ProviderStatus.TIMEOUT) }
        catch (e: CancellationException) { throw e }
        catch (e: ProviderFailure) { throw e }
        catch (_: SocketTimeoutException) { throw ProviderFailure(ProviderStatus.TIMEOUT) }
        catch (_: IOException) { throw ProviderFailure(ProviderStatus.UNREACHABLE) }
        catch (_: Exception) { throw ProviderFailure(ProviderStatus.ERROR) }
    }
}
