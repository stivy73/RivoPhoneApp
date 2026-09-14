package com.grinch.rivo4.controller.identification

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.edit
import com.grinch.rivo4.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

data class CallerLabel(val name: String? = null, val source: String = "", val risk: Int? = null,
    val spam: Boolean = false, val expires: Long = Long.MAX_VALUE, val attribution: String = "",
    val url: String = "", val credits: List<Pair<String, String>> = emptyList(), val cacheable: Boolean = false)

/** No network or contacts queries on the call/UI thread. Independent from recording. */
class CallerIdentification(private val context: Context) {
    private val storage = context.getSharedPreferences("caller_identification", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val revision = MutableStateFlow(0)
    private val results = java.util.concurrent.ConcurrentHashMap<String, CallerLabel>()
    private val contacts = java.util.concurrent.ConcurrentHashMap<String, CallerLabel>()
    private val resolvedLocally = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val connections = java.util.concurrent.ConcurrentHashMap<String, HttpsURLConnection>()
    private val providerVersions = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val lookupStates = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var generation = 0
    @Volatile private var country = Locale.getDefault().country.uppercase(Locale.ROOT)
    private val countryReady = scope.async {
            country = runCatching {
                context.getSystemService(TelephonyManager::class.java).networkCountryIso
                    .ifBlank { context.getSystemService(TelephonyManager::class.java).simCountryIso }
            }.getOrDefault("").ifBlank { country }.uppercase(Locale.ROOT)
    }

    init {
        // One event-driven observer; changes never trigger provider requests.
        runCatching {
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true,
                object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        val known = resolvedLocally.toList()
                        invalidate()
                        resolvedLocally.clear()
                        contacts.clear()
                        revision.update { it + 1 }
                        scope.launch { known.forEach { local(it) } }
                    }
                })
        }
    }

    fun option(key: String, default: Boolean = false) = storage.getBoolean(key, default)
    fun value(key: String) = storage.getString(key, "").orEmpty()
    @Synchronized fun configure(key: String, value: String) {
        if ((key == "proxy" || key == "token") && value.trim() != this.value(key)) clearCache()
        else invalidate()
        storage.edit { putString(key, value.trim()) }
        revision.update { it + 1 }
    }
    @Synchronized fun toggle(key: String, enabled: Boolean) {
        if (key == "google" || key == "ipqs") {
            providerVersions.merge(key, 1, Int::plus)
            val old = connections.filterKeys { it.startsWith("$key:") }.values.toList()
            scope.launch { old.forEach { it.disconnect() } }
        } else if (key == "online") invalidate()
        storage.edit { putBoolean(key, enabled) }
        if (key == "google" && !enabled) results.keys.removeAll { it.startsWith("google:") }
        revision.update { it + 1 }
    }
    @Synchronized private fun invalidate() {
        generation++
        lookupStates.clear()
        pending.values.forEach { it.cancel() }
        pending.clear()
        // Disconnect on IO so changing a setting cannot stall the UI.
        val old = connections.values.toList()
        scope.launch { old.forEach { it.disconnect() } }
    }
    @Synchronized fun clearCache() {
        invalidate()
        results.clear()
        storage.edit { storage.all.keys.filter { it.startsWith("ipqs:") }.forEach(::remove) }
        revision.update { it + 1 }
    }

    fun normalize(raw: String): String? {
        if (raw.length > 40 || !raw.matches(Regex("[+0-9 ().-]+"))) return null
        if (raw.count { it.isDigit() } < 8) return null
        return PhoneNumberUtils.formatNumberToE164(raw, country)?.takeIf { it.matches(Regex("\\+[1-9][0-9]{7,14}")) }
    }

    fun customNames(): Map<String, String> = storage.all.filterKeys { it.startsWith("custom:") }
        .mapKeys { it.key.removePrefix("custom:") }.mapValues { it.value as? String ?: "" }
    fun setCustom(raw: String, name: String): Boolean {
        val number = normalize(raw) ?: return false
        storage.edit {
            if (name.isBlank()) remove("custom:$number")
            else putString("custom:$number", name.trim().filter { !it.isISOControl() }.take(160))
        }
        revision.update { it + 1 }
        return true
    }
    fun lookupState(raw: String): Int? = normalize(raw)?.let { lookupStates[it] }
    @Synchronized fun label(raw: String): CallerLabel? {
        val number = normalize(raw) ?: return null
        if (number !in resolvedLocally) return null
        return CallerPolicy.select(contacts[number], value("custom:$number"), option("online"),
            option("google"), option("ipqs"), results["google:$number"], results["ipqs:$number"],
            option("spam", true), System.currentTimeMillis())
    }
    fun display(label: CallerLabel?): String? = label?.name?.let {
        if (label.source == "ipqs") context.getString(R.string.caller_possible, it) else it
    }
    fun source(label: CallerLabel?): String = when (label?.source) {
        "contact" -> context.getString(R.string.caller_contact)
        "custom" -> context.getString(R.string.caller_custom)
        "google" -> "Google Maps" + label.attribution.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
        "ipqs" -> "IPQualityScore"
        else -> ""
    }

    /** Called by presentation for local-only refresh; never traverses history online. */
    suspend fun local(raw: String) = withContext(Dispatchers.IO) {
        countryReady.await()
        val number = normalize(raw) ?: return@withContext
        try {
            if (resolvedLocally.size >= 1000 && number !in resolvedLocally) {
                val oldest = resolvedLocally.firstOrNull()
                if (oldest != null) { resolvedLocally.remove(oldest); contacts.remove(oldest) }
            }
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?: return@withContext
            cursor.use {
                if (it.moveToFirst()) contacts[number] = CallerLabel(it.getString(0), "contact")
                else contacts.remove(number)
            }
            resolvedLocally.add(number)
            if (!results.containsKey("ipqs:$number")) {
                value("ipqs:$number").takeIf { it.isNotBlank() }?.let { encoded ->
                    runCatching { decode(Json.parseToJsonElement(encoded).jsonObject, "ipqs") }
                        .getOrNull()?.let { results["ipqs:$number"] = it; expireLater("ipqs:$number", it.expires) }
                }
            }
            revision.update { it + 1 }
        } catch (_: Exception) { resolvedLocally.remove(number) }
    }

    fun identify(raw: String, allowedPresentation: Boolean = true, refresh: Boolean = false) {
        if (!allowedPresentation) return
        scope.launch {
            countryReady.await()
            beginIdentify(raw, refresh)
        }
    }

    private fun beginIdentify(raw: String, refresh: Boolean) {
        val number = normalize(raw) ?: return
        val key = "lookup:$number"
        synchronized(pending) {
            if (pending[key]?.isActive == true) return
            val epoch = generation
            val job = scope.launch(start = CoroutineStart.LAZY) {
                lookupStates[number] = R.string.caller_searching
                revision.update { it + 1 }
                try {
                    @Suppress("DEPRECATION")
                    if (PhoneNumberUtils.isEmergencyNumber(raw)) return@launch
                    // A fresh Android lookup is mandatory before ANY outgoing request.
                    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                    val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                        ?: return@launch
                    cursor.use {
                        if (it.moveToFirst()) {
                            contacts[number] = CallerLabel(it.getString(0), "contact")
                            resolvedLocally.add(number)
                            revision.update { n -> n + 1 }
                            return@launch
                        }
                        contacts.remove(number)
                    }
                    local(number)
                    if (!CallerPolicy.mayLookup(number in resolvedLocally, contacts.containsKey(number),
                            value("custom:$number").isNotBlank(), option("online")) || epoch != generation) return@launch
                    supervisorScope {
                        listOf("google", "ipqs").forEach { provider -> launch {
                            val providerVersion = providerVersions[provider] ?: 0
                            if (!option(provider) || epoch != generation) return@launch
                            val cached = results["$provider:$number"]
                            if (provider == "ipqs" && !refresh && cached?.cacheable == true && cached.expires > System.currentTimeMillis()) return@launch
                            try {
                                val data = request("/v1/identify", buildJsonObject {
                                    put("number", number); put("provider", provider); put("enabled", true); put("refresh", refresh)
                                }, "$provider:$number", epoch, provider, providerVersion)
                                synchronized(this@CallerIdentification) {
                                if (!CallerPolicy.acceptsResult(epoch, generation, providerVersion,
                                        providerVersions[provider] ?: 0, option(provider), option("online"))) return@launch
                                require(data["number"]?.jsonPrimitive?.content == number)
                                require(data["provider"]?.jsonPrimitive?.content == provider)
                                val ttl = (data["ttlSeconds"]?.jsonPrimitive?.longOrNull ?: 0).coerceIn(0, 86400)
                                val expires = System.currentTimeMillis() + if (provider == "google" || ttl == 0L) 300000L else ttl * 1000
                                val stored = JsonObject(data + ("expires" to JsonPrimitive(expires)))
                                if (results.size >= 1000) results.keys.firstOrNull()?.let { results.remove(it) }
                                results["$provider:$number"] = decode(stored, provider)
                                if (provider == "ipqs" && ttl > 0) {
                                    val keys = storage.all.keys.filter { it.startsWith("ipqs:") }
                                    storage.edit {
                                        if (keys.size >= 500) keys.firstOrNull()?.let(::remove)
                                        putString("ipqs:$number", stored.toString())
                                    }
                                }
                                revision.update { n -> n + 1 }
                                expireLater("$provider:$number", expires)
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* Isolated provider failure. Never affects calls/audio. */ }
                        } }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Fail closed if contacts are unavailable. */ }
                finally {
                    if (epoch == generation) {
                        lookupStates[number] = R.string.caller_search_finished
                        revision.update { it + 1 }
                    }
                    synchronized(pending) { if (pending[key] == coroutineContext[Job]) pending.remove(key) }
                }
            }
            pending[key] = job
            job.start()
        }
    }

    suspend fun verify(): String = withContext(Dispatchers.IO) {
        val data = request("/v1/status", null, "status", generation)
        val available = data["providers"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        require(available.all { it in listOf("google", "ipqs") })
        configure("available", available.joinToString(","))
        available.joinToString(", ")
    }
    private fun expireLater(key: String, expires: Long) {
        scope.launch {
            delay((expires - System.currentTimeMillis()).coerceAtLeast(0))
            synchronized(this@CallerIdentification) {
                if (results[key]?.expires == expires) {
                    results.remove(key)
                    revision.update { it + 1 }
                }
            }
        }
    }
    private fun request(path: String, body: JsonObject?, key: String, epoch: Int, provider: String? = null, providerVersion: Int = 0): JsonObject {
        check(epoch == generation)
        val url = URL(value("proxy").trimEnd('/') + path)
        require(url.protocol == "https" && url.userInfo == null && value("token").length >= 32)
        val connection = url.openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 2000; connection.readTimeout = 4500
        connection.setRequestProperty("Authorization", "Bearer ${value("token")}")
        connections[key] = connection
        try {
            check(epoch == generation)
            if (provider != null) check(option(provider) && option("online") && providerVersion == (providerVersions[provider] ?: 0))
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode == 200)
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 65536) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 65537 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size <= 65536)
            return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } finally { connections.remove(key, connection); connection.disconnect() }
    }
    private fun decode(data: JsonObject, provider: String): CallerLabel {
        val name = data["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.take(160)
        require(provider != "google" || name == null || data["verified"]?.jsonPrimitive?.booleanOrNull == true)
        return CallerLabel(name, provider, data["risk"]?.jsonPrimitive?.intOrNull?.coerceIn(0,100),
            data["spamReported"]?.jsonPrimitive?.booleanOrNull == true,
            data["expires"]?.jsonPrimitive?.longOrNull ?: 0,
            data["attributions"]?.jsonArray?.mapNotNull { it.jsonObject["provider"]?.jsonPrimitive?.contentOrNull }?.joinToString(", ").orEmpty(),
            data["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            data["attributions"]?.jsonArray?.mapNotNull {
                val providerName = it.jsonObject["provider"]?.jsonPrimitive?.contentOrNull
                val providerUri = it.jsonObject["providerUri"]?.jsonPrimitive?.contentOrNull
                if (providerName != null && providerUri != null) providerName to providerUri else null
            }.orEmpty(), provider == "ipqs" && (data["ttlSeconds"]?.jsonPrimitive?.longOrNull ?: 0) > 0)
    }
}
