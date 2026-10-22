package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Test

class CardsTest {

    @Test
    fun aCardReadsItsSmallMarkdown() {
        val body = """
            # Para Laura
            Hola Laura,
            te confirmo la reunión.

            - el martes
            * a las **10:00**
            1. Abre Ajustes
            2) Toca Cuentas
            > Enviado desde el teléfono
            ---
        """.trimIndent()
        assertEquals(
            listOf(
                Cards.Block.Heading("Para Laura"),
                Cards.Block.Paragraph("Hola Laura,\nte confirmo la reunión."),
                Cards.Block.Bullet("el martes"),
                Cards.Block.Bullet("a las **10:00**"),
                Cards.Block.Step(1, "Abre Ajustes"),
                Cards.Block.Step(2, "Toca Cuentas"),
                Cards.Block.Quote("Enviado desde el teléfono"),
                Cards.Block.Rule,
            ),
            Cards.blocks(body),
        )
    }

    @Test
    fun boldSpansAndButtons() {
        assertEquals(listOf("a las " to false, "10:00" to true, " en punto" to false), Cards.spans("a las **10:00** en punto"))
        assertEquals(listOf("Enviar", "Cambiar", "Otra"), Cards.show("Correo", "Hola", " Enviar | Cambiar||Otra|Cuarta".split("|")).actions)
        assertEquals("Correo", Cards.shown?.title)
    }

    @Test
    fun optionsOpenOnlyWebLinks() {
        val video = "[Pollo al horno **fácil**](https://www.youtube.com/watch?v=abc) · 12 min"
        assertEquals("https://www.youtube.com/watch?v=abc", Cards.link(video))
        assertEquals("Pollo al horno **fácil** · 12 min", Cards.plain(video))
        assertEquals(null, Cards.link("[Llamar](tel:5551234)"))
        assertEquals(null, Cards.link("[App](intent://x#Intent;end)"))
    }
}
