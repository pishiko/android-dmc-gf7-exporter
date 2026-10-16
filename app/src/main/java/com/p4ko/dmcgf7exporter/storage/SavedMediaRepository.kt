package com.p4ko.dmcgf7exporter.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.p4ko.dmcgf7exporter.model.ExportTarget
import com.p4ko.dmcgf7exporter.model.MediaFormat
import com.p4ko.dmcgf7exporter.model.SyncUiState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

data class TargetComparisonResult(
    val allTargets: List<ExportTarget>,
    val savedTargets: List<ExportTarget>,
    val unsavedTargets: List<ExportTarget>
)

/**
 * Pure filename comparison logic used to compare camera images with saved images on Android.
 */
object SavedFileMatcher {

    /**
     * Normalizes a user-configured save directory path so it resides under Android's `DCIM` directory.
     *
     * Examples:
     * - `""` -> `"DCIM/DMC-GF7"`
     * - `"DMC-GF7"` -> `"DCIM/DMC-GF7"`
     * - `"DCIM/Lumix"` -> `"DCIM/Lumix"`
     * - `"DCIM"` -> `"DCIM"`
     */
    fun normalizeDcimRelativePath(rawPath: String): String {
        val cleaned = rawPath.trim()
            .replace('\\', '/')
            .trim('/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

        if (cleaned.isEmpty()) {
            return SyncUiState.DEFAULT_SAVE_DIRECTORY
        }
        return if (cleaned.equals("DCIM", ignoreCase = true)) {
            "DCIM"
        } else if (cleaned.startsWith("DCIM/", ignoreCase = true)) {
            "DCIM/" + cleaned.substring(5)
        } else {
            "DCIM/$cleaned"
        }
    }

    /**
     * Checks whether [target] is already saved on the device based on [savedFileNames].
     * Comparisons are case-insensitive and check both DLNA original filenames (`DO1030822.JPG`)
     * and standard LUMIX DCF filenames (`P1030822.JPG`).
     */
    fun isTargetSaved(target: ExportTarget, savedFileNames: Set<String>): Boolean {
        if (savedFileNames.isEmpty()) return false
        val upperSaved = savedFileNames.mapTo(HashSet(savedFileNames.size)) { it.trim().uppercase() }
        return target.candidateFileNames.any { candidate ->
            upperSaved.contains(candidate.uppercase())
        }
    }

    /**
     * Partitions [targets] into already-saved and unsaved lists by comparing filenames.
     */
    fun partitionTargets(
        targets: List<ExportTarget>,
        savedFileNames: Set<String>
    ): TargetComparisonResult {
        val upperSaved = savedFileNames.mapTo(HashSet(savedFileNames.size)) { it.trim().uppercase() }
        val saved = mutableListOf<ExportTarget>()
        val unsaved = mutableListOf<ExportTarget>()

        for (target in targets) {
            val exists = target.candidateFileNames.any { candidate ->
                upperSaved.contains(candidate.uppercase())
            }
            if (exists) {
                saved.add(target)
            } else {
                unsaved.add(target)
            }
        }

        return TargetComparisonResult(
            allTargets = targets,
            savedTargets = saved,
            unsavedTargets = unsaved
        )
    }
}

/**
 * Abstraction for listing existing filenames and saving downloaded media into Android's DCIM directory.
 */
interface SavedMediaRepository {
    fun listSavedFileNames(relativeDirPath: String): Set<String>

