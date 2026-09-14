package com.outshake.ui

import com.outshake.R
import com.outshake.ui.mascot.PigeonView
import com.outshake.vpn.ConnectionManager.State

/** Explicit, testable presentation contract. A pending request must never look connected. */
data class CourtyardState(
    val status: Int, val title: Int, val body: Int, val caption: Int,
    val action: Int, val mood: PigeonView.Mood
) {
    companion object {
        fun isBusy(state: State) = state == State.CONNECTING ||
            state == State.RECONNECTING || state == State.DISCONNECTING
        fun canConnect(state: State) = state == State.DISCONNECTED || state == State.ERROR

        fun forState(state: State): CourtyardState = when (state) {
            State.DISCONNECTED -> CourtyardState(R.string.status_disconnected, R.string.hero_idle,
                R.string.hero_idle_body, R.string.coo_idle, R.string.feed_connect, PigeonView.Mood.IDLE)
            State.CONNECTING -> CourtyardState(R.string.status_connecting, R.string.hero_connecting,
                R.string.hero_connecting_body, R.string.coo_connecting, R.string.waiting_connect, PigeonView.Mood.CONNECTING)
            State.CONNECTED -> CourtyardState(R.string.status_connected, R.string.hero_connected,
                R.string.hero_connected_body, R.string.coo_connected, R.string.rest_disconnect, PigeonView.Mood.CONNECTED)
            State.RECONNECTING -> CourtyardState(R.string.status_reconnecting, R.string.hero_reconnecting,
                R.string.hero_reconnecting_body, R.string.coo_reconnecting, R.string.waiting_reconnect, PigeonView.Mood.RECONNECTING)
            State.DISCONNECTING -> CourtyardState(R.string.status_disconnecting, R.string.hero_disconnecting,
                R.string.hero_disconnecting_body, R.string.coo_disconnecting, R.string.waiting_disconnect, PigeonView.Mood.DISCONNECTING)
            State.ERROR -> CourtyardState(R.string.status_error, R.string.hero_error,
                R.string.hero_error_body, R.string.coo_error, R.string.feed_retry, PigeonView.Mood.ERROR)
        }
    }
}
