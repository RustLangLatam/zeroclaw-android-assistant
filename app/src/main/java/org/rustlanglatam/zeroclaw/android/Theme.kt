package org.rustlanglatam.zeroclaw.android

import android.graphics.BlurMaskFilter
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.runtime.setValue
import kotlin.math.sin

/*
 * Design tokens (see docs/specs/presence-mark.md). The owner selects one of several palettes in settings; every
 * palette fills the same roles: a background with soft radial lights, text colors, the Presence
 * Mark's core and ring, a warm gradient for the primary action, and a cool tone reserved for voice
 * states. Inter is the interface typeface; JetBrains Mono is used for times and data.
 */

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Inter = FontFamily(listOf(300, 400, 500, 600).map { variable(R.font.inter, it) })
val Mono = FontFamily(listOf(400, 500).map { variable(R.font.jetbrains_mono, it) })

/** A radial background light: center ([x], [y]) and [radius] as fractions of the screen, and its peak [alpha]. */
data class Light(val color: Color, val x: Float, val y: Float, val radius: Float, val alpha: Float)

/** A color palette; every palette defines the same set of roles. */
data class Palette(
    val key: String,
    @androidx.annotation.StringRes val label: Int,
    val light: Boolean,
    /** Background, surface, primary text, secondary text. */
    val void: Color, val sheet: Color, val mist: Color, val slate: Color,
    /** Primary action gradient (top to bottom) and the content color drawn on it. */
    val accentTop: Color, val accentBottom: Color, val onLight: Color,
    /** Warm accent colors. */
    val warmth: Color, val rose: Color,
    /** Colors for voice and listening states. */
    val signal: Color, val ice: Color,
    /** Presence Mark colors: the ring's three gradient stops, the core and its edge, and the halo. */
    val ringA: Color, val ringB: Color, val ringC: Color, val pearl: Color, val pearlEdge: Color, val halo: Color,
    /** Radial lights drawn behind all content. */
    val lights: List<Light>,
    /** Glass surface tint, and the color of hairlines and the dot grid; followed by status colors. */
    val glass: Color, val line: Color,
    val ok: Color, val wait: Color, val alert: Color,
    /** Glow intensity multiplier for the mark; reduced at night by [Daylight]. */
    val glow: Float = 1f,
) {
    /** The palette's display name in the assistant's language. */
    val name: String get() = t(label)
}

/** The built-in palettes. */
object Palettes {
    /** Dark plum with coral, pink and lilac lights. */
    val dusk = Palette(
        "dusk", R.string.palette_dusk, light = false,
        void = Color(0xFF1B1020), sheet = Color(0xFF26162B), mist = Color(0xFFFFF4EE), slate = Color(0xFFD2B7C6),
        accentTop = Color(0xFFFFD0B0), accentBottom = Color(0xFFFF8FAB), onLight = Color(0xFF3A1426),
        warmth = Color(0xFFFFB98F), rose = Color(0xFFFF8FAB),
        signal = Color(0xFF8EE6F2), ice = Color(0xFFDDFAFF),
        ringA = Color(0xFFFFC98A), ringB = Color(0xFFFF84A6), ringC = Color(0xFFB99CFF),
        pearl = Color(0xFFFFF8F2), pearlEdge = Color(0xFFFFCDB8), halo = Color(0xFFFF8FAB),
        lights = listOf(
            Light(Color(0xFFFF6F91), 0.85f, 0.18f, 0.9f, 0.30f),
            Light(Color(0xFF9D7BFF), 0.10f, 0.32f, 0.8f, 0.26f),
            Light(Color(0xFFFFA36C), 0.50f, 1.05f, 1.0f, 0.34f),
        ),
        glass = Color(0xFFFFE6EE), line = Color(0x1FFFFFFF),
        ok = Color(0xFF9BE3B4), wait = Color(0xFFFFC777), alert = Color(0xFFFF8A8A),
    )

    /** Dark violet with lilac, pink and blue lights. */
    val lavender = Palette(
        "lavender", R.string.palette_lavender, light = false,
        void = Color(0xFF17112A), sheet = Color(0xFF221A3A), mist = Color(0xFFF7F2FF), slate = Color(0xFFC3B6E0),
        accentTop = Color(0xFFE3D4FF), accentBottom = Color(0xFFFF9FD0), onLight = Color(0xFF2A1840),
        warmth = Color(0xFFD9C4FF), rose = Color(0xFFFF9FD0),
        signal = Color(0xFF8FD8FF), ice = Color(0xFFE0F4FF),
        ringA = Color(0xFF8FD8FF), ringB = Color(0xFFB99CFF), ringC = Color(0xFFFF9FD0),
        pearl = Color(0xFFFFFAFF), pearlEdge = Color(0xFFE2D2FF), halo = Color(0xFFB99CFF),
        lights = listOf(
            Light(Color(0xFFB08BFF), 0.80f, 0.20f, 0.9f, 0.32f),
            Light(Color(0xFFFF8FCB), 0.12f, 0.45f, 0.8f, 0.20f),
            Light(Color(0xFF6FA8FF), 0.50f, 1.05f, 1.0f, 0.26f),
        ),
        glass = Color(0xFFF0E8FF), line = Color(0x1FFFFFFF),
        ok = Color(0xFF9BE3B4), wait = Color(0xFFFFCF80), alert = Color(0xFFFF8FA0),
    )

    /** Light cream with peach, pink and lilac lights. */
    val coral = Palette(
        "coral", R.string.palette_coral, light = true,
        void = Color(0xFFFFF6F1), sheet = Color(0xFFFFFBF8), mist = Color(0xFF3A2232), slate = Color(0xFF8E6E7F),
        accentTop = Color(0xFFFFB08F), accentBottom = Color(0xFFFF7396), onLight = Color(0xFFFFFFFF),
        warmth = Color(0xFFE8734F), rose = Color(0xFFE5507A),
        signal = Color(0xFF2B9DB3), ice = Color(0xFFBFEFF7),
        ringA = Color(0xFFFF9E6B), ringB = Color(0xFFFF6F98), ringC = Color(0xFF9C7CFF),
        pearl = Color(0xFFFFFFFF), pearlEdge = Color(0xFFFFC2AE), halo = Color(0xFFFF8FA6),
        lights = listOf(
            Light(Color(0xFFFFB59A), 0.85f, 0.15f, 0.9f, 0.55f),
            Light(Color(0xFFFFC1D6), 0.10f, 0.40f, 0.8f, 0.50f),
            Light(Color(0xFFD7C9FF), 0.50f, 1.05f, 1.0f, 0.60f),
        ),
        glass = Color(0xFFFFFFFF), line = Color(0x1A3A2232),
        ok = Color(0xFF2E9D63), wait = Color(0xFFC77D12), alert = Color(0xFFD9434F),
    )

    /** Near-black graphite with silver-grey lights. The default palette, also used by the splash screen. */
    val night = Palette(
        "night", R.string.palette_night, light = false,
        void = Color(0xFF0C0D0F), sheet = Color(0xFF16181B), mist = Color(0xFFEEF1F4), slate = Color(0xFF9AA2AB),
        accentTop = Color(0xFFE6EAEE), accentBottom = Color(0xFFAAB3BC), onLight = Color(0xFF141619),
        warmth = Color(0xFFCDD4DB), rose = Color(0xFFB9C1C9),
        signal = Color(0xFFA9C7D6), ice = Color(0xFFE3ECF2),
        ringA = Color(0xFFE2E7EC), ringB = Color(0xFFB3BCC5), ringC = Color(0xFF808B96),
        pearl = Color(0xFFF7F9FB), pearlEdge = Color(0xFFC8D0D8), halo = Color(0xFFB3BCC5),
        lights = listOf(
            Light(Color(0xFFBFC7D0), 0.80f, 0.20f, 0.9f, 0.10f),
            Light(Color(0xFF8E98A4), 0.12f, 0.40f, 0.8f, 0.07f),
            Light(Color(0xFF5F6874), 0.50f, 1.05f, 1.0f, 0.14f),
        ),
        glass = Color(0xFFEEF2F6), line = Color(0x14FFFFFF),
        ok = Color(0xFF9CCFB0), wait = Color(0xFFD9C08A), alert = Color(0xFFE0918E),
    )

