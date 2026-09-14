package com.outshake.ui.mascot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * The mascot's little choreography score. No Android objects, randomness, or frame integration:
 * identical inputs produce identical poses, including when a frame is skipped.
 *
 * Coordinates are in the illustrator's 400 x 340 stage. Head and feet are independent joints,
 * rather than a single image being translated up and down.
 */
internal object PigeonRig {
    enum class State { IDLE, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTING, ERROR }

    class Pose {
        var bodyX = 0f
        var bodyY = 0f
        var bodyAngle = 0f
        var stretch = 1f
        var headX = 241f
        var headY = 101f
        var headAngle = -3f
        var wing = 0f
        var tail = 0f
        var tuft = 0f
        var leftStep = 0f
        var rightStep = 0f
        var leftLift = 0f
        var rightLift = 0f
        var gazeX = 0f
        var gazeY = 0f
        var blink = 0f
        var lid = 0f
        var chew = 0f
        var happy = 0f
        var hop = 0f
        var peck = 0f

        fun copyFrom(other: Pose) = blend(other, other, 1f)

        fun blend(a: Pose, b: Pose, fraction: Float) {
            val f = fraction.coerceIn(0f, 1f)
            bodyX = lerp(a.bodyX, b.bodyX, f)
            bodyY = lerp(a.bodyY, b.bodyY, f)
            bodyAngle = lerp(a.bodyAngle, b.bodyAngle, f)
            stretch = lerp(a.stretch, b.stretch, f)
            headX = lerp(a.headX, b.headX, f)
            headY = lerp(a.headY, b.headY, f)
            headAngle = lerp(a.headAngle, b.headAngle, f)
            wing = lerp(a.wing, b.wing, f)
            tail = lerp(a.tail, b.tail, f)
            tuft = lerp(a.tuft, b.tuft, f)
            leftStep = lerp(a.leftStep, b.leftStep, f)
            rightStep = lerp(a.rightStep, b.rightStep, f)
            leftLift = lerp(a.leftLift, b.leftLift, f)
            rightLift = lerp(a.rightLift, b.rightLift, f)
            gazeX = lerp(a.gazeX, b.gazeX, f)
            gazeY = lerp(a.gazeY, b.gazeY, f)
            blink = lerp(a.blink, b.blink, f)
            lid = lerp(a.lid, b.lid, f)
            chew = lerp(a.chew, b.chew, f)
            happy = lerp(a.happy, b.happy, f)
            hop = lerp(a.hop, b.hop, f)
            peck = lerp(a.peck, b.peck, f)
        }
    }

    /** Static poses remain legible without freezing halfway through a hop, blink, or peck. */
    fun resting(state: State, out: Pose) {
        sample(state, 3.45f, 3.45f, out)
        out.blink = 0f
        out.leftStep = 0f
        out.rightStep = 0f
        out.leftLift = 0f
        out.rightLift = 0f
        out.wing = if (state == State.CONNECTING || state == State.RECONNECTING) 12f else 0f
        out.tail = 0f
        out.tuft = 0f
        out.hop = 0f
        out.chew = 0f
    }

