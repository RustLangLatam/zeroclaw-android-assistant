package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTest {

    @Test
    fun settingsByName() {
        assertEquals(Device.Setting.AIRPLANE, Device.Setting.named("modo avión"))
        assertEquals(Device.Setting.BLUETOOTH, Device.Setting.named("Bluetooth"))
        assertEquals(Device.Setting.BRIGHTNESS, Device.Setting.named("brillo"))
        assertEquals(Device.Setting.DO_NOT_DISTURB, Device.Setting.named("do_not_disturb"))
        assertEquals(Device.Setting.WIFI, Device.Setting.named("Wi-Fi"))
        assertNull(Device.Setting.named("tostadora"))
        assertTrue(Device.Setting.BRIGHTNESS.direct)
        assertFalse(Device.Setting.AIRPLANE.direct)
    }

    @Test
    fun levelsInWords() {
        assertEquals(128, Device.level("50%", 10, 255))
        assertEquals(255, Device.level("max", 10, 255))
        assertEquals(0, Device.level("mínimo", 10, 255))
        assertEquals(37, Device.level("baja", 100, 255))
        assertEquals(255, Device.level("sube", 240, 255))
        assertEquals(11, Device.level("up", 8, 15))
        assertNull(Device.level("azul", 10, 255))
    }

    @Test
    fun onOrOff() {
        assertEquals(true, Device.onOff("enciende"))
        assertEquals(false, Device.onOff("apaga"))
        assertEquals(true, Device.onOff("on"))
        assertNull(Device.onOff("mañana"))
    }
}
