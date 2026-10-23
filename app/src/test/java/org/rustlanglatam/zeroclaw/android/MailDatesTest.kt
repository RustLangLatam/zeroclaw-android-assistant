package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Bills and parcels found in ordinary emails (issue #20 asks for at least 80 % of 20). */
class MailDatesTest {

    private val today = LocalDate.of(2026, 10, 23)

    private val actionable = listOf(
        "Su factura de octubre\nImporte: 45,20 €. Fecha de cargo: 28/10/2026.",
        "Factura nº 4471 disponible\nTotal a pagar 129,90 EUR. Vence el 5 de noviembre de 2026.",
        "Recibo de agua\nEl recibo de 32,15 € se cargará en tu cuenta el 02/11/2026.",
        "Tu factura de internet\nTotal: 39,99 €. Fecha límite de pago: 30 de octubre.",
        "Aviso de pago\nTienes un recibo pendiente de 60,00 euros con vencimiento el 31/10/2026.",
        "Seguro del coche: renovación\nImporte 412,50 €. Vence el 15 de noviembre.",
        "Your invoice INV-2209\nAmount due: \$84.00. Due date: November 2, 2026.",
        "Electricity bill\nTotal due \$120.45, pay by 11/03/2026.",
        "Payment reminder\nYour bill of 59.99 EUR is due on October 29, 2026.",
        "Invoice from the studio supplier\nAmount due: 1.250,00 €. Payment due 10/11/2026.",
        "Tu pedido ha sido enviado\nTu paquete llega el jueves. Seguimiento: AB123456789ES.",
        "Pedido 112-447 en camino\nEntrega estimada: 27 de octubre.",
        "Tu envío llegará mañana\nEl repartidor entregará tu paquete mañana entre las 9 y las 14.",
        "Your order has shipped\nEstimated delivery: October 28, 2026.",
        "Out for delivery\nYour package arrives today... or tomorrow at the latest.",
        "Shipment update\nYour parcel will be delivered on Monday.",
        "Tu paquete está en reparto\nLlega el 26/10/2026 a tu dirección.",
        "Order 55-1209\nYour package arrives on November 1.",
        "Entrega programada\nTu pedido se entrega el sábado.",
        "Delivery scheduled\nYour shipment is arriving Friday.",
    )

    @Test
    fun atLeastFourInFiveAreFound() {
        val found = actionable.count { mail ->
            val r = Shared.read(mail, today, Inbox.english(mail))
            r.kind in setOf(Shared.Kind.BILL, Shared.Kind.PARCEL) && r.date != null && !r.date.isBefore(today)
        }
        assertTrue("found $found of ${actionable.size}", found >= 16)
    }

    @Test
    fun newslettersAndChatterAreNot() {
        listOf(
            "Novedades de otoño\nDescubre la nueva colección en nuestra web.",
            "Weekly digest\nHere are the top stories of the week.",
            "¿Comemos el viernes?\nAna",
        ).forEach { mail ->
            val r = Shared.read(mail, today, Inbox.english(mail))
            assertEquals(mail, false, r.kind in setOf(Shared.Kind.BILL, Shared.Kind.PARCEL) && r.date != null)
        }
    }
}
