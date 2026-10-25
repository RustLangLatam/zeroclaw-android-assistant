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

    @Test fun aGatewayUrlIsEncryptedOrOnThisPhone() {
        assertTrue(Keys.valid(Keys.Key.MODEL_URL, "https://gateway.example.com/v1/chat/completions"))
        assertTrue(Keys.valid(Keys.Key.MODEL_URL, "http://127.0.0.1:4000/v1"))
        assertFalse(Keys.valid(Keys.Key.MODEL_URL, "http://gateway.example.com/v1"))
        assertFalse(Keys.valid(Keys.Key.MODEL_URL, "https://x\"/v1"))
    }
}
