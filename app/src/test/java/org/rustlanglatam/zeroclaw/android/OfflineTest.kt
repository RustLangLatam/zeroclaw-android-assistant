package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTest {
    @Test fun theOwnerAsksInTheirWords() {
        assertEquals(Preferences.Setting.OFFLINE, Preferences.Setting.named("sin internet"))
        assertEquals(Preferences.Setting.OFFLINE, Preferences.Setting.named("offline"))
        assertEquals(Preferences.Setting.OFFLINE, Preferences.Setting.named("modelo local"))
    }

    @Test fun toldBeforeAnythingDownloads() {
        assertTrue(Offline.explanation.contains("1,1 GB"))
        assertTrue(Offline.explanation.contains("Wi-Fi"))
        Offline.progress = 0.42f
        try { assertEquals("Descargando · 42 %", Offline.status) } finally { Offline.progress = null }
        assertEquals("Apagado", Offline.status)
    }

    @Test fun theChoiceIsSaved() {
        val state = Saved.State(Policy.DEFAULT_RULES, false, 30, offline = true)
        assertTrue(Saved.decode(Saved.encode(state)).offline)
    }

    @Test fun checksTheFile() {
        val f = java.io.File.createTempFile("model", ".gguf").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Offline.sha256(f))
    }

    @Test fun theSmallModelSeesOnlyItsTools() {
        val (_, body) = PhoneMcp.rpc(Principal.OWNER, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", Offline.TOOLS.toSet()) { _, _ -> org.json.JSONObject() }
        val names = org.json.JSONObject(body).getJSONObject("result").getJSONArray("tools").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
        assertEquals(Offline.TOOLS.toSet(), names.toSet())
    }

    @Test fun aTimeAsAModelWritesIt() {
        assertEquals(java.time.LocalTime.of(21, 0), Reminders.clock("21:00"))
        assertEquals(java.time.LocalTime.of(20, 0), Reminders.clock("20:00', "))
        assertEquals(java.time.LocalTime.of(21, 0), Reminders.clock("9 pm"))
        assertEquals(java.time.LocalTime.of(9, 30), Reminders.clock("9:30"))
        assertEquals(null, Reminders.clock("luego"))
    }

    @Test fun aCallWrittenAsText() {
        assertTrue(Offline.rawCall("{\"name\": \"offline__list_events\", \"arguments\": {\"days_from_today\": 0}}"))
        assertEquals(false, Offline.rawCall("Hoy tienes una reunión con Laura a las 20:30."))
        assertTrue(Offline.toSmall("¿Qué tengo hoy?").endsWith(" /no_think"))
    }

    @Test fun theOwnersTimeWins() {
        fun t(h: Int, m: Int) = java.time.LocalTime.of(h, m)
        assertEquals(t(22, 0), Reminders.spoken("Recuérdame a las 10 de la noche sacar la basura"))
        assertEquals(t(7, 30), Reminders.spoken("Pon una alarma a las 7 y media de la mañana"))
        assertEquals(t(8, 45), Reminders.spoken("a las 9 menos cuarto"))
        assertEquals(t(16, 0), Reminders.spoken("a las 4 de la tarde"))
        assertEquals(t(21, 30), Reminders.spoken("at 9:30 pm"))
        assertEquals(t(21, 30), Reminders.spoken("a las 21:30"))
        assertEquals(null, Reminders.spoken("Recuérdame en 10 minutos tomar agua"))
    }

    @Test fun aReminderOrAnAlarmInSoManyWords() {
        val (tool, args) = Reminders.asked("Recuérdame a las 10 de la noche sacar la basura")!!
        assertEquals("remind", tool); assertEquals("Sacar la basura", args.getString("text")); assertEquals("22:00", args.getString("time"))
        val pill = Reminders.asked("Recuérdame en 5 minutos tomar la pastilla de la tensión")!!.second
        assertEquals("Tomar la pastilla de la tensión", pill.getString("text")); assertEquals(5L, pill.getLong("minutes_from_now"))
        val tomorrow = Reminders.asked("Ksandra, recuérdame mañana a las 8 de la mañana llamar a mamá")!!.second
        assertEquals("Llamar a mamá", tomorrow.getString("text")); assertEquals("08:00", tomorrow.getString("time")); assertEquals(1, tomorrow.getInt("days_from_today"))
        val (alarm, at) = Reminders.asked("Pon una alarma a las 7 y media de la mañana")!!
        assertEquals("create_alarm", alarm); assertEquals(7, at.getInt("hour")); assertEquals(30, at.getInt("minute"))
        assertEquals(null, Reminders.asked("Recuérdame llamar a mamá"))
        assertEquals(null, Reminders.asked("Cuéntame un chiste"))
    }

    @Test fun theCalendarAndRemindersReadByTheApp() {
        assertEquals("list_events" to 0, Reminders.asked("¿Qué tengo hoy?")!!.let { it.first to it.second.getInt("days_from_today") })
        assertEquals(1, Reminders.asked("Qué tengo mañana")!!.second.getInt("days_from_today"))
        assertEquals("list_reminders", Reminders.asked("¿Qué recordatorios tengo?")!!.first)
        assertEquals("youtube", Reminders.asked("Abre YouTube")!!.second.getString("app"))
        assertEquals("Hoy tienes Reunión con Laura de 20:30 a 21:30 y Cena con Ana de 22:00 a 23:30.",
            Offline.agenda("2026-10-18 (today):\n20:30-21:30 Reunión con Laura\n22:00-23:30 Cena con Ana", 0))
        assertEquals("Mañana no tienes nada en la agenda.", Offline.agenda("2026-10-19 (tomorrow):\nNo events on 2026-10-19.", 1))
        val today = java.time.LocalDate.now()
        assertEquals("Tienes 1 recordatorio: Tomar la pastilla hoy a las 17:13.", Offline.reminders("#1 $today 17:13 [high] Tomar la pastilla"))
    }
}
