package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PanoramaTest {

    @Test
    fun stateFirstThenWhatSheWantsKnownThenTheRest() {
        val items = Panorama.items(
            Panorama.Now(
                stopped = true, back = "Te escribió Ana.", unread = listOf("Ana" to "¿Cenamos?", "Laura" to "Hola", "Ana" to "¿Sí?"),
                importantMail = Triple("Laura", "Contrato", "Léeme el correo"), mails = 3, next = "20:30" to "Reunión",
                briefing = "inflación" to "Baja al 3%", curious = "Aún no sé a qué te dedicas. ¿Me cuentas?",
            ),
        )
        assertEquals(
            listOf(Panorama.Kind.STATE, Panorama.Kind.BACK, Panorama.Kind.MAIL, Panorama.Kind.MESSAGE, Panorama.Kind.AGENDA, Panorama.Kind.NEWS, Panorama.Kind.CURIOUS),
            items.map { it.kind },
        )
        assertEquals("3 mensajes nuevos, de Ana, Laura", items[3].text)
        assertEquals(Place.ACTIVITY, items[3].place)
        assertEquals("Léeme el correo", items[2].ask)
    }

    @Test
    fun calmOrArranging() {
        assertEquals(listOf("Todo en calma. Toca el círculo si me necesitas."), Panorama.items(Panorama.Now()).map { it.text })
        assertEquals(1, Panorama.items(Panorama.Now(arranging = true, unread = listOf("Ana" to "x"))).size)
        assertEquals("Un correo sin leer.", Panorama.items(Panorama.Now(mails = 1)).single().text)
    }

    @Test
    fun curiousNeverAboutHealth() {
        assertEquals("Aún no sé a qué te dedicas. ¿Me cuentas?", Panorama.curious { if (it == Memory.Area.WORK || it == Memory.Area.HEALTH) Memory.Depth.NONE else Memory.Depth.SOME })
        assertEquals(null, Panorama.curious { if (it == Memory.Area.HEALTH) Memory.Depth.NONE else Memory.Depth.WELL })
    }
}
