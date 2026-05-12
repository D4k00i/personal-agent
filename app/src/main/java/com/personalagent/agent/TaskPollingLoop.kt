package com.personalagent.agent

import android.content.Context
import com.personalagent.WorkerApp
import com.personalagent.config.WorkerConfig
import com.personalagent.health.HealthCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent coroutine loop that polls the Room database for PENDING tasks,
 * executes them via [TaskExecutor], records results, retries FAILED tasks,
 * and posts completion notifications.
 *
 * ## Execution flow per cycle
 * 1. Check [drainMode] — if active, exit the loop entirely.
 * 2. Check [isBusy] — if another task is already executing, skip this cycle.
 * 3. Check health gate — pause if battery < 20% (not charging) or thermal ≥ HOT.
 * 4. **Retry scan**: query FAILED tasks with remaining retries, re-enqueue with backoff.
 * 5. Query [PersonalTaskDao.getOldestPending] for the next task.
 * 6. If a task is found:
 *    - Set [isBusy] to true.
 *    - Launch a coroutine to execute the task.
 *    - On success: complete + updatePayload + notify "Completed".
 *    - On failure: mark FAILED — retry engine picks it up next cycle.
 *    - On terminal failure (max retries exhausted): notify "Failed".
 * 7. If no task: sleep for [pollIntervalSec] seconds.
 *
 * @param context Android context for health collection and task execution.
 * @param pollIntervalSec Seconds to sleep between polls when no task is available.
 * @param drainMode Flag indicating the agent is shutting down — stops the loop.
 * @param isBusy Atomic flag for single-task mutual exclusion.
 * @param scope Coroutine scope for launching task execution coroutines.
 *
 * @see TaskExecutor
 * @see ImageRunner
 * @see NotificationHelper
 * @see HealthCollector
 */
suspend fun taskPollingLoop(
    context: Context,
    pollIntervalSec: Int,
    drainMode: AtomicBoolean,
    isBusy: AtomicReference<Boolean>,
    scope: CoroutineScope,
) {
    Timber.i("PersonalTaskPolling loop started — interval=%ds", pollIntervalSec)
    val dao = WorkerApp.db.personalTaskDao()
    val executor = TaskExecutor(context)
    val config = WorkerConfig.load(context)

    while (true) {
        // ---- 1. Drain check ----
        if (drainMode.get()) {
            Timber.w("TaskPolling: drain mode active, stopping poll")
            return
        }

        // ---- 2. Busy gate ----
        if (isBusy.get()) {
            delay(1_000L)
            continue
        }

        // ---- 3. Health gate ----
        val health = HealthCollector.snapshot(context)
        if (health.batteryPercent < config.minBatteryPercent && !health.charging) {
            Timber.d("TaskPolling: battery too low (%d%%), pausing", health.batteryPercent)
            delay(30_000L)
            continue
        }
        if (health.thermalState == "THERMAL_HOT" || health.thermalState == "THERMAL_CRITICAL") {
            Timber.d("TaskPolling: thermal elevated (%s), pausing", health.thermalState)
            delay(10_000L)
            continue
        }

        // ---- 4. Retry scan ----
        processRetries(dao, context, config)

        // ---- 5. Dequeue next task ----
        val pending = dao.getOldestPending(1)
        if (pending.isNotEmpty()) {
            val task = pending.first()
            Timber.i("TaskPolling: task found id=%s type=%s", task.id, task.type)
            isBusy.set(true)

            scope.launch {
                try {
                    dao.updateStatus(task.id, "RUNNING")
                    Timber.i("TaskPolling: task status set to RUNNING id=%s", task.id)

                    val result = executor.execute(task)

                    val now = System.currentTimeMillis()
                    if (result.success) {
                        dao.completeTask(task.id, "DONE", now)
                        result.outputPayload?.let { payload ->
                            dao.updatePayload(task.id, payload)
                        }
                        Timber.i("TaskPolling: task completed id=%s", task.id)
                        NotificationHelper.show(context, task, "Completed")
                    } else {
                        // Mark FAILED — retry engine will pick it up next cycle if retries remain.
                        dao.updateStatus(task.id, "FAILED")
                        Timber.w("TaskPolling: task failed id=%s attempt=%d/%d", task.id, task.retryCount + 1, task.maxRetries)

                        if (task.retryCount >= task.maxRetries) {
                            Timber.e("TaskPolling: terminal failure id=%s (retries=%d/%d)", task.id, task.retryCount, task.maxRetries)
                            NotificationHelper.show(context, task, "Failed")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "TaskPolling: task execution crashed id=%s", task.id)
                    dao.updateStatus(task.id, "FAILED")
                    if (task.retryCount >= task.maxRetries) {
                        NotificationHelper.show(context, task, "Failed")
                    }
                } finally {
                    isBusy.set(false)
                }
            }
        } else {
            Timber.d("TaskPolling: no tasks available, sleeping %ds", pollIntervalSec)
            delay(pollIntervalSec * 1000L)
        }
    }
}

// ---- Retry engine ----

/**
 * Scans the database for FAILED tasks that still have retries remaining,
 * resets them to PENDING with an incremented retry count, and applies
 * exponential backoff before re-enqueuing.
 *
 * Exponential backoff: baseMs * 2^(retryCount-1)
 *   retry 1 → 5s, retry 2 → 10s, retry 3 → 20s
 *
 * Tasks that have exhausted their maxRetries are NOT re-enqueued
 * (they remain FAILED permanently).
 */
private suspend fun processRetries(
    dao: PersonalTaskDao,
    context: Context,
    config: WorkerConfig,
) {
    val retryableTasks = try {
        dao.getRetryableFailedTasks()
    } catch (e: Exception) {
        Timber.e(e, "TaskPolling: retry scan query failed")
        return
    }

    for (task in retryableTasks) {
        val newCount = task.retryCount + 1
        val backoffMs = config.retryBackoffBaseMs * (1L shl (newCount - 1))

        Timber.i("TaskPolling: retrying task id=%s attempt=%d/%d backoff=%dms",
            task.id, newCount, task.maxRetries, backoffMs)

        delay(backoffMs)

        try {
            dao.updateRetry(task.id, newCount, "PENDING")
            Timber.i("TaskPolling: task re-enqueued id=%s", task.id)
        } catch (e: Exception) {
            Timber.e(e, "TaskPolling: failed to re-enqueue task id=%s", task.id)
        }
    }
}
