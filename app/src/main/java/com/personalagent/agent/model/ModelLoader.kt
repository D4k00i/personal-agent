package com.personalagent.agent.model

import timber.log.Timber
import java.io.File

/**
 * Handles loading AI model files from the assets directory and initialising
 * the appropriate inference engine (TFLite [org.tensorflow.lite.Interpreter]
 * or ONNX [ai.onnxruntime.OrtSession]).
 *
 * In Sprint 2, model files are not yet bundled in the APK — all loads return
 * stub objects. The full implementation will be activated in Phase 3 when
 * model `.tflite` / `.onnx` files are placed in `app/src/main/assets/models/`.
 *
 * ## Usage
 * ```
 * val model = ModelLoader.load(meta) // returns stub or real interpreter
 * val size = ModelLoader.getModelSize(meta) // returns meta.sizeBytes
 * ```
 *
 * @see ModelMeta
 * @see ModelCache
 */
object ModelLoader {

    /**
     * Loads a model from asset files and returns an inference-capable object.
     *
     * In Sprint 2 (stub mode), checks if the asset directory exists and returns
     * a descriptive stub string. When model files are present, this will initialise
     * the appropriate engine.
     *
     * @param meta Model metadata with [ModelMeta.assetPath] pointing to the model directory.
     * @return A loaded model object (stub: null), or null if the model cannot be loaded.
     */
    fun load(meta: ModelMeta): Any? {
        Timber.d("ModelLoader: loading %s from %s (engine=%s, %.0fMB)",
            meta.displayName, meta.assetPath, meta.engine,
            meta.sizeBytes.toDouble() / (1024.0 * 1024.0))

        // For Sprint 2: return null to signal stub mode.
        // Phase 3 will check asset files and initialise TFLite/ONNX engine.
        Timber.i("ModelLoader: stub mode — %s not actually loaded (asset files not bundled)", meta.name)
        return null
    }

    /**
     * Returns the pre-configured size of a model from the registry.
     *
     * This is the authoritative size for memory accounting, even before
     * the model file is downloaded.
     */
    fun getModelSize(meta: ModelMeta): Long = meta.sizeBytes

    /**
     * Checks whether the model asset directory exists under `assets/models/`.
     * Always returns false in Sprint 2 since files aren't bundled.
     */
    fun isModelAvailable(meta: ModelMeta): Boolean {
        // In Phase 3, this will check if the actual model file exists:
        //   context.assets.list(meta.assetPath)?.isNotEmpty() == true
        Timber.d("ModelLoader: isModelAvailable(%s) → false (stub)", meta.name)
        return false
    }
}
