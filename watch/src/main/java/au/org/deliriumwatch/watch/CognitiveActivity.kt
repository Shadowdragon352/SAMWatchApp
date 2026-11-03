package au.org.deliriumwatch.watch

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import kotlin.math.sqrt

class CognitiveActivity : Activity(){
    private val handler=Handler(Looper.getMainLooper())

    private lateinit var db:WatchDb


    private lateinit var root:LinearLayout

    private lateinit var title:TextView

    private lateinit var stimulus:TextView

    private lateinit var controls:LinearLayout


    private lateinit var sessionStudyId:String

    private var stage="intro"

    private var trialStart=0L

    private var answered=false

    private val trialGate=TrialGate()

    private var trialToken=0

    private var timeout:Runnable?=null


    private val rng=java.util.Random()

    private val results=mutableListOf<Trial>()

    private val sessionId=UUID.randomUUID().toString()

    private var promptId=-1


    private var attentionSeq=listOf<Boolean>()

    private var idx=0

    private var oneBackSeq=listOf<String>()


    data class Trial(
        val task:String,
        val index:Int,
        val expected:String,
        val response:String?,
        val correct:Boolean,
        val rtMs:Long?,
        val tsMs:Long
    )

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        sessionStudyId=WatchPrefs(this).studyId
        db=WatchDb(this)
        promptId=intent.getIntExtra("prompt_id",-1)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        store(
            "cognition_prompt_outcome",
            System.currentTimeMillis(),
            JSONObject()
                .put("outcome","started")
                .put("session_id",sessionId)
                .put("prompt_id",promptId)
        )

