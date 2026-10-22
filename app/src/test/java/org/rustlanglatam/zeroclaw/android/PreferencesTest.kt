package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferencesTest {

    @Test
    fun settingsByName() {
        assertEquals(Preferences.Setting.WARMTH, Preferences.Setting.named("warmth"))
        assertEquals(Preferences.Setting.VOICE, Preferences.Setting.named("voz"))
        assertEquals(Preferences.Setting.PALETTE, Preferences.Setting.named("colores"))
        assertEquals(Preferences.Setting.READ_MAIL, Preferences.Setting.named("read_mail"))
        assertEquals(Preferences.Setting.WIDGET_ADD, Preferences.Setting.named("widget_add"))
        assertNull(Preferences.Setting.named("tostadora"))
    }

    @Test
    fun valuesInWords() {
        assertEquals(Warmth.CLOSE, Preferences.warmth("cercana"))
        assertEquals(Warmth.QUIET, Preferences.warmth("discreta"))
        val names = listOf("dusk" to "Amanecer", "lavender" to "Lavanda", "coral" to "Coral claro", "night" to "Noche")
        assertEquals("coral", Preferences.palette("coral claro", names))
        assertEquals("night", Preferences.palette("Noche", names))
        assertEquals(Widget.WEATHER, Preferences.widget("clima"))
        assertEquals(Widget.MAIL, Preferences.widget("correos"))
        assertEquals(Widget.BATTERY, Preferences.widget("batería"))
        assertNull(Preferences.widget("nada"))
    }

    @Test
    fun whereTheFloatingMarkGoes() {
        assertEquals(Preferences.Setting.FLOATING_PLACE, Preferences.Setting.named("floating_place"))
        assertEquals(false to 0.18f, Preferences.floatingPlace("a la izquierda, arriba"))
        assertEquals(true to null, Preferences.floatingPlace("derecha"))
        assertEquals(null to 0.72f, Preferences.floatingPlace("más abajo"))
        assertNull(Preferences.floatingPlace("donde quieras"))
    }
}
