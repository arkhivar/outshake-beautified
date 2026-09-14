package com.outshake.ui

import android.content.Intent
import android.graphics.Rect
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.outshake.R
import com.outshake.config.Profile
import com.outshake.config.ProfileImporter
import com.outshake.databinding.ActivityMainBinding
import com.outshake.databinding.ItemProfileBinding
import com.outshake.shake.CooSoundPlayer
import com.outshake.shake.ShakeService
import com.outshake.store.ProfileStore
import com.outshake.ui.mascot.PigeonView
import com.outshake.vpn.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The courtyard is a presentation of ConnectionManager, never a second VPN state machine. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ProfileStore
    private lateinit var adapter: ProfileAdapter
    private lateinit var sounds: CooSoundPlayer
    private var consentPending = false
    private var pendingProfileId: String? = null
    private var lastRenderedState: ConnectionManager.State? = null
    private var screenResumed = false
    private var sceneVisible = false
    private val sceneBounds = Rect()

    private val vpnPrepare = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        consentPending = false
        val requestedId = pendingProfileId
        pendingProfileId = null
        if (result.resultCode == RESULT_OK) {
            // Consent may outlive a rotation. Never silently connect a newly selected profile.
            val active = store.activeProfile()
            if (active != null && active.id == requestedId &&
                CourtyardState.canConnect(ConnectionManager.state.value)) {
                requestConnection(active.id)
            }
        } else {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
        render(ConnectionManager.state.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(this)
        consentPending = savedInstanceState?.getBoolean("consentPending") ?: false
        pendingProfileId = savedInstanceState?.getString("pendingProfileId")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sounds = CooSoundPlayer(this)
        adapter = ProfileAdapter()
        binding.profileList.layoutManager = LinearLayoutManager(this)
        binding.profileList.adapter = adapter
        binding.addButton.setOnClickListener { openImport() }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.sceneAction.setOnClickListener { onSceneAction() }
        binding.pigeonScene.setOnClickListener { onSceneAction() }
        binding.root.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
                syncSceneVisibility()
            }
        )
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncSceneVisibility() }
        binding.pigeonScene.onPeck = {
            if (sceneVisible &&
                ConnectionManager.state.value == ConnectionManager.State.CONNECTED) {
                sounds.play(CooSoundPlayer.Cue.PET)
            }
        }
        binding.statusText.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.connection_status_title)
                .setMessage(R.string.scene_status_note)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionManager.state.collect { render(it) }
            }
        }
        if (!store.onboardingComplete && savedInstanceState == null) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("consentPending", consentPending)
        outState.putString("pendingProfileId", pendingProfileId)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        screenResumed = true
        refreshProfiles()
        binding.pigeonScene.setMotionEnabled(!store.reducedMotion)
        syncSceneVisibility()
        binding.root.post { syncSceneVisibility() }
        render(ConnectionManager.state.value)
        ShakeService.sync(this)
    }

    override fun onPause() {
        screenResumed = false
        sceneVisible = false
        binding.pigeonScene.setSceneActive(false)
        sounds.stop()
        super.onPause()
    }

    override fun onDestroy() {
        sounds.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::binding.isInitialized) syncSceneVisibility()
    }

    private fun syncSceneVisibility() {
        val visible = screenResumed && window.decorView.hasWindowFocus() &&
            binding.pigeonScene.isShown && binding.pigeonScene.getGlobalVisibleRect(sceneBounds) &&
            sceneBounds.height() > 0
        sceneVisible = visible
        binding.pigeonScene.setSceneActive(visible)
        if (!visible && ::sounds.isInitialized) sounds.stop()
    }

    private fun openImport() = startActivity(Intent(this, ImportActivity::class.java))

    private fun onSceneAction() {
        val state = ConnectionManager.state.value
        if (consentPending || CourtyardState.isBusy(state)) return
        if (state == ConnectionManager.State.CONNECTED) {
            try {
                ConnectionManager.disconnect(this)
                sounds.play(CooSoundPlayer.Cue.REST)
            } catch (_: Exception) {
                ConnectionManager.onError(getString(R.string.vpn_request_failed))
            }
            return
        }
        val active = store.activeProfile()
        if (active == null) {
            openImport()
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            consentPending = true
            pendingProfileId = active.id
            render(state)
            vpnPrepare.launch(prepare)
        } else {
            requestConnection(active.id)
        }
    }

    private fun requestConnection(profileId: String) {
        try {
            ConnectionManager.connect(this, profileId)
            // The coo acknowledges a request. Connected feeding starts only on service state.
            sounds.play(CooSoundPlayer.Cue.FEED)
        } catch (_: Exception) {
            ConnectionManager.onError(getString(R.string.vpn_request_failed))
        }
    }

    private fun refreshProfiles() {
        val profiles = store.getProfiles()
        adapter.submit(profiles, store.activeProfileId)
        binding.emptyState.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        renderProfileLabel(ConnectionManager.state.value)
    }

    private fun renderProfileLabel(state: ConnectionManager.State) {
        val active = store.activeProfile()
        val running = store.getProfiles().firstOrNull { it.id == ConnectionManager.activeProfileId }
        binding.activeText.text = if (state == ConnectionManager.State.CONNECTED && running != null) {
            getString(R.string.connected_profile_fmt, running.name)
        } else {
            active?.let { getString(R.string.next_profile_fmt, it.name) } ?: getString(R.string.no_active_profile)
        }
    }

    private fun render(state: ConnectionManager.State) {
        val copy = CourtyardState.forState(state)
        binding.statusText.setText(copy.status)
        binding.heroTitle.setText(copy.title)
        binding.heroSubtitle.setText(copy.body)
        binding.mascotCaption.setText(copy.caption)
        binding.statusText.setTextColor(getColor(
            if (state == ConnectionManager.State.ERROR) R.color.error else R.color.on_surface
        ))
        binding.pigeonScene.setMood(copy.mood)
        if (state == ConnectionManager.State.CONNECTED &&
            lastRenderedState != null && lastRenderedState != state) {
            binding.pigeonScene.celebrate()
        }
        lastRenderedState = state
        val noProfile = store.activeProfile() == null
        val canImport = noProfile && CourtyardState.canConnect(state)
        binding.sceneAction.setText(if (canImport) R.string.feed_import else copy.action)
        val enabled = !CourtyardState.isBusy(state) && !consentPending
        binding.sceneAction.isEnabled = enabled
        binding.pigeonScene.isEnabled = enabled
        binding.pigeonScene.contentDescription = getString(
            when {
                canImport -> R.string.cd_import_coo
                state == ConnectionManager.State.CONNECTED -> R.string.cd_rest_coo
                CourtyardState.isBusy(state) -> copy.status
                else -> R.string.cd_feed_coo
            }
        )
        binding.connectHint.setText(if (store.shakeEnabled) R.string.shake_hint else R.string.shake_disabled_hint)
        val error = ConnectionManager.lastError
        binding.errorText.visibility = if (state == ConnectionManager.State.ERROR && error != null) View.VISIBLE else View.GONE
        binding.errorText.text = error
        renderProfileLabel(state)
    }

    private fun allowProfileEdit(): Boolean {
        if (consentPending || !CourtyardState.canConnect(ConnectionManager.state.value)) {
            Toast.makeText(this, R.string.profile_change_notice, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.VH>() {
        private var items: List<Profile> = emptyList()
        private var activeId: String? = null
        fun submit(list: List<Profile>, activeId: String?) {
            items = list
            this.activeId = activeId
            notifyDataSetChanged()
        }
        inner class VH(val b: ItemProfileBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
            VH(ItemProfileBinding.inflate(layoutInflater, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val profile = items[position]
            val selected = profile.id == activeId
            holder.b.nameText.text = profile.name
            val kind = getString(if (profile.sourceType == com.outshake.config.SourceType.DYNAMIC)
                R.string.profile_dynamic else R.string.profile_static)
            holder.b.detailText.text = getString(R.string.profile_row_detail,
                getString(if (selected) R.string.profile_selected else R.string.profile_available), kind)
            holder.b.activeRadio.isChecked = selected
            holder.b.root.strokeColor = getColor(if (selected) R.color.accent else R.color.outline)
            holder.b.root.isFocusable = true
            holder.b.refreshButton.visibility =
                if (profile.sourceType == com.outshake.config.SourceType.DYNAMIC) View.VISIBLE else View.GONE
            holder.b.root.setOnClickListener {
                if (allowProfileEdit()) {
                    store.activeProfileId = profile.id
                    refreshProfiles()
                    render(ConnectionManager.state.value)
                }
            }
            holder.b.deleteButton.setOnClickListener { if (allowProfileEdit()) confirmDelete(profile) }
            holder.b.refreshButton.setOnClickListener { if (allowProfileEdit()) refreshDynamic(profile) }
        }
    }

    private fun confirmDelete(profile: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_confirm_title, profile.name))
            .setMessage(R.string.delete_confirm_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (allowProfileEdit()) {
                    store.delete(profile.id)
                    refreshProfiles()
                    render(ConnectionManager.state.value)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshDynamic(profile: Profile) {
        Toast.makeText(this, R.string.refreshing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { ProfileImporter.refresh(profile) }
                // A shake may have started the tunnel while the fetch was in progress.
                if (!allowProfileEdit()) return@launch
                if (store.getProfiles().none { it.id == profile.id }) return@launch
                store.addOrUpdate(updated)
                refreshProfiles()
                Toast.makeText(this@MainActivity, getString(R.string.profile_updated_fmt, updated.name), Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.refresh_failed_fmt, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
}
