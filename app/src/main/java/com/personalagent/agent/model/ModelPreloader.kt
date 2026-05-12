package com.personalagent.agent.model

import android.content.Context
import com.personalagent.health.HealthCollector
import timber.log.Timber
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicReference

/**
 * Predictive model pre-warming to reduce cold-start latency.
 *
 * ## Cheat sheet
 * | Source | When | What |
 * |--------|------|------|
 * | `preloadResidents` | Startup | BGE-small + Whisper-small always |
 * | `preloadByTimeOfDay` | Every N min | Morning→translate, Evening→vision, Night→code+math |
 * | `preloadIfIdle` | Idle window | Next expected on-demand model |
 *
 * ## Time-of-day heuristic
 * ```
 * 06:00–10:00  Morning    Preload Hy-MT1.5 (translate)  — foreign news
 * 18:00–22:00  Evening    Preload Florence-2 (vision)    — photo organization
 * 22:00–06:00  Night      Preload code+math (Qwen)       — heavy work, only if plugged in
 * 10:00–18:00  Day        No predictive preload           — let LRU handle naturally
 * ```
 *
 * Health gates are respected throughout — heavy models fail gracefully if
 * battery/thermal conditions are unfavourable.
 *
 * ## Usage
 * ```kotlin
 * val cache = ModelCache.getInstance()
 * ModelPreloader.preloadResidents(cache, context)          // startup
 * ModelPreloader.preloadByTimeOfDay(cache, context)        // periodic
 * ModelPreloader.preloadIfIdle(cache, context, isBusy)     // opportunistic
 * ```
 */
object ModelPreloader {

    // ---- Time windows ----

    private val MORNING_START  = LocalTime.of(6, 0)
    private val MORNING_END    = LocalTime.of(10, 0)
    private val EVENING_START  = LocalTime.of(18, 0)
    private val EVENING_END    = LocalTime.of(22, 0)
    // Night: 22:00–06:00 (spans midnight)

    // ---- Public API ----

    /**
     * Preloads all resident models at startup.
     *
     * Delegates to [ModelCache.preloadResidentModels] and reports results.
     * Safe to call multiple times (idempotent via the cache).
     *
     * @return [PreloadReport] describing what was loaded or already cached.
     */
    fun preloadResidents(cache: ModelCache, context: Context): PreloadReport {
        Timber.i("ModelPreloader: preloading resident models...")
        val statsBefore = cache.getStats()

        cache.preloadResidentModels(context)

        val statsAfter = cache.getStats()
        val report = PreloadReport(
            attempted = true,
            source = "residents",
            modelsAttempted = ModelRegistry.getResidentModels().map { it.displayName },
            modelsPreloaded = if (statsAfter.residentMemoryBytes > statsBefore.residentMemoryBytes)
                ModelRegistry.getResidentModels().map { it.displayName } else emptyList(),
            memoryDelta = statsAfter.currentMemoryBytes - statsBefore.currentMemoryBytes,
            cacheStats = statsAfter,
        )
        Timber.i("ModelPreloader: resident preload complete — %s", report.summary())
        return report
    }

    /**
     * Preloads one model based on the current time of day.
     *
     * Uses a simple heuristic:
     * - **Morning (06:00–10:00)**: Hy-MT1.5 (translate) — user may read foreign news.
     * - **Evening (18:00–22:00)**: Florence-2 (vision) — user may organize photos.
     * - **Night (22:00–06:00)**: Qwen-Coder + Qwen-Math (code, math) — heavy models,
     *   only attempted if device is charging or battery ≥ 50% (enforced by health gate).
     * - **Day (10:00–18:00)**: No predictive preload — let LRU handle naturally.
     *
     * @return [PreloadReport] with the model chosen and whether it was loaded.
     */
    fun preloadByTimeOfDay(cache: ModelCache, context: Context): PreloadReport {
        val now = LocalTime.now()
        val models = selectModelsForTimeWindow(now)

        if (models.isEmpty()) {
            Timber.d("ModelPreloader: time-of-day — no models for window %s", timeWindowName(now))
            return PreloadReport(
                attempted = true,
                source = "time-of-day (${timeWindowName(now)})",
                modelsAttempted = emptyList(),
                modelsPreloaded = emptyList(),
                memoryDelta = 0L,
                cacheStats = cache.getStats(),
            )
        }

        Timber.i("ModelPreloader: time-of-day %s — considering %s",
            timeWindowName(now), models.joinToString { it.displayName })

        val statsBefore = cache.getStats()
        val preloadedNames = mutableListOf<String>()

        for (meta in models) {
            if (cache.isLoaded(meta.name)) {
                Timber.d("ModelPreloader: %s already loaded, skipping", meta.displayName)
                continue
            }

            try {
                cache.get(context, meta)
                preloadedNames.add(meta.displayName)
                Timber.i("ModelPreloader: time-of-day preloaded %s (%.0fMB)",
                    meta.displayName, meta.sizeBytes.toDouble() / (1024.0 * 1024.0))
            } catch (e: ModelCache.ModelLoadException) {
                Timber.w("ModelPreloader: health gate blocked %s — %s", meta.displayName, e.message)
            } catch (e: Exception) {
                Timber.e(e, "ModelPreloader: failed to preload %s", meta.displayName)
            }
        }

        val statsAfter = cache.getStats()
        val report = PreloadReport(
            attempted = true,
            source = "time-of-day (${timeWindowName(now)})",
            modelsAttempted = models.map { it.displayName },
            modelsPreloaded = preloadedNames,
            memoryDelta = statsAfter.currentMemoryBytes - statsBefore.currentMemoryBytes,
            cacheStats = statsAfter,
        )
        Timber.i("ModelPreloader: time-of-day preload done — %s", report.summary())
        return report
    }

