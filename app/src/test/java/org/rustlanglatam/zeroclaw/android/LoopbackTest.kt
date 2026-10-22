package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackTest {

    private fun head(text: String) = Loopback.readHead(text.byteInputStream())

    @Test
    fun aHeadIsReadUpToItsBlankLine() {
        val lines = head("POST /mcp/owner HTTP/1.1\r\nAuthorization: Bearer abc\r\nContent-Length: 12\r\n\r\n{\"id\":1}")!!
        assertEquals(listOf("POST /mcp/owner HTTP/1.1", "Authorization: Bearer abc", "Content-Length: 12"), lines)
        assertEquals("Bearer abc", Loopback.header(lines, "AUTHORIZATION"))
        assertEquals(12, Loopback.contentLength(lines))
    }

    @Test
    fun aHeadThatNeverEndsOrIsTooLargeIsNotRead() {
        assertNull(head("POST /mcp/owner HTTP/1.1\r\nHost: x"))
        assertNull(Loopback.readHead(("POST / HTTP/1.1\r\nX: " + "a".repeat(Loopback.MAX_HEAD)).byteInputStream()))
    }

    @Test
    fun aBadOrHugeLengthIsRefusedBeforeAnythingIsAllocated() {
        fun length(value: String) = Loopback.contentLength(listOf("POST / HTTP/1.1", "Content-Length: $value"))
        assertNull(length("x"))
        assertNull(length("-1"))
        assertNull(length("2000000000"))
        assertNull(length("99999999999999"))
        assertEquals(Loopback.MAX_BODY, length("${Loopback.MAX_BODY}"))
        assertEquals(0, Loopback.contentLength(listOf("POST / HTTP/1.1")))
    }

    @Test
    fun eachStartGivesTheServersNewKeysAndKeepsTheRest() {
        val config = """
            [gateway]
            paired_tokens = ["hash"]

            [[mcp.servers]]
            name = "owner"
            transport = "http"
            url = "http://127.0.0.1:42618/mcp/owner"
            headers = { Authorization = "Bearer 0011aa" }

            [[mcp.servers]]
            name = "third_party"
            transport = "http"
            url = "http://127.0.0.1:42618/mcp/third_party"
            headers = { Authorization = "Bearer 22bb33" }
        """.trimIndent()
        val owner = Loopback.newKey()
        val third = Loopback.newKey()
        val updated = Loopback.withKeys(config, mapOf("owner" to owner, "third_party" to third))
        assertTrue("Bearer $owner\" }\n\n[[mcp.servers]]\nname = \"third_party\"" in updated)
        assertTrue(updated.trimEnd().endsWith("Bearer $third\" }"))
        assertFalse("0011aa" in updated || "22bb33" in updated)
        assertTrue("paired_tokens = [\"hash\"]" in updated)
        assertEquals(64, owner.length)
        assertNotEquals(owner, third)
    }

    @Test
    fun theGatewayIsZeroClawsOnlyWhenItsLogSaysSo() {
        assertTrue(Loopback.gatewayUp("\u001b[1m🦀 ZeroClaw Gateway listening on http://127.0.0.1:42617\u001b[0m\n", 42617))
        assertFalse(Loopback.gatewayUp("Error: Address already in use (os error 98)", 42617))
        assertFalse(Loopback.gatewayUp("ZeroClaw Gateway listening on http://127.0.0.1:9999", 42617))
    }
}
