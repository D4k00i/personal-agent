package com.personalagent.agent.model

/**
 * Metadata describing an AI model managed by the ModelPool.
 *
 * ## Model categories
 * - **Resident** ([resident]=true): always loaded, never evicted (BGE-small, Whisper-small).
 * - **On-demand**: loaded when needed, evicted by LRU when memory is tight.
 *
 * ## Health gates
 * Models with [requiresHealthyDevice]=true (>2 GB) require the device to be
 * in a healthy state before loading: battery ≥ 50% OR charging, AND thermal ≤ WARM.
 *
 * @property name Unique model identifier (e.g., "hy-mt1.5").
 * @property displayName Human-readable name (e.g., "Hy-MT1.5-1.8B").
 * @property sizeBytes Estimated RAM footprint in bytes.
 * @property assetPath Path under `assets/models/` where model files live.
 * @property engine Inference backend: "TFLite" or "ONNX".
 * @property taskSubtypes List of task subtypes this model can serve (e.g., ["translate"]).
 * @property priority Load priority: 1 = highest, 4 = lowest.
 * @property resident If true, preloaded at startup and never evicted.
 * @property requiresHealthyDevice If true, health gate is enforced before loading.
 */
data class ModelMeta(
    val name: String,
    val displayName: String,
    val sizeBytes: Long,
    val assetPath: String,
    val engine: String,
    val taskSubtypes: List<String>,
    val priority: Int,
    val resident: Boolean,
    val requiresHealthyDevice: Boolean,
)
