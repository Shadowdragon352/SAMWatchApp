from __future__ import annotations
import os, tempfile, uuid
fd, path=tempfile.mkstemp(suffix='.db'); os.close(fd)
os.environ['DELIRIUMWATCH_DB']=path
os.environ['DELIRIUMWATCH_TOKEN']='test-token'
from fastapi.testclient import TestClient
from app.main import app

client=TestClient(app)

def test_ingest_requires_token_and_deduplicates():
    e={'event_id':str(uuid.uuid4()),'study_id':'P1','device_id':'W1','ts_ms':1700000000000,'type':'heart_rate','source':'test','payload':{'bpm':82}}
    assert client.post('/api/v1/ingest',json={'events':[e]}).status_code==401
    h={'Authorization':'Bearer test-token'}
    r=client.post('/api/v1/ingest',json={'events':[e]},headers=h); assert r.status_code==200; assert r.json()['inserted']==1
    r=client.post('/api/v1/ingest',json={'events':[e]},headers=h); assert r.json()['duplicates']==1
    assert client.get('/api/v1/patients').status_code==401
    assert client.get('/api/v1/patients',headers=h).json()[0]['study_id']=='P1'

    r=client.get('/api/v1/patients/P1/series?hours=1&bucket_seconds=60',headers=h)
    assert r.status_code==200 and 'heart_rate' in r.json()
    r=client.get('/api/v1/export/P1.csv',headers=h)
    assert r.status_code==200 and 'event_id' in r.text and e['event_id'] in r.text