    /**
     * Opportunistic preload — runs when the system is idle.
     *
     * Picks the highest-priority on-demand model not yet loaded and attempts
     * to preload it. Respects the same health gates as [preloadByTimeOfDay].
     *
     * @param cache The model cache.
     * @param context Android context for health snapshot.
     * @param isBusy Reference to the polling loop's busy flag. If true, skips.
     * @return [PreloadReport] for the attempt, or null if skipped because busy.
     */
    fun preloadIfIdle(
        cache: ModelCache,
        context: Context,
        isBusy: AtomicReference<Boolean>,
    ): PreloadReport? {
        if (isBusy.get()) {
            Timber.d("ModelPreloader: idle preload skipped — system busy")
            return null
        }

        val stats = cache.getStats()
        val availableMemory = 4L * 1024 * 1024 * 1024 - stats.currentMemoryBytes
        if (availableMemory < 600 * 1024 * 1024) {
            Timber.d("ModelPreloader: idle preload skipped — only %.0fMB free",
                availableMemory.toDouble() / (1024.0 * 1024.0))
            return null
        }

        val onDemand = ModelRegistry.getOnDemandModels()
        val candidate = onDemand.firstOrNull { !cache.isLoaded(it.name) && it.sizeBytes <= availableMemory }
            ?: run {
                Timber.d("ModelPreloader: idle preload — no suitable candidate")
                return PreloadReport(
                    attempted = true,
                    source = "idle",
                    modelsAttempted = emptyList(),
                    modelsPreloaded = emptyList(),
                    memoryDelta = 0L,
                    cacheStats = stats,
                )
            }

        Timber.i("ModelPreloader: idle preload — loading %s (%.0fMB)",
            candidate.displayName, candidate.sizeBytes.toDouble() / (1024.0 * 1024.0))

        val statsBefore = cache.getStats()
        val preloadedNames = mutableListOf<String>()

        try {
            cache.get(context, candidate)
            preloadedNames.add(candidate.displayName)
        } catch (e: ModelCache.ModelLoadException) {
            Timber.w("ModelPreloader: idle health gate blocked %s — %s", candidate.displayName, e.message)
        } catch (e: Exception) {
            Timber.e(e, "ModelPreloader: idle preload failed for %s", candidate.displayName)
        }

        val statsAfter = cache.getStats()
        val report = PreloadReport(
            attempted = true,
            source = "idle",
            modelsAttempted = listOf(candidate.displayName),
            modelsPreloaded = preloadedNames,
            memoryDelta = statsAfter.currentMemoryBytes - statsBefore.currentMemoryBytes,
            cacheStats = statsAfter,
        )
        Timber.i("ModelPreloader: idle preload done — %s", report.summary())
        return report
    }

    // ---- Private ----

    /**
     * Returns the models to preload for the given time window.
     * Empty list means "no predictive preload right now".
     */
    private fun selectModelsForTimeWindow(now: LocalTime): List<ModelMeta> {
        return when {
            // Morning: 06:00–10:00 — translate model
            now >= MORNING_START && now < MORNING_END -> {
                listOfNotNull(ModelRegistry.getByName("hy-mt1.5"))
            }
            // Evening: 18:00–22:00 — vision model
            now >= EVENING_START && now < EVENING_END -> {
                listOfNotNull(ModelRegistry.getByName("florence-2"))
            }
            // Night: 22:00–06:00 — code + math (heavy, health-gated)
            now >= EVENING_END || now < MORNING_START -> {
                listOfNotNull(
                    ModelRegistry.getByName("qwen-coder-1.5b"),
                    ModelRegistry.getByName("qwen-math-1.5b"),
                )
            }
            // Day: 10:00–18:00 — nothing
            else -> emptyList()
        }
    }

    private fun timeWindowName(now: LocalTime): String = when {
        now >= MORNING_START && now < MORNING_END -> "morning"
        now >= EVENING_START && now < EVENING_END -> "evening"
        now >= EVENING_END || now < MORNING_START -> "night"
        else -> "day"
    }

    // ---- Inner types ----

    /**
     * Outcome of a preload attempt.
     */
    data class PreloadReport(
        val attempted: Boolean,
        val source: String,
        val modelsAttempted: List<String>,
        val modelsPreloaded: List<String>,
        val memoryDelta: Long,
        val cacheStats: ModelCache.CacheStats,
    ) {
        /** Human-readable one-liner. */
        fun summary(): String = buildString {
            append("[$source] ")
            if (modelsAttempted.isEmpty()) {
                append("no models to preload")
            } else {
                append("attempted=${modelsAttempted.size} (${modelsAttempted.joinToString()})")
                append(", loaded=${modelsPreloaded.size} (${modelsPreloaded.joinToString()})")
                append(", memory=%+.0fMB".format(memoryDelta.toDouble() / (1024.0 * 1024.0)))
            }
            append(", cache=%d models / %.0fMB".format(
                cacheStats.loadedCount,
                cacheStats.currentMemoryBytes.toDouble() / (1024.0 * 1024.0),
            ))
        }
    }
}
