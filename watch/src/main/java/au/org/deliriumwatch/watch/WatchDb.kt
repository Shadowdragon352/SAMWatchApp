package au.org.deliriumwatch.watch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class QueuedEvent(val id: Long, val payload: String)

class WatchDb(context: Context) : SQLiteOpenHelper(context, "watch_events.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_ms INTEGER NOT NULL,
                event_id TEXT NOT NULL UNIQUE,
                type TEXT NOT NULL,
                payload TEXT NOT NULL,
                sent INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_watch_unsent ON events(sent, id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun insert(tsMs: Long, eventId: String, type: String, payload: String) {
        val cv=ContentValues().apply { put("ts_ms",tsMs); put("event_id",eventId); put("type",type); put("payload",payload); put("sent",0) }
        writableDatabase.insertWithOnConflict("events",null,cv,SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun pendingForMessage(maxEvents: Int=100, maxBytes: Int=48_000): List<QueuedEvent> {
        val out=mutableListOf<QueuedEvent>(); var bytes=120
        readableDatabase.query("events", arrayOf("id","payload"), "sent=0", null,null,null,"id ASC",maxEvents.toString()).use { c ->
            while(c.moveToNext()) {
                val item=QueuedEvent(c.getLong(0),c.getString(1)); val next=item.payload.toByteArray(Charsets.UTF_8).size+2
                if(out.isNotEmpty() && bytes+next>maxBytes) break
                out+=item; bytes+=next
            }
        }
        return out
    }

    @Synchronized
    fun markSent(ids: List<Long>) {
        val db=writableDatabase; db.beginTransaction()
        try { db.compileStatement("UPDATE events SET sent=1 WHERE id=?").use { s -> ids.forEach { s.clearBindings(); s.bindLong(1,it); s.executeUpdateDelete() } }; db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    fun pendingCount(): Int { readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE sent=0",null).use { c -> return if(c.moveToFirst()) c.getInt(0) else 0 } }

    @Synchronized
    fun prune(retentionHours: Int) {
        val before=System.currentTimeMillis()-retentionHours.toLong()*3600*1000
        writableDatabase.delete("events","sent=1 AND ts_ms<?", arrayOf(before.toString()))
    }
}
