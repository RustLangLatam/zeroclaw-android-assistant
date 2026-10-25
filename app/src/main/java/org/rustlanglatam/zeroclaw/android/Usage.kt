package org.rustlanglatam.zeroclaw.android

import android.content.Context
import io.github.rustlanglatam.zeroclaw.CostSummary
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reports model cost from ZeroClaw's own accounting (`/api/cost`, through the client): this month, today and per agent,
 * with agents labeled by role (owner conversation, third-party replies, background briefings, offline model). Used by
 * the `usage` tool and the settings screen. Pure apart from [read].
 */
object Usage {
    /** Fetches the cost summary since the first of the month; blocks on the network. Returns `null` when ZeroClaw is unreachable. */
    fun read(context: Context): CostSummary? {
        val token = AssistantService.token(context) ?: return null
        val from = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime().toString()
        return runCatching { runBlocking { AssistantService.client(token).cost.summary(from = from) } }.getOrNull()
    }

    /** Returns a localized, owner-facing label for an agent alias. */
    fun who(agent: String) = when (agent) {
        "owner" -> t(R.string.usage_with_you)
        "third_party" -> t(R.string.usage_replies_to_others)
        AssistantService.SCRIBE -> t(R.string.usage_briefings_and_notices)
        AssistantService.SEER -> t(R.string.usage_photos)
        Offline.AGENT -> t(R.string.usage_without_internet_on_the_phone)
        else -> agent
    }

    fun usd(v: Double) = "US$ " + "%.2f".format(java.util.Locale.ROOT, v)

    /**
     * Formats an amount in US dollars when the model has a configured price, otherwise as a token count. Callers pass
     * [priced] explicitly so every row of a list uses the same unit.
     */
    fun amount(usd: Double, tokens: Long, priced: Boolean = usd > 0) = if (priced) usd(usd) else "%,d tokens".format(java.util.Locale("es"), tokens)

    /** Renders the month's and today's cost and each agent's share, largest first, for the owner's agent to relay. */
    fun describe(s: CostSummary?): String {
        if (s == null) return "The cost cannot be read now: ZeroClaw is not reachable."
        val agents = s.byAgent.orEmpty().values.sortedByDescending { it.costUsd }
            .joinToString("; ") { "${who(it.agentAlias)}: ${usd(it.costUsd)} (${it.requestCount} calls)" }
        if (s.monthlyCostUsd <= 0) return "No price is set for the model, so only its use is known: ${s.totalTokens} tokens and " +
            "${s.requestCount} calls this month. By agent: " + s.byAgent.orEmpty().values.sortedByDescending { it.totalTokens }
            .joinToString("; ") { "${who(it.agentAlias)}: ${it.totalTokens} tokens" } + ". Say it in one or two sentences, in ${Language.current.english}."
        return "Model cost this month: ${usd(s.monthlyCostUsd)}; today: ${usd(s.dailyCostUsd)}; ${s.requestCount} calls." +
            (if (agents.isEmpty()) "" else " By agent: $agents.") + " Say it in one or two sentences, in ${Language.current.english}, in dollars."
    }
}
