package com.outshake.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.outshake.R
import com.outshake.databinding.ActivityOnboardingBinding
import com.outshake.store.ProfileStore

/**
 * First-run stepper: welcome → notifications (API 33+) → battery/background → shake intro.
 * Marks [ProfileStore.onboardingComplete] when finished; Main stays on the back stack so the
 * user lands on it when this finishes (and can still back out mid-flow).
 */
class OnboardingActivity : AppCompatActivity() {

    private enum class Step { WELCOME, NOTIFICATIONS, BATTERY, SHAKE }

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var store: ProfileStore

    private lateinit var steps: List<Step>
    private var index = 0

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { advance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ProfileStore(this)

        // The notification runtime permission only exists on API 33+; skip that step below it.
        steps = buildList {
            add(Step.WELCOME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Step.NOTIFICATIONS)
            add(Step.BATTERY)
            add(Step.SHAKE)
        }

        binding.primaryButton.setOnClickListener { onPrimary() }
        binding.secondaryButton.setOnClickListener { advance() }

        showStep(0)
    }

    override fun onResume() {
        super.onResume()
        // The user may have just returned from the battery-optimization settings screen.
        if (steps[index] == Step.BATTERY) updateBatteryState()
    }

    private fun onPrimary() {
        when (steps[index]) {
            Step.WELCOME -> advance()
            Step.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    advance()
                }
            }
            Step.BATTERY -> {
                if (isBatteryExempt()) advance() else requestBatteryExemption()
            }
            Step.SHAKE -> finishOnboarding()
        }
    }

    private fun advance() {
        if (index < steps.size - 1) showStep(index + 1) else finishOnboarding()
    }

    private fun finishOnboarding() {
        store.onboardingComplete = true
        finish()
    }

    private fun showStep(newIndex: Int) {
        index = newIndex
        val step = steps[index]
        binding.stepWelcome.visibility = if (step == Step.WELCOME) View.VISIBLE else View.GONE
        binding.stepNotifications.visibility = if (step == Step.NOTIFICATIONS) View.VISIBLE else View.GONE
        binding.stepBattery.visibility = if (step == Step.BATTERY) View.VISIBLE else View.GONE
        binding.stepShake.visibility = if (step == Step.SHAKE) View.VISIBLE else View.GONE

        binding.primaryButton.setText(
            when (step) {
                Step.WELCOME -> R.string.onboarding_get_started
                Step.NOTIFICATIONS -> R.string.onboarding_allow_notifications
                Step.BATTERY ->
                    if (isBatteryExempt()) R.string.onboarding_continue
                    else R.string.onboarding_allow_background
                Step.SHAKE -> R.string.onboarding_done
            }
        )
        // Skipping only makes sense where the primary action requests something.
        binding.secondaryButton.visibility = when (step) {
            Step.NOTIFICATIONS, Step.BATTERY -> View.VISIBLE
            else -> View.GONE
        }

        if (step == Step.BATTERY) updateBatteryState()
        renderDots()
    }

    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryState() {
        val exempt = isBatteryExempt()
        binding.batteryStatusRow.visibility = if (exempt) View.VISIBLE else View.GONE
        binding.primaryButton.setText(
            if (exempt) R.string.onboarding_continue else R.string.onboarding_allow_background
        )
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: ActivityNotFoundException) {
            openBatterySettingsFallback()
        } catch (e: SecurityException) {
            openBatterySettingsFallback()
        }
    }

    private fun openBatterySettingsFallback() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.onboarding_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderDots() {
        binding.stepDots.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (8 * density).toInt()
        val margin = (4 * density).toInt()
        steps.forEachIndexed { i, _ ->
            val dot = ImageView(this)
            dot.setImageResource(R.drawable.onboarding_dot)
            dot.imageTintList = ColorStateList.valueOf(
                getColor(if (i == index) R.color.accent else R.color.outline)
            )
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(margin, 0, margin, 0)
            dot.layoutParams = lp
            binding.stepDots.addView(dot)
        }
    }
}
