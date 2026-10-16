package com.p4ko.dmcgf7exporter.storage

import com.p4ko.dmcgf7exporter.model.CameraMediaItem
import com.p4ko.dmcgf7exporter.model.CameraMediaResource
import com.p4ko.dmcgf7exporter.model.MediaFormat
import com.p4ko.dmcgf7exporter.model.MediaSelectionConfig
import com.p4ko.dmcgf7exporter.model.PriorityMediaSelectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSelectionAndMatcherTest {

    private val rawPlusJpegItem = CameraMediaItem(
        id = "1030516",
        title = "103-0516",
        upnpClass = "object.item.imageItem",
        resources = listOf(
            CameraMediaResource(
                url = "http://192.168.54.1:50001/DO1030516.JPG",
                protocolInfo = "http-get:*:application/octet-stream;PANASONIC.COM_PN=CAM_RAW_JPG",
                format = MediaFormat.JPEG_ORIGINAL,
                fileName = "DO1030516.JPG"
            ),
            CameraMediaResource(
                url = "http://192.168.54.1:50001/DT1030516.JPG",
                protocolInfo = "http-get:*:image/jpeg;PANASONIC.COM_PN=CAM_TN",
                format = MediaFormat.JPEG_THUMB,
                fileName = "DT1030516.JPG"
            ),
            CameraMediaResource(
                url = "http://192.168.54.1:50001/DO1030516.RW2",
                protocolInfo = "http-get:*:application/octet-stream;PANASONIC.COM_PN=CAM_RAW",
                format = MediaFormat.RAW,
                fileName = "DO1030516.RW2"
            )
        )
    )

    @Test
    fun defaultPolicy_prioritizesJpegOriginalOnly() {
        val policy = PriorityMediaSelectionPolicy()
        val targets = policy.selectTargets(rawPlusJpegItem)

        assertEquals(1, targets.size)
        assertEquals("DO1030516.JPG", targets[0].primaryFileName)
        assertEquals(MediaFormat.JPEG_ORIGINAL, targets[0].resource.format)
        assertTrue(targets[0].candidateFileNames.contains("DO1030516.JPG"))
        assertTrue(targets[0].candidateFileNames.contains("P1030516.JPG"))
    }

    @Test
    fun extensiblePolicy_canSelectRawAndVideoWhenEnabled() {
        val policy = PriorityMediaSelectionPolicy(
            MediaSelectionConfig(
                includeJpegOriginal = true,
                includeRaw = true,
                includeVideo = true
            )
        )
        val targets = policy.selectTargets(rawPlusJpegItem)

        assertEquals(2, targets.size)
        assertEquals(MediaFormat.JPEG_ORIGINAL, targets[0].resource.format)
        assertEquals(MediaFormat.RAW, targets[1].resource.format)
        assertEquals("DO1030516.RW2", targets[1].primaryFileName)
    }

    @Test
    fun normalizeDcimRelativePath_alwaysProducesCleanDcimPath() {
        assertEquals("DCIM/DMC-GF7", SavedFileMatcher.normalizeDcimRelativePath(""))
        assertEquals("DCIM/DMC-GF7", SavedFileMatcher.normalizeDcimRelativePath("DMC-GF7"))
        assertEquals("DCIM/LumixGF7", SavedFileMatcher.normalizeDcimRelativePath("/DCIM/LumixGF7/"))
        assertEquals("DCIM", SavedFileMatcher.normalizeDcimRelativePath("dcim"))
    }

    @Test
    fun savedFileMatcher_recognizesBothDlnaDoFileNameAndDcfPFileName() {
        val policy = PriorityMediaSelectionPolicy()
        val target = policy.selectTargets(rawPlusJpegItem).single()

        // Match by DO1030516.JPG
        assertTrue(SavedFileMatcher.isTargetSaved(target, setOf("do1030516.jpg")))
        // Match by P1030516.JPG (standard Panasonic DCF filename)
        assertTrue(SavedFileMatcher.isTargetSaved(target, setOf("P1030516.JPG")))
        // Unrelated file does not match
        assertFalse(SavedFileMatcher.isTargetSaved(target, setOf("DO1030517.JPG")))
    }
}
