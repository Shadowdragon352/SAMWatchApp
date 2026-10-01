package au.org.deliriumwatch.watch

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CognitionPromptReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val promptId=intent.getIntExtra("prompt_id",0)
        val shown=EventFactory.create(context,"cognition_prompt_outcome",System.currentTimeMillis(),org.json.JSONObject().put("outcome","presented").put("prompt_id",promptId))
        WatchDb(context).insert(shown.getLong("ts_ms"),shown.getString("event_id"),"cognition_prompt_outcome",shown.toString())
        val nm=context.getSystemService(NotificationManager::class.java)
        if(android.os.Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(NotificationChannel(CHANNEL,"Cognitive check prompts",NotificationManager.IMPORTANCE_HIGH))
        val open=PendingIntent.getActivity(context,100+promptId,Intent(context,CognitiveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("prompt_id",promptId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_help).setContentTitle("Quick check").setContentText("A short attention game is ready.").setContentIntent(open).setAutoCancel(true).build()
        nm.notify(4300+promptId,n)
    }
    companion object{const val CHANNEL="cognition_prompts"}
}
