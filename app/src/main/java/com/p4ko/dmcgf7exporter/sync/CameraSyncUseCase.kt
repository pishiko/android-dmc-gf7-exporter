package com.p4ko.dmcgf7exporter.sync

import com.p4ko.dmcgf7exporter.model.ExportTarget
import com.p4ko.dmcgf7exporter.model.MediaSelectionPolicy
import com.p4ko.dmcgf7exporter.model.PriorityMediaSelectionPolicy
import com.p4ko.dmcgf7exporter.model.SyncPhase
import com.p4ko.dmcgf7exporter.model.SyncUiState
import com.p4ko.dmcgf7exporter.network.LumixCameraClient
import com.p4ko.dmcgf7exporter.storage.SavedFileMatcher
import com.p4ko.dmcgf7exporter.storage.SavedMediaRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream

data class SyncExecutionSummary(
    val totalCameraCount: Int,
    val savedCount: Int,
    val unsavedCount: Int,
    val downloadedCount: Int,
    val failedCount: Int
)

/**
 * Coordinates the LUMIX DMC-GF7 connection and image export workflow:
 * - Step 1 (`connectOnly`): Handshake with GF7 (`req_acc`), wait for camera-side approval, and enter `playmode`.
 * - Step 2 (`execute`):
 *   1. Ensure camera is in `playmode` (or perform full handshake if not yet connected)
 *   2. Enumerate all camera items on the SD card using DLNA `Browse` paging until the end
 *   3. Select target media resources (JPEG Original prioritized; extensible for RAW/Video)
 *   4. Compare camera images with saved images in Android's DCIM directory by filename
 *   5. Automatically download only images that do not exist on the Android device
 */
