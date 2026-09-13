package com.outshake.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.outshake.R
import com.outshake.config.ProfileImporter
import com.outshake.databinding.ActivityImportBinding
import com.outshake.store.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.importButton.setOnClickListener { doImport() }
        binding.pasteButton.setOnClickListener { pasteFromClipboard() }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = clipboard.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.import_empty_key, Toast.LENGTH_SHORT).show()
        } else {
            binding.keyInput.setText(text)
            binding.keyInput.setSelection(text.length)
        }
    }

    private fun doImport() {
        val key = binding.keyInput.text.toString().trim()
        if (key.isEmpty()) {
            binding.messageText.setText(R.string.import_empty_key)
            return
        }
        binding.progress.visibility = View.VISIBLE
        binding.messageText.text = ""
        binding.importButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { ProfileImporter.import(key) }
                val store = ProfileStore(this@ImportActivity)
                store.addOrUpdate(profile)
                if (store.activeProfileId == null) store.activeProfileId = profile.id
                finish()
            } catch (e: Exception) {
                binding.progress.visibility = View.GONE
                binding.importButton.isEnabled = true
                binding.messageText.text = e.message ?: getString(R.string.import_failed)
            }
        }
    }
}
