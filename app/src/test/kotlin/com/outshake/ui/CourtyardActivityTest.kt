package com.outshake.ui

import android.app.Application
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.outshake.R
import com.outshake.config.Cipher
import com.outshake.config.Profile
import com.outshake.config.SourceType
import com.outshake.config.TransportConfig
import com.outshake.store.ProfileStore
import com.outshake.ui.mascot.PigeonView
import com.outshake.vpn.ConnectionManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Activity-level regression checks. No physical sensors, VPN consent or live network claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourtyardActivityTest {
    private lateinit var app: Application

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("outshake", Context.MODE_PRIVATE).edit().clear().commit()
        ProfileStore(app).apply {
            onboardingComplete = true
            shakeEnabled = false
            soundEnabled = false
            reducedMotion = true
        }
        ConnectionManager.onDisconnected()
    }

    @Test fun `empty courtyard opens import rather than requesting a tunnel`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<MaterialButton>(R.id.sceneAction).performClick()
        val launched = shadowOf(activity).nextStartedActivity
        assertEquals(ImportActivity::class.java.name, launched.component!!.className)
        assertEquals(ConnectionManager.State.DISCONNECTED, ConnectionManager.state.value)
        controller.pause().stop().destroy()
    }

    @Test fun `real activity renders service changes and locks transitioning input`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        ConnectionManager.onReconnecting()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(activity.getString(R.string.status_reconnecting),
            activity.findViewById<TextView>(R.id.statusText).text.toString())
        assertFalse(activity.findViewById<MaterialButton>(R.id.sceneAction).isEnabled)
        assertFalse(activity.findViewById<PigeonView>(R.id.pigeonScene).isEnabled)
        ConnectionManager.onConnected()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(activity.getString(R.string.status_connected),
            activity.findViewById<TextView>(R.id.statusText).text.toString())
        assertTrue(activity.findViewById<MaterialButton>(R.id.sceneAction).isEnabled)
        controller.pause().stop().destroy()
    }

    @Test fun `real activity restores safely and preserves comfort preferences`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.recreate()
        assertEquals(controller.get().getString(R.string.feed_import),
            controller.get().findViewById<MaterialButton>(R.id.sceneAction).text.toString())
        assertFalse(ProfileStore(controller.get()).soundEnabled)
        assertTrue(ProfileStore(controller.get()).reducedMotion)
        assertEquals(ConnectionManager.State.DISCONNECTED, ConnectionManager.state.value)
        controller.pause().stop().destroy()
    }

    @Test fun `onboarding demonstration never starts a VPN and restores its step`() {
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).setup()
        controller.get().findViewById<PigeonView>(R.id.pigeonScene).performClick()
        assertEquals(ConnectionManager.State.DISCONNECTED, ConnectionManager.state.value)
        controller.get().findViewById<MaterialButton>(R.id.primaryButton).performClick()
        // API28 omits the notification permission step, so welcome advances to battery.
        assertEquals(View.VISIBLE, controller.get().findViewById<View>(R.id.stepBattery).visibility)
        controller.recreate()
        assertEquals(View.VISIBLE, controller.get().findViewById<View>(R.id.stepBattery).visibility)
        assertEquals(ConnectionManager.State.DISCONNECTED, ConnectionManager.state.value)
        controller.pause().stop().destroy()
    }

    @Test fun `capture the actual appcompat screens with native widgets`() {
        ProfileStore(app).apply {
            val profile = Profile("preview", "My server",
                TransportConfig("example.invalid", 443, Cipher.CHACHA20_IETF_POLY1305, "test-only-not-a-real-key"),
                SourceType.STATIC, "preview-fixture")
            addOrUpdate(profile)
            activeProfileId = profile.id
        }
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        saveScreen(main.get(), "actual-home")
        main.pause().stop().destroy()
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        saveScreen(settings.get(), "actual-settings")
        settings.pause().stop().destroy()
        val onboarding = Robolectric.buildActivity(OnboardingActivity::class.java).setup()
        saveScreen(onboarding.get(), "actual-onboarding")
        onboarding.pause().stop().destroy()
    }

    private fun saveScreen(activity: Activity, name: String) {
        val root = activity.findViewById<View>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 390, 844)
        val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val output = File("build/courtyard-previews/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
