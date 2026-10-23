package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamTest {

    private fun scam(text: String, knows: Boolean = false) = Scam.looks(Scam.signs(text, knows))

    @Test
    fun scamsInSpanishAreSpotted() {
        assertTrue(scam("Tu paquete está retenido en aduana. Paga la tasa de 1,99 € aquí: https://correos-envio.xyz/pago"))
        assertTrue(scam("Tu cuenta será bloqueada en 24 horas. Verifica tus datos en www.banco-seguro.top"))
        assertTrue(scam("BBVA: hemos detectado un acceso. Confirma tus datos bancarios llamando al +34 911 234 567"))
        assertTrue(scam("Hola mamá, se me rompió el móvil. Hazme un bizum urgente de 300 euros a este número: 612 345 678"))
        assertTrue(scam("Has ganado un premio de 500 €. Reclámalo ahora en bit.ly/premio-ya"))
        assertTrue(scam("Tu multa de tráfico caduca hoy mismo. Paga en https://dgt-multas.info"))
        assertTrue(scam("Te envié un código por SMS, dímelo para verificar tu cuenta: https://wa-verify.click"))
        assertTrue(scam("Último aviso: su suscripción se cancela. Actualice su tarjeta en netflix-pago.com"))
    }

    @Test
    fun scamsInEnglishAreSpotted() {
        assertTrue(scam("Your parcel is on hold. Pay the customs fee here: https://ups-delivery.top/fee"))
        assertTrue(scam("Your account has been locked. Verify your password immediately at secure-login.site"))
        assertTrue(scam("Final notice: your debt must be paid today. Call +1 202 555 0147"))
        assertTrue(scam("Congratulations, you won a gift card! Claim it now: tinyurl.com/claimgift"))
        assertTrue(scam("Apple ID suspended. Log in within 24 hours: www.appleid-check.online"))
        assertTrue(scam("Hi mom, new phone. Can you send me 200 dollars right now? It's urgent, pay here: paypal-me.link/x", knows = true))
    }

    @Test
    fun ordinaryMessagesAreNot() {
        assertFalse(scam("¿Seguimos con la cena de esta noche?", knows = true))
        assertFalse(scam("Te paso el enlace de las fotos: https://photos.example.com/album", knows = true))
        assertFalse(scam("Can we move the meeting to 12? Call me at +34 600 000 001 if needed", knows = true))
        assertFalse(scam("Rebajas: 30% en toda la tienda, 19,99 € en zapatillas. https://tienda.example.com"))
        assertFalse(scam("Your order has shipped. Track it at https://shop.example.com/track"))
        assertFalse(scam("Te debo 20 euros de la comida, te los paso mañana", knows = true))
    }
}
