package org.rustlanglatam.zeroclaw.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageTest {
    @After fun spanishAgain() { Language.chosen = null }

    @Test fun thePhonesUntilTheOwnerChooses() {
        val phone = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.ENGLISH)
            assertEquals(Lang.EN, Language.current)
            java.util.Locale.setDefault(java.util.Locale.FRENCH)
            assertEquals(Lang.ES, Language.current) // unsupported locale: falls back to Spanish
            Language.chosen = "es"
            java.util.Locale.setDefault(java.util.Locale.ENGLISH)
            assertEquals(Lang.ES, Language.current)
        } finally { java.util.Locale.setDefault(phone) }
    }

    @Test fun aChoiceWins() {
        Language.chosen = "es"
        assertEquals("Ajustes", t(R.string.app_settings))
        Language.chosen = "en"
        assertEquals(Lang.EN, Language.current)
        assertEquals("Settings", t(R.string.app_settings))
    }

    @Test fun arraysAndAFixedLanguage() {
        Language.chosen = "es"
        assertEquals("Lucía", ta(R.array.voice_names).first())
        assertEquals("Hi, I'm Ksandra, Milton's assistant. Ok", t(Lang.EN, R.string.inbox_intro, "Ksandra", "Milton", "Ok"))
        Language.chosen = "en"
        assertEquals("Emma", ta(R.array.voice_names).first())
        assertEquals(" (unverified)", Identity.UNVERIFIED)
        assertEquals("Ana", Identity.bare("Ana (sin verificar)"))
    }

    @Test fun choicesInTheOwnersWords() {
        assertEquals("en", Language.named("háblame en inglés"))
        assertEquals("en", Language.named("English"))
        assertEquals("es", Language.named("en español, por favor"))
        assertEquals(Language.PHONE, Language.named("el del teléfono"))
        assertNull(Language.named("francés"))
    }

    @Test fun theChoiceIsSaved() {
        val state = Saved.State(Policy.DEFAULT_RULES, false, 30, language = "en")
        assertEquals("en", Saved.decode(Saved.encode(state)).language)
    }
}

class EnglishWordsTest {
    @Test fun commandsInEnglishToo() {
        assertEquals(Command.CONTROL, Commands.of("open settings"))
        assertEquals(Command.MEMORY, Commands.of("show me what you know about me"))
        assertEquals(Command.CLOSE, Commands.of("close the menu"))
        assertEquals(Command.APP_MODE, Commands.of("switch to app mode"))
        assertEquals(Command.ASSISTANT_MODE, Commands.of("assistant mode"))
        assertEquals(Command.WIDGETS, Commands.of("edit my widgets"))
        assertEquals(Command.PAUSE, Commands.of("pause"))
        assertNull(Commands.of("what do I have today?"))
        assertEquals("Milton", Commands.nameFrom("my name is Milton"))
    }

    @Test fun theWordsSheRecognisesInEnglish() {
        assertEquals(Memory.Area.PEOPLE, Memory.Area.of("His sister Ana has a dog"))
        assertEquals(Memory.Area.MOOD, Memory.Area.of("Feels exhausted and stressed"))
        assertEquals(listOf(Empathy.Style.Cue.SHORTER), Empathy.Style.learn("too long, keep it short").also { Empathy.Style.reset() })
        val (important, why) = Mailbox.importance(null, "Invoice due tomorrow", "", emptyMap())
        assertEquals(true, important)
        assertEquals("habla de «invoice»", why)
    }
}
