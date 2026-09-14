package com.outshake.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import com.outshake.R
import com.outshake.databinding.ActivityMainBinding
import com.outshake.databinding.ItemProfileBinding
import com.outshake.ui.mascot.PigeonView
import com.outshake.vpn.ConnectionManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual Android layout + Skia rendering, not a web approximation. No network or VPN startup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourtyardRenderTest {
    @Test fun `render all courtyard moods in day and night`() {
        for (night in listOf(false, true)) {
            for (state in ConnectionManager.State.entries) {
                val controller = Robolectric.buildActivity(Activity::class.java).setup()
                val activity = controller.get()
                val config = Configuration(activity.resources.configuration)
                config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                val context = android.view.ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Outshake)
                val b = ActivityMainBinding.inflate(LayoutInflater.from(context))
                val copy = CourtyardState.forState(state)
                b.statusText.setText(copy.status)
                b.heroTitle.setText(copy.title)
                b.heroSubtitle.setText(copy.body)
                b.mascotCaption.setText(copy.caption)
                b.sceneAction.setText(copy.action)
                b.sceneAction.isEnabled = !CourtyardState.isBusy(state)
                b.pigeonScene.setMotionEnabled(false)
                b.pigeonScene.setMood(copy.mood)
                b.activeText.text = if (state == ConnectionManager.State.CONNECTED) "Tunnel: My server" else "Next connection: My server"
                b.profileList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
                b.profileList.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 1
                    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                        val row = ItemProfileBinding.inflate(LayoutInflater.from(context), parent, false)
                        return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(row.root) {}
                    }
                    override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                        val row = ItemProfileBinding.bind(holder.itemView)
                        row.nameText.text = "My server"
                        row.detailText.text = "Selected · static key"
                        row.activeRadio.isChecked = true
                        row.refreshButton.visibility = View.GONE
                    }
                }
                b.root.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
                b.root.layout(0, 0, 390, 844)
                assertTrue(b.pigeonScene.height > 200)
                assertTrue(b.sceneAction.measuredHeight >= 48)
                assertEquals(state == ConnectionManager.State.CONNECTED, copy.mood == PigeonView.Mood.CONNECTED)
                val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
                b.root.draw(Canvas(bitmap))
                val output = File("build/courtyard-previews/${if (night) "night" else "day"}-${state.name.lowercase()}.png")
                output.parentFile!!.mkdirs()
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertNotEquals(bitmap.getPixel(0, 0), bitmap.getPixel(195, 480))
                bitmap.recycle()
                controller.pause().stop().destroy()
            }
        }
    }

    @Test fun `native animation poses render deterministically and export a motion preview`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = android.view.ContextThemeWrapper(activity, R.style.Theme_Outshake)
        val bird = PigeonView(context)
        bird.layout(0, 0, 684, 496)
        val poses = listOf(
            PigeonView.Mood.IDLE to 1.2f, PigeonView.Mood.IDLE to 5.8f,
            PigeonView.Mood.CONNECTED to .95f, PigeonView.Mood.CONNECTED to 3.2f,
            PigeonView.Mood.CONNECTING to .8f, PigeonView.Mood.ERROR to 1f
        )
        val strip = Bitmap.createBitmap(684 * 3, 496 * 2, Bitmap.Config.ARGB_8888)
        val stripCanvas = Canvas(strip)
        poses.forEachIndexed { index, (mood, t) ->
            bird.setMood(mood)
            val tile = Bitmap.createBitmap(684, 496, Bitmap.Config.ARGB_8888)
            val c = Canvas(tile)
            c.drawColor(android.graphics.Color.rgb(232, 237, 225))
            bird.renderFrameForTesting(c, t)
            stripCanvas.drawBitmap(tile, (index % 3) * 684f, (index / 3) * 496f, null)
            tile.recycle()
        }
        val output = File("build/courtyard-previews/pose-contact-sheet.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
        strip.recycle()
        if (System.getProperty("outshake.renderFrames") == "true") {
            for (mood in listOf(PigeonView.Mood.IDLE, PigeonView.Mood.CONNECTED)) {
                bird.setMood(mood)
                val frames = File("build/courtyard-previews/frames-${mood.name.lowercase()}").apply { mkdirs() }
                for (frame in 0 until 126) {
                    val bitmap = Bitmap.createBitmap(684, 496, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.rgb(232, 237, 225))
                    bird.renderFrameForTesting(canvas, frame / 15f)
                    File(frames, "%03d.png".format(frame)).outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        }
        activity.finish()
    }

    @Test fun `large font small screen remains scrollable with reachable action`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val config = Configuration(activity.resources.configuration).apply { fontScale = 1.5f }
        val context = android.view.ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Outshake)
        val b = ActivityMainBinding.inflate(LayoutInflater.from(context))
        b.pigeonScene.setMotionEnabled(false)
        b.root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(568, View.MeasureSpec.EXACTLY))
        b.root.layout(0, 0, 320, 568)
        assertTrue(b.root.getChildAt(0).height > b.root.height)
        assertTrue(b.sceneAction.height >= 48)
        assertTrue(b.sceneAction.bottom <= (b.sceneAction.parent as View).height)
        controller.pause().stop().destroy()
    }
}