    @Throws(IOException::class)
    fun saveMediaStream(
        relativeDirPath: String,
        fileName: String,
        format: MediaFormat,
        writeAction: (OutputStream) -> Unit
    )
}

/**
 * Android implementation of [SavedMediaRepository] that stores files in Android's `DCIM` directory
 * via `MediaStore` (API 29+) or `Environment.getExternalStorageDirectory()` (API 26-28).
 */
class DcimSavedMediaRepository(
    context: Context
) : SavedMediaRepository {

    private val appContext = context.applicationContext

    override fun listSavedFileNames(relativeDirPath: String): Set<String> {
        val normalizedPath = SavedFileMatcher.normalizeDcimRelativePath(relativeDirPath)
        val result = linkedSetOf<String>()

        // 1. Check direct filesystem directory under external storage if readable
        runCatching {
            val externalRoot = Environment.getExternalStorageDirectory()
            val dir = File(externalRoot, normalizedPath)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.length() > 0 && !file.name.startsWith(".pending")) {
                        result.add(file.name.uppercase())
                    }
                }
            }
        }

        // 2. Query MediaStore for images/videos/files in the target DCIM relative path
        runCatching {
            queryMediaStoreFileNames(normalizedPath, result)
        }

        return result
    }

    override fun saveMediaStream(
        relativeDirPath: String,
        fileName: String,
        format: MediaFormat,
        writeAction: (OutputStream) -> Unit
    ) {
        val normalizedPath = SavedFileMatcher.normalizeDcimRelativePath(relativeDirPath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStoreQ(normalizedPath, fileName, format, writeAction)
        } else {
            saveViaLegacyDcimFile(normalizedPath, fileName, format, writeAction)
        }
    }

    private fun queryMediaStoreFileNames(normalizedPath: String, outNames: MutableSet<String>) {
        val resolver = appContext.contentResolver
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )
        val pathWithSlash = "$normalizedPath/"

        for (uri in collections) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.IS_PENDING
                )
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf(pathWithSlash, normalizedPath)
                resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val pendingIdx = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                    while (cursor.moveToNext()) {
                        val isPending = if (pendingIdx >= 0) cursor.getInt(pendingIdx) != 0 else false
                        if (!isPending) {
                            val name = cursor.getString(nameIdx)
                            if (!name.isNullOrBlank()) {
                                outNames.add(name.trim().uppercase())
                            }
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val projection = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATA
                )
                @Suppress("DEPRECATION")
                val selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
                val selectionArgs = arrayOf("%/$pathWithSlash%")
                resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIdx)
                        if (!name.isNullOrBlank()) {
                            outNames.add(name.trim().uppercase())
                        }
                    }
                }
            }
        }
    }

    private fun saveViaMediaStoreQ(
        normalizedPath: String,
        fileName: String,
        format: MediaFormat,
        writeAction: (OutputStream) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = appContext.contentResolver
        val collectionUri = when (format) {
            MediaFormat.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaFormat.RAW, MediaFormat.UNKNOWN -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, format.defaultMimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$normalizedPath/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(collectionUri, values)
            ?: throw IOException("MediaStore へのエントリ作成に失敗しました: $normalizedPath/$fileName")

        try {
            val outStream = resolver.openOutputStream(itemUri)
                ?: throw IOException("保存ストリームを開けませんでした: $itemUri")
            outStream.use { stream ->
                writeAction(stream)
            }
            val completeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(itemUri, completeValues, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw if (e is IOException) e else IOException("ファイルの書き込みに失敗しました: $fileName", e)
        }
    }

    private fun saveViaLegacyDcimFile(
        normalizedPath: String,
        fileName: String,
        format: MediaFormat,
        writeAction: (OutputStream) -> Unit
    ) {
        val externalRoot = Environment.getExternalStorageDirectory()
        val targetDir = File(externalRoot, normalizedPath)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("保存先ディレクトリを作成できませんでした: ${targetDir.absolutePath}")
        }

        val tempFile = File(targetDir, ".tmp_$fileName")
        val finalFile = File(targetDir, fileName)
        try {
            FileOutputStream(tempFile).use { out ->
                writeAction(out)
            }
            if (finalFile.exists()) {
                finalFile.delete()
            }
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(finalFile.absolutePath),
                arrayOf(format.defaultMimeType),
                null
            )
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            throw if (e is IOException) e else IOException("ファイルの保存に失敗しました: $fileName", e)
        }
    }
}
