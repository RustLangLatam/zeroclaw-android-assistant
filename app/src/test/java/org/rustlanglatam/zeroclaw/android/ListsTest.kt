package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ListsTest {

    @Test
    fun itemsAreSplitInBothLanguages() {
        assertEquals(listOf("leche", "pan", "huevos"), Lists.items("leche, pan y huevos"))
        assertEquals(listOf("milk", "bread", "eggs"), Lists.items("milk, bread and eggs."))
        assertEquals(listOf("pilas"), Lists.items("pilas"))
    }

    @Test
    fun addingSkipsWhatIsAlreadyThere() {
        val list = Lists.OwnerList("compra", listOf("Leche"))
        assertEquals(listOf("Leche", "pan"), Lists.adding(list, listOf("leche", "pan")).items)
    }

    @Test
    fun removingMatchesLooselyAndKeepsTheRest() {
        val list = Lists.OwnerList("compra", listOf("leche desnatada", "pan", "huevos"))
        assertEquals(listOf("huevos"), Lists.removing(list, listOf("la leche", "pan")).items.let { left -> left })
    }

    @Test
    fun aListSurvivesTheMemoryStore() {
        val list = Lists.OwnerList("Compra", listOf("leche", "pan"))
        assertEquals(list, Lists.fromStore(list.key, Lists.content(list)))
        assertEquals("list-compra", list.key)
        assertEquals("Compra: leche, pan.", Lists.describe(list))
    }
}
