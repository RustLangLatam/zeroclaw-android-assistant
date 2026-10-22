package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class MemoryTest {

    private lateinit var file: File

    @Before
    fun clean() {
        file = File.createTempFile("memory", ".json").apply { delete() }
        Memory.load(file)
        Memory.learning = true
    }

    @Test
    fun factsAndInferencesStayApart() {
        Memory.remember("Le gusta el jazz por la mañana", confirmed = true)
        Memory.remember("Suele poner la alarma a las 7", confirmed = false)
        val told = Memory.describe()
        assertTrue(told, told.startsWith("What the owner told you:\nTus gustos:\n- Le gusta el jazz por la mañana"))
        assertTrue(told, "What you inferred (not confirmed; ask before relying on it):\nTu día:\n- Suele poner la alarma a las 7" in told)
    }

    @Test
    fun oldMemoriesFadeUntilHeardAgainButDatedDaysDoNot() {
        val day = 86_400_000L
        Memory.remember("Trabaja en un banco", confirmed = true, now = 0)
        Memory.remember("Cumpleaños de Ana", confirmed = true, now = 0, on = "05-12")
        val later = (Memory.FADE_DAYS + 1) * day
        val old = Memory.describe(later)
        assertTrue(old, old.substringAfter("Unsure now").contains("Trabaja en un banco"))
        assertTrue(old, old.substringBefore("Unsure now").contains("Cumpleaños de Ana"))
        Memory.remember("Trabaja en un banco", confirmed = true, now = later)
        assertTrue(!Memory.describe(later).contains("Unsure now"))
    }

    @Test
    fun theSameThingTwiceIsOneMemoryAndAConfirmationSticks() {
        Memory.remember("Suele poner la alarma a las 7", confirmed = false)
        Memory.remember("suele poner la alarma a las 7.", confirmed = true)
        assertEquals(1, Memory.items.size)
        assertTrue(Memory.items.single().confirmed)
    }

    @Test
    fun itSurvivesARestartAndCanBeForgotten() {
        Memory.remember("Trabaja desde casa los viernes", confirmed = true)
        Memory.load(file)
        assertEquals("Trabaja desde casa los viernes", Memory.items.single().text)
        assertEquals(1, Memory.forget("viernes"))
        Memory.load(file)
        assertTrue(Memory.items.isEmpty())
    }

    @Test
    fun forgettingNeedsEnoughWordsAndNamesFew() {
        listOf("Le gusta el café", "Le gusta el té", "Le gusta la playa", "Le gusta el cine", "Vive en Madrid").forEach { Memory.remember(it, confirmed = true) }
        var dropped = 0
        Memory.onDrop = { dropped++ }
        assertEquals(0, Memory.forget("el")) // too short: matches nearly everything
        assertEquals(0, Memory.forget("le gusta")) // matches four memories: too ambiguous
        assertEquals(1, Memory.forget("le gusta el te")) // exact match
        assertEquals(1, Memory.forget("madrid"))
        assertEquals(2, dropped)
        assertEquals(3, Memory.items.size)
        Memory.onDrop = {}
    }

    @Test
    fun pausedLearningSavesNothing() {
        Memory.pause(true)
        assertEquals("The owner paused learning: nothing was saved.", Memory.remember("Le gusta el té", confirmed = true))
        assertTrue(Memory.items.isEmpty())
    }

    @Test
    fun eachMemoryHasAnArea() {
        assertEquals(Memory.Area.TASTES, Memory.Area.of("Le gusta el jazz por la mañana"))
        assertEquals(Memory.Area.WORK, Memory.Area.of("Trabaja con Laura en el proyecto del banco"))
        assertEquals(Memory.Area.INTERESTS, Memory.Area.of("Sigue la política de Chile"))
        assertEquals(Memory.Area.PERSONAL, Memory.Area.of("Es zurdo"))
        Memory.remember("Es zurdo", confirmed = true, area = Memory.Area.PERSONAL)
        Memory.load(file)
        assertEquals(Memory.Area.PERSONAL, Memory.items.single().area)
    }

    @Test
    fun howWellSheKnowsTheOwner() {
        assertEquals(Memory.Depth.NONE, Memory.depth(Memory.Area.TASTES))
        Memory.remember("Le gusta el jazz", confirmed = true)
        Memory.remember("Le encanta el café sin azúcar", confirmed = true)
        assertEquals(Memory.Depth.LITTLE, Memory.depth(Memory.Area.TASTES))
        Memory.remember("Su película favorita es Amélie", confirmed = true)
        assertEquals(Memory.Depth.SOME, Memory.depth(Memory.Area.TASTES))
        val profile = Memory.profile()
        assertTrue(profile, "- Tus gustos: algo (3 told, 0 inferred)" in profile)
        assertTrue(profile, "You know little about: Sobre ti" in profile)
        assertTrue(profile, "Tu salud" !in profile.substringAfter("You know little about"))
    }

    @Test
    fun keptInZeroClawByCategory() {
        val told = Memory.Item(5, "Le encanta el jazz", true, 5, Memory.Area.TASTES)
        assertEquals("profile:tastes:told", Memory.category(told))
        assertEquals(told, Memory.fromStore("profile-5", "Le encanta el jazz", "profile:tastes:told", 5))
        val guess = Memory.fromStore("profile-7", "Suele trabajar tarde", "profile:routines:inferred", 7)
        assertEquals(false, guess.confirmed)
        assertEquals(Memory.Area.ROUTINES, guess.area)
        // A memory stored by the agent itself is a conversation memory, counted like an inference.
        val talk = Memory.fromStore("pending_call", "Quedó en llamar a Laura el lunes", "conversation", 9)
        assertEquals(true, talk.conversation)
        assertEquals("pending_call", talk.key)
        assertEquals(Memory.Area.WORK, talk.area)
    }
}
