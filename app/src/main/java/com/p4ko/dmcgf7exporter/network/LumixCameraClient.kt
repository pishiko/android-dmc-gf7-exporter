package com.p4ko.dmcgf7exporter.network

import com.p4ko.dmcgf7exporter.model.CameraMediaItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder

/**
 * Client for communicating with Panasonic LUMIX DMC-GF7 over Wi-Fi (`cam.cgi` + DLNA `ContentDirectory:Browse`).
 *
 * Implements the Image App / libgphoto2 sequence:
 * 1. Request access (`cam.cgi?mode=accctrl&type=req_acc...`)
 * 2. Poll `getstate` while the camera waits for on-screen user approval (`ok_under_research` / `err_busy`)
 * 3. Register device name (`cam.cgi?mode=setsetting&type=device_name...`)
 * 4. Switch to playback mode (`cam.cgi?mode=camcmd&value=playmode`)
 * 5. Fetch content count (`cam.cgi?mode=get_content_info`)
 * 6. Enumerate all SD card contents via DLNA `ContentDirectory:1#Browse` paging (`StartingIndex`, `RequestedCount`)
 * 7. Download missing files from port `50001` with automatic retry.
 */
class LumixCameraClient(
    private val transport: HttpTransport,
    private val clientUuid: String = DEFAULT_CLIENT_UUID,
    private val deviceName: String = DEFAULT_DEVICE_NAME,
    private val retryDelayMs: Long = 500L
) {

    /**
     * Performs the initial handshake with the DMC-GF7, waits for the user to accept the connection
     * request on the camera screen if prompted, and switches the camera to playback mode (`playmode`).
     */
    suspend fun connectAndEnterPlayMode(
        host: String,
        maxHandshakeAttempts: Int = 5,
        maxApprovalPolls: Int = 60,
        onStatusUpdate: (String) -> Unit = {}
    ) {
        val cleanHost = sanitizeHost(host)
        onStatusUpdate("GF7 ($cleanHost) へ接続リクエストを送信中...")

        var accessAccepted = false
        var needsApprovalWait = false
        val encodedName = URLEncoder.encode(deviceName, "UTF-8")

        for (attempt in 1..maxHandshakeAttempts) {
            currentCoroutineContext().ensureActive()
            val reqAccUrl = "http://$cleanHost/cam.cgi?mode=accctrl&type=req_acc&value=$clientUuid&value2=$encodedName"
            val response = try {
                transport.get(reqAccUrl)
            } catch (e: IOException) {
                if (attempt == maxHandshakeAttempts) {
                    throw IOException("カメラ ($cleanHost) に接続できません。GF7のWi-Fiに接続されているか確認してください。", e)
                }
                delay(retryDelayMs)
                continue
            }

            val reply = LumixXmlParser.parseCamReply(response.body)
            if (reply.isOk) {
                accessAccepted = true
                if (reply.status == CamReplyStatus.OK_UNDER_RESEARCH) {
                    needsApprovalWait = true
                }
                break
            }

            // Fallback to libgphoto2's `value=0` format if UUID handshake didn't immediately succeed
            val fallbackUrl = "http://$cleanHost/cam.cgi?mode=accctrl&type=req_acc&value=0&value2=$encodedName"
            val fallbackResp = runCatching { transport.get(fallbackUrl) }.getOrNull()
            if (fallbackResp != null) {
                val fallbackReply = LumixXmlParser.parseCamReply(fallbackResp.body)
                if (fallbackReply.isOk) {
                    accessAccepted = true
                    if (fallbackReply.status == CamReplyStatus.OK_UNDER_RESEARCH) {
                        needsApprovalWait = true
                    }
                    break
                }
            }

            // If camera is showing prompt or busy during handshake, wait for user approval
            if (reply.status == CamReplyStatus.BUSY) {
                needsApprovalWait = true
                accessAccepted = true
                break
            }

            if (attempt < maxHandshakeAttempts) {
                delay(retryDelayMs)
            }
        }

        if (!accessAccepted) {
            throw IOException("カメラとのハンドシェイク (req_acc) に失敗しました。")
        }

        // Wait for user to accept the connection request on the camera screen if needed
        if (needsApprovalWait) {
            onStatusUpdate("カメラ画面で接続許可を求められています。カメラ側で「はい」を選択してください...")
            waitForCameraReady(
                host = cleanHost,
                encodedName = encodedName,
                maxPolls = maxApprovalPolls,
                onStatusUpdate = onStatusUpdate
            )
        }

        // Set device name
        runCatching {
            transport.get("http://$cleanHost/cam.cgi?mode=setsetting&type=device_name&value=$encodedName")
        }

        // Switch to playmode (also confirms the camera accepted the remote session)
        onStatusUpdate("再生モード (playmode) に切り替え中...")
        switchToPlayMode(cleanHost, maxAttempts = maxHandshakeAttempts)
    }

    /**
     * Switches the camera to playback mode (`cam.cgi?mode=camcmd&value=playmode`), retrying if `err_busy`.
     */
    suspend fun switchToPlayMode(host: String, maxAttempts: Int = 10) {
        val cleanHost = sanitizeHost(host)
        val url = "http://$cleanHost/cam.cgi?mode=camcmd&value=playmode"
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            val response = transport.get(url)
            val reply = LumixXmlParser.parseCamReply(response.body)
            if (reply.isOk) {
                return
            }
            if (reply.status == CamReplyStatus.BUSY && attempt < maxAttempts) {
                delay(retryDelayMs)
                continue
            }
            if (attempt == maxAttempts) {
                throw IOException("再生モード (playmode) への切り替えに失敗しました: ${reply.rawResultText}")
            }
            delay(retryDelayMs)
        }
    }

    /**
     * Queries `cam.cgi?mode=get_content_info` for the total content count in the camera.
     * Returns null if the camera does not support or fails to report it (DLNA TotalMatches will still be used).
     */
    suspend fun fetchContentInfo(host: String, maxAttempts: Int = 5): LumixContentInfo? {
        val cleanHost = sanitizeHost(host)
        val url = "http://$cleanHost/cam.cgi?mode=get_content_info"
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            val response = runCatching { transport.get(url) }.getOrNull() ?: break
            val reply = LumixXmlParser.parseCamReply(response.body)
            if (reply.status == CamReplyStatus.BUSY && attempt < maxAttempts) {
                delay(retryDelayMs)
                continue
            }
            return LumixXmlParser.parseContentInfo(response.body)
        }
        return null
    }

    /**
     * Enumerates all media items on the camera's SD card using DLNA `ContentDirectory:1#Browse` paging.
     *
     * Starts at `StartingIndex = 0` and loops with `RequestedCount = pageSize` until the end of the SD card
     * (`StartingIndex >= TotalMatches` or `NumberReturned == 0`).
     */
    suspend fun enumerateAllMedia(
        host: String,
        pageSize: Int = DEFAULT_BROWSE_PAGE_SIZE,
        expectedTotalHint: Int? = null,
        onProgress: (scannedCount: Int, totalCount: Int) -> Unit = { _, _ -> }
    ): List<CameraMediaItem> {
        require(pageSize > 0) { "pageSize must be positive" }
        val cleanHost = sanitizeHost(host)
        var controlUrl = "http://$cleanHost:$DLNA_PORT$DEFAULT_CDS_CONTROL_PATH"

        val allItemsById = linkedMapOf<String, CameraMediaItem>()
        var startingIndex = 0
        var knownTotalMatches = expectedTotalHint ?: 0
        var discoveredCustomControlUrl = false

        while (true) {
            currentCoroutineContext().ensureActive()
            val page = try {
                browsePage(
                    controlUrl = controlUrl,
                    startingIndex = startingIndex,
                    requestedCount = pageSize
                )
            } catch (e: Exception) {
                // On the very first page, if the default /Server0/CDS_control fails,
                // try discovering the ContentDirectory controlURL from /Server0/ddd.
                if (startingIndex == 0 && !discoveredCustomControlUrl) {
                    discoveredCustomControlUrl = true
                    val discovered = discoverControlUrl(cleanHost)
                    if (discovered != null && discovered != controlUrl) {
                        controlUrl = discovered
                        browsePage(
                            controlUrl = controlUrl,
                            startingIndex = startingIndex,
                            requestedCount = pageSize
                        )
                    } else {
                        throw e
                    }
                } else {
                    throw e
                }
            }

            if (page.totalMatches > 0) {
                knownTotalMatches = page.totalMatches
            }

            for (item in page.items) {
                allItemsById.putIfAbsent(item.id, item)
            }

            val advanceBy = if (page.numberReturned > 0) page.numberReturned else page.items.size
            if (advanceBy <= 0) {
                break
            }

            startingIndex += advanceBy
            val effectiveTotal = if (knownTotalMatches > 0) knownTotalMatches else allItemsById.size
            onProgress(allItemsById.size, effectiveTotal)

            if (knownTotalMatches > 0 && startingIndex >= knownTotalMatches) {
                break
            }
        }

        return allItemsById.values.toList()
    }

    /**
     * Executes a single DLNA `ContentDirectory:1#Browse` SOAP call.
     */
    suspend fun browsePage(
        controlUrl: String,
        startingIndex: Int,
        requestedCount: Int,
        objectId: String = "0",
        maxAttempts: Int = 3
    ): DlnaBrowsePage {
        val soapBody = LumixXmlParser.buildBrowseSoapEnvelope(
            objectId = objectId,
            startingIndex = startingIndex,
            requestedCount = requestedCount
        )
        val headers = mapOf(
            "SOAPAction" to "\"$BROWSE_SOAP_ACTION\"",
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "Accept" to "text/xml"
        )

        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                val response = transport.post(
                    url = controlUrl,
                    body = soapBody,
                    headers = headers
                )
                if (!response.isSuccessful) {
                    throw IOException("DLNA Browse failed with HTTP ${response.statusCode}")
                }
                return LumixXmlParser.parseBrowseSoapResponse(response.body)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts) {
                    delay(retryDelayMs * attempt)
                }
            }
        }
        throw IOException("DLNA Browse ページの取得に失敗しました (index=$startingIndex)", lastException)
    }

    /**
     * Downloads a single media file from the camera's HTTP media server (port 50001) with retry logic.
     */
    suspend fun downloadFile(
        host: String,
        resourceUrl: String,
        outputStreamProvider: () -> OutputStream,
        maxRetries: Int = 3
    ): Long {
        val cleanHost = sanitizeHost(host)
        var lastError: Exception? = null

        for (attempt in 0..maxRetries) {
            currentCoroutineContext().ensureActive()
            try {
                return outputStreamProvider().use { outputStream ->
                    transport.downloadToStream(resourceUrl, outputStream)
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    delay(retryDelayMs * (attempt + 1))
                    // Re-assert playmode as recommended in libgphoto2 when a transfer is interrupted
                    runCatching { switchToPlayMode(cleanHost, maxAttempts = 1) }
                }
            }
        }
        throw IOException("画像のダウンロードに失敗しました: $resourceUrl", lastError)
    }

    private suspend fun waitForCameraReady(
        host: String,
        encodedName: String,
        maxPolls: Int,
        onStatusUpdate: (String) -> Unit
    ) {
        val stateUrl = "http://$host/cam.cgi?mode=getstate"
        for (poll in 1..maxPolls) {
            currentCoroutineContext().ensureActive()
            val resp = runCatching { transport.get(stateUrl) }.getOrNull()
            if (resp != null) {
                val reply = LumixXmlParser.parseCamReply(resp.body)
                if (reply.status == CamReplyStatus.OK) {
                    return
                }
            }
            onStatusUpdate("カメラ側で接続リクエストを許可してください... ($poll/${maxPolls})")
            delay(maxOf(retryDelayMs, 1000L))
            if (poll % 5 == 0) {
                runCatching {
                    transport.get("http://$host/cam.cgi?mode=accctrl&type=req_acc&value=$clientUuid&value2=$encodedName")
                }
            }
        }
        throw IOException("カメラ側での接続許可が確認できませんでした。カメラ画面で「はい」を押してから再度「接続」してください。")
    }

    private fun discoverControlUrl(host: String): String? {
        val baseUrl = "http://$host:$DLNA_PORT"
        val dddUrl = "$baseUrl$DEFAULT_DDD_PATH"
        val response = runCatching { transport.get(dddUrl) }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        return LumixXmlParser.parseDeviceDescriptionControlUrl(response.body, baseUrl)
    }

    companion object {
        const val DLNA_PORT = 60606
        const val MEDIA_PORT = 50001
        const val DEFAULT_CDS_CONTROL_PATH = "/Server0/CDS_control"
        const val DEFAULT_DDD_PATH = "/Server0/ddd"
        const val BROWSE_SOAP_ACTION = "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"
        const val DEFAULT_BROWSE_PAGE_SIZE = 15
        const val DEFAULT_CLIENT_UUID = "4D454930-0100-1000-8001-020A0005A9CF"
        const val DEFAULT_DEVICE_NAME = "DMC-GF7-Exporter"

        fun sanitizeHost(rawHost: String): String {
            return rawHost.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .substringBefore(':')
                .ifEmpty { "192.168.54.1" }
        }
    }
}
