package com.personalagent.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Runtime configuration for the worker agent, backed by [SharedPreferences].
 *
 * Controls polling frequency, battery/thermal thresholds, retry policy,
 * model pool limits, and resource constraints. Loaded at startup by
 * [com.personalagent.lifecycle.ServiceLifecycle] and used by
 * [com.personalagent.agent.TaskPollingLoop] and model components.
 *
 * @property taskPollIntervalSec Seconds between task queue polls.
 * @property minBatteryPercent Minimum battery percentage to allow task execution
 *           (unless charging).
 * @property minRamMb Minimum free RAM (MB) required to execute a task.
 * @property thermalLimit Maximum thermal state allowed for task execution
 *           (e.g., "THERMAL_WARM" means block at "THERMAL_HOT" and above).
 * @property maxRetries Maximum retry attempts for a FAILED task before terminal failure.
 * @property retryBackoffBaseMs Base delay in ms for exponential backoff
 *           (5s → 10s → 20s → 40s).
 * @property maxModelRamMb Maximum RAM (MB) allocated for loaded AI models (4 GB).
 * @property modelPreloadEnabled Whether to preload resident models at startup.
 * @property modelHealthGateEnabled Whether to enforce health gates for large models (>2GB).
 * @property preloadIntervalMin Minutes between periodic time-of-day preload checks.
 */
data class WorkerConfig(
    val taskPollIntervalSec: Int = 5,
    val minBatteryPercent: Int = 20,
    val minRamMb: Int = 200,
    val thermalLimit: String = "THERMAL_WARM",
    val maxRetries: Int = 3,
    val retryBackoffBaseMs: Long = 5_000L,
    val maxModelRamMb: Int = 4096,
    val modelPreloadEnabled: Boolean = true,
    val modelHealthGateEnabled: Boolean = true,
    val aiInferenceTimeoutSec: Int = 300,
    val preloadIntervalMin: Int = 30,
) {
    companion object {
        private const val PREFS_NAME = "personal_agent_config"
        private const val KEY_POLL_INTERVAL_SEC = "poll_interval_sec"
        private const val KEY_MIN_BATTERY_PCT = "min_battery_pct"
        private const val KEY_MIN_RAM_MB = "min_ram_mb"
        private const val KEY_THERMAL_LIMIT = "thermal_limit"
        private const val KEY_MAX_RETRIES = "max_retries"
        private const val KEY_RETRY_BACKOFF_MS = "retry_backoff_ms"
        private const val KEY_MAX_MODEL_RAM_MB = "max_model_ram_mb"
        private const val KEY_MODEL_PRELOAD_ENABLED = "model_preload_enabled"
        private const val KEY_MODEL_HEALTH_GATE_ENABLED = "model_health_gate_enabled"
        private const val KEY_AI_INFERENCE_TIMEOUT_SEC = "ai_inference_timeout_sec"
        private const val KEY_PRELOAD_INTERVAL_MIN = "preload_interval_min"

        fun load(context: Context): WorkerConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return WorkerConfig(
                taskPollIntervalSec = prefs.getInt(KEY_POLL_INTERVAL_SEC, 5),
                minBatteryPercent = prefs.getInt(KEY_MIN_BATTERY_PCT, 20),
                minRamMb = prefs.getInt(KEY_MIN_RAM_MB, 200),
                thermalLimit = prefs.getString(KEY_THERMAL_LIMIT, "THERMAL_WARM")!!,
                maxRetries = prefs.getInt(KEY_MAX_RETRIES, 3),
                retryBackoffBaseMs = prefs.getLong(KEY_RETRY_BACKOFF_MS, 5_000L),
                maxModelRamMb = prefs.getInt(KEY_MAX_MODEL_RAM_MB, 4096),
                modelPreloadEnabled = prefs.getBoolean(KEY_MODEL_PRELOAD_ENABLED, true),
                modelHealthGateEnabled = prefs.getBoolean(KEY_MODEL_HEALTH_GATE_ENABLED, true),
                aiInferenceTimeoutSec = prefs.getInt(KEY_AI_INFERENCE_TIMEOUT_SEC, 300),
                preloadIntervalMin = prefs.getInt(KEY_PRELOAD_INTERVAL_MIN, 30),
            )
        }

        fun save(context: Context, config: WorkerConfig) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putInt(KEY_POLL_INTERVAL_SEC, config.taskPollIntervalSec)
                putInt(KEY_MIN_BATTERY_PCT, config.minBatteryPercent)
                putInt(KEY_MIN_RAM_MB, config.minRamMb)
                putString(KEY_THERMAL_LIMIT, config.thermalLimit)
                putInt(KEY_MAX_RETRIES, config.maxRetries)
                putLong(KEY_RETRY_BACKOFF_MS, config.retryBackoffBaseMs)
                putInt(KEY_MAX_MODEL_RAM_MB, config.maxModelRamMb)
                putBoolean(KEY_MODEL_PRELOAD_ENABLED, config.modelPreloadEnabled)
                putBoolean(KEY_MODEL_HEALTH_GATE_ENABLED, config.modelHealthGateEnabled)
                putInt(KEY_AI_INFERENCE_TIMEOUT_SEC, config.aiInferenceTimeoutSec)
                putInt(KEY_PRELOAD_INTERVAL_MIN, config.preloadIntervalMin)
                apply()
            }
        }
    }
}
