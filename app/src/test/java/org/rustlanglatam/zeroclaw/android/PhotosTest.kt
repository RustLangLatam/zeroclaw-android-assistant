package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PhotosTest {

    private val today = LocalDate.of(2026, 10, 17)

    @Test
    fun decemberOfLastYear() {
        assertEquals(LocalDate.of(2025, 12, 1) to LocalDate.of(2025, 12, 31), Photos.range(12, null, 1, null, null, today))
        assertEquals(LocalDate.of(2024, 1, 1) to LocalDate.of(2024, 12, 31), Photos.range(null, 2024, null, null, null, today))
        assertEquals(LocalDate.of(2026, 7, 1) to LocalDate.of(2026, 7, 10), Photos.range(null, null, null, "2026-07-01", "2026-07-10", today))
    }

    @Test
    fun whatTheOwnerAsksForAsLabels() {
        assertEquals(setOf("Dog", "Cat", "Bird", "Pet"), Photos.labels("mascota"))
        assertEquals(setOf("Beach", "Sea", "Surf", "Dog"), Photos.labels("playa, perro"))
        assertEquals(setOf("Guitar"), Photos.labels("guitar"))
    }

    @Test
    fun withMyPetMeansAPetAndSomeoneInThePhoto() {
        val dogAlone = Photos.Photo(1, "u1", 0, setOf("Dog", "Grass"), faces = 0)
        val dogAndMe = Photos.Photo(2, "u2", 0, setOf("Dog", "Smile"), faces = 1)
        val pet = Photos.labels("mascota")
        assertTrue(Photos.matches(dogAndMe, pet, withPeople = true))
        assertFalse(Photos.matches(dogAlone, pet, withPeople = true))
        assertTrue(Photos.matches(dogAlone, pet, withPeople = false))
    }
}
