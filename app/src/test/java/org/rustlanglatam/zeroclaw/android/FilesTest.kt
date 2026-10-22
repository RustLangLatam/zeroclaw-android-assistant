package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Test

class FilesTest {

    @Test
    fun theWordsThatMatter() {
        assertEquals(listOf("presupuesto", "credito"), Files.terms("Ksandra, ¿dónde está el documento del presupuesto de crédito?"))
    }

    @Test
    fun namesMatchWithoutAccentsOrSeparators() {
        val terms = listOf("presupuesto", "credito")
        assertEquals(2, Files.score("Presupuesto_Crédito_Hipotecario.pdf", terms))
        assertEquals(1, Files.score("presupuestos-2025.xlsx", terms))
        assertEquals(0, Files.score("factura_luz.pdf", terms))
    }

    @Test
    fun whereEachFileCameFrom() {
        assertEquals("WhatsApp", Files.source("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/"))
        assertEquals("Descargas", Files.source("Download/"))
    }

    @Test
    fun theBestAndNewestFirst() {
        val old = Files.Hit("a", "Descargas", null, null, modified = 1, score = 2)
        val new = Files.Hit("b", "WhatsApp", null, null, modified = 2, score = 2)
        val weak = Files.Hit("c", "Correo", null, null, modified = 3, score = 1)
        assertEquals(listOf(new, old, weak), Files.rank(listOf(weak, old, new, Files.Hit("d", "x", null, null, 9, 0))))
    }
}
