package com.p4ko.dmcgf7exporter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.p4ko.dmcgf7exporter.model.SyncUiState
import com.p4ko.dmcgf7exporter.network.WifiNetworkHelper
import com.p4ko.dmcgf7exporter.sync.SyncStateHolder
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(
    initialCameraHost: String = SyncUiState.DEFAULT_CAMERA_HOST,
    initialCameraWifiSsid: String = WifiNetworkHelper.DEFAULT_SSID_PREFIX,
    initialSaveDirectory: String = SyncUiState.DEFAULT_SAVE_DIRECTORY
) : ViewModel() {

    init {
        SyncStateHolder.initializeIfIdle(
            cameraHost = initialCameraHost,
            cameraWifiSsid = initialCameraWifiSsid,
            saveDirectoryPath = initialSaveDirectory
        )
    }

    val uiState: StateFlow<SyncUiState> = SyncStateHolder.state

    fun updateSettings(
        saveDirectoryPath: String,
        cameraHost: String,
        cameraWifiSsid: String
    ): Triple<String, String, String> {
        return SyncStateHolder.updateSettings(
            saveDirectoryPath = saveDirectoryPath,
            cameraHost = cameraHost,
            cameraWifiSsid = cameraWifiSsid
        )
    }

    class Factory(
        private val initialCameraHost: String,
        private val initialCameraWifiSsid: String,
        private val initialSaveDirectory: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(
                initialCameraHost = initialCameraHost,
                initialCameraWifiSsid = initialCameraWifiSsid,
                initialSaveDirectory = initialSaveDirectory
            ) as T
        }
    }
}
