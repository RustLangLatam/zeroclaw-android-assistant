package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsTest {

    private val rss = """
        <rss><channel><title>"inflación" - Google Noticias</title>
        <item><title>La inflación baja al 3% en septiembre - Diario Ejemplo</title><link>https://news.example/a</link>
        <pubDate>Fri, 16 Oct 2026 10:00:00 GMT</pubDate><description>&lt;a href="x"&gt;texto&lt;/a&gt;</description>
        <source url="https://diario.example">Diario Ejemplo</source></item>
        <item><title><![CDATA[El Banco Central mantiene la tasa &amp; anuncia cambios]]></title><link>https://news.example/b</link>
        <pubDate>Fri, 16 Oct 2026 12:00:00 GMT</pubDate><source url="https://radio.example">Radio Ejemplo</source></item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun headlinesFromTheFeed() {
        val list = News.parse(rss)
        assertEquals(2, list.size)
        assertEquals(News.Headline("La inflación baja al 3% en septiembre", "Diario Ejemplo", "https://news.example/a", "Fri, 16 Oct 2026 10:00:00 GMT"), list[0])
        assertEquals("El Banco Central mantiene la tasa & anuncia cambios", list[1].title)
    }

    @Test
    fun onlyWhatIsNewIsTold() {
        val topic = News.Topic(1, "inflación")
        val (first, seen) = News.fresh(topic, News.parse(rss))
        assertEquals(2, first.size)
        val (again, _) = News.fresh(seen, News.parse(rss))
        assertTrue(again.isEmpty())
    }

    @Test
    fun theFeedAndWhatTheAgentIsAsked() {
        assertEquals("https://news.google.com/rss/search?q=inflaci%C3%B3n+Chile+when%3A2d&hl=es-419&gl=CL&ceid=CL:es-419", News.feed("inflación Chile", country = "CL"))
        org.junit.Assert.assertEquals("es" to "ES", News.edition("es"))
        org.junit.Assert.assertEquals("es-419" to "MX", News.edition("mx"))
        org.junit.Assert.assertEquals("es-419" to "US", News.edition(""))
        // In English the feed is in English, whatever the country.
        org.junit.Assert.assertEquals("en" to "ES", News.edition("es", Lang.EN))
        val ask = News.ask("Milton", "inflación", News.parse(rss))
        assertTrue(ask, "- La inflación baja al 3% en septiembre (Diario Ejemplo" in ask)
        assertTrue(ask, "Do not call any tool." in ask)
    }

    @Test
    fun savedAndDescribed() {
        val topics = listOf(News.Topic(1, "inflación", "08:00", null, listOf("a")), News.Topic(2, "Colo-Colo", null, 3))
        assertEquals(topics, News.decodeTopics(News.encodeTopics(topics)))
        assertEquals("#1 \"inflación\" every day at 08:00\n#2 \"Colo-Colo\" as it happens (checked every 3 h)", News.describe(topics))
        val briefings = listOf(News.Briefing("inflación", 5, "Baja la inflación.", News.parse(rss)))
        assertEquals(briefings, News.decodeBriefings(News.encodeBriefings(briefings)))
    }

    @Test
    fun onlyAnAnswerMarkedImportantIsToldAtOnce() {
        org.junit.Assert.assertEquals("Ganó el sí en el referéndum de hoy.", News.urgent("URGENT: Ganó el sí en el referéndum de hoy."))
        org.junit.Assert.assertEquals("Cerraron el aeropuerto por la tormenta.", News.urgent(" URGENT\nCerraron el aeropuerto por la tormenta."))
        org.junit.Assert.assertNull(News.urgent("NOTHING"))
        org.junit.Assert.assertNull(News.urgent("Nada importante hoy, URGENT: no"))
        org.junit.Assert.assertNull(News.urgent("URGENT"))
        org.junit.Assert.assertNull(News.urgent(null))
    }
}
