package com.personalagent.agent.model

import android.content.Context
import com.personalagent.health.HealthCollector
import timber.log.Timber
import java.util.LinkedHashMap

/**
 * Thread-safe LRU cache for loaded AI models with health-aware admission control.
 *
 * ## Memory budget
 * Total: [maxMemoryBytes] = 4 GB. Resident models (~1.1 GB) are excluded from eviction.
 * On-demand pool: ~2.9 GB available, LRU-evicted when capacity is reached.
 *
 * ## Health gates
 * Models > 2 GB ([ModelMeta.requiresHealthyDevice]) require:
 * - Battery ≥ 50% OR charging
 * - Thermal ≤ WARM (not HOT or CRITICAL)
 *
 * ## Usage
 * ```
 * val cache = ModelCache.getInstance()
 * cache.preloadResidentModels(context)           // called once at startup
 * val model = cache.get(context, meta)            // auto load/evict
 * val stats = cache.getStats()                    // CacheStats(...)
 * ```
 *
 * @property maxMemoryBytes Hard memory cap for all loaded models (4 GB).
 */
class ModelCache(private val maxMemoryBytes: Long = 4L * 1024 * 1024 * 1024) {

    /** Loaded models keyed by name, insertion-ordered for LRU eviction. */
    private val loadedModels = object : LinkedHashMap<String, LoadedModel>(16, 0.75f, false) {
        // Manual eviction via evict() — not automatic via removeEldestEntry
    }

    @Volatile
    private var currentMemoryBytes: Long = 0L

    @Volatile
    private var residentMemoryBytes: Long = 0L

    // ---- Public API ----

    /**
     * Returns a loaded model, loading it through the cache if necessary.
     *
     * Applies health gate and memory eviction before loading. Resident models
     * are returned immediately (must be preloaded via [preloadResidentModels]).
     *
     * @param context Android context for health snapshot.
     * @param meta Model metadata from [ModelRegistry].
     * @return The loaded model object (stub: null when asset files are missing).
     * @throws ModelLoadException if health gate blocks loading.
     */
    @Synchronized
    fun get(context: Context, meta: ModelMeta): Any? {
        val key = meta.name

        // Check cache first.
        loadedModels[key]?.let { cached ->
            Timber.d("ModelCache: hit model=%s", key)
            // Re-insert to bump LRU position.
            loadedModels.remove(key)
            loadedModels[key] = cached
            return cached.model
        }

        Timber.d("ModelCache: miss model=%s (%.0fMB), loading...", key, meta.sizeBytes.toDouble() / MB)

        // ---- Health gate ----
        if (meta.requiresHealthyDevice) {
            val health = HealthCollector.snapshot(context)
            if (!isHealthyForLargeModel(health)) {
                Timber.w("ModelCache: health gate blocked model=%s batt=%d%% charging=%b thermal=%s",
                    key, health.batteryPercent, health.charging, health.thermalState)
                throw ModelLoadException(
                    "Health gate: cannot load ${meta.displayName} — " +
                        "battery=${health.batteryPercent}%, " +
                        "charging=${health.charging}, " +
                        "thermal=${health.thermalState}"
                )
            }
        }

        // ---- Memory check + eviction ----
        val needed = meta.sizeBytes
        // Don't count resident memory against eviction decisions.
        val nonResidentMemory = currentMemoryBytes - residentMemoryBytes
        if (nonResidentMemory + needed > maxMemoryBytes - residentMemoryBytes) {
            val toFree = (nonResidentMemory + needed) - (maxMemoryBytes - residentMemoryBytes)
            Timber.i("ModelCache: memory pressure — need %.0fMB, will evict %.0fMB",
                needed.toDouble() / MB, toFree.toDouble() / MB)
            evict(toFree)
        }

        // ---- Load model ----
        val model = ModelLoader.load(meta)
        if (model == null && !meta.resident) {
            // On-demand model file missing → stub, don't cache the failure.
            Timber.w("ModelCache: model file not available for %s (stub mode)", key)
            return null
        }

        // Cache the loaded model.
        val loaded = LoadedModel(
            meta = meta,
            model = model,
            loadedAtMs = System.currentTimeMillis(),
        )
        loadedModels[key] = loaded
        currentMemoryBytes += needed
        if (meta.resident) {
            residentMemoryBytes += needed
        }

        val status = if (model != null) "loaded" else "stubbed"
        Timber.i("ModelCache: %s model=%s (%.0fMB, total=%.0fMB, resident=%.0fMB)",
            status, key, needed.toDouble() / MB,
            currentMemoryBytes.toDouble() / MB, residentMemoryBytes.toDouble() / MB)
        return model
    }

