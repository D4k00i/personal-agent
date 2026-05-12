package com.personalagent.agent

import android.content.Context
import com.personalagent.agent.model.AIRunner
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Dispatches a [PersonalTask] to the appropriate runner based on [PersonalTask.type].
 *
 * ## Runner dispatch table
 * | Task type | Runner | Phase 1 status |
 * |---|:---:|---|
 * | `IMAGE` | [ImageRunner] | ✅ Implemented |
 * | `TEXT` | (stub) | ⏳ 2s delay, returns success |
 * | `AI` | [AIRunner] | ✅ Implemented (stub inference via ModelPool) |
 * | `AUTO` | (stub) | ⏳ 2s delay, returns success |
 * | unknown | — | ❌ Returns failure |
 *
 * @property context Android context passed to runners that need system services.
 *
 * @see ImageRunner
 * @see AIRunner
 * @see TaskPollingLoop
 */
class TaskExecutor(private val context: Context) {

    /**
     * Outcome of a single task execution.
     *
     * @property success Whether the runner completed without error.
     * @property outputPayload Optional updated JSON payload (e.g., output path
     *           from [ImageRunner]). The caller should persist this via
     *           [PersonalTaskDao.updatePayload].
     */
    data class ExecutionResult(
        val success: Boolean,
        val outputPayload: String? = null,
    )

    /**
     * Executes the given task synchronously (the caller should invoke this
     * from a background coroutine/thread).
     *
     * Image tasks run on [kotlinx.coroutines.Dispatchers.IO] via [ImageRunner.execute].
     * AI tasks run via [AIRunner.execute] through the ModelPool.
     * Stub tasks (TEXT, AUTO) simulate work with a delay.
     *
     * @param task The task to execute.
     * @return [ExecutionResult] with success/failure and optional updated payload.
     */
    suspend fun execute(task: PersonalTask): ExecutionResult {
        Timber.i("TaskExecutor: dispatching task id=%s type=%s", task.id, task.type)

        return when (task.type.uppercase()) {
            "IMAGE" -> {
                val runner = ImageRunner(context, task)
                val ok = runner.execute()
                if (ok) {
                    ExecutionResult(success = true, outputPayload = runner.buildUpdatedPayload())
                } else {
                    ExecutionResult(success = false)
                }
            }
            "TEXT" -> {
                // TODO: TextRunner — summarise / classify
                Timber.w("TaskExecutor: TEXT runner not implemented — simulating")
                delay(2_000L)
                ExecutionResult(success = true)
            }
            "AI" -> {
                val runner = AIRunner(context, task)
                val ok = runner.execute()
                if (ok) {
                    ExecutionResult(success = true, outputPayload = runner.buildResultPayload())
                } else {
                    ExecutionResult(success = false)
                }
            }
            "AUTO" -> {
                // TODO: AutoRunner — action chain / macros
                Timber.w("TaskExecutor: AUTO runner not implemented — simulating")
                delay(2_000L)
                ExecutionResult(success = true)
            }
            else -> {
                Timber.e("TaskExecutor: unknown task type=%s", task.type)
                ExecutionResult(success = false)
            }
        }
    }
}
