package org.rustlanglatam.zeroclaw.android

import io.github.rustlanglatam.zeroclaw.CatalogModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    private fun p(name: String) = CatalogModelProvider(name, name.uppercase(), local = false)

    @Test fun theChoiceFirstThenTheCatalogsOrderAndCustomLast() {
        val catalog = listOf(p("a"), p(Keys.CUSTOM), p("b"), p("c"), p("d"))
        assertEquals(listOf("c", "d", "a", "b", Keys.CUSTOM), ModelCatalog.ordered(catalog, current = "c", build = "d").map { it.first })
        assertEquals("C", ModelCatalog.ordered(catalog, current = "c", build = "").first().second)
    }

    @Test fun perTokenPricesBecomePerMillion() {
        assertEquals("0.25", ModelCatalog.perMillion("0.00000025"))
        assertEquals("15", ModelCatalog.perMillion("0.000015"))
        assertNull(ModelCatalog.perMillion("n/a"))
        assertNull(ModelCatalog.perMillion(null))
    }

    @Test fun theVoiceServiceIsNamedByItsHost() {
        assertEquals("voice.example.com", voiceHost("wss://voice.example.com/v1/realtime?x=1"))
        assertNull(voiceHost(""))
    }

    @Test fun theListedVoiceServicesAreComplete() {
        val list = CloudVoice.providers(java.io.File("src/main/assets/voices.json").readText())
        assertTrue(list.isNotEmpty())
        list.forEach { p ->
            assertTrue(p.url.startsWith("wss://")); assertTrue(p.model.isNotBlank()); assertTrue(p.voices.isNotEmpty())
            assertTrue(Keys.valid(Keys.Key.VOICE_URL, p.url)); assertTrue(Keys.valid(Keys.Key.VOICE_PER_MINUTE, p.perMinute))
        }
        assertEquals(list.first(), CloudVoice.providerOf(" ${list.first().url} ", list))
        assertNull(CloudVoice.providerOf("wss://elsewhere.example.com/v1", list))
    }

    @Test fun aGatewayUrlIsEncryptedOrOnThisPhone() {
        assertTrue(Keys.valid(Keys.Key.MODEL_URL, "https://gateway.example.com/v1/chat/completions"))
        assertTrue(Keys.valid(Keys.Key.MODEL_URL, "http://127.0.0.1:4000/v1"))
        assertFalse(Keys.valid(Keys.Key.MODEL_URL, "http://gateway.example.com/v1"))
        assertFalse(Keys.valid(Keys.Key.MODEL_URL, "https://x\"/v1"))
    }
}
