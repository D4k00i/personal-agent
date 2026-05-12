package com.personalagent.agent.model

import android.content.Context
import com.personalagent.agent.PersonalTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import kotlin.random.Random

/**
 * Inference dispatcher for AI-type tasks.
 *
 * ## Pipeline
 * 1. Parse [PersonalTask.payloadJson] → extract `subtype` and `input`.
 * 2. Resolve model via [ModelRegistry.resolve].
 * 3. Load model via [ModelCache.get] (health gate + LRU).
 * 4. Run subtype-specific inference.
 * 5. Build result payload with output, model name, and latency.
 *
 * ## Inference subtypes
 * | Subtype          | Description                              | Stub output                        |
 * |------------------|------------------------------------------|------------------------------------|
 * | translate_vi_en  | Seq2seq translation Vietnamese→English  | "[STUB] translated (vi→en): ..."   |
 * | translate_en_vi  | Seq2seq translation English→Vietnamese  | "[STUB] translated (en→vi): ..."   |
 * | vision           | Image classification / OCR               | "[STUB] vision result: ..."        |
 * | sql              | Text-to-SQL generation                   | "SELECT * FROM stub_table WHERE..."|
 * | code             | Code generation                          | "// [STUB] generated code for: ..."|
 * | math             | Mathematical problem solving             | "[STUB] answer: 42"               |
 *
 * In Sprint 2, inference uses stubs with realistic latency (1-3s).
 * Real ONNX execution will be wired in Phase 3 when inference logic is implemented.
 *
 * @property context Android context (reserved for future model file access).
 * @property task The AI-type task to execute.
 *
 * @see ModelRegistry
 * @see ModelCache
 * @see TaskExecutor
 */
class AIRunner(
    private val context: Context,
    private val task: PersonalTask,
) {
    /** Duration of the inference call in milliseconds. Set during [execute]. */
    var latencyMs: Long = 0
        private set

    // ---- Public API ----

    /**
     * Executes the AI inference pipeline on [Dispatchers.IO].
     *
     * @return true on success, false if any step failed (parse, resolve, load, or inference).
     */
    suspend fun execute(): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.i("AIRunner: starting task id=%s", task.id)

            // 1. Parse payload.
            val parsed = parsePayload() ?: return@withContext false

            // 2. Resolve model.
            val meta = ModelRegistry.resolve("AI", task.payloadJson)
            if (meta == null) {
                Timber.e("AIRunner: no model found for task id=%s", task.id)
                return@withContext false
            }

            // 3. Load model through cache (health gate + LRU).
            try {
                ModelCache.getInstance().get(context, meta)
            } catch (e: ModelCache.ModelLoadException) {
                Timber.e(e, "AIRunner: health gate blocked model=%s", meta.name)
                return@withContext false
            }

            // 4. Run inference.
            val startMs = System.currentTimeMillis()
            val output = runInference(parsed.subtype, parsed.input)
            latencyMs = System.currentTimeMillis() - startMs

            Timber.i("AIRunner: inference complete subtype=%s latency=%dms", parsed.subtype, latencyMs)
            true
        } catch (e: Exception) {
            Timber.e(e, "AIRunner: task failed id=%s", task.id)
            false
        }
    }

    /**
     * Builds the result payload JSON containing the inference output.
     * Call after a successful [execute].
     */
    fun buildResultPayload(): String {
        val subtype = JSONObject(task.payloadJson).optString("subtype", "").lowercase()
        val meta = ModelRegistry.resolve("AI", task.payloadJson)
        val modelName = meta?.name ?: "unknown"

        return JSONObject().apply {
            put("output", lastOutput)
            put("model", modelName)
            put("latencyMs", latencyMs)
            put("subtype", subtype)
            put("summary", "$subtype: $lastOutput".take(120))
        }.toString()
    }

    // ---- Private ----

    private var lastOutput: String = ""

    private data class ParsedInput(
        val subtype: String,
        val input: String,
    )

    /**
     * Parses the task payload and extracts the subtype + input text.
     * Required fields: `subtype`, `input`.
     */
    private fun parsePayload(): ParsedInput? {
        return try {
            val json = JSONObject(task.payloadJson)
            val subtype = json.optString("subtype", "").lowercase()
            val input = json.optString("input", "")

            if (subtype.isEmpty()) {
                Timber.e("AIRunner: payload missing subtype, task id=%s", task.id)
                return null
            }
            if (input.isEmpty()) {
                Timber.e("AIRunner: payload missing input, task id=%s", task.id)
                return null
            }

            Timber.d("AIRunner: parsed subtype=%s inputLen=%d", subtype, input.length)
            ParsedInput(subtype, input)
        } catch (e: Exception) {
            Timber.e(e, "AIRunner: failed to parse payload json for task id=%s", task.id)
            null
        }
    }

    // ---- Inference stubs ----

    /**
     * Dispatches inference to the appropriate stub based on [subtype].
     *
     * Each stub simulates a realistic processing delay and returns a
     * placeholder result. Real ONNX calls will replace these stubs
     * in Phase 3 when inference logic is implemented.
     */
    private suspend fun runInference(subtype: String, input: String): String {
        Timber.d("AIRunner: inference MOCK subtype=%s inputLen=%d", subtype, input.length)

        lastOutput = when (subtype) {
            "translate_vi_en" -> stubTranslateViEn(input)
            "translate_en_vi" -> stubTranslateEnVi(input)
            "vision" -> stubVision(input)
            "sql" -> stubTextToSQL(input)
            "code" -> stubCodeGen(input)
            "math" -> stubMath(input)
            else -> {
                Timber.w("AIRunner: unknown subtype=%s, using generic stub", subtype)
                "[STUB] unknown subtype '$subtype'"
            }
        }

        return lastOutput
    }

    // ---- Subtype stubs ----

    private suspend fun stubTranslateViEn(input: String): String {
        delay(1_000L + Random.nextLong(2_000L)) // 1-3s realistic latency
        return "[STUB] translated (vi→en): ${input.take(80)}"
    }

    private suspend fun stubTranslateEnVi(input: String): String {
        delay(1_000L + Random.nextLong(2_000L)) // 1-3s realistic latency
        return "[STUB] translated (en→vi): ${input.take(80)}"
    }

    private suspend fun stubVision(input: String): String {
        delay(500L + Random.nextLong(1_500L)) // 0.5-2s
        return "[STUB] vision: detected 'cat' (confidence=0.94) in image"
    }

    private suspend fun stubTextToSQL(input: String): String {
        delay(1_500L + Random.nextLong(2_000L)) // 1.5-3.5s
        return "SELECT id, name, created_at FROM stub_table WHERE status = 'active' ORDER BY created_at DESC LIMIT 10;"
    }

    private suspend fun stubCodeGen(input: String): String {
        delay(2_000L + Random.nextLong(3_000L)) // 2-5s
        return "// [STUB] generated Kotlin function for: ${input.take(50)}\n" +
            "fun process(input: String): String {\n" +
            "    // TODO: implement logic\n" +
            "    return input.reversed()\n" +
            "}"
    }

    private suspend fun stubMath(input: String): String {
        delay(500L + Random.nextLong(1_000L)) // 0.5-1.5s
        return "[STUB] answer: 42 (parsed expression: ${input.take(30)})"
    }
}