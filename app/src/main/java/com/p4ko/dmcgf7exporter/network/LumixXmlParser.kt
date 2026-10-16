package com.p4ko.dmcgf7exporter.network

import com.p4ko.dmcgf7exporter.model.CameraMediaItem
import com.p4ko.dmcgf7exporter.model.CameraMediaResource
import com.p4ko.dmcgf7exporter.model.MediaFormat
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

enum class CamReplyStatus {
    OK,
    OK_UNDER_RESEARCH,
    BUSY,
    ERROR
}

data class CamReplyResult(
    val status: CamReplyStatus,
    val rawResultText: String
) {
    val isOk: Boolean
        get() = status == CamReplyStatus.OK || status == CamReplyStatus.OK_UNDER_RESEARCH
}

data class LumixContentInfo(
    val currentPosition: Int,
    val totalContentNumber: Int,
    val contentNumber: Int
)

data class DlnaBrowsePage(
    val items: List<CameraMediaItem>,
    val numberReturned: Int,
    val totalMatches: Int,
    val updateId: Int = 0
)

/**
 * XML/SOAP builder and parser for Panasonic LUMIX `cam.cgi` and DLNA `ContentDirectory:1#Browse`.
 */
object LumixXmlParser {

    /**
     * Parses the reply from `http://<ip>/cam.cgi?...`.
     *
     * Handles both XML replies:
     * `<?xml version="1.0" encoding="UTF-8"?><camrply><result>ok</result>...</camrply>`
     * and comma-separated plain text replies returned by `req_acc`:
     * `ok,GF7-123456,remote,open` or `ok_under_research,GF7-123456,remote,open`.
     */
    fun parseCamReply(responseText: String): CamReplyResult {
        val trimmed = responseText.trim()
        if (trimmed.isEmpty()) {
            return CamReplyResult(CamReplyStatus.ERROR, "")
        }

        // Plain-text format (e.g. "ok,GF7-xxx,remote,encrypted" or "ok_under_research,...")
        if (!trimmed.startsWith("<")) {
            val firstToken = trimmed.substringBefore(',').trim().lowercase()
            val status = when {
                firstToken == "ok" -> CamReplyStatus.OK
                firstToken.startsWith("ok_under_research") || firstToken.startsWith("ok") ->
                    CamReplyStatus.OK_UNDER_RESEARCH
                firstToken.contains("busy") -> CamReplyStatus.BUSY
                else -> CamReplyStatus.ERROR
            }
            return CamReplyResult(status, trimmed)
        }

        return try {
            val doc = parseXmlDocument(trimmed)
            val resultText = findFirstElementText(doc.documentElement, "result")
                ?.trim()
                ?.lowercase()
                ?: ""
            val status = when {
                resultText == "ok" -> CamReplyStatus.OK
                resultText.startsWith("ok_under_research") || resultText.startsWith("ok") ->
                    CamReplyStatus.OK_UNDER_RESEARCH
                resultText == "err_busy" || resultText.contains("busy") -> CamReplyStatus.BUSY
                else -> CamReplyStatus.ERROR
            }
            CamReplyResult(status, resultText.ifEmpty { trimmed })
        } catch (_: Exception) {
            // Fallback substring check if XML is slightly malformed
            val lower = trimmed.lowercase()
            val status = when {
                lower.contains("<result>ok</result>") -> CamReplyStatus.OK
                lower.contains("<result>ok_under_research") -> CamReplyStatus.OK_UNDER_RESEARCH
                lower.contains("<result>err_busy</result>") -> CamReplyStatus.BUSY
                else -> CamReplyStatus.ERROR
            }
            CamReplyResult(status, trimmed)
        }
    }

