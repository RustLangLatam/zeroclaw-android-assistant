package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EmpathyTest {
    @Test fun threeShorterKeepHerShort() {
        Empathy.Style.reset()
        repeat(2) { Empathy.Style.learn("Más corto, por favor") }
        assertEquals("", Empathy.Style.describe())
        assertEquals(listOf(Empathy.Style.Cue.SHORTER), Empathy.Style.learn("muy largo, resúmelo"))
        assertTrue(Empathy.Style.describe().contains("short answers"))
        assertTrue(Empathy.Style.learn("¿qué hora es?").isEmpty())
        Empathy.Style.reset()
    }

    @Test fun laughsAndStraightTalk() {
        Empathy.Style.reset()
        repeat(3) { Empathy.Style.learn("jajaja, al grano") }
        val d = Empathy.Style.describe()
        assertTrue(d.contains("joke") && d.contains("straight"))
        Empathy.Style.reset()
    }

    @Test fun momentsTodayAndTomorrow() {
        val today = LocalDate.of(2026, 10, 17)
        val items = listOf(
            Memory.Item(1, "Cumpleaños de Ana", true, 0, on = "10-18"),
            Memory.Item(2, "Examen de Milton", true, 0, on = "2026-10-17"),
            Memory.Item(3, "Aniversario", true, 0, on = "2025-10-17"),
            Memory.Item(4, "Sin fecha", true, 0),
        )
        assertEquals(listOf(2L to "hoy", 1L to "mañana"), Empathy.Moments.upcoming(items, today).map { it.first.id to it.second }.sortedBy { it.second })
    }

    @Test fun daysAreChecked() {
        assertEquals("10-18", Empathy.Moments.day(" 10-18 "))
        assertEquals("2026-02-28", Empathy.Moments.day("2026-02-28"))
        assertEquals("02-29", Empathy.Moments.day("02-29"))
        assertNull(Empathy.Moments.day("13-01"))
        assertNull(Empathy.Moments.day("mañana"))
        assertNull(Empathy.Moments.day(null))
    }

    @Test fun reflectionWeeklyAndAtMostThree() {
        assertTrue(Empathy.Reflection.due(0, 7 * 86_400_000L))
        assertTrue(!Empathy.Reflection.due(1, 7 * 86_400_000L))
        val answer = "Aquí van:\n- Milton corre los domingos\n- Le preocupa el trabajo\n- Disfruta el café\n- Una cuarta de más\n- x"
        assertEquals(listOf("Milton corre los domingos", "Le preocupa el trabajo", "Disfruta el café"), Empathy.Reflection.conclusions(answer))
    }

    @Test fun datedMemoryKeepsItsDay() {
        val item = Memory.Item(5, "Cumpleaños de Laura", true, 0, on = "03-04")
        assertEquals("03-04", Memory.fromStore(item.key, item.text, Memory.category(item), 0).on)
    }
}

class FromAppTest {
    @Test fun everyMessageFromTheAppIsMarkedOnceSoItIsNeverShownAsTheOwners() {
        val welcome = Phrases.welcome("Milton", java.time.LocalDateTime.of(2026, 10, 18, 9, 0))
        assertEquals(welcome, Phrases.fromApp(welcome))
        val setup = Phrases.fromApp("(Milton just finished setting you up.)")
        assertTrue(setup.startsWith(Phrases.NOTE))
        assertEquals(setup, Phrases.fromApp(setup))
        assertTrue("Never remark on how often" in welcome)
        val stored = "[CURRENT DATE & TIME: 2026-10-18 01:40:33 +02:00]\n\n$welcome"
        assertTrue(OwnerChat.said(stored).startsWith(Phrases.NOTE))
        assertEquals("¿Qué hora es?", OwnerChat.said("[CURRENT DATE & TIME: 2026-10-18 01:40:33 +02:00]\n\n¿Qué hora es?"))
        assertEquals("Hola", OwnerChat.said("Hola"))
    }
}

class SkyTest {
    @Test fun rainComingIsToldOverTheSkyAndWordsFitTheWidget() {
        assertEquals("lluvia 70%", Widgets.forecast(2, 70))
        assertEquals("Parcial", Widgets.forecast(2, 20))
        assertEquals("Lluvia", Widgets.forecast(63, 90))
        assertEquals(Widgets.Sky.STORM, Widgets.kind(95))
        assertEquals(Widgets.Sky.RAIN, Widgets.kind(81))
        assertTrue((0..99).all { Widgets.sky(it).length <= 10 })
    }
}

class DigestTest {
    @Test fun whatCameInAndWhatHappenedSurvivesARestart() {
        val e = Inbox.Entry(7, "10:30", "Ana", "¿Comemos?", Inbox.Status.ANSWERED, "fake", 123L, "Ana")
        val (read, back) = Inbox.decode(Inbox.encode(listOf(e), 5))
        assertEquals(5, read)
        assertEquals(listOf(e).map { listOf(it.id, it.sender, it.text, it.status, it.time, it.conversation) },
            back.map { listOf(it.id, it.sender, it.text, it.status, it.time, it.conversation) })
    }

    @Test fun aDigestSavedWithLegacyStatusesLoadsWithTheCurrentOnes() {
        val saved = """{"read":0,"entries":[{"id":1,"at":"10:30","sender":"Ana","text":"Hola","status":"respondido"},""" +
            """{"id":2,"at":"10:31","sender":"Pat","text":"Hola","status":"retenido (en pausa)"}]}"""
        assertEquals(listOf(Inbox.Status.ANSWERED, Inbox.Status.HELD_BACK), Inbox.decode(saved).second.map { it.status })
    }
}
