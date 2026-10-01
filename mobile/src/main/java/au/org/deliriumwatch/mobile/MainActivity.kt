package au.org.deliriumwatch.mobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.*
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var prefs: PhonePrefs
    private lateinit var status: TextView
    private lateinit var server: EditText
    private lateinit var token: EditText
    private lateinit var study: EditText
    private lateinit var rawAccel: CheckBox
    private lateinit var ppgEnabled: CheckBox
    private lateinit var ppgAll: CheckBox
    private lateinit var rawPpg: CheckBox
    private lateinit var threshold: EditText
    private lateinit var promptTimes: EditText
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PhonePrefs(this)
        setContentView(buildUi())
        try { UploadJobService.schedule(this) } catch (e: Exception) { toast("Background upload unavailable: ${e.message}") }
        refreshStatus()
    }

    private fun field(label: String, value: String, password: Boolean = false): Pair<LinearLayout, EditText> {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,8,0,8) }
        box.addView(TextView(this).apply { text = label; textSize = 13f })
        val edit = EditText(this).apply { setText(value); textSize = 16f; if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return box to edit
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,28,28,40) }
        root.addView(TextView(this).apply { text="DeliriumWatch Gateway"; textSize=25f; setTextColor(Color.rgb(25,70,110)) })
        root.addView(TextView(this).apply { text="Research only. This app relays pseudonymous watch data to your laptop; it does not diagnose delirium or sepsis."; textSize=14f; setPadding(0,8,0,18) })

        var pair = field("Laptop server URL (Tailscale hostname/IP is supported)", prefs.serverUrl); root.addView(pair.first); server=pair.second
        pair = field("API token (optional but recommended)", prefs.token, true); root.addView(pair.first); token=pair.second
        pair = field("Pseudonymous study ID", prefs.studyId); root.addView(pair.first); study=pair.second
        pair = field("Movement active threshold, m/s² (engineering threshold; not clinical)", prefs.activeThreshold.toString()); root.addView(pair.first); threshold=pair.second
        pair = field("Cognition prompt times, 24 h comma-separated", prefs.promptTimes); root.addView(pair.first); promptTimes=pair.second
        rawAccel = CheckBox(this).apply { text="Include raw accelerometer samples in research events"; isChecked=prefs.rawAccel }
        ppgEnabled = CheckBox(this).apply { text="Enable continuous PPG summary (Samsung SDK; increases battery use)"; isChecked=prefs.ppgEnabled }
        ppgAll = CheckBox(this).apply { text="PPG: collect red + infrared as well as green (higher battery use)"; isChecked=prefs.ppgAllWavelengths }
        rawPpg = CheckBox(this).apply { text="Include raw PPG samples in research events (higher data use)"; isChecked=prefs.rawPpg }
        root.addView(rawAccel); root.addView(ppgEnabled); root.addView(ppgAll); root.addView(rawPpg)

        val save = Button(this).apply { text="Save + send to watch"; setOnClickListener { saveAndSend() } }
        val sync = Button(this).apply { text="Sync queued data now"; setOnClickListener { syncNow() } }
        root.addView(save); root.addView(sync)
        status = TextView(this).apply { textSize=14f; setPadding(0,18,0,0) }
        root.addView(status)
        scroll.addView(root)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        return scroll
    }

    private fun saveAndSend() {
        val url = server.text.toString().trim()
        if (url.isNotBlank() && !validServerUrl(url)) { toast("Use an http:// or https:// server URL"); return }
        val value = threshold.text.toString().toDoubleOrNull()
        if (value == null || !value.isFinite() || value !in 0.01..5.0) { toast("Movement threshold must be 0.01–5.0"); return }
        val times = promptTimes.text.toString().trim()
        if (!validPromptTimes(times)) { toast("Use up to 8 times such as 09:00,14:00,19:00"); return }
        if (study.text.toString().trim().length !in 1..100) { toast("Study ID must contain 1–100 characters"); return }
        prefs.serverUrl = url
        prefs.token = token.text.toString()
        prefs.studyId = study.text.toString()
        prefs.rawAccel = rawAccel.isChecked
        prefs.ppgEnabled = ppgEnabled.isChecked
        prefs.ppgAllWavelengths = ppgAll.isChecked
        prefs.rawPpg = rawPpg.isChecked && ppgEnabled.isChecked
        prefs.activeThreshold = value
        prefs.promptTimes = times
        val bytes = prefs.watchConfigJson().toString().toByteArray(Charsets.UTF_8)
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) { toast("No connected watch found"); refreshStatus(); return@addOnSuccessListener }
            nodes.forEach { node ->
                Wearable.getMessageClient(this).sendMessage(node.id, WearReceiverService.PATH_CONFIG, bytes)
                    .addOnSuccessListener { toast("Configuration delivered to watch") }
                    .addOnFailureListener { toast("Configuration failed: ${it.message}") }
            }
            refreshStatus()
        }.addOnFailureListener { toast("Could not query watch: ${it.message}") }
    }

    private fun syncNow() {
        status.text = "Uploading…"
        executor.execute {
            val result = Uploader.drain(this, 20)
            handler.post { if(!isDestroyed){toast(result.message); refreshStatus()} }
        }
    }

    private fun refreshStatus() {
        val pending = PhoneDb(this).use { it.pendingCount() }
        status.text = "Queued events: $pending\nLast successful upload: ${prefs.lastUploadMessage}\nServer: ${prefs.serverUrl.ifBlank { "not configured" }}"
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            status.append("\nConnected watch nodes: ${nodes.size}")
        }.addOnFailureListener { status.append("\nWatch connection unavailable: ${it.message}") }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        super.onDestroy()
    }

    private fun validServerUrl(value: String): Boolean = try {
        val uri = java.net.URI(value)
        uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
    } catch (_: Exception) { false }

    private fun validPromptTimes(value: String): Boolean {
        val times = value.split(',')
        return times.size in 1..8 && times.all { it.trim().matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