    val all = listOf(lavender, dusk, coral, night)

    /** The palette used before the owner selects one; scripts/splash.py renders the splash screen with it. */
    val default get() = night

    /** Returns the palette with [key], or [default]. */
    fun of(key: String?) = all.firstOrNull { it.key == key } ?: default
}

/**
 * Adjusts a palette to the time of day: unchanged during the day, amber-tinted lights at dusk, and
 * at night dimmer lights and glow, desaturated accents and darker backgrounds (on light palettes, the
 * background shifts toward a dimmer cream). Pure Kotlin, unit-tested on the JVM.
 */
object Daylight {
    /** Returns the daylight factor for [hour], from 0 (night) to 1 (full day). */
    fun of(hour: Int): Float = when (hour) {
        in 0..5 -> 0f
        6 -> 0.35f
        7 -> 0.65f
        in 8..16 -> 1f
        17 -> 0.85f
        18 -> 0.7f
        19 -> 0.55f
        20 -> 0.4f
        21 -> 0.25f
        else -> 0.1f
    }

    private val amber = Color(0xFFFFA25A)
    private val dimCream = Color(0xFFE9D8D0)

    /** Returns [p] adjusted for [hour]. */
    fun tune(p: Palette, hour: Int): Palette {
        val d = of(hour)
        val night = 1 - d
        val dusk = if (hour in 17..20) 0.35f else 0f
        val lights = p.lights.map { it.copy(color = androidx.compose.ui.graphics.lerp(it.color, amber, dusk), alpha = it.alpha * (0.45f + 0.55f * d)) }
        fun quiet(c: Color) = androidx.compose.ui.graphics.lerp(c, p.slate, night * 0.22f)
        fun darker(c: Color, k: Float) = androidx.compose.ui.graphics.lerp(Color.Black, c, k)
        return if (p.light) p.copy(
            void = androidx.compose.ui.graphics.lerp(p.void, dimCream, night * 0.7f),
            sheet = androidx.compose.ui.graphics.lerp(p.sheet, dimCream, night * 0.5f),
            glass = androidx.compose.ui.graphics.lerp(p.glass, dimCream, night * 0.6f),
            lights = lights, glow = 0.6f + 0.4f * d,
        ) else p.copy(
            void = darker(p.void, 0.72f + 0.28f * d), sheet = darker(p.sheet, 0.8f + 0.2f * d),
            accentTop = quiet(p.accentTop), accentBottom = quiet(p.accentBottom),
            pearl = androidx.compose.ui.graphics.lerp(p.pearl, p.pearlEdge, night * 0.35f),
            signal = quiet(p.signal), ice = quiet(p.ice),
            lights = lights, glow = 0.55f + 0.45f * d,
        )
    }
}

/** The active palette, tuned to the current hour; backed by snapshot state so screens recompose when either changes. */
object Ink {
    /** The palette selected by the owner. */
    var base by mutableStateOf(Palettes.default)

    /** The hour the palette is tuned to; updated by the app's clock and by the floating mark. */
    var hour by mutableStateOf(java.time.LocalTime.now().hour)

    val palette: Palette get() = Daylight.tune(base, hour)
    val void get() = palette.void
    val sheet get() = palette.sheet
    val mist get() = palette.mist
    val slate get() = palette.slate
    val signal get() = palette.signal
    val ice get() = palette.ice
    val warmth get() = palette.warmth
    val rose get() = palette.rose
    val pearl get() = palette.pearl
    val line get() = palette.line
    val ok get() = palette.ok
    val wait get() = palette.wait
    val alert get() = palette.alert
    /** Content color on the primary action gradient. */
    val onLight get() = palette.onLight
}

/** Vertical gradient brush for the primary action. */
val Glow get() = Brush.verticalGradient(listOf(Ink.palette.accentTop, Ink.palette.accentBottom))

private fun scheme(p: Palette) = (if (p.light) lightColorScheme() else darkColorScheme()).copy(
    background = p.void, onBackground = p.mist, surface = p.sheet, onSurface = p.mist,
    surfaceVariant = p.sheet, onSurfaceVariant = p.slate, outline = p.line,
    primary = p.rose, onPrimary = p.onLight, error = p.alert,
)

// Brand type scale: display 36, H1 28, H2 22, H3 18, body 16, small 14, caption 12.
private val Type = Typography(
    displayMedium = TextStyle(fontFamily = Inter, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal, letterSpacing = (-1.6).sp),
    displaySmall = TextStyle(fontFamily = Inter, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal, letterSpacing = (-1.0).sp),
    headlineSmall = TextStyle(fontFamily = Inter, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    // Eyebrow labels: short, letter-spaced uppercase text.
    labelSmall = TextStyle(fontFamily = Inter, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.2.sp),
)

/** Monospaced style for times, figures and identifiers. */
val Technical = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)

/** Dimension tokens: corner radii and screen margins. */
object Dimens {
    val card = 18.dp
    val control = 12.dp
    val screen = 24.dp
}

/** Applies the active [Ink] palette and the brand typography to [content]. */
@Composable
fun KsandraTheme(content: @Composable () -> Unit) {
    Ui.tick
    // Simple mode scales every text by [Simple.TEXT_SCALE] on top of the system's font size.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scale = if (Guard.simple) Simple.TEXT_SCALE else 1f
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, density.fontScale * scale)) {
        MaterialTheme(colorScheme = scheme(Ink.palette), typography = Type, content = content)
    }
}

/**
 * Draws a glass surface clipped to [shape]: a translucent vertical-gradient fill and a 1 dp border
 * that is brighter at the top. [lit] scales the opacity.
 */
fun Modifier.glass(shape: Shape, lit: Float = 1f) = this.composed {
    val p = Ink.palette
    // Light palettes use a mostly opaque frosted fill; dark palettes a faint tinted one.
    val fill = if (p.light) listOf(p.glass.copy(alpha = 0.62f * lit.coerceAtMost(1.4f)), p.glass.copy(alpha = 0.40f * lit.coerceAtMost(1.4f)))
    else listOf(p.glass.copy(alpha = 0.08f * lit), p.glass.copy(alpha = 0.03f * lit))
    val edge = if (p.light) listOf(Color.White.copy(alpha = 0.9f), p.mist.copy(alpha = 0.08f))
    else listOf(Color.White.copy(alpha = 0.18f * lit), Color.White.copy(alpha = 0.04f))
    clip(shape).background(Brush.verticalGradient(fill)).border(1.dp, Brush.verticalGradient(edge), shape)
}

/** Fades in and slides up 16 dp over 420 ms on first composition, after [delay] ms. */
fun Modifier.rise(delay: Int = 0) = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(420, delay)) }
    graphicsLayer { alpha = progress.value; translationY = (1 - progress.value) * 16.dp.toPx() }
}

/**
 * Applies [rise] unless the splash transition is in progress. During the splash the mark must already be
 * in its final position and fully opaque, so the splash's mark lands exactly on top of it.
 */
fun Modifier.landing(delay: Int = 0) = composed { if (remember { Ui.splashing }) this else rise(delay) }

/**
 * Timings, distances and curves of the exit animation for transient cards (for example a reply countdown or a
 * pending approval). After [HOLD_MS] the card's background and border fade over [CARD_MS]; halfway through that
 * fade the content starts a smoke-like dissolve: it is redrawn as horizontal strands that sway and drift upward
 * with increasing blur, while a grid of cells fades out at pseudo-random times. Finally the remaining content
 * fades over [CLEAR_MS], and the slot collapses over [FALL_MS].
 */
