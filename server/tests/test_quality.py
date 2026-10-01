import math
import uuid
import pytest
from pydantic import ValidationError
from app import db, analysis
from app.models import EventIn


def event(study, ts, payload):
    return EventIn(event_id=str(uuid.uuid4()), study_id=study, ts_ms=ts,
                   type="heart_rate", source="samsung", payload=payload)


def test_blank_study_id_rejected():
    with pytest.raises(ValidationError):
        event("   ", 1000, {})


def test_nonfinite_nested_payload_rejected():
    with pytest.raises(ValidationError):
        event("P", 1000, {"raw": [[float("nan")]]})


def test_unknown_ibi_quality_is_not_used_for_hrv():
    db.insert_events([event("HRV", 100000, {"ibi_ms": [700, 900, 700], "ibi_status": []})])
    assert analysis.summary("HRV", now_ms=110000)["current"]["hrv_rmssd_ms"] is None


def test_hrv_does_not_bridge_invalid_beats():
    db.insert_events([event("HRV", 100000, {"ibi_ms": [700, 710, 999, 900, 910], "ibi_status": [0, 0, -1, 0, 0]})])
    assert analysis.summary("HRV", now_ms=110000)["current"]["hrv_rmssd_ms"] == 10.0


def test_hrv_does_not_bridge_long_recording_gaps():
    db.insert_events([
        event("HRV", 100000, {"ibi_ms": [700, 710], "ibi_status": [0, 0]}),
        event("HRV", 150000, {"ibi_ms": [900, 910], "ibi_status": [0, 0]})])
    assert analysis.summary("HRV", now_ms=160000)["current"]["hrv_rmssd_ms"] == 10.0


def test_ingest_atomic_validation_and_duplicate_retry(client):
    headers = {"Authorization": "Bearer test-token"}
    a = event("P", 1000, {"bpm": 80}).model_dump()
    b = dict(a, event_id=str(uuid.uuid4()), study_id="   ")
    assert client.post("/api/v1/ingest", json={"events": [a, b]}, headers=headers).status_code == 422
    assert db.count_events("P") == 0
    for _ in range(2):
        assert client.post("/api/v1/ingest", json={"events": [a]}, headers=headers).status_code == 200
    assert db.count_events("P") == 1
