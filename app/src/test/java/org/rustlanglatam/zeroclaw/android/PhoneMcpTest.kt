package org.rustlanglatam.zeroclaw.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMcpTest {

    @Test
    fun everyListedToolHasItsWorkAndNothingElseIsServed() {
        val listed = PhoneMcp.TOOLS.values.flatMap { it.keys }.toSet()
        assertEquals(listed, PhoneMcp.HANDLERS.keys)
        // A third party's endpoint lists only its own two tools.
        assertEquals(setOf("take_message", "availability"), PhoneMcp.TOOLS.getValue(Principal.THIRD_PARTY).keys)
    }

    private fun rpc(body: String, principal: Principal = Principal.OWNER, call: (String, JSONObject) -> JSONObject = { name, _ -> JSONObject().put("called", name) }) =
        PhoneMcp.rpc(principal, body, call = call)

    @Test
    fun theProtocolAnswersWhatItKnowsAndErrorsForTheRest() {
        assertEquals(202 to "", rpc("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        val init = JSONObject(rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""").second)
        assertEquals("2025-03-26", init.getJSONObject("result").getString("protocolVersion"))
        val list = JSONObject(rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", Principal.THIRD_PARTY).second)
        assertEquals(2, list.getJSONObject("result").getJSONArray("tools").length())
        assertEquals(-32700, JSONObject(rpc("{oops").second).getJSONObject("error").getInt("code"))
        assertEquals(-32601, JSONObject(rpc("""{"jsonrpc":"2.0","id":3,"method":"x/y"}""").second).getJSONObject("error").getInt("code"))
        assertEquals(-32602, JSONObject(rpc("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{}}""").second).getJSONObject("error").getInt("code"))
        val called = JSONObject(rpc("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"list_reminders"}}""").second)
        assertEquals("list_reminders", called.getJSONObject("result").getString("called"))
    }

    @Test
    fun aToolThatThrowsIsAnErrorForTheAgentNotACrash() {
        val answer = JSONObject(rpc("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"news"}}""") { _, _ -> error("no network") }.second)
        val result = answer.getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
        assertTrue("no network" in result.getJSONArray("content").getJSONObject(0).getString("text"))
    }
}