object Evanesce {
    /** Delay before the card begins to fade. */
    const val HOLD_MS = 150
    /** Duration of the card background fade; the content dissolve starts at its midpoint ([SMOKE_AT]). */
    const val CARD_MS = 450
    const val SMOKE_AT = HOLD_MS + CARD_MS / 2
    /** Duration of the content dissolve. */
    const val DISSOLVE_MS = 1500
    /** Duration of the final fade of the content to zero opacity. */
    const val CLEAR_MS = 300
    const val TOTAL_MS = SMOKE_AT + DISSOLVE_MS + CLEAR_MS

    /**
     * Duration of the slot collapse after the dissolve, with accelerating easing. Content above moves down
     * into the space; content below does not move.
     */
    const val FALL_MS = 380

    /** Strand height, horizontal sway wavelength (along y) and maximum sway amplitude. */
    val STRAND = 2.dp
    val WAVE = 16.dp
    val SWAY = 9.dp
    /** Maximum upward drift; [BLUR] is the final blur radius. */
    val DRIFT = 12.dp
    /** Number of fainter trailing copies drawn above the content, and the vertical offset between them. */
    const val TRAILS = 2
    val TRAIL = 3.5.dp
    val BLUR = 12.dp
    /** Side of the square cells that fade out individually (edges are softened by the blur). */
    val CELL = 4.dp

    /** A cell starts fading between [FIRST] and [FIRST] + [SPREAD] of the dissolve and fades over [FADE] of it. */
    private const val FIRST = 0.25f
    private const val SPREAD = 0.5f
    private const val FADE = 0.35f

    /** Maps overall progress [t] (0..1 of [TOTAL_MS]) to dissolve progress (0..1), starting at [SMOKE_AT]. */
    fun dissolve(t: Float) = ((t * TOTAL_MS - SMOKE_AT) / DISSOLVE_MS).coerceIn(0f, 1f)

    /** Returns the card background opacity at [t]: 1 during [HOLD_MS], then a smoothstep fade over [CARD_MS]. */
    fun alpha(t: Float): Float {
        val c = ((t * TOTAL_MS - HOLD_MS) / CARD_MS).coerceIn(0f, 1f)
        return 1f - c * c * (3f - 2f * c)
    }

    /** Returns the content opacity at [t]: 1 until the last [CLEAR_MS], then linear to 0. */
    fun words(t: Float) = 1f - ((t * TOTAL_MS - SMOKE_AT - DISSOLVE_MS) / CLEAR_MS).coerceIn(0f, 1f)

    /** Returns a cell's opacity at [t], given its start [delay] (0..1) within the dissolve window. */
    fun cell(t: Float, delay: Float): Float = 1f - ((dissolve(t) - FIRST - delay * SPREAD) / FADE).coerceIn(0f, 1f)

    /**
     * Returns the horizontal sway and the upward rise of strand [i] of [n] at height [y] (px), in units of [SWAY]
     * and [DRIFT]. Both grow as d^1.5 of the dissolve progress; upper strands (lower [i]) rise faster.
     */
    fun strand(t: Float, i: Int, n: Int, y: Float, wave: Float): Pair<Float, Float> {
        val d = dissolve(t)
        val own = noise(i)
        val sway = d * kotlin.math.sqrt(d) * kotlin.math.sin(6.2832f * (y / wave + own) + d * 5f)
        val rise = d * kotlin.math.sqrt(d) * (0.55f + 0.45f * own) * (1.25f - 0.5f * i / n.coerceAtLeast(1))
        return sway to rise
    }

    /** Returns the trail strength at [t]: 4d(1 - d), zero at both ends and 1 at the middle of the dissolve. */
    fun trail(t: Float): Float { val d = dissolve(t); return 4f * d * (1f - d) }

    /** Returns a deterministic pseudo-random value in 0..1 for strand [i]. */
    fun noise(i: Int) = delay(i, 7919)

    /** Returns a deterministic pseudo-random value in 0..1 for cell ([x], [y]), from an integer hash with no visible pattern. */
    fun delay(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }
}

/** Layer paints for [dissolve], allocated once; safe to share because drawing happens on the main thread. */
private val Whole = androidx.compose.ui.graphics.Paint()
private val Faint = androidx.compose.ui.graphics.Paint()

/** Applies the [Evanesce] card opacity at [t] (0..1) to the card background; no-op at 0. [still] is unused. */
fun Modifier.evanesce(t: Float, @Suppress("UNUSED_PARAMETER") still: Boolean = false): Modifier =
    if (t <= 0f) this else graphicsLayer { alpha = Evanesce.alpha(t) }


/**
 * Applies the [Evanesce] content dissolve at [t] (0..1): the content is redrawn in horizontal strands, each offset
 * by its sway and rise, with trailing copies and a growing blur, then masked cell by cell. No-op at 0 or when
 * [still] (reduced motion), in which case the content only fades with the card.
 */
fun Modifier.dissolve(t: Float, still: Boolean = false): Modifier =
    if (t <= 0f || still) this
    else this
        .graphicsLayer {
            alpha = Evanesce.words(t)
            // Blur radius grows as d^1.5. RenderEffect requires API 31; older devices skip the blur.
            val d = Evanesce.dissolve(t)
            val blur = Evanesce.BLUR.toPx() * d * kotlin.math.sqrt(d)
            if (blur > 0.3f && android.os.Build.VERSION.SDK_INT >= 31) renderEffect = androidx.compose.ui.graphics.BlurEffect(blur, blur)
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            clip = false
        }
        .drawWithContent {
            if (Evanesce.dissolve(t) <= 0f) return@drawWithContent drawContent()
            val strand = Evanesce.STRAND.toPx()
            val wave = Evanesce.WAVE.toPx()
            val n = (size.height / strand).toInt() + 1
            // Draws the trailing copies first (c > 0: fainter and higher), then the main copy (c = 0).
            val trail = Evanesce.trail(t)
            for (c in Evanesce.TRAILS downTo 0) {
                if (c > 0 && trail < 0.02f) continue
                val above = c * Evanesce.TRAIL.toPx()
                drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), if (c == 0) Whole else Faint.apply { alpha = 0.3f * trail / c })
                for (i in 0 until n) {
                    val top = i * strand
                    val (sway, rise) = Evanesce.strand(t, i + 31 * c, n, top, wave)
                    val dy = -rise * Evanesce.DRIFT.toPx() - above * Evanesce.dissolve(t)
                    // Clips to the strand's displaced band and redraws the full content translated into it.
                    clipRect(-size.width, top + dy, size.width * 2, top + dy + strand + 0.5f) {
                        translate(sway * Evanesce.SWAY.toPx() * (1f + 0.4f * c), dy) { this@drawWithContent.drawContent() }
                    }
                }
                drawContext.canvas.restore()
            }
            // Masks each cell into the offscreen layer's alpha with DstIn; no intermediate bitmap is allocated.
            // The grid extends [lift] above the bounds to cover the drifted strands.
            val side = Evanesce.CELL.toPx()
            val cols = (size.width / side).toInt() + 1
            val lift = 1.25f * Evanesce.DRIFT.toPx() + Evanesce.TRAILS * Evanesce.TRAIL.toPx()
            val rows = ((size.height + lift) / side).toInt() + 1
            for (y in 0 until rows) for (x in 0 until cols) {
                // Cell delay mixes a hash with the row, so cells start fading from the top down.
                val delay = 0.6f * Evanesce.delay(x, y) + 0.4f * y / rows
                val a = Evanesce.cell(t, delay)
                if (a < 1f) drawRect(Color.Black.copy(alpha = a), Offset(x * side, y * side - lift), Size(side, side), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            }
        }

