package org.rustlanglatam.zeroclaw.android

/** App categories the assistant distinguishes, each with the English [word] used in tool arguments and results. */
enum class AppKind(val word: String) {
    MUSIC("music"), PODCASTS("podcasts"), VIDEO("video"), MAPS("maps"), MESSAGING("messaging"),
    SOCIAL("social"), NEWS("news"), PHOTOS("photos"), PRODUCTIVITY("productivity"), GAMES("games"), OTHER("other"),
}

/**
 * An installed app: foreground [minutes] over the last 30 days (0 without usage access), whether it is
 * the [default] for its kind, and whether it supports play-from-search ([playsSearch]).
 */
data class App(val pkg: String, val label: String, val kind: AppKind, val minutes: Long = 0, val default: Boolean = false, val playsSearch: Boolean = false)

/**
 * Classifies, ranks and looks up the owner's apps, and formats them for the agent when a request needs an
 * app (for example, playing music). Pure Kotlin, unit-tested on the JVM; [PhoneApps] reads the device.
 */
object Apps {

    /** Well-known packages whose Android category is missing or wrong. */
    private val KNOWN = mapOf(
        AppKind.MUSIC to setOf(
            "com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app", "com.amazon.mp3",
            "com.apple.android.music", "com.soundcloud.android", "com.aspiro.tidal", "com.pandora.android", "com.anghami",
        ),
        AppKind.PODCASTS to setOf("com.google.android.apps.podcasts", "au.com.shiftyjelly.pocketcasts", "com.spotify.podcasts"),
        AppKind.VIDEO to setOf(
            "com.google.android.youtube", "com.netflix.mediaclient", "com.disney.disneyplus", "com.amazon.avod.thirdpartyclient",
            "com.hbo.hbonow", "com.wbd.stream", "tv.twitch.android.app", "com.plexapp.android",
        ),
        AppKind.MAPS to setOf("com.google.android.apps.maps", "com.waze", "com.ubercab", "com.citymapper.app.release"),
        AppKind.MESSAGING to setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.google.android.apps.messaging", "com.samsung.android.messaging"),
        AppKind.SOCIAL to setOf("com.instagram.android", "com.facebook.katana", "com.twitter.android", "com.zhiliaoapp.musically", "com.linkedin.android"),
        AppKind.PHOTOS to setOf("com.google.android.apps.photos"),
    )

    /** Returns the kind of [pkg]: from [KNOWN] first, then from [android.content.pm.ApplicationInfo.category]. */
    fun kind(pkg: String, category: Int): AppKind {
        KNOWN.entries.firstOrNull { pkg in it.value }?.let { return it.key }
        return when (category) {
            0 -> AppKind.GAMES
            1 -> AppKind.MUSIC
            2 -> AppKind.VIDEO
            3 -> AppKind.PHOTOS
            4 -> AppKind.SOCIAL
            5 -> AppKind.NEWS
            6 -> AppKind.MAPS
            7 -> AppKind.PRODUCTIVITY
            else -> AppKind.OTHER
        }
    }

    /** Parses a kind name from the model in English or Spanish, singular or plural; returns `null` for any kind. */
    fun kindOf(word: String?): AppKind? {
        val w = Commands.normalize(word.orEmpty()).removeSuffix("s")
        if (w.isEmpty() || w in Lexicon.words("apps.kind.any")) return null
        return AppKind.entries.firstOrNull { it.word.removeSuffix("s") == w } ?: when (w) {
            in Lexicon.words("apps.kind.music") -> AppKind.MUSIC
            in Lexicon.words("apps.kind.podcasts") -> AppKind.PODCASTS
            in Lexicon.words("apps.kind.video") -> AppKind.VIDEO
            in Lexicon.words("apps.kind.maps") -> AppKind.MAPS
            in Lexicon.words("apps.kind.messaging") -> AppKind.MESSAGING
            in Lexicon.words("apps.kind.social") -> AppKind.SOCIAL
            in Lexicon.words("apps.kind.news") -> AppKind.NEWS
            in Lexicon.words("apps.kind.photos") -> AppKind.PHOTOS
            in Lexicon.words("apps.kind.games") -> AppKind.GAMES
            else -> AppKind.OTHER
        }
    }

    /** Sorts by usage minutes, then default apps, then label. */
    fun rank(apps: List<App>): List<App> =
        apps.sortedWith(compareByDescending<App> { it.minutes }.thenByDescending { it.default }.thenBy { it.label.lowercase() })

    /** Finds an app by exact package, exact label, or the best-ranked partial label match. */
    fun find(apps: List<App>, name: String): App? {
        val n = Commands.normalize(name)
        if (n.isEmpty()) return null
        return apps.firstOrNull { it.pkg == name.trim() }
            ?: apps.firstOrNull { Commands.normalize(it.label) == n }
            ?: rank(apps.filter { n in Commands.normalize(it.label) || Commands.normalize(it.label) in n }).firstOrNull()
    }

    /** Formats the `list_apps` tool result: up to 15 apps of [kind], ranked, with usage and capabilities. */
    fun describe(apps: List<App>, kind: AppKind?, usageKnown: Boolean): String {
        val chosen = rank(if (kind == null) apps else apps.filter { it.kind == kind })
        val what = kind?.let { "${it.word} apps" } ?: "apps"
        if (chosen.isEmpty()) return "No $what installed."
        val order = if (usageKnown) "most used first (last 30 days)" else
            "usage access is off, so the phone's default comes first, then by name; the owner can allow it in Ajustes"
        return "The owner's $what, $order:\n" + chosen.take(15).mapIndexed { i, a ->
            listOfNotNull(
                "${i + 1}. ${a.label} (${a.pkg})",
                a.kind.word.takeIf { kind == null },
                "${a.minutes} min".takeIf { usageKnown },
                "default".takeIf { a.default },
                "can play a search".takeIf { a.playsSearch },
            ).joinToString(" · ")
        }.joinToString("\n")
    }
}