class CameraSyncUseCase(
    private val cameraClient: LumixCameraClient,
    private val savedMediaRepository: SavedMediaRepository,
    private val selectionPolicy: MediaSelectionPolicy = PriorityMediaSelectionPolicy()
) {

    /**
     * Step 1 ("接続" button): Connects to the camera, waits for the user to accept the prompt on the camera LCD,
     * switches to playback mode, and fetches the initial content count.
     */
    suspend fun connectOnly(
        cameraHost: String,
        saveDirectoryPath: String,
        onStateChanged: (SyncUiState) -> Unit
    ) {
        val normalizedHost = LumixCameraClient.sanitizeHost(cameraHost)
        val normalizedDir = SavedFileMatcher.normalizeDcimRelativePath(saveDirectoryPath)

        var currentState = SyncUiState(
            phase = SyncPhase.CONNECTING,
            isConnected = false,
            cameraHost = normalizedHost,
            saveDirectoryPath = normalizedDir,
            statusMessage = "DMC-GF7 ($normalizedHost) に接続リクエスト中..."
        )
        onStateChanged(currentState)

        cameraClient.connectAndEnterPlayMode(
            host = normalizedHost,
            onStatusUpdate = { statusMsg ->
                currentState = currentState.copy(statusMessage = statusMsg)
                onStateChanged(currentState)
            }
        )
        currentCoroutineContext().ensureActive()

        val contentInfo = cameraClient.fetchContentInfo(normalizedHost)
        val totalInCamera = contentInfo?.contentNumber ?: 0

        currentState = currentState.copy(
            phase = SyncPhase.CONNECTED,
            isConnected = true,
            totalCameraCount = totalInCamera,
            statusMessage = if (totalInCamera > 0) {
                "カメラと接続しました: $totalInCamera 件。「保存」を押すと未保存画像をダウンロードします。"
            } else {
                "カメラと接続しました。「保存」を押すと未保存画像をダウンロードします。"
            },
            errorMessage = null
        )
        onStateChanged(currentState)
    }

    /**
     * Step 2 ("保存" button): Enumerates all photos via DLNA Browse paging, compares with saved files in DCIM,
     * and downloads only unsaved images.
     */
    suspend fun execute(
        cameraHost: String,
        saveDirectoryPath: String,
        downloadUnsaved: Boolean = true,
        alreadyConnected: Boolean = false,
        browsePageSize: Int = LumixCameraClient.DEFAULT_BROWSE_PAGE_SIZE,
        onStateChanged: (SyncUiState) -> Unit
    ): SyncExecutionSummary {
        val normalizedHost = LumixCameraClient.sanitizeHost(cameraHost)
        val normalizedDir = SavedFileMatcher.normalizeDcimRelativePath(saveDirectoryPath)

        var currentState = SyncUiState(
            phase = if (alreadyConnected) SyncPhase.SCANNING else SyncPhase.CONNECTING,
            isConnected = alreadyConnected,
            cameraHost = normalizedHost,
            saveDirectoryPath = normalizedDir,
            statusMessage = if (alreadyConnected) {
                "再生モードを確認中..."
            } else {
                "DMC-GF7 ($normalizedHost) に接続中..."
            }
        )
        onStateChanged(currentState)

        // 1. Connect or ensure playmode
        if (alreadyConnected) {
            cameraClient.switchToPlayMode(normalizedHost)
        } else {
            cameraClient.connectAndEnterPlayMode(
                host = normalizedHost,
                onStatusUpdate = { statusMsg ->
                    currentState = currentState.copy(statusMessage = statusMsg)
                    onStateChanged(currentState)
                }
            )
            currentState = currentState.copy(isConnected = true)
        }
        currentCoroutineContext().ensureActive()

        // 2. Fetch initial content info (optional hint for total count)
        val contentInfo = cameraClient.fetchContentInfo(normalizedHost)
        val initialTotalHint = contentInfo?.contentNumber ?: 0
        currentState = currentState.copy(
            phase = SyncPhase.SCANNING,
            isConnected = true,
            totalCameraCount = initialTotalHint,
            statusMessage = "SDカード内の画像をDLNA Browseで全件走査中..."
        )
        onStateChanged(currentState)

        // 3. Enumerate all items on SD card using DLNA Browse paging until the end
        val cameraItems = cameraClient.enumerateAllMedia(
            host = normalizedHost,
            pageSize = browsePageSize,
            expectedTotalHint = initialTotalHint.takeIf { it > 0 },
            onProgress = { scannedCount, totalCount ->
                currentState = currentState.copy(
                    phase = SyncPhase.SCANNING,
                    isConnected = true,
                    scannedItemsCount = scannedCount,
                    totalCameraCount = maxOf(totalCount, scannedCount),
                    statusMessage = "カメラ内画像を走査中... ($scannedCount / ${maxOf(totalCount, scannedCount)} 件)"
                )
                onStateChanged(currentState)
            }
        )
        currentCoroutineContext().ensureActive()

        // 4. Select export targets (JPEG Original prioritized)
        val targets: List<ExportTarget> = cameraItems.flatMap { item ->
            selectionPolicy.selectTargets(item)
        }

        // 5. Compare camera targets with saved filenames in Android DCIM directory
        currentState = currentState.copy(
            phase = SyncPhase.COMPARING,
            isConnected = true,
            totalCameraCount = targets.size,
            scannedItemsCount = cameraItems.size,
            statusMessage = "端末内の保存済み画像 ($normalizedDir) と比較中..."
        )
        onStateChanged(currentState)

        val savedFileNames = savedMediaRepository.listSavedFileNames(normalizedDir)
        val comparison = SavedFileMatcher.partitionTargets(targets, savedFileNames)

        var savedCount = comparison.savedTargets.size
        var unsavedCount = comparison.unsavedTargets.size
        val toDownload = comparison.unsavedTargets

        if (!downloadUnsaved || toDownload.isEmpty()) {
            val msg = if (targets.isEmpty()) {
                "カメラ内に画像が見つかりませんでした"
            } else if (toDownload.isEmpty()) {
                "すべての画像 ($savedCount 件) が保存済みです"
            } else {
                "スキャン完了: 未保存画像が $unsavedCount 件あります"
            }
            currentState = currentState.copy(
                phase = SyncPhase.COMPLETED,
                isConnected = true,
                totalCameraCount = targets.size,
                savedCount = savedCount,
                unsavedCount = unsavedCount,
                downloadedCount = 0,
                failedCount = 0,
                totalToDownloadCount = toDownload.size,
                currentFileName = null,
                statusMessage = msg
            )
            onStateChanged(currentState)
            return SyncExecutionSummary(
                totalCameraCount = targets.size,
                savedCount = savedCount,
                unsavedCount = unsavedCount,
                downloadedCount = 0,
                failedCount = 0
            )
        }

        // 6. Download only unsaved images into Android DCIM directory
        var downloadedCount = 0
        var failedCount = 0

        for ((index, target) in toDownload.withIndex()) {
            currentCoroutineContext().ensureActive()
            val fileName = target.primaryFileName

            currentState = currentState.copy(
                phase = SyncPhase.DOWNLOADING,
                isConnected = true,
                totalCameraCount = targets.size,
                savedCount = savedCount,
                unsavedCount = unsavedCount,
                downloadedCount = downloadedCount,
                failedCount = failedCount,
                totalToDownloadCount = toDownload.size,
                currentFileName = fileName,
                statusMessage = "ダウンロード中 (${index + 1}/${toDownload.size}): $fileName"
            )
            onStateChanged(currentState)

            try {
                val buffer = ByteArrayOutputStream()
                cameraClient.downloadFile(
                    host = normalizedHost,
                    resourceUrl = target.resource.url,
                    outputStreamProvider = {
                        buffer.reset()
                        buffer
                    }
                )
                savedMediaRepository.saveMediaStream(
                    relativeDirPath = normalizedDir,
                    fileName = fileName,
                    format = target.resource.format,
                    writeAction = { outputStream ->
                        buffer.writeTo(outputStream)
                    }
                )
                downloadedCount++
                savedCount++
                unsavedCount = (unsavedCount - 1).coerceAtLeast(0)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                failedCount++
            }

            currentState = currentState.copy(
                phase = SyncPhase.DOWNLOADING,
                isConnected = true,
                totalCameraCount = targets.size,
                savedCount = savedCount,
                unsavedCount = unsavedCount,
                downloadedCount = downloadedCount,
                failedCount = failedCount,
                totalToDownloadCount = toDownload.size,
                currentFileName = fileName
            )
            onStateChanged(currentState)
        }

        val finalMessage = if (failedCount == 0) {
            "保存完了: 新規 $downloadedCount 件を $normalizedDir に保存しました"
        } else {
            "同期完了: $downloadedCount 件保存 / $failedCount 件失敗 ($normalizedDir)"
        }

        currentState = currentState.copy(
            phase = SyncPhase.COMPLETED,
            isConnected = true,
            totalCameraCount = targets.size,
            savedCount = savedCount,
            unsavedCount = unsavedCount,
            downloadedCount = downloadedCount,
            failedCount = failedCount,
            totalToDownloadCount = toDownload.size,
            currentFileName = null,
            statusMessage = finalMessage
        )
        onStateChanged(currentState)

        return SyncExecutionSummary(
            totalCameraCount = targets.size,
            savedCount = savedCount,
            unsavedCount = unsavedCount,
            downloadedCount = downloadedCount,
            failedCount = failedCount
        )
    }
}