/**
 * Renders [items] keyed by [key]. An item removed from [items] stays at its previous position while it plays the
 * [Evanesce] animation and its slot collapses, then is dropped; new items appear immediately. Items for which
 * [plain] is true (placeholders) are removed without animation.
 *
 * [content] receives the item, whether it is leaving (and should ignore input), a modifier for the card
 * background ([evanesce]) and one for its content ([dissolve]). Leaving items have their semantics cleared.
 */
@Composable
fun <T> Evanescing(items: List<T>, key: (T) -> Any, plain: (T) -> Boolean = { false }, content: @Composable (T, Boolean, Modifier, Modifier) -> Unit) {
    // The previously shown list, in order; a plain list rather than state because it is rebuilt every composition.
    // [done] holds keys whose exit animation has finished.
    val history = remember { mutableListOf<T>() }
    var done by remember { mutableStateOf(emptySet<Any>()) }
    val keys = items.map(key).toSet()
    val shown = buildList {
        addAll(items)
        // Reinserts leaving items at their previous index.
        history.forEachIndexed { i, old -> val k = key(old); if (k !in keys && k !in done && !plain(old)) add(i.coerceAtMost(size), old) }
    }
    history.clear(); history.addAll(shown)
    val still = stillness()
    shown.forEach { item ->
        val k = key(item)
        androidx.compose.runtime.key(k) {
            val gone = k !in keys
            val t = remember { Animatable(0f) }
            val fall = remember { Animatable(0f) }
            LaunchedEffect(gone) {
                if (!gone) { t.snapTo(0f); fall.snapTo(0f); done = done - k; return@LaunchedEffect }
                t.animateTo(1f, tween(Evanesce.TOTAL_MS, easing = LinearEasing))
                if (!still) fall.animateTo(1f, tween(Evanesce.FALL_MS, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                done = done + k
            }
            // Shrinks the measured height by the fall progress while keeping the content bottom-aligned, so content
            // below this slot does not move.
            Box(Modifier.layout { m, c ->
                val p = m.measure(c)
                val h = (p.height * (1f - fall.value)).roundToInt()
                layout(p.width, h) { p.place(0, h - p.height) }
            }.clipToBounds()) {
                content(item, gone, Modifier.evanesce(t.value, still).let { if (gone) it.clearAndSetSemantics {} else it }, Modifier.dissolve(t.value, still))
            }
        }
    }
}

/** Returns a callback that performs a context-click haptic on the current view. */
@Composable
fun haptic(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
}

/** Fills the screen with the palette background, its radial lights scaled by [glow], and a 22 dp dot grid. */
@Composable
fun Atmosphere(glow: Float = 1f, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink.void)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            Ink.palette.lights.forEach { l ->
                drawRect(Brush.radialGradient(listOf(l.color.copy(alpha = l.alpha * glow), Color.Transparent), Offset(w * l.x, h * l.y), w * l.radius))
            }
            val step = 22.dp.toPx()
            val dot = Ink.mist.copy(alpha = 0.035f)
            var y = step / 2
            while (y < h) {
                var x = step / 2
                while (x < w) { drawCircle(dot, 0.9f, Offset(x, y)); x += step }
                y += step
            }
        }
        content()
    }
}

/** Visual state of the Presence Mark. */
enum class Presence { RESTING, LISTENING, PROCESSING, SPEAKING, PAUSED, OFFLINE }

/** Returns whether the system animator duration scale is 0 (animations off). */
@Composable
fun stillness(): Boolean {
    val context = LocalContext.current
    // Re-read on every [Ui.tick], since the setting can change while the screen is visible.
    return remember(Ui.tick) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** Draws a blurred copy of a shape via [BlurMaskFilter], to be layered under the sharp shape as a glow. */
private fun DrawScope.glow(color: Color, radius: Float, draw: (android.graphics.Canvas, android.graphics.Paint) -> Unit) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        draw(canvas.nativeCanvas, paint)
    }
}

/** Start angle and sweep of the mark's open ring (about 297 degrees, gap near the two o'clock position), per the brand geometry. */
private const val RING_START = 8.7f

private const val RING_SWEEP = 296.7f

/**
 * Draws the Presence Mark (docs/specs/presence-mark.md): an open gradient ring around a glowing core.
 * Per [state]: RESTING breathes slowly; LISTENING (microphone on) emits expanding rings from the core;
 * PROCESSING rotates the ring and shifts the core's color; SPEAKING pulses with the voice; PAUSED is static
 * and grey; OFFLINE draws a dashed ring with an empty core. Animation stops under reduced motion.
 */
