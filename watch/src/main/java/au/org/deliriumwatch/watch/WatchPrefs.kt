package au.org.deliriumwatch.watch

import android.content.Context
import org.json.JSONObject

class WatchPrefs(context: Context) {
    private val p = context.getSharedPreferences("watch_config", Context.MODE_PRIVATE)
    var studyId: String
        get() = p.getString("study_id", "UNASSIGNED") ?: "UNASSIGNED"
        set(v) = p.edit().putString("study_id", v.ifBlank { "UNASSIGNED" }).apply()
    var rawAccel: Boolean
        get() = p.getBoolean("raw_accel", false)
        set(v) = p.edit().putBoolean("raw_accel", v).apply()
    var ppgEnabled: Boolean
        get() = p.getBoolean("ppg_enabled", false)
        set(v) = p.edit().putBoolean("ppg_enabled", v).apply()
    var ppgAllWavelengths: Boolean
        get() = p.getBoolean("ppg_all_wavelengths", false)
        set(v) = p.edit().putBoolean("ppg_all_wavelengths", v).apply()
    var rawPpg: Boolean
        get() = p.getBoolean("raw_ppg", false)
        set(v) = p.edit().putBoolean("raw_ppg", v).apply()
    var retentionHours: Int
        get() = p.getInt("retention_hours", 72)
        set(v) = p.edit().putInt("retention_hours", v.coerceIn(12,336)).apply()
    var activeThreshold: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("active_threshold", java.lang.Double.doubleToRawLongBits(0.12)))
        set(v) = p.edit().putLong("active_threshold", java.lang.Double.doubleToRawLongBits(if (v.isFinite()) v.coerceIn(0.01,5.0) else 0.12)).apply()
    var promptTimes: String
        get() = p.getString("prompt_times", "09:00,14:00,19:00") ?: "09:00,14:00,19:00"
        set(v) = p.edit().putString("prompt_times", v).apply()
    var sendIntervalSec: Int
        get() = p.getInt("send_interval_sec", 15)
        set(v) = p.edit().putInt("send_interval_sec", v.coerceIn(5,300)).apply()
    var monitoring: Boolean
        get() = p.getBoolean("monitoring", false)
        set(v) = p.edit().putBoolean("monitoring", v).apply()
    var lastSensorStatus: String
        get() = p.getString("sensor_status", "Not started") ?: "Not started"
        set(v) = p.edit().putString("sensor_status", v).apply()
    var lastHeartRate: Int
        get() = p.getInt("last_hr", 0)
        set(v) = p.edit().putInt("last_hr", v).apply()
    var lastMovement: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("last_move", java.lang.Double.doubleToRawLongBits(Double.NaN)))
        set(v) = p.edit().putLong("last_move", java.lang.Double.doubleToRawLongBits(v)).apply()
    var lastTemp: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("last_temp", java.lang.Double.doubleToRawLongBits(Double.NaN)))
        set(v) = p.edit().putLong("last_temp", java.lang.Double.doubleToRawLongBits(v)).apply()

    fun applyConfig(obj: JSONObject) {
        studyId = obj.optString("study_id", studyId)
        rawAccel = obj.optBoolean("raw_accel", rawAccel)
        ppgEnabled = obj.optBoolean("ppg_enabled", ppgEnabled)
        ppgAllWavelengths = obj.optBoolean("ppg_all_wavelengths", ppgAllWavelengths)
        rawPpg = obj.optBoolean("raw_ppg", rawPpg) && ppgEnabled
        retentionHours = obj.optInt("retention_hours", retentionHours)
        activeThreshold = obj.optDouble("active_threshold_ms2", activeThreshold)
        promptTimes = obj.optString("prompt_times", promptTimes)
        sendIntervalSec = obj.optInt("send_interval_sec", sendIntervalSec)
    }
}
