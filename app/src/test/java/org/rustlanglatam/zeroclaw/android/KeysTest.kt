package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysTest {

    @Test
    fun ordinaryValuesAreAccepted() {
        assertTrue(Keys.valid(Keys.Key.MODEL_PROVIDER, "openrouter"))
        assertTrue(Keys.valid(Keys.Key.MODEL, "some-model/v2:free"))
        assertTrue(Keys.valid(Keys.Key.MODEL_KEY, "sk-abc_123-XYZ.456"))
        assertTrue(Keys.valid(Keys.Key.SEARCH_KEY, "https://search.example.com"))
        assertTrue(Keys.valid(Keys.Key.VOICE_URL, "wss://voice.example.com/v1/realtime"))
        assertTrue(Keys.valid(Keys.Key.VOICE_PER_MINUTE, "0.06"))
        assertTrue(Keys.valid(Keys.Key.MODEL_KEY, ""))
    }

    @Test
    fun valuesThatCouldRewriteTheConfigAreRefused() {
        assertFalse(Keys.valid(Keys.Key.MODEL_KEY, "abc\"\nallowed_tools = [\"shell\"]"))
        assertFalse(Keys.valid(Keys.Key.MODEL, "model\\\""))
        assertFalse(Keys.valid(Keys.Key.MODEL_KEY, "abc\tdef"))
        assertFalse(Keys.valid(Keys.Key.MODEL_PROVIDER, "openai.main]\n[agents.owner"))
        assertFalse(Keys.valid(Keys.Key.SEARCH_PROVIDER, "brave key"))
    }

    @Test
    fun voiceSettingsKeepTheirShape() {
        assertFalse(Keys.valid(Keys.Key.VOICE_URL, "http://voice.example.com"))
        assertFalse(Keys.valid(Keys.Key.VOICE_PER_MINUTE, "-1"))
        assertFalse(Keys.valid(Keys.Key.VOICE_PER_MINUTE, "cheap"))
    }
}
