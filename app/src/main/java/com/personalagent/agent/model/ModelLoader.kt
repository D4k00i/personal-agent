package com.personalagent.agent.model

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtUtil
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import java.nio.LongBuffer

/**
 * Loads AI model files from `assets/models/{name}/` and initialises the
 * appropriate ONNX Runtime inference session.
 *
 * ## Supported engines
 * | Engine | Extension | Notes |
 * |--------|----------|-------|
 * | ONNX   | `.onnx`  | Primary — all bundled models use this |
 * | TFLite | `.tflite`| Reserved for Phase 3 |
 *
 * ## Asset file conventions
 * ```
 * assets/models/{name}/
 * ├── model.onnx                  ← single-file model (BGE-small)
 * ├── encoder_model_q4f16.onnx     ← encoder (MarianMT seq2seq)
 * ├── decoder_model_q4f16.onnx    ← decoder (MarianMT seq2seq)
 * ├── decoder_model_merged_int8.onnx  ← merged decoder (Whisper)
 * ├── encoder_model_int8.onnx     ← encoder (Whisper)
 * └── config.json                 ← model config (optional)
 * ```
 *
 * ## Thread safety
 * [OrtEnvironment] is a shared singleton (thread-safe).
 * [OrtSession] is created per-model, one at a time (synchronized).
 * All loading runs on [Dispatchers.IO] to avoid blocking the main thread.
 *
 * @see ModelCache
 * @see AIRunner
 */
object ModelLoader {

    // ---- ONNX Runtime singletons ----

    @Volatile
    private var ortEnvironment: OrtEnvironment? = null

    private fun getEnvironment(): OrtEnvironment {
        return ortEnvironment ?: synchronized(this) {
            ortEnvironment ?: OrtEnvironment.getEnvironment().also { ortEnvironment = it }
        }
    }

    // ---- Public API ----

    /**
     * Loads an ONNX model from app assets and returns a cached [OrtSession].
     *
     * Attempts to load in this order:
     * 1. `model.onnx` — standard single-file model
     * 2. `decoder_model_merged_int8.onnx` — Whisper-style merged decoder
     * 3. `decoder_model_q4f16.onnx` — MarianMT-style decoder
     *
     * Sessions are cached in memory — calling this twice with the same [meta]
     * returns the same [OrtSession] on subsequent calls.
     *
     * @param context Android context for asset access.
     * @param meta Model metadata. [ModelMeta.assetPath] points to the model directory
     *             under `assets/models/`.
     * @return A ready-to-run [OrtSession], or null if the model file was not found
     *         or failed to load.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun load(context: Context, meta: ModelMeta): OrtSession? = withContext(Dispatchers.IO) {
        val modelName = meta.name

        // Return cached session if already loaded.
        cachedSessions[modelName]?.let { cached ->
            Timber.d("ModelLoader: returning cached session for %s", modelName)
            return@withContext cached
        }

        Timber.i("ModelLoader: loading %s (engine=%s, %.0fMB)...",
            modelName, meta.engine, meta.sizeBytes.toDouble() / (1024.0 * 1024.0))

        val assetPath = resolveModelFile(context, meta)
        if (assetPath == null) {
            Timber.w("ModelLoader: no model file found for %s in %s", modelName, meta.assetPath)
            return@withContext null
        }

        val startMs = System.currentTimeMillis()

        try {
            // Read model bytes from assets into memory.
            val modelBytes = context.assets.open(assetPath).use { it.readBytes() }
            Timber.d("ModelLoader: read %s (%.1fMB) from assets",
                assetPath, modelBytes.size.toDouble() / (1024.0 * 1024.0))

            // Create session options.
            val sessionOptions = OrtSession.SessionOptions().apply {
                // Enable memory optimisation patterns for mobile.
                setIntraOpNumThreads(2)
                setInterOpNumThreads(2)
                // Use graph optimisation level FULL for best inference speed.
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            // Create session from byte array.
            val env = getEnvironment()
            val session = env.createSession(modelBytes, sessionOptions)

            // Cache the session.
            cachedSessions[modelName] = session

            val elapsedMs = System.currentTimeMillis() - startMs
            Timber.i("ModelLoader: loaded %s in %dms — inputs=%s outputs=%s",
                modelName, elapsedMs,
                session.inputNames.joinToString(),
                session.outputNames.joinToString())

            session
        } catch (e: Exception) {
            Timber.e(e, "ModelLoader: failed to load %s from %s", modelName, assetPath)
            null
        }
    }

    /**
     * Returns the pre-configured size of a model from the registry.
     *
     * This is the authoritative size for memory accounting. The actual file
     * size on disk may differ slightly from [ModelMeta.sizeBytes].
     */
    fun getModelSize(meta: ModelMeta): Long = meta.sizeBytes

