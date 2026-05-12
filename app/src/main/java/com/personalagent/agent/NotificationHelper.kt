package com.personalagent.agent

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.personalagent.MainActivity
import com.personalagent.WorkerForegroundService
import org.json.JSONObject
import timber.log.Timber

/**
 * One-shot notification helper that posts task lifecycle events to the system tray.
 *
 * Uses the same [WorkerForegroundService.CHANNEL_ID] so notifications appear in the
 * "Personal Agent" channel without creating a duplicate.
 *
 * ## Usage
 * ```
 * NotificationHelper.show(context, task, "Completed")
 * NotificationHelper.show(context, task, "Failed")
 * ```
 */
object NotificationHelper {

    /**
     * Posts a task status notification.
     *
     * Extracts a human-readable summary from [PersonalTask.payloadJson] if available,
     * falling back to the task type.
     *
     * @param context Android context for NotificationManager.
     * @param task   The task whose lifecycle event triggered this notification.
     * @param status Display label, e.g. "Completed" or "Failed".
     */
    fun show(context: Context, task: PersonalTask, status: String) {
        val title = "Task $status"
        val body = buildBody(task)

        Timber.i("NotificationHelper: posting notification title=%s body=%s", title, body)

        // Tap action → open MainActivity.
        val tapIntent = PendingIntent.getActivity(
            context,
            task.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, WorkerForegroundService.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(task.id.hashCode(), notification)
    }

    // ---- Private ----

    private fun buildBody(task: PersonalTask): String {
        val summary = extractSummary(task.payloadJson)
        return if (summary != null) {
            "${task.type} task — $summary"
        } else {
            "${task.type} task — id=${task.id.take(8)}"
        }
    }

    private fun extractSummary(payloadJson: String): String? {
        return try {
            val json = JSONObject(payloadJson)
            json.optString("summary", null)
        } catch (_: Exception) {
            null
        }
    }
}
