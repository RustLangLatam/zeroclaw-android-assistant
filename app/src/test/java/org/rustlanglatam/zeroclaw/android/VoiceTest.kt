package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceTest {
    /** Synthesizes a voiced tone at [hz] with harmonics, approximating speech. */
    private fun tone(hz: Double, rate: Int = 24000, seconds: Double = 0.6) = FloatArray((rate * seconds).toInt()) { i ->
        val t = i.toDouble() / rate
        (0.4 * sin(2 * PI * hz * t) + 0.25 * sin(4 * PI * hz * t) + 0.15 * sin(6 * PI * hz * t)).toFloat()
    }

    @Test fun aWomansPitch() = assertEquals(220f, VoiceProbe.pitch(tone(220.0), 24000)!!, 6f)

    @Test fun aMansPitch() = assertEquals(115f, VoiceProbe.pitch(tone(115.0), 24000)!!, 4f)

    @Test fun silenceHasNone() = assertNull(VoiceProbe.pitch(FloatArray(24000), 24000))
}
