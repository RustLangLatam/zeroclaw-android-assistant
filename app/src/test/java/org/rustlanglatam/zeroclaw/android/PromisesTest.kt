package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromisesTest {
    @Test fun anActionSaidDone() {
        assertTrue(Promises.claims("Hecho, Milton: en un minuto te llamo para que llames al médico."))
        assertTrue(Promises.claims("Listo, te lo anoté para mañana a las 9."))
        assertTrue(Promises.claims("Ya se lo envié a Laura."))
        assertTrue(Promises.claims("Done: I'll remind you at 9."))
        assertTrue(Promises.claims("Reminder set for tomorrow."))
        assertTrue(Promises.claims("Envío un mensaje a Laura diciendo que llego tarde."))
        // Claims of having shown or opened something also count.
        assertTrue(Promises.claims("Ahí tienes los ajustes de voz."))
        assertTrue(Promises.claims("Te abro tus conectores."))
        assertTrue(Promises.claims("Ahí los tienes."))
        assertTrue(Promises.claims("Here you go: your voice settings."))
    }

    @Test fun conversationIsNot() {
        assertFalse(Promises.claims("Hoy hace 15 grados y puede llover por la tarde."))
        assertFalse(Promises.claims("¿Quieres que te lo recuerde mañana?"))
        assertFalse(Promises.claims("It is sunny today."))
    }

    @Test fun theNoteIsTheApps() {
        assertTrue(Promises.note("Milton", "Hecho").startsWith(Phrases.NOTE))
    }
}
