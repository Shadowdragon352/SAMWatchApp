package au.org.deliriumwatch.watch

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var stage="intro"; private var trialStart=0L; private var answered=false
    private val rng=java.util.Random(); private val results=mutableListOf<Trial>()
    private val sessionId=UUID.randomUUID().toString(); private var promptId=-1
    private var attentionSeq=listOf<Boolean>(); private var idx=0
    private var oneBackSeq=listOf<String>()

    data class Trial(val task:String,val index:Int,val expected:String,val response:String?,val correct:Boolean,val rtMs:Long?,val tsMs:Long)

    override fun onCreate(savedInstanceState:Bundle?){ super.onCreate(savedInstanceState); db=WatchDb(this); promptId=intent.getIntExtra("prompt_id",-1); window.statusBarColor=Color.BLACK; setContentView(buildUi()); store("cognition_prompt_outcome",System.currentTimeMillis(),JSONObject().put("outcome","started").put("session_id",sessionId).put("prompt_id",promptId)); showIntro() }
    override fun onDestroy(){ handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun buildUi():View{
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(18,20,18,20);setBackgroundColor(Color.BLACK)}
        title=TextView(this).apply{textSize=17f;setTextColor(Color.WHITE);gravity=Gravity.CENTER}
        stimulus=TextView(this).apply{textSize=58f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(0,12,0,12)}
        controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        root.addView(title,LinearLayout.LayoutParams(-1,-2));root.addView(stimulus,LinearLayout.LayoutParams(-1,0,1f));root.addView(controls,LinearLayout.LayoutParams(-1,-2));return root
    }
    private fun button(text:String,onClick:()->Unit)=Button(this).apply{this.text=text;textSize=17f;setOnClickListener{onClick()}}
    private fun setButtons(vararg bs:Button){controls.removeAllViews();bs.forEach{controls.addView(it,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(4,2,4,2)})}}

    private fun showIntro(){stage="intro";title.text="Quick attention check";stimulus.text="★";setButtons(button("Start"){startAttention()},button("Not now"){recordDeclined();finish()})}

    private fun startAttention(){stage="attention";results.clear();idx=0; val seq=MutableList(24){false}; listOf(1,4,7,10,13,16,19,22).forEach{seq[it]=true}; Collections.shuffle(seq,rng);attentionSeq=seq; title.text="Tap only for the star";controls.removeAllViews();handler.postDelayed({nextAttention()},1200)}
    private fun nextAttention(){ if(idx>=attentionSeq.size){summarise("attention");startOneBackIntro();return}; answered=false; val target=attentionSeq[idx];stimulus.text=if(target)"★" else listOf("○","△","◇")[rng.nextInt(3)];controls.removeAllViews(); val tap=button("TAP"){attentionTap(target)};controls.addView(tap,LinearLayout.LayoutParams(-1,-2));trialStart=System.currentTimeMillis();handler.postDelayed({attentionTimeout(target)},1300) }
    private fun attentionTap(target:Boolean){if(answered)return;answered=true;val now=System.currentTimeMillis();recordTrial(Trial("attention",idx,if(target)"tap" else "no_tap","tap",target,now-trialStart,now));blankThen{idx++;nextAttention()}}
    private fun attentionTimeout(target:Boolean){if(answered)return;answered=true;val now=System.currentTimeMillis();recordTrial(Trial("attention",idx,if(target)"tap" else "no_tap",null,!target,null,now));blankThen{idx++;nextAttention()}}

    private fun startOneBackIntro(){stage="one_back_intro";title.text="Next: is the shape the same as the last one?";stimulus.text="○  →  ○";setButtons(button("Continue"){startOneBack()})}
    private fun startOneBack(){stage="one_back";idx=0; val shapes=listOf("○","△","◇"); val seq=mutableListOf(shapes[rng.nextInt(3)]); repeat(17){i-> val makeSame=(i%2==0).xor(rng.nextBoolean()&&i%3==0); val prev=seq.last();seq+=if(makeSame)prev else shapes.filter{it!=prev}[rng.nextInt(2)]};oneBackSeq=seq;title.text="Same as the last shape?";controls.removeAllViews();showOneBackPrime()}
    private fun showOneBackPrime(){stimulus.text=oneBackSeq[0];title.text="Remember this shape";handler.postDelayed({idx=1;nextOneBack()},1500)}
    private fun nextOneBack(){if(idx>=oneBackSeq.size){summarise("one_back");finishScreen();return};answered=false;stimulus.text=oneBackSeq[idx];val same=oneBackSeq[idx]==oneBackSeq[idx-1];setButtons(button("SAME"){oneBackAnswer(true,same)},button("DIFFERENT"){oneBackAnswer(false,same)});trialStart=System.currentTimeMillis();handler.postDelayed({oneBackTimeout(same)},2500)}
    private fun oneBackAnswer(answerSame:Boolean,expectedSame:Boolean){if(answered)return;answered=true;val now=System.currentTimeMillis();recordTrial(Trial("one_back",idx-1,if(expectedSame)"same" else "different",if(answerSame)"same" else "different",answerSame==expectedSame,now-trialStart,now));blankThen{idx++;nextOneBack()}}
    private fun oneBackTimeout(expectedSame:Boolean){if(answered)return;answered=true;val now=System.currentTimeMillis();recordTrial(Trial("one_back",idx-1,if(expectedSame)"same" else "different",null,false,null,now));blankThen{idx++;nextOneBack()}}
    private fun blankThen(next:()->Unit){stimulus.text="";controls.removeAllViews();handler.postDelayed(next,250)}

    private fun recordTrial(t:Trial){results+=t; val payload=JSONObject().put("session_id",sessionId).put("prompt_id",promptId).put("task",t.task).put("trial_index",t.index).put("expected",t.expected).put("response",t.response).put("correct",t.correct).put("rt_ms",t.rtMs);store("cognition_trial",t.tsMs,payload)}
    private fun summarise(task:String){val r=results.filter{it.task==task};if(r.isEmpty())return;val rts=r.mapNotNull{it.rtMs}.sorted();val med=if(rts.isEmpty())null else if(rts.size%2==1)rts[rts.size/2].toDouble() else (rts[rts.size/2-1]+rts[rts.size/2])/2.0;val mean=if(rts.isEmpty())null else rts.average();val sd=if(rts.size<2)null else sqrt(rts.sumOf{(it-(mean?:0.0))*(it-(mean?:0.0))}/rts.size);val omissions=r.count{it.response==null&&it.expected in listOf("tap","same","different")};val commissions=if(task=="attention")r.count{it.response=="tap"&&it.expected=="no_tap"} else r.count{it.response!=null&&!it.correct};val p=JSONObject().put("session_id",sessionId).put("prompt_id",promptId).put("task",task).put("accuracy",r.count{it.correct}.toDouble()/r.size).put("median_rt_ms",med).put("rt_sd_ms",sd).put("omissions",omissions).put("commissions",commissions).put("trials",r.size);store("cognition_summary",System.currentTimeMillis(),p)}
    private fun recordDeclined(){store("cognition_prompt_outcome",System.currentTimeMillis(),JSONObject().put("outcome","declined").put("session_id",sessionId).put("prompt_id",promptId))}
    private fun finishScreen(){stage="done";title.text="All done";stimulus.text="✓";setButtons(button("Close"){finish()});store("cognition_prompt_outcome",System.currentTimeMillis(),JSONObject().put("outcome","completed").put("session_id",sessionId).put("prompt_id",promptId));startForegroundService(Intent(this,MonitoringService::class.java).setAction(MonitoringService.ACTION_FLUSH))}
    private fun store(type:String,ts:Long,payload:JSONObject){val e=EventFactory.create(this,type,ts,payload);db.insert(ts,e.getString("event_id"),type,e.toString())}
}
