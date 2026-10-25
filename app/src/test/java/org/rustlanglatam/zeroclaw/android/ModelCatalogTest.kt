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

    @Test fun aChosenProviderBringsItsModelAndOnlyTheKeyIsLeftEmpty() {
        // The recommended model when the provider lists it or there is no list; else the list's first.
        assertEquals("b", ModelCatalog.pick("b", listOf("a", "b")))
        assertEquals("a", ModelCatalog.pick("gone", listOf("a", "b")))
        assertEquals("b", ModelCatalog.pick("b", null))
        assertNull(ModelCatalog.pick(null, emptyList()))
        // Each group in use needs its model (or voice) and key; a custom gateway its URL; an unused group nothing.
        val chosen = mapOf(Keys.Key.MODEL_PROVIDER to "p", Keys.Key.MODEL to "m", Keys.Key.VISION_PROVIDER to "v")
        assertEquals(listOf(Keys.Key.MODEL, Keys.Key.MODEL_KEY, Keys.Key.VISION_MODEL, Keys.Key.VISION_KEY), Connectors.required { chosen[it].orEmpty() })
        assertTrue(Keys.Key.MODEL_URL in Connectors.required { if (it == Keys.Key.MODEL_PROVIDER) Keys.CUSTOM else "" })
        assertEquals(emptyList<Keys.Key>(), Connectors.required { "" })
    }

    @Test fun aProvidersModelListIsReadInBothShapes() {
        assertEquals(setOf("a-1", "b-2"), KeyCheck.ids("""{"object":"list","data":[{"id":"a-1"},{"id":"b-2"}]}"""))
        assertEquals(setOf("g-3-flash"), KeyCheck.ids("""{"models":[{"name":"models/g-3-flash"}]}"""))
        assertEquals(emptySet<String>(), KeyCheck.ids("not json"))
    }
}
