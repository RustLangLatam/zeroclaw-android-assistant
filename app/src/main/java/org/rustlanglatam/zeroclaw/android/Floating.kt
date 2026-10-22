package org.rustlanglatam.zeroclaw.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/**
 * Overlay bubble (`TYPE_APPLICATION_OVERLAY`) showing the Presence Mark over other apps while the app is in the
 * background; the owner can disable it.
 *
 * A tap opens the app; a long press opens it with the microphone listening; dragging moves it, and on release it
 * snaps to the nearest horizontal edge. State is conveyed visually rather than with text: static when idle, a pulse
 * and a dot for new messages or pending approvals, a countdown arc while a reply waits in its undo window, and a
 * grey core while the kill switch is on. It animates only when its state changes.
 */
object Floating {

    /** Resting position: [right] selects the screen edge and [height] the vertical position as a fraction (0 to 1). */
    @Volatile var right = true
    @Volatile var height = 0.62f

    /** Moves the mark to the given edge and height (from settings, a voice command or a drag) and persists them. */
    fun place(context: Context, right: Boolean? = null, height: Float? = null) {
        right?.let { this.right = it }
        height?.let { this.height = it.coerceIn(0.08f, 0.9f) }
        Guard.save()
        main.post {
            val v = view ?: return@post
            val p = params ?: return@post
            val metrics = context.resources.displayMetrics
            p.x = if (this.right) metrics.widthPixels - p.width - p.width / 6 else p.width / 6
            p.y = (metrics.heightPixels * this.height).toInt()
            runCatching { context.applicationContext.getSystemService(WindowManager::class.java).updateViewLayout(v, p) }
        }
    }

    /** Whether any of the app's own screens is in the foreground; the mark is shown only when none is. */
    val appVisible get() = screens.get() > 0

    /**
     * Number of the app's screens in the foreground. A counter rather than a flag: when [CallActivity] opens over
     * the main screen it starts before the main screen stops, and a flag cleared by the main screen would bring the
     * mark back over the call.
     */
    private val screens = java.util.concurrent.atomic.AtomicInteger()

    /** Records that one of the app's screens entered ([front] `true`) or left the foreground, and updates the mark. */
    fun shown(context: Context, front: Boolean) {
        if (front) screens.incrementAndGet() else screens.updateAndGet { maxOf(0, it - 1) }
        update(context)
    }

    /** Number of digest messages the owner has seen in the app; entries beyond it count as new for the mark. */
    @Volatile var seen = 0

    private var view: MarkView? = null
    private var params: WindowManager.LayoutParams? = null
    private var peek: PeekView? = null
    private val main = Handler(Looper.getMainLooper())

    /** Whether the overlay permission is granted. */
    fun permitted(context: Context) = Settings.canDrawOverlays(context)

    /** Whether the mark is on screen now. */
    val showing get() = view != null

