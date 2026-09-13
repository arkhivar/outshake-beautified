package com.outshake.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.AnimationDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.outshake.R
import com.outshake.config.Profile
import com.outshake.config.ProfileImporter
import com.outshake.databinding.ActivityMainBinding
import com.outshake.databinding.ItemProfileBinding
import com.outshake.shake.ShakeService
import com.outshake.store.ProfileStore
import com.outshake.vpn.ConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ProfileStore
    private lateinit var adapter: ProfileAdapter

    /** Mascot animation-list resource currently shown; 0 until first render. */
    private var mascotAnimRes = 0

    /** Lazily created one-shot player for the tap coo; released in onDestroy. */
    private var tapSoundPool: SoundPool? = null
    private var tapSoundId = 0
    private var hopAnimator: AnimatorSet? = null

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private val vpnPrepare = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            store.activeProfile()?.let { ConnectionManager.connect(this, it.id) }
        } else {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(this)

        // First-run gate: onboarding handles the permission priming flow. Main stays on the
        // back stack so returning from onboarding lands here.
        if (!store.onboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ProfileAdapter()
        binding.profileList.layoutManager = LinearLayoutManager(this)
        binding.profileList.adapter = adapter

        binding.addButton.setOnClickListener { startActivity(Intent(this, ImportActivity::class.java)) }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.connectButton.setOnClickListener {
            onUserToggle(!isOn(ConnectionManager.state.value))
        }
        binding.mascotImage.setOnClickListener { onMascotTapped() }

        lifecycleScope.launch {
            ConnectionManager.state.collect { render(it) }
        }

        // Onboarding asks for notifications on first run; keep this as a fallback for users
        // who skipped it there.
        if (store.onboardingComplete) ensureNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshProfiles()
        render(ConnectionManager.state.value)
        // Shake detection runs in a foreground service (works while backgrounded); start if enabled.
        ShakeService.sync(this)
        startMascotAnim()
    }

    override fun onPause() {
        // AnimationDrawable keeps ticking while paused otherwise — stop it to save battery.
        stopMascotAnim()
        super.onPause()
    }

    override fun onDestroy() {
        hopAnimator?.cancel()
        hopAnimator = null
        tapSoundPool?.release()
        tapSoundPool = null
        super.onDestroy()
    }

    // ---- Pigeon mascot ----

    /** Swap the mascot loop only when the target animation actually changes. */
    private fun updateMascot(state: ConnectionManager.State) {
        val animRes = if (state == ConnectionManager.State.CONNECTED) {
            R.drawable.pigeon_peck_anim // fed and happy: pecking seeds
        } else {
            R.drawable.pigeon_hungry_anim // begging for a connection
        }
        if (animRes != mascotAnimRes) {
            stopMascotAnim()
            mascotAnimRes = animRes
            binding.mascotImage.setImageResource(animRes)
        }
        startMascotAnim()
    }

    private fun startMascotAnim() {
        (binding.mascotImage.drawable as? AnimationDrawable)?.let {
            if (!it.isRunning) it.start()
        }
    }

    private fun stopMascotAnim() {
        (binding.mascotImage.drawable as? AnimationDrawable)?.stop()
    }

    /** Curious coo + a little hop with a head-tilt wiggle. */
    private fun onMascotTapped() {
        playTapCoo()
        hopAnimator?.cancel()
        val img = binding.mascotImage
        val hopPx = 24f * resources.displayMetrics.density
        val up = ObjectAnimator.ofFloat(img, View.TRANSLATION_Y, 0f, -hopPx).apply {
            duration = 150
            interpolator = DecelerateInterpolator()
        }
        val down = ObjectAnimator.ofFloat(img, View.TRANSLATION_Y, -hopPx, 0f).apply {
            duration = 150
            interpolator = AccelerateInterpolator()
        }
        val wiggle = ObjectAnimator.ofFloat(img, View.ROTATION, 0f, -6f, 5f, 0f).apply {
            duration = 300
        }
        hopAnimator = AnimatorSet().apply {
            play(up).before(down)
            play(wiggle).with(up)
            start()
        }
    }

    /** Lazily builds the one-shot SoundPool (notification stream, muted in silent mode). */
    private fun playTapCoo() {
        val pool = tapSoundPool ?: run {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build().also {
                tapSoundId = it.load(this, R.raw.coo_tap, 1)
                tapSoundPool = it
            }
        }
        pool.play(tapSoundId, TAP_COO_VOLUME, TAP_COO_VOLUME, 1, 0, 1f)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** True while the VPN is up or coming up (the hero button acts as "disconnect" then). */
    private fun isOn(state: ConnectionManager.State): Boolean = when (state) {
        ConnectionManager.State.CONNECTED,
        ConnectionManager.State.CONNECTING,
        ConnectionManager.State.RECONNECTING -> true
        else -> false
    }

    /** Called only for genuine user taps of the hero connect button. */
    private fun onUserToggle(wantOn: Boolean) {
        if (wantOn) {
            val active = store.activeProfile()
            if (active == null) {
                Toast.makeText(this, R.string.select_profile_first, Toast.LENGTH_SHORT).show()
                render(ConnectionManager.state.value)
                return
            }
            val prepare = VpnService.prepare(this)
            if (prepare != null) vpnPrepare.launch(prepare)
            else ConnectionManager.connect(this, active.id)
        } else {
            ConnectionManager.disconnect(this)
        }
    }

    private fun refreshProfiles() {
        val profiles = store.getProfiles()
        adapter.submit(profiles, store.activeProfileId)
        binding.emptyState.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        val active = store.activeProfile()
        binding.activeText.text = active?.let { getString(R.string.active_profile_fmt, it.name) }
            ?: getString(R.string.no_active_profile)
    }

    private fun render(state: ConnectionManager.State) {
        updateMascot(state)
        binding.statusText.text = getString(
            when (state) {
                ConnectionManager.State.DISCONNECTED -> R.string.status_disconnected
                ConnectionManager.State.CONNECTING -> R.string.status_connecting
                ConnectionManager.State.CONNECTED -> R.string.status_connected
                ConnectionManager.State.RECONNECTING -> R.string.status_reconnecting
                ConnectionManager.State.DISCONNECTING -> R.string.status_disconnecting
                ConnectionManager.State.ERROR -> R.string.status_error
            }
        )
        val statusColor = when (state) {
            ConnectionManager.State.CONNECTED -> R.color.accent
            ConnectionManager.State.ERROR -> R.color.error
            else -> R.color.on_surface
        }
        binding.statusText.setTextColor(getColor(statusColor))

        // Transitions are intermediate: disable input and show the progress ring.
        val transitioning = state == ConnectionManager.State.CONNECTING ||
            state == ConnectionManager.State.RECONNECTING ||
            state == ConnectionManager.State.DISCONNECTING
        val on = isOn(state)

        binding.connectButton.isEnabled = !transitioning
        binding.connectProgress.visibility = if (transitioning) View.VISIBLE else View.GONE

        // Connected: filled teal circle with a shield; otherwise raised circle with power icon.
        binding.connectButton.setIconResource(if (on) R.drawable.ic_shield_check else R.drawable.ic_power)
        binding.connectButton.backgroundTintList =
            ColorStateList.valueOf(getColor(if (on) R.color.accent else R.color.surface_raised))
        binding.connectButton.iconTint =
            ColorStateList.valueOf(getColor(if (on) R.color.on_accent else R.color.accent))
        binding.connectButton.contentDescription =
            getString(if (on) R.string.cd_disconnect_vpn else R.string.cd_connect_vpn)
        binding.connectHint.text =
            getString(if (on) R.string.tap_to_disconnect else R.string.tap_to_connect)

        val err = ConnectionManager.lastError
        if (state == ConnectionManager.State.ERROR && err != null) {
            binding.errorText.visibility = View.VISIBLE
            binding.errorText.text = err
        } else {
            binding.errorText.visibility = View.GONE
        }
    }

    private inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.VH>() {
        private var items: List<Profile> = emptyList()
        private var activeId: String? = null

        fun submit(list: List<Profile>, activeId: String?) {
            this.items = list
            this.activeId = activeId
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemProfileBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemProfileBinding.inflate(layoutInflater, parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            holder.b.nameText.text = p.name
            val prefix = if (p.transport.prefix != null) " · prefix" else ""
            holder.b.detailText.text = "${p.sourceType.name.lowercase()} · ${p.transport.cipher.id}$prefix"
            holder.b.activeRadio.isChecked = p.id == activeId
            holder.b.refreshButton.visibility =
                if (p.sourceType == com.outshake.config.SourceType.DYNAMIC) View.VISIBLE
                else View.GONE

            holder.b.root.setOnClickListener {
                store.activeProfileId = p.id
                refreshProfiles()
            }
            holder.b.deleteButton.setOnClickListener { confirmDelete(p) }
            holder.b.refreshButton.setOnClickListener { refreshDynamic(p) }
        }
    }

    private fun confirmDelete(profile: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_confirm_title, profile.name))
            .setMessage(R.string.delete_confirm_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(profile.id)
                refreshProfiles()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshDynamic(profile: Profile) {
        Toast.makeText(this, R.string.refreshing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { ProfileImporter.refresh(profile) }
                store.addOrUpdate(updated)
                refreshProfiles()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.profile_updated_fmt, updated.name),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.refresh_failed_fmt, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private companion object {
        /** Half volume is plenty for an in-app tap cue (ShakeService uses 0.35 for shake feedback). */
        const val TAP_COO_VOLUME = 0.5f
    }
}
