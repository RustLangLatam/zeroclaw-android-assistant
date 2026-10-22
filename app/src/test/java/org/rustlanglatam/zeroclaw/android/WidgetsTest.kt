package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetsTest {

    private val greeting = Placed(Widget.GREETING, true)
    private val panorama = Placed(Widget.PANORAMA, true)
    private val weather = Placed(Widget.WEATHER, true)
    private val alarm = Placed(Widget.ALARM, true)
    private val agenda = Placed(Widget.AGENDA, true)
    private val messages = Placed(Widget.MESSAGES, false)
    private val mail = Placed(Widget.MAIL, false)
    private val reminders = Placed(Widget.REMINDERS, false)

    @Test
    fun wideOnesTakeARowAndCardsFillTheRest() {
        val rows = Widgets.rows(Widgets.DEFAULT, top = true)
        assertEquals(listOf(listOf(greeting), listOf(panorama), listOf(weather, alarm, agenda)), rows)
        assertEquals(listOf(listOf(messages, mail, reminders)), Widgets.rows(Widgets.DEFAULT, top = false))
        assertTrue(Widgets.fits(Widgets.DEFAULT))
    }

    @Test
    fun droppedOnAnotherItTakesItsPlace() {
        val moved = Widgets.move(Widgets.DEFAULT, Widget.AGENDA, Widget.WEATHER, top = true)
        assertEquals(listOf(greeting, panorama, agenda, weather, alarm, messages, mail, reminders), moved)
        // The greeting can be moved after the other cards in its area, like any widget.
        assertEquals(
            listOf(listOf(panorama), listOf(weather, alarm, agenda), listOf(greeting)),
            Widgets.rows(Widgets.move(Widgets.DEFAULT, Widget.GREETING, null, top = true), top = true),
        )
    }

    @Test
    fun whenItDoesNotFitTheyTradePlaces() {
        // The area below has one row: a wide widget can only go there by swapping with what is there, and only if that fits.
        val full = Widgets.DEFAULT + Placed(Widget.BATTERY, false)
        assertEquals(full, Widgets.move(full, Widget.GREETING, Widget.MAIL, top = false))
        val two = listOf(greeting, panorama, weather, messages)
        assertEquals(listOf(messages.copy(top = true), panorama, weather, greeting.copy(top = false)), Widgets.move(two, Widget.GREETING, Widget.MESSAGES, top = false))
    }

    @Test
    fun threeRowsAboveOneBelowAtMost() {
        val full = Widgets.DEFAULT + Placed(Widget.BATTERY, false)
        assertFalse(Widgets.fits(full + Placed(Widget.NEWS, false)))
        assertEquals(full + Placed(Widget.NEWS, true), Widgets.add(full, Widget.NEWS))
        val fuller = full + Placed(Widget.NEWS, true)
        assertEquals(emptyList<Widget>(), Widgets.available(fuller))
        assertEquals(listOf(Widget.GREETING, Widget.KSANDRA), Widgets.available(Widgets.remove(fuller, Widget.GREETING)))
    }

    @Test
    fun savedAndALayoutFromBeforeTheWideOnes() {
        assertEquals(Widgets.DEFAULT, Widgets.decode(Widgets.encode(Widgets.DEFAULT)))
        val old = listOf("WEATHER:top", "ALARM:top", "AGENDA:top", "MESSAGES:bottom", "MAIL:bottom", "REMINDERS:bottom")
        assertEquals(Widgets.DEFAULT, Widgets.decode(old))
        // A greeting removed by the owner is not restored on decode.
        val without = Widgets.remove(Widgets.DEFAULT, Widget.GREETING)
        assertEquals(without, Widgets.decode(Widgets.encode(without)))
    }

    @Test
    fun theSkyAndTheUnread() {
        assertEquals("Tormenta", Widgets.sky(95))
        assertEquals(listOf("com.whatsapp" to 2, "org.telegram.messenger" to 1), Widgets.unread(listOf("org.telegram.messenger", "com.whatsapp", "com.whatsapp", "")))
    }
}
