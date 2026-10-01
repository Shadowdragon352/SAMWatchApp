package au.org.deliriumwatch.mobile

import android.content.Context
import org.json.JSONObject

class PhonePrefs(context: Context) {
    private val p = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("serverUrl", "") ?: ""
        set(v) = p.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()
    var studyId: String
        get() = p.getString("studyId", "UNASSIGNED") ?: "UNASSIGNED"
        set(v) = p.edit().putString("studyId", v.trim().ifBlank { "UNASSIGNED" }).apply()
    var rawAccel: Boolean
        get() = p.getBoolean("rawAccel", false)
        set(v) = p.edit().putBoolean("rawAccel", v).apply()
    var ppgEnabled: Boolean
        get() = p.getBoolean("ppgEnabled", false)
        set(v) = p.edit().putBoolean("ppgEnabled", v).apply()
    var ppgAllWavelengths: Boolean
        get() = p.getBoolean("ppgAllWavelengths", false)
        set(v) = p.edit().putBoolean("ppgAllWavelengths", v).apply()
    var rawPpg: Boolean
        get() = p.getBoolean("rawPpg", false)
        set(v) = p.edit().putBoolean("rawPpg", v).apply()
    var retentionHours: Int
        get() = p.getInt("retentionHours", 72)
        set(v) = p.edit().putInt("retentionHours", v.coerceIn(12, 336)).apply()
    var activeThreshold: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("activeThreshold", java.lang.Double.doubleToRawLongBits(0.12)))
        set(v) = p.edit().putLong("activeThreshold", java.lang.Double.doubleToRawLongBits(v.coerceIn(0.01, 5.0))).apply()
    var promptTimes: String
        get() = p.getString("promptTimes", "09:00,14:00,19:00") ?: "09:00,14:00,19:00"
        set(v) = p.edit().putString("promptTimes", v).apply()
    var lastUploadMessage: String
        get() = p.getString("lastUpload", "Never") ?: "Never"
        set(v) = p.edit().putString("lastUpload", v).apply()

    fun watchConfigJson(): JSONObject = JSONObject().apply {
        put("study_id", studyId)
        put("raw_accel", rawAccel)
        put("ppg_enabled", ppgEnabled)
        put("ppg_all_wavelengths", ppgAllWavelengths)
        put("raw_ppg", rawPpg)
        put("retention_hours", retentionHours)
        put("active_threshold_ms2", activeThreshold)
        put("prompt_times", promptTimes)
        put("send_interval_sec", 15)
        put("movement_window_sec", 5)
    }
}
