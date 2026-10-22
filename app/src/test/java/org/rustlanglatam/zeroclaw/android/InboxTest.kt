package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

class InboxTest {

    private val whatsapp = "com.whatsapp"
    private fun key() = "0|$whatsapp|${System.nanoTime()}"

    @Before
    fun defaults() {
        Inbox.apps = Inbox.DEFAULT_APPS
        Inbox.groups = false
        Policy.owner = "Milton"
        Policy.groups = mapOf("Ana" to setOf("family"), "Laura" to setOf("work"))
        Inbox.load(temp(), now = 0)
    }

    private fun temp() = File.createTempFile("seen", ".txt").apply { delete(); deleteOnExit() }

    @Test
    fun onlyNewMessagesFromOthersAreTaken() {
        val chat = key()
        val first = listOf(Inbox.Raw("Laura", "Are you free at 10?", 1))
        assertEquals(listOf("Are you free at 10?"), Inbox.accept(whatsapp, chat, false, first).map { it.text })
        // Posted again with the whole history, the owner's own reply and one new message.
        val again = first + Inbox.Raw(null, "Let me check", 2) + Inbox.Raw("Laura", "Or at 15?", 3)
        assertEquals(listOf("Or at 15?"), Inbox.accept(whatsapp, chat, false, again).map { it.text })
        // An edited message keeps its timestamp, so it is not handled twice.
        assertTrue(Inbox.accept(whatsapp, chat, false, listOf(Inbox.Raw("Laura", "Or at 16?", 3))).isEmpty())
    }

    @Test
    fun otherAppsAndGroupChatsAreIgnoredByDefault() {
        val message = listOf(Inbox.Raw("Laura", "hi", 1))
        assertTrue(Inbox.accept("com.example.game", key(), false, message).isEmpty())
        assertTrue(Inbox.accept(whatsapp, key(), true, message).isEmpty())
        Inbox.groups = true
        assertEquals(1, Inbox.accept(whatsapp, key(), true, message).size)
        Inbox.apps = setOf("com.example.chat")
        assertTrue(Inbox.accept(whatsapp, key(), false, message).isEmpty())
    }

    @Test
    fun theFirstReplyInAConversationIntroducesTheAssistant() {
        val chat = key()
        assertEquals("Hola, soy Ksandra, la asistente de Milton. Está ocupado a las 10.", Inbox.withIntro(chat, "Está ocupado a las 10."))
        assertEquals("¡Gracias!", Inbox.withIntro(chat, "¡Gracias!"))
        assertEquals("Soy Ksandra: anotado.", Inbox.withIntro(key(), "Soy Ksandra: anotado."))
        assertEquals("Milton's assistant here: noted.", Inbox.withIntro(key(), "Milton's assistant here: noted."))
        // A contact who wrote in English is answered in English.
        assertEquals("Hi, I'm Ksandra, Milton's assistant. He is free at four, I will let him know.", Inbox.withIntro(key(), "He is free at four, I will let him know."))
    }

    @Test
    fun anUndoneReplyIsMarkedInTheDigest() {
        val entry = Inbox.received(Inbox.Message(whatsapp, key(), "Ana", "Dinner?", 1))
        Inbox.status(entry, Inbox.Status.QUEUED)
        Inbox.undone(Act.reply(entry.id, "Ana", null, "Yes"))
        assertEquals(Inbox.Status.UNDONE, entry.status)
    }

    @Test
    fun repliesFollowTheContactsRules() {
        val policy = Policy(Policy.DEFAULT_RULES) { LocalDate.of(2026, 10, 16) }
        fun decision(contact: String) = policy.decide(Principal.THIRD_PARTY, Capability.NOTIFICATION_REPLY, Policy.target(contact))
        fun reply(contact: String) = decision(contact).autonomy
        assertEquals(Autonomy.ASK, reply("Laura")) // work
        assertEquals(Autonomy.NEVER, reply("Pat")) // unknown sender: no rule applies
        // Asking does not consume the quota; each reply sent uses one of the five daily uses.
        repeat(10) { assertEquals(Autonomy.ACT_NOTIFY, reply("Ana")) }
        repeat(5) { policy.spend(decision("Ana").spends!!) } // family, 5 a day
        assertEquals(Autonomy.NEVER, reply("Ana")) // limit reached: falls back to the third-party default
        // Undoing a reply refunds its use; today's counters survive a restart.
        policy.refund(decision("Pat").spends ?: "${LocalDate.of(2026, 10, 16)} THIRD_PARTY NOTIFICATION_REPLY Ana")
        assertEquals(Autonomy.ACT_NOTIFY, reply("Ana"))
        val again = Policy(Policy.DEFAULT_RULES) { LocalDate.of(2026, 10, 16) }.apply { restoreCounters(policy.counters() + ("2026-10-15 x" to 3)) }
        assertEquals(policy.counters(), again.counters())
        assertTrue(Capability.NOTIFICATION_REPLY.strongApproval && Capability.NOTIFICATION_REPLY.undoable)
        // A rule for everyone cannot reply without asking.
        assertEquals(Autonomy.ASK, Policy.ceiling(Rule(Principal.THIRD_PARTY, Capability.NOTIFICATION_REPLY, Scope.ALL, Autonomy.ACT)))
    }

