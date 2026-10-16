package com.p4ko.dmcgf7exporter.model

enum class SyncPhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    SCANNING,
    COMPARING,
    DOWNLOADING,
    COMPLETED,
    CANCELLED,
    ERROR
}

/**
 * Immutable UI state representing camera connection, counts, saved/unsaved comparison, and sync progress.
 */
data class SyncUiState(
    val phase: SyncPhase = SyncPhase.IDLE,
    val isConnected: Boolean = false,
    val cameraHost: String = DEFAULT_CAMERA_HOST,
    val cameraWifiSsid: String = DEFAULT_WIFI_SSID,
    val saveDirectoryPath: String = DEFAULT_SAVE_DIRECTORY,
    val totalCameraCount: Int = 0,
    val savedCount: Int = 0,
    val unsavedCount: Int = 0,
    val downloadedCount: Int = 0,
    val failedCount: Int = 0,
    val totalToDownloadCount: Int = 0,
    val scannedItemsCount: Int = 0,
    val currentFileName: String? = null,
    val statusMessage: String = "未接続",
    val errorMessage: String? = null
) {
    val isRunning: Boolean
        get() = phase == SyncPhase.CONNECTING ||
            phase == SyncPhase.SCANNING ||
            phase == SyncPhase.COMPARING ||
            phase == SyncPhase.DOWNLOADING

    val canConnect: Boolean
        get() = !isConnected && !isRunning

    val canSave: Boolean
        get() = isConnected && !isRunning

    val canDisconnect: Boolean
        get() = isConnected || isRunning

    val progressPercent: Int
        get() = when {
            phase == SyncPhase.SCANNING && totalCameraCount > 0 ->
                ((scannedItemsCount.toDouble() / totalCameraCount) * 100).toInt().coerceIn(0, 100)
            totalToDownloadCount > 0 ->
                (((downloadedCount + failedCount).toDouble() / totalToDownloadCount) * 100).toInt().coerceIn(0, 100)
            phase == SyncPhase.COMPLETED -> 100
            else -> 0
        }

    companion object {
        const val DEFAULT_CAMERA_HOST = "192.168.54.1"
        const val DEFAULT_WIFI_SSID = "MYCAM"
        const val DEFAULT_SAVE_DIRECTORY = "DCIM/DMC-GF7"
    }
}
