package com.personalagent.identity

import android.os.Build
import com.personalagent.BuildConfig
import timber.log.Timber

/**
 * Collects static device identity information at startup.
 *
 * Called once during ServiceLifecycle.start().
 */
data class DeviceInfo(
    val deviceName: String,
    val deviceModel: String,
    val androidVersion: String,
    val osBuild: String,
    val serialHint: String,
    val agentVersion: String,
    val arch: String,
    val cpuCores: Int,
)

object DeviceInfoCollector {

    fun collect(): DeviceInfo {
        return DeviceInfo(
            deviceName = Build.MODEL,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            osBuild = Build.DISPLAY,
            serialHint = getSerialHint(),
            agentVersion = BuildConfig.VERSION_NAME,
            arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            cpuCores = Runtime.getRuntime().availableProcessors(),
        ).also { info ->
            Timber.i(
                "DeviceInfo: model=%s, android=%s, arch=%s, cores=%d",
                info.deviceModel, info.androidVersion, info.arch, info.cpuCores,
            )
        }
    }

    private fun getSerialHint(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        } catch (e: SecurityException) {
            "unknown"
        }
    }
}
