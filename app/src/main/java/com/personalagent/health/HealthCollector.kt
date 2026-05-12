package com.personalagent.health

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * Health snapshot collected before every heartbeat / task poll.
 *
 * Mirrors the HealthSnapshot proto message from the control plane spec.
 */
data class HealthSnapshot(
    val batteryPercent: Int,
    val charging: Boolean,
    val thermalState: String,     // THERMAL_NORMAL | THERMAL_WARM | THERMAL_HOT | THERMAL_CRITICAL
    val cpuTempC: Double,
    val ramFreeMb: Int,
    val storageFreeMb: Int,
    val networkReachable: Boolean,
    val powerSaveMode: Boolean,
)

object HealthCollector {

    /**
     * Collects a fresh health snapshot using Android system services.
     */
    fun snapshot(context: Context): HealthSnapshot {
        return HealthSnapshot(
            batteryPercent = getBatteryPercent(context),
            charging = isCharging(context),
            thermalState = getThermalState(context),
            cpuTempC = 0.0, // Not available via public Android API without root
            ramFreeMb = getRamFreeMb(context),
            storageFreeMb = getStorageFreeMb(context),
            networkReachable = isNetworkReachable(context),
            powerSaveMode = isPowerSaveMode(context),
        ).also { s ->
            Timber.d(
                "Health: batt=%d%%, charging=%b, thermal=%s, ram=%dMB, net=%b",
                s.batteryPercent, s.charging, s.thermalState, s.ramFreeMb, s.networkReachable,
            )
        }
    }

    // ---- Battery ----

    private fun getBatteryPercent(context: Context): Int {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (scale > 0) (level * 100) / scale else -1
    }

    private fun isCharging(context: Context): Boolean {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    // ---- Thermal ----

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getThermalState(context: Context): String {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            when (pm?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "THERMAL_NORMAL"
                PowerManager.THERMAL_STATUS_LIGHT -> "THERMAL_WARM"
                PowerManager.THERMAL_STATUS_MODERATE -> "THERMAL_WARM"
                PowerManager.THERMAL_STATUS_SEVERE -> "THERMAL_HOT"
                PowerManager.THERMAL_STATUS_CRITICAL -> "THERMAL_CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "THERMAL_CRITICAL"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "THERMAL_CRITICAL"
                else -> "THERMAL_NORMAL"
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read thermal state")
            "THERMAL_NORMAL"
        }
    }

    // ---- RAM ----

    private fun getRamFreeMb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.availMem / (1024 * 1024)).toInt()
    }

    // ---- Storage ----

    private fun getStorageFreeMb(context: Context): Int {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            (freeBytes / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            Timber.w(e, "Failed to read storage free")
            0
        }
    }

    // ---- Network ----

    private fun isNetworkReachable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---- Power Save ----

    private fun isPowerSaveMode(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isPowerSaveMode ?: false
    }
}
