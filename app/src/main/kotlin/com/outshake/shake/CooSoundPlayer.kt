package com.outshake.shake

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import com.outshake.R
import com.outshake.store.ProfileStore

/**
 * Short, non-looping pigeon feedback shared by the screen and accepted-shake path.
 *
 * Construct early (e.g. onCreate) to preload all samples asynchronously. Call [release] when the
 * owner ends; it is safe to call repeatedly or race with a sensor/load callback. Application context
 * only, no audio focus, background ambience, volume changes, or silent/DND bypass.
 *
 * FEED / REST acknowledge an accepted connect / disconnect request, NOT VPN success.
 * PET is an optional foreground mascot/seed accent, never a connection-status indication.
 */
class CooSoundPlayer(context: Context) {
    enum class Cue { FEED, REST, PET }

    private val app = context.applicationContext
    private val store = ProfileStore(app)
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val lock = Any()
    private val queue = LatestCueQueue<Cue>()
    private val soundIds = mutableMapOf<Cue, Int>()
    private var pool: SoundPool? = null
    private var activeStream = 0
    private var released = false

    init {
        // Hold the same lock as the listener: even a very fast decode cannot beat ID registration.
        synchronized(lock) {
            try {
                val created = SoundPool.Builder()
                    .setMaxStreams(1)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .build()
                pool = created
                created.setOnLoadCompleteListener { source, sampleId, status ->
                    synchronized(lock) {
                        if (!released && pool === source) {
                            val cue = soundIds.entries.firstOrNull { it.value == sampleId }?.key
                            if (cue != null) {
                                queue.onLoaded(cue, status == 0, SystemClock.elapsedRealtime(), allowed())
                                    ?.let(::playLoaded)
                            }
                        }
                    }
                }
                mapOf(
                    Cue.FEED to R.raw.pigeon_feed,
                    Cue.REST to R.raw.pigeon_rest,
                    Cue.PET to R.raw.pigeon_pet
                ).forEach { (cue, resource) ->
                    val id = created.load(app, resource, 1)
                    if (id != 0) soundIds[cue] = id
                    else queue.onLoaded(cue, false, SystemClock.elapsedRealtime(), false)
                }
            } catch (_: RuntimeException) {
                // Optional feedback must never break an accepted VPN request or activity startup.
                release()
            }
        }
    }

    fun play(cue: Cue) {
        synchronized(lock) {
            if (released) return
            queue.request(cue, SystemClock.elapsedRealtime(), allowed())?.let(::playLoaded)
        }
    }

    /** Read live on requests AND decode callbacks: turning sound off while loading cancels playback. */
    private fun allowed(): Boolean = try {
        val manager = audio
        CooPlaybackPolicy.allows(
            soundEnabled = store.soundEnabled,
            normalRinger = manager?.ringerMode == AudioManager.RINGER_MODE_NORMAL,
            notificationAudible = manager != null &&
                manager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0 &&
                !manager.isStreamMute(AudioManager.STREAM_NOTIFICATION)
        )
    } catch (_: RuntimeException) {
        false
    }

    /** Called under lock only; SoundPool and notification policy still enforce system volume/DND. */
    private fun playLoaded(cue: Cue) {
        if (released || !allowed()) return
        val id = soundIds[cue] ?: return
        try {
            val readyPool = pool ?: return
            if (activeStream != 0) readyPool.stop(activeStream)
            val gain = if (cue == Cue.PET) 0.28f else 0.38f
            activeStream = readyPool.play(id, gain, gain, 1, 0, 1f)
        } catch (_: RuntimeException) {
            // Missing/failed audio is silent, not a failed connection.
        }
    }

    /** Call onPause for a screen-owned player: no late first tap after it leaves the foreground. */
    fun stop() {
        synchronized(lock) {
            if (released) return
            queue.clearPending()
            try {
                if (activeStream != 0) pool?.stop(activeStream)
            } catch (_: RuntimeException) {
                // Lifecycle changes must not fail because an optional sound could not stop.
            } finally {
                activeStream = 0
            }
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            queue.release()
            val old = pool
            pool = null
            soundIds.clear()
            activeStream = 0
            try {
                old?.setOnLoadCompleteListener(null)
            } catch (_: RuntimeException) {
                // Always attempt release even if detaching the listener failed.
            } finally {
                try {
                    old?.release() // also stops any currently playing stream
                } catch (_: RuntimeException) {
                    // Teardown is best effort and idempotent.
                }
            }
        }
    }
}