    @Test
    fun savedRulesFromBeforeGetTheNewDefaults() {
        val old = Policy.DEFAULT_RULES.filter { it.capability != Capability.NOTIFICATION_REPLY }
        val rules = Policy.withNewDefaults(old)
        assertEquals(2, rules.count { it.capability == Capability.NOTIFICATION_REPLY })
        assertEquals(rules, Policy.withNewDefaults(rules))
    }

    @Test
    fun theChannelSettingsAreSaved() {
        val state = Saved.State(Policy.DEFAULT_RULES, false, 30, emptyList(), setOf("org.telegram.messenger"), true)
        assertEquals(state, Saved.decode(Saved.encode(state)))
        // A legacy file without channel settings decodes to the default apps and no group chats.
        val old = Saved.encode(state.copy(apps = null, groups = false)).replace(",\"groups\":false", "")
        assertEquals(state.copy(apps = null, groups = false), Saved.decode(old))
    }

    @Test
    fun aRuleFromANewerVersionDoesNotLoseTheRest() {
        val state = Saved.State(Policy.DEFAULT_RULES, false, 30, emptyList(), owner = "Milton")
        val newer = Saved.encode(state).replaceFirst("\"capability\":\"${Policy.DEFAULT_RULES[0].capability.name}\"", "\"capability\":\"TELEPORT\"")
        val loaded = Saved.decode(newer)
        assertEquals("Milton", loaded.owner)
        assertEquals(Policy.DEFAULT_RULES.size - 1, loaded.rules.size)
    }

    @Test
    fun seenMessagesSurviveARestart() {
        val file = temp()
        Inbox.load(file, now = 100)
        val chat = key()
        val history = listOf(Inbox.Raw("Laura", "Are you free at 10?", 101))
        assertEquals(1, Inbox.accept(whatsapp, chat, false, history).size)
        // After a restart the next message re-posts the history; only the new message is accepted.
        Inbox.load(file, now = 500)
        val again = Inbox.accept(whatsapp, chat, false, history + Inbox.Raw("Laura", "Or at 15?", 102))
        assertEquals(listOf("Or at 15?"), again.map { it.text })
        Inbox.load(file, now = 900)
        assertTrue(Inbox.accept(whatsapp, chat, false, history + Inbox.Raw("Laura", "Or at 15?", 102)).isEmpty())
    }

    @Test
    fun theHistoryOnThePhoneBeforeTheFirstStartIsNotAnswered() {
        Inbox.load(temp(), now = 1_000)
        val old = listOf(Inbox.Raw("Ana", "Dinner yesterday?", 900))
        assertEquals(listOf("Today?"), Inbox.accept(whatsapp, key(), false, old + Inbox.Raw("Ana", "Today?", 1_100)).map { it.text })
    }

    @Test
    fun theIntroductionNamesTheOwner() {
        Policy.owner = "Pat"
        assertEquals("Hola, soy Ksandra, la asistente de Pat. Noted.", Inbox.withIntro(key(), "Noted."))
    }

    @Test
    fun theOwnerChoosesFamilyAndWork() {
        Policy.groups = Policy.groupsOf(family = listOf("Ana"), work = listOf("Laura", "Ana"))
        assertEquals(setOf("family", "work"), Policy.target("Ana").groups)
        assertEquals(listOf("Ana", "Laura"), Policy.members("work"))
        assertTrue(Policy.target("Pat").groups.isEmpty())
        val policy = Policy(Policy.DEFAULT_RULES)
        assertEquals(Autonomy.ASK, policy.decide(Principal.THIRD_PARTY, Capability.NOTIFICATION_REPLY, Policy.target("Laura")).autonomy)
        assertEquals(Autonomy.NEVER, policy.decide(Principal.THIRD_PARTY, Capability.NOTIFICATION_REPLY, Policy.target("Pat")).autonomy)
    }

    @Test
    fun theSetupIsSaved() {
        val state = Saved.State(Policy.DEFAULT_RULES, false, 30, emptyList(), setOf("com.whatsapp"), false,
            owner = "Milton", contacts = mapOf("Ana" to setOf("family")), voice = true)
        assertEquals(state, Saved.decode(Saved.encode(state)))
        // A legacy file from before setup decodes with no owner, no contacts and voice off.
        val old = Saved.decode(Saved.encode(state.copy(owner = null, contacts = null, voice = false)))
        assertNull(old.owner)
        assertNull(old.contacts)
        assertFalse(old.voice)
    }

    @Test
    fun theAssistantIntroducesItselfFromTheRules() {
        val groups = Policy.groupsOf(listOf("Ana"), listOf("Laura"))
        val text = introduction("Milton", Policy.DEFAULT_RULES, groups, listOf("WhatsApp"), 30)
        assertTrue(text.startsWith("Hola Milton, soy ${Policy.ASSISTANT}"))
        assertTrue("Familia (Ana): lo hago y te aviso, tras 30 s para deshacer" in text)
        assertTrue("Trabajo (Laura): solo si lo apruebas" in text)
        assertTrue("Cualquier otra persona: nunca respondo" in text)
        // The introduction reflects a rule the owner lowered.
        val lowered = Policy.DEFAULT_RULES.map {
            if (it.capability == Capability.NOTIFICATION_REPLY && it.scope == Scope.group("family")) it.copy(autonomy = Autonomy.NEVER) else it
        }
        assertTrue("Familia (Ana): nunca" in introduction("Milton", lowered, groups, listOf("WhatsApp"), 30))
        assertTrue("Aún no leo ninguna app de mensajería." in introduction("Milton", Policy.DEFAULT_RULES, groups, emptyList(), 30))
    }
}
