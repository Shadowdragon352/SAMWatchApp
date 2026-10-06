package au.org.deliriumwatch.watch

import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object EventFactory {
    fun deviceId(context: Context): String {
        val secure = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!secure.isNullOrBlank()) return secure
        val preferences = context.getSharedPreferences("watch_identity", Context.MODE_PRIVATE)
        synchronized(this) {
            val existing = preferences.getString("fallback_device_id", null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            check(preferences.edit().putString("fallback_device_id", created).commit()) { "Could not save watch identity" }
            return created
        }
    }
    fun create(context: Context, type: String, tsMs: Long, payload: JSONObject, source: String="watch"): JSONObject {
        val device = deviceId(context)
        return JSONObject().apply {
            put("event_id", UUID.randomUUID().toString())
            put("study_id", WatchPrefs(context).studyId)
            put("device_id", device)
            put("ts_ms", tsMs)
            put("type", type)
            put("source", source)
            put("payload", payload)
        }
    }

    fun numbers(values: Iterable<Number>): JSONArray = JSONArray().apply { values.forEach { put(it) } }
    fun nullableNumbers(values: Iterable<Number?>): JSONArray = JSONArray().apply { values.forEach { if(it==null) put(JSONObject.NULL) else put(it) } }
}
