from __future__ import annotations
import os, tempfile, uuid
from pathlib import Path

fd, path = tempfile.mkstemp(suffix='.db'); os.close(fd)
os.environ['DELIRIUMWATCH_DB']=path

from app import db, analysis
from app.models import EventIn, AnnotationIn


def ev(study, ts, typ, payload):
    return EventIn(event_id=str(uuid.uuid4()), study_id=study, device_id='w', ts_ms=ts, type=typ, payload=payload)


def test_baseline_not_assumed_and_then_enabled():
    db.init_db(); study='T1'; now=10_000_000
    events=[]
    for i in range(30):
        events.append(ev(study, now-6_000_000+i*60_000, 'movement_5s', {'rms':0.1+i*0.0005,'active_fraction':0.5}))
    for i in range(10):
        events.append(ev(study, now-600_000+i*60_000, 'movement_5s', {'rms':0.4,'active_fraction':0.9}))
    db.insert_events(events)
    s=analysis.summary(study, now_ms=now)
    assert s['confirmed_baseline_change']['index'] is None
    db.insert_annotation(study, AnnotationIn(ts_ms=now-7_000_000, kind='state', value='NON_DELIRIOUS_CONFIRMED', note='reference'))
    db.insert_annotation(study, AnnotationIn(ts_ms=now-900_000, kind='state', value='UNKNOWN', note='end reference'))
    s=analysis.summary(study, now_ms=now)
    assert s['confirmed_baseline_change']['index'] is not None


def teardown_module():
    try: Path(path).unlink(missing_ok=True)
    except Exception: pass


def test_movement_metrics_are_descriptive_and_available():
    study='T2'; now=20_000_000
    events=[]
    # 2 h of nominal 5 s movement windows with alternating low/active blocks.
    for i in range(2 * 60 * 60 // 5):
        active = (i // 12) % 2 == 1
        events.append(ev(study, now-2*60*60*1000+i*5000, 'movement_5s', {
            'rms': 0.25 if active else 0.02,
            'active_fraction': 0.8 if active else 0.01,
            'transitions': 1 if i % 12 == 0 else 0,
            'jerk_rms': 0.4 if active else 0.02,
        }))
    db.insert_events(events)
    s=analysis.summary(study, now_ms=now)
    m=s['movement_metrics']
    assert m['available'] is True
    assert 0.4 < m['near_immobile_window_fraction_24h'] < 0.6
    assert m['movement_bouts_24h'] > 10
    assert 'not a clinical threshold' in m['definitions']['near_immobile_window']