    /** Shows or hides the mark according to setup, the bubble setting, the overlay permission and app visibility. */
    fun update(context: Context) = main.post {
        val app = context.applicationContext
        val show = Guard.setUp && Guard.bubble && !appVisible && permitted(app)
        if (show && view == null) attach(app)
        if (!show && view != null) detach(app)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach(context: Context) {
        val wm = context.getSystemService(WindowManager::class.java)
        val size = (56 * context.resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Qualified: inside apply, a bare `height` would resolve to LayoutParams.height.
            x = if (Floating.right) context.resources.displayMetrics.widthPixels - size - size / 6 else size / 6
            y = (context.resources.displayMetrics.heightPixels * Floating.height).toInt()
        }
        val mark = MarkView(context)
        mark.contentDescription = t(R.string.floating_x_tap_to_open_hold_to, Policy.ASSISTANT)
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val hold = Runnable { if (!moved) { mark.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); open(context, listen = true) } }
        mark.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                    main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) { moved = true; main.removeCallbacks(hold) }
                    if (moved) {
                        params.x = startX + (e.rawX - downX).toInt(); params.y = startY + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(mark, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(hold)
                    if (moved) {
                        // Snaps to the nearest edge and persists the position.
                        val metrics = context.resources.displayMetrics
                        place(context, right = params.x + size / 2 >= metrics.widthPixels / 2, height = params.y.toFloat() / metrics.heightPixels)
                    } else if (e.action == MotionEvent.ACTION_UP && e.eventTime - e.downTime < ViewConfiguration.getLongPressTimeout()) {
                        open(context, listen = false)
                    }
                }
            }
            true
        }
        runCatching { wm.addView(mark, params) }.onSuccess { view = mark; this.params = params; mark.start() }
    }

    private fun detach(context: Context) {
        view?.let { it.stop(); runCatching { context.getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
        hidePeek(context)
    }

    /**
     * Shows a short notice next to the mark: the source [app]'s icon and one line of [text], for a few seconds.
     * A tap opens the app. No-op when the mark is not shown.
     *
     * The window is touchable on purpose: Android caps untouchable overlays at 80% opacity.
     */
    fun notice(context: Context, text: String, app: String? = null) = main.post {
        val mark = view ?: return@post
        val markParams = params ?: return@post
        val app0 = context.applicationContext
        hidePeek(app0)
        val pill = PeekView(app0, text, app)
        val wm = app0.getSystemService(WindowManager::class.java)
        val width = app0.resources.displayMetrics.widthPixels
        val gap = (6 * app0.resources.displayMetrics.density).toInt()
        val right = markParams.x + mark.width / 2 > width / 2
        val lp = WindowManager.LayoutParams(
            pill.wide, pill.tall + pill.lift, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (right) markParams.x - pill.wide - gap else markParams.x + markParams.width + gap
            // The window extends `lift` pixels above the pill so the dissolve animation can drift upward.
            y = markParams.y + (markParams.height - pill.tall) / 2 - pill.lift
        }
        pill.setOnClickListener { hidePeek(app0); open(app0, listen = false) }
        pill.alpha = 0f
        pill.translationX = if (right) 24f else -24f
        runCatching { wm.addView(pill, lp) }.onSuccess {
            peek = pill
            pill.animate().alpha(1f).translationX(0f).setDuration(320).start()
            // After the timeout it dissolves like the app's cards; with animations disabled it is removed at once.
            main.postDelayed({
                if (peek == pill) ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = Evanesce.TOTAL_MS.toLong()
                    interpolator = null
                    addUpdateListener { pill.going(it.animatedValue as Float) }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) { if (peek == pill) hidePeek(app0) }
                    })
                }.start()
            }, 4500)
        }
    }

    private fun hidePeek(context: Context) {
        peek?.let { runCatching { context.getSystemService(WindowManager::class.java).removeView(it) } }
        peek = null
    }

    /**
     * The notice view: a translucent dark pill with the app's icon and one line of text. It dissolves following
     * [Evanesce]: the background fades first, then the icon and text split into horizontal strands that sway and rise
     * with an increasing blur (API 31+). The per-cell thinning used by the cards is omitted here.
     */
    private class PeekView(context: Context, private val text: String, app: String?) : View(context) {
        private val d = resources.displayMetrics.density
        private val icon = app?.let { runCatching { context.packageManager.getApplicationIcon(it) }.getOrNull() }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13.5f * resources.displayMetrics.scaledDensity; color = (if (Ink.palette.light) Palettes.default.mist else Ink.mist).toInt() }
        private val pad = (14 * d).toInt()
        private val iconSize = if (icon != null) (20 * d).toInt() else 0
        val tall = (40 * d).toInt()
        /** Extra height above the pill for the dissolve animation's upward drift. */
        val lift = ((Evanesce.DRIFT.value * 1.25f + Evanesce.TRAILS * Evanesce.TRAIL.value) * d).toInt()
        private var t = 0f

        /** Sets the dissolve progress [t], from 0 to 1 over [Evanesce.TOTAL_MS]. */
        fun going(t: Float) {
            this.t = t
            // Blur grows super-linearly (dissolve^1.5); the background has mostly faded by then.
            val dissolve = Evanesce.dissolve(t)
            val blur = Evanesce.BLUR.value * d * dissolve * kotlin.math.sqrt(dissolve)
            if (android.os.Build.VERSION.SDK_INT >= 31) setRenderEffect(
                if (blur > 0.3f) android.graphics.RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL) else null,
            )
            invalidate()
        }
        val wide = (pad * 2 + iconSize + (if (icon != null) (8 * d).toInt() else 0) + ink.measureText(text)).toInt()
            .coerceAtMost((resources.displayMetrics.widthPixels * 0.7f).toInt())

        init { contentDescription = text }

        override fun onDraw(canvas: Canvas) {
            val p = Ink.palette
            val r = tall / 2f
            canvas.translate(0f, lift.toFloat())
            // Always dark background with light text, for legibility over any wallpaper or app.
            val glass = (Evanesce.alpha(t) * 255).toInt()
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (if (p.light) Palettes.default.sheet else p.sheet).toInt(); alpha = glass }
            canvas.drawRoundRect(0f, 0f, width.toFloat(), tall.toFloat(), r, r, bg)
            bg.style = Paint.Style.STROKE; bg.strokeWidth = d; bg.color = p.ringB.copy(alpha = 0.45f).toInt(); bg.alpha = bg.alpha * glass / 255
            canvas.drawRoundRect(d / 2, d / 2, width - d / 2, tall - d / 2, r, r, bg)
            if (Evanesce.dissolve(t) <= 0f) return words(canvas)
            // Draws the content once per strand, clipped and offset; the content's alpha drops only at the end.
            val strand = Evanesce.STRAND.value * d
            val n = (tall / strand).toInt() + 1
            val layer = canvas.saveLayerAlpha(null, (Evanesce.words(t) * 255).toInt())
            for (i in 0 until n) {
                val top = i * strand
                val (sway, rise) = Evanesce.strand(t, i, n, top, Evanesce.WAVE.value * d)
                val dy = -rise * Evanesce.DRIFT.value * d
                canvas.save()
                canvas.clipRect(-width.toFloat(), top + dy, width * 2f, top + dy + strand + 0.5f)
                canvas.translate(sway * Evanesce.SWAY.value * d, dy)
                words(canvas)
                canvas.restore()
            }
            canvas.restoreToCount(layer)
        }

        /** Draws the icon and the ellipsized text line. */
        private fun words(canvas: Canvas) {
            var x = pad
            icon?.let {
                it.setBounds(x, (tall - iconSize) / 2, x + iconSize, (tall + iconSize) / 2)
                it.draw(canvas)
                x += iconSize + (8 * d).toInt()
            }
            val shown = android.text.TextUtils.ellipsize(text, android.text.TextPaint(ink), (width - x - pad).toFloat(), android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, x.toFloat(), tall / 2f - (ink.descent() + ink.ascent()) / 2, ink)
        }

        private fun androidx.compose.ui.graphics.Color.toInt() = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
    }

    private fun open(context: Context, listen: Boolean) {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("listen", listen).let(MainActivity::sealed),
        )
    }

    /** The mark view, drawn with the platform canvas: an open ring of LEDs around a glowing core. */
    private class MarkView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(6 * density, BlurMaskFilter.Blur.NORMAL) }

        private val LEDS = 25

        /** Pulse progress from 0 to 1 while a pulse animation runs; 0 otherwise. */
        private var pulse = 0f
        private var animator: ValueAnimator? = null
        private var lastNews = 0
        private val tick = object : Runnable {
            override fun run() {
                Ink.hour = java.time.LocalTime.now().hour
                check()
                // Invalidates only when the displayed state changed (the countdown changes every second while active).
                val now = shown()
                if (now != drawn) { drawn = now; invalidate() }
                postDelayed(this, 1000)
            }
        }

        /** Snapshot of the displayed state, compared each tick to decide whether to redraw. */
        private fun shown(): List<Any?> = listOf(
            Ink.palette.key, Ink.hour, Guard.policy.stopped, news(),
            Guard.outbox.waiting().firstOrNull()?.let { it.id to (System.currentTimeMillis() - it.at) / 1000 },
        )

        private var drawn: List<Any?>? = null

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun start() { lastNews = seen; post(tick) }

        fun stop() { removeCallbacks(tick); animator?.cancel() }

        private fun news() = (Inbox.digest.size - seen).coerceAtLeast(0) + Guard.approvals.count()

        /** On a new message or approval, shows a notice and plays three slow pulses. */
        private fun check() {
            val now = news()
            if (now > lastNews) {
                val entry = Inbox.digest.lastOrNull()
                if ((Inbox.digest.size - seen) > 0 && entry != null && entry.status == Inbox.Status.RECEIVED) notice(context, t(R.string.floating_x_wrote_to_you, entry.sender), entry.app)
                else if (Guard.approvals.count() > 0) notice(context, t(R.string.floating_something_awaits_your_approval))
            }
            if (now > lastNews && animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1600; repeatCount = 2
                    addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) { pulse = 0f; animator = null; invalidate() }
                    })
                    start()
                }
            }
            lastNews = now
        }

        override fun onDraw(canvas: Canvas) {
            val s = width.coerceAtMost(height).toFloat()
            val c = s / 2
            val u = s / 120f
            val paused = Guard.policy.stopped
            // Background disc.
            val p = Ink.palette
            fill.shader = RadialGradient(c, c * 0.8f, c, intArrayOf(p.sheet.copy(alpha = 0.95f).toArgb(), p.void.copy(alpha = 0.95f).toArgb()), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(c, c, c - 1 * density, fill)
            fill.shader = null
            ring.shader = null; ring.strokeWidth = 1 * density; ring.color = 0x33FFFFFF
            canvas.drawCircle(c, c, c - 1.5f * density, ring)

            // Expanding pulse ring.
            if (pulse > 0f) {
                ring.color = Ink.rose.copy(alpha = 0.6f * (1 - pulse)).toArgb(); ring.strokeWidth = 2 * density
                canvas.drawCircle(c, c, (30f + 24f * pulse) * u, ring)
            }

            // Either the LED ring or, while a reply is in its undo window, a countdown arc.
            val r = 34f * u
            val waiting = Guard.outbox.waiting().firstOrNull()
            ring.strokeWidth = 7f * u
            if (waiting != null && !paused) {
                val left = (Guard.undoSeconds - (System.currentTimeMillis() - waiting.at) / 1000f).coerceAtLeast(0f) / Guard.undoSeconds.coerceAtLeast(1)
                ring.shader = null; ring.color = 0x22FFFFFF
                canvas.drawCircle(c, c, r, ring)
                ring.color = Ink.wait.toArgb()
                canvas.drawArc(c - r, c - r, c + r, c + r, -90f, 360f * left, false, ring)
            } else {
                // Same rendering as the in-app button and splash: 25 LEDs along the open arc, each with a solid core in
                // ledColor and a halo whose strength follows the bond strand's depth (ledLight), so all three match.
                val bond = Bond.of { Memory.depth(it) }
                for (k in 0 until LEDS) {
                    val f = k / (LEDS - 1f)
                    val a = Math.toRadians((8.7 + f * 296.7)).toFloat()
                    val x = c + kotlin.math.cos(a) * r
                    val y = c + kotlin.math.sin(a) * r
                    val color = ledColor(f, p, paused)
                    val b = ledLight(f, bond, paused)
                    val size = 2.9f * u
                    fill.color = color.copy(alpha = (LED_HALO.second * (0.5f + 0.5f * b)).coerceIn(0f, 1f)).toArgb(); canvas.drawCircle(x, y, size * LED_HALO.first, fill)
                    fill.color = color.copy(alpha = if (paused) 0.7f else 0.75f + 0.25f * b).toArgb(); canvas.drawCircle(x, y, size, fill)
                }
            }
            ring.shader = null

            // Central eye, as on the in-app button: a shaded glass sphere with the glowing core inside.
            val g = 20f * u
            fill.shader = RadialGradient(c, c, g * 1.22f, intArrayOf(0x73000000, 0x73000000, 0x00000000), floatArrayOf(0f, 0.78f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(c, c, g * 1.22f, fill)
            fill.shader = RadialGradient(c - 0.3f * g, c - 0.35f * g, g * 1.45f,
                intArrayOf(androidx.compose.ui.graphics.lerp(p.sheet, androidx.compose.ui.graphics.Color.White, 0.16f).toArgb(), p.void.toArgb(), 0xFF000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(c, c, g, fill)
            fill.shader = null
            val core = (8f + 2.5f * pulse) * u
            canvas.save()
            canvas.clipPath(android.graphics.Path().apply { addCircle(c, c, g, android.graphics.Path.Direction.CW) })
            if (paused) {
                fill.color = Ink.slate.toArgb(); canvas.drawCircle(c, c, core, fill)
            } else {
                glow.color = EyeLed.edge.copy(alpha = 0.9f).toArgb(); canvas.drawCircle(c, c, core * 1.4f, glow)
                fill.shader = RadialGradient(c, c, core, intArrayOf(EyeLed.core.toArgb(), EyeLed.core.toArgb(), EyeLed.edge.toArgb()), null, Shader.TileMode.CLAMP)
                canvas.drawCircle(c, c, core, fill)
                fill.shader = null
            }
            canvas.restore()
            ring.shader = null; ring.color = p.halo.copy(alpha = 0.12f).toArgb(); ring.strokeWidth = g * 0.07f
            canvas.drawArc(c - g * 0.84f, c - g * 0.84f, c + g * 0.84f, c + g * 0.84f, 35f, 110f, false, ring)
            ring.shader = LinearGradient(c, c - g, c, c + g, intArrayOf(0x47FFFFFF, 0x00FFFFFF, 0x4D000000), null, Shader.TileMode.CLAMP); ring.strokeWidth = g * 0.05f
            canvas.drawCircle(c, c, g, ring); ring.shader = null

            // Unread indicator: messages or approvals since the owner last looked.
            if (!paused && news() > 0) {
                fill.color = Ink.rose.toArgb()
                canvas.drawCircle(s - 15 * u, 15 * u, 9 * u, fill)
            }
        }

        private fun androidx.compose.ui.graphics.Color.toArgb() = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
    }
}
