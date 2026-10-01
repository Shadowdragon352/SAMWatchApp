from __future__ import annotations

import csv
import io
import json
import os
import time
from pathlib import Path
from typing import Annotated
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, PlainTextResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles

from . import analysis, db
from .models import AnnotationIn, IngestRequest


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.init_db()
    yield

app = FastAPI(title="DeliriumWatch Research Server", version="0.2.0", lifespan=lifespan)
STATIC_DIR = Path(__file__).resolve().parent / "static"
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


class ConnectionManager:
    def __init__(self) -> None:
        self.connections: dict[str, set[WebSocket]] = {}

    async def connect(self, study_id: str, ws: WebSocket) -> None:
        await ws.accept()
        self.connections.setdefault(study_id, set()).add(ws)

    def disconnect(self, study_id: str, ws: WebSocket) -> None:
        self.connections.get(study_id, set()).discard(ws)

    async def publish(self, study_id: str, message: dict) -> None:
        stale: list[WebSocket] = []
        for ws in list(self.connections.get(study_id, set())):
            try:
                await ws.send_json(message)
            except Exception:
                stale.append(ws)
        for ws in stale:
            self.disconnect(study_id, ws)


manager = ConnectionManager()


def require_token(authorization: Annotated[str | None, Header()] = None) -> None:
    configured = os.getenv("DELIRIUMWATCH_TOKEN", "").strip()
    if not configured:
        return
    expected = f"Bearer {configured}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="Invalid or missing bearer token")


@app.get("/")
def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/api/v1/health")
def health() -> dict:
    return {"ok": True, "time_ms": int(time.time() * 1000), "research_only": True}


@app.post("/api/v1/ingest", dependencies=[Depends(require_token)])
async def ingest(request: IngestRequest) -> dict:
    inserted, duplicates = db.insert_events(request.events)
    studies = sorted({e.study_id for e in request.events})
    for study_id in studies:
        await manager.publish(study_id, {"type": "events_ingested", "count": inserted})
    return {"ok": True, "inserted": inserted, "duplicates": duplicates}


@app.get("/api/v1/patients", dependencies=[Depends(require_token)])
def patients() -> list[dict]:
    return db.list_patients()


@app.get("/api/v1/patients/{study_id}/timeline", dependencies=[Depends(require_token)])
def timeline(
    study_id: str,
    hours: float = Query(default=24.0, ge=0.1, le=336.0),
    types: str | None = None,
    limit: int = Query(default=20000, ge=1, le=50000),
) -> dict:
    end = int(time.time() * 1000)
    start = end - int(hours * 3600 * 1000)
    type_list = [t.strip() for t in types.split(",") if t.strip()] if types else None
    events = db.get_events(study_id, start_ms=start, end_ms=end, types=type_list, limit=limit)
    return {"study_id": study_id, "start_ms": start, "end_ms": end, "events": events}


@app.get("/api/v1/patients/{study_id}/summary", dependencies=[Depends(require_token)])
def patient_summary(study_id: str) -> dict:
    return analysis.summary(study_id)


@app.get("/api/v1/patients/{study_id}/series", dependencies=[Depends(require_token)])
def patient_series(
    study_id: str,
    hours: float = Query(default=24.0, ge=0.1, le=336.0),
    bucket_seconds: int = Query(default=60, ge=5, le=3600),
) -> dict:
    end = int(time.time() * 1000)
    start = end - int(hours * 3600 * 1000)
    bucket = bucket_seconds * 1000
    def pack(series):
        return [{"ts_ms": ts, "value": value} for ts, value in series]
    return {
        "study_id": study_id,
        "start_ms": start,
        "end_ms": end,
        "bucket_seconds": bucket_seconds,
        "movement_rms": pack(db.get_numeric_series(study_id, "movement_5s", "rms", start, end, bucket)),
        "heart_rate": pack(db.get_numeric_series(study_id, "heart_rate", "bpm", start, end, bucket)),
        "skin_temperature": pack(db.get_numeric_series(study_id, "temperature", "skin_temp_c", start, end, bucket)),
    }


@app.get("/api/v1/patients/{study_id}/annotations", dependencies=[Depends(require_token)])
def annotations(study_id: str) -> list[dict]:
    return db.get_annotations(study_id)


@app.post("/api/v1/patients/{study_id}/annotations", dependencies=[Depends(require_token)])
async def annotate(study_id: str, annotation: AnnotationIn) -> dict:
    item = db.insert_annotation(study_id, annotation)
    await manager.publish(study_id, {"type": "annotation", "annotation": item})
    return item


@app.get("/api/v1/export/{study_id}.csv", dependencies=[Depends(require_token)])
def export_csv(study_id: str):
    safe_name = "".join(ch if ch.isalnum() or ch in "-_." else "_" for ch in study_id)[:100] or "participant"

    def rows():
        buf = io.StringIO()
        writer = csv.writer(buf)
        writer.writerow(["event_id", "study_id", "device_id", "ts_ms", "type", "source", "payload_json"])
        yield buf.getvalue().encode("utf-8")
        for e in db.iter_events(study_id):
            buf.seek(0); buf.truncate(0)
            writer.writerow([
                e["event_id"], e["study_id"], e["device_id"], e["ts_ms"], e["type"], e["source"],
                json.dumps(e["payload"], separators=(",", ":")),
            ])
            yield buf.getvalue().encode("utf-8")

    return StreamingResponse(
        rows(), media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{safe_name}-events.csv"'},
    )


@app.get("/api/v1/export/{study_id}.jsonl", dependencies=[Depends(require_token)])
def export_jsonl(study_id: str):
    safe_name = "".join(ch if ch.isalnum() or ch in "-_." else "_" for ch in study_id)[:100] or "participant"

    def rows():
        for e in db.iter_events(study_id):
            yield (json.dumps(e, separators=(",", ":")) + "\n").encode("utf-8")

    return StreamingResponse(
        rows(), media_type="application/x-ndjson",
        headers={"Content-Disposition": f'attachment; filename="{safe_name}-events.jsonl"'},
    )


@app.websocket("/ws/{study_id}")
async def websocket_endpoint(ws: WebSocket, study_id: str):
    configured = os.getenv("DELIRIUMWATCH_TOKEN", "").strip()
    supplied = ws.query_params.get("token", "")
    if configured and supplied != configured:
        await ws.close(code=1008, reason="Authentication required")
        return
    await manager.connect(study_id, ws)
    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        manager.disconnect(study_id, ws)
