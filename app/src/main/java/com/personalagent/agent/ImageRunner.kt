package com.personalagent.agent

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Processes IMAGE-type tasks: resize a photo to ≤1080px, save to organized folder,
 * and optionally delete the original.
 *
 * ## Pipeline
 * 1. Parse [PersonalTask.payloadJson] → extract imageId, path, mimeType, deleteOriginal.
 * 2. Load bitmap via ContentResolver URI (preferred) or file path (fallback).
 * 3. Resize to ≤1080px (aspect ratio preserved, [Bitmap.createScaledBitmap]).
 * 4. Save output to `Pictures/PPA/YYYY-MM/IMG_&lt;epoch&gt;.jpg` (JPEG Q80).
 * 5. Optionally delete original via ContentResolver or file system.
 *
 * ## Output structure
 * ```
 * Pictures/PPA/
 *   2026-05/
 *     IMG_1715500000123.jpg   (≤1080px, JPEG Q80)
 *     IMG_1715500000456.jpg
 * ```
 *
 * ## Usage
 * ```
 * val runner = ImageRunner(context, task)
 * val ok = runner.execute()
 * if (ok) {
 *     dao.completeTask(task.id, "DONE", now)
 *     runner.buildUpdatedPayload()?.let { dao.updatePayload(task.id, it) }
 * } else {
 *     dao.updateStatus(task.id, "FAILED")
 * }
 * ```
 *
 * @property context Android context for ContentResolver and storage access.
 * @property task The IMAGE-type task to process.
 *
 * @see TaskExecutor
 * @see PersonalTaskDao
 */
