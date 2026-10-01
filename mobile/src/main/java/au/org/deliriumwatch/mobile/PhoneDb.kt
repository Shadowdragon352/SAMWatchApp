package au.org.deliriumwatch.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class PhoneDb(context: Context) : SQLiteOpenHelper(context, "gateway.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE events(
                event_id TEXT PRIMARY KEY,
                study_id TEXT NOT NULL,
                ts_ms INTEGER NOT NULL,
                payload TEXT NOT NULL,
                uploaded INTEGER NOT NULL DEFAULT 0,
                received_ms INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_events_uploaded ON events(uploaded, ts_ms)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun insertBatch(batch: JSONObject, configuredStudyId: String): Int {
        val events = batch.optJSONArray("events") ?: JSONArray()
        var inserted = 0
        writableDatabase.beginTransaction()
        try {
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                val eventId = e.optString("event_id")
                if (eventId.isBlank()) continue
                val cv = ContentValues().apply {
                    put("event_id", eventId)
                    put("study_id", e.optString("study_id", configuredStudyId))
                    put("ts_ms", e.optLong("ts_ms"))
                    put("payload", e.toString())
                    put("uploaded", 0)
                    put("received_ms", System.currentTimeMillis())
                }
                val result = writableDatabase.insertWithOnConflict("events", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                if (result != -1L) inserted++
                else readableDatabase.rawQuery("SELECT 1 FROM events WHERE event_id=?", arrayOf(eventId)).use { c ->
                    check(c.moveToFirst()) { "Record could not be saved" }
                }
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return inserted
    }

    @Synchronized
    fun pending(limit: Int = 100): List<Pair<String, JSONObject>> {
        val out = mutableListOf<Pair<String, JSONObject>>()
        readableDatabase.query("events", arrayOf("event_id","payload"), "uploaded=0", null, null, null, "ts_ms ASC", limit.toString()).use { c ->
            while (c.moveToNext()) out += c.getString(0) to JSONObject(c.getString(1))
        }
        return out
    }

    @Synchronized
    fun markUploaded(ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.compileStatement("UPDATE events SET uploaded=1 WHERE event_id=?").use { stmt ->
                ids.forEach { stmt.clearBindings(); stmt.bindString(1, it); stmt.executeUpdateDelete() }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun pendingCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE uploaded=0", null).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    @Synchronized
    fun pruneUploaded(days: Int = 14) {
        val before = System.currentTimeMillis() - days.toLong()*24*3600*1000
        writableDatabase.delete("events", "uploaded=1 AND received_ms<?", arrayOf(before.toString()))
    }
}
