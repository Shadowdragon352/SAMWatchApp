package au.org.deliriumwatch.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = WatchPrefs(context)
        CognitionScheduler.scheduleAll(context, prefs.promptTimes)
        // A while-in-use health permission is not valid from a boot receiver.
        if (prefs.monitoring) {
            prefs.monitoring = false
            prefs.lastSensorStatus = "Watch restarted. Open the app and tap Start monitoring."
        }
    }
}
