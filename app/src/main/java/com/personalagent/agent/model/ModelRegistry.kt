package com.personalagent.agent.model

import org.json.JSONObject
import timber.log.Timber

/**
 * Statically-configured registry mapping task subtypes → model metadata.
 *
 * ## Model inventory (8 GB RAM device)
 *
 * ### Resident models (always loaded, ~1.1 GB total)
 * | Model | Size | Purpose |
 * |-------|------|---------|
 * | BGE-small | 600 MB | Embedding (RAG local) |
 * | Whisper-small | 500 MB | Audio STT |
 *
 * ### On-demand pool (LRU, loaded when needed)
 * | Priority | Model | Size | Subtypes |
 * |----------|-------|------|----------|
 * | 1 | Florence-2-0.77B | 1.5 GB | vision |
 * | 2 | Hy-MT1.5-1.8B | 574 MB | translate |
 * | 3 | CodeS-1B | 2.0 GB | sql |
 * | 4 | Qwen2.5-Coder-1.5B | 3.0 GB | code |
 * | 4 | Qwen2.5-Math-1.5B | 3.0 GB | math |
 *
 * ## Usage
 * ```
 * val meta = ModelRegistry.resolve("AI", payloadJson)
 * if (meta != null) modelCache.get(context, meta)
 * ```
 */
object ModelRegistry {

    private const val MB = 1024L * 1024L

    // ---- Registry ----

    private val allModels: List<ModelMeta> = listOf(
        // Resident models
        ModelMeta(
            name = "bge-small",
            displayName = "BGE-small",
            sizeBytes = 600 * MB,
            assetPath = "models/bge-small/",
            engine = "ONNX",
            taskSubtypes = listOf("embedding"),
            priority = 0,
            resident = true,
            requiresHealthyDevice = false,
        ),
        ModelMeta(
            name = "whisper-small",
            displayName = "Whisper-small",
            sizeBytes = 500 * MB,
            assetPath = "models/whisper-small/",
            engine = "ONNX",
            taskSubtypes = listOf("stt"),
            priority = 0,
            resident = true,
            requiresHealthyDevice = false,
        ),
        // On-demand models
        ModelMeta(
            name = "florence-2",
            displayName = "Florence-2-0.77B",
            sizeBytes = 1500 * MB,
            assetPath = "models/florence-2/",
            engine = "TFLite",
            taskSubtypes = listOf("vision", "ocr"),
            priority = 1,
            resident = false,
            requiresHealthyDevice = false,
        ),
        // Opus-MT models — replacement for Hy-MT1.5 (ONNX, ~140 MB each direction)
        ModelMeta(
            name = "opus-mt-vi-en",
            displayName = "Opus-MT vi→en",
            sizeBytes = 140 * MB,
            assetPath = "models/opus-mt-vi-en/",
            engine = "ONNX",
            taskSubtypes = listOf("translate_vi_en"),
            priority = 2,
            resident = false,
            requiresHealthyDevice = false,
        ),
        ModelMeta(
            name = "opus-mt-en-vi",
            displayName = "Opus-MT en→vi",
            sizeBytes = 140 * MB,
            assetPath = "models/opus-mt-en-vi/",
            engine = "ONNX",
            taskSubtypes = listOf("translate_en_vi"),
            priority = 2,
            resident = false,
            requiresHealthyDevice = false,
        ),
        ModelMeta(
            name = "codes-1b",
            displayName = "CodeS-1B",
            sizeBytes = 2000 * MB,
            assetPath = "models/codes-1b/",
            engine = "TFLite",
            taskSubtypes = listOf("sql"),
            priority = 3,
            resident = false,
            requiresHealthyDevice = false,
        ),
        ModelMeta(
            name = "qwen-coder-1.5b",
            displayName = "Qwen2.5-Coder-1.5B",
            sizeBytes = 3000 * MB,
            assetPath = "models/qwen-coder-1.5b/",
            engine = "ONNX",
            taskSubtypes = listOf("code"),
            priority = 4,
            resident = false,
            requiresHealthyDevice = true,
        ),
        ModelMeta(
            name = "qwen-math-1.5b",
            displayName = "Qwen2.5-Math-1.5B",
            sizeBytes = 3000 * MB,
            assetPath = "models/qwen-math-1.5b/",
            engine = "ONNX",
            taskSubtypes = listOf("math"),
            priority = 4,
            resident = false,
            requiresHealthyDevice = true,
        ),
    )

    // Lookup views.
    private val byName: Map<String, ModelMeta> = allModels.associateBy { it.name }
    private val bySubtype: Map<String, ModelMeta> = run {
        val map = mutableMapOf<String, ModelMeta>()
        for (m in allModels) {
            for (s in m.taskSubtypes) {
                map[s] = m
            }
        }
        map
    }

    // ---- Public API ----

    /**
     * Resolves the best model for a given task payload.
     *
     * Extracts `subtype` from [payloadJson] and maps it to a [ModelMeta].
     * The first model that supports the subtype wins (subtype→model is 1:1).
     *
     * @param taskType Expected task type (e.g., "AI").
     * @param payloadJson Task payload with at least `{ "subtype": "..." }`.
     * @return Matching [ModelMeta] or null if no model supports the subtype.
     */
    fun resolve(taskType: String, payloadJson: String): ModelMeta? {
        val subtype = try {
            JSONObject(payloadJson).optString("subtype", "").lowercase()
        } catch (e: Exception) {
            Timber.e(e, "ModelRegistry: failed to parse payload")
            return null
        }

        if (subtype.isEmpty()) {
            Timber.w("ModelRegistry: payload missing subtype")
            return null
        }

        val meta = bySubtype[subtype]
        if (meta == null) {
            Timber.w("ModelRegistry: no model registered for subtype=%s", subtype)
        } else {
            Timber.d("ModelRegistry: subtype=%s → %s (%s, %.0fMB, resident=%b)",
                subtype, meta.displayName, meta.engine,
                meta.sizeBytes.toDouble() / MB, meta.resident)
        }
        return meta
    }

    /**
     * Returns a model by its unique name, or null.
     */
    fun getByName(name: String): ModelMeta? = byName[name]

    /**
     * Returns all resident models (should be preloaded at startup).
     */
    fun getResidentModels(): List<ModelMeta> = allModels.filter { it.resident }

    /**
     * Returns all on-demand models sorted by priority (ascending).
     */
    fun getOnDemandModels(): List<ModelMeta> =
        allModels.filter { !it.resident }.sortedBy { it.priority }

    /**
     * Returns all registered models.
     */
    fun getAllModels(): List<ModelMeta> = allModels.toList()
}
