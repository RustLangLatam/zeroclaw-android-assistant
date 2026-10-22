package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsTest {

    private val spotify = App("com.spotify.music", "Spotify", AppKind.MUSIC, minutes = 300, playsSearch = true)
    private val ytMusic = App("com.google.android.apps.youtube.music", "YouTube Music", AppKind.MUSIC, minutes = 20, default = true, playsSearch = true)
    private val maps = App("com.google.android.apps.maps", "Maps", AppKind.MAPS, minutes = 90)

    @Test
    fun theMostUsedComesFirstThenTheDefault() {
        assertEquals(listOf(spotify, ytMusic), Apps.rank(listOf(ytMusic, spotify)))
        val noUsage = listOf(spotify.copy(minutes = 0), ytMusic.copy(minutes = 0))
        assertEquals("YouTube Music", Apps.rank(noUsage).first().label)
    }

    @Test
    fun kindsFromWellKnownAppsAndAndroidsCategory() {
        assertEquals(AppKind.MUSIC, Apps.kind("com.spotify.music", -1))
        assertEquals(AppKind.VIDEO, Apps.kind("com.example.player", 2))
        assertEquals(AppKind.MUSIC, Apps.kindOf("música"))
        assertEquals(AppKind.VIDEO, Apps.kindOf("videos"))
        assertNull(Apps.kindOf(""))
    }

    @Test
    fun theAppTheOwnerMeans() {
        val apps = listOf(spotify, ytMusic, maps)
        assertEquals(spotify, Apps.find(apps, "spotify"))
        assertEquals(ytMusic, Apps.find(apps, "com.google.android.apps.youtube.music"))
        assertEquals(maps, Apps.find(apps, "Google Maps"))
        assertNull(Apps.find(apps, "Netflix"))
    }

    @Test
    fun theModelIsToldWhatItCanDoInEach() {
        val text = Apps.describe(listOf(maps, ytMusic, spotify), AppKind.MUSIC, usageKnown = true)
        assertTrue(text, text.startsWith("The owner's music apps, most used first"))
        assertTrue(text, "1. Spotify (com.spotify.music) · 300 min · can play a search" in text)
        assertTrue(text, "Maps" !in text)
        assertEquals("No video apps installed.", Apps.describe(listOf(spotify), AppKind.VIDEO, usageKnown = false))
    }
}
