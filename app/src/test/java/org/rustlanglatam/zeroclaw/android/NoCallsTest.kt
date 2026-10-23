package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** The app never places a phone call: no CALL_PHONE permission, no ACTION_CALL intent; dialing only opens the dialer. */
class NoCallsTest {

    @Test
    fun theManifestCannotPlaceCalls() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.CALL_PHONE"))
        assertFalse(manifest.contains("android.permission.CALL_PRIVILEGED"))
    }

    @Test
    fun noSourceUsesTheCallIntent() {
        val sources = File("src/main/java").walk().filter { it.extension == "kt" }.toList()
        assertFalse(sources.isEmpty())
        assertEquals(emptyList<String>(), sources.filter { Regex("""ACTION_CALL\b|"android\.intent\.action\.CALL"""").containsMatchIn(it.readText()) }.map { it.name })
    }

    @Test
    fun dialingIsTheOwnersOnly() {
        val policy = Policy(Policy.DEFAULT_RULES)
        assertEquals(Autonomy.ACT, policy.decide(Principal.OWNER, Capability.DIAL, Policy.OWNER_TARGET).autonomy)
        assertEquals(Autonomy.NEVER, policy.decide(Principal.THIRD_PARTY, Capability.DIAL, Target("Ana", setOf("family"))).autonomy)
    }
}
