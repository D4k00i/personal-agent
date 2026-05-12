package com.personalagent.agent

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Unit tests for [PhotoTriggerObserver].
 *
 * Mocks [ContentResolver] and [PersonalTaskDao] to verify that
 * [onChange] correctly queries MediaStore and inserts a task.
 */
@RunWith(AndroidJUnit4::class)
class PhotoTriggerObserverTest {

    private lateinit var contentResolver: ContentResolver
    private lateinit var dao: PersonalTaskDao
    private lateinit var observer: PhotoTriggerObserver
    private val scope = CoroutineScope(Dispatchers.Unconfined) // Unconfined for easy testing

    @Before
    fun setup() {
        contentResolver = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)

        observer = PhotoTriggerObserver(
            contentResolver = contentResolver,
            dao = dao,
            scope = scope,
            handler = handler
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // TC-01: Observer inserts a task into DB when onChange fires
    // -------------------------------------------------------------------------

    @Test
    fun onChange_queriesMediaStore_andInsertsTask() = runBlocking {
        // 1. Mock ContentResolver.query to return one photo.
        val photoId = 12345L
        val photoPath = "/storage/emulated/0/DCIM/Camera/IMG_20260511.jpg"
        val mimeType = "image/jpeg"

        val cursor = MatrixCursor(arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED
        ))
        cursor.addRow(arrayOf(photoId, photoPath, "IMG_20260511.jpg", mimeType, 1715400000L))

        every {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(), any(), any(), any()
            )
        } returns cursor

        // 2. Fire onChange.
        val uri = Uri.parse("content://media/external/images/media/12345")
        observer.onChange(false, uri)

        // 3. Verify DAO insert was called with correct task.
        val taskSlot = slot<PersonalTask>()
        coVerify(timeout = 1000) {
            dao.insert(capture(taskSlot))
        }

        val task = taskSlot.captured
        assertEquals("IMAGE", task.type)
        assertEquals(10, task.priority)
        assertEquals("PENDING", task.status)

        val payload = JSONObject(task.payloadJson)
        assertEquals(photoId, payload.getLong("imageId"))
        assertEquals(photoPath, payload.getString("path"))
        assertEquals(mimeType, payload.getString("mimeType"))
    }

    @Test
    fun onChange_noPhotoFound_doesNotInsertTask() = runBlocking {
        // Mock empty cursor.
        val cursor = MatrixCursor(arrayOf(MediaStore.Images.Media._ID))
        every {
            contentResolver.query(any(), any(), any(), any(), any())
        } returns cursor

        observer.onChange(false, Uri.parse("content://media/external/images/media/1"))

        coVerify(exactly = 0) {
            dao.insert(any())
        }
    }

    @Test
    fun onChange_selfChange_isIgnored() = runBlocking {
        observer.onChange(true, Uri.parse("content://media/external/images/media/1"))

        coVerify(exactly = 0) {
            dao.insert(any())
        }
        verify(exactly = 0) {
            contentResolver.query(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun onChange_queryFails_handlesGracefully() = runBlocking {
        // Mock query throwing exception.
        every {
            contentResolver.query(any(), any(), any(), any(), any())
        } throws RuntimeException("Database error")

        observer.onChange(false, Uri.parse("content://media/external/images/media/1"))

        // Should not crash, and should not insert.
        coVerify(exactly = 0) {
            dao.insert(any())
        }
    }

    // -------------------------------------------------------------------------
    // TC-02: Verify timestamp and photo metadata are parsed correctly
    // -------------------------------------------------------------------------

    @Test
    fun queryLatestPhoto_extractsCorrectColumns() = runBlocking {
        val dateAdded = 1715400000L
        val cursor = MatrixCursor(arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED
        ))
        cursor.addRow(arrayOf(1L, "/test.jpg", "test.jpg", "image/png", dateAdded))

        every {
            contentResolver.query(any(), any(), null, null, any())
        } returns cursor

        observer.onChange(false, null)

        val taskSlot = slot<PersonalTask>()
        coVerify { dao.insert(capture(taskSlot)) }

        val payload = JSONObject(taskSlot.captured.payloadJson)
        assertEquals("image/png", payload.getString("mimeType"))
    }

    @Test
    fun queryLatestPhoto_handlesNullDataColumn() = runBlocking {
        val cursor = MatrixCursor(arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED
        ))
        // DATA is null — common in some MediaStore edge cases or sandbox restrictions
        cursor.addRow(arrayOf(1L, null, "test.jpg", "image/jpeg", 123L))

        every {
            contentResolver.query(any(), any(), any(), any(), any())
        } returns cursor

        observer.onChange(false, null)

        // Should skip insert if path is missing (as per implementation `?: return null`)
        coVerify(exactly = 0) {
            dao.insert(any())
        }
    }
}