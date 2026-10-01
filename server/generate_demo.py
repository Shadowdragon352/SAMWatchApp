from __future__ import annotations
import argparse, json, math, random, time, urllib.request, uuid

p=argparse.ArgumentParser(); p.add_argument('--url',default='http://127.0.0.1:8000'); p.add_argument('--token',default=''); p.add_argument('--study-id',default='DEMO-001'); args=p.parse_args()
now=int(time.time()*1000); events=[]; random.seed(7)
for i in range(6*60*12):
    ts=now-6*3600*1000+i*5000
    phase=i/(6*60*12)
    deteriorate=max(0,(phase-.72)/.28)
    rms=max(.005, random.gauss(.11-.05*deteriorate,.025+.02*deteriorate))
    events.append({'event_id':str(uuid.uuid4()),'study_id':args.study_id,'device_id':'demo-watch','ts_ms':ts,'type':'movement_5s','source':'demo','payload':{'rms':rms,'mad':rms*.55,'sd':rms*.6,'peak':rms*2.1,'jerk_rms':rms*3,'active_fraction':min(1,max(0,rms/.18)),'transitions':random.randint(0,2)}})
    if i%12==0:
        bpm=random.gauss(74+20*deteriorate,3)
        events.append({'event_id':str(uuid.uuid4()),'study_id':args.study_id,'device_id':'demo-watch','ts_ms':ts,'type':'heart_rate','source':'demo','payload':{'bpm':bpm,'ibi_ms':[60000/bpm],'ibi_status':[0],'status':1}})
    if i%120==0:
        events.append({'event_id':str(uuid.uuid4()),'study_id':args.study_id,'device_id':'demo-watch','ts_ms':ts,'type':'temperature','source':'demo','payload':{'skin_temp_c':33.2+0.8*deteriorate,'ambient_temp_c':23.0,'status':0}})
for h,acc,rt in [(5,.94,610),(3,.91,650),(1,.68,1040)]:
    ts=now-h*3600*1000
    events.append({'event_id':str(uuid.uuid4()),'study_id':args.study_id,'device_id':'demo-watch','ts_ms':ts,'type':'cognition_summary','source':'demo','payload':{'task':'one_back','accuracy':acc,'median_rt_ms':rt,'rt_sd_ms':90+(1-acc)*500,'omissions':0 if acc>.8 else 3,'commissions':0,'trials':17}})
headers={'Content-Type':'application/json'}
if args.token: headers['Authorization']='Bearer '+args.token
for i in range(0,len(events),500):
    data=json.dumps({'events':events[i:i+500]}).encode(); req=urllib.request.Request(args.url.rstrip('/')+'/api/v1/ingest',data=data,headers=headers,method='POST'); print(urllib.request.urlopen(req).read().decode())
print('Demo complete:',args.study_id)
