package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelsTest {

    @Test
    fun actionsReadAsTheOwnerWouldSayThem() {
        fun said(act: Act) = act.title to act.detail
        assertEquals("Responder a Laura" to "Está ocupado a las 10.", said(Act.reply(3, "Laura", null, "Está ocupado a las 10.")))
        assertEquals("Responder a Ana por WhatsApp" to "Sí: a las 9", said(Act.reply(4, "Ana", "WhatsApp", "Sí: a las 9")))
        assertEquals("Enviar un mensaje a Ana" to "Llego tarde", said(Act.message("Ana", "Llego tarde")))
        assertEquals("Poner una alarma a las 07:00" to null, said(Act.alarm(7, 0)))
        assertEquals("Decirle a Laura si estás libre a las 10:00" to null, said(Act.availability(10, "Laura")))
        // The activity log keeps a separate technical text, never shown to the owner.
        assertEquals("reply #4 to Ana via WhatsApp: Sí: a las 9", Act.reply(4, "Ana", "WhatsApp", "Sí: a las 9").log)
        assertEquals(4, Act.reply(4, "Ana", "WhatsApp", "Sí").ref)
    }

    @Test
    fun rulesReadAsTitleAndDetail() {
        val rule = Rule(Principal.THIRD_PARTY, Capability.NOTIFICATION_REPLY, Scope.group("family"), Autonomy.ACT_NOTIFY, perDay = 5)
        assertEquals("Responder mensajes · familia" to "Hacerlo y avisarte · máx. 5 al día", Labels.rule(rule))
    }
}

class DaylightTest {

    @Test
    fun theNightIsSofterThanTheDay() {
        val noon = Daylight.tune(Palettes.dusk, 12)
        val night = Daylight.tune(Palettes.dusk, 23)
        org.junit.Assert.assertTrue(night.lights.first().alpha < noon.lights.first().alpha)
        org.junit.Assert.assertTrue(night.glow < noon.glow)
        org.junit.Assert.assertTrue(night.void.red <= noon.void.red)
        // The light palette dims its cream background at night.
        org.junit.Assert.assertTrue(Daylight.tune(Palettes.coral, 23).void.red < Daylight.tune(Palettes.coral, 12).void.red)
        assertEquals(Palettes.dusk.key, night.key)
    }

    @Test
    fun settingsAndGroupsInTheOwnersWords() {
        fun said(act: Act) = act.title to act.detail
        assertEquals("Poner a Pat en tu familia" to null, said(Act.group("Pat", "family")))
        assertEquals("Sacar a Laura de tus grupos" to null, said(Act.group("Laura", "none")))
        assertEquals("Cambiar cómo te hablo" to "cercana", said(Act.setting(Preferences.Setting.WARMTH, "cercana")))
        assertEquals("Cambiar el brillo" to "50%", said(Act.device(Device.Setting.BRIGHTNESS, "50%")))
    }
}
