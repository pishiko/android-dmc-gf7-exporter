package com.p4ko.dmcgf7exporter.model

/**
 * Media formats exposed by Panasonic LUMIX DMC-GF7 over DLNA ContentDirectory.
 * Designed so JPEG Original is prioritized by default while supporting RAW (RW2) and Video (MP4).
 */
enum class MediaFormat(
    val defaultMimeType: String,
    val defaultExtension: String,
    val isOriginalAsset: Boolean
) {
    JPEG_ORIGINAL(
        defaultMimeType = "image/jpeg",
        defaultExtension = "JPG",
        isOriginalAsset = true
    ),
    RAW(
        defaultMimeType = "image/x-panasonic-rw2",
        defaultExtension = "RW2",
        isOriginalAsset = true
    ),
    VIDEO(
        defaultMimeType = "video/mp4",
        defaultExtension = "MP4",
        isOriginalAsset = true
    ),
    JPEG_LARGE_THUMB(
        defaultMimeType = "image/jpeg",
        defaultExtension = "JPG",
        isOriginalAsset = false
    ),
    JPEG_THUMB(
        defaultMimeType = "image/jpeg",
        defaultExtension = "JPG",
        isOriginalAsset = false
    ),
    UNKNOWN(
        defaultMimeType = "application/octet-stream",
        defaultExtension = "BIN",
        isOriginalAsset = false
    );

    companion object {
        /**
         * Resolves [MediaFormat] from the DLNA `<res protocolInfo="...">` attribute and URL.
         *
         * Reference from libgphoto2 `camlibs/lumix/lumix.c`:
         * - JPEG only: `PANASONIC.COM_PN=CAM_ORG`
         * - RAW+JPEG JPEG part: `PANASONIC.COM_PN=CAM_RAW_JPG`
         * - RAW part (.RW2/.RAW): `PANASONIC.COM_PN=CAM_RAW` (and not `CAM_RAW_JPG`)
         * - Movie (.MP4): `PANASONIC.COM_PN=CAM_AVC_MP4...`
         * - Medium preview (`DL...JPG`): `PANASONIC.COM_PN=CAM_LRGTN`
         * - Thumbnail (`DT...JPG`): `PANASONIC.COM_PN=CAM_TN`
         */
        fun fromProtocolInfoAndUrl(protocolInfo: String, url: String): MediaFormat {
            val upperInfo = protocolInfo.uppercase()
            val fileName = url.substringAfterLast('/').uppercase()

            return when {
                upperInfo.contains("PANASONIC.COM_PN=CAM_ORG") ||
                    upperInfo.contains("PANASONIC.COM_PN=CAM_RAW_JPG") -> JPEG_ORIGINAL

                upperInfo.contains("PANASONIC.COM_PN=CAM_RAW") &&
                    !upperInfo.contains("PANASONIC.COM_PN=CAM_RAW_JPG") -> RAW

                upperInfo.contains("PANASONIC.COM_PN=CAM_AVC_MP4") ||
                    upperInfo.contains("VIDEO/") ||
                    fileName.endsWith(".MP4") ||
                    fileName.endsWith(".MOV") -> VIDEO

                upperInfo.contains("PANASONIC.COM_PN=CAM_LRGTN") -> JPEG_LARGE_THUMB

                upperInfo.contains("PANASONIC.COM_PN=CAM_TN") -> JPEG_THUMB

                // Fallback heuristics by Lumix DLNA filename prefix (DO = original, DL = large thumb, DT = thumb)
                fileName.startsWith("DO") && (fileName.endsWith(".JPG") || fileName.endsWith(".JPEG")) ->
                    JPEG_ORIGINAL
                fileName.startsWith("DO") && (fileName.endsWith(".RW2") || fileName.endsWith(".RAW")) ->
                    RAW
                fileName.startsWith("DL") && fileName.endsWith(".JPG") ->
                    JPEG_LARGE_THUMB
                fileName.startsWith("DT") && fileName.endsWith(".JPG") ->
                    JPEG_THUMB

                else -> UNKNOWN
            }
        }
    }
}