    /**
     * Preloads all resident models at startup. Safe to call multiple times
     * (idempotent — skips already-loaded models).
     */
    @Synchronized
    fun preloadResidentModels(context: Context) {
        val residentModels = ModelRegistry.getResidentModels()
        Timber.i("ModelCache: preloading %d resident models...", residentModels.size)

        for (meta in residentModels) {
            if (loadedModels.containsKey(meta.name)) {
                Timber.d("ModelCache: resident model %s already loaded", meta.name)
                continue
            }
            try {
                get(context, meta)
            } catch (e: ModelLoadException) {
                Timber.e(e, "ModelCache: failed to preload resident model %s", meta.name)
            }
        }

        Timber.i("ModelCache: resident models preloaded — %d loaded, %.0fMB",
            loadedModels.count { it.value.meta.resident },
            residentMemoryBytes.toDouble() / MB)
    }

    /**
     * Checks whether a model is currently loaded in cache.
     */
    fun isLoaded(name: String): Boolean = synchronized(this) {
        loadedModels.containsKey(name)
    }

    /**
     * Returns cache statistics.
     */
    fun getStats(): CacheStats = synchronized(this) {
        CacheStats(
            currentMemoryBytes = currentMemoryBytes,
            residentMemoryBytes = residentMemoryBytes,
            loadedCount = loadedModels.size,
            residentCount = loadedModels.values.count { it.meta.resident },
        )
    }

    /**
     * Evicts all non-resident models.
     */
    @Synchronized
    fun evictAll() {
        val iter = loadedModels.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (!entry.value.meta.resident) {
                Timber.i("ModelCache: evicting model=%s (%.0fMB)", entry.key,
                    entry.value.meta.sizeBytes.toDouble() / MB)
                currentMemoryBytes -= entry.value.meta.sizeBytes
                iter.remove()
            }
        }
        Timber.i("ModelCache: all non-resident models evicted — remaining=%.0fMB",
            currentMemoryBytes.toDouble() / MB)
    }

    // ---- Private ----

    /**
     * Evicts non-resident models in insertion order (LRU) until at least
     * [toFreeBytes] have been freed.
     */
    private fun evict(toFreeBytes: Long) {
        var freed = 0L
        val iter = loadedModels.entries.iterator()
        val evictedModels = mutableListOf<String>()

        while (freed < toFreeBytes && iter.hasNext()) {
            val entry = iter.next()
            if (entry.value.meta.resident) continue // never evict resident models

            freed += entry.value.meta.sizeBytes
            evictedModels.add(entry.key)
            Timber.i("ModelCache: evicted model=%s (%.0fMB)",
                entry.key, entry.value.meta.sizeBytes.toDouble() / MB)
            iter.remove()
            currentMemoryBytes -= entry.value.meta.sizeBytes
        }

        if (evictedModels.isNotEmpty()) {
            Timber.i("ModelCache: evicted %d models, freed %.0fMB",
                evictedModels.size, freed.toDouble() / MB)
        }

        if (freed < toFreeBytes) {
            val residentUsed = loadedModels.values.filter { it.meta.resident }.sumOf { it.meta.sizeBytes }
            Timber.w("ModelCache: could not free enough memory — need=%.0fMB, freed=%.0fMB, resident=%.0fMB",
                toFreeBytes.toDouble() / MB, freed.toDouble() / MB, residentUsed.toDouble() / MB)
        }
    }

    /**
     * Checks whether the device is healthy enough to load a large model (>2 GB).
     */
    private fun isHealthyForLargeModel(health: com.personalagent.health.HealthSnapshot): Boolean {
        val batteryOk = health.batteryPercent >= 50 || health.charging
        val thermalOk = health.thermalState != "THERMAL_HOT" && health.thermalState != "THERMAL_CRITICAL"
        return batteryOk && thermalOk
    }

    // ---- Inner types ----

    private data class LoadedModel(
        val meta: ModelMeta,
        val model: Any?,
        val loadedAtMs: Long,
    )

    /** Snapshot of the cache state. */
    data class CacheStats(
        val currentMemoryBytes: Long,
        val residentMemoryBytes: Long,
        val loadedCount: Int,
        val residentCount: Int,
    )

    /** Thrown when health gate blocks model loading. */
    class ModelLoadException(message: String) : Exception(message)

    companion object {
        private const val MB = 1024L * 1024L

        @Volatile
        private var INSTANCE: ModelCache? = null

        fun getInstance(maxMemoryBytes: Long = 4L * 1024 * 1024 * 1024): ModelCache {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ModelCache(maxMemoryBytes).also { INSTANCE = it }
            }
        }
    }
}
