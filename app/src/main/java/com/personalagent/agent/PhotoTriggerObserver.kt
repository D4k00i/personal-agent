package com.personalagent.agent

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID

/**
 * Observes the MediaStore image collection for new photos and enqueues
 * IMAGE tasks into the PPA task database.
 *
 * ## Behaviour
 * When a new photo is saved (by Camera or any app that writes to MediaStore),
 * this observer:
 * 1. Queries the latest image from `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`.
 * 2. Creates a [PersonalTask] with `type="IMAGE"` and `priority=10`.
 * 3. Inserts the task via [PersonalTaskDao.insert].
 *
 * ## Thread safety
 * Callbacks run on a dedicated [HandlerThread] via the [android.os.Handler]
 * passed to the constructor. DB inserts are dispatched to [Dispatchers.IO]
 * via the provided [CoroutineScope].
 *
 * @property contentResolver Used to query MediaStore and register/unregister.
 * @property dao DAO for inserting IMAGE tasks.
 * @property scope Coroutine scope for dispatching DB writes off the handler thread.
 * @constructor Creates an observer registered to receive callbacks on [handler]'s looper.
 *
 * @see PersonalTask
 * @see PersonalTaskDao.insert
 */
class PhotoTriggerObserver(
    private val contentResolver: ContentResolver,
    private val dao: PersonalTaskDao,
    private val scope: CoroutineScope,
    handler: Handler,
) : ContentObserver(handler) {

    /**
     * Called by the system when a change is detected in the observed URI.
     *
     * Ignores self-triggered changes ([selfChange] == true). Dispatches
     * a DB write coroutine to query the latest photo and enqueue a task.
     */
    override fun onChange(selfChange: Boolean, uri: Uri?) {
        // Ignore self-triggered changes (the observer itself didn't cause this).
        if (selfChange) return

        Timber.d("PhotoTriggerObserver: onChange triggered, uri=%s", uri)

        scope.launch(Dispatchers.IO) {
            try {
                val photo = queryLatestPhoto()
                if (photo == null) {
                    Timber.d("PhotoTriggerObserver: no photo found in query")
                    return@launch
                }

                val task = PersonalTask(
                    id = UUID.randomUUID().toString(),
                    type = "IMAGE",
                    payloadJson = JSONObject().apply {
                        put("imageId", photo.mediaStoreId)
                        put("path", photo.filePath)
                        put("mimeType", photo.mimeType)
                    }.toString(),
                    priority = 10, // Images are the primary use case — high priority
                )

                dao.insert(task)
                Timber.i("PhotoTriggerObserver: enqueued IMAGE task id=%s path=%s", task.id, photo.filePath)
            } catch (e: Exception) {
                Timber.e(e, "PhotoTriggerObserver: failed to enqueue IMAGE task")
            }
        }
    }

    // ---- MediaStore query ----

    private data class PhotoInfo(
        val mediaStoreId: Long,
        val filePath: String,
        val displayName: String,
        val mimeType: String,
        val dateAdded: Long,
    )

    private fun queryLatestPhoto(): PhotoInfo? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,   // selection
            null,   // selectionArgs
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return PhotoInfo(
                    mediaStoreId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)),
                    filePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                        ?: return null,
                    displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                        ?: "",
                    mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))
                        ?: "image/jpeg",
                    dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)),
                )
            }
        }

        return null
    }
}
