package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SharedTest {

    private val today = LocalDate.of(2026, 10, 23)

    @Test
    fun aSpanishBillGivesItsDueDateAndAmount() {
        val text = """
            ENERGÍA DEL NORTE S.A.   Factura nº 2026-10-4471
            Fecha de emisión: 15/10/2026
            Periodo: 01/09/2026 - 30/09/2026
            Total a pagar: 45,20 €
            Fecha de cargo / vence: 28/10/2026
        """.trimIndent()
        assertEquals(Shared.Found(Shared.Kind.BILL, LocalDate.of(2026, 10, 28), null, "45,20 €"), Shared.read(text, today))
    }

    @Test
    fun anEnglishBillWithMonthNames() {
        val text = "Invoice #881. Amount due: \$129.99. Payment due by November 5, 2026. Thank you."
        assertEquals(Shared.Found(Shared.Kind.BILL, LocalDate.of(2026, 11, 5), null, "\$129.99"), Shared.read(text, today, english = true))
    }

    @Test
    fun aDoctorsAppointment() {
        val text = "Centro de Salud Ruzafa. Cita: Dra. Pérez, consulta 12. Jueves 30 de octubre a las 10:15. Traiga la tarjeta sanitaria."
        assertEquals(Shared.Found(Shared.Kind.APPOINTMENT, LocalDate.of(2026, 10, 30), LocalTime.of(10, 15), null), Shared.read(text, today))
    }

    @Test
    fun aSchoolCircularWithoutYear() {
        val found = Shared.read("Excursión al museo el 7 de noviembre. Traer almuerzo.", today)
        assertEquals(LocalDate.of(2026, 11, 7), found.date)
        assertEquals(Shared.Kind.OTHER, found.kind)
    }

    @Test
    fun aDateWithoutYearAlreadyPastIsNextYear() {
        assertEquals(LocalDate.of(2027, 2, 3), Shared.dates("hasta el 3 de febrero", today).single().second)
    }
}
