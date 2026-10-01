package au.org.deliriumwatch.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object WatchPermissions {
    fun granted(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun heartRate(context: Context) = granted(context, if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS)
    fun foreground(context: Context): List<String> = buildList {
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        add(if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= 36) add("android.permission.health.READ_SKIN_TEMPERATURE")
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        try { Class.forName("com.samsung.android.service.health.tracking.HealthTrackingService"); add("com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA") } catch (_: ClassNotFoundException) { }
    }.filterNot { granted(context, it) }
    fun canMonitor(context: Context) = granted(context, Manifest.permission.ACTIVITY_RECOGNITION) || heartRate(context)
    // Background body permission must be requested separately after foreground access.
    fun background(): String? = when {
        Build.VERSION.SDK_INT >= 36 -> "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        Build.VERSION.SDK_INT >= 33 -> "android.permission.BODY_SENSORS_BACKGROUND"
        else -> null
    }
}