@Composable
fun PresenceSymbol(state: Presence, size: Dp, modifier: Modifier = Modifier, pressed: Boolean = false) {
    val reduced = stillness()
    // During the splash transition the mark is drawn identical to the splash's (resting, static, splash
    // colors); [alive] then eases animation, colors and light in over one second.
    val alive by animateFloatAsState(if (Ui.splashing) 0f else 1f, tween(1_000), label = "alive")
    val still = reduced || Ui.splashing || state == Presence.PAUSED || state == Presence.OFFLINE
    val transition = rememberInfiniteTransition(label = "presence")
    val beat by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(when (state) { Presence.LISTENING -> 1600; Presence.PROCESSING -> 2600; Presence.SPEAKING -> 1100; else -> 4000 }, easing = LinearEasing)),
        label = "beat",
    )
    val t = if (still) 0.25f else beat
    val breath = alive * (sin(2 * PI * t).toFloat() + 1) / 2
    val grey = state == Presence.PAUSED || state == Presence.OFFLINE
    // Any instance of the mark shows the speaking state while speech is playing, regardless of the state
    // its screen passed, unless a pose override is active.
    val speaking = (state == Presence.SPEAKING || (Ui.speaking && Ui.pose == null)) && !reduced && !Ui.splashing

    // After the splash lands, the ring LEDs light one by one at full intensity as in the splash, then ease
    // to their normal levels.
    val waking = remember { Animatable(1f) }
    val woken = remember { Animatable(0f) }
    LaunchedEffect(Ui.wokeAt) {
        if (Ui.wokeAt == 0L || System.currentTimeMillis() - Ui.wokeAt > 3_000) return@LaunchedEffect
        woken.snapTo(1f)
        if (Ui.wokeBare) {
            waking.snapTo(0f)
            waking.animateTo(1f, tween(RING_LEDS * 24 + 180, delayMillis = 500, easing = LinearEasing))
        }
        woken.animateTo(0f, tween(1_200, delayMillis = 600, easing = FastOutSlowInEasing))
    }

    // While speaking, each word about to be spoken ([Ui.word]) sets [kick] to 1, decaying over 360 ms;
    // the voice waveform uses it as the speech level.
    val kick = remember { Animatable(0f) }
    LaunchedEffect(Ui.word) {
        if (!speaking) return@LaunchedEffect
        kick.snapTo(1f)
        kick.animateTo(0f, tween(360, easing = FastOutSlowInEasing))
    }
    // Baseline level between words: a product of two sines at unrelated frequencies, so it does not look periodic.
    val swell = if (speaking) 0.25f + 0.25f * ((sin(2 * PI * t * 3).toFloat() * sin(2 * PI * t * 1.7f).toFloat()) + 1) / 2 else 0f
    val level = if (speaking) maxOf(kick.value, swell) else 0f
    // Microphone level, smoothed over 120 ms to avoid flicker. With the debug [Ui.pose] override set to
    // LISTENING there is no real input, so a synthetic sine level is used.
    val made = if (Ui.pose == Presence.LISTENING) 0.35f + 0.3f * sin(2 * PI * t * 2).toFloat() else 0f
    val heard by animateFloatAsState(if (state == Presence.LISTENING) maxOf(Ui.micLevel, made) else 0f, tween(120), label = "heard")

    // The ring is a row of LEDs along the arc. Per state: rotation speed (rad/s, PROCESSING only) and the
    // amplitude of a brightness wave running along it. Both animate between states, and the rotation angle
    // only increases, so there are no visible jumps.
    val (ledSpeed, ledWave) = when (state) {
        Presence.PROCESSING -> 1.6f to 1f
        Presence.LISTENING -> 0f to 0.45f
        Presence.SPEAKING -> 0f to 0.3f
        Presence.RESTING -> 0f to 0.18f
        else -> 0f to 0f
    }
    val speedNow by animateFloatAsState(if (reduced) 0f else ledSpeed, tween(900), label = "ledSpeed")
    val waveNow by animateFloatAsState(if (reduced) 0f else ledWave, tween(900), label = "ledWave")
    val orbit = remember { mutableFloatStateOf(0f) }
    val clock = remember { mutableFloatStateOf(0f) }
    // A single frame loop, active unless reduced motion or paused; it stops when the screen stops producing
    // frames. [orbit] and [clock] are read only in the draw phase, so updates invalidate drawing, not composition.
    val turning by androidx.compose.runtime.rememberUpdatedState(state == Presence.PROCESSING)
    LaunchedEffect(reduced, state == Presence.PAUSED) {
        if (reduced || state == Presence.PAUSED) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) {
                val dt = (now - last) / 1e9f
                // After PROCESSING ends, keeps rotating (at least 0.8 rad/s) until the angle wraps, then rests at 0.
                val o = orbit.floatValue
                val v = when { turning -> speedNow; o == 0f -> 0f; else -> maxOf(speedNow, 0.8f) }
                val next = o + v * dt
                orbit.floatValue = if (!turning && next >= 2 * PI.toFloat()) 0f else next % (2 * PI.toFloat())
                clock.floatValue = (clock.floatValue + dt) % 3600f
            }
            last = now
        }
    }

    // Idle animation while RESTING: random gaze shifts (the ring's start angle follows gazeX), blinks, a
    // spring squash when pressed, and slower, half-closed behavior from 23:00 to 06:00. Event reactions
    // follow below. All disabled when the mark is static.
    val life = !still
    val gazeX = remember { Animatable(0f) }
    val gazeY = remember { Animatable(0f) }
    val blink = remember { Animatable(1f) }
    val sleepy = life && state == Presence.RESTING && (Ink.hour >= 23 || Ink.hour < 6)
    val squash by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.35f, stiffness = 500f), label = "squash")
    LaunchedEffect(state, life, pressed) {
        if (!life || state != Presence.RESTING || pressed) {
            launch { gazeX.animateTo(0f, spring(stiffness = 120f)) }
            gazeY.animateTo(0f, spring(stiffness = 120f))
            return@LaunchedEffect
        }
        while (true) {
            delay(Random.nextLong(1600, 5200) * (if (sleepy) 2 else 1))
            when (Random.nextInt(10)) {
                in 0..5 -> {
                    val a = Random.nextFloat() * 2 * PI.toFloat()
                    val d = 0.4f + 0.6f * Random.nextFloat()
                    launch { gazeX.animateTo(cos(a) * d, spring(dampingRatio = 0.7f, stiffness = 60f)) }
                    gazeY.animateTo(sin(a) * d * 0.7f, spring(dampingRatio = 0.7f, stiffness = 60f))
                }
                in 6..7 -> {
                    // Recenters the gaze.
                    launch { gazeX.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 80f)) }
                    gazeY.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 80f))
                }
                else -> {
                    // Quick look left, then right, then recenter.
                    gazeX.animateTo(-0.8f, tween(180))
                    delay(260)
                    gazeX.animateTo(0.8f, tween(220))
                    delay(400)
                    gazeX.animateTo(0f, spring(stiffness = 80f))
                }
            }
        }
    }
    LaunchedEffect(life) {
        while (life) {
            delay(Random.nextLong(2800, 7000))
            repeat(if (Random.nextInt(4) == 0) 2 else 1) {
                blink.animateTo(0.08f, tween(70))
                blink.animateTo(1f, tween(120))
                delay(90)
            }
        }
    }
    // One-shot reactions to app events ([Gestures]), expressed through gaze, nod, shake, widening and light
    // pulses instead of a generic progress indicator.
    val nod = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    val widen = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    val sweep = remember { Animatable(-1f) }
    LaunchedEffect(Gestures.last) {
        val (gesture, _) = Gestures.last ?: return@LaunchedEffect
        if (!life) return@LaunchedEffect
        suspend fun nodOnce(depth: Float = 3f) { nod.animateTo(depth, tween(130)); nod.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
        suspend fun light(times: Int, ms: Int) = repeat(times) { pulse.snapTo(1f); pulse.animateTo(0f, tween(ms)) }
        suspend fun look(x: Float, y: Float, ms: Int) { launch { gazeY.animateTo(y, tween(ms)) }; gazeX.animateTo(x, tween(ms)) }
        suspend fun back() { launch { gazeY.animateTo(0f, spring(stiffness = 90f)) }; gazeX.animateTo(0f, spring(stiffness = 90f)) }
        when (gesture) {
            // Incoming message: glance down-left, one light pulse, a nod.
            Gesture.NOTICE -> { look(-0.6f, 0.7f, 220); launch { light(1, 900) }; delay(300); nodOnce(); delay(250); back() }
            // Email: the gaze scans two lines left to right, then recenters and nods.
            Gesture.READ -> {
                look(-0.7f, 0.1f, 200)
                repeat(2) { k -> gazeY.snapTo(0.1f + 0.25f * k); gazeX.animateTo(0.7f, tween(480, easing = LinearEasing)); gazeX.animateTo(-0.7f, tween(150)) }
                back(); nodOnce(2f)
            }
            // Important item: quick blink, core widens, three light pulses.
            Gesture.PERK -> {
                blink.animateTo(0.1f, tween(60)); blink.animateTo(1f, tween(100))
                widen.animateTo(1f, tween(180))
                light(3, 520)
                widen.animateTo(0f, tween(700))
            }
            // Low priority: a single nod.
            Gesture.NOD -> nodOnce()
            // Urgent: core widens, horizontal shake, four light pulses.
            Gesture.ALERT -> {
                launch { widen.animateTo(1f, tween(150)) }
                repeat(4) { shake.animateTo(3f, tween(55)); shake.animateTo(-3f, tween(55)) }
                shake.animateTo(0f, spring(dampingRatio = 0.4f))
                light(4, 450)
                widen.animateTo(0f, tween(600))
            }
            // Awaiting the owner: recenter, partial widen, two slow pulses.
            Gesture.EXPECT -> { back(); widen.animateTo(0.5f, tween(260)); light(2, 900); widen.animateTo(0f, tween(500)) }
            // Task completed: a glint sweeps along the ring twice, with a small nod.
            Gesture.DONE -> { launch { nodOnce(2f) }; sweep.snapTo(0f); sweep.animateTo(2f, tween(1300, easing = LinearEasing)); sweep.snapTo(-1f) }
        }
    }
    val glad = pulse.value
    // Color semantics ([Bond]): the ring's strands reflect how much memory exists about the owner; the halo
    // reflects pending load (reminders, approvals, outbox, important mail, unread messages).
    val bond = Ui.shownBond ?: Bond.of { Memory.depth(it) }
    val load = Bond.load(
        urgent = PhoneReminders.calling, approvals = if (Ui.pending != null) 1 else 0, outgoing = Guard.outbox.waiting().size,
        importantMail = Mailbox.mails.count { it.important && it.id !in Mailbox.opened }, unread = Inbox.unread().size,
    )

    // While speaking, [talk] goes to 1: the button scales vertically by (1 - 0.85 * talk) and fades while the
    // voice waveform fades in; reversed when speech stops. The debug [Ui.keepMark] flag keeps the mark instead.
    val talk by animateFloatAsState(if (speaking && !Ui.keepMark) 1f else 0f, tween(if (speaking) 420 else 650, easing = FastOutSlowInEasing), label = "talk")
    val layer = remember { androidx.compose.ui.graphics.Paint() }
    val wave = remember { androidx.compose.ui.graphics.Path() }

    // Debug [Ui.face] override: an image drawn in place of the button, crossfaded over 1.2 s.
    val faced by animateFloatAsState(if (Ui.face != null) 1f else 0f, tween(1_200), label = "face")
    val face = remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    Ui.face?.let { face.value = it }
    Box(modifier.size(size), contentAlignment = androidx.compose.ui.Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
        val u = this.size.minDimension / 120f
        val c = center
        val p0 = Ink.palette.let {
            if (alive >= 1f) it else Palettes.default.let { s ->
                it.copy(ringA = androidx.compose.ui.graphics.lerp(s.ringA, it.ringA, alive), ringB = androidx.compose.ui.graphics.lerp(s.ringB, it.ringB, alive), ringC = androidx.compose.ui.graphics.lerp(s.ringC, it.ringC, alive), pearl = androidx.compose.ui.graphics.lerp(s.pearl, it.pearl, alive))
            }
        }
        // With the debug [Ui.shownBond] override, ring colors blend toward the dusk palette's by the ring's lit fraction.
        val p = Ui.shownBond?.let { b -> val s = Palettes.dusk; val x = b.lit
            p0.copy(ringA = androidx.compose.ui.graphics.lerp(p0.ringA, s.ringA, x), ringB = androidx.compose.ui.graphics.lerp(p0.ringB, s.ringB, x), ringC = androidx.compose.ui.graphics.lerp(p0.ringC, s.ringC, x))
        } ?: p0
        val dim = if (sleepy) 0.65f else 1f
        translate(shake.value * u, nod.value * u) {
        val fade = 1f - talk
        if (fade > 0.01f) {
        drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, this.size), layer.apply { alpha = fade })
        scale(1f, 1f - 0.85f * talk, c) {
        scale(squash * (1f + 0.03f * widen.value), c) {
        val r = 40f * u * (1f + 0.012f * breath * (if (life) 1f else 0f))

        // Halo color by load: the palette halo when calm, the wait color when busy, and a pulsing alert color
        // when urgent.
        val (light, more) = when (load) {
            Bond.Load.CALM -> p.halo to 0f
            Bond.Load.BUSY -> p.wait to 0.08f
            Bond.Load.URGENT -> p.alert to 0.18f + 0.18f * sin(2 * PI.toFloat() * t)
        }
        if (!grey) drawCircle(Brush.radialGradient(listOf(light.copy(alpha = ((0.22f + more + 0.08f * breath + 0.3f * glad) * p.glow * dim).coerceIn(0f, 1f)), Color.Transparent), c, 60f * u), 60f * u, c)

        // Raised disc; the last argument is the press depth derived from [squash].
        button(c, u, p, ((1f - squash) / 0.08f).coerceIn(0f, 1f))

        // LISTENING: three expanding rings whose radius, stroke and opacity scale with the microphone level.
        if (state == Presence.LISTENING && !still && heard > 0.03f) for (k in 0..2) {
            val q = (t * 2.2f + k / 3f) % 1f
            drawCircle(Ink.signal.copy(alpha = (0.65f * heard * (1 - q)).coerceAtMost(0.8f)), (47f + (3f + 10f * heard) * q) * u, c, style = Stroke((1.2f + 1.6f * heard) * u))
        }

        // LED ring along the arc; each LED takes its strand's color, and its brightness from [bond]. Grey when
        // paused or offline; when OFFLINE the LEDs light one by one in a repeating connecting sequence.
        ledRing(
            c, r, u, RING_START + 7f * gazeX.value + Math.toDegrees(orbit.floatValue.toDouble()).toFloat(),
            clock.floatValue, waveNow, bond, p, grey, state == Presence.OFFLINE, dim, waking.value, woken.value, sweep.value,
        )

        // Core radius: breathes, widens on reactions and, with [Ui.keepMark], scales with the speech level.
        val core = when (state) {
            Presence.PROCESSING -> 6.5f + 1f * breath
            else -> 6.5f + 0.4f * breath
        } * u * (1f + 0.35f * widen.value + 0.3f * level * (if (Ui.keepMark) 1f else 0f))
        // The core is offset by 7u along the gaze and its bloom by 4u, for a parallax effect.
        val eye = c + Offset(gazeX.value, gazeY.value) * (7f * u)
        val glow0 = c + Offset(gazeX.value, gazeY.value) * (4f * u)
        // Glass sphere set in the button: back layer, the core clipped to the sphere, then the front layer.
        val ball = EYE * u
        glassBack(c, ball, p)
        clipPath(androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, ball)) }) {
            when (state) {
                Presence.PAUSED -> drawCircle(Ink.slate, core, c)
                Presence.OFFLINE -> drawCircle(Ink.slate, core, c, style = Stroke(1.5f * u))
                else -> {
                    drawCircle(EyeLed.bloom.copy(alpha = (0.14f + 0.10f * breath) * dim), ball, glow0)
                    scale(1f, blink.value * (if (sleepy) 0.55f else 1f), eye) {
                        glow(EyeLed.edge.copy(alpha = 0.95f * dim), 12f * u) { canvas, paint -> canvas.drawCircle(eye.x, eye.y, core, paint) }
                        drawCircle(Brush.radialGradient(listOf(EyeLed.core, EyeLed.core, EyeLed.edge), eye, core), core, eye)
                    }
                }
            }
        }
        glassFront(c, ball, p)
        }
        }
        drawContext.canvas.restore()
        }
        // Voice waveform, replacing the button while speaking.
        if (talk > 0.01f) voice(wave, c, u, clock.floatValue, level, talk, p)
        }
    }
    val video = Ui.faceVideo
    if (video != null || (face.value != null && faced > 0.01f)) {
        // Debug face image or video, framed by a sweep gradient of the dusk palette's ring colors.
        val rim = Palettes.dusk.let { listOf(it.ringA, it.ringB, it.ringC, it.ringA) }
        Box(
            Modifier.size(size * 0.84f).graphicsLayer { alpha = if (video != null) 1f else faced }
                .background(Brush.sweepGradient(rim), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            val inner = Modifier.size(size * 0.805f).clip(androidx.compose.foundation.shape.CircleShape)
            if (video != null) FaceVideo(video, inner)
            else face.value?.let { androidx.compose.foundation.Image(it, null, inner, contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        }
    }
    }
}

/** Plays [file] once inside the mark button with its own audio (debug [Ui.faceVideo] override). */
@Composable
private fun FaceVideo(file: java.io.File, modifier: Modifier) {
    // Shows the first frame as a still until the player renders, so the frame never appears empty.
    val first = remember(file) {
        runCatching { android.media.MediaMetadataRetriever().run { setDataSource(file.path); getFrameAtTime(0).also { release() } } }.getOrNull()?.asImageBitmap()
    }
    first?.let { androidx.compose.foundation.Image(it, null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
    val player = remember(file) { android.media.MediaPlayer() }
    androidx.compose.runtime.DisposableEffect(file) { onDispose { player.release() } }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { context ->
            android.view.TextureView(context).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        runCatching {
                            player.setDataSource(file.path)
                            player.setSurface(android.view.Surface(texture))
                            if (Ui.quietRecorded) player.setVolume(0f, 0f)
                            player.setOnPreparedListener { it.start(); android.util.Log.i(AssistantService.TAG, "face video ${file.name}") }
                            player.prepareAsync()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) {}
                }
            }
        },
        modifier = modifier,
    )
}

