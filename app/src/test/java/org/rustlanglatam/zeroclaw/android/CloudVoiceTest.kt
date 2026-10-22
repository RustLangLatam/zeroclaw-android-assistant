package org.rustlanglatam.zeroclaw.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CloudVoiceTest {
    @Test
    fun herNaturalVoiceOnlyWhenSetChosenOnlineAndUnderTheCeiling() {
        val cloud = CloudVoice.NAME
        assertTrue(CloudVoice.usable(cloud, online = true, spentUsd = 4.99, ceilingUsd = 5.0, set = true))
        assertFalse(CloudVoice.usable(cloud, online = true, spentUsd = 5.0, ceilingUsd = 5.0, set = true))
        assertTrue(CloudVoice.usable(cloud, online = true, spentUsd = 50.0, ceilingUsd = 0.0, set = true))
        assertFalse(CloudVoice.usable(cloud, online = false, spentUsd = 0.0, ceilingUsd = 5.0, set = true))
        assertFalse(CloudVoice.usable("es-us-x-sfb-local", online = true, spentUsd = 0.0, ceilingUsd = 5.0, set = true))
        assertFalse(CloudVoice.usable(cloud, online = true, spentUsd = 0.0, ceilingUsd = 5.0, set = false))
    }

    @Test
    fun sheIsReadNotAnswered() {
        val style = CloudVoice.style("Spanish", Warmth.CLOSE)
        assertTrue("word for word" in style && "Never answer" in style && "español neutro" in style && "trill" in style && "never an English accent" in style && "in Spanish" in style)
        assertFalse("español neutro" in CloudVoice.style("English", Warmth.QUIET))
        val request = JSONObject(CloudVoice.request("Hola, ¿cómo andás?", style)).getJSONObject("response")
        // Out-of-band response: no earlier conversation context is sent.
        assertEquals("none", request.getString("conversation"))
        assertEquals("audio", request.getJSONArray("output_modalities").getString(0))
        assertEquals("Hola, ¿cómo andás?", request.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
        val session = JSONObject(CloudVoice.session("v", style)).getJSONObject("session")
        assertEquals(24_000, session.getJSONObject("audio").getJSONObject("output").getJSONObject("format").getInt("rate"))
        assertEquals("v", session.getJSONObject("audio").getJSONObject("output").getString("voice"))
    }

    @Test
    fun herNameSpelledAsItSounds() {
        assertEquals("Hola, soy Kasandra. Kasandra te ayuda.", CloudVoice.spoken("Hola, soy Ksandra. Ksandra te ayuda."))
        assertEquals("Te escribe kasandra", CloudVoice.spoken("Te escribe ksandra"))
        // Other words starting with the same letters are left unchanged.
        assertEquals("Kstar y Kremlin", CloudVoice.spoken("Kstar y Kremlin"))
    }

    @Test
    fun keptSoundByWordsVoiceAndStyle() {
        val k = CloudVoice.key("Hola", "v", "s")
        assertEquals(k, CloudVoice.key(" Hola ", "v", "s"))
        assertNotEquals(k, CloudVoice.key("Hola", "w", "s"))
        assertNotEquals(k, CloudVoice.key("Hola", "v", "t"))
        assertEquals(64, k.length)
    }

    @Test
    fun theCostBySecondAndByMonth() {
        // A minute of 16-bit sound at 24 kHz.
        assertEquals(60.0, CloudVoice.seconds(2L * 24_000 * 60), 1e-9)
        assertEquals(0.025, CloudVoice.cost(60.0, 0.025), 1e-9)
        assertEquals(0.0, CloudVoice.cost(60.0, null), 1e-9)
        val oct = YearMonth.of(2026, 10)
        val m = CloudVoice.Month("2026-10", 30.0).plus(15.0, oct)
        assertEquals(45.0, m.current(oct), 1e-9)
        // A new month starts at zero.
        assertEquals(0.0, m.current(oct.plusMonths(1)), 1e-9)
        assertEquals(CloudVoice.Month("2026-11", 5.0), m.plus(5.0, oct.plusMonths(1)))
    }

    @Test
    fun thePresenceBeatsWithEachSyllable() {
        fun window(loud: Boolean) = ByteArray(2_400) { i -> if (loud && i % 2 == 1) 0x30 else 0 }
        val pcm = window(false) + window(true) + window(true) + window(false) + window(true)
        assertEquals(2 to true, CloudVoice.onsets(pcm, loud = false))
        // A loud chunk that continues a loud one is not a new onset.
        assertEquals(0 to true, CloudVoice.onsets(window(true), loud = true))
    }

    @Test
    fun theOwnerSetsItByVoice() {
        assertEquals(Preferences.Setting.VOICE_CEILING, Preferences.Setting.named("voice_ceiling"))
        assertEquals(Preferences.Setting.VOICE, Preferences.Setting.named("voice"))
        assertEquals(10.0, Preferences.ceiling("10 dólares")!!, 1e-9)
        assertEquals(2.5, Preferences.ceiling("US$ 2,50")!!, 1e-9)
        assertEquals(0.0, Preferences.ceiling("sin tope")!!, 1e-9)
        assertNull(Preferences.ceiling("mucho"))
        assertTrue(Preferences.natural("la voz natural"))
        assertTrue(Preferences.natural("la de internet"))
        assertFalse(Preferences.natural("Lucía"))
    }

    @Test
    fun theCeilingIsKept() {
        val state = Saved.State(Policy.DEFAULT_RULES, false, 30, voiceCeiling = 10.0)
        assertEquals(10.0, Saved.decode(Saved.encode(state)).voiceCeiling, 1e-9)
        assertEquals(Guard.DEFAULT_VOICE_CEILING, Saved.decode(Saved.encode(Saved.State(Policy.DEFAULT_RULES, false, 30))).voiceCeiling, 1e-9)
    }
}
