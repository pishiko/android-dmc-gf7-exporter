package com.p4ko.dmcgf7exporter.network

import com.p4ko.dmcgf7exporter.model.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LumixXmlParserTest {

    @Test
    fun parseCamReply_handlesXmlAndPlainTextResponses() {
        val okXml = """<?xml version="1.0" encoding="UTF-8"?><camrply><result>ok</result></camrply>"""
        val busyXml = """<?xml version="1.0" encoding="UTF-8"?><camrply><result>err_busy</result></camrply>"""
        val plainOk = "ok,GF7-123456,remote,encrypted"
        val underResearch = "ok_under_research,GF7-123456,remote,open"

        assertEquals(CamReplyStatus.OK, LumixXmlParser.parseCamReply(okXml).status)
        assertEquals(CamReplyStatus.BUSY, LumixXmlParser.parseCamReply(busyXml).status)
        assertEquals(CamReplyStatus.OK, LumixXmlParser.parseCamReply(plainOk).status)
        assertEquals(CamReplyStatus.OK_UNDER_RESEARCH, LumixXmlParser.parseCamReply(underResearch).status)
        assertTrue(LumixXmlParser.parseCamReply(underResearch).isOk)
    }

    @Test
    fun parseContentInfo_extractsTotalAndContentNumbers() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <camrply>
                <result>ok</result>
                <current_position>341</current_position>
                <total_content_number>372</total_content_number>
                <content_number>342</content_number>
            </camrply>
        """.trimIndent()

        val info = LumixXmlParser.parseContentInfo(xml)
        assertNotNull(info)
        assertEquals(341, info!!.currentPosition)
        assertEquals(372, info.totalContentNumber)
        assertEquals(342, info.contentNumber)
    }

    @Test
    fun buildBrowseSoapEnvelope_containsExpectedDlnaAndPanasonicTags() {
        val soap = LumixXmlParser.buildBrowseSoapEnvelope(
            objectId = "0",
            startingIndex = 15,
            requestedCount = 30
        )
        assertTrue(soap.contains("<ObjectID>0</ObjectID>"))
        assertTrue(soap.contains("<BrowseFlag>BrowseDirectChildren</BrowseFlag>"))
        assertTrue(soap.contains("<StartingIndex>15</StartingIndex>"))
        assertTrue(soap.contains("<RequestedCount>30</RequestedCount>"))
        assertTrue(soap.contains("<pana:X_FromCP>LumixLink2.0</pana:X_FromCP>"))
    }

    @Test
    fun parseBrowseSoapResponse_parsesJpegRawAndMovieResources() {
        val didl = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                       xmlns:dc="http://purl.org/dc/elements/1.1/"
                       xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                <item id="1030822" parentID="0" restricted="0">
                    <dc:title>103-0822</dc:title>
                    <upnp:writeStatus>WRITABLE</upnp:writeStatus>
                    <upnp:class name="imageItem">object.item.imageItem</upnp:class>
                    <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=00900000000000000000000000000000;PANASONIC.COM_PN=CAM_ORG">http://192.168.54.1:50001/DO1030822.JPG</res>
                    <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN;DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=00900000000000000000000000000000;PANASONIC.COM_PN=CAM_TN">http://192.168.54.1:50001/DT1030822.JPG</res>
                    <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_MED;DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=00900000000000000000000000000000;PANASONIC.COM_PN=CAM_LRGTN">http://192.168.54.1:50001/DL1030822.JPG</res>
                </item>
                <item id="1030516" parentID="0" restricted="0">
                    <dc:title>103-0516</dc:title>
                    <upnp:writeStatus>WRITABLE</upnp:writeStatus>
                    <upnp:class name="imageItem">object.item.imageItem</upnp:class>
                    <res protocolInfo="http-get:*:application/octet-stream;PANASONIC.COM_PN=CAM_RAW_JPG">http://192.168.54.1:50001/DO1030516.JPG</res>
                    <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN;PANASONIC.COM_PN=CAM_TN">http://192.168.54.1:50001/DT1030516.JPG</res>
                    <res protocolInfo="http-get:*:application/octet-stream;PANASONIC.COM_PN=CAM_RAW">http://192.168.54.1:50001/DO1030516.RW2</res>
                </item>
                <item id="1030900" parentID="0" restricted="0">
                    <dc:title>103-0900</dc:title>
                    <upnp:writeStatus>WRITABLE</upnp:writeStatus>
                    <upnp:class name="videoItem">object.item.videoItem</upnp:class>
                    <res protocolInfo="http-get:*:application/octet-stream:DLNA.ORG_OP=01;PANASONIC.COM_PN=CAM_AVC_MP4_HP_1080_60P_AAC">http://192.168.54.1:50001/DO1030900.MP4</res>
                    <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN;PANASONIC.COM_PN=CAM_TN">http://192.168.54.1:50001/DT1030900.JPG</res>
                </item>
            </DIDL-Lite>
        """.trimIndent()

        val escapedDidl = didl
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        val soapResponse = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                        s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                        <Result>$escapedDidl</Result>
                        <NumberReturned>3</NumberReturned>
                        <TotalMatches>42</TotalMatches>
                        <UpdateID>1</UpdateID>
                    </u:BrowseResponse>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        val page = LumixXmlParser.parseBrowseSoapResponse(soapResponse)
        assertEquals(3, page.numberReturned)
        assertEquals(42, page.totalMatches)
        assertEquals(3, page.items.size)

        val item1 = page.items[0]
        assertEquals("1030822", item1.id)
        assertEquals("103-0822", item1.title)
        assertEquals("DO1030822.JPG", item1.resourceOf(MediaFormat.JPEG_ORIGINAL)?.fileName)
        assertEquals("DL1030822.JPG", item1.resourceOf(MediaFormat.JPEG_LARGE_THUMB)?.fileName)
        assertEquals("DT1030822.JPG", item1.resourceOf(MediaFormat.JPEG_THUMB)?.fileName)

        val item2 = page.items[1]
        assertEquals("1030516", item2.id)
        assertEquals("DO1030516.JPG", item2.resourceOf(MediaFormat.JPEG_ORIGINAL)?.fileName)
        assertEquals("DO1030516.RW2", item2.resourceOf(MediaFormat.RAW)?.fileName)

        val item3 = page.items[2]
        assertEquals("1030900", item3.id)
        assertEquals("DO1030900.MP4", item3.resourceOf(MediaFormat.VIDEO)?.fileName)
    }

    @Test
    fun parseDeviceDescriptionControlUrl_extractsContentDirectoryControlPath() {
        val dddXml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
                <device>
                    <serviceList>
                        <service>
                            <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                            <controlURL>/D8AFF1C0AF18/Server0/CDS_control</controlURL>
                        </service>
                    </serviceList>
                </device>
            </root>
        """.trimIndent()

        val url = LumixXmlParser.parseDeviceDescriptionControlUrl(dddXml, "http://192.168.54.1:60606")
        assertEquals("http://192.168.54.1:60606/D8AFF1C0AF18/Server0/CDS_control", url)
    }
}
