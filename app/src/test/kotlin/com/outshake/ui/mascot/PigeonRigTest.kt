package com.outshake.ui.mascot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PigeonRigTest {
    @Test
    fun `score is deterministic even when sampled out of order`() {
        val first = PigeonRig.Pose()
        val second = PigeonRig.Pose()
        for (state in PigeonRig.State.entries) {
            PigeonRig.sample(state, 6.73f, 6.73f, first)
            PigeonRig.sample(state, .12f, .12f, second)
            PigeonRig.sample(state, 6.73f, 6.73f, second)
            assertArrayEquals(values(first), values(second), 0f)
        }
    }

    @Test
    fun `all joints stay finite and in their useful range through many cycles`() {
        val pose = PigeonRig.Pose()
        for (state in PigeonRig.State.entries) {
            for (frame in 0..2400) {
                val t = frame / 30f
                PigeonRig.sample(state, t, t, pose)
                assertTrue(values(pose).all { it.isFinite() })
                assertTrue(pose.headX in 210f..300f)
                assertTrue(pose.headY in 75f..225f)
                assertTrue(pose.stretch in .85f..1.15f)
                assertTrue(pose.blink in 0f..1f)
                assertTrue(pose.peck in 0f..1f)
                assertTrue(pose.leftLift in 0f..8f)
                assertTrue(pose.rightLift in 0f..8f)
            }
        }
    }

    @Test
    fun `search and failure never show the connected happy expression`() {
        val pose = PigeonRig.Pose()
        for (state in listOf(PigeonRig.State.CONNECTING, PigeonRig.State.RECONNECTING, PigeonRig.State.ERROR)) {
            for (frame in 0..300) {
                PigeonRig.sample(state, frame / 30f, frame / 30f, pose)
                assertEquals(0f, pose.happy, 0f)
                assertEquals(0f, pose.peck, 0f)
                assertEquals(0f, pose.hop, 0f)
            }
        }
        PigeonRig.sample(PigeonRig.State.CONNECTED, 3f, 3f, pose)
        assertEquals(1f, pose.happy, 0f)
    }

    @Test
    fun `peck places beak in the seed dish after all body transforms`() {
        val p = PigeonRig.Pose()
        PigeonRig.sample(PigeonRig.State.CONNECTED, .95f, .95f, p)
        assertEquals(1f, p.peck, .001f)
        val headAngle = p.headAngle * PI.toFloat() / 180f
        var x = p.headX + 59f * cos(headAngle) - 14f * sin(headAngle)
        var y = p.headY + 59f * sin(headAngle) + 14f * cos(headAngle)
        x = 191f + (x - 191f) / p.stretch
        y = 250f + (y - 250f) * p.stretch
        val bodyAngle = p.bodyAngle * PI.toFloat() / 180f
        val finalX = 191f + (x - 191f) * cos(bodyAngle) - (y - 236f) * sin(bodyAngle) + p.bodyX
        val finalY = 236f + (x - 191f) * sin(bodyAngle) + (y - 236f) * cos(bodyAngle) + p.bodyY - p.hop
        assertTrue("beak x = $finalX", finalX in 273f..330f)
        assertTrue("beak y = $finalY", finalY in 270f..287f)
    }

    @Test
    fun `reduced motion is open eyed and firmly grounded for every mood`() {
        val p = PigeonRig.Pose()
        for (state in PigeonRig.State.entries) {
            PigeonRig.resting(state, p)
            assertEquals(0f, p.blink, 0f)
            assertEquals(0f, p.hop, 0f)
            assertEquals(0f, p.peck, 0f)
            assertEquals(0f, p.leftLift, 0f)
            assertEquals(0f, p.rightLift, 0f)
            assertEquals(0f, p.chew, 0f)
        }
    }

    @Test
    fun `disconnect has a one shot fluff and then exactly the idle score`() {
        val disconnected = PigeonRig.Pose()
        val idle = PigeonRig.Pose()
        PigeonRig.sample(PigeonRig.State.DISCONNECTING, .3f, .3f, disconnected)
        PigeonRig.sample(PigeonRig.State.IDLE, .3f, .3f, idle)
        assertFalse(values(disconnected).contentEquals(values(idle)))
        PigeonRig.sample(PigeonRig.State.DISCONNECTING, 8.7f, 8.7f, disconnected)
        PigeonRig.sample(PigeonRig.State.IDLE, 8.7f, 8.7f, idle)
        assertArrayEquals(values(idle), values(disconnected), 0f)
    }

    @Test
    fun `hop is a bounded additive one shot without changing mood expression`() {
        val p = PigeonRig.Pose()
        PigeonRig.resting(PigeonRig.State.IDLE, p)
        val initial = values(p)
        PigeonRig.celebrate(-1f, p)
        assertArrayEquals(initial, values(p), 0f)
        PigeonRig.celebrate(.48f, p)
        assertTrue(p.hop > 29f)
        assertEquals(0f, p.happy, 0f)
        PigeonRig.resting(PigeonRig.State.IDLE, p)
        PigeonRig.celebrate(2f, p)
        assertArrayEquals(initial, values(p), 0f)
    }

    @Test
    fun `pose blending is endpoint exact with a smooth clamped transition`() {
        val a = PigeonRig.Pose()
        val b = PigeonRig.Pose()
        val out = PigeonRig.Pose()
        PigeonRig.resting(PigeonRig.State.IDLE, a)
        PigeonRig.resting(PigeonRig.State.ERROR, b)
        out.blend(a, b, -1f)
        assertArrayEquals(values(a), values(out), 0f)
        out.blend(a, b, 2f)
        assertArrayEquals(values(b), values(out), .00001f)
        out.blend(a, b, PigeonRig.smooth(.5f))
        assertEquals((a.headY + b.headY) / 2f, out.headY, .001f)
        assertEquals(0f, PigeonRig.smooth(-4f), 0f)
        assertEquals(1f, PigeonRig.smooth(4f), 0f)
    }

    private fun values(p: PigeonRig.Pose) = floatArrayOf(
        p.bodyX, p.bodyY, p.bodyAngle, p.stretch, p.headX, p.headY, p.headAngle,
        p.wing, p.tail, p.tuft, p.leftStep, p.rightStep, p.leftLift, p.rightLift,
        p.gazeX, p.gazeY, p.blink, p.lid, p.chew, p.happy, p.hop, p.peck
    )
}
