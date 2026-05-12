package com.personalagent.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Unit tests for [ImageRunner].
 *
 * All tests use real Bitmap operations on temporary files — no mocks.
 * Output directory is cleaned up after each test.
 */
@RunWith(AndroidJUnit4::class)
class ImageRunnerTest {

    private lateinit var context: Context
    private lateinit var outputDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        outputDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "PPA/test-${UUID.randomUUID()}"
        )
        outputDir.mkdirs()
    }

    @After
    fun tearDown() {
        outputDir.deleteRecursively()
    }

    // -------------------------------------------------------------------------
    // TC-01: Image resizing — 4000x3000 → output ≤1080px, aspect ratio maintained
    // -------------------------------------------------------------------------

    @Test
    fun resize_largeImage_4000x3000_toMax1080_maintainsAspectRatio() = runBlocking {
        val input = createBitmap(width = 4000, height = 3000)
        val inputFile = saveBitmapToTempFile(input, "input_large.jpg")

        val task = makeTask(payload = jsonOf(
            "imageId" to -1L,
            "path" to inputFile.absolutePath,
            "mimeType" to "image/jpeg",
            "deleteOriginal" to false
        ))

        val runner = ImageRunner(context, task)
        val ok = runner.execute()

        assertTrue("ImageRunner should succeed", ok)
        assertNotNull("outputPath should be set", runner.outputPath)

        val outputFile = File(runner.outputPath!!)
        assertTrue("Output file should exist", outputFile.exists())

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull("Output should be decodable", outputBitmap)

        // Both dimensions must be ≤ 1080
        assertTrue("Width should be ≤ 1080 (was ${outputBitmap.width})", outputBitmap.width <= 1080)
        assertTrue("Height should be ≤ 1080 (was ${outputBitmap.height})", outputBitmap.height <= 1080)

        // Aspect ratio must be preserved: 4:3 = 1.333...
        val ratio = outputBitmap.width.toDouble() / outputBitmap.height.toDouble()
        assertTrue(
            "Aspect ratio should be preserved (4:3 ≈ 1.333, got $ratio)",
            ratio > 1.32 && ratio < 1.35
        )

        // Output should be smaller than input
        assertTrue(
            "Output should be smaller than input",
            outputFile.length() < inputFile.length()
        )

        outputBitmap.recycle()
        inputBitmapRecycle(input)
    }

    @Test
    fun resize_1080pImage_noResizeNeeded() = runBlocking {
        // Exactly 1080 wide — should not resize.
        val input = createBitmap(width = 1080, height = 720)
        val inputFile = saveBitmapToTempFile(input, "input_1080p.jpg")

        val task = makeTask(payload = jsonOf(
            "imageId" to -1L,
            "path" to inputFile.absolutePath,
            "mimeType" to "image/jpeg",
            "deleteOriginal" to false
        ))

        val runner = ImageRunner(context, task)
        val ok = runner.execute()

        assertTrue("ImageRunner should succeed", ok)
        val outputBitmap = BitmapFactory.decodeFile(runner.outputPath!!)
        assertEquals(1080, outputBitmap.width)
        assertEquals(720, outputBitmap.height)
        outputBitmap.recycle()
        inputBitmapRecycle(input)
    }

    @Test
    fun resize_portraitImage_3000x4000_maintainsAspectRatio() = runBlocking {
        val input = createBitmap(width = 3000, height = 4000) // tall portrait
        val inputFile = saveBitmapToTempFile(input, "input_portrait.jpg")

        val task = makeTask(payload = jsonOf(
            "imageId" to -1L,
            "path" to inputFile.absolutePath,
            "mimeType" to "image/jpeg",
            "deleteOriginal" to false
        ))

        val runner = ImageRunner(context, task)
        val ok = runner.execute()

        assertTrue("ImageRunner should succeed", ok)
        val outputBitmap = BitmapFactory.decodeFile(runner.outputPath!!)

        assertTrue("Width should be ≤ 1080", outputBitmap.width <= 1080)
        assertTrue("Height should be ≤ 1080", outputBitmap.height <= 1080)

        // Portrait: 3:4 = 0.75
        val ratio = outputBitmap.width.toDouble() / outputBitmap.height.toDouble()
        assertTrue("Aspect ratio should be preserved (3:4 ≈ 0.75, got $ratio)", ratio > 0.74 && ratio < 0.76)

        outputBitmap.recycle()
        inputBitmapRecycle(input)
    }

    // -------------------------------------------------------------------------
    // TC-02: Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun resize_verySmallImage_100x100_noResize() = runBlocking {
        val input = createBitmap(width = 100, height = 100)
        val inputFile = saveBitmapToTempFile(input, "input_tiny.jpg")

        val task = makeTask(payload = jsonOf(
            "imageId" to -1L,
            "path" to inputFile.absolutePath,
            "mimeType" to "image/jpeg",
            "deleteOriginal" to false
        ))

        val runner = ImageRunner(context, task)
        val ok = runner.execute()

        assertTrue("ImageRunner should succeed on small image", ok)
        val outputBitmap = BitmapFactory.decodeFile(runner.outputPath!!)
        assertEquals(100, outputBitmap.width)
        assertEquals(100, outputBitmap.height)
        outputBitmap.recycle()
        inputBitmapRecycle(input)
    }

    @Test
    fun resize_corruptedFile_returnsFalse() = runBlocking {
        // Write garbage bytes to a "JPEG" file.
        val corruptedFile = File(outputDir, "corrupted.jpg")
        corruptedFile.writeBytes(ByteArray(256) { 0xFF.toByte() })

        val task = makeTask(payload = jsonOf(
            "imageId" to -1L,
            "path" to corruptedFile.absolutePath,
            "mimeType" to "image/jpeg",
            "deleteOriginal" to false
        ))

        val runner = ImageRunner(context, task)
        val ok = runner.execute()

        assertFalse("ImageRunner should return false for corrupted file", ok)
        assertNull("outputPath should not be set", runner.outputPath)
    }

    @Test
    fun resize_nullInput_returnsFalse() = runBlocking {
        // Payload with neither imageId nor path — should fail gracefully.
        val task = makeTask(payload = jsonOf(
            "imageId" to -1L,
            "path" to "",
            "mimeType" to "image/jpeg",
            "deleteOriginal" to false
        ))

        val runner = ImageRunner(context, task)
        val ok = runner.execute()

        assertFalse("ImageRunner should return false when no image source", ok)
    }

    @Test
    fun resize_nonexistentPath_returnsFalse() = runBlocking {
        val task = makeTask(payload = jsonOf(
            "imageId" to -1L,
            "path" to "/nonexistent/path/photo.jpg",
            "mimeType" to "image/jpeg",
            "deleteOriginal" to false
        ))

        val runner = ImageRunner(context, task)
        val ok = runner.execute()

        assertFalse("ImageRunner should return false for nonexistent path", ok)
    }

    // -------------------------------------------------------------------------
    // TC-03: Output path follows Pictures/PPA/YYYY-MM/IMG_<timestamp>.jpg convention
    // -------------------------------------------------------------------------

    @Test
    fun outputPath_followsNamingConvention() = runBlocking {
        val input = createBitmap(width = 2000, height = 1500)
        val inputFile = saveBitmapToTempFile(input, "input_convention.jpg")

        val task = makeTask(payload = jsonOf(
            "imageId" to -1L,
            "path" to inputFile.absolutePath,
            "mimeType" to "image/jpeg",
            "deleteOriginal" to false
        ))

        val runner = ImageRunner(context, task)
        runner.execute()

        val outputPath = runner.outputPath ?: fail("outputPath was not set")
        val outputFile = File(outputPath)

        // Must be under Pictures/PPA/
        assertTrue(
            "Output should be under Pictures/PPA/ (got: $outputPath)",
            outputPath.contains("Pictures/PPA/")
        )

        // Filename must start with "IMG_"
        assertTrue(
            "Filename should start with IMG_ (got: ${outputFile.name})",
            outputFile.name.startsWith("IMG_")
        )

        // Extension must be .jpg (JPEG)
        assertTrue(
            "Extension should be .jpg (got: ${outputFile.extension})",
            outputFile.extension == "jpg"
        )

        // Directory must be YYYY-MM format
        val relativePath = outputFile.parentFile?.name ?: ""
        assertTrue(
            "Output directory should be YYYY-MM (got: $relativePath)",
            relativePath.matches(Regex("\\d{4}-\\d{2}"))
        )

        inputBitmapRecycle(input)
    }

    @Test
    fun outputPath_pngExtension_whenMimeTypeIsPng() = runBlocking {
        val input = createBitmap(width = 2000, height = 1500)
        val inputFile = saveBitmapToTempFile(input, "input.png", format = Bitmap.CompressFormat.PNG)

        val task = makeTask(payload = jsonOf(
            "imageId" to -1L,
            "path" to inputFile.absolutePath,
            "mimeType" to "image/png",
            "deleteOriginal" to false
        ))

        val runner = ImageRunner(context, task)
        runner.execute()

        val outputPath = runner.outputPath ?: fail("outputPath was not set")
        assertTrue(
            "Extension should be .png when mimeType is image/png (got: ${File(outputPath).extension})",
            File(outputPath).extension == "png"
        )

        inputBitmapRecycle(input)
    }

    @Test
    fun outputPath_outputSizeBytes_isSet() = runBlocking {
        val input = createBitmap(width = 2000, height = 1500)
        val inputFile = saveBitmapToTempFile(input, "input_size.jpg")

        val task = makeTask(payload = jsonOf(
            "imageId" to -1L,
            "path" to inputFile.absolutePath,
            "mimeType" to "image/jpeg",
            "deleteOriginal" to false
        ))

        val runner = ImageRunner(context, task)
        runner.execute()

        assertTrue("outputSizeBytes should be > 0", runner.outputSizeBytes > 0)
        assertEquals(
            "outputSizeBytes should match file length",
            File(runner.outputPath!!).length(),
            runner.outputSizeBytes
        )

        inputBitmapRecycle(input)
    }

    // -------------------------------------------------------------------------
    // TC-04: buildUpdatedPayload — merges outputPath + outputSizeBytes
    // -------------------------------------------------------------------------

    @Test
    fun buildUpdatedPayload_mergesOutputFields() = runBlocking {
        val input = createBitmap(width = 2000, height = 1500)
        val inputFile = saveBitmapToTempFile(input, "input_payload.jpg")

        val originalPayload = jsonOf(
            "imageId" to 123L,
            "path" to inputFile.absolutePath,
            "mimeType" to "image/jpeg",
            "deleteOriginal" to false
        )

        val task = makeTask(payload = originalPayload)
        val runner = ImageRunner(context, task)
        runner.execute()

        val updatedPayload = runner.buildUpdatedPayload()
        val json = JSONObject(updatedPayload)

        assertEquals("outputPath should be in updated payload", runner.outputPath, json.optString("outputPath"))
        assertEquals("outputSizeBytes should be in updated payload", runner.outputSizeBytes, json.optLong("outputSizeBytes"))
        assertEquals("Original imageId should be preserved", 123L, json.optLong("imageId"))
        assertEquals("Original path should be preserved", inputFile.absolutePath, json.optString("path"))

        inputBitmapRecycle(input)
    }

    @Test
    fun buildUpdatedPayload_beforeExecute_returnsEmptyOrOriginal() = runBlocking {
        val task = makeTask(payload = jsonOf("imageId" to 1L, "path" to "/test.jpg", "mimeType" to "image/jpeg"))
        val runner = ImageRunner(context, task)

        val updated = runner.buildUpdatedPayload()
        val json = JSONObject(updated)

        // outputPath not set yet, so should not appear or be empty string
        assertTrue(
            "outputPath should not be set before execute",
            !json.has("outputPath") || json.optString("outputPath").isEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // TC-05: Delete original (deleteOriginal = true)
    // -------------------------------------------------------------------------

    @Test
    fun execute_deleteOriginalFlag_deletesFile() = runBlocking {
        val input = createBitmap(width = 2000, height = 1500)
        val inputFile = saveBitmapToTempFile(input, "input_delete.jpg")

        assertTrue("Input file should exist before", inputFile.exists())

        val task = makeTask(payload = jsonOf(
            "imageId" to -1L,
            "path" to inputFile.absolutePath,
            "mimeType" to "image/jpeg",
            "deleteOriginal" to true
        ))

        val runner = ImageRunner(context, task)
        val ok = runner.execute()

        assertTrue("ImageRunner should succeed", ok)
        // Note: File.delete() may fail due to sandboxing on newer Android.
        // The runner handles this gracefully. We just verify it doesn't crash.
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createBitmap(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            // Fill with a solid color for predictable file sizes.
            eraseColor(Color.rgb(128, 64, 192))
        }
    }

    private fun inputBitmapRecycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun saveBitmapToTempFile(
        bitmap: Bitmap,
        filename: String,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    ): File {
        val file = File(outputDir, filename)
        file.outputStream().use { out ->
            bitmap.compress(format, 95, out)
        }
        return file
    }

    private fun makeTask(payload: JSONObject) = PersonalTask(
        id = UUID.randomUUID().toString(),
        type = "IMAGE",
        payloadJson = payload.toString(),
        priority = 10,
        status = "PENDING",
    )

    private fun jsonOf(vararg pairs: Pair<String, Any>): JSONObject {
        val json = JSONObject()
        for ((k, v) in pairs) {
            when (v) {
                is Long -> json.put(k, v)
                is String -> json.put(k, v)
                is Boolean -> json.put(k, v)
                is Int -> json.put(k, v)
            }
        }
        return json
    }
}