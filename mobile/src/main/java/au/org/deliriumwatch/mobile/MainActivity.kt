package au.org.deliriumwatch.mobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
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
        UploadJobService.schedule(this)
        setContentView(buildUi())
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
        return scroll
    }

    private fun saveAndSend() {
        prefs.serverUrl = server.text.toString()
        prefs.token = token.text.toString()
        prefs.studyId = study.text.toString()
        prefs.rawAccel = rawAccel.isChecked
        prefs.ppgEnabled = ppgEnabled.isChecked
        prefs.ppgAllWavelengths = ppgAll.isChecked
        prefs.rawPpg = rawPpg.isChecked && ppgEnabled.isChecked
        prefs.activeThreshold = threshold.text.toString().toDoubleOrNull() ?: 0.12
        prefs.promptTimes = promptTimes.text.toString().ifBlank { "09:00,14:00,19:00" }
        val bytes = prefs.watchConfigJson().toString().toByteArray(Charsets.UTF_8)
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) { toast("No connected watch found"); refreshStatus(); return@addOnSuccessListener }
            nodes.forEach { Wearable.getMessageClient(this).sendMessage(it.id, WearReceiverService.PATH_CONFIG, bytes) }
            toast("Configuration sent to ${nodes.size} watch node(s)")
            refreshStatus()
        }.addOnFailureListener { toast("Could not query watch: ${it.message}") }
    }

    private fun syncNow() {
        status.text = "Uploading…"
        executor.execute {
            val result = Uploader.drain(this, 20)
            handler.post { toast(result.message); refreshStatus() }
        }
    }

    private fun refreshStatus() {
        val pending = PhoneDb(this).pendingCount()
        status.text = "Queued events: $pending\nLast successful upload: ${prefs.lastUploadMessage}\nServer: ${prefs.serverUrl.ifBlank { "not configured" }}"
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            status.append("\nConnected watch nodes: ${nodes.size}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
