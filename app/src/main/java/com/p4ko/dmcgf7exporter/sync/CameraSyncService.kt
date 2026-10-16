package com.p4ko.dmcgf7exporter.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.p4ko.dmcgf7exporter.R
import com.p4ko.dmcgf7exporter.model.SyncPhase
import com.p4ko.dmcgf7exporter.model.SyncUiState
import com.p4ko.dmcgf7exporter.network.LumixCameraClient
import com.p4ko.dmcgf7exporter.network.UrlConnectionHttpTransport
import com.p4ko.dmcgf7exporter.network.WifiNetworkHelper
import com.p4ko.dmcgf7exporter.storage.DcimSavedMediaRepository
import com.p4ko.dmcgf7exporter.storage.SavedFileMatcher
import com.p4ko.dmcgf7exporter.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service that handles the 3-step DMC-GF7 workflow:
 * 1. Connect (`ACTION_CONNECT`): Automatically connects to camera Wi-Fi AP, then performs camera handshake
 * 2. Save (`ACTION_START_SYNC`): Enumerates all items via DLNA Browse, compares with DCIM, and downloads unsaved images in background
 * 3. Disconnect (`ACTION_DISCONNECT`): Cancels active job, disconnects from camera Wi-Fi AP, and resets state
 */
class CameraSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private lateinit var wifiHelper: WifiNetworkHelper

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wifiHelper = WifiNetworkHelper(applicationContext)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                activeJob?.cancel()
                activeJob = null
                wifiHelper.disconnectFromCameraWifi()
                SyncStateHolder.markDisconnected()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                if (activeJob?.isActive == true) {
                    return START_NOT_STICKY
                }
                val host = intent.getStringExtra(EXTRA_CAMERA_HOST)
                    ?: SyncStateHolder.state.value.cameraHost
                val ssid = intent.getStringExtra(EXTRA_WIFI_SSID)
                    ?: SyncStateHolder.state.value.cameraWifiSsid
                val saveDir = intent.getStringExtra(EXTRA_SAVE_DIRECTORY)
                    ?: SyncStateHolder.state.value.saveDirectoryPath

                startForegroundWithState(
                    SyncStateHolder.state.value.copy(
                        phase = SyncPhase.CONNECTING,
                        isConnected = false,
                        cameraHost = LumixCameraClient.sanitizeHost(host),
                        cameraWifiSsid = ssid,
                        saveDirectoryPath = SavedFileMatcher.normalizeDcimRelativePath(saveDir),
                        statusMessage = "カメラのWi-Fi ($ssid) を検索・接続中...",
                        errorMessage = null
                    )
                )

                activeJob = serviceScope.launchConnect(
                    host = host,
                    ssid = ssid,
                    saveDir = saveDir
                )
            }
            ACTION_START_SYNC -> {
                if (activeJob?.isActive == true) {
                    return START_NOT_STICKY
                }
                val host = intent.getStringExtra(EXTRA_CAMERA_HOST)
                    ?: SyncStateHolder.state.value.cameraHost
                val ssid = intent.getStringExtra(EXTRA_WIFI_SSID)
                    ?: SyncStateHolder.state.value.cameraWifiSsid
                val saveDir = intent.getStringExtra(EXTRA_SAVE_DIRECTORY)
                    ?: SyncStateHolder.state.value.saveDirectoryPath
                val downloadUnsaved = intent.getBooleanExtra(EXTRA_DOWNLOAD_UNSAVED, true)
                val alreadyConnected = SyncStateHolder.state.value.isConnected

                startForegroundWithState(
                    SyncStateHolder.state.value.copy(
                        phase = SyncPhase.SCANNING,
                        isConnected = true,
                        cameraHost = LumixCameraClient.sanitizeHost(host),
                        cameraWifiSsid = ssid,
                        saveDirectoryPath = SavedFileMatcher.normalizeDcimRelativePath(saveDir),
                        statusMessage = "カメラ内画像の走査・保存を開始しています...",
                        errorMessage = null
                    )
                )

                activeJob = serviceScope.launchSync(
                    host = host,
                    ssid = ssid,
                    saveDir = saveDir,
                    downloadUnsaved = downloadUnsaved,
                    alreadyConnected = alreadyConnected
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeJob?.cancel()
        serviceScope.cancel()
        wifiHelper.disconnectFromCameraWifi()
        super.onDestroy()
    }

    private fun CoroutineScope.launchConnect(
        host: String,
        ssid: String,
        saveDir: String
    ): Job {
        return launch {
            val wakeLock = acquireWakeLock()
            val wifiLock = acquireWifiLock()
            val useCase = createUseCase()

            try {
                // 1. Connect to camera Wi-Fi AP automatically
                wifiHelper.connectToCameraWifi(
                    ssidOrPrefix = ssid,
                    cameraHost = host,
                    onStatusUpdate = { status ->
                        val updated = SyncStateHolder.state.value.copy(
                            phase = SyncPhase.CONNECTING,
                            isConnected = false,
                            statusMessage = status
                        )
                        SyncStateHolder.updateState(updated)
                        updateNotification(updated)
                    },
                    onUnexpectedDisconnect = {
                        val disconnected = SyncStateHolder.state.value.copy(
                            phase = SyncPhase.IDLE,
                            isConnected = false,
                            statusMessage = "カメラのWi-Fi接続が切断されました",
                            errorMessage = null
                        )
                        SyncStateHolder.updateState(disconnected)
                        postCompletionNotification(disconnected)
                    }
                )

                // 2. Perform camera HTTP handshake (req_acc -> getstate -> playmode)
                useCase.connectOnly(
                    cameraHost = host,
                    saveDirectoryPath = saveDir,
                    onStateChanged = { state ->
                        SyncStateHolder.updateState(state)
                        updateNotification(state)
                    }
                )
            } catch (ce: CancellationException) {
                wifiHelper.disconnectFromCameraWifi()
                SyncStateHolder.markDisconnected()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                wifiHelper.disconnectFromCameraWifi()
                val errState = SyncStateHolder.state.value.copy(
                    phase = SyncPhase.ERROR,
                    isConnected = false,
                    currentFileName = null,
                    statusMessage = "接続に失敗しました",
                    errorMessage = e.message ?: "カメラとの接続中にエラーが発生しました"
                )
                SyncStateHolder.updateState(errState)
                postCompletionNotification(errState)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                releaseLocks(wakeLock, wifiLock)
                // If successfully connected, keep the foreground service running so the Wi-Fi connection
                // remains held and notification displays "Connected" with a Disconnect action.
                val current = SyncStateHolder.state.value
                if (current.isConnected) {
                    updateNotification(current)
                }
            }
        }
    }

    private fun CoroutineScope.launchSync(
        host: String,
        ssid: String,
        saveDir: String,
        downloadUnsaved: Boolean,
        alreadyConnected: Boolean
    ): Job {
        return launch {
            val wakeLock = acquireWakeLock()
            val wifiLock = acquireWifiLock()
            val useCase = createUseCase()

            try {
                if (!alreadyConnected) {
                    wifiHelper.connectToCameraWifi(
                        ssidOrPrefix = ssid,
                        cameraHost = host,
                        onStatusUpdate = { status ->
                            val updated = SyncStateHolder.state.value.copy(statusMessage = status)
                            SyncStateHolder.updateState(updated)
                            updateNotification(updated)
                        },
                        onUnexpectedDisconnect = {
                            val disconnected = SyncStateHolder.state.value.copy(
                                phase = SyncPhase.IDLE,
                                isConnected = false,
                                statusMessage = "カメラのWi-Fi接続が切断されました"
                            )
                            SyncStateHolder.updateState(disconnected)
                            postCompletionNotification(disconnected)
                        }
                    )
                }

                useCase.execute(
                    cameraHost = host,
                    saveDirectoryPath = saveDir,
                    downloadUnsaved = downloadUnsaved,
                    alreadyConnected = alreadyConnected,
                    onStateChanged = { state ->
                        SyncStateHolder.updateState(state)
                        updateNotification(state)
                    }
                )
            } catch (ce: CancellationException) {
                if (SyncStateHolder.state.value.isConnected) {
                    val cancelledState = SyncStateHolder.state.value.copy(
                        phase = SyncPhase.CANCELLED,
                        currentFileName = null,
                        statusMessage = "保存処理を中断しました"
                    )
                    SyncStateHolder.updateState(cancelledState)
                    updateNotification(cancelledState)
                }
            } catch (e: Exception) {
                val errState = SyncStateHolder.state.value.copy(
                    phase = SyncPhase.ERROR,
                    currentFileName = null,
                    statusMessage = "保存中にエラーが発生しました",
                    errorMessage = e.message ?: "カメラとの通信中にエラーが発生しました"
                )
                SyncStateHolder.updateState(errState)
                postCompletionNotification(errState)
            } finally {
                releaseLocks(wakeLock, wifiLock)
                val finalState = SyncStateHolder.state.value
                if (finalState.phase == SyncPhase.COMPLETED) {
                    postCompletionNotification(finalState)
                }
                // Keep connected notification alive until user explicitly taps "切断"
                if (finalState.isConnected) {
                    updateNotification(finalState)
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun createUseCase(): CameraSyncUseCase {
        val transport = UrlConnectionHttpTransport { url ->
            wifiHelper.openConnection(url)
        }
        val cameraClient = LumixCameraClient(transport = transport)
        val repository = DcimSavedMediaRepository(applicationContext)
        return CameraSyncUseCase(
            cameraClient = cameraClient,
            savedMediaRepository = repository
        )
    }

    private fun startForegroundWithState(state: SyncUiState) {
        SyncStateHolder.updateState(state)
        val notification = buildProgressNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    private fun updateNotification(state: SyncUiState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID_PROGRESS, buildProgressNotification(state))
    }

    private fun postCompletionNotification(state: SyncUiState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val title = when (state.phase) {
            SyncPhase.COMPLETED -> "LUMIX DMC-GF7 保存完了"
            SyncPhase.CANCELLED -> "LUMIX DMC-GF7 中断"
            SyncPhase.ERROR -> "LUMIX DMC-GF7 エラー"
            else -> getString(R.string.app_name)
        }
        val body = state.errorMessage ?: state.statusMessage
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(createContentPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(NOTIFICATION_ID_RESULT, notification)
    }

    private fun buildProgressNotification(state: SyncUiState): Notification {
        val disconnectIntent = Intent(this, CameraSyncService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val indeterminate = state.phase == SyncPhase.CONNECTING ||
            state.phase == SyncPhase.COMPARING ||
            (state.phase == SyncPhase.SCANNING && state.totalCameraCount == 0)

        val title = when (state.phase) {
            SyncPhase.CONNECTING -> "LUMIX DMC-GF7 接続中"
            SyncPhase.CONNECTED -> "LUMIX DMC-GF7 接続済み"
            SyncPhase.COMPLETED -> "LUMIX DMC-GF7 保存完了"
            else -> "LUMIX DMC-GF7 同期中 (${state.progressPercent}%)"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (state.phase == SyncPhase.CONNECTED || state.phase == SyncPhase.COMPLETED) {
                    android.R.drawable.stat_sys_download_done
                } else {
                    android.R.drawable.stat_sys_download
                }
            )
            .setContentTitle(title)
            .setContentText(state.statusMessage)
            .setSubText("総数:${state.totalCameraCount} / 保存済:${state.savedCount} / 未保存:${state.unsavedCount}")
            .setOngoing(state.isRunning || state.isConnected)
            .setOnlyAlertOnce(true)
            .setContentIntent(createContentPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.button_disconnect),
                disconnectPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.isRunning) {
            builder.setProgress(100, state.progressPercent, indeterminate)
        }

        return builder.build()
    }

    private fun createContentPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DMC-GF7 画像同期",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "カメラからの接続・画像走査・ダウンロード進捗を表示します"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? {
        return runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DmcGf7Exporter::SyncWakeLock").apply {
                acquire(MAX_LOCK_DURATION_MS)
            }
        }.getOrNull()
    }

    private fun acquireWifiLock(): WifiManager.WifiLock? {
        return runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wm.createWifiLock(lockMode, "DmcGf7Exporter::SyncWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseLocks(
        wakeLock: PowerManager.WakeLock?,
        wifiLock: WifiManager.WifiLock?
    ) {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
        runCatching {
            if (wifiLock?.isHeld == true) {
                wifiLock.release()
            }
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.p4ko.dmcgf7exporter.action.CONNECT"
        const val ACTION_START_SYNC = "com.p4ko.dmcgf7exporter.action.START_SYNC"
        const val ACTION_DISCONNECT = "com.p4ko.dmcgf7exporter.action.DISCONNECT"
        const val EXTRA_CAMERA_HOST = "extra_camera_host"
        const val EXTRA_WIFI_SSID = "extra_wifi_ssid"
        const val EXTRA_SAVE_DIRECTORY = "extra_save_directory"
        const val EXTRA_DOWNLOAD_UNSAVED = "extra_download_unsaved"

        private const val CHANNEL_ID = "dmc_gf7_sync_channel"
        private const val NOTIFICATION_ID_PROGRESS = 1001
        private const val NOTIFICATION_ID_RESULT = 1002
        private const val MAX_LOCK_DURATION_MS = 2 * 60 * 60 * 1000L // 2 hours max

        fun connect(
            context: Context,
            cameraHost: String,
            cameraWifiSsid: String,
            saveDirectoryPath: String
        ) {
            val intent = Intent(context, CameraSyncService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_CAMERA_HOST, cameraHost)
                putExtra(EXTRA_WIFI_SSID, cameraWifiSsid)
                putExtra(EXTRA_SAVE_DIRECTORY, saveDirectoryPath)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startSync(
            context: Context,
            cameraHost: String,
            cameraWifiSsid: String,
            saveDirectoryPath: String,
            downloadUnsaved: Boolean
        ) {
            val intent = Intent(context, CameraSyncService::class.java).apply {
                action = ACTION_START_SYNC
                putExtra(EXTRA_CAMERA_HOST, cameraHost)
                putExtra(EXTRA_WIFI_SSID, cameraWifiSsid)
                putExtra(EXTRA_SAVE_DIRECTORY, saveDirectoryPath)
                putExtra(EXTRA_DOWNLOAD_UNSAVED, downloadUnsaved)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, CameraSyncService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}
