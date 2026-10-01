from __future__ import annotations

import json
import os
import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Iterable

from .models import EventIn, AnnotationIn


DEFAULT_DB_PATH = Path(__file__).resolve().parents[1] / "deliriumwatch.db"
_lock = threading.RLock()


def _db_path() -> Path:
    return Path(os.getenv("DELIRIUMWATCH_DB", DEFAULT_DB_PATH))

def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(_db_path(), check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


@contextmanager
def connection():
    with _lock:
        conn = _connect()
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()


def init_db() -> None:
    _db_path().parent.mkdir(parents=True, exist_ok=True)
    with connection() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS events (
                event_id TEXT PRIMARY KEY,
                study_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                ts_ms INTEGER NOT NULL,
                type TEXT NOT NULL,
                source TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                received_ms INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER) * 1000)
            );
            CREATE INDEX IF NOT EXISTS idx_events_study_ts ON events(study_id, ts_ms);
            CREATE INDEX IF NOT EXISTS idx_events_type_ts ON events(type, ts_ms);

            CREATE TABLE IF NOT EXISTS annotations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                study_id TEXT NOT NULL,
                ts_ms INTEGER NOT NULL,
                kind TEXT NOT NULL,
                value TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT ''
            );
            CREATE INDEX IF NOT EXISTS idx_annotations_study_ts ON annotations(study_id, ts_ms);
            """
        )


def insert_events(events: Iterable[EventIn]) -> tuple[int, int]:
    inserted = 0
    duplicate = 0
    with connection() as conn:
        for event in events:
            cur = conn.execute(
                """INSERT OR IGNORE INTO events
                   (event_id, study_id, device_id, ts_ms, type, source, payload_json)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (
                    event.event_id,
                    event.study_id,
                    event.device_id,
                    event.ts_ms,
                    event.type,
                    event.source,
                    json.dumps(event.payload, separators=(",", ":")),
                ),
            )
            if cur.rowcount == 1:
                inserted += 1
            else:
                duplicate += 1
    return inserted, duplicate


def list_patients() -> list[dict]:
    with connection() as conn:
        rows = conn.execute(
            """
            SELECT study_id, COUNT(*) AS event_count, MIN(ts_ms) AS first_ts, MAX(ts_ms) AS last_ts
            FROM events GROUP BY study_id ORDER BY last_ts DESC
            """
        ).fetchall()
    return [dict(r) for r in rows]


def get_events(study_id: str, start_ms: int | None = None, end_ms: int | None = None,
               types: list[str] | None = None, limit: int = 20000) -> list[dict]:
    clauses = ["study_id = ?"]
    params: list[object] = [study_id]
    if start_ms is not None:
        clauses.append("ts_ms >= ?")
        params.append(start_ms)
    if end_ms is not None:
        clauses.append("ts_ms <= ?")
        params.append(end_ms)
    if types:
        placeholders = ",".join("?" for _ in types)
        clauses.append(f"type IN ({placeholders})")
        params.extend(types)
    params.append(limit)
    sql = f"""
        SELECT event_id, study_id, device_id, ts_ms, type, source, payload_json
        FROM events WHERE {' AND '.join(clauses)}
        ORDER BY ts_ms ASC LIMIT ?
    """
    with connection() as conn:
        rows = conn.execute(sql, params).fetchall()
    result = []
    for row in rows:
        item = dict(row)
        item["payload"] = json.loads(item.pop("payload_json"))
        result.append(item)
    return result


def insert_annotation(study_id: str, annotation: AnnotationIn) -> dict:
    with connection() as conn:
        cur = conn.execute(
            "INSERT INTO annotations(study_id, ts_ms, kind, value, note) VALUES (?, ?, ?, ?, ?)",
            (study_id, annotation.ts_ms, annotation.kind, annotation.value, annotation.note),
        )
        row_id = cur.lastrowid
    return {"id": row_id, "study_id": study_id, **annotation.model_dump()}


def get_annotations(study_id: str) -> list[dict]:
    with connection() as conn:
        rows = conn.execute(
            "SELECT id, study_id, ts_ms, kind, value, note FROM annotations WHERE study_id=? ORDER BY ts_ms ASC",
            (study_id,),
        ).fetchall()
    return [dict(r) for r in rows]



def count_events(study_id: str, start_ms: int | None = None, end_ms: int | None = None) -> int:
    clauses = ["study_id=?"]
    params: list[object] = [study_id]
    if start_ms is not None:
        clauses.append("ts_ms>=?")
        params.append(start_ms)
    if end_ms is not None:
        clauses.append("ts_ms<=?")
        params.append(end_ms)
    with connection() as conn:
        row = conn.execute(f"SELECT COUNT(*) FROM events WHERE {' AND '.join(clauses)}", params).fetchone()
    return int(row[0]) if row else 0

def get_numeric_series(
    study_id: str,
    event_type: str,
    key: str,
    start_ms: int,
    end_ms: int | None = None,
    bucket_ms: int = 60_000,
) -> list[tuple[int, float]]:
    """Return a bounded, time-bucketed numeric series extracted from payload JSON.

    This prevents high-rate HR/temperature streams from being loaded wholesale just
    to compute longitudinal summaries. `key` is supplied by server code, not users.
    """
    bucket_ms = max(1, int(bucket_ms))
    path = f"$.{key}"
    clauses = ["study_id=?", "type=?", "ts_ms>=?", "json_type(payload_json, ?) IN ('integer','real')"]
    if end_ms is not None:
        clauses.append("ts_ms<=?")
    sql = f"""
        SELECT (ts_ms / ?) * ? AS bucket_ts,
               AVG(CAST(json_extract(payload_json, ?) AS REAL)) AS value
        FROM events
        WHERE {' AND '.join(clauses)}
        GROUP BY bucket_ts
        ORDER BY bucket_ts ASC
    """
    # SQL placeholder order has SELECT parameters before WHERE parameters.
    select_params: list[object] = [bucket_ms, bucket_ms, path]
    where_params: list[object] = [study_id, event_type, start_ms, path]
    if end_ms is not None:
        where_params.append(end_ms)
    with connection() as conn:
        rows = conn.execute(sql, select_params + where_params).fetchall()
    return [(int(r[0]), float(r[1])) for r in rows if r[1] is not None]


def iter_events(study_id: str, fetch_size: int = 2000):
    """Yield every event for an export without imposing a silent row limit."""
    conn = _connect()
    try:
        cur = conn.execute(
            """
            SELECT event_id, study_id, device_id, ts_ms, type, source, payload_json
            FROM events WHERE study_id=? ORDER BY ts_ms ASC
            """,
            (study_id,),
        )
        while True:
            rows = cur.fetchmany(fetch_size)
            if not rows:
                break
            for row in rows:
                item = dict(row)
                item["payload"] = json.loads(item.pop("payload_json"))
                yield item
    finally:
        conn.close()
