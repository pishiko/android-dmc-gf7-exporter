package com.p4ko.dmcgf7exporter.sync

import com.p4ko.dmcgf7exporter.model.MediaFormat
import com.p4ko.dmcgf7exporter.model.SyncPhase
import com.p4ko.dmcgf7exporter.model.SyncUiState
import com.p4ko.dmcgf7exporter.network.HttpResponse
import com.p4ko.dmcgf7exporter.network.HttpTransport
import com.p4ko.dmcgf7exporter.network.LumixCameraClient
import com.p4ko.dmcgf7exporter.storage.SavedMediaRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class CameraSyncUseCaseTest {

    @Test
    fun execute_pagesThroughAllDlnaBrowseItemsAndDownloadsOnlyUnsavedJpegs() = runTest {
        val allIds = listOf("1030001", "1030002", "1030003", "1030004", "1030005")
        val browseRequests = mutableListOf<Pair<Int, Int>>() // (StartingIndex, RequestedCount)
        val downloadedUrls = mutableListOf<String>()

        val fakeTransport = object : HttpTransport {
            override fun get(
                url: String,
                headers: Map<String, String>,
                timeoutMs: Int
            ): HttpResponse {
                return when {
                    url.contains("mode=accctrl&type=req_acc") ->
                        HttpResponse(200, """<?xml version="1.0" encoding="UTF-8"?><camrply><result>ok</result></camrply>""")
                    url.contains("mode=setsetting&type=device_name") ->
                        HttpResponse(200, """<?xml version="1.0" encoding="UTF-8"?><camrply><result>ok</result></camrply>""")
                    url.contains("mode=camcmd&value=playmode") ->
                        HttpResponse(200, """<?xml version="1.0" encoding="UTF-8"?><camrply><result>ok</result></camrply>""")
                    url.contains("mode=get_content_info") ->
                        HttpResponse(
                            200,
                            """<?xml version="1.0" encoding="UTF-8"?><camrply><result>ok</result><current_position>5</current_position><total_content_number>5</total_content_number><content_number>5</content_number></camrply>"""
                        )
                    else -> HttpResponse(404, "Not found: $url")
                }
            }

            override fun post(
                url: String,
                body: String,
                headers: Map<String, String>,
                timeoutMs: Int
            ): HttpResponse {
                val startIdx = Regex("<StartingIndex>(\\d+)</StartingIndex>")
                    .find(body)!!.groupValues[1].toInt()
                val reqCount = Regex("<RequestedCount>(\\d+)</RequestedCount>")
                    .find(body)!!.groupValues[1].toInt()
                browseRequests.add(startIdx to reqCount)

                val slice = allIds.drop(startIdx).take(reqCount)
                val itemsXml = slice.joinToString("") { id ->
                    val title = id.substring(0, 3) + "-" + id.substring(3)
                    """
                    <item id="$id" parentID="0" restricted="0">
                        <dc:title>$title</dc:title>
                        <upnp:writeStatus>WRITABLE</upnp:writeStatus>
                        <upnp:class name="imageItem">object.item.imageItem</upnp:class>
                        <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;PANASONIC.COM_PN=CAM_ORG">http://192.168.54.1:50001/DO$id.JPG</res>
                        <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN;PANASONIC.COM_PN=CAM_TN">http://192.168.54.1:50001/DT$id.JPG</res>
                    </item>
                    """.trimIndent()
                }
                val didl = """
                    <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                               xmlns:dc="http://purl.org/dc/elements/1.1/"
                               xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                        $itemsXml
                    </DIDL-Lite>
                """.trimIndent()
                val escapedDidl = didl
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")

                val soapResp = """
                    <?xml version="1.0"?>
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                        <s:Body>
                            <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                                <Result>$escapedDidl</Result>
                                <NumberReturned>${slice.size}</NumberReturned>
                                <TotalMatches>${allIds.size}</TotalMatches>
                                <UpdateID>1</UpdateID>
                            </u:BrowseResponse>
                        </s:Body>
                    </s:Envelope>
                """.trimIndent()
                return HttpResponse(200, soapResp)
            }

            override fun downloadToStream(
                url: String,
                outputStream: OutputStream,
                timeoutMs: Int
            ): Long {
                downloadedUrls.add(url)
                val dummyJpeg = "JPEG_BYTES_FOR_$url".toByteArray()
                outputStream.write(dummyJpeg)
                return dummyJpeg.size.toLong()
            }
        }

        // Suppose 1030001 (as DO1030001.JPG) and 1030003 (as P1030003.JPG) are already saved on Android
        val savedFilesInDcim = mutableMapOf(
            "DO1030001.JPG" to "EXISTING_1".toByteArray(),
            "P1030003.JPG" to "EXISTING_3".toByteArray()
        )
        val savedDirsUsed = mutableListOf<String>()

        val fakeRepository = object : SavedMediaRepository {
            override fun listSavedFileNames(relativeDirPath: String): Set<String> {
                savedDirsUsed.add(relativeDirPath)
                return savedFilesInDcim.keys.toSet()
            }

            override fun saveMediaStream(
                relativeDirPath: String,
                fileName: String,
                format: MediaFormat,
                writeAction: (OutputStream) -> Unit
            ) {
                savedDirsUsed.add(relativeDirPath)
                val out = ByteArrayOutputStream()
                writeAction(out)
                savedFilesInDcim[fileName] = out.toByteArray()
            }
        }

        val client = LumixCameraClient(
            transport = fakeTransport,
            retryDelayMs = 1L
        )
        val useCase = CameraSyncUseCase(
            cameraClient = client,
            savedMediaRepository = fakeRepository
        )

        val states = mutableListOf<SyncUiState>()
        val summary = useCase.execute(
            cameraHost = "192.168.54.1",
            saveDirectoryPath = "DCIM/CustomGF7",
            downloadUnsaved = true,
            browsePageSize = 2, // Force 3 pages: [0..1], [2..3], [4]
            onStateChanged = { states.add(it) }
        )

        // Verify DLNA Browse paging scanned all 3 pages until the end of the SD card
        assertEquals(listOf(0 to 2, 2 to 2, 4 to 2), browseRequests)

        // Verify only the 3 unsaved images were downloaded
        assertEquals(
            listOf(
                "http://192.168.54.1:50001/DO1030002.JPG",
                "http://192.168.54.1:50001/DO1030004.JPG",
                "http://192.168.54.1:50001/DO1030005.JPG"
            ),
            downloadedUrls
        )

        // Verify summary counts
        assertEquals(5, summary.totalCameraCount)
        assertEquals(5, summary.savedCount)
        assertEquals(0, summary.unsavedCount)
        assertEquals(3, summary.downloadedCount)
        assertEquals(0, summary.failedCount)

        // Verify final UI state
        val finalState = states.last()
        assertEquals(SyncPhase.COMPLETED, finalState.phase)
        assertEquals(5, finalState.totalCameraCount)
        assertEquals(5, finalState.savedCount)
        assertEquals(0, finalState.unsavedCount)
        assertEquals(3, finalState.downloadedCount)
        assertEquals("DCIM/CustomGF7", finalState.saveDirectoryPath)
        assertTrue(savedDirsUsed.all { it == "DCIM/CustomGF7" })
    }

    @Test
    fun syncStateHolder_retainsStateAcrossBackgroundAndUiUpdates() {
        val (dir, host, ssid) = SyncStateHolder.updateSettings("GF7_Album", "http://192.168.54.1/", "GF7-TEST")
        assertEquals("DCIM/GF7_Album", dir)
        assertEquals("192.168.54.1", host)
        assertEquals("GF7-TEST", ssid)
        assertEquals("DCIM/GF7_Album", SyncStateHolder.state.value.saveDirectoryPath)
        assertEquals("192.168.54.1", SyncStateHolder.state.value.cameraHost)
        assertEquals("GF7-TEST", SyncStateHolder.state.value.cameraWifiSsid)

        SyncStateHolder.markDisconnected()
        assertEquals(SyncPhase.IDLE, SyncStateHolder.state.value.phase)
        assertEquals(false, SyncStateHolder.state.value.isConnected)
        assertTrue(SyncStateHolder.state.value.canConnect)
        assertEquals(false, SyncStateHolder.state.value.canSave)
    }

    @Test
    fun connectOnly_waitsForCameraApprovalAndTransitionsToConnected() = runTest {
        var getStatePollCount = 0
        val fakeTransport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse {
                return when {
                    url.contains("mode=accctrl&type=req_acc") ->
                        HttpResponse(200, "ok_under_research,GF7-123456,remote,open")
                    url.contains("mode=getstate") -> {
                        getStatePollCount++
                        if (getStatePollCount < 2) {
                            HttpResponse(200, """<?xml version="1.0" encoding="UTF-8"?><camrply><result>err_busy</result></camrply>""")
                        } else {
                            HttpResponse(200, """<?xml version="1.0" encoding="UTF-8"?><camrply><result>ok</result></camrply>""")
                        }
                    }
                    url.contains("mode=setsetting&type=device_name") ->
                        HttpResponse(200, """<?xml version="1.0" encoding="UTF-8"?><camrply><result>ok</result></camrply>""")
                    url.contains("mode=camcmd&value=playmode") ->
                        HttpResponse(200, """<?xml version="1.0" encoding="UTF-8"?><camrply><result>ok</result></camrply>""")
                    url.contains("mode=get_content_info") ->
                        HttpResponse(
                            200,
                            """<?xml version="1.0" encoding="UTF-8"?><camrply><result>ok</result><current_position>1</current_position><total_content_number>12</total_content_number><content_number>12</content_number></camrply>"""
                        )
                    else -> HttpResponse(404, "")
                }
            }

            override fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse =
                HttpResponse(404, "")

            override fun downloadToStream(url: String, outputStream: OutputStream, timeoutMs: Int): Long = 0L
        }

        val dummyRepo = object : SavedMediaRepository {
            override fun listSavedFileNames(relativeDirPath: String): Set<String> = emptySet()
            override fun saveMediaStream(
                relativeDirPath: String,
                fileName: String,
                format: MediaFormat,
                writeAction: (OutputStream) -> Unit
            ) = Unit
        }

        val client = LumixCameraClient(transport = fakeTransport, retryDelayMs = 1L)
        val useCase = CameraSyncUseCase(cameraClient = client, savedMediaRepository = dummyRepo)

        val states = mutableListOf<SyncUiState>()
        useCase.connectOnly(
            cameraHost = "192.168.54.1",
            saveDirectoryPath = "DCIM/DMC-GF7",
            onStateChanged = { states.add(it) }
        )

        assertEquals(2, getStatePollCount)
        val connectedState = states.last()
        assertEquals(SyncPhase.CONNECTED, connectedState.phase)
        assertTrue(connectedState.isConnected)
        assertEquals(false, connectedState.canConnect)
        assertTrue(connectedState.canSave)
        assertTrue(connectedState.canDisconnect)
        assertEquals(12, connectedState.totalCameraCount)
    }
}
