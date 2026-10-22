package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTalkTest {

    @Test
    fun whichAlarmComesNextIsReadByTheApp() {
        listOf("¿Cuál es mi próxima alarma?", "a qué hora es mi siguiente alarma", "¿tengo alguna alarma?", "when is my next alarm").forEach {
            assertEquals(it, "next_alarm", Reminders.asked(it)?.first)
        }
        assertEquals("create_alarm", Reminders.asked("pon una alarma a las 7")?.first)
        assertEquals(null, Reminders.asked("apaga la alarma de las 7")?.first)
    }

    @Test
    fun theNextAlarmSaid() {
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        assertEquals("Tu próxima alarma es mañana a las 06:15.", Offline.nextAlarm("The owner's next alarm: 06:15 on $tomorrow (monday)."))
        assertEquals("No tienes ninguna alarma pendiente. También te puse: 07:30.",
            Offline.nextAlarm("Android shows no pending alarm. Alarms you set for the owner (and did not turn off): 07:30."))
    }

    @Test
    fun whatWasSaidOfflineReachesTheOwnersAgentOnce() {
        val sent = OwnerChat.withOffline("¿y la alarma?", listOf("Owner: ¿cuál es mi próxima alarma?", "You: Tu próxima alarma es a las 05:00."))
        assertTrue("You: Tu próxima alarma es a las 05:00." in sent)
        // Once stored and shown again, only the owner's own words remain.
        assertEquals("¿y la alarma?", OwnerChat.said("[CURRENT DATE & TIME: 2026-10-19 02:15] $sent"))
        assertEquals("hola", OwnerChat.withOffline("hola", emptyList()))
    }
}