class ImageRunner(
    private val context: Context,
    private val task: PersonalTask,
) {
    /** Set during [execute] — the absolute path of the resized output file. */
    var outputPath: String? = null
        private set

    /** Set during [execute] — file size in bytes of the resized output. */
    var outputSizeBytes: Long = 0
        private set

    // ---- Public API ----

    /**
     * Executes the image processing pipeline.
     *
     * @return true on success, false if any step failed.
     */
    suspend fun execute(): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.i("ImageRunner: starting task id=%s", task.id)

            // 1. Parse payload.
            val payload = JSONObject(task.payloadJson)
            val imageId = payload.optLong("imageId", -1)
            val filePath = payload.optString("path", "")
            val mimeType = payload.optString("mimeType", "image/jpeg")
            val deleteOriginal = payload.optBoolean("deleteOriginal", false)

            if (imageId < 0 && filePath.isEmpty()) {
                Timber.e("ImageRunner: payload missing both imageId and path")
                return@withContext false
            }

            Timber.d("ImageRunner: imageId=%d path=%s mime=%s delete=%b", imageId, filePath, mimeType, deleteOriginal)

            // 2. Load image — try ContentResolver URI first, then file path fallback.
            val bitmap = loadImage(imageId, filePath)
            if (bitmap == null) {
                Timber.e("ImageRunner: failed to load image imageId=%d path=%s", imageId, filePath)
                return@withContext false
            }
            Timber.d("ImageRunner: loaded bitmap %dx%d", bitmap.width, bitmap.height)

            // 3. Resize to max 1080px, maintaining aspect ratio.
            val resized = scaleBitmap(bitmap, MAX_DIMENSION)
            if (resized !== bitmap) {
                bitmap.recycle()
            }
            Timber.d("ImageRunner: resized to %dx%d", resized.width, resized.height)

            // 4. Save to organized output folder.
            val outputFile = createOutputFile(mimeType)
            val ok = saveBitmap(resized, outputFile)
            resized.recycle()

            if (!ok) {
                Timber.e("ImageRunner: failed to save output file")
                return@withContext false
            }

            outputPath = outputFile.absolutePath
            outputSizeBytes = outputFile.length()
            Timber.i("ImageRunner: saved to %s (%d bytes)", outputPath, outputSizeBytes)

            // 5. Optionally delete original.
            if (deleteOriginal) {
                deleteOriginal(imageId, filePath)
            }

            Timber.i("ImageRunner: task completed id=%s", task.id)
            true
        } catch (e: Exception) {
            Timber.e(e, "ImageRunner: task failed id=%s", task.id)
            false
        }
    }

    /**
     * Builds an updated payload JSON string containing the new `outputPath` and `outputSizeBytes`
     * merged into the original payload. Call after a successful [execute].
     */
    fun buildUpdatedPayload(): String {
        val payload = JSONObject(task.payloadJson)
        outputPath?.let { payload.put("outputPath", it) }
        payload.put("outputSizeBytes", outputSizeBytes)
        val dims = if (outputSizeBytes > 0) {
            val kb = outputSizeBytes / 1024
            "resized to ≤${MAX_DIMENSION}px, ${kb}KB"
        } else {
            "resized to ≤${MAX_DIMENSION}px"
        }
        payload.put("summary", dims)
        return payload.toString()
    }

    // ---- Private helpers ----

    private fun loadImage(imageId: Long, filePath: String): Bitmap? {
        // Try ContentResolver URI first.
        if (imageId > 0) {
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    return BitmapFactory.decodeStream(stream)
                }
            } catch (e: SecurityException) {
                Timber.w(e, "ImageRunner: ContentResolver denied, falling back to file path")
            } catch (e: Exception) {
                Timber.w(e, "ImageRunner: ContentResolver failed, falling back to file path")
            }
        }

        // File path fallback.
        if (filePath.isNotEmpty()) {
            return try {
                BitmapFactory.decodeFile(filePath)
            } catch (e: Exception) {
                Timber.e(e, "ImageRunner: decodeFile failed path=%s", filePath)
                null
            }
        }

        return null
    }

    private fun scaleBitmap(original: Bitmap, maxDim: Int): Bitmap {
        val w = original.width
        val h = original.height
        if (w <= maxDim && h <= maxDim) {
            Timber.d("ImageRunner: image already within %dpx, no resize", maxDim)
            return original
        }
        val ratio = min(maxDim.toFloat() / w, maxDim.toFloat() / h)
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()
        return Bitmap.createScaledBitmap(original, newW, newH, true)
    }

    private fun createOutputFile(mimeType: String): File {
        val dateStr = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "PPA/$dateStr"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.w("ImageRunner: failed to create output dir %s", dir.absolutePath)
        }

        val ext = when {
            mimeType.contains("png", ignoreCase = true) -> ".png"
            mimeType.contains("webp", ignoreCase = true) -> ".webp"
            else -> ".jpg"
        }
        val filename = "IMG_${System.currentTimeMillis()}$ext"
        return File(dir, filename)
    }

    private fun saveBitmap(bitmap: Bitmap, file: File): Boolean {
        val format = if (file.extension.equals("png", ignoreCase = true))
            Bitmap.CompressFormat.PNG
        else
            Bitmap.CompressFormat.JPEG

        return try {
            file.outputStream().use { out ->
                bitmap.compress(format, COMPRESS_QUALITY, out)
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "ImageRunner: saveBitmap failed %s", file.absolutePath)
            false
        }
    }

    private fun deleteOriginal(imageId: Long, filePath: String) {
        try {
            var deleted = false
            if (imageId > 0) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)
                val rows = context.contentResolver.delete(uri, null, null)
                deleted = rows > 0
                if (deleted) {
                    Timber.i("ImageRunner: original deleted via ContentResolver imageId=%d", imageId)
                    return
                }
            }
            if (!deleted && filePath.isNotEmpty()) {
                if (File(filePath).delete()) {
                    Timber.i("ImageRunner: original deleted via file path=%s", filePath)
                } else {
                    Timber.w("ImageRunner: could not delete original path=%s", filePath)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "ImageRunner: failed to delete original")
        }
    }

    companion object {
        private const val MAX_DIMENSION = 1080
        private const val COMPRESS_QUALITY = 80
    }
}
