package au.org.deliriumwatch.watch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime

object CognitionScheduler {
    fun scheduleAll(context: Context, times: String) {
        val alarm=context.getSystemService(AlarmManager::class.java)
        for(i in 0 until 8) alarm.cancel(pending(context,i))
        val parsed=times.split(',').mapNotNull { token ->
            val p=token.trim().split(':'); if(p.size!=2)null else {
                val h=p[0].toIntOrNull(); val m=p[1].toIntOrNull(); if(h==null||m==null||h !in 0..23||m !in 0..59)null else h to m
            }
        }.take(8)
        parsed.forEachIndexed { idx,(h,m) ->
            var z=ZonedDateTime.now().withHour(h).withMinute(m).withSecond(0).withNano(0)
            if(!z.isAfter(ZonedDateTime.now())) z=z.plusDays(1)
            alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP,z.toInstant().toEpochMilli(),AlarmManager.INTERVAL_DAY,pending(context,idx))
        }
    }
    private fun pending(context:Context,id:Int)=PendingIntent.getBroadcast(context,9000+id,Intent(context,CognitionPromptReceiver::class.java).putExtra("prompt_id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