        showIntro()
    }


    override fun onPause() {
        super.onPause()

        // treat leaving an active/practice task as an interruption rather
        // than incorrectly interpreting it as poor cognitive performance.
        if (
            stage in listOf(
                "attention_practice",
                "attention",
                "one_back_intro",
                "one_back_practice",
                "one_back"
            )
        ) {
            trialGate.cancel()
            handler.removeCallbacksAndMessages(null)
            stage = "interrupted"

            title.text = "Check stopped"
            stimulus.textSize = 16f
            stimulus.text =
                "This check was not completed.\n" +
                        "You can try again later."

            setButtons(
                button("Close") {
                    finish()
                }
            )

            store(
                "cognition_prompt_outcome",
                System.currentTimeMillis(),
                JSONObject()
                    .put("outcome", "interrupted")
                    .put("session_id", sessionId)
                    .put("prompt_id", promptId)
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        db.close()
        super.onDestroy()
    }

    private fun buildUi(): View {
        root = LinearLayout(this)
        val scroll = WatchUi.scroll(this, root)
        val diameter = resources.displayMetrics.widthPixels
        val inset = (diameter * 0.15f).toInt()

        root.setPadding(inset, inset, inset, inset)
        root.gravity = Gravity.CENTER

        title = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        stimulus = TextView(this).apply {
            textSize = 36f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(stimulus, LinearLayout.LayoutParams(-1, -2))
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))

        return scroll
    }

    private fun button(
        text: String,
        onClick: () -> Unit
    ) = Button(this).apply {
        this.text = text
        textSize = 15f
        isAllCaps = false
        minimumWidth = 0
        minimumHeight = WatchUi.dp(this@CognitiveActivity, 48f)
        setPadding(4, 0, 4, 0)
        setOnClickListener { onClick() }
    }

    private fun setButtons(vararg buttons: Button) {
        controls.removeAllViews()

        buttons.forEach { button ->
            controls.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    WatchUi.dp(this, 48f),
                    1f
                )
            )
        }
    }


    private fun beginTrial(
        timeoutMs: Long,
        action: () -> Unit
    ) {
        timeout?.let { handler.removeCallbacks(it) }

        trialToken = trialGate.begin()
        val token = trialToken
        trialStart = SystemClock.elapsedRealtime()

        timeout = Runnable {
            if (trialGate.finish(token)) {
                action()
            }
        }.also {
            handler.postDelayed(it, timeoutMs)
        }
    }

    private fun acceptAnswer(): Boolean {
        if (!trialGate.finish(trialToken)) return false
        timeout?.let { handler.removeCallbacks(it) }
        return true
    }

    private fun showIntro() {
        stage = "intro"
        title.text = "Quick attention check"
        stimulus.textSize = 17f
        stimulus.text =
            "You will see different shapes.\n\n" +
                    "Tap only when you see the star."

        setButtons(
            button("Practice") {
                showAttentionPractice()
            },
            button("Not now") {
                recordDeclined()
                finish()
            }
        )
    }


    private fun showAttentionPractice() {
        stage = "attention_practice"
        title.text = "Practice — tap the star"
        stimulus.textSize = 36f
        stimulus.text = "★"

        setButtons(
            button("TAP") {
                stage = "attention_practice_complete"
                title.text = "That's it"
                stimulus.text = "✓"

                setButtons(
                    button("Start") {
                        startAttention()
                    }
                )
            }
        )
    }



    private fun startAttention() {
        stage = "attention"
        results.clear()
        idx = 0

        val sequence = MutableList(24) { false }
        listOf(1, 4, 7, 10, 13, 16, 19, 22).forEach {
            sequence[it] = true
        }

        Collections.shuffle(sequence, rng)
        attentionSeq = sequence

        title.text = "Get ready"
        stimulus.textSize = 18f
        stimulus.text = "Tap only for ★"
        controls.removeAllViews()

        handler.postDelayed(
            { nextAttention() },
            1200
        )
    }


    // CHANGED: show progress so the patient knows how much is left.
    private fun nextAttention() {
        if (idx >= attentionSeq.size) {
            summarise("attention")
            startOneBackIntro()
            return
        }

        answered = false
        val target = attentionSeq[idx]

        title.text = "Tap for ★   ${idx + 1}/${attentionSeq.size}"
        stimulus.textSize = 36f
        stimulus.text = if (target) {
            "★"
        } else {
            listOf("○", "△", "◇")[rng.nextInt(3)]
        }

        controls.removeAllViews()

        val tap = button("TAP") {
            attentionTap(target)
        }

        controls.addView(
            tap,
            LinearLayout.LayoutParams(
                -1,
                WatchUi.dp(this, 48f)
            )
        )

        beginTrial(1300) {
            attentionTimeout(target)
        }
    }



    private fun attentionTap(target: Boolean) {
        if (!acceptAnswer()) return

        answered = true
        val now = System.currentTimeMillis()

        recordTrial(
            Trial(
                task = "attention",
                index = idx,
                expected = if (target) "tap" else "no_tap",
                response = "tap",
                correct = target,
                rtMs = SystemClock.elapsedRealtime() - trialStart,
                tsMs = now
            )
        )

        blankThen {
            idx++
            nextAttention()
        }
    }


    private fun attentionTimeout(target: Boolean) {
        if (answered) return

        answered = true
        val now = System.currentTimeMillis()

        recordTrial(
            Trial(
                task = "attention",
                index = idx,
                expected = if (target) "tap" else "no_tap",
                response = null,
                correct = !target,
                rtMs = null,
                tsMs = now
            )
        )

        blankThen {
            idx++
            nextAttention()
        }
    }


    private fun startOneBackIntro() {
        stage = "one_back_intro"
        title.text = "Next check"
        stimulus.textSize = 17f
        stimulus.text =
            "Compare each new shape with the one before it."

        setButtons(
            button("Practice") {
                showOneBackPractice()
            }
        )
    }


    private fun showOneBackPractice() {
        stage = "one_back_practice"
        title.text = "Practice: are these the same?"
        stimulus.textSize = 28f
        stimulus.text = "○  →  ○"

        setButtons(
            button("SAME") {
                stage = "one_back_practice_complete"
                title.text = "Correct"
                stimulus.text = "✓"

                setButtons(
                    button("Start") {
                        startOneBack()
                    }
                )
            },
            button("DIFFERENT") {
                title.text = "These are the same"
                stimulus.text = "○  →  ○"

                setButtons(
                    button("Try again") {
                        showOneBackPractice()
                    }
                )
            }
        )
    }


    private fun startOneBack() {
        stage = "one_back"
        stimulus.textSize = 36f
        idx = 0

        val shapes = listOf("○", "△", "◇")
        val sequence = mutableListOf(shapes[rng.nextInt(3)])

        repeat(17) { i ->
            val makeSame = (i % 2 == 0).xor(
                rng.nextBoolean() && i % 3 == 0
            )
            val previous = sequence.last()

            sequence += if (makeSame) {
                previous
            } else {
                shapes.filter { it != previous }[rng.nextInt(2)]
            }
        }

        oneBackSeq = sequence
        title.text = "Same as the last shape?"
        controls.removeAllViews()
        showOneBackPrime()
    }

    private fun showOneBackPrime() {
        title.text = "Remember this shape"
        stimulus.text = oneBackSeq[0]

        handler.postDelayed(
            {
                idx = 1
                nextOneBack()
            },
            1500
        )
    }


    private fun nextOneBack() {
        if (idx >= oneBackSeq.size) {
            summarise("one_back")
            finishScreen()
            return
        }

        answered = false
        title.text = "Same as last?   $idx/${oneBackSeq.size - 1}"
        stimulus.text = oneBackSeq[idx]

        val same = oneBackSeq[idx] == oneBackSeq[idx - 1]

        setButtons(
            button("SAME") {
                oneBackAnswer(true, same)
            },
            button("DIFFERENT") {
                oneBackAnswer(false, same)
            }
        )

        beginTrial(2500) {
            oneBackTimeout(same)
        }
    }



    private fun oneBackAnswer(
        answerSame: Boolean,
        expectedSame: Boolean
    ) {
        if (!acceptAnswer()) return

        answered = true
        val now = System.currentTimeMillis()

        recordTrial(
            Trial(
                task = "one_back",
                index = idx - 1,
                expected = if (expectedSame) "same" else "different",
                response = if (answerSame) "same" else "different",
                correct = answerSame == expectedSame,
                rtMs = SystemClock.elapsedRealtime() - trialStart,
                tsMs = now
            )
        )

        blankThen {
            idx++
            nextOneBack()
        }
    }




    private fun oneBackTimeout(expectedSame: Boolean) {
        if (answered) return

        answered = true
        val now = System.currentTimeMillis()

        recordTrial(
            Trial(
                task = "one_back",
                index = idx - 1,
                expected = if (expectedSame) "same" else "different",
                response = null,
                correct = false,
                rtMs = null,
                tsMs = now
            )
        )

        blankThen {
            idx++
            nextOneBack()
        }
    }


    private fun blankThen(next: () -> Unit) {
        stimulus.text = ""
        controls.removeAllViews()
        handler.postDelayed(next, 250)
    }


    // -------------------------------------------------------------------------
    // STORAGE / SUMMARY
    // -------------------------------------------------------------------------

    private fun recordTrial(trial: Trial) {
        results += trial

        val payload = JSONObject()
            .put("session_id", sessionId)
            .put("prompt_id", promptId)
            .put("task", trial.task)
            .put("trial_index", trial.index)
            .put("expected", trial.expected)
            .put("response", trial.response)
            .put("correct", trial.correct)
            .put("rt_ms", trial.rtMs)

        store(
            "cognition_trial",
            trial.tsMs,
            payload
        )
    }


    private fun summarise(task: String) {
        val taskResults = results.filter { it.task == task }
        if (taskResults.isEmpty()) return

        val reactionTimes = taskResults
            .mapNotNull { it.rtMs }
            .sorted()

        val median = if (reactionTimes.isEmpty()) {
            null
        } else if (reactionTimes.size % 2 == 1) {
            reactionTimes[reactionTimes.size / 2].toDouble()
        } else {
            (
                    reactionTimes[reactionTimes.size / 2 - 1] +
                            reactionTimes[reactionTimes.size / 2]
                    ) / 2.0
        }

        val mean = if (reactionTimes.isEmpty()) {
            null
        } else {
            reactionTimes.average()
        }

        val sd = if (reactionTimes.size < 2) {
            null
        } else {
            sqrt(
                reactionTimes.sumOf {
                    (it - (mean ?: 0.0)) * (it - (mean ?: 0.0))
                } / reactionTimes.size
            )
        }

        val omissions = taskResults.count {
            it.response == null &&
                    it.expected in listOf("tap", "same", "different")
        }

        val commissions = if (task == "attention") {
            taskResults.count {
                it.response == "tap" && it.expected == "no_tap"
            }
        } else {
            taskResults.count {
                it.response != null && !it.correct
            }
        }

        val payload = JSONObject()
            .put("session_id", sessionId)
            .put("prompt_id", promptId)
            .put("task", task)
            .put(
                "accuracy",
                taskResults.count { it.correct }.toDouble() / taskResults.size
            )
            .put("median_rt_ms", median)
            .put("rt_sd_ms", sd)
            .put("omissions", omissions)
            .put("commissions", commissions)
            .put("trials", taskResults.size)

        store(
            "cognition_summary",
            System.currentTimeMillis(),
            payload
        )
    }




    private fun recordDeclined() {
        stage = "declined"

        store(
            "cognition_prompt_outcome",
            System.currentTimeMillis(),
            JSONObject()
                .put("outcome", "declined")
                .put("session_id", sessionId)
                .put("prompt_id", promptId)
        )
    }


    private fun finishScreen() {
        stage = "done"
        title.text = "All done"
        stimulus.textSize = 36f
        stimulus.text = "✓"

        setButtons(
            button("Close") {
                finish()
            }
        )

        store(
            "cognition_prompt_outcome",
            System.currentTimeMillis(),
            JSONObject()
                .put("outcome", "completed")
                .put("session_id", sessionId)
                .put("prompt_id", promptId)
        )

        WatchTransport.flush(this)
    }

    private fun store(
        type: String,
        ts: Long,
        payload: JSONObject
    ) {
        val event = EventFactory
            .create(this, type, ts, payload)
            .put("study_id", sessionStudyId)

        db.insert(
            ts,
            event.getString("event_id"),
            type,
            event.toString()
        )
    }
}
