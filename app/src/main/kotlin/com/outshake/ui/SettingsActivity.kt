package com.outshake.ui

import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.outshake.R
import com.outshake.databinding.ActivitySettingsBinding
import com.outshake.shake.ShakeService
import com.outshake.store.ProfileStore

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: ProfileStore

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    // Sensitivity maps a 0..100 seek value onto a g-force threshold of 3.6 (hard) .. 1.6 (easy).
    private val minG = 1.6f
    private val maxG = 3.6f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ProfileStore(this)

        binding.backButton.setOnClickListener { finish() }

        binding.shakeSwitch.isChecked = store.shakeEnabled
        binding.shakeSwitch.setOnCheckedChangeListener { _, checked ->
            store.shakeEnabled = checked
            if (checked) {
                ensureNotificationPermission()
                ShakeService.start(this)
            } else {
                ShakeService.stop(this)
            }
        }

        binding.vibrateSwitch.isChecked = store.vibrateOnToggle
        binding.vibrateSwitch.setOnCheckedChangeListener { _, checked ->
            store.vibrateOnToggle = checked
        }

        binding.soundSwitch.isChecked = store.soundEnabled
        binding.soundSwitch.setOnCheckedChangeListener { _, checked ->
            store.soundEnabled = checked
        }

        binding.reducedMotionSwitch.isChecked = store.reducedMotion
        binding.reducedMotionSwitch.setOnCheckedChangeListener { _, checked ->
            store.reducedMotion = checked
        }

        binding.bootSwitch.isChecked = store.connectOnBoot
        binding.bootSwitch.setOnCheckedChangeListener { _, checked ->
            store.connectOnBoot = checked
            if (checked) ensureNotificationPermission()
        }

        val current = store.shakeSensitivity
        binding.sensitivitySeek.progress = gToProgress(current)
        updateSensitivityLabel(current)
        binding.sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val g = progressToG(progress)
                store.shakeSensitivity = g
                updateSensitivityLabel(g)
                // Push the new threshold to a running ShakeService (no restart needed).
                if (fromUser) ShakeService.sync(this@SettingsActivity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                ShakeService.sync(this@SettingsActivity)
            }
        })
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun progressToG(progress: Int): Float = maxG - (maxG - minG) * (progress / 100f)
    private fun gToProgress(g: Float): Int = (((maxG - g) / (maxG - minG)) * 100f).toInt().coerceIn(0, 100)

    private fun updateSensitivityLabel(g: Float) {
        val label = getString(
            when {
                g <= 2.0f -> R.string.sensitivity_high
                g >= 3.2f -> R.string.sensitivity_low
                else -> R.string.sensitivity_medium
            }
        )
        binding.sensitivityValue.text = getString(R.string.sensitivity_value_fmt, label, g)
    }
}