    fun sample(state: State, time: Float, stateAge: Float, out: Pose) {
        val t = max(0f, time)
        val c = t % 8.4f
        val breath = sin(t * 2.05f)
        out.bodyX = 0f
        out.bodyY = breath * 1.5f
        out.bodyAngle = breath * .65f
        out.stretch = 1f + breath * .008f
        out.headX = 241f
        out.headY = 101f + sin(t * 2.05f - .4f) * 2f
        out.headAngle = -3f
        out.wing = sin(t * 2.05f - .8f) * .8f
        out.tail = sin(t * 2.05f - 1.4f) * 1.3f
        out.tuft = sin(t * 3.8f - .6f) * 2f
        out.leftStep = 0f
        out.rightStep = 0f
        out.leftLift = 0f
        out.rightLift = 0f
        out.gazeX = 2f
        out.gazeY = 1f
        out.blink = max(pulse(t % 5.7f, 4.25f, .105f), pulse(t % 9.1f, 7.7f, .095f))
        out.lid = 0f
        out.chew = 0f
        out.happy = 0f
        out.hop = 0f
        out.peck = 0f

        when (state) {
            State.IDLE, State.DISCONNECTING -> {
                // Two deliberately punchy steps, then a long "are those for me?" pause.
                val strut = window(c, .35f, 2.7f, .3f)
                val stride = sin((c - .35f) * 7.2f)
                out.bodyX = sin(c * 1.5f) * 4f * strut
                out.bodyY += abs(stride) * -2.5f * strut
                out.headX += stride * 9f * strut
                out.headY += cos((c - .35f) * 7.2f - .65f) * 8f * strut
                out.headAngle += stride * 4f * strut
                out.leftStep = stride * 7f * strut
                out.rightStep = -out.leftStep
                out.leftLift = max(0f, stride) * 6f * strut
                out.rightLift = max(0f, -stride) * 6f * strut
                val viewer = window(c, 3f, 4.7f, .45f)
                out.gazeX -= viewer * 5f
                out.headAngle -= viewer * 6f
                out.headY -= viewer * 4f
                val beg = pulse(c, 5.8f, .85f)
                out.headX += beg * 12f
                out.headY += beg * 11f
                out.headAngle += beg * 10f
                out.gazeY += beg * 3f
                out.tail += sin(c * 15f) * pulse(c, 7.35f, .5f) * 8f
                if (state == State.DISCONNECTING) {
                    val fluff = (1f - (stateAge / 1.15f).coerceIn(0f, 1f))
                    val shake = sin(stateAge * 36f) * fluff
                    out.bodyAngle += shake * 5f
                    out.stretch -= fluff * .045f
                    out.headAngle += sin(stateAge * 36f - .8f) * fluff * 9f
                    out.wing += abs(shake) * 24f
                    out.tuft += shake * 14f
                }
            }
            State.CONNECTED -> {
                // Anticipation, three little bites, chew; another bite, then a two-step.
                val peck = max(
                    max(pulse(c, .95f, .39f), pulse(c, 1.65f, .34f)),
                    max(pulse(c, 2.35f, .35f), pulse(c, 4.8f, .43f))
                )
                out.peck = peck
                out.headX += peck * 34f
                // After the chest's squash/rotation, the beak tip lands on the dish at y≈278.
                out.headY += peck * 108f
                out.headAngle += peck * 58f
                out.bodyAngle += peck * 5.5f
                out.bodyY += peck * 4f
                out.stretch -= peck * .055f
                out.tail -= peck * 9f
                out.gazeY += peck * 5f
                out.blink = max(out.blink, peck * .62f)
                out.chew = window(c, 2.7f, 3.65f, .12f) * (.5f + .5f * sin(t * 24f))
                out.happy = 1f
                out.lid = .07f
                val dance = window(c, 6.1f, 7.55f, .22f)
                val step = sin((c - 6.1f) * 10f)
                out.bodyAngle += step * 3.5f * dance
                out.headAngle -= step * 3f * dance
                out.leftLift = max(0f, step) * 7f * dance
                out.rightLift = max(0f, -step) * 7f * dance
                out.leftStep = step * 3f * dance
                out.rightStep = -out.leftStep
                out.hop = abs(step) * 3.5f * dance
                out.wing += abs(step) * 5f * dance
                out.gazeX -= window(c, 5.5f, 7.8f, .3f) * 4f
            }
            State.CONNECTING, State.RECONNECTING -> {
                val search = sin(t * 1.8f)
                out.headY -= 7f
                out.headX += search * 6f
                out.headAngle = search * 8f - 4f
                out.gazeX = search * 5f
                out.gazeY = -3f + cos(t * 2.1f)
                val flutter = window(c % 2.8f, .55f, 1.35f, .2f)
                out.wing = 8f + (.5f + .5f * sin(t * 37f)) * flutter * 32f
                out.tail += sin(t * 12f) * flutter * 3f
                out.tuft += sin(t * 18f) * flutter * 5f
                out.stretch += .014f
                if (state == State.RECONNECTING) {
                    out.lid = .12f
                    out.headAngle += 4f
                    out.bodyY += 2f
                }
            }
            State.ERROR -> {
                out.headX -= 5f
                out.headY += 26f
                out.headAngle = 10f + breath
                out.bodyY += 5f
                out.stretch -= .045f
                out.wing = -5f
                out.tail = -7f
                out.tuft -= 10f
                out.gazeX = -2f
                out.gazeY = 3f
                out.lid = .34f
            }
        }
        out.tuft += out.headAngle * -.17f
    }

    /** A springy one-shot accent. Zero outside its lifetime; never alters the current mood. */
    fun celebrate(age: Float, out: Pose) {
        if (age < 0f || age > 1.65f) return
        val anticipation = pulse(age, .12f, .12f)
        val leap = pulse(age, .48f, .31f)
        val landing = pulse(age, .84f, .16f)
        val settle = if (age > .84f) {
            sin((age - .84f) * 23f) * exp(-(age - .84f) * 6f)
        } else 0f
        out.hop += leap * 30f
        out.stretch += -.09f * anticipation + .08f * leap - .10f * landing + .025f * settle
        out.bodyY += anticipation * 4f + landing * 3f
        out.wing += leap * 22f
        out.tuft += settle * 9f
        out.headAngle -= leap * 4f
    }

    fun smooth(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun window(t: Float, start: Float, end: Float, ramp: Float): Float =
        smooth((t - start) / ramp) * smooth((end - t) / ramp)

    private fun pulse(t: Float, center: Float, halfWidth: Float): Float {
        val distance = abs(t - center) / halfWidth
        return if (distance >= 1f) 0f else (.5f + .5f * cos(distance * PI.toFloat()))
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
}
