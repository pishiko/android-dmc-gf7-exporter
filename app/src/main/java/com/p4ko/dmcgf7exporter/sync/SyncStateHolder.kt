package com.p4ko.dmcgf7exporter.sync

import com.p4ko.dmcgf7exporter.model.SyncPhase
import com.p4ko.dmcgf7exporter.model.SyncUiState
import com.p4ko.dmcgf7exporter.network.LumixCameraClient
import com.p4ko.dmcgf7exporter.network.WifiNetworkHelper
import com.p4ko.dmcgf7exporter.storage.SavedFileMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide state holder shared between [CameraSyncService] and [com.p4ko.dmcgf7exporter.ui.MainViewModel]
 * so the UI always displays real-time progress even if the Activity is backgrounded or recreated.
 */
object SyncStateHolder {
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    fun initializeIfIdle(
        cameraHost: String,
        cameraWifiSsid: String,
        saveDirectoryPath: String
    ) {
        val current = _state.value
        if (!current.isRunning && !current.isConnected && current.totalCameraCount == 0) {
            _state.value = current.copy(
                cameraHost = LumixCameraClient.sanitizeHost(cameraHost),
                cameraWifiSsid = cameraWifiSsid.trim().ifEmpty { WifiNetworkHelper.DEFAULT_SSID_PREFIX },
                saveDirectoryPath = SavedFileMatcher.normalizeDcimRelativePath(saveDirectoryPath)
            )
        }
    }

    fun updateState(newState: SyncUiState) {
        _state.value = newState
    }

    fun markDisconnected() {
        val current = _state.value
        _state.value = SyncUiState(
            phase = SyncPhase.IDLE,
            isConnected = false,
            cameraHost = current.cameraHost,
            cameraWifiSsid = current.cameraWifiSsid,
            saveDirectoryPath = current.saveDirectoryPath,
            statusMessage = "切断しました",
            errorMessage = null
        )
    }

    fun updateSettings(
        saveDirectoryPath: String,
        cameraHost: String,
        cameraWifiSsid: String
    ): Triple<String, String, String> {
        val normalizedDir = SavedFileMatcher.normalizeDcimRelativePath(saveDirectoryPath)
        val sanitizedHost = LumixCameraClient.sanitizeHost(cameraHost)
        val sanitizedSsid = cameraWifiSsid.trim().ifEmpty { WifiNetworkHelper.DEFAULT_SSID_PREFIX }
        _state.value = _state.value.copy(
            saveDirectoryPath = normalizedDir,
            cameraHost = sanitizedHost,
            cameraWifiSsid = sanitizedSsid,
            statusMessage = "設定を更新しました: $normalizedDir",
            errorMessage = null
        )
        return Triple(normalizedDir, sanitizedHost, sanitizedSsid)
    }
}
