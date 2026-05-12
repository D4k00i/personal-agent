package com.personalagent.lifecycle

import android.content.Context
import android.os.HandlerThread
import android.provider.MediaStore
import com.personalagent.WorkerApp
import com.personalagent.agent.model.ModelCache
import com.personalagent.agent.model.ModelPreloader
import com.personalagent.agent.PhotoTriggerObserver
import com.personalagent.agent.taskPollingLoop
import com.personalagent.config.WorkerConfig
import com.personalagent.health.HealthCollector
import com.personalagent.identity.DeviceInfoCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ServiceLifecycle(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val drainMode = AtomicBoolean(false)
    private val isBusy = AtomicReference<Boolean>(false)
    private var taskPollJob: Job? = null

    private var observerThread: HandlerThread? = null
    private var photoObserver: PhotoTriggerObserver? = null

    fun start() {
        Timber.i("PersonalAgent lifecycle starting...")
        val config = WorkerConfig.load(context)
        val deviceInfo = DeviceInfoCollector.collect()
        val health = HealthCollector.snapshot(context)
        Timber.i("Device: %s, health: batt=%d%%", deviceInfo.deviceModel, health.batteryPercent)

        // Register photo trigger observer on background handler thread.
        registerPhotoObserver()

        taskPollJob = scope.launch {
            try {
                taskPollingLoop(
                    context = context,
                    pollIntervalSec = config.taskPollIntervalSec,
                    drainMode = drainMode,
                    isBusy = isBusy,
                    scope = scope,
                )
            } catch (e: Exception) {
                Timber.e(e, "Task polling loop crashed")
            }
        }

        // ---- Model pre-warming ----

        if (config.modelPreloadEnabled) {
            val cache = ModelCache.getInstance(config.maxModelRamMb.toLong() * 1024 * 1024)
            scope.launch {
                // Phase 1: Preload resident models immediately.
                ModelPreloader.preloadResidents(cache, context)

                // Phase 2: First time-of-day preload after a short delay.
                kotlinx.coroutines.delay(5_000L)
                ModelPreloader.preloadByTimeOfDay(cache, context)

                // Phase 3: Periodic time-of-day preload every N minutes.
                while (true) {
                    kotlinx.coroutines.delay(config.preloadIntervalMin * 60_000L)
                    if (drainMode.get()) {
                        Timber.d("ServiceLifecycle: drain mode — stopping preload cycle")
                        return@launch
                    }
                    ModelPreloader.preloadByTimeOfDay(cache, context)
                }
            }
        }

        Timber.i("PersonalAgent lifecycle started")
    }

    fun stop() {
        Timber.i("PersonalAgent lifecycle stopping...")
        unregisterPhotoObserver()
        taskPollJob?.cancel()
        taskPollJob = null
    }

    // ---- Photo ContentObserver lifecycle ----

    private fun registerPhotoObserver() {
        val thread = HandlerThread("PhotoTriggerObserver").apply { start() }
        val observer = PhotoTriggerObserver(
            contentResolver = context.contentResolver,
            dao = WorkerApp.db.personalTaskDao(),
            scope = scope,
            handler = android.os.Handler(thread.looper),
        )

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true, // notifyForDescendants — detect changes in sub-paths
            observer,
        )

        observerThread = thread
        photoObserver = observer
        Timber.i("PhotoTriggerObserver registered — uri=%s", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    }

    private fun unregisterPhotoObserver() {
        photoObserver?.let { observer ->
            context.contentResolver.unregisterContentObserver(observer)
            Timber.i("PhotoTriggerObserver unregistered")
        }
        photoObserver = null

        observerThread?.quitSafely()
        observerThread = null
    }
}
