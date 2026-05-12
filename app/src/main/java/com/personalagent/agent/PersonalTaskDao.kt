package com.personalagent.agent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [PersonalTask] entities.
 *
 * All suspend functions are called from background coroutines
 * ([Dispatchers.IO] or [Dispatchers.Default]). Flow-returning queries
 * are collected on [Dispatchers.Main] by [com.personalagent.MainActivity].
 */
@Dao
interface PersonalTaskDao {

    /**
     * Inserts or replaces a task.
     *
     * Uses [OnConflictStrategy.REPLACE] so that idempotent re-inserts
     * (e.g., duplicate observer triggers) are harmless.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: PersonalTask)

    /**
     * Returns a single task by primary key, or null if not found.
     */
    @Query("SELECT * FROM personal_tasks WHERE id = :id")
    suspend fun getById(id: String): PersonalTask?

    /**
     * Returns all PENDING tasks ordered by priority (descending) then
     * creation time (ascending). Used by the polling loop for dequeuing.
     */
    @Query("SELECT * FROM personal_tasks WHERE status = 'PENDING' ORDER BY priority DESC, created_at ASC")
    suspend fun getPendingTasksSortedByPriority(): List<PersonalTask>

    /**
     * Updates only the status column for a task.
     *
     * Used for simple transitions: PENDING → RUNNING, RUNNING → FAILED.
     *
     * @param id Target task ID.
     * @param status New status value.
     */
    @Query("UPDATE personal_tasks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /**
     * Marks a task as reaching a terminal state and records the completion time.
     *
     * Atomic write of both [status] and [completedAt] to avoid partial updates.
     *
     * @param id Target task ID.
     * @param status Terminal status (typically "DONE" or "FAILED").
     * @param completedAt Epoch-millis completion timestamp.
     */
    @Query("UPDATE personal_tasks SET status = :status, completed_at = :completedAt WHERE id = :id")
    suspend fun completeTask(id: String, status: String, completedAt: Long)

    /**
     * Replaces the JSON payload of an existing task.
     *
     * Called by the polling loop after a runner enriches the payload with
     * output metadata (e.g., [ImageRunner] adds outputPath/outputSizeBytes).
     *
     * @param id Target task ID.
     * @param payload Updated JSON payload string.
     */
    @Query("UPDATE personal_tasks SET payload_json = :payload WHERE id = :id")
    suspend fun updatePayload(id: String, payload: String)

    /**
     * Returns the oldest PENDING task(s) for the polling loop to dequeue.
     *
     * @param limit Maximum number of tasks to return (default 1 — single-task model).
     * @return List of tasks ordered by created_at ascending (oldest first).
     */
    @Query("SELECT * FROM personal_tasks WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit")
    suspend fun getOldestPending(limit: Int = 1): List<PersonalTask>

    /**
     * Cleans up completed tasks older than the given cutoff.
     *
     * Uses [completedAt] epoch-millis comparison to avoid timezone issues.
     *
     * @param cutoff Epoch-millis threshold; tasks completed before this are deleted.
     */
    @Query("DELETE FROM personal_tasks WHERE status = 'DONE' AND completed_at < :cutoff")
    suspend fun deleteCompleted(cutoff: Long)

    // ---- Retry engine ----

    /**
     * Returns all FAILED tasks that can still be retried (retryCount &lt; maxRetries).
     */
    @Query("SELECT * FROM personal_tasks WHERE status = 'FAILED' AND retry_count < max_retries ORDER BY created_at ASC")
    suspend fun getRetryableFailedTasks(): List<PersonalTask>

    /**
     * Atomically updates the retry count and status for a task being re-enqueued.
     */
    @Query("UPDATE personal_tasks SET retry_count = :count, status = :status WHERE id = :id")
    suspend fun updateRetry(id: String, count: Int, status: String)

    // ---- Reactive queries for Dashboard UI ----

    /**
     * Returns a [Flow] of up to 20 PENDING tasks for the dashboard.
     *
     * The Flow emits whenever the underlying Room table changes
     * (insert, update, delete), enabling reactive UI updates.
     */
    @Query("SELECT * FROM personal_tasks WHERE status = 'PENDING' ORDER BY priority DESC, created_at ASC LIMIT 20")
    fun getPendingTasksFlow(): Flow<List<PersonalTask>>

    /**
     * Returns a [Flow] of the 10 most recently completed or failed tasks.
     *
     * The Flow emits whenever the underlying Room table changes,
     * enabling reactive UI updates.
     */
    @Query("SELECT * FROM personal_tasks WHERE status IN ('DONE', 'FAILED') ORDER BY completed_at DESC LIMIT 10")
    fun getRecentTasksFlow(): Flow<List<PersonalTask>>
}