/** Number of LEDs along the ring's arc. */
private const val RING_LEDS = 25

/**
 * Draws the raised disc under the mark: a diagonal gradient lit from the top left, a blurred drop shadow and a
 * rim stroke bright at the top and dark at the bottom. [pressed] (0..1) lowers the shadow offset, blur and rim highlight.
 */
private fun DrawScope.button(c: Offset, u: Float, p: Palette, pressed: Float) {
    val radius = 47f * u
    val (lit, shade) = if (p.light) {
        androidx.compose.ui.graphics.lerp(p.glass, Color.White, 0.55f) to androidx.compose.ui.graphics.lerp(p.glass, p.halo, 0.22f)
    } else {
        androidx.compose.ui.graphics.lerp(p.sheet, Color.White, 0.10f) to p.void
    }
    glow(Color.Black.copy(alpha = (if (p.light) 0.16f else 0.45f) * (1f - 0.45f * pressed)), (11f - 5f * pressed) * u) { canvas, paint ->
        canvas.drawCircle(c.x, c.y + (5f - 3f * pressed) * u, radius, paint)
    }
    drawCircle(Brush.linearGradient(listOf(lit, shade), c + Offset(-radius, -radius), c + Offset(radius, radius)), radius, c)
    drawCircle(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.75f * (1f - 0.5f * pressed)), 0.5f to Color.White.copy(alpha = 0f), 1f to Color.Black.copy(alpha = 0.10f),
            startY = c.y - radius, endY = c.y + radius,
        ),
        radius - 0.6f * u, c, style = Stroke(1.2f * u),
    )
}

