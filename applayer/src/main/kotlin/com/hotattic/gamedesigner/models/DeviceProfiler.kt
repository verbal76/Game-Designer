package com.hotattic.gamedesigner.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.hotattic.gamedesigner.core.models.DeviceProfile
import java.io.File

/** Reads what this phone can actually offer a local model: RAM, free storage, CPU and ABI. */
object DeviceProfiler {
    fun profile(context: Context, modelDir: File): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val free = try { StatFs(modelDir.apply { mkdirs() }.path).availableBytes } catch (e: Exception) { context.filesDir.usableSpace }
        return DeviceProfile(
            manufacturer = Build.MANUFACTURER.orEmpty(), model = Build.MODEL.orEmpty(), sdk = Build.VERSION.SDK_INT,
            totalRamMb = (mi.totalMem / (1024 * 1024)).toInt(), availRamMb = (mi.availMem / (1024 * 1024)).toInt(),
            freeStorageMb = free / (1024 * 1024), cpuCores = Runtime.getRuntime().availableProcessors(),
            abis = Build.SUPPORTED_ABIS.toList(), socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orEmpty() else "",
        )
    }
}
