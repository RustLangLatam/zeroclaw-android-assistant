package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideosTest {

    @Test
    fun videosBecomeOptionsOfACard() {
        val search = """{"items":[
            {"id":{"videoId":"abc"},"snippet":{"title":"Pollo al horno &quot;fácil&quot; [receta]","channelTitle":"Cocina &amp; más"}},
            {"id":{"kind":"youtube#channel"},"snippet":{"title":"A channel"}}]}"""
        val found = Videos.parse(search)
        assertEquals(listOf(Videos.Video("abc", "Pollo al horno \"fácil\" [receta]", "Cocina & más")), found)
        assertEquals(mapOf("abc" to 12), Videos.minutes("""{"items":[{"id":"abc","contentDetails":{"duration":"PT12M3S"}}]}"""))
        val lines = Videos.describe("pollo al horno", found.map { it.copy(minutes = 12) })
        assertTrue(lines.endsWith("- [Pollo al horno \"fácil\" (receta)](https://www.youtube.com/watch?v=abc) · Cocina & más · 12 min"))
        // Each line is a card option whose link opens the video.
        assertEquals("https://www.youtube.com/watch?v=abc", Cards.link(lines.lines().last()))
    }

    @Test
    fun withoutResultsTheSearchItself() {
        assertEquals("https://www.youtube.com/results?search_query=pollo+al+horno", Cards.link(Videos.fallback("pollo al horno")))
        val said = Videos.describe("pollo al horno", emptyList())
        assertEquals("https://www.youtube.com/results?search_query=pollo+al+horno", Cards.link(said))
    }
}
