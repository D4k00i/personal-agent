package com.personalagent.agent

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a single task in the personal task queue.
 *
 * Each row is one unit of work — enqueued by a trigger (e.g. [PhotoTriggerObserver]
 * when a new photo is saved) and processed by a runner (e.g. [ImageRunner]) via
 * the [TaskPollingLoop].
 *
 * ## Lifecycle
 * ```
 * PENDING → RUNNING → DONE
 *                  → FAILED
 * ```
 *
 * ## Task Types
 * - `IMAGE` — image processing (resize, organize) — implemented
 * - `TEXT`  — text summarisation / classification — stub (Phase 2)
 * - `AI`    — on-device AI inference via [AIRunner] — implemented (stub inference)
 * - `AUTO`  — automated action chains / macros — stub (Phase 2)
 *
 * ## Payload schemas
 *
 * ### IMAGE task payload
 * ```
 * { "imageId": 12345, "path": "/sdcard/DCIM/...", "mimeType": "image/jpeg",
 *   "deleteOriginal": false }
 * ```
 *
 * ### AI task payload
 * ```
 * {
 *   "subtype": "translate|vision|sql|code|math",
 *   "input": "<text or base64 data>",
 *   "params": { "sourceLang": "vi", "targetLang": "en" }
 * }
 * ```
 * Subtypes:
 * - `translate` — Seq2seq translation (TFLite t5-small)
 * - `vision` — Image classification / OCR (TFLite mobilenet-v3)
 * - `sql` — Text-to-SQL generation (TFLite t5-small-text2sql)
 * - `code` — Code generation (ONNX codegen-350m)
 * - `math` — Math problem solving (TFLite flan-t5-math)
 * Params are optional and subtype-specific (e.g., sourceLang/targetLang for translate).
 *
 * @property id UUID v4 string used as primary key.
 * @property type Task type identifier: IMAGE, TEXT, AI, or AUTO.
 * @property payloadJson JSON payload whose schema depends on [type].
 * @property priority Execution priority; higher values are dequeued first.
 * @property status Current lifecycle state: PENDING, RUNNING, DONE, or FAILED.
 * @property createdAt Epoch-millis timestamp when the task was created.
 * @property scheduledAt Epoch-millis timestamp for deferred execution (future).
 * @property completedAt Epoch-millis timestamp when the task reached a terminal state.
 */
@Entity(tableName = "personal_tasks")
data class PersonalTask(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "priority") val priority: Int = 0,
    @ColumnInfo(name = "status") val status: String = "PENDING",
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "max_retries") val maxRetries: Int = 3,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
)
