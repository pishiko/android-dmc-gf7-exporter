package com.p4ko.dmcgf7exporter.storage

import android.content.Context
import com.p4ko.dmcgf7exporter.model.SyncUiState
import com.p4ko.dmcgf7exporter.network.LumixCameraClient
import com.p4ko.dmcgf7exporter.network.WifiNetworkHelper

/**
 * Persists user settings such as the save destination directory under DCIM, camera IP address,
 * and camera Wi-Fi SSID / password.
 */
class ExporterPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var saveDirectoryPath: String
        get() {
            val raw = prefs.getString(KEY_SAVE_DIRECTORY, SyncUiState.DEFAULT_SAVE_DIRECTORY)
                ?: SyncUiState.DEFAULT_SAVE_DIRECTORY
            return SavedFileMatcher.normalizeDcimRelativePath(raw)
        }
        set(value) {
            val normalized = SavedFileMatcher.normalizeDcimRelativePath(value)
            prefs.edit().putString(KEY_SAVE_DIRECTORY, normalized).apply()
        }

    var cameraHost: String
        get() {
            val raw = prefs.getString(KEY_CAMERA_HOST, SyncUiState.DEFAULT_CAMERA_HOST)
                ?: SyncUiState.DEFAULT_CAMERA_HOST
            return LumixCameraClient.sanitizeHost(raw)
        }
        set(value) {
            val sanitized = LumixCameraClient.sanitizeHost(value)
            prefs.edit().putString(KEY_CAMERA_HOST, sanitized).apply()
        }

    var cameraWifiSsid: String
        get() {
            val raw = prefs.getString(KEY_CAMERA_WIFI_SSID, WifiNetworkHelper.DEFAULT_SSID_PREFIX)
                ?: WifiNetworkHelper.DEFAULT_SSID_PREFIX
            return raw.trim().ifEmpty { WifiNetworkHelper.DEFAULT_SSID_PREFIX }
        }
        set(value) {
            prefs.edit().putString(KEY_CAMERA_WIFI_SSID, value.trim()).apply()
        }

    companion object {
        private const val PREFS_NAME = "dmc_gf7_exporter_prefs"
        private const val KEY_SAVE_DIRECTORY = "save_directory_path"
        private const val KEY_CAMERA_HOST = "camera_host"
        private const val KEY_CAMERA_WIFI_SSID = "camera_wifi_ssid"
    }
}
