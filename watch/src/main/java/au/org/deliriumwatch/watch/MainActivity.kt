package au.org.deliriumwatch.watch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.gms.wearable.Wearable

class MainActivity : Activity() {
    private lateinit var prefs: WatchPrefs
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var startPending = false
    private val refresh = object : Runnable {
        override fun run() { updateStatus(); handler.postDelayed(this, 2000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = WatchPrefs(this)
        setContentView(buildUi())
        CognitionScheduler.scheduleAll(this, prefs.promptTimes)
        requestPhoneConfig()
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun buildUi(): View {
        val root = LinearLayout(this)
        val scroll = WatchUi.scroll(this, root)
        fun label(value: String, size: Float, color: Int) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        }
        root.addView(label("DeliriumWatch", 18f, Color.WHITE))
        root.addView(label("Research only", 12f, Color.LTGRAY))
        fun button(value: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = value; textSize = 13f; isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        button("Start monitoring") { requestStart() }
        button("Stop monitoring") {
            startPending = false
            stopService(Intent(this, MonitoringService::class.java))
            prefs.monitoring = false
            updateStatus()
        }
        button("Cognitive check") { startActivity(Intent(this, CognitiveActivity::class.java)) }
        button("Send queued data") {
            WatchTransport.flush(this) { ok, count ->
                runOnUiThread { toast(if (ok) "Phone saved $count events" else "No receipt yet; data stays queued"); updateStatus() }
            }
        }
        button("Sensor permissions") {
            val missing = WatchPermissions.foreground(this)
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_SENSORS)
            else {
                val bg = WatchPermissions.background()
                if (bg != null && WatchPermissions.heartRate(this) && !WatchPermissions.granted(this, bg)) {
                    requestPermissions(arrayOf(bg), REQUEST_BACKGROUND)
                } else toast("Sensor permissions granted")
            }
        }
        status = label("", 12f, Color.WHITE).apply {
            setPadding(0, WatchUi.dp(this@MainActivity, 8f), 0, 0)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        return scroll
    }

    private fun requestStart() {
        if (startPending) return
        val missing = WatchPermissions.foreground(this)
        if (missing.isEmpty()) startMonitoring()
        else { startPending = true; requestPermissions(missing.toTypedArray(), REQUEST_START) }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == REQUEST_START) {
            val requested = startPending
            startPending = false
            if (requested) startMonitoring()
        } else if (code == REQUEST_SENSORS) toast("Tap Sensor permissions again to enable background sensor access")
    }
    private fun startMonitoring() {
        if (!WatchPermissions.canMonitor(this)) {
            toast("Allow physical activity or body sensors in app permissions to start")
            return
        }
        try {
            startForegroundService(Intent(this, MonitoringService::class.java))
        } catch (e: Exception) {
            prefs.monitoring = false
            prefs.lastSensorStatus = "Cannot start: ${e.message}"
            toast(prefs.lastSensorStatus)
        }
        updateStatus()
    }
    private fun requestPhoneConfig() {
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(this).sendMessage(node.id, "/delirium-watch/config/request", byteArrayOf())
                    .addOnFailureListener { toast("Phone configuration unavailable") }
            }
        }.addOnFailureListener { toast("Phone connection unavailable; local recording still works") }
    }
    private fun updateStatus() {
        val count = WatchDb(this).use { it.pendingCount() }
        val move = if (prefs.lastMovement.isFinite()) "%.3f".format(prefs.lastMovement) else "—"
        val temp = if (prefs.lastTemp.isFinite()) "%.2f".format(prefs.lastTemp) else "—"
        status.text = "Study: ${prefs.studyId}\nMonitoring: ${if (prefs.monitoring) "ON" else "OFF"}\nHR: ${if (prefs.lastHeartRate > 0) prefs.lastHeartRate.toString() else "—"}\nMovement RMS: $move\nSkin temp: $temp\nQueued: $count\n${prefs.lastSensorStatus}"
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    companion object { const val REQUEST_START = 77; const val REQUEST_SENSORS = 78; const val REQUEST_BACKGROUND = 79 }
}
