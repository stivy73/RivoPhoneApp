package com.grinch.rivo4.controller.identification

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DirectProvidersTest {
    private val number = "+390212345678"
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun normalize(s: String) = s.replace(" ", "").replace("-", "").takeIf { it.startsWith("+") }
    private fun google(s: String) = ProviderPayloads.google(obj(s), number, ::normalize)
    private fun ipqs(s: String) = ProviderPayloads.ipqs(obj(s), number)
    private fun error(code: Int, message: String) = ProviderPayloads.failure(code, buildJsonObject { put("error", buildJsonObject { put("message", message) }) })
    @Test fun exactPhoneMatchRequired() {
        val result = google("""{"places":[{"displayName":{"text":"Shop"},"internationalPhoneNumber":"+39 02 12345678","id":"123"}]}""")
        assertEquals("Shop", result["name"]!!.jsonPrimitive.content)
        assertTrue(result["verified"]!!.jsonPrimitive.boolean)
        assertEquals("123", result["id"]!!.jsonPrimitive.content)
    }
    @Test fun nationalNumberCannotOverrideDifferentInternationalCountry() {
        assertEquals(JsonNull, google("""{"places":[{"displayName":{"text":"Wrong country"},"internationalPhoneNumber":"+12021234567","nationalPhoneNumber":"+390212345678"}]}""")["name"])
    }
    @Test fun differentPhoneRejected() {
        assertEquals(JsonNull, google("""{"places":[{"displayName":{"text":"Shop"},"internationalPhoneNumber":"+390299999999"}]}""")["name"])
    }
    @Test fun laterExactResultAccepted() {
        val result = google("""{"places":[{"displayName":{"text":"Wrong"},"internationalPhoneNumber":"+390299999999"},{"displayName":{"text":"Right"},"internationalPhoneNumber":"+390212345678"}]}""")
        assertEquals("Right", result["name"]!!.jsonPrimitive.content)
    }
    @Test fun ambiguousSharedNumberNotArbitrarilyNamed() {
        assertEquals(JsonNull, google("""{"places":[{"displayName":{"text":"A"},"internationalPhoneNumber":"+390212345678"},{"displayName":{"text":"B"},"internationalPhoneNumber":"+390212345678"}]}""")["name"])
    }
    @Test fun emptyGoogleResponseIsNoMatch() { assertEquals(JsonNull, google("{}")["name"]) }
    @Test fun invalidKey() { assertEquals(ProviderStatus.INVALID_KEY, error(400, "API_KEY_INVALID")) }
    @Test fun disabledApi() { assertEquals(ProviderStatus.API_DISABLED, error(403, "SERVICE_DISABLED")) }
    @Test fun missingBilling() { assertEquals(ProviderStatus.BILLING, error(403, "BILLING_DISABLED")) }
    @Test fun quotaExceeded() { assertEquals(ProviderStatus.QUOTA, error(429, "")) }
    @Test fun incompatibleRestriction() { assertEquals(ProviderStatus.RESTRICTION, error(403, "API_KEY_ANDROID_APP_BLOCKED")) }
    @Test fun httpServerFailure() { assertEquals(ProviderStatus.UNREACHABLE, error(503, "")) }
    @Test fun ipqsInvalidKey() { assertEquals(ProviderStatus.INVALID_KEY, ProviderPayloads.failure(200, obj("""{"success":false,"message":"Invalid API key"}"""))) }
    @Test fun ipqsQuota() { assertEquals(ProviderStatus.QUOTA, ProviderPayloads.failure(200, obj("""{"success":false,"message":"Insufficient credits"}"""))) }
    @Test fun ipqsSuccessFalseFails() {
        try { ipqs("""{"success":false}"""); fail() } catch (e: ProviderFailure) { assertEquals(ProviderStatus.ERROR, e.status) }
    }
    @Test fun ipqsNameAndMetadata() {
        val result = ipqs("""{"success":true,"valid":true,"name":"Company","formatted":"+39 02 12345678","carrier":"Carrier","line_type":"Landline","country":"IT","risky":true,"recent_abuse":false,"fraud_score":90,"spammer":false}""")
        assertEquals("Company", result["name"]!!.jsonPrimitive.content)
        assertEquals("Carrier", result["carrier"]!!.jsonPrimitive.content)
        assertFalse(result["verified"]!!.jsonPrimitive.boolean)
        assertFalse(result["spamReported"]!!.jsonPrimitive.boolean)
        assertEquals(90, result["risk"]!!.jsonPrimitive.int)
    }
    @Test fun ipqsSpammerSeparateFromRisk() {
        val result = ipqs("""{"success":true,"valid":true,"spammer":true,"fraud_score":10}""")
        assertTrue(result["spamReported"]!!.jsonPrimitive.boolean)
        assertEquals(10, result["risk"]!!.jsonPrimitive.int)
    }
    @Test fun ipqsNoName() { assertEquals(JsonNull, ipqs("""{"success":true,"valid":false,"name":"N/A"}""")["name"]) }
    @Test fun ipqsNameArrayIsNotFirstPerson() { assertEquals(JsonNull, ipqs("""{"success":true,"valid":true,"name":["A","B"]}""")["name"]) }
    @Test fun unexpectedPayloadFails() {
        try { ipqs("{}"); fail() } catch (e: ProviderFailure) { assertEquals(ProviderStatus.ERROR, e.status) }
    }
    @Test fun googleVerificationUsesOnlyIdAndAndroidHeaders() = runTest {
        var calls = 0
        val client = DirectProviders({ mapOf("X-Android-Package" to "test", "X-Android-Cert" to "cert") }, ProviderExchange { url, headers, body, _ ->
            calls++; assertEquals("https://places.googleapis.com/v1/places:searchText", url)
            assertEquals("places.id", headers["X-Goog-FieldMask"])
            assertEquals("synthetic", headers["X-Goog-Api-Key"])
            assertEquals("test", headers["X-Android-Package"])
            assertFalse(body!!.contains(number)); obj("{}");
        })
        client.verify("google", "synthetic"); assertEquals(1, calls)
    }
    @Test fun ipqsVerificationChecksCreditsWithoutPhoneLookup() = runTest {
        val client = DirectProviders({ emptyMap() }, ProviderExchange { url, _, _, _ ->
            assertTrue(url.startsWith("https://www.ipqualityscore.com/api/json/account/"))
            obj("""{"success":true,"credits":12}""")
        })
        client.verify("ipqs", "synthetic")
    }
    @Test fun ipqsVerificationZeroCredits() = runTest {
        val client = DirectProviders({ emptyMap() }, ProviderExchange { _, _, _, _ -> obj("""{"success":true,"credits":0}""") })
        try { client.verify("ipqs", "synthetic"); fail() } catch (e: ProviderFailure) { assertEquals(ProviderStatus.QUOTA, e.status) }
    }
    @Test fun missingKeyNeverCallsNetwork() = runTest {
        val client = DirectProviders({ emptyMap() }, ProviderExchange { _, _, _, _ -> error("Network must not be called") })
        for (provider in listOf("google", "ipqs")) {
            try { client.lookup(provider, "", number, ::normalize); fail() } catch (e: ProviderFailure) { assertEquals(ProviderStatus.NOT_CONFIGURED, e.status) }
        }
    }
    @Test fun ipqsKeyInHeaderNotLookupUrl() = runTest {
        val client = DirectProviders({ emptyMap() }, ProviderExchange { url, headers, body, type ->
            assertEquals("https://ipqualityscore.com/api/json/phone", url)
            assertEquals("synthetic", headers["IPQS-KEY"])
            assertFalse(body!!.contains("synthetic"))
            assertEquals("application/x-www-form-urlencoded", type)
            obj("""{"success":true,"valid":true}""")
        })
        client.lookup("ipqs", "synthetic", number, ::normalize)
    }
    @Test fun exceptionContainsNoUpstreamSecret() {
        val status = error(400, "API_KEY_INVALID synthetic-secret")!!
        val error = ProviderFailure(status)
        assertFalse(error.toString().contains("synthetic-secret")); assertNull(error.cause)
    }
}
