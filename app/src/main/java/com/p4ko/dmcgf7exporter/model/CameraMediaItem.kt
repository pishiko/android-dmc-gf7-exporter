package com.p4ko.dmcgf7exporter.model

/**
 * A single downloadable `<res>` resource belonging to a [CameraMediaItem].
 */
data class CameraMediaResource(
    val url: String,
    val protocolInfo: String,
    val format: MediaFormat,
    val fileName: String,
    val sizeBytes: Long? = null
)

/**
 * Represents one media entry (`<item>`) returned by the camera's DLNA ContentDirectory service.
 *
 * Example item from DMC-GF7:
 * - id: `"1030822"`
 * - title: `"103-0822"`
 * - resources: `DO1030822.JPG` (`CAM_ORG` or `CAM_RAW_JPG`), `DO1030822.RW2` (`CAM_RAW`), etc.
 */
data class CameraMediaItem(
    val id: String,
    val title: String,
    val upnpClass: String,
    val writeStatus: String? = null,
    val resources: List<CameraMediaResource>
) {
    fun resourceOf(format: MediaFormat): CameraMediaResource? =
        resources.firstOrNull { it.format == format }
}

/**
 * Represents a resolved media target selected from a [CameraMediaItem] for synchronization.
 */
data class ExportTarget(
    val itemId: String,
    val itemTitle: String,
    val resource: CameraMediaResource,
    val candidateFileNames: Set<String>
) {
    val primaryFileName: String
        get() = resource.fileName
}