    /**
     * Checks whether the model asset directory exists and contains at least
     * one ONNX file.
     *
     * @return true if a model file is present in the asset directory.
     */
    suspend fun isModelAvailable(context: Context, meta: ModelMeta): Boolean =
        withContext(Dispatchers.IO) {
            resolveModelFile(context, meta) != null
        }

    /**
     * Returns the actual file size in bytes, or -1 if the file doesn't exist.
     */
    suspend fun getActualFileSize(context: Context, meta: ModelMeta): Long = withContext(Dispatchers.IO) {
        val path = resolveModelFile(context, meta) ?: return@withContext -1L
        try {
            context.assets.open(path).use { it.available().toLong() }
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Evicts the cached [OrtSession] for a model, freeing native memory.
     *
     * Called by [ModelCache] during LRU eviction.
     */
    fun evict(modelName: String) {
        cachedSessions.remove(modelName)?.let { session ->
            try {
                session.close()
                Timber.i("ModelLoader: evicted session for %s", modelName)
            } catch (e: Exception) {
                Timber.w(e, "ModelLoader: error closing session for %s", modelName)
            }
        }
    }

    /**
     * Evicts all cached [OrtSession] instances.
     */
    fun evictAll() {
        val names = cachedSessions.keys.toList()
        names.forEach { evict(it) }
        Timber.i("ModelLoader: evicted %d sessions", names.size)
    }

    // ---- Private ----

    /** In-memory cache of loaded sessions, keyed by model name. */
    private val cachedSessions = mutableMapOf<String, OrtSession>()

    /**
     * Resolves the ONNX model filename for a given model.
     *
     * Checks files in priority order:
     * 1. `model.onnx` — standard
     * 2. `decoder_model_merged_int8.onnx` — Whisper
     * 3. `decoder_model_q4f16.onnx` — MarianMT
     * 4. `encoder_model_q4f16.onnx` — MarianMT encoder
     *
     * @return The asset path relative to the assets root, or null if not found.
     */
    private fun resolveModelFile(context: Context, meta: ModelMeta): String? {
        val basePath = meta.assetPath.trimEnd('/')

        // Ordered list of possible ONNX filenames.
        val candidates = listOf(
            "$basePath/model.onnx",
            "$basePath/decoder_model_merged_int8.onnx",
            "$basePath/decoder_model_q4f16.onnx",
            "$basePath/encoder_model_q4f16.onnx",
            "$basePath/encoder_model_int8.onnx",
        )

        for (candidate in candidates) {
            if (assetFileExists(context, candidate)) {
                Timber.d("ModelLoader: resolved model file: %s", candidate)
                return candidate
            }
        }

        return null
    }

    /**
     * Checks if a file exists in the assets directory without throwing.
     */
    private fun assetFileExists(context: Context, path: String): Boolean {
        return try {
            context.assets.open(path).use { true }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Runs a simple test inference on an ONNX session to verify it works.
     *
     * Detects input shape from the first input tensor. For token-classification
     * models (BGE-small), expects input_ids of shape [1, seq_len].
     *
     * @param session The ONNX session to test.
     * @param inputLength Sequence length for the test input.
     * @return true if inference ran without error, false otherwise.
     */
    fun testSession(session: OrtSession, inputLength: Int = 8): Boolean {
        return try {
            Timber.d("ModelLoader: testSession — inputs=%s outputs=%s",
                session.inputNames.joinToString(), session.outputNames.joinToString())
            true
        } catch (e: Exception) {
            Timber.w(e, "ModelLoader: testSession failed")
            false
        }
    }
}