package com.grinch.rivo4.controller.identification

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.edit
import com.grinch.rivo4.R
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import java.util.Locale

data class CallerLabel(val name: String? = null, val source: String = "",
    val expires: Long = Long.MAX_VALUE, val attribution: String = "",
    val url: String = "", val credits: List<Pair<String, String>> = emptyList())

/** No network or contacts queries on the call/UI thread. Independent from recording. */
class CallerIdentification(private val context: Context) {
    private val storage = context.getSharedPreferences("caller_identification", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val revision = MutableStateFlow(0)
    private val results = java.util.concurrent.ConcurrentHashMap<String, CallerLabel>()
    private val contacts = java.util.concurrent.ConcurrentHashMap<String, CallerLabel>()
    private val resolvedLocally = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pending = LookupWork(scope)
    private val secrets = ProviderSecrets(context)
    private val secretLocks = mapOf("google" to Mutex())
    private val providerJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val statuses = java.util.concurrent.ConcurrentHashMap<String, ProviderStatus>()
    private val direct = DirectProviders(androidHeaders = {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        val certificate = info.signingInfo!!.apkContentsSigners.first().toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(certificate)
        mapOf("X-Android-Package" to context.packageName,
            "X-Android-Cert" to digest.joinToString("") { "%02X".format(it) })
    })
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
        storage.edit { remove("proxy"); remove("token"); remove("available") }
        // Remove retired provider data without touching Google or custom names.
        storage.edit {
            storage.all.keys.filter { it == "ipqs" || it == "verified:ipqs" || it == "spam" || it.startsWith("ipqs:") }.forEach(::remove)
        }
        scope.launch {
            secrets.removeLegacyIpqs()
            listOf("google").forEach { provider ->
                val initialStatus = try {
                    if (secrets.read(provider).isBlank()) ProviderStatus.NOT_CONFIGURED
                    else if (option("verified:$provider")) ProviderStatus.CONFIGURED else ProviderStatus.NOT_CONFIGURED
                } catch (_: Exception) { ProviderStatus.ERROR }
                statuses.putIfAbsent(provider, initialStatus)
            }
            revision.update { it + 1 }
        }
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
    fun status(provider: String) = statuses[provider] ?: ProviderStatus.NOT_CONFIGURED
    suspend fun apiKey(provider: String): String = withContext(Dispatchers.IO) { secrets.read(provider) }
    suspend fun saveAndVerify(provider: String, input: String) = withContext(Dispatchers.IO) {
        val version: Int
        synchronized(this@CallerIdentification) {
            cancelProvider(provider)
            version = providerVersions[provider] ?: 0
            statuses[provider] = ProviderStatus.VERIFYING
            storage.edit(commit = true) { putBoolean("verified:$provider", false) }
            revision.update { it + 1 }
        }
        try {
            if (input.isBlank()) throw ProviderFailure(ProviderStatus.NOT_CONFIGURED)
            secretLocks.getValue(provider).withLock {
                if (version != providerVersions[provider]) return@withContext
                secrets.save(provider, input.trim())
            }
            direct.verify(provider, input.trim())
            synchronized(this@CallerIdentification) {
                if (version == providerVersions[provider]) {
                    storage.edit { putBoolean("verified:$provider", true) }
                    statuses[provider] = ProviderStatus.CONFIGURED
                }
            }
        } catch (e: CancellationException) {
            synchronized(this@CallerIdentification) {
                if (version == providerVersions[provider]) statuses[provider] = ProviderStatus.NOT_CONFIGURED
            }
            throw e
        } catch (e: Exception) {
            synchronized(this@CallerIdentification) {
                if (version == providerVersions[provider]) statuses[provider] = (e as? ProviderFailure)?.status ?: ProviderStatus.ERROR
            }
        } finally { revision.update { it + 1 } }
    }
    suspend fun removeKey(provider: String) = withContext(Dispatchers.IO) {
        secretLocks.getValue(provider).withLock {
            synchronized(this@CallerIdentification) {
                cancelProvider(provider)
                storage.edit { putBoolean(provider, false); remove("verified:$provider") }
                statuses[provider] = ProviderStatus.NOT_CONFIGURED
                revision.update { it + 1 }
            }
            secrets.remove(provider)
        }
    }
    private fun cancelProvider(provider: String) {
        providerVersions.merge(provider, 1, Int::plus)
        providerJobs.filterKeys { it.startsWith("$provider:") }.values.forEach { it.cancel() }
    }
    @Synchronized fun toggle(key: String, enabled: Boolean) {
        if (key == "google") {
            cancelProvider(key)
        } else if (key == "online") invalidate()
        storage.edit { putBoolean(key, enabled) }
        if (key == "google" && !enabled) results.keys.removeAll { it.startsWith("google:") }
        revision.update { it + 1 }
    }
    @Synchronized private fun invalidate() {
        generation++
        lookupStates.clear()
        pending.cancelAll()
    }
    @Synchronized fun clearCache() {
        invalidate()
        results.clear()
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
            option("google"), results["google:$number"], System.currentTimeMillis())
    }
    fun display(label: CallerLabel?): String? = label?.name
    fun source(label: CallerLabel?): String = when (label?.source) {
        "contact" -> context.getString(R.string.caller_contact)
        "custom" -> context.getString(R.string.caller_custom)
        "google" -> "Google Maps" + label.attribution.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
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
        val epoch = generation
        pending.start(key) lookup@{
                lookupStates[number] = R.string.caller_searching
                revision.update { it + 1 }
                try {
                    @Suppress("DEPRECATION")
                    if (PhoneNumberUtils.isEmergencyNumber(raw)) return@lookup
                    // A fresh Android lookup is mandatory before ANY outgoing request.
                    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                    val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                        ?: return@lookup
                    cursor.use {
                        if (it.moveToFirst()) {
                            contacts[number] = CallerLabel(it.getString(0), "contact")
                            resolvedLocally.add(number)
                            revision.update { n -> n + 1 }
                            return@lookup
                        }
                        contacts.remove(number)
                    }
                    local(number)
                    if (!CallerPolicy.mayLookup(number in resolvedLocally, contacts.containsKey(number),
                            value("custom:$number").isNotBlank(), option("online")) || epoch != generation) return@lookup
                    googleProvider providerTask@{ provider ->
                            val providerVersion = providerVersions[provider] ?: 0
                            if (!CallerPolicy.providerMayLookup(option(provider), option("verified:$provider"), epoch, generation)) return@providerTask
                            try {
                                providerJobs["$provider:$number"] = coroutineContext[Job]!!
                                val secret = secrets.read(provider)
                                ensureActive()
                                if (!option(provider) || epoch != generation || providerVersion != (providerVersions[provider] ?: 0)) return@providerTask
                                val data = direct.lookup(provider, secret, number, ::normalize)
                                synchronized(this@CallerIdentification) {
                                if (!CallerPolicy.acceptsResult(epoch, generation, providerVersion,
                                        providerVersions[provider] ?: 0, option(provider), option("online"))) return@providerTask
                                statuses[provider] = ProviderStatus.CONFIGURED
                                require(data["number"]?.jsonPrimitive?.content == number)
                                require(data["provider"]?.jsonPrimitive?.content == provider)
                                val expires = System.currentTimeMillis() + 300000L
                                val stored = JsonObject(data + ("expires" to JsonPrimitive(expires)))
                                if (results.size >= 1000) results.keys.firstOrNull()?.let { results.remove(it) }
                                results["$provider:$number"] = decode(stored, provider)
                                revision.update { n -> n + 1 }
                                expireLater("$provider:$number", expires)
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                synchronized(this@CallerIdentification) {
                                    if (epoch == generation && providerVersion == (providerVersions[provider] ?: 0)) {
                                        val state = (e as? ProviderFailure)?.status ?: ProviderStatus.ERROR
                                        statuses[provider] = state
                                        if (state in listOf(ProviderStatus.INVALID_KEY, ProviderStatus.API_DISABLED, ProviderStatus.BILLING, ProviderStatus.RESTRICTION))
                                            storage.edit { putBoolean("verified:$provider", false) }
                                        revision.update { it + 1 }
                                    }
                                }
                            } finally { providerJobs.remove("$provider:$number", coroutineContext[Job]) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Fail closed if contacts are unavailable. */ }
                finally {
                    if (epoch == generation) {
                        lookupStates[number] = R.string.caller_search_finished
                        revision.update { it + 1 }
                    }
                }
            }
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
    private fun decode(data: JsonObject, provider: String): CallerLabel {
        val name = data["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.take(160)
        require(provider != "google" || name == null || data["verified"]?.jsonPrimitive?.booleanOrNull == true)
        return CallerLabel(name, provider,
            data["expires"]?.jsonPrimitive?.longOrNull ?: 0,
            data["attributions"]?.jsonArray?.mapNotNull { it.jsonObject["provider"]?.jsonPrimitive?.contentOrNull }?.joinToString(", ").orEmpty(),
            data["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            data["attributions"]?.jsonArray?.mapNotNull {
                val providerName = it.jsonObject["provider"]?.jsonPrimitive?.contentOrNull
                val providerUri = it.jsonObject["providerUri"]?.jsonPrimitive?.contentOrNull
                if (providerName != null && providerUri != null) providerName to providerUri else null
            }.orEmpty())
    }
}
