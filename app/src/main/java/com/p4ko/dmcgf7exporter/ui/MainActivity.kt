package com.p4ko.dmcgf7exporter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.p4ko.dmcgf7exporter.R
import com.p4ko.dmcgf7exporter.databinding.ActivityMainBinding
import com.p4ko.dmcgf7exporter.model.SyncPhase
import com.p4ko.dmcgf7exporter.model.SyncUiState
import com.p4ko.dmcgf7exporter.storage.ExporterPreferences
import com.p4ko.dmcgf7exporter.sync.CameraSyncService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: ExporterPreferences

    private var pendingActionAfterPermission: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val action = pendingActionAfterPermission
        pendingActionAfterPermission = null
        action?.invoke()
    }

    private val viewModel: MainViewModel by viewModels {
        val prefs = ExporterPreferences(applicationContext)
        MainViewModel.Factory(
            initialCameraHost = prefs.cameraHost,
            initialCameraWifiSsid = prefs.cameraWifiSsid,
            initialSaveDirectory = prefs.saveDirectoryPath
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = ExporterPreferences(applicationContext)
        binding.etCameraWifiSsid.setText(preferences.cameraWifiSsid)
        binding.etSaveDirectory.setText(preferences.saveDirectoryPath)
        binding.etCameraHost.setText(preferences.cameraHost)

        binding.btnApplySettings.setOnClickListener {
            applyCurrentSettingsFromInputs(showToast = true)
        }

        // Step 1: 接続ボタン (Wi-Fi自動接続 -> カメラ側の接続リクエスト許可を待つ)
        binding.btnConnect.setOnClickListener {
            val (dir, host, ssid) = applyCurrentSettingsFromInputs(showToast = false)
            runWithRequiredPermissions {
                CameraSyncService.connect(
                    context = applicationContext,
                    cameraHost = host,
                    cameraWifiSsid = ssid,
                    saveDirectoryPath = dir
                )
            }
        }

        // Step 2: 保存ボタン (全件列挙 -> 比較 -> 未保存画像ダウンロード)
        binding.btnSave.setOnClickListener {
            val (dir, host, ssid) = applyCurrentSettingsFromInputs(showToast = false)
            runWithRequiredPermissions {
                CameraSyncService.startSync(
                    context = applicationContext,
                    cameraHost = host,
                    cameraWifiSsid = ssid,
                    saveDirectoryPath = dir,
                    downloadUnsaved = true
                )
            }
        }

        // Step 3: 切断ボタン (Wi-Fiも切断)
        binding.btnDisconnect.setOnClickListener {
            CameraSyncService.disconnect(applicationContext)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state)
                }
            }
        }
    }

    private fun applyCurrentSettingsFromInputs(showToast: Boolean): Triple<String, String, String> {
        val rawSsid = binding.etCameraWifiSsid.text?.toString().orEmpty().trim()
        val rawDir = binding.etSaveDirectory.text?.toString().orEmpty()
        val rawHost = binding.etCameraHost.text?.toString().orEmpty()

        val (normalizedDir, sanitizedHost, sanitizedSsid) =
            viewModel.updateSettings(rawDir, rawHost, rawSsid)

        preferences.cameraWifiSsid = sanitizedSsid
        preferences.saveDirectoryPath = normalizedDir
        preferences.cameraHost = sanitizedHost

        if (binding.etCameraWifiSsid.text?.toString() != sanitizedSsid) {
            binding.etCameraWifiSsid.setText(sanitizedSsid)
        }
        if (binding.etSaveDirectory.text?.toString() != normalizedDir) {
            binding.etSaveDirectory.setText(normalizedDir)
        }
        if (binding.etCameraHost.text?.toString() != sanitizedHost) {
            binding.etCameraHost.setText(sanitizedHost)
        }

        if (showToast) {
            Toast.makeText(
                this,
                getString(R.string.msg_settings_saved, normalizedDir),
                Toast.LENGTH_SHORT
            ).show()
        }
        return Triple(normalizedDir, sanitizedHost, sanitizedSsid)
    }

    private fun renderUiState(state: SyncUiState) {
        when {
            state.isConnected -> {
                binding.tvConnectionBadge.text = getString(R.string.status_connected)
                binding.tvConnectionBadge.setTextColor(Color.parseColor("#2E7D32"))
            }
            state.phase == SyncPhase.CONNECTING -> {
                binding.tvConnectionBadge.text = getString(R.string.status_connecting)
                binding.tvConnectionBadge.setTextColor(Color.parseColor("#EF6C00"))
            }
            else -> {
                binding.tvConnectionBadge.text = getString(R.string.status_disconnected)
                binding.tvConnectionBadge.setTextColor(Color.parseColor("#757575"))
            }
        }

        binding.tvTotalCameraCount.text = state.totalCameraCount.toString()
        binding.tvSavedCount.text = state.savedCount.toString()
        binding.tvUnsavedCount.text = state.unsavedCount.toString()

        val progressPercent = state.progressPercent
        binding.progressBarSync.progress = progressPercent

        val progressLabel = when (state.phase) {
            SyncPhase.SCANNING -> {
                val denom = maxOf(state.totalCameraCount, state.scannedItemsCount)
                "走査 ${state.scannedItemsCount} / $denom ($progressPercent%)"
            }
            SyncPhase.DOWNLOADING, SyncPhase.COMPLETED -> {
                if (state.totalToDownloadCount > 0) {
                    "${state.downloadedCount} / ${state.totalToDownloadCount} ($progressPercent%)"
                } else {
                    "0 / 0 (100%)"
                }
            }
            else -> {
                "${state.downloadedCount} / ${state.totalToDownloadCount} ($progressPercent%)"
            }
        }
        binding.tvSyncProgressDetail.text = progressLabel
        binding.tvStatusMessage.text = state.statusMessage

        if (!state.errorMessage.isNullOrBlank()) {
            binding.tvErrorMessage.text = state.errorMessage
            binding.tvErrorMessage.visibility = View.VISIBLE
        } else {
            binding.tvErrorMessage.visibility = View.GONE
        }

        // Enable/disable buttons according to the Connect -> Save -> Disconnect workflow
        binding.btnConnect.isEnabled = state.canConnect
        binding.btnSave.isEnabled = state.canSave
        binding.btnDisconnect.isEnabled = state.canDisconnect

        val running = state.isRunning
        binding.etCameraWifiSsid.isEnabled = !running
        binding.etSaveDirectory.isEnabled = !running
        binding.etCameraHost.isEnabled = !running
        binding.btnApplySettings.isEnabled = !running
    }

    private fun runWithRequiredPermissions(action: () -> Unit) {
        val requiredPermissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            )
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            else -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }

        val missing = requiredPermissions.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            action()
        } else {
            pendingActionAfterPermission = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