    /**
     * Parses `cam.cgi?mode=get_content_info` XML response.
     *
     * Sample from libgphoto2 `lumix.c`:
     * `<?xml version="1.0" encoding="UTF-8"?>`
     * `<camrply><result>ok</result><current_position>2</current_position><total_content_number>651</total_content_number><content_number>651</content_number></camrply>`
     */
    fun parseContentInfo(xmlText: String): LumixContentInfo? {
        val reply = parseCamReply(xmlText)
        if (!reply.isOk) return null

        return try {
            val doc = parseXmlDocument(xmlText)
            val root = doc.documentElement ?: return null
            val currentPos = findFirstElementText(root, "current_position")?.trim()?.toIntOrNull() ?: 0
            val totalContent = findFirstElementText(root, "total_content_number")?.trim()?.toIntOrNull()
            val contentNum = findFirstElementText(root, "content_number")?.trim()?.toIntOrNull()

            val resolvedContentNum = contentNum ?: totalContent ?: return null
            val resolvedTotal = totalContent ?: resolvedContentNum
            LumixContentInfo(
                currentPosition = currentPos,
                totalContentNumber = resolvedTotal,
                contentNumber = resolvedContentNum
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Builds the SOAP request envelope for DLNA `ContentDirectory:1#Browse`.
     * Includes `<pana:X_FromCP>LumixLink2.0</pana:X_FromCP>` as used by Panasonic Image App / libgphoto2.
     */
    fun buildBrowseSoapEnvelope(
        objectId: String = "0",
        startingIndex: Int,
        requestedCount: Int
    ): String {
        return buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
            append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""")
            append("""<s:Body>""")
            append("""<u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1" """)
            append("""xmlns:pana="urn:schemas-panasonic-com:pana">""")
            append("""<ObjectID>$objectId</ObjectID>""")
            append("""<BrowseFlag>BrowseDirectChildren</BrowseFlag>""")
            append("""<Filter>*</Filter>""")
            append("""<StartingIndex>$startingIndex</StartingIndex>""")
            append("""<RequestedCount>$requestedCount</RequestedCount>""")
            append("""<SortCriteria></SortCriteria>""")
            append("""<pana:X_FromCP>LumixLink2.0</pana:X_FromCP>""")
            append("""</u:Browse>""")
            append("""</s:Body>""")
            append("""</s:Envelope>""")
        }
    }

    /**
     * Parses the DLNA SOAP `BrowseResponse` XML and its embedded `DIDL-Lite` catalog.
     */
    fun parseBrowseSoapResponse(soapXml: String): DlnaBrowsePage {
        val doc = parseXmlDocument(soapXml)
        val root = doc.documentElement
        val resultDidl = findFirstElementText(root, "Result")?.trim().orEmpty()
        val numberReturned = findFirstElementText(root, "NumberReturned")?.trim()?.toIntOrNull() ?: 0
        val totalMatches = findFirstElementText(root, "TotalMatches")?.trim()?.toIntOrNull() ?: 0
        val updateId = findFirstElementText(root, "UpdateID")?.trim()?.toIntOrNull() ?: 0

        val items = if (resultDidl.isNotEmpty()) {
            parseDidlLite(resultDidl)
        } else {
            emptyList()
        }

        return DlnaBrowsePage(
            items = items,
            numberReturned = if (numberReturned > 0) numberReturned else items.size,
            totalMatches = totalMatches,
            updateId = updateId
        )
    }

    /**
     * Parses a `<DIDL-Lite>` XML document into [CameraMediaItem] instances.
     */
    fun parseDidlLite(didlXml: String): List<CameraMediaItem> {
        val trimmed = didlXml.trim()
        if (trimmed.isEmpty()) return emptyList()

        val doc = parseXmlDocument(trimmed)
        val root = doc.documentElement ?: return emptyList()
        val items = mutableListOf<CameraMediaItem>()

        val childNodes = root.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && localNameOf(node) == "item") {
                val itemElement = node as Element
                parseItemElement(itemElement)?.let { items.add(it) }
            }
        }
        return items
    }

    /**
     * Parses a UPnP Device Description XML (`/Server0/ddd`) to find the `ContentDirectory` controlURL.
     */
    fun parseDeviceDescriptionControlUrl(dddXml: String, baseUrl: String): String? {
        return try {
            val doc = parseXmlDocument(dddXml)
            val serviceNodes = doc.getElementsByTagName("*")
            for (i in 0 until serviceNodes.length) {
                val node = serviceNodes.item(i)
                if (node.nodeType == Node.ELEMENT_NODE && localNameOf(node) == "service") {
                    val serviceElem = node as Element
                    val serviceType = findFirstElementText(serviceElem, "serviceType").orEmpty()
                    if (serviceType.contains("ContentDirectory", ignoreCase = true)) {
                        val controlUrl = findFirstElementText(serviceElem, "controlURL")?.trim().orEmpty()
                        if (controlUrl.isNotEmpty()) {
                            return if (controlUrl.startsWith("http://") || controlUrl.startsWith("https://")) {
                                controlUrl
                            } else {
                                val normalizedBase = baseUrl.trimEnd('/')
                                val normalizedPath = controlUrl.trimStart('/')
                                "$normalizedBase/$normalizedPath"
                            }
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseItemElement(itemElement: Element): CameraMediaItem? {
        val id = itemElement.getAttribute("id").trim()
        var title = ""
        var upnpClass = ""
        var writeStatus: String? = null
        val resources = mutableListOf<CameraMediaResource>()

        val children = itemElement.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            val elem = child as Element
            when (localNameOf(elem)) {
                "title" -> title = elem.textContent?.trim().orEmpty()
                "class" -> upnpClass = elem.textContent?.trim().orEmpty()
                "writeStatus" -> writeStatus = elem.textContent?.trim()
                "res" -> {
                    val url = elem.textContent?.trim().orEmpty()
                    if (url.isNotEmpty()) {
                        val protocolInfo = elem.getAttribute("protocolInfo").trim()
                        val sizeBytes = elem.getAttribute("size").trim().toLongOrNull()
                        val format = MediaFormat.fromProtocolInfoAndUrl(protocolInfo, url)
                        val fileName = extractFileNameFromUrl(url, title, id, format)
                        resources.add(
                            CameraMediaResource(
                                url = url,
                                protocolInfo = protocolInfo,
                                format = format,
                                fileName = fileName,
                                sizeBytes = sizeBytes
                            )
                        )
                    }
                }
            }
        }

        if (id.isEmpty() && resources.isEmpty()) return null
        val resolvedId = id.ifEmpty { title.ifEmpty { resources.first().fileName } }
        return CameraMediaItem(
            id = resolvedId,
            title = title.ifEmpty { resolvedId },
            upnpClass = upnpClass,
            writeStatus = writeStatus,
            resources = resources
        )
    }

    internal fun extractFileNameFromUrl(
        url: String,
        title: String,
        id: String,
        format: MediaFormat
    ): String {
        val cleanPath = url.substringBefore('?').trimEnd('/')
        val segment = cleanPath.substringAfterLast('/')
        if (segment.isNotEmpty() && segment.contains('.')) {
            return segment
        }
        val base = title.replace("-", "").ifEmpty { id.ifEmpty { "IMAGE" } }
        return "$base.${format.defaultExtension}"
    }

    private fun parseXmlDocument(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val builder = factory.newDocumentBuilder()
        return builder.parse(InputSource(StringReader(xml.trim())))
    }

    private fun findFirstElementText(parent: Element, localName: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val elem = node as Element
                if (localNameOf(elem).equals(localName, ignoreCase = true)) {
                    return elem.textContent
                }
                val nested = findFirstElementText(elem, localName)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun localNameOf(node: Node): String {
        val local = node.localName
        if (!local.isNullOrEmpty()) return local
        val name = node.nodeName ?: return ""
        return name.substringAfter(':')
    }
}
