package com.outshake.shake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShakeFeedbackTest {
    @Test
    fun `feedback describes requests not completed connection`() {
        assertEquals("VPN connection requested", ShakeService.feedbackMessage("Connecting"))
        assertEquals("VPN disconnect requested", ShakeService.feedbackMessage("Disconnecting"))
        assertNull(ShakeService.feedbackMessage("Busy — ignoring shake"))
    }

    @Test
    fun `missing consent asks to open app without a success cue`() {
        assertEquals(ShakeService.Feedback.MESSAGE, ShakeService.feedbackFor("VPN permission required"))
        assertEquals("Open Outshake to grant VPN permission", ShakeService.feedbackMessage("VPN permission required"))
    }

    @Test
    fun `cooldown default is five seconds`() {
        assertEquals(5000L, ShakeDetector.COOLDOWN_MS)
    }

    @Test
    fun `connecting maps to vpn-on cue`() {
        assertEquals(ShakeService.Feedback.VPN_ON, ShakeService.feedbackFor("Connecting"))
    }

    @Test
    fun `disconnecting maps to vpn-off cue`() {
        assertEquals(ShakeService.Feedback.VPN_OFF, ShakeService.feedbackFor("Disconnecting"))
    }

    @Test
    fun `no active profile maps to a message`() {
        assertEquals(ShakeService.Feedback.MESSAGE, ShakeService.feedbackFor("No active profile"))
    }

    @Test
    fun `busy transition produces no cue`() {
        assertEquals(ShakeService.Feedback.NONE, ShakeService.feedbackFor("Busy — ignoring shake"))
    }
}
