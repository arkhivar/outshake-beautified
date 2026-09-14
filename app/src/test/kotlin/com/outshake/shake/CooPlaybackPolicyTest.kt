package com.outshake.shake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CooPlaybackPolicyTest {
    private enum class Cue { FEED, REST, PET }

    @Test fun `only an enabled preference and audible normal mode can play`() {
        for (enabled in listOf(false, true)) {
            for (normal in listOf(false, true)) {
                for (audible in listOf(false, true)) {
                    assertEquals(enabled && normal && audible,
                        CooPlaybackPolicy.allows(enabled, normal, audible))
                }
            }
        }
        assertTrue(CooPlaybackPolicy.allows(true, true, true))
    }

    @Test fun `manual mute wins over a normally ringing phone`() {
        assertFalse(CooPlaybackPolicy.allows(false, true, true))
    }

    @Test fun `silent or vibrate wins even with a nonzero notification volume`() {
        assertFalse(CooPlaybackPolicy.allows(true, false, true))
    }

    @Test fun `notification stream mute wins even in normal ringer mode`() {
        assertFalse(CooPlaybackPolicy.allows(true, true, false))
    }

    @Test fun `first tap waits for asynchronous sample load and plays once`() {
        val queue = LatestCueQueue<Cue>()
        assertNull(queue.request(Cue.PET, 100, true))
        assertEquals(Cue.PET, queue.onLoaded(Cue.PET, true, 150, true))
        assertNull(queue.onLoaded(Cue.PET, true, 160, true))
    }

    @Test fun `loaded cue plays immediately`() {
        val queue = LatestCueQueue<Cue>()
        assertNull(queue.onLoaded(Cue.FEED, true, 0, true))
        assertEquals(Cue.FEED, queue.request(Cue.FEED, 100, true))
    }

    @Test fun `pending cues retain the latest only regardless of load order`() {
        val queue = LatestCueQueue<Cue>()
        queue.request(Cue.FEED, 100, true)
        queue.request(Cue.REST, 120, true)
        assertNull(queue.onLoaded(Cue.FEED, true, 130, true))
        assertEquals(Cue.REST, queue.onLoaded(Cue.REST, true, 140, true))
    }

    @Test fun `already loaded new request cancels the old pending cue`() {
        val queue = LatestCueQueue<Cue>()
        queue.onLoaded(Cue.PET, true, 0, true)
        queue.request(Cue.FEED, 100, true)
        assertEquals(Cue.PET, queue.request(Cue.PET, 120, true))
        assertNull(queue.onLoaded(Cue.FEED, true, 130, true))
    }

    @Test fun `old cue expires instead of becoming delayed phantom feedback`() {
        val queue = LatestCueQueue<Cue>()
        queue.request(Cue.FEED, 100, true)
        assertNull(queue.onLoaded(Cue.FEED, true, 801, true))
        // Expiry discards the request, not the loaded sample.
        assertEquals(Cue.FEED, queue.request(Cue.FEED, 900, true))
    }

    @Test fun `expiry boundary permits a recent request`() {
        val queue = LatestCueQueue<Cue>()
        queue.request(Cue.FEED, 100, true)
        assertEquals(Cue.FEED, queue.onLoaded(Cue.FEED, true, 800, true))
    }

    @Test fun `mute during decode discards pending sound permanently`() {
        val queue = LatestCueQueue<Cue>()
        queue.request(Cue.FEED, 100, true)
        assertNull(queue.onLoaded(Cue.PET, true, 120, false))
        assertNull(queue.onLoaded(Cue.FEED, true, 130, true))
    }

    @Test fun `a muted request clears any pending cue`() {
        val queue = LatestCueQueue<Cue>()
        queue.request(Cue.FEED, 100, true)
        assertNull(queue.request(Cue.PET, 120, false))
        assertNull(queue.onLoaded(Cue.FEED, true, 130, true))
    }

    @Test fun `failed load never creates feedback and does not queue retries`() {
        val queue = LatestCueQueue<Cue>()
        queue.request(Cue.FEED, 100, true)
        assertNull(queue.onLoaded(Cue.FEED, false, 120, true))
        assertNull(queue.request(Cue.FEED, 130, true))
        assertNull(queue.onLoaded(Cue.FEED, true, 140, true))
    }

    @Test fun `failure of another sample does not swallow the first tap`() {
        val queue = LatestCueQueue<Cue>()
        queue.request(Cue.PET, 100, true)
        assertNull(queue.onLoaded(Cue.REST, false, 120, true))
        assertEquals(Cue.PET, queue.onLoaded(Cue.PET, true, 130, true))
    }

    @Test fun `release is idempotent and rejects late callbacks and new cues`() {
        val queue = LatestCueQueue<Cue>()
        queue.request(Cue.FEED, 100, true)
        queue.release()
        queue.release()
        assertNull(queue.onLoaded(Cue.FEED, true, 120, true))
        assertNull(queue.request(Cue.FEED, 130, true))
    }

    @Test fun `backwards clock does not replay stale feedback`() {
        val queue = LatestCueQueue<Cue>()
        queue.request(Cue.FEED, 100, true)
        assertNull(queue.onLoaded(Cue.FEED, true, 90, true))
    }

    @Test fun `pause cancels first tap before decode without throwing samples away`() {
        val queue = LatestCueQueue<Cue>()
        queue.request(Cue.PET, 100, true)
        queue.clearPending()
        assertNull(queue.onLoaded(Cue.PET, true, 120, true))
        assertEquals(Cue.PET, queue.request(Cue.PET, 130, true))
    }

    @Test fun `stop retains loaded cues for the next foreground visit`() {
        val queue = LatestCueQueue<Cue>()
        queue.onLoaded(Cue.PET, true, 0, true)
        queue.clearPending()
        queue.clearPending()
        assertEquals(Cue.PET, queue.request(Cue.PET, 130, true))
    }
}
