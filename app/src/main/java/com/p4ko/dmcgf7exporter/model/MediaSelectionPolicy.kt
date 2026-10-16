package com.p4ko.dmcgf7exporter.model

/**
 * Configuration for selecting which media formats to export from the camera.
 *
 * By default, JPEG Original (`CAM_ORG` / `CAM_RAW_JPG`) is prioritized, and the structure
 * supports RAW (`RW2`) and Video (`MP4`) for future or optional expansion.
 */
data class MediaSelectionConfig(
    val includeJpegOriginal: Boolean = true,
    val includeRaw: Boolean = false,
    val includeVideo: Boolean = false,
    val fallbackToLargeThumbIfNoOriginal: Boolean = false
)

/**
 * Strategy interface for choosing exportable resources from a [CameraMediaItem].
 */
fun interface MediaSelectionPolicy {
    fun selectTargets(item: CameraMediaItem): List<ExportTarget>
}

/**
 * Default policy that prioritizes original JPEGs and supports RAW (RW2) and Video (MP4).
 */
class PriorityMediaSelectionPolicy(
    private val config: MediaSelectionConfig = MediaSelectionConfig()
) : MediaSelectionPolicy {

    override fun selectTargets(item: CameraMediaItem): List<ExportTarget> {
        val selectedResources = mutableListOf<CameraMediaResource>()

        if (config.includeJpegOriginal) {
            val jpegOrig = item.resourceOf(MediaFormat.JPEG_ORIGINAL)
                ?: if (config.fallbackToLargeThumbIfNoOriginal) {
                    item.resourceOf(MediaFormat.JPEG_LARGE_THUMB)
                } else {
                    null
                }
            if (jpegOrig != null) {
                selectedResources.add(jpegOrig)
            }
        }

        if (config.includeRaw) {
            item.resourceOf(MediaFormat.RAW)?.let { selectedResources.add(it) }
        }

        if (config.includeVideo) {
            item.resourceOf(MediaFormat.VIDEO)?.let { selectedResources.add(it) }
        }

        return selectedResources.map { resource ->
            ExportTarget(
                itemId = item.id,
                itemTitle = item.title,
                resource = resource,
                candidateFileNames = buildCandidateFileNames(
                    itemId = item.id,
                    itemTitle = item.title,
                    resourceFileName = resource.fileName
                )
            )
        }
    }

    companion object {
        /**
         * Builds the set of upper-case candidate filenames that identify the same camera asset
         * on the Android device.
         *
         * Example:
         * - DLNA URL filename: `DO1030822.JPG`
         * - DLNA `<dc:title>`: `103-0822`
         * - Standard LUMIX DCF filename on SD card / Image App: `P1030822.JPG`
         */
        fun buildCandidateFileNames(
            itemId: String,
            itemTitle: String,
            resourceFileName: String
        ): Set<String> {
            val candidates = linkedSetOf<String>()
            val trimmedResource = resourceFileName.trim()
            if (trimmedResource.isNotEmpty()) {
                candidates.add(trimmedResource.uppercase())
            }

            val ext = trimmedResource.substringAfterLast('.', "")
                .uppercase()
                .ifEmpty { "JPG" }

            // Derive DCF filename (e.g. DO1030822.JPG -> P1030822.JPG)
            val baseName = trimmedResource.substringBeforeLast('.')
            if (baseName.length > 2 &&
                (baseName.startsWith("DO", ignoreCase = true) ||
                    baseName.startsWith("DL", ignoreCase = true) ||
                    baseName.startsWith("DT", ignoreCase = true))
            ) {
                val digits = baseName.substring(2)
                if (digits.all { it.isDigit() }) {
                    candidates.add("P$digits.$ext")
                }
            }

            // Also derive from dc:title (e.g. "103-0822" -> "P1030822.JPG", or "P1030822" -> "P1030822.JPG")
            val normalizedTitle = itemTitle.trim().uppercase()
            if (normalizedTitle.isNotEmpty()) {
                val digitsOnly = normalizedTitle.replace("-", "")
                if (digitsOnly.all { it.isDigit() } && digitsOnly.isNotEmpty()) {
                    candidates.add("P$digitsOnly.$ext")
                    candidates.add("DO$digitsOnly.$ext")
                } else if (!normalizedTitle.contains('.')) {
                    candidates.add("$normalizedTitle.$ext")
                } else {
                    candidates.add(normalizedTitle)
                }
            }

            // Also derive from itemId if purely numeric (e.g. "1030822" -> "P1030822.JPG")
            val trimmedId = itemId.trim()
            if (trimmedId.isNotEmpty() && trimmedId.all { it.isDigit() }) {
                candidates.add("P$trimmedId.$ext")
                candidates.add("DO$trimmedId.$ext")
            }

            return candidates
        }
    }
}
