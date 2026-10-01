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

class CognitiveActivity:Activity(){
    private val handler=Handler(Looper.getMainLooper()); private lateinit var db:WatchDb
    private lateinit var root:LinearLayout; private lateinit var title:TextView; private lateinit var stimulus:TextView; private lateinit var controls:LinearLayout
    private lateinit var sessionStudyId:String; private var stage="intro"; private var trialStart=0L; private var answered=false; private val trialGate=TrialGate(); private var trialToken=0; private var timeout:Runnable?=null
    private val rng=java.util.Random(); private val results=mutableListOf<Trial>()
    private val sessionId=UUID.randomUUID().toString(); private var promptId=-1
    private var attentionSeq=listOf<Boolean>(); private var idx=0
    private var oneBackSeq=listOf<String>()

    data class Trial(val task:String,val index:Int,val expected:String,val response:String?,val correct:Boolean,val rtMs:Long?,val tsMs:Long)

    override fun onCreate(savedInstanceState:Bundle?){ super.onCreate(savedInstanceState); sessionStudyId=WatchPrefs(this).studyId; db=WatchDb(this); promptId=intent.getIntExtra("prompt_id",-1); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); setContentView(buildUi()); store("cognition_prompt_outcome",System.currentTimeMillis(),JSONObject().put("outcome","started").put("session_id",sessionId).put("prompt_id",promptId)); showIntro() }
    override fun onPause(){
        super.onPause()
        if(stage in listOf("attention","one_back","one_back_intro")) {
            trialGate.cancel();handler.removeCallbacksAndMessages(null);stage="interrupted"
            title.text="Check interrupted";stimulus.text="";setButtons(button("Close"){finish()})
            store("cognition_prompt_outcome",System.currentTimeMillis(),JSONObject().put("outcome","interrupted").put("session_id",sessionId).put("prompt_id",promptId))
        }
    }
    override fun onDestroy(){ handler.removeCallbacksAndMessages(null); db.close(); super.onDestroy() }

    private fun buildUi():View{
        root=LinearLayout(this)
        val scroll=WatchUi.scroll(this,root)
        val diameter=resources.displayMetrics.widthPixels
        val inset=(diameter*0.15f).toInt()
        root.setPadding(inset,inset,inset,inset)
        root.gravity=Gravity.CENTER
        title=TextView(this).apply{textSize=14f;setTextColor(Color.WHITE);gravity=Gravity.CENTER}
        stimulus=TextView(this).apply{textSize=36f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(0,4,0,4)}
        controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        root.addView(title,LinearLayout.LayoutParams(-1,-2));root.addView(stimulus,LinearLayout.LayoutParams(-1,-2));root.addView(controls,LinearLayout.LayoutParams(-1,-2));return scroll
    }
    private fun button(text:String,onClick:()->Unit)=Button(this).apply{this.text=text;textSize=12f;isAllCaps=false;minimumWidth=0;minimumHeight=0;setPadding(2,0,2,0);setOnClickListener{onClick()}}
    private fun setButtons(vararg bs:Button){controls.removeAllViews();bs.forEach{controls.addView(it,LinearLayout.LayoutParams(0,WatchUi.dp(this,40f),1f))}}
    private fun beginTrial(timeoutMs:Long,action:()->Unit){
        timeout?.let{handler.removeCallbacks(it)}
        trialToken=trialGate.begin();val token=trialToken
        trialStart=SystemClock.elapsedRealtime()
        timeout=Runnable{if(trialGate.finish(token))action()}.also{handler.postDelayed(it,timeoutMs)}
    }
    private fun acceptAnswer():Boolean{
        if(!trialGate.finish(trialToken))return false
        timeout?.let{handler.removeCallbacks(it)};return true
    }

    private fun showIntro(){stage="intro";title.text="Quick attention check";stimulus.text="★";setButtons(button("Start"){startAttention()},button("Not now"){recordDeclined();finish()})}

    private fun startAttention(){stage="attention";results.clear();idx=0; val seq=MutableList(24){false}; listOf(1,4,7,10,13,16,19,22).forEach{seq[it]=true}; Collections.shuffle(seq,rng);attentionSeq=seq; title.text="Tap only for the star";controls.removeAllViews();handler.postDelayed({nextAttention()},1200)}
    private fun nextAttention(){ if(idx>=attentionSeq.size){summarise("attention");startOneBackIntro();return}; answered=false; val target=attentionSeq[idx];stimulus.text=if(target)"★" else listOf("○","△","◇")[rng.nextInt(3)];controls.removeAllViews(); val tap=button("TAP"){attentionTap(target)};controls.addView(tap,LinearLayout.LayoutParams(-1,WatchUi.dp(this,40f)));beginTrial(1300){attentionTimeout(target)} }
    private fun attentionTap(target:Boolean){if(!acceptAnswer())return;answered=true;val now=System.currentTimeMillis();recordTrial(Trial("attention",idx,if(target)"tap" else "no_tap","tap",target,SystemClock.elapsedRealtime()-trialStart,now));blankThen{idx++;nextAttention()}}
    private fun attentionTimeout(target:Boolean){if(answered)return;answered=true;val now=System.currentTimeMillis();recordTrial(Trial("attention",idx,if(target)"tap" else "no_tap",null,!target,null,now));blankThen{idx++;nextAttention()}}

    private fun startOneBackIntro(){stage="one_back_intro";title.text="Next: compare each shape with the previous one";stimulus.text="○ → ○";stimulus.textSize=24f;setButtons(button("Continue"){startOneBack()})}
    private fun startOneBack(){stage="one_back";stimulus.textSize=36f;idx=0; val shapes=listOf("○","△","◇"); val seq=mutableListOf(shapes[rng.nextInt(3)]); repeat(17){i-> val makeSame=(i%2==0).xor(rng.nextBoolean()&&i%3==0); val prev=seq.last();seq+=if(makeSame)prev else shapes.filter{it!=prev}[rng.nextInt(2)]};oneBackSeq=seq;title.text="Same as the last shape?";controls.removeAllViews();showOneBackPrime()}
    private fun showOneBackPrime(){title.text="Remember this shape";stimulus.text=oneBackSeq[0];title.text="Remember this shape";handler.postDelayed({idx=1;nextOneBack()},1500)}
    private fun nextOneBack(){title.text="Same as last shape?";if(idx>=oneBackSeq.size){summarise("one_back");finishScreen();return};answered=false;stimulus.text=oneBackSeq[idx];val same=oneBackSeq[idx]==oneBackSeq[idx-1];setButtons(button("SAME"){oneBackAnswer(true,same)},button("OTHER"){oneBackAnswer(false,same)});beginTrial(2500){oneBackTimeout(same)}}
    private fun oneBackAnswer(answerSame:Boolean,expectedSame:Boolean){if(!acceptAnswer())return;answered=true;val now=System.currentTimeMillis();recordTrial(Trial("one_back",idx-1,if(expectedSame)"same" else "different",if(answerSame)"same" else "different",answerSame==expectedSame,SystemClock.elapsedRealtime()-trialStart,now));blankThen{idx++;nextOneBack()}}
    private fun oneBackTimeout(expectedSame:Boolean){if(answered)return;answered=true;val now=System.currentTimeMillis();recordTrial(Trial("one_back",idx-1,if(expectedSame)"same" else "different",null,false,null,now));blankThen{idx++;nextOneBack()}}
    private fun blankThen(next:()->Unit){stimulus.text="";controls.removeAllViews();handler.postDelayed(next,250)}

    private fun recordTrial(t:Trial){results+=t; val payload=JSONObject().put("session_id",sessionId).put("prompt_id",promptId).put("task",t.task).put("trial_index",t.index).put("expected",t.expected).put("response",t.response).put("correct",t.correct).put("rt_ms",t.rtMs);store("cognition_trial",t.tsMs,payload)}
    private fun summarise(task:String){val r=results.filter{it.task==task};if(r.isEmpty())return;val rts=r.mapNotNull{it.rtMs}.sorted();val med=if(rts.isEmpty())null else if(rts.size%2==1)rts[rts.size/2].toDouble() else (rts[rts.size/2-1]+rts[rts.size/2])/2.0;val mean=if(rts.isEmpty())null else rts.average();val sd=if(rts.size<2)null else sqrt(rts.sumOf{(it-(mean?:0.0))*(it-(mean?:0.0))}/rts.size);val omissions=r.count{it.response==null&&it.expected in listOf("tap","same","different")};val commissions=if(task=="attention")r.count{it.response=="tap"&&it.expected=="no_tap"} else r.count{it.response!=null&&!it.correct};val p=JSONObject().put("session_id",sessionId).put("prompt_id",promptId).put("task",task).put("accuracy",r.count{it.correct}.toDouble()/r.size).put("median_rt_ms",med).put("rt_sd_ms",sd).put("omissions",omissions).put("commissions",commissions).put("trials",r.size);store("cognition_summary",System.currentTimeMillis(),p)}
    private fun recordDeclined(){store("cognition_prompt_outcome",System.currentTimeMillis(),JSONObject().put("outcome","declined").put("session_id",sessionId).put("prompt_id",promptId))}
    private fun finishScreen(){stage="done";title.text="All done";stimulus.text="✓";setButtons(button("Close"){finish()});store("cognition_prompt_outcome",System.currentTimeMillis(),JSONObject().put("outcome","completed").put("session_id",sessionId).put("prompt_id",promptId));WatchTransport.flush(this)}
    private fun store(type:String,ts:Long,payload:JSONObject){val e=EventFactory.create(this,type,ts,payload).put("study_id",sessionStudyId);db.insert(ts,e.getString("event_id"),type,e.toString())}
}
