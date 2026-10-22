package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneMemoryTest {

    private val store = PhoneMemory.Pending.Op(PhoneMemory.Pending.STORE, "profile-1")
    private val forget = PhoneMemory.Pending.Op(PhoneMemory.Pending.FORGET, "profile-1")

    @Test
    fun whatWaitsForZeroClawKeepsTheLastOperationPerMemory() {
        val other = PhoneMemory.Pending.Op(PhoneMemory.Pending.STORE, "profile-2")
        val ops = listOf(store, other).let { PhoneMemory.Pending.add(it, forget) }
        assertEquals(listOf(other, forget), ops)
    }

    @Test
    fun anOperationIsALineAndAnUnknownLineIsSkipped() {
        assertEquals(store, PhoneMemory.Pending.parse(store.toString()))
        assertEquals(forget, PhoneMemory.Pending.parse("forget profile-1"))
        assertNull(PhoneMemory.Pending.parse("rename profile-1"))
        assertNull(PhoneMemory.Pending.parse("store"))
    }
}
