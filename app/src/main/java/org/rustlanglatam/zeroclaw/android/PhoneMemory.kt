package org.rustlanglatam.zeroclaw.android

import android.content.Context
import android.util.Log
import io.github.rustlanglatam.zeroclaw.Client
import io.github.rustlanglatam.zeroclaw.MemorySearch
import kotlinx.coroutines.runBlocking
import java.time.OffsetDateTime
import kotlin.concurrent.thread

/**
 * Persists [Memory] and [Notes] in the owner agent's ZeroClaw memory through the Kotlin client. The store
 * holds both the profile (each fact under `profile-<id>`, with its area and whether the owner stated it
 * encoded in the category) and entries the agent stored itself. Writes are sent as they happen; [sync]
 * reads everything back so the memory screen can show and delete all of it.
 */
object PhoneMemory {

    private const val AGENT = "owner"

    fun attach(context: Context) {
        val app = context.applicationContext
        Memory.onPut = { item -> write(app, Pending.Op(Pending.STORE, item.key)) }
        Memory.onDrop = { item -> write(app, Pending.Op(Pending.FORGET, item.key)) }
        Notes.onPut = { note -> write(app, Pending.Op(Pending.STORE, note.key)) }
        Notes.onDrop = { note -> write(app, Pending.Op(Pending.FORGET, note.key)) }
        Lists.onPut = { list -> write(app, Pending.Op(Pending.STORE, list.key)) }
        Lists.onDrop = { list -> write(app, Pending.Op(Pending.FORGET, list.key)) }
    }

    /**
     * Operations not yet delivered to ZeroClaw (unpaired, gateway down, failed call), stored in
     * `memory-pending.txt` and sent before the next sync, which would otherwise overwrite the local copy
     * without them. Per key, the last operation wins. Pure, unit-tested on the JVM.
     */
    object Pending {
        const val STORE = "store"
        const val FORGET = "forget"

        data class Op(val kind: String, val key: String) {
            override fun toString() = "$kind $key"
        }

        fun parse(line: String): Op? = line.split(' ', limit = 2).takeIf { it.size == 2 && it[0] in setOf(STORE, FORGET) }?.let { Op(it[0], it[1]) }

        /** Returns [ops] with [op] added, replacing any earlier operation on the same key. */
        fun add(ops: List<Op>, op: Op): List<Op> = ops.filterNot { it.key == op.key } + op
    }

    private fun pendingFile(context: Context) = java.io.File(context.filesDir, "memory-pending.txt")

    @Synchronized
    private fun pending(context: Context): List<Pending.Op> =
        runCatching { pendingFile(context).readSealedLines().mapNotNull(Pending::parse) }.getOrDefault(emptyList())

    @Synchronized
    private fun keep(context: Context, ops: List<Pending.Op>) {
        val file = pendingFile(context)
        if (ops.isEmpty()) file.delete() else runCatching { file.writeSealedLines(ops.map { it.toString() }) }
    }

    /** Sends [op]; returns `false` when it failed and must stay pending. */
    private suspend fun send(client: Client, op: Pending.Op): Boolean = runCatching {
        when (op.kind) {
            Pending.STORE -> Memory.items.firstOrNull { it.key == op.key }?.let { client.memory.store(it.key, it.text, Memory.category(it), AGENT) }
                ?: Notes.current().firstOrNull { it.key == op.key }?.let { client.memory.store(it.key, Notes.content(it), Notes.CATEGORY, AGENT) }
                ?: Lists.all().firstOrNull { it.key == op.key }?.let { client.memory.store(it.key, Lists.content(it), Lists.CATEGORY, AGENT) }
            else -> client.memory.forget(op.key, AGENT)
        }
        true
    }.onFailure { Log.w(AssistantService.TAG, "memory $op: ${it.message}") }.getOrDefault(false)

    /**
     * Flushes pending operations, uploads local-only memories once (migration), then replaces the local
     * copy with ZeroClaw's memory. Runs on a background thread; does nothing while unpaired.
     */
    fun sync(context: Context) = thread {
        val token = AssistantService.token(context) ?: return@thread
        runCatching {
            val client = AssistantService.client(token)
            runBlocking {
                // Pending operations go first: the read below replaces the local copy.
                keep(context, pending(context).filterNot { send(client, it) })
                if (!Memory.migrated) {
                    Memory.items.filterNot { it.conversation }.forEach { client.memory.store(it.key, it.text, Memory.category(it), AGENT) }
                    Memory.migrated = true
                }
                val entries = client.memory.search(MemorySearch(agent = AGENT))
                // Known limitation: a write still in flight can be missed by this read; the next sync picks it up.
                // Home-screen notes share the same memory, distinguished by their category.
                val (notes, rest) = entries.partition { it.category == Notes.CATEGORY }
                val (lists, memories) = rest.partition { it.category == Lists.CATEGORY }
                Memory.replace(memories.map { Memory.fromStore(it.key, it.content, it.category, time(it.timestamp)) })
                Notes.replace(notes.map { Notes.fromStore(it.key, it.content) })
                Lists.replace(lists.map { Lists.fromStore(it.key, it.content) })
            }
        }.onFailure { Log.w(AssistantService.TAG, "memory sync: ${it.message}") }
    }

    private fun time(stamp: String) = runCatching { OffsetDateTime.parse(stamp).toInstant().toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    private fun write(context: Context, op: Pending.Op) {
        thread {
            val token = AssistantService.token(context)
            val sent = token != null && runBlocking { send(AssistantService.client(token), op) }
            if (!sent) keep(context, Pending.add(pending(context), op))
        }
    }
}
