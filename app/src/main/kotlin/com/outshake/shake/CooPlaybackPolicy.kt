package com.outshake.shake

/** Pure, fail-closed audio gate: an app preference never overrides the phone's quiet modes. */
internal object CooPlaybackPolicy {
    fun allows(soundEnabled: Boolean, normalRinger: Boolean, notificationAudible: Boolean): Boolean =
        soundEnabled && normalRinger && notificationAudible
}

/**
 * Async sample loading must not eat the first tap, replay a backlog, or emit a stale action later.
 * The owner serializes access and supplies a monotonic clock. Nothing here depends on Android.
 */
internal class LatestCueQueue<T>(private val expiryMs: Long = 700L) {
    private val loaded = mutableSetOf<T>()
    private val failed = mutableSetOf<T>()
    private var pending: Pair<T, Long>? = null
    private var released = false

    fun request(cue: T, nowMs: Long, allowed: Boolean): T? {
        // Even a muted or immediately playable request supersedes any earlier pending cue.
        pending = null
        if (released || !allowed || cue in failed) return null
        if (cue in loaded) return cue
        pending = cue to nowMs
        return null
    }

    fun onLoaded(cue: T, success: Boolean, nowMs: Long, allowed: Boolean): T? {
        if (released) return null
        if (success) loaded.add(cue) else failed.add(cue)
        val waiting = pending ?: return null
        if (!allowed || nowMs - waiting.second !in 0..expiryMs) {
            pending = null
            return null
        }
        if (waiting.first != cue) return null
        pending = null
        return cue.takeIf { success }
    }

    /** Cancel a foreground request on pause/mute while retaining decoded samples. */
    fun clearPending() {
        pending = null
    }

    fun release() {
        released = true
        clearPending()
        loaded.clear()
        failed.clear()
    }
}
