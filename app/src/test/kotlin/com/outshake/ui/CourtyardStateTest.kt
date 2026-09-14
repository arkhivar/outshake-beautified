package com.outshake.ui

import com.outshake.R
import com.outshake.ui.mascot.PigeonView
import com.outshake.vpn.ConnectionManager.State
import org.junit.Assert.*
import org.junit.Test

class CourtyardStateTest {
    @Test fun `every real service state has its own presentation`() {
        assertEquals(6, State.entries.map { CourtyardState.forState(it).mood }.toSet().size)
        assertEquals(6, State.entries.map { CourtyardState.forState(it).status }.toSet().size)
    }
    @Test fun `only connected feeds the bird and offers disconnect`() {
        State.entries.forEach { state ->
            val copy = CourtyardState.forState(state)
            assertEquals(state == State.CONNECTED, copy.mood == PigeonView.Mood.CONNECTED)
            assertEquals(state == State.CONNECTED, copy.action == R.string.rest_disconnect)
        }
    }
    @Test fun `transitions cannot be toggled or mistaken for success`() {
        listOf(State.CONNECTING, State.RECONNECTING, State.DISCONNECTING).forEach {
            assertTrue(CourtyardState.isBusy(it))
            assertFalse(CourtyardState.canConnect(it))
            assertNotEquals(R.string.status_connected, CourtyardState.forState(it).status)
        }
    }
    @Test fun `only idle and error permit a new connection`() {
        assertEquals(listOf(State.DISCONNECTED, State.ERROR), State.entries.filter { CourtyardState.canConnect(it) })
    }
}
