package com.personalagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.personalagent.lifecycle.ServiceLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Foreground service that hosts the worker agent lifecycle.
 *
 * Runs persistently with a low-importance notification. Owns a [CoroutineScope]
 * for all agent loops (heartbeat, task polling).
 */
class WorkerForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lifecycle: ServiceLifecycle? = null

    // ---- Service lifecycle ----

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.d("WorkerForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                Timber.i("Stop action received — shutting down")
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Start as foreground with persistent notification.
        val notification = buildNotification("starting…")
        startForeground(NOTIFICATION_ID, notification)

        // Initialise and start the worker lifecycle.
        lifecycle = ServiceLifecycle(applicationContext, serviceScope)
        lifecycle?.start()

        Timber.i("WorkerForegroundService started (START_STICKY)")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
        Timber.i("WorkerForegroundService destroyed")
    }

    private fun stopEverything() {
        lifecycle?.stop()
        lifecycle = null
    }

    // ---- Notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Personal Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Personal Phone Agent running in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Personal Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun updateNotification(status: String) {
        val notification = buildNotification(status)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "personal_agent"
        const val NOTIFICATION_ID = 1001
    }
}