/**
 * Draws the voice waveform centered at [c]: a main tapered wave plus a faint echo, over four blurred, faster
 * background waves. Amplitude scales with [level]; color interpolates ringC -> ringB -> ringA as [level] rises.
 * [talk] (0..1) scales width, height and opacity from a flat line to full size.
 */
private fun DrawScope.voice(
    path: androidx.compose.ui.graphics.Path, c: Offset, u: Float, clock: Float, level: Float, talk: Float, p: Palette,
    width: Float = 54f * u, tallest: Float = 16f * u,
) {
    val half = width * (0.35f + 0.65f * talk)
    val height = tallest * (0.15f + 0.85f * level) * talk
    val tone = if (level < 0.5f) androidx.compose.ui.graphics.lerp(p.ringC, p.ringB, level * 2f)
    else androidx.compose.ui.graphics.lerp(p.ringB, p.ringA, (level - 0.5f) * 2f)
    fun trace(pitch: Float, pace: Float, shift: Float, tall: Float) {
        path.rewind()
        for (i in 0..72) {
            val x = -1f + 2f * i / 72f
            val taper = (1f - x * x).let { it * it }
            val a = x * pitch * PI.toFloat() + clock * pace + shift
            // Fundamental plus a 2.4x overtone at 0.3 amplitude, with a (1 - x^2)^2 taper toward both ends.
            val y = (sin(a) + 0.3f * sin(2.4f * a + clock * 1.3f)) * taper * height * tall
            if (i == 0) path.moveTo(c.x + x * half, c.y + y) else path.lineTo(c.x + x * half, c.y + y)
        }
    }
    // Background waves: higher frequency, lower opacity, blurred.
    for (m in 0..3) {
        trace(4.2f + 1.3f * m, 7f + 2.3f * m, m * 1.7f, 0.55f + 0.15f * (m % 2))
        glow(tone.copy(alpha = 0.16f * talk), 4.5f * u) { canvas, paint ->
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 3.2f * u
            canvas.drawPath(path.asAndroidPath(), paint)
        }
    }
    // Faint echo, then the main wave.
    trace(2.6f, 5.4f, 2.1f, 0.6f)
    drawPath(path, tone, alpha = 0.35f * talk, style = Stroke(1.1f * u, cap = StrokeCap.Round))
    trace(2.3f, 5.2f, 0f, 1f)
    drawPath(path, tone, alpha = 0.9f * talk, style = Stroke(1.6f * u, cap = StrokeCap.Round))
}

/**
 * Returns the value for position [f] (0..1 along the arc) across three strands: [a], [b], [c] each hold a third,
 * with linear blends over 0.28..0.38 and 0.62..0.72.
 */
internal fun along(f: Float, a: Float, b: Float, c: Float) = when {
    f < 0.28f -> a
    f < 0.38f -> a + (b - a) * (f - 0.28f) / 0.1f
    f < 0.62f -> b
    f < 0.72f -> b + (c - b) * (f - 0.62f) / 0.1f
    else -> c
}

/** Color variant of [along]. */
internal fun along(f: Float, a: Color, b: Color, c: Color) = when {
    f < 0.28f -> a
    f < 0.38f -> androidx.compose.ui.graphics.lerp(a, b, (f - 0.28f) / 0.1f)
    f < 0.62f -> b
    f < 0.72f -> androidx.compose.ui.graphics.lerp(b, c, (f - 0.62f) / 0.1f)
    else -> c
}

/**
 * Returns the color of the LED at [f] (0..1 along the arc). Shared by every place the ring is drawn (the mark and the
 * floating mark) so their colors match.
 */
fun ledColor(f: Float, p: Palette, grey: Boolean): Color = if (grey) Ink.slate else along(f, p.ringA, p.ringB, p.ringC)

/** Returns the brightness of the LED at [f] from its strand's level in [bond]; fixed at 0.7 when [grey]. */
fun ledLight(f: Float, bond: Bond.Ring, grey: Boolean): Float = if (grey) 0.7f else Bond.color(along(f, bond.you, bond.day, bond.world))

/** LED halo as (radius relative to the LED core, alpha); matches the splash screen's rendering. */
val LED_HALO = 2.2f to 0.14f

/** Radius of the glass sphere around the core, in mark units (the mark is 120 units across). */
const val EYE = 16f

/** Draws the layers behind the core: the socket shadow in the button and the sphere's body, lit from the top left. */
fun DrawScope.glassBack(c: Offset, g: Float, p: Palette) {
    drawCircle(Brush.radialGradient(0.78f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent, center = c, radius = g * 1.22f), g * 1.22f, c)
    drawCircle(
        Brush.radialGradient(
            0f to androidx.compose.ui.graphics.lerp(p.sheet, Color.White, 0.16f), 0.55f to p.void, 1f to Color.Black,
            center = c + Offset(-0.3f * g, -0.35f * g), radius = g * 1.45f,
        ),
        g, c,
    )
}

/**
 * Draws the layers over the core: a faint reflected-light arc at the bottom and a thin rim. A specular highlight is
 * deliberately omitted because it rendered as a smudge.
 */
fun DrawScope.glassFront(c: Offset, g: Float, p: Palette) {
    drawArc(p.halo.copy(alpha = 0.12f), 35f, 110f, false, topLeft = c - Offset(g * 0.84f, g * 0.84f), size = androidx.compose.ui.geometry.Size(g * 1.68f, g * 1.68f), style = Stroke(g * 0.07f, cap = StrokeCap.Round))
    drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent, Color.Black.copy(alpha = 0.3f)), c.y - g, c.y + g), g, c, style = Stroke(g * 0.05f))
}

/**
 * Draws [RING_LEDS] LEDs on a circle of radius [r], from [start] degrees along [RING_SWEEP]. [wave] is the amplitude
 * of a brightness wave running along the ring; [connecting] lights the LEDs one by one in a loop; [wake] and [woken]
 * drive the post-splash wake-up; [sweep] (>= 0) positions the completion glint.
 */
