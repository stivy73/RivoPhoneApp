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
    @Test fun italianLandlineQueryUsesSeparatedPrefixAndRegionButKeepsCanonicalMatch() = runTest {
        var calls = 0
        val client = DirectProviders({ emptyMap() }, ProviderExchange { _, _, body, _ ->
            calls++
            val request = obj(body!!)
            assertEquals("+39 0212345678", request["textQuery"]!!.jsonPrimitive.content)
            assertEquals("IT", request["regionCode"]!!.jsonPrimitive.content)
            assertEquals(5, request["pageSize"]!!.jsonPrimitive.int)
            obj("""{"places":[{"displayName":{"text":"Shop"},"internationalPhoneNumber":"+39 02 12345678"}]}""")
        })
        val result = client.lookup("google", "synthetic", number, ::normalize)
        assertEquals(1, calls)
        assertEquals(number, result["number"]!!.jsonPrimitive.content)
        assertEquals("Shop", result["name"]!!.jsonPrimitive.content)
        assertTrue(result["verified"]!!.jsonPrimitive.boolean)
    }
    @Test fun italianMobileQueryDoesNotAddALandlineZero() = runTest {
        val client = DirectProviders({ emptyMap() }, ProviderExchange { _, _, body, _ ->
            val request = obj(body!!)
            assertEquals("+39 3123456789", request["textQuery"]!!.jsonPrimitive.content)
            assertEquals("IT", request["regionCode"]!!.jsonPrimitive.content)
            obj("{}")
        })
        client.lookup("google", "synthetic", "+393123456789", ::normalize)
    }
    @Test fun foreignNumberIsNotForcedIntoItalianRegion() = runTest {
        val foreign = "+442071234567"
        val client = DirectProviders({ emptyMap() }, ProviderExchange { _, _, body, _ ->
            val request = obj(body!!)
            assertEquals(foreign, request["textQuery"]!!.jsonPrimitive.content)
            assertFalse(request.containsKey("regionCode"))
            obj("{}")
        })
        client.lookup("google", "synthetic", foreign, ::normalize)
    }
    @Test fun formattedQueryStillRejectsADifferentReturnedNumber() = runTest {
        val client = DirectProviders({ emptyMap() }, ProviderExchange { _, _, _, _ ->
            obj("""{"places":[{"displayName":{"text":"Wrong shop"},"internationalPhoneNumber":"+39 02 99999999"}]}""")
        })
        val result = client.lookup("google", "synthetic", number, ::normalize)
        assertEquals(JsonNull, result["name"])
        assertFalse(result["verified"]!!.jsonPrimitive.boolean)
    }
    @Test fun googleVerificationUsesOnlyIdAndAndroidHeaders() = runTest {
        var calls = 0
        val client = DirectProviders({ mapOf("X-Android-Package" to "test", "X-Android-Cert" to "cert") }, ProviderExchange { url, headers, body, _ ->
            calls++; assertEquals("https://places.googleapis.com/v1/places:searchText", url)
            assertEquals("places.id", headers["X-Goog-FieldMask"])
            assertEquals("synthetic", headers["X-Goog-Api-Key"])
            assertEquals("test", headers["X-Android-Package"])
            val request = obj(body!!)
            assertEquals("Google", request["textQuery"]!!.jsonPrimitive.content)
            assertFalse(request.containsKey("regionCode"))
            assertFalse(body.contains(number)); obj("{}");
        })
        client.verify("google", "synthetic"); assertEquals(1, calls)
    }
    @Test fun missingKeyNeverCallsNetwork() = runTest {
        val client = DirectProviders({ emptyMap() }, ProviderExchange { _, _, _, _ -> error("Network must not be called") })
        for (provider in listOf("google")) {
            try { client.lookup(provider, "", number, ::normalize); fail() } catch (e: ProviderFailure) { assertEquals(ProviderStatus.NOT_CONFIGURED, e.status) }
        }
    }
    @Test fun exceptionContainsNoUpstreamSecret() {
        val status = error(400, "API_KEY_INVALID synthetic-secret")!!
        val error = ProviderFailure(status)
        assertFalse(error.toString().contains("synthetic-secret")); assertNull(error.cause)
    }
    @Test fun businessSearchUsesMinimalBusinessFieldsAndKeepsResultsTransient() = runTest {
        val client = DirectProviders({ emptyMap() }, ProviderExchange { _, headers, body, _ ->
            assertEquals(
                "places.id,places.displayName,places.internationalPhoneNumber,places.nationalPhoneNumber,places.shortFormattedAddress,places.formattedAddress,places.primaryTypeDisplayName,places.googleMapsUri,places.attributions",
                headers["X-Goog-FieldMask"]
            )
            val request = obj(body!!)
            assertEquals("Mezzosale", request["textQuery"]!!.jsonPrimitive.content)
            assertEquals("IT", request["regionCode"]!!.jsonPrimitive.content)
            assertEquals(5, request["pageSize"]!!.jsonPrimitive.int)
            obj("""{"places":[{"id":"place-1","displayName":{"text":"Mezzosale"},"internationalPhoneNumber":"+39 0438 460195","shortFormattedAddress":"Treviso","primaryTypeDisplayName":{"text":"Ristorante"},"googleMapsUri":"https://maps.google.test/place-1"}]}""")
        })
        val result = client.searchBusinesses("synthetic", " Mezzosale ")
        assertEquals(1, result.size)
        assertEquals("place-1", result.single().id)
        assertEquals("Mezzosale", result.single().name)
        assertEquals("+39 0438 460195", result.single().phone)
        assertEquals("Treviso", result.single().address)
    }
    @Test fun businessSearchDropsIncompleteAndDuplicatePlaces() {
        val results = ProviderPayloads.businesses(obj("""{"places":[
            {"id":"same","displayName":{"text":"First"}},
            {"id":"same","displayName":{"text":"Second"}},
            {"id":"missing-name"}
        ]}"""))
        assertEquals(1, results.size)
        assertEquals("First", results.single().name)
    }
}
