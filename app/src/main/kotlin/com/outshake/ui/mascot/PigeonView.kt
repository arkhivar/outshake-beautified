package com.outshake.ui.mascot

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * An original articulated courtyard pigeon, drawn entirely with Android Canvas.
 *
 * The host owns click semantics, connection state, accessibility labels and optional sound.
 * This view never connects, disconnects, plays audio, or performs a click by itself.
 *
 * Main-thread API. Animation is capped at 29.4 fps and stops while hidden, detached, paused,
 * reduced-motion enabled, or the system animator duration scale is zero (including API 24).
 */
class PigeonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mood { IDLE, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTING, ERROR }

    /**
     * Optional quiet accent hook for the host. Only connected, foreground, animated pecks;
     * at most once per ten real seconds. Never called by rendering or screenshot tests.
     */
    var onPeck: (() -> Unit)? = null

    private var mood = Mood.IDLE
    private var state = PigeonRig.State.IDLE
    private var motionEnabled = true
    private var sceneActive = true
    private var night = resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private var initialized = false
    private var attached = false
    private var running = false
    private var observingScale = false
    private var durationScale = 1f
    private var lastTickMs = 0L
    private var elapsed = 0f
    private var moodChangedAt = -2f
    private var celebrationAt = -10f
    private var lastPeckMs = -10_000L
    private var previousPeck = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var touchedAt = -10f

    private val pose = PigeonRig.Pose()
    private val desired = PigeonRig.Pose()
    private val transitionFrom = PigeonRig.Pose()
    private val testPose = PigeonRig.Pose()
    private val eventPose = PigeonRig.Pose()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dynamicNeck = Path()
    private val neckLight = Path()
    private val neckShade = Path()
    private val jointPath = Path()
    private val detailPath = Path()

    private val bodyGradient = LinearGradient(
        135f, 159f, 238f, 259f,
        intArrayOf(c("#C8C9F0"), c("#A9AFDC"), c("#7E8CBE")),
        floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP
    )
    private val bellyGradient = RadialGradient(
        221f, 207f, 74f, intArrayOf(c("#DEE0F7"), c("#00DEE0F7")),
        null, Shader.TileMode.CLAMP
    )
    private val wingGradient = LinearGradient(
        139f, 175f, 198f, 244f,
        intArrayOf(c("#ABB4DF"), c("#8997C7"), c("#7382B4")),
        null, Shader.TileMode.CLAMP
    )
    private val neckGradient = LinearGradient(
        196f, 128f, 262f, 190f,
        intArrayOf(c("#8D9ECB"), c("#A6DED4"), c("#7EB9BD"), c("#9B95CB")),
        floatArrayOf(0f, .34f, .69f, 1f), Shader.TileMode.CLAMP
    )
    private val headGradient = LinearGradient(
        -28f, -31f, 35f, 33f,
        intArrayOf(c("#CFD1F0"), c("#B6BCE4"), c("#919CCA")),
        floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
    )
    private val beakGradient = LinearGradient(
        35f, 5f, 59f, 18f,
        intArrayOf(c("#F6BD69"), c("#E98A49")), null, Shader.TileMode.CLAMP
    )
    private val floorLight = RadialGradient(
        210f, 270f, 157f, intArrayOf(c("#5CF8F9EB"), c("#00F8F9EB")),
        null, Shader.TileMode.CLAMP
    )
    private val floorNight = RadialGradient(
        210f, 270f, 155f, intArrayOf(c("#223F5653"), c("#003F5653")),
        null, Shader.TileMode.CLAMP
    )
    private val shadowGradient = RadialGradient(
        0f, 0f, 1f, intArrayOf(c("#38545C62"), c("#15545C62"), Color.TRANSPARENT),
        floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP
    )
    private val dishGradient = LinearGradient(
        283f, 270f, 308f, 293f,
        intArrayOf(c("#D2C7BB"), c("#AAA49D")), null, Shader.TileMode.CLAMP
    )

    // Designed silhouettes, not primitive circles or a bitmap animation.
    private val body = shape {
        moveTo(111f, 198f)
        cubicTo(119f, 173f, 144f, 157f, 176f, 158f)
        cubicTo(199f, 157f, 215f, 169f, 227f, 176f)
        cubicTo(251f, 182f, 266f, 205f, 259f, 226f)
        cubicTo(253f, 253f, 224f, 267f, 191f, 265f)
        cubicTo(155f, 265f, 128f, 248f, 113f, 228f)
        cubicTo(106f, 218f, 106f, 207f, 111f, 198f)
        close()
    }
    private val bodyHighlight = shape {
        moveTo(126f, 187f)
        cubicTo(142f, 168f, 166f, 163f, 188f, 167f)
        cubicTo(179f, 174f, 174f, 180f, 174f, 187f)
        cubicTo(154f, 181f, 140f, 185f, 126f, 192f)
        close()
    }
    private val lowerFeathers = shape {
        moveTo(140f, 243f)
        cubicTo(144f, 250f, 150f, 254f, 157f, 254f)
        moveTo(157f, 249f)
        cubicTo(163f, 257f, 169f, 260f, 178f, 260f)
        moveTo(179f, 254f)
        cubicTo(185f, 261f, 194f, 263f, 202f, 261f)
    }
    private val tail = shape {
        moveTo(139f, 199f)
        cubicTo(117f, 194f, 91f, 179f, 66f, 174f)
        cubicTo(62f, 177f, 69f, 189f, 78f, 196f)
        cubicTo(68f, 191f, 58f, 189f, 58f, 193f)
        cubicTo(69f, 211f, 91f, 228f, 122f, 233f)
        cubicTo(135f, 226f, 142f, 214f, 139f, 199f)
        close()
    }
    private val tailSeams = shape {
        moveTo(74f, 184f)
        cubicTo(88f, 198f, 107f, 210f, 124f, 217f)
        moveTo(70f, 200f)
        cubicTo(84f, 211f, 100f, 218f, 117f, 223f)
    }
    private val wing = shape {
        moveTo(177f, 175f)
        cubicTo(160f, 164f, 137f, 172f, 125f, 188f)
        cubicTo(113f, 205f, 117f, 231f, 134f, 242f)
        cubicTo(149f, 253f, 179f, 252f, 210f, 239f)
        cubicTo(215f, 237f, 215f, 232f, 211f, 230f)
        cubicTo(193f, 219f, 196f, 188f, 177f, 175f)
        close()
    }
    private val wingStripes = arrayOf(
        shape {
            moveTo(130f, 192f)
            cubicTo(131f, 211f, 146f, 228f, 163f, 232f)
            cubicTo(169f, 233f, 172f, 237f, 166f, 240f)
            cubicTo(144f, 239f, 124f, 222f, 123f, 203f)
            cubicTo(123f, 199f, 126f, 194f, 130f, 192f)
            close()
        },
        shape {
            moveTo(146f, 183f)
            cubicTo(148f, 204f, 162f, 220f, 179f, 227f)
            cubicTo(187f, 230f, 187f, 234f, 181f, 236f)
            cubicTo(159f, 232f, 140f, 214f, 138f, 193f)
            cubicTo(138f, 188f, 141f, 184f, 146f, 183f)
            close()
        }
    )
    private val wingFeathers = shape {
        moveTo(162f, 183f)
        cubicTo(171f, 189f, 175f, 200f, 177f, 208f)
        moveTo(172f, 181f)
        cubicTo(181f, 187f, 184f, 199f, 186f, 208f)
        moveTo(176f, 238f)
        cubicTo(187f, 239f, 197f, 235f, 203f, 235f)
        moveTo(179f, 243f)
        cubicTo(190f, 243f, 199f, 240f, 205f, 239f)
    }
    private val farWing = shape {
        moveTo(203f, 182f)
        cubicTo(218f, 154f, 239f, 148f, 247f, 156f)
        cubicTo(252f, 163f, 241f, 180f, 228f, 188f)
        cubicTo(237f, 186f, 238f, 191f, 231f, 195f)
        lineTo(213f, 202f)
        close()
    }
    private val head = shape {
        moveTo(-37f, 0f)
        cubicTo(-41f, -20f, -29f, -36f, -8f, -37f)
        cubicTo(11f, -40f, 34f, -30f, 39f, -12f)
        cubicTo(43f, -2f, 39f, 6f, 37f, 12f)
        cubicTo(38f, 26f, 20f, 37f, 1f, 36f)
        cubicTo(-22f, 37f, -33f, 24f, -37f, 0f)
        close()
    }
    private val headSheen = shape {
        moveTo(-30f, -15f)
        cubicTo(-26f, -30f, -8f, -35f, 8f, -30f)
        cubicTo(-4f, -30f, -19f, -23f, -22f, -12f)
        cubicTo(-25f, -8f, -29f, -8f, -30f, -15f)
        close()
    }
    private val tuftBack = shape {
        moveTo(-23f, -29f)
        cubicTo(-34f, -36f, -36f, -46f, -33f, -50f)
        cubicTo(-29f, -44f, -18f, -42f, -15f, -34f)
        close()
    }
    private val tuftFront = shape {
        moveTo(-16f, -32f)
        cubicTo(-23f, -43f, -20f, -52f, -15f, -55f)
        cubicTo(-16f, -47f, -6f, -42f, -7f, -33f)
        close()
    }
    private val nearEye = shape {
        moveTo(-1f, -9f)
        cubicTo(-1f, -23f, 8f, -29f, 19f, -25f)
        cubicTo(30f, -22f, 34f, -7f, 29f, 6f)
        cubicTo(26f, 15f, 19f, 18f, 11f, 14f)
        cubicTo(3f, 11f, -1f, 3f, -1f, -9f)
        close()
    }
    private val farEye = shape {
        moveTo(-28f, -10f)
        cubicTo(-29f, -23f, -21f, -29f, -13f, -25f)
        cubicTo(-5f, -22f, -3f, -11f, -7f, -2f)
        cubicTo(-10f, 6f, -18f, 9f, -23f, 3f)
        cubicTo(-27f, 0f, -28f, -5f, -28f, -10f)
        close()
    }
    private val beakTop = shape {
        moveTo(32f, 5f)
        cubicTo(40f, 3f, 48f, 7f, 59f, 14f)
        cubicTo(61f, 16f, 57f, 19f, 51f, 19f)
        cubicTo(42f, 18f, 35f, 17f, 31f, 13f)
        close()
    }
    private val beakBottom = shape {
        moveTo(33f, 14f)
        cubicTo(39f, 17f, 50f, 17f, 56f, 16f)
        cubicTo(49f, 25f, 37f, 25f, 33f, 18f)
        close()
    }
    private val cere = shape {
        moveTo(31f, 4f)
        cubicTo(35f, 1f, 41f, 4f, 43f, 8f)
        cubicTo(44f, 10f, 41f, 12f, 38f, 11f)
        lineTo(32f, 11f)
        close()
    }
    private val dishBody = shape {
        moveTo(268f, 278f)
        cubicTo(270f, 291f, 281f, 297f, 301f, 297f)
        cubicTo(320f, 297f, 333f, 290f, 335f, 278f)
        close()
    }
    private val leaf = shape {
        moveTo(69f, 282f)
        cubicTo(53f, 282f, 48f, 272f, 46f, 266f)
        cubicTo(61f, 264f, 72f, 270f, 69f, 282f)
        close()
    }

    private val seedX = floatArrayOf(282f, 292f, 305f, 314f, 323f, 298f, 309f, 289f, 319f, 276f, 261f, 250f, 337f, 346f)
    private val seedY = floatArrayOf(277f, 273f, 280f, 274f, 279f, 278f, 272f, 281f, 283f, 274f, 290f, 302f, 295f, 282f)
    private val seedAngles = floatArrayOf(-25f, 36f, 8f, -40f, 25f, 76f, -12f, 48f, 20f, 67f, -31f, 16f, -47f, 29f)

    private val scaleObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            updateSystemScale()
            updateLoop()
            invalidate()
        }
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!shouldAnimate()) {
                stopLoop()
                return
            }
            val now = SystemClock.uptimeMillis()
            elapsed += ((now - lastTickMs).coerceIn(0L, 100L) / 1000f) / durationScale
            lastTickMs = now
            invalidate()
            dispatchPeckAccent(now)
            if (running && shouldAnimate()) postDelayed(this, FRAME_MS)
        }
    }

    init {
        // Do not set a click listener here: the host's normal View listener remains authoritative.
        setWillNotDraw(false)
        PigeonRig.resting(state, transitionFrom)
        initialized = true
        updateSystemScale()
    }

    fun setMood(mood: Mood) {
        if (this.mood == mood) return
        evaluate(elapsed, pose)
        transitionFrom.copyFrom(pose)
        this.mood = mood
        state = PigeonRig.State.valueOf(mood.name)
        moodChangedAt = elapsed
        previousPeck = 0f
        invalidate()
        updateLoop()
    }

    /** False means a designed, fully open-eyed still pose, not a suspended mid-animation frame. */
    fun setMotionEnabled(enabled: Boolean) {
        if (motionEnabled == enabled) return
        motionEnabled = enabled
        if (!enabled) celebrationAt = -10f
        updateLoop()
        invalidate()
    }

    /** Call with true from onResume, false from onPause. Hidden/detached views stop independently. */
    fun setSceneActive(active: Boolean) {
        if (sceneActive == active) return
        sceneActive = active
        updateLoop()
        if (active) invalidate()
    }

    fun setNightMode(night: Boolean) {
        if (this.night == night) return
        this.night = night
        invalidate()
    }

    /** A host-triggered spring hop with a deterministic handful of seeds. Does not change mood. */
    fun celebrate() {
        if (!shouldAnimate()) return
        celebrationAt = elapsed
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        updateSystemScale()
        try {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false, scaleObserver
            )
            observingScale = true
        } catch (_: SecurityException) {
            // Still honor the sampled system value on attach/resume on restricted devices.
        }
        updateLoop()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stopLoop()
        if (observingScale) {
            context.contentResolver.unregisterContentObserver(scaleObserver)
            observingScale = false
        }
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (initialized) updateLoop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (initialized) updateLoop()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (initialized) {
            if (isVisible) updateLoop() else stopLoop()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setNightMode(newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val wantedWidth = max(suggestedMinimumWidth, (STAGE_WIDTH * density).toInt() + paddingLeft + paddingRight)
        val measuredW = resolveSize(wantedWidth, widthMeasureSpec)
        val wantedHeight = max(suggestedMinimumHeight, (300f * density).toInt() + paddingTop + paddingBottom)
        setMeasuredDimension(measuredW, resolveSize(wantedHeight, heightMeasureSpec))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isEnabled && isClickable && motionAllowed()) {
            val scale = stageScale(width, height)
            if (scale > 0f) {
                val left = paddingLeft + (width - paddingLeft - paddingRight - STAGE_WIDTH * scale) * .5f
                val top = paddingTop + (height - paddingTop - paddingBottom - FRAME_HEIGHT * scale) * .5f -
                    FRAME_TOP * scale
                touchX = ((event.x - left) / scale - pose.headX) / 22f
                touchY = ((event.y - top) / scale - pose.headY) / 28f
                touchedAt = elapsed
            }
        }
        // View handles pressed state, cancellation, click listeners and accessibility clicks.
        return super.onTouchEvent(event)
    }

    // Keep accessibility activation on exactly the same host-owned path as a physical tap.
    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        evaluate(elapsed, pose)
        render(canvas, pose, elapsed, elapsed - celebrationAt, width, height)
    }

    /**
     * Deterministic Canvas seam. Does not advance clocks, enqueue frames, call onPeck or mutate
     * animation state. The current mood is rendered without a transition, as if active since t=0.
     *
     * In Robolectric native graphics: layout(0, 0, 800, 680), create an ARGB_8888 bitmap/Canvas,
     * setMood(...), then renderFrameForTesting(canvas, 0.95f). No looper advancement is needed.
     * The optional accent age renders the hop/scatter at that exact age; -1 disables it.
     * Motion/system settings are deliberately bypassed so golden frames are machine-independent.
     */
    @JvmOverloads
    fun renderFrameForTesting(canvas: Canvas, timeSeconds: Float, celebrationAgeSeconds: Float = -1f) {
        val time = if (timeSeconds.isFinite()) max(0f, timeSeconds) else 0f
        PigeonRig.sample(state, time, time, testPose)
        PigeonRig.celebrate(celebrationAgeSeconds, testPose)
        render(
            canvas, testPose, time, celebrationAgeSeconds,
            if (width > 0) width else canvas.width,
            if (height > 0) height else canvas.height
        )
    }

    private fun evaluate(time: Float, out: PigeonRig.Pose) {
        if (!motionAllowed()) {
            PigeonRig.resting(state, out)
            return
        }
        PigeonRig.sample(state, time, max(0f, time - moodChangedAt), desired)
        out.blend(transitionFrom, desired, PigeonRig.smooth((time - moodChangedAt) / .58f))
        PigeonRig.celebrate(time - celebrationAt, out)
        val glance = (1f - (time - touchedAt) / 1.5f).coerceIn(0f, 1f)
        out.gazeX += (touchX.coerceIn(-5f, 5f) - out.gazeX) * glance
        out.gazeY += (touchY.coerceIn(-4f, 4f) - out.gazeY) * glance
    }

    private fun updateSystemScale() {
        durationScale = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                .let { if (it.isFinite()) it.coerceAtLeast(0f) else 1f }
        } catch (_: SecurityException) {
            1f
        }
    }

    private fun motionAllowed() = motionEnabled && durationScale > 0f
    private fun shouldAnimate() = attached && sceneActive && isShown &&
        windowVisibility == VISIBLE && motionAllowed()

    private fun updateLoop() {
        if (!initialized) return
        if (sceneActive && !running) updateSystemScale()
        if (shouldAnimate()) {
            if (!running) {
                running = true
                lastTickMs = SystemClock.uptimeMillis()
                postDelayed(frame, FRAME_MS)
            }
        } else stopLoop()
    }

    private fun stopLoop() {
        running = false
        removeCallbacks(frame)
    }

    private fun dispatchPeckAccent(now: Long) {
        if (mood != Mood.CONNECTED || elapsed - moodChangedAt < .6f) return
        PigeonRig.sample(state, elapsed, elapsed - moodChangedAt, eventPose)
        if (eventPose.peck > .90f && previousPeck <= .90f && now - lastPeckMs >= 10_000L) {
            lastPeckMs = now
            onPeck?.invoke()
        }
        previousPeck = eventPose.peck
    }

    private fun stageScale(w: Int, h: Int): Float = min(
        (w - paddingLeft - paddingRight).coerceAtLeast(0) / STAGE_WIDTH,
        (h - paddingTop - paddingBottom).coerceAtLeast(0) / FRAME_HEIGHT
    )

    private fun render(canvas: Canvas, p: PigeonRig.Pose, time: Float, accent: Float, w: Int, h: Int) {
        val scale = stageScale(w, h)
        if (scale <= 0f) return
        val saved = canvas.save()
        canvas.translate(
            paddingLeft + (w - paddingLeft - paddingRight - STAGE_WIDTH * scale) * .5f,
            paddingTop + (h - paddingTop - paddingBottom - FRAME_HEIGHT * scale) * .5f -
                FRAME_TOP * scale
        )
        canvas.scale(scale, scale)
        drawCourtyard(canvas, p)
        drawFoot(canvas, p, false)
        drawFoot(canvas, p, true)
        val birdSave = canvas.save()
        canvas.translate(p.bodyX, p.bodyY - p.hop)
        canvas.rotate(p.bodyAngle, 191f, 236f)
        canvas.scale(1f / p.stretch, p.stretch, 191f, 250f)
        drawTail(canvas, p)
        drawFarWing(canvas, p)
        paint(bodyGradient)
        canvas.drawPath(body, fill)
        paint(c("#30F6F4FF"))
        canvas.drawPath(bodyHighlight, fill)
        paint(bellyGradient)
        canvas.drawPath(body, fill)
        drawNeck(canvas, p)
        line(c("#27717CAB"), 1.65f)
        canvas.drawPath(lowerFeathers, stroke)
        drawWing(canvas, p)
        drawHead(canvas, p, time)
        canvas.restoreToCount(birdSave)
        drawDish(canvas)
        if (accent >= .2f && accent <= 1.7f) drawScatter(canvas, accent)
        canvas.restoreToCount(saved)
    }

    private fun drawCourtyard(canvas: Canvas, p: PigeonRig.Pose) {
        paint(if (night) floorNight else floorLight)
        canvas.drawOval(28f, 219f, 380f, 329f, fill)
        // Incomplete rings feel like a courtyard, not an app status indicator.
        line(if (night) c("#244C6760") else c("#3EABB6A4"), .9f)
        canvas.drawArc(30f, 238f, 375f, 327f, 8f, 146f, false, stroke)
        canvas.drawArc(53f, 247f, 352f, 315f, 179f, 154f, false, stroke)
        line(if (night) c("#184C6760") else c("#2AA9B5A2"), .8f)
        canvas.drawArc(7f, 226f, 395f, 338f, 20f, 124f, false, stroke)
        canvas.drawArc(75f, 259f, 330f, 305f, 8f, 98f, false, stroke)
        val save = canvas.save()
        canvas.translate(190f + p.bodyX * .5f, 284f)
        canvas.scale(82f - p.hop * .45f, 13f - p.hop * .10f)
        paint(shadowGradient)
        canvas.drawCircle(0f, 0f, 1f, fill)
        canvas.restoreToCount(save)
        paint(if (night) c("#698876") else c("#A6B59A"))
        canvas.drawPath(leaf, fill)
        line(if (night) c("#94AB8B") else c("#CAD3B7"), 1.2f)
        canvas.drawLine(53f, 271f, 71f, 285f, stroke)
        paint(if (night) c("#465950") else c("#C0C9B4"))
        canvas.drawOval(90f, 301f, 96f, 303f, fill)
        canvas.drawOval(356f, 264f, 360f, 266f, fill)
        canvas.drawOval(225f, 317f, 228f, 319f, fill)
    }

    private fun drawFoot(canvas: Canvas, p: PigeonRig.Pose, near: Boolean) {
        val hipX = (if (near) 204f else 173f) + p.bodyX
        val hipY = (if (near) 249f else 247f) + p.bodyY - p.hop
        val footX = (if (near) 215f else 168f) + (if (near) p.rightStep else p.leftStep)
        val lift = (if (near) p.rightLift else p.leftLift) + p.hop * .8f
        val footY = (if (near) 287f else 282f) - lift
        val kneeX = (hipX + footX) * .5f - 5f
        val kneeY = (hipY + footY) * .5f + 3f
        jointPath.rewind()
        jointPath.moveTo(hipX, hipY)
        jointPath.quadTo(hipX - 3f, hipY + 10f, kneeX, kneeY)
        jointPath.quadTo(kneeX - 1f, footY - 5f, footX, footY - 2f)
        line(if (near) c("#BB6C73") else c("#9D6572"), if (near) 7f else 6f)
        canvas.drawPath(jointPath, stroke)
        line(if (near) c("#EB9990") else c("#BF817F"), 2.3f)
        canvas.drawPath(jointPath, stroke)
        jointPath.rewind()
        jointPath.moveTo(footX, footY - 2f)
        jointPath.cubicTo(footX + 5f, footY - 1f, footX + 11f, footY - 3f, footX + 17f, footY)
        jointPath.moveTo(footX + 1f, footY - 2f)
        jointPath.cubicTo(footX + 5f, footY + 2f, footX + 8f, footY + 4f, footX + 12f, footY + 4f)
        jointPath.moveTo(footX, footY - 2f)
        jointPath.quadTo(footX - 5f, footY - 5f, footX - 9f, footY - 1f)
        line(if (near) c("#D7817E") else c("#AD737A"), 4.5f)
        canvas.drawPath(jointPath, stroke)
        line(if (near) c("#F2B79B") else c("#D2998B"), 2f)
        canvas.drawLine(footX + 15f, footY -.8f, footX + 18f, footY + .3f, stroke)
        canvas.drawLine(footX + 10f, footY + 3.5f, footX + 13f, footY + 4.5f, stroke)
        // Two tiny joint creases, not a straight "stick" leg.
        line(c("#9F6471"), 1.1f)
        canvas.drawLine(kneeX - 1.5f, kneeY + 1f, kneeX + 1.5f, kneeY + 2f, stroke)
    }

    private fun drawTail(canvas: Canvas, p: PigeonRig.Pose) {
        val save = canvas.save()
        canvas.rotate(p.tail, 128f, 218f)
        paint(c("#7A87B4"))
        canvas.drawPath(tail, fill)
        line(c("#57658F"), 3f)
        canvas.drawPath(tailSeams, stroke)
        line(c("#A7B3D6"), 1.5f)
        canvas.drawLine(74f, 183f, 93f, 192f, stroke)
        canvas.restoreToCount(save)
    }

    private fun drawFarWing(canvas: Canvas, p: PigeonRig.Pose) {
        if (p.wing <= 4f) return
        val save = canvas.save()
        canvas.rotate(-p.wing * 1.3f, 208f, 196f)
        paint(c("#7F8FBC"))
        canvas.drawPath(farWing, fill)
        line(c("#A9B7DA"), 2f)
        canvas.drawLine(217f, 182f, 242f, 159f, stroke)
        canvas.restoreToCount(save)
    }

    private fun drawWing(canvas: Canvas, p: PigeonRig.Pose) {
        val save = canvas.save()
        canvas.rotate(p.wing, 160f, 186f)
        // A slim offset occlusion edge gives the overlapping wing real depth.
        canvas.translate(1.5f, 3f)
        paint(c("#2462709F"))
        canvas.drawPath(wing, fill)
        canvas.translate(-1.5f, -3f)
        paint(wingGradient)
        canvas.drawPath(wing, fill)
        paint(c("#4E5D8D"))
        canvas.drawPath(wingStripes[0], fill)
        paint(c("#566593"))
        canvas.drawPath(wingStripes[1], fill)
        line(c("#84BCC5"), 1.8f)
        canvas.drawPath(wingFeathers, stroke)
        detailPath.rewind()
        detailPath.moveTo(130f, 188f)
        detailPath.cubicTo(139f, 177f, 153f, 173f, 167f, 179f)
        line(c("#8CE4E3FA"), 2.4f)
        canvas.drawPath(detailPath, stroke)
        canvas.restoreToCount(save)
    }

    private fun drawNeck(canvas: Canvas, p: PigeonRig.Pose) {
        val hx = p.headX
        val hy = p.headY
        val bend = p.peck
        // The root stays in the chest. Two independent Bézier rails bend into the skull.
        dynamicNeck.rewind()
        dynamicNeck.moveTo(185f, 185f)
        dynamicNeck.cubicTo(204f, 160f - bend * 38f, hx - 39f, hy + 37f - bend * 44f, hx - 30f, hy + 5f)
        dynamicNeck.cubicTo(hx - 19f, hy - 8f, hx + 19f, hy - 1f, hx + 26f, hy + 15f)
        dynamicNeck.cubicTo(hx + 29f, hy + 52f - bend * 38f, 258f + bend * 8f, 194f, 242f, 220f)
        dynamicNeck.cubicTo(227f, 235f, 200f, 227f, 188f, 212f)
        dynamicNeck.cubicTo(181f, 203f, 180f, 194f, 185f, 185f)
        dynamicNeck.close()
        paint(neckGradient)
        canvas.drawPath(dynamicNeck, fill)
        val save = canvas.save()
        canvas.clipPath(dynamicNeck)
        neckShade.rewind()
        neckShade.moveTo(hx + 11f, hy + 17f)
        neckShade.cubicTo(hx + 17f, hy + 67f - bend * 34f, 235f, 203f, 216f, 222f)
        neckShade.cubicTo(238f, 232f, 258f, 205f, 264f, 174f)
        neckShade.lineTo(hx + 37f, hy + 10f)
        neckShade.close()
        paint(c("#269C73B5"))
        canvas.drawPath(neckShade, fill)
        neckLight.rewind()
        neckLight.moveTo(hx - 22f, hy + 24f)
        neckLight.cubicTo(hx - 31f, hy + 57f - bend * 30f, 219f, 177f, 198f, 195f)
        neckLight.cubicTo(205f, 202f, 214f, 201f, 220f, 191f)
        neckLight.cubicTo(238f, 166f, hx - 8f, hy + 59f - bend * 30f, hx - 9f, hy + 27f)
        neckLight.close()
        paint(c("#3FD3F5DA"))
        canvas.drawPath(neckLight, fill)
        // Short iridescent scale-like feathers follow the neck's long axis.
        val angle = kotlin.math.atan2(hy + 24f - 189f, hx - 217f) * 180f / PI.toFloat() + 90f
        for (row in 0..2) {
            val fraction = .24f + row * .18f
            val x = 215f + (hx - 215f) * fraction
            val y = 193f + (hy + 24f - 193f) * fraction
            val featherSave = canvas.save()
            canvas.rotate(angle * .4f, x, y)
            line(if (row == 1) c("#769283C1") else c("#79D8EFDC"), 2.5f)
            detailPath.rewind()
            detailPath.moveTo(x - 13f, y - 2f)
            detailPath.quadTo(x - 10f, y + 3f, x - 6f, y + 3f)
            detailPath.moveTo(x - 2f, y + 1f)
            detailPath.quadTo(x + 2f, y + 6f, x + 6f, y + 4f)
            detailPath.moveTo(x + 9f, y + 2f)
            detailPath.quadTo(x + 12f, y + 5f, x + 15f, y + 2f)
            canvas.drawPath(detailPath, stroke)
            canvas.restoreToCount(featherSave)
        }
        canvas.restoreToCount(save)
    }

    private fun drawHead(canvas: Canvas, p: PigeonRig.Pose, time: Float) {
        val save = canvas.save()
        canvas.translate(p.headX, p.headY)
        canvas.rotate(p.headAngle)
        val tuftSave = canvas.save()
        canvas.rotate(p.tuft, -15f, -32f)
        paint(c("#8998C5"))
        canvas.drawPath(tuftBack, fill)
        canvas.rotate(p.tuft * -.3f, -10f, -33f)
        paint(c("#B6C0E3"))
        canvas.drawPath(tuftFront, fill)
        line(c("#E0DFF5"), 1.2f)
        canvas.drawLine(-16f, -47f, -13f, -40f, stroke)
        canvas.restoreToCount(tuftSave)
        paint(headGradient)
        canvas.drawPath(head, fill)
        paint(c("#64F3F0FF"))
        canvas.drawPath(headSheen, fill)
        drawEye(canvas, p, false)
        drawEye(canvas, p, true)
        // The little cheek is painted into the plumage, not a floating badge.
        paint(c("#66D6A6C2"))
        canvas.drawOval(14f, 19f, 30f, 27f, fill)
        line(c("#77F1D2D5"), 1.6f)
        canvas.drawLine(18f, 21f, 17f, 23f, stroke)
        canvas.drawLine(23f, 22f, 22f, 24f, stroke)
        val beakSave = canvas.save()
        canvas.translate(0f, p.chew * 2.2f)
        paint(c("#C47749"))
        canvas.drawPath(beakBottom, fill)
        canvas.restoreToCount(beakSave)
        paint(beakGradient)
        canvas.drawPath(beakTop, fill)
        line(c("#9A694F"), 1.1f)
        detailPath.rewind()
        detailPath.moveTo(35f, 14f)
        detailPath.quadTo(47f, 18f, 55f, 16f)
        canvas.drawPath(detailPath, stroke)
        paint(c("#ECE2D2"))
        canvas.drawPath(cere, fill)
        paint(c("#BD8557"))
        canvas.drawOval(43f, 10f, 46f, 11.8f, fill)
        // Mouth corner only lifts once connected, never while searching/reconnecting.
        if (p.happy > .05f) {
            line(c("#72749B"), 1.3f)
            detailPath.rewind()
            detailPath.moveTo(29f, 18f)
            detailPath.quadTo(30f, 21f + 2f * p.happy, 34f, 21f)
            canvas.drawPath(detailPath, stroke)
        }
        if (p.chew > .15f) {
            paint(c("#E7BC75"))
            canvas.drawOval(45f, 24f, 48f, 26f, fill)
            canvas.drawOval(49f + sin(time * 8f), 28f, 51f + sin(time * 8f), 30f, fill)
        }
        canvas.restoreToCount(save)
    }

    private fun drawEye(canvas: Canvas, p: PigeonRig.Pose, near: Boolean) {
        val eye = if (near) nearEye else farEye
        val cx = if (near) 15f else -17f
        val cy = if (near) -5f else -11f
        val save = canvas.save()
        // Soft periorbital edge helps the ivory sit inside the sculpted head.
        line(if (near) c("#50848BB8") else c("#40848BB8"), 3f)
        canvas.drawPath(eye, stroke)
        paint(if (near) c("#FFF9E9") else c("#EBEBD8"))
        canvas.drawPath(eye, fill)
        canvas.clipPath(eye)
        val gazeScale = if (near) 1f else .7f
        val px = cx + p.gazeX.coerceIn(-5f, 5f) * gazeScale
        val py = cy + p.gazeY.coerceIn(-4f, 6f) * gazeScale
        paint(c("#D5CDAA"))
        canvas.drawOval(px - 8.4f * gazeScale, py - 11.3f * gazeScale, px + 8.4f * gazeScale, py + 11.3f * gazeScale, fill)
        paint(c("#30394C"))
        canvas.drawOval(px - 6.8f * gazeScale, py - 10f * gazeScale, px + 6.8f * gazeScale, py + 10f * gazeScale, fill)
        paint(c("#FAFFF6"))
        canvas.drawOval(px - 3.9f * gazeScale, py - 7.4f * gazeScale, px -.2f * gazeScale, py - 2.7f * gazeScale, fill)
        paint(c("#7DC9D4D4"))
        canvas.drawOval(px + 2f * gazeScale, py + 4.2f * gazeScale, px + 3.8f * gazeScale, py + 6.5f * gazeScale, fill)
        val closing = max(p.blink, p.lid).coerceIn(0f, 1f)
        if (closing > 0f) {
            val top = -31f
            val bottom = if (near) 18f else 8f
            val lidY = top + (bottom - top) * closing
            paint(headGradient)
            canvas.drawRect(cx - 26f, top - 2f, cx + 26f, lidY, fill)
            line(c("#7C86B1"), 1.7f)
            canvas.drawLine(cx - 24f, lidY, cx + 24f, lidY + if (near) -1f else 1f, stroke)
        }
        if (p.happy > 0f) {
            paint(headGradient)
            val y = (if (near) 17f else 7f) - p.happy * 3.5f
            canvas.drawOval(cx - 19f, y, cx + 19f, y + 20f, fill)
        }
        canvas.restoreToCount(save)
        // Expressive brows: puzzled during search, softened for the sympathetic error droop.
        line(c("#7482AF"), if (near) 2.3f else 1.8f)
        detailPath.rewind()
        val browY = if (near) -30f else -31f
        detailPath.moveTo(cx - 8f, browY - p.lid * 4f)
        detailPath.quadTo(cx, browY - 3f - p.lid * 5f, cx + 7f, browY + p.lid * 3f)
        canvas.drawPath(detailPath, stroke)
    }

    private fun drawDish(canvas: Canvas) {
        paint(if (night) c("#273139") else c("#2A797C71"))
        canvas.drawOval(265f, 286f, 341f, 302f, fill)
        paint(dishGradient)
        canvas.drawPath(dishBody, fill)
        paint(c("#E5DACC"))
        canvas.drawOval(268f, 267f, 335f, 288f, fill)
        paint(c("#ADA393"))
        canvas.drawOval(273f, 270f, 330f, 284f, fill)
        paint(c("#C8B79A"))
        canvas.drawOval(276f, 273f, 328f, 284f, fill)
        for (i in seedX.indices) {
            drawSeed(canvas, seedX[i], seedY[i], seedAngles[i], i)
        }
        line(c("#78FFF0DB"), 1.7f)
        canvas.drawArc(269f, 267f, 334f, 288f, 16f, 149f, false, stroke)
        line(c("#5D8D887F"), 1.3f)
        canvas.drawArc(279f, 282f, 326f, 293f, 21f, 132f, false, stroke)
    }

    private fun drawSeed(canvas: Canvas, x: Float, y: Float, angle: Float, index: Int) {
        val save = canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angle)
        paint(if (index % 3 == 0) c("#A77C50") else if (index % 3 == 1) c("#E5BE78") else c("#E7D4A5"))
        canvas.drawOval(-2.3f, -1.15f, 2.3f, 1.15f, fill)
        line(c("#7AFFF1C7"), .65f)
        canvas.drawLine(-.9f, -.4f, .7f, -.4f, stroke)
        canvas.restoreToCount(save)
    }

    private fun drawScatter(canvas: Canvas, age: Float) {
        val t = age - .2f
        val alpha = ((1.7f - age) / .45f).coerceIn(0f, 1f)
        for (i in 0..10) {
            val direction = i * 2.399963f
            val velocity = 27f + (i % 4) * 12f
            val x = 299f + cos(direction) * velocity * t
            val initialY = -50f - (i % 3) * 15f
            val y = min(289f + (i % 4) * 3f, 278f + initialY * t + 71f * t * t)
            val save = canvas.save()
            canvas.translate(x, y)
            canvas.rotate(i * 31f + t * 210f)
            paint(if (i % 2 == 0) c("#D7AD67") else c("#B88952"))
            fill.alpha = (255 * alpha).toInt()
            canvas.drawOval(-2.2f, -1.1f, 2.2f, 1.1f, fill)
            fill.alpha = 255
            canvas.restoreToCount(save)
        }
    }

    private fun paint(color: Int) {
        fill.shader = null
        fill.color = color
    }

    private fun paint(shader: Shader) {
        fill.color = Color.WHITE
        fill.shader = shader
    }

    private fun line(color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
    }

    private companion object {
        const val STAGE_WIDTH = 400f
        // The geometry's stage is 400 x 340; trim quiet floor/margin for compact phone cards.
        // At 342 x 248 dp this gives the standing bird ~200 dp, with breathing room for its hop.
        const val FRAME_HEIGHT = 310f
        const val FRAME_TOP = 8f
        const val FRAME_MS = 34L

        private val colors = HashMap<String, Int>(96)
        fun c(hex: String): Int = colors.getOrPut(hex) { Color.parseColor(hex) }
        fun shape(block: Path.() -> Unit) = Path().apply(block)
    }
}