private fun DrawScope.ledRing(
    c: Offset, r: Float, u: Float, start: Float, clock: Float, wave: Float,
    bond: Bond.Ring, p: Palette, grey: Boolean, connecting: Boolean, dim: Float, wake: Float = 1f, woken: Float = 0f, sweep: Float = -1f,
) {
    val runner = clock * 2 * PI.toFloat() * 0.5f
    val progress = (clock / 0.12f) % (RING_LEDS + 6)
    for (k in 0 until RING_LEDS) {
        val f = k / (RING_LEDS - 1f)
        val a = ((start + f * RING_SWEEP) * PI / 180f).toFloat()
        val at = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
        val passing = ((cos(f * 2 * PI.toFloat() - runner) + 1) / 2).let { it * it * it * it }
        val lit = if (connecting) (progress - k).coerceIn(0f, 1f) * ((RING_LEDS + 6 - progress) / 3f).coerceIn(0f, 1f) else 1f
        // Brightness from the strand's level in [bond].
        val known = ledLight(f, bond, grey)
        // Post-splash wake-up: [wake] lights LEDs in sequence as the splash did; [woken] holds them at full
        // brightness and then eases to their normal level.
        val awake = ((wake * (RING_LEDS + 7) - k) / 7f).coerceIn(0f, 1f)
        // Drawn as in the splash: a solid core in the strand's color, visible per [lit] and [awake], and a halo
        // whose strength combines the strand's level with the running wave.
        val on = (lit * awake * if (grey) 0.7f else 1f).coerceIn(0f, 1f)
        if (on < 0.01f) continue
        val shine = maxOf((1f - 0.6f * wave + 0.6f * wave * passing) * known * dim, if (grey) 0f else woken).coerceIn(0f, 1f)
        val color = ledColor(f, p, grey)
        val size = 1.8f * u
        drawCircle(color, size * LED_HALO.first, at, alpha = LED_HALO.second * on * (0.5f + 0.5f * shine))
        drawCircle(color, size, at, alpha = on * (0.75f + 0.25f * shine))
        // Completion glint: a Gaussian bump along the arc whitens each LED as it passes. Kept no larger than the LED,
        // since a wider halo made the LEDs look swollen.
        if (sweep >= 0f && !grey) {
            val d = ((sweep % 1f) - f) * 12f
            val boost = kotlin.math.exp(-d * d)
            if (boost > 0.02f) {
                drawCircle(androidx.compose.ui.graphics.lerp(color, Color.White, 0.75f), size * 0.85f, at, alpha = boost)
                drawCircle(Color.White, size * 1.6f, at, alpha = 0.12f * boost)
            }
        }
    }
}

/** Draws the same voice waveform as the mark, sized to fill [modifier]'s bounds, for use in screen titles while speaking. */
@Composable
fun VoiceLine(modifier: Modifier = Modifier) {
    // Under reduced motion the waveform is static: no per-word kick and a constant swell.
    val reduced = stillness()
    val kick = remember { Animatable(0f) }
    LaunchedEffect(Ui.word) {
        if (!Ui.speaking || reduced) return@LaunchedEffect
        kick.snapTo(1f)
        kick.animateTo(0f, tween(360, easing = FastOutSlowInEasing))
    }
    val grow by animateFloatAsState(if (Ui.speaking) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "voiceLine")
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now -> if (last != 0L) clock.floatValue = (clock.floatValue + (now - last) / 1e9f) % 3600f; last = now }
    }
    val path = remember { androidx.compose.ui.graphics.Path() }
    Canvas(modifier) {
        val t = clock.floatValue
        // Baseline level between words: a product of two sines at unrelated frequencies; constant under reduced motion.
        val swell = if (reduced) 0.3f else 0.25f + 0.25f * ((sin(t * 6.3f) * sin(t * 3.7f)) + 1) / 2
        voice(path, center, size.height / 30f, t, maxOf(kick.value, swell), grow, Ink.palette, size.width / 2f * 0.96f, size.height * 0.42f)
    }
}

/**
 * App events the Presence Mark reacts to, each with its own animation in [PresenceSymbol]. [DONE] confirms a
 * completed action (a message sent, a command executed) with a glint that sweeps the ring twice.
 */
enum class Gesture { NOTICE, READ, PERK, NOD, ALERT, EXPECT, DONE }

/**
 * Core colors, independent of the palette, chosen to resemble a cool-white LED: a nearly white center, a saturated
 * cool blue edge and a blue bloom.
 */
object EyeLed {
    val core = Color(0xFFF4FBFF)
    val edge = Color(0xFF7CCBFF)
    val bloom = Color(0xFF4FA8FF)
}

/** Holds the latest [Gesture] with its timestamp; [PresenceSymbol] plays it when it changes. */
object Gestures {
    var last by mutableStateOf<Pair<Gesture, Long>?>(null)
        private set

    /** Posts [gesture] to the main thread. Fails silently on the JVM (unit tests), where there is no main looper. */
    fun play(gesture: Gesture) { runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post { last = gesture to System.nanoTime() } } }
}

/** Text style for the lowercase wordmark: large, with tight negative letter spacing. */
val Wordmark = TextStyle(fontFamily = Inter, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Normal, letterSpacing = (-2.6).sp)

/** Icons not included in the core Material icon set, built from their Material SVG paths. */
object Glyphs {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

    val mic = icon("mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    val micOff = icon("micOff", "M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28zM14.98,11.17c0,-0.06 0.02,-0.11 0.02,-0.17L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v0.18l5.98,5.99zM4.27,3L3,4.27l6.01,6.01L9.01,11c0,1.66 1.33,3 2.99,3 0.22,0 0.44,-0.03 0.65,-0.08l1.66,1.66c-0.71,0.33 -1.5,0.52 -2.31,0.52 -2.76,0 -5.3,-2.1 -5.3,-5.1L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c0.91,-0.13 1.77,-0.45 2.54,-0.9L19.73,21 21,19.73 4.27,3z")
    val home = icon("home", "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z")
    val more = icon("more", "M6,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
    val news = icon("news", "M4,5h16v2H4zM4,10h10v2H4zM4,15h16v2H4zM4,20h10v-0.01V22H4z")
    val pause = icon("pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z")
    val up = icon("up", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
    val pulse = icon("pulse", "M3,13h2.5l2.5,-6 4,12 3,-8 1.5,2H21v-2h-3.5l-2.5,-3.5 -3,8 -4,-12 -3,7.5H3z")
    val shield = icon("shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM12,11.99h7c-0.53,4.12 -3.28,7.79 -7,8.94V12H5V6.3l7,-3.11v8.8z")
    val chat = icon("chat", "M20,2L4,2c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2L22,4c0,-1.1 -0.9,-2 -2,-2zM20,16L6,16l-2,2L4,4h16v12z")
    val photo = icon("photo", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
    val spark = icon("spark", "M12,2l2.4,7.2L22,12l-7.6,2.8L12,22l-2.4,-7.2L2,12l7.6,-2.8z")
    val folder = icon("folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    val list = icon("list", "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z")
}

/**
 * Returns the result of [read], executed on [kotlinx.coroutines.Dispatchers.IO] whenever [key] changes; [initial]
 * until the first read completes. On failure the previous value is kept. Used for calendar and file queries
 * that would otherwise block the main thread.
 */
@Composable
fun <T> loaded(key: Any, initial: T, read: () -> T): T =
    androidx.compose.runtime.produceState(initial, key) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching(read).getOrDefault(value) }
    }.value

/**
 * Draws the Presence Mark as the caller avatar on the reminder call screen. While [ringing], two rings expand
 * outward from it and fade, unless reduced motion is on.
 */
@Composable
fun KsandraLogo(size: Dp, ringing: Boolean, modifier: Modifier = Modifier) {
    val reduced = stillness()
    val waves = rememberInfiniteTransition(label = "call")
    val wave by waves.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "wave")
    Box(modifier.size(size * 1.5f), contentAlignment = androidx.compose.ui.Alignment.Center) {
        // Two rings half a cycle apart, growing to 1.45x the radius while fading out.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.toPx() / 2
            if (ringing && !reduced) for (k in 0..1) {
                val f = (wave + k * 0.5f) % 1f
                drawCircle(Ink.palette.ringB.copy(alpha = 0.35f * (1 - f)), r * (1f + 0.45f * f), center, style = Stroke(2.dp.toPx()))
            }
        }
        PresenceSymbol(if (Ui.speaking) Presence.SPEAKING else Presence.RESTING, size)
    }
}
