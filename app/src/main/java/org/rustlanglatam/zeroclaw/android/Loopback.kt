package org.rustlanglatam.zeroclaw.android

import java.io.InputStream

/**
 * Trust rules for loopback (127.0.0.1) traffic, which any app on the device can reach (ADR-0020): requests are read
 * with size limits both before and after the key check; keys live for the lifetime of the process; and the gateway is
 * attributed to ZeroClaw only when ZeroClaw's own log reports binding it. Pure Kotlin, unit-tested on the JVM.
 */
object Loopback {
    /** Maximum size in bytes of a request head (request line and headers); reading stops beyond it. */
    const val MAX_HEAD = 16 * 1024

    /** Maximum body size in bytes. ZeroClaw's MCP requests are small JSON-RPC messages, so larger bodies are refused unread. */
    const val MAX_BODY = 1024 * 1024

    /** Reads the request head and returns its lines without the terminating blank line, or `null` if the stream ends first or the head exceeds [max]. */
    fun readHead(input: InputStream, max: Int = MAX_HEAD): List<String>? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var read = 0
        while (true) {
            val c = input.read()
            if (c < 0 || ++read > max) return null
            if (c == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) return lines
                lines += text
                line.clear()
            } else {
                line.append(c.toChar())
            }
        }
    }

    /** Returns the value of header [name] in [head], matched case-insensitively, or `null`. */
    fun header(head: List<String>, name: String): String? =
        head.drop(1).firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

    /** Returns the Content-Length: 0 when absent, `null` when it is not a number or falls outside 0..[max]. */
    fun contentLength(head: List<String>, max: Int = MAX_BODY): Int? {
        val value = header(head, "content-length") ?: return 0
        return value.toIntOrNull()?.takeIf { it in 0..max }
    }

    /** Returns a new key: 32 bytes from [java.security.SecureRandom], hex-encoded. */
    fun newKey(): String = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /**
     * Returns [config] with each MCP server entry's bearer key replaced from [keys], looked up by endpoint name
     * (`owner`, `third_party`). Everything else is left untouched, since ZeroClaw keeps its pairing in the same file.
     */
    fun withKeys(config: String, keys: Map<String, String>): String =
        Regex("""(url = "http://127\.0\.0\.1:\d+/mcp/(\w+)"\s*\n\s*headers = \{ Authorization = "Bearer )([0-9a-f]+)(" \})""")
            .replace(config) { m -> keys[m.groupValues[2]]?.let { m.groupValues[1] + it + m.groupValues[4] } ?: m.value }

    /** Returns whether ZeroClaw's [log] reports its gateway listening on [port]; a `/health` response alone may come from another app. */
    fun gatewayUp(log: String, port: Int): Boolean = "Gateway listening on http://127.0.0.1:$port" in log
}
