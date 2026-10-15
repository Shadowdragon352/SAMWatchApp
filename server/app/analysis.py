from __future__ import annotations

import math
import os
import statistics
import time
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from . import db


BASELINE_STATE = "NON_DELIRIOUS_CONFIRMED"
MINUTE_MS = 60_000
HOUR_MS = 60 * MINUTE_MS
DAY_MS = 24 * HOUR_MS
REASSESSMENT_DOMAIN_THRESHOLD = float(os.getenv("DW_REASSESSMENT_THRESHOLD", "40"))
QUALITY_EVENT_LOOKBACK_MS = 6 * HOUR_MS


def _median(values: list[float]) -> float | None:
    return statistics.median(values) if values else None


def _mad(values: list[float], center: float | None = None) -> float | None:
    if not values:
        return None
    c = statistics.median(values) if center is None else center
    return statistics.median(abs(v - c) for v in values)


def _robust_z(value: float | None, reference: list[float]) -> float | None:
    if value is None or len(reference) < 8:
        return None
    center = statistics.median(reference)
    mad = _mad(reference, center)
    if mad is None or mad < 1e-9:
        sd = statistics.pstdev(reference) if len(reference) > 1 else 0.0
        if sd < 1e-9:
            return 0.0
        return (value - center) / sd
    return 0.67448975 * (value - center) / mad


def _latest_median(series: list[tuple[int, float]], after_ms: int) -> float | None:
    return _median([v for ts, v in series if ts >= after_ms])


def _state_intervals(annotations: list[dict]) -> list[tuple[int, int | None, str]]:
    states = [a for a in annotations if a["kind"] == "state"]
    intervals: list[tuple[int, int | None, str]] = []
    for idx, state in enumerate(states):
        end = states[idx + 1]["ts_ms"] - 1 if idx + 1 < len(states) else None
        intervals.append((state["ts_ms"], end, state["value"]))
    return intervals


def _is_in_state(ts: int, intervals: list[tuple[int, int | None, str]], wanted: str) -> bool:
    return any(state == wanted and ts >= start and (end is None or ts <= end) for start, end, state in intervals)


CHANGE_DOMAINS = {
    "movement": ("movement_rms", "active_fraction"),
    "physiology": ("heart_rate", "skin_temperature"),
    "cognition": (
        "attention_accuracy", "attention_rt",
        "one_back_accuracy", "one_back_rt",
    ),
}

# Direction is separate from magnitude. Cognition reaction-time features are
# reversed because a longer reaction time represents worse performance.
DIRECTION_POLARITY = {
    "movement": {
        "movement_rms": 1,
        "active_fraction": 1,
    },
    "physiology": {
        "heart_rate": 1,
        "skin_temperature": 1,
    },
    "cognition": {
        "attention_accuracy": 1,
        "attention_rt": -1,
        "one_back_accuracy": 1,
        "one_back_rt": -1,
    },
}


def _domain_change_indices(z_map: dict[str, float | None]) -> tuple[float | None, dict[str, float | None]]:
    """Magnitude-only engineering indices with equal weighting by data domain.

    This prevents a domain with more extracted features from automatically carrying
    more weight. The result is deliberately not a probability or clinical score.
    """
    domains: dict[str, float | None] = {}
    for domain, names in CHANGE_DOMAINS.items():
        zs = [min(abs(float(z_map[n])), 5.0) for n in names if z_map.get(n) is not None]
        domains[domain] = round(100.0 * statistics.mean(zs) / 5.0, 1) if zs else None
    available = [v for v in domains.values() if v is not None]
    return (round(statistics.mean(available), 1) if available else None), domains


def _change_level(value: float | None) -> str:
    """Engineering display label only; these are not clinical thresholds."""
    if value is None:
        return "unavailable"
    if value < 20:
        return "minimal"
    if value < 40:
        return "mild"
    if value < 60:
        return "moderate"
    return "large"


def _domain_directions(
    z_map: dict[str, float | None],
    domain_indices: dict[str, float | None],
) -> dict[str, dict[str, str]]:
    """Describe direction without changing the existing magnitude calculation."""
    result: dict[str, dict[str, str]] = {}

    for domain, feature_polarities in DIRECTION_POLARITY.items():
        adjusted: list[float] = []
        for feature, polarity in feature_polarities.items():
            z = z_map.get(feature)
            if z is not None:
                adjusted.append(float(z) * polarity)

        magnitude = domain_indices.get(domain)
        if magnitude is None or not adjusted:
            result[domain] = {
                "direction": "unavailable",
                "label": "No comparison available",
                "level": "unavailable",
            }
            continue

        # Ignore very small feature movements when deciding the arrow so noise
        # does not make the dashboard appear more certain than the data support.
        strong = [value for value in adjusted if abs(value) >= 0.5]
        if magnitude < 10 or not strong:
            direction = "stable"
        elif any(value > 0 for value in strong) and any(value < 0 for value in strong):
            direction = "mixed"
        elif statistics.mean(strong) > 0:
            direction = "up"
        else:
            direction = "down"

        if domain == "cognition":
            labels = {
                "up": "Performance increased",
                "down": "Performance decreased",
                "mixed": "Mixed cognitive change",
                "stable": "No substantial change",
            }
        else:
            labels = {
                "up": "Increased",
                "down": "Decreased",
                "mixed": "Mixed change",
                "stable": "No substantial change",
            }

        result[domain] = {
            "direction": direction,
            "label": labels[direction],
            "level": _change_level(magnitude),
        }

    return result


def _measurement_confidence(
    current: dict[str, float | None],
    movement_metrics: dict[str, Any],
    latest_movement: dict | None,
    latest_cognition: dict | None,
    baseline_domains: dict[str, float | None],
    now_ms: int,
) -> dict[str, Any]:
    """Summarise availability/recency of data used for interpretation.

    This is deliberately *not* diagnostic confidence. It only describes whether
    the underlying measurements are recent and sufficiently available.
    """
    domains: dict[str, dict[str, Any]] = {}
    issues: list[str] = []

    # Movement: use freshness plus 24 h stream coverage.
    movement_age_min = None
    if latest_movement is not None:
        movement_age_min = max(0.0, (now_ms - int(latest_movement["ts_ms"])) / MINUTE_MS)

    coverage = movement_metrics.get("coverage_fraction") if movement_metrics.get("available") else None
    movement_current = current.get("movement_rms") is not None or current.get("active_fraction") is not None

    if not movement_current or movement_age_min is None or movement_age_min > 30:
        movement_level = "unavailable"
        issues.append("No recent movement measurement is available.")
    elif movement_age_min <= 5 and coverage is not None and coverage >= 0.80:
        movement_level = "high"
    elif movement_age_min <= 15 and coverage is not None and coverage >= 0.50:
        movement_level = "moderate"
        issues.append("Movement data are usable but coverage or recency is reduced.")
    else:
        movement_level = "low"
        issues.append("Movement data confidence is low; check watch fit, wear and sensor availability.")

    if baseline_domains.get("movement") is None:
        issues.append("Movement has insufficient confirmed-baseline data for a baseline comparison.")

    domains["movement"] = {
        "level": movement_level,
        "coverage_percent": round(100.0 * coverage, 1) if coverage is not None else None,
        "age_minutes": round(movement_age_min, 1) if movement_age_min is not None else None,
    }

    # Physiology: the current dictionary only contains values from the last 30 min.
    physiology_available = sum([
        current.get("heart_rate") is not None,
        current.get("skin_temperature") is not None,
    ])
    if physiology_available == 2:
        physiology_level = "high"
    elif physiology_available == 1:
        physiology_level = "moderate"
        issues.append("One physiology measurement is unavailable in the recent window.")
    else:
        physiology_level = "unavailable"
        issues.append("No recent physiology measurements are available.")

    if baseline_domains.get("physiology") is None:
        issues.append("Physiology has insufficient confirmed-baseline data for a baseline comparison.")

    domains["physiology"] = {
        "level": physiology_level,
        "heart_rate_available": current.get("heart_rate") is not None,
        "skin_temperature_available": current.get("skin_temperature") is not None,
    }

    # Cognition: a task is intermittent, so allow a larger recency window.
    cognition_age_hours = None
    if latest_cognition is None:
        cognition_level = "unavailable"
        issues.append("No cognitive assessment result is available.")
    else:
        cognition_age_hours = max(0.0, (now_ms - int(latest_cognition["ts_ms"])) / HOUR_MS)
        if cognition_age_hours <= 2:
            cognition_level = "high"
        elif cognition_age_hours <= 6:
            cognition_level = "moderate"
            issues.append("The latest cognitive assessment is not recent.")
        elif cognition_age_hours <= 12:
            cognition_level = "low"
            issues.append("The latest cognitive assessment is more than 6 hours old.")
        else:
            cognition_level = "unavailable"
            issues.append("No sufficiently recent cognitive assessment is available.")

    if baseline_domains.get("cognition") is None:
        issues.append("Cognition has insufficient confirmed-baseline data for a baseline comparison.")

    domains["cognition"] = {
        "level": cognition_level,
        "age_hours": round(cognition_age_hours, 1) if cognition_age_hours is not None else None,
    }

    rank = {"unavailable": 0, "low": 1, "moderate": 2, "high": 3}
    usable = [rank[item["level"]] for item in domains.values() if item["level"] != "unavailable"]
    if not usable:
        overall = "unavailable"
    elif statistics.mean(usable) >= 2.5:
        overall = "high"
    elif statistics.mean(usable) >= 1.5:
        overall = "moderate"
    else:
        overall = "low"

    return {
        "overall": overall,
        "domains": domains,
        "issues": list(dict.fromkeys(issues)),
        "description": (
            "Confidence describes measurement availability, coverage and recency only; "
            "it is not confidence in a delirium diagnosis."
        ),
    }


def _reassessment_prompt(
    domain_indices: dict[str, float | None],
    directions: dict[str, dict[str, str]],
    confidence: dict[str, Any],
) -> dict[str, Any]:
    changed = {
        domain: value
        for domain, value in domain_indices.items()
        if value is not None and value >= REASSESSMENT_DOMAIN_THRESHOLD
    }

    if not changed:
        return {
            "show": False,
            "threshold": REASSESSMENT_DOMAIN_THRESHOLD,
            "message": "No reassessment prompt generated from the confirmed baseline.",
            "research_only": True,
        }

    primary_domain = max(changed, key=changed.get)
    domain_confidence = confidence.get("domains", {}).get(primary_domain, {}).get("level", "unavailable")

    actions = {
        "movement": (
            "Movement has changed from the confirmed baseline. Check patient activity/context "
            "and confirm that the watch measurement is representative before reassessing if appropriate."
        ),
        "physiology": (
            "Physiology has changed from the confirmed baseline. Verify the physiological measurements "
            "and reassess the patient if clinically appropriate."
        ),
        "cognition": (
            "Cognitive performance has changed from the confirmed baseline. Consider a clinician-led "
            "cognitive reassessment or 4AT if clinically appropriate."
        ),
    }

    if domain_confidence in {"low", "unavailable"}:
        suggested_action = (
            "A baseline change was detected, but data confidence for the main changed domain is low. "
            "Check the measurement and patient/device context before interpreting the change."
        )
    else:
        suggested_action = actions[primary_domain]

    changed_domains = [
        {
            "domain": domain,
            "change_index": value,
            "level": _change_level(value),
            "direction": directions.get(domain, {}).get("direction", "unavailable"),
            "direction_label": directions.get(domain, {}).get("label", "No comparison available"),
            "confidence": confidence.get("domains", {}).get(domain, {}).get("level", "unavailable"),
        }
        for domain, value in sorted(changed.items(), key=lambda item: item[1], reverse=True)
    ]

    return {
        "show": True,
        "threshold": REASSESSMENT_DOMAIN_THRESHOLD,
        "trigger_domain": primary_domain,
        "domain_change": changed[primary_domain],
        "direction": directions.get(primary_domain, {}).get("direction", "unavailable"),
        "direction_label": directions.get(primary_domain, {}).get("label", "No comparison available"),
        "confidence": domain_confidence,
        "changed_domains": changed_domains,
        "suggested_action": suggested_action,
        "research_only": True,
        "description": (
            "Prototype engineering prompt based on change from confirmed baseline; "
            "not a validated clinical threshold or diagnosis."
        ),
    }


def _patient_quality_context(study_id: str, now_ms: int) -> dict[str, Any]:
    """Expose recent patient/device interruptions without treating them as deterioration."""
    events = db.get_events(
        study_id,
        start_ms=now_ms - QUALITY_EVENT_LOOKBACK_MS,
        end_ms=now_ms,
        types=["quality_issue", "cognition_prompt_outcome"],
        limit=500,
    )

    quality_issues = []
    cognition_outcomes = []
    for event in events:
        if event["type"] == "quality_issue":
            quality_issues.append({
                "ts_ms": event["ts_ms"],
                "code": event["payload"].get("code", "unknown"),
                "message": event["payload"].get("message", "Data-quality issue reported by watch."),
                "severity": event["payload"].get("severity", "warning"),
            })
        elif event["type"] == "cognition_prompt_outcome":
            cognition_outcomes.append({
                "ts_ms": event["ts_ms"],
                "outcome": event["payload"].get("outcome", "unknown"),
                "session_id": event["payload"].get("session_id"),
            })

    return {
        "recent_quality_issues": quality_issues[-10:],
        "latest_cognition_prompt_outcome": cognition_outcomes[-1] if cognition_outcomes else None,
        "description": (
            "Interrupted or declined cognitive tasks and device errors are reported as data-quality/context "
            "information and are not interpreted as patient deterioration."
        ),
    }


def _safe_tz() -> ZoneInfo:
    name = os.getenv("DELIRIUMWATCH_TZ", "Australia/Melbourne")
    try:
        return ZoneInfo(name)
    except ZoneInfoNotFoundError:
        return ZoneInfo("UTC")


def _movement_metrics(events: list[dict], now_ms: int) -> dict[str, Any]:
    """Translate 5 s movement windows into interpretable research descriptors.

    `active_fraction` is already derived on-watch from a configurable acceleration
    magnitude threshold. The 5% and 20% cut-points below are *engineering labels*
    for near-immobile and active windows, not clinical thresholds.
    """
    rows: list[dict[str, float]] = []
    for e in events:
        if e.get("type") != "movement_5s":
            continue
        p = e.get("payload", {})
        try:
            rms = float(p["rms"])
            af = float(p["active_fraction"])
        except (KeyError, TypeError, ValueError):
            continue
        if math.isfinite(rms) and math.isfinite(af):
            rows.append({
                "ts": float(e["ts_ms"]),
                "rms": rms,
                "active_fraction": min(1.0, max(0.0, af)),
                "transitions": float(p.get("transitions", 0) or 0),
                "jerk_rms": float(p.get("jerk_rms", 0) or 0),
            })
    rows.sort(key=lambda r: r["ts"])
    if not rows:
        return {
            "available": False,
            "description": "No movement_5s records are available.",
        }

    cutoff_1h = now_ms - HOUR_MS
    r1 = [r for r in rows if r["ts"] >= cutoff_1h]
    rms = [r["rms"] for r in rows]
    af = [r["active_fraction"] for r in rows]
    near_immobile = [r for r in rows if r["active_fraction"] <= 0.05]

    # Cross-window active bouts. A window is considered active here when >=20% of
    # its samples cross the researcher-configurable on-watch movement threshold.
    bouts: list[float] = []
    bout_start: float | None = None
    bout_end: float | None = None
    previous_ts: float | None = None
    for r in rows:
        active = r["active_fraction"] >= 0.20
        contiguous = previous_ts is None or r["ts"] - previous_ts <= 7_500
        if active:
            if bout_start is None or not contiguous:
                if bout_start is not None and bout_end is not None:
                    bouts.append(max(5.0, (bout_end - bout_start) / 1000.0 + 5.0))
                bout_start = r["ts"]
            bout_end = r["ts"]
        elif bout_start is not None and bout_end is not None:
            bouts.append(max(5.0, (bout_end - bout_start) / 1000.0 + 5.0))
            bout_start = bout_end = None
        previous_ts = r["ts"]
    if bout_start is not None and bout_end is not None:
        bouts.append(max(5.0, (bout_end - bout_start) / 1000.0 + 5.0))

    # One-minute movement RMSSD captures short-term fragmentation without being
    # dominated by 25 Hz sensor noise.
    minute_series = db.get_numeric_series(
        events[0]["study_id"], "movement_5s", "rms",
        start_ms=max(int(rows[0]["ts"]), now_ms - DAY_MS), end_ms=now_ms, bucket_ms=MINUTE_MS,
    )
    minute_values = [v for _, v in minute_series]
    minute_rmssd = None
    if len(minute_values) >= 3:
        diffs = [minute_values[i] - minute_values[i - 1] for i in range(1, len(minute_values))]
        minute_rmssd = math.sqrt(sum(d * d for d in diffs) / len(diffs))

    # Day/night descriptors use configurable local time. These are descriptive,
    # not a sleep classifier. Twilight (06-08 and 20-22) is excluded from ratio.
    tz = _safe_tz()
    day_vals: list[float] = []
    night_vals: list[float] = []
    for r in rows:
        h = datetime.fromtimestamp(r["ts"] / 1000.0, tz=tz).hour
        if 8 <= h < 20:
            day_vals.append(r["rms"])
        elif h >= 22 or h < 6:
            night_vals.append(r["rms"])
    day_mean = statistics.mean(day_vals) if day_vals else None
    night_mean = statistics.mean(night_vals) if night_vals else None
    night_day_ratio = (night_mean / day_mean) if day_mean and night_mean is not None else None

    # Non-parametric rest-activity descriptors M10/L5 and relative amplitude.
    # Only calculate when a nearly complete day is present and candidate windows
    # consist of consecutive hourly bins.
    hourly: dict[int, list[float]] = {}
    for r in rows:
        bucket = int(r["ts"] // HOUR_MS)
        hourly.setdefault(bucket, []).append(r["rms"])
    hourly_mean = {k: statistics.mean(v) for k, v in hourly.items()}

    def consecutive_window_means(length: int) -> list[tuple[int, float]]:
        out: list[tuple[int, float]] = []
        for start in sorted(hourly_mean):
            keys = [start + i for i in range(length)]
            if all(k in hourly_mean for k in keys):
                out.append((start, statistics.mean(hourly_mean[k] for k in keys)))
        return out

    m10 = l5 = relative_amplitude = None
    if len(hourly_mean) >= 20:
        w10 = consecutive_window_means(10)
        w5 = consecutive_window_means(5)
        if w10:
            m10 = max(v for _, v in w10)
        if w5:
            l5 = min(v for _, v in w5)
        if m10 is not None and l5 is not None and (m10 + l5) > 1e-12:
            relative_amplitude = (m10 - l5) / (m10 + l5)

    observed_hours = max(5.0 / 3600.0, (rows[-1]["ts"] - rows[0]["ts"] + 5_000.0) / HOUR_MS)
    expected_windows = max(1.0, observed_hours * 3600.0 / 5.0)
    coverage = min(1.0, len(rows) / expected_windows)

    return {
        "available": True,
        "window_seconds": 5,
        "observed_hours": round(observed_hours, 2),
        "coverage_fraction": round(coverage, 3),
        "mean_rms_24h": round(statistics.mean(rms), 5),
        "median_rms_24h": round(statistics.median(rms), 5),
        "mean_active_fraction_24h": round(statistics.mean(af), 4),
        "mean_active_fraction_1h": round(statistics.mean(r["active_fraction"] for r in r1), 4) if r1 else None,
        "near_immobile_window_fraction_24h": round(len(near_immobile) / len(rows), 4),
        "within_window_transitions_per_hour": round(sum(r["transitions"] for r in rows) / observed_hours, 2),
        "movement_bouts_24h": len(bouts),
        "median_active_bout_seconds": round(statistics.median(bouts), 1) if bouts else None,
        "movement_rmssd_1min": round(minute_rmssd, 5) if minute_rmssd is not None else None,
        "mean_jerk_rms_24h": round(statistics.mean(r["jerk_rms"] for r in rows), 5),
        "day_mean_rms": round(day_mean, 5) if day_mean is not None else None,
        "night_mean_rms": round(night_mean, 5) if night_mean is not None else None,
        "night_day_activity_ratio": round(night_day_ratio, 3) if night_day_ratio is not None else None,
        "m10_rms": round(m10, 5) if m10 is not None else None,
        "l5_rms": round(l5, 5) if l5 is not None else None,
        "relative_amplitude": round(relative_amplitude, 4) if relative_amplitude is not None else None,
        "definitions": {
            "near_immobile_window": "active_fraction <= 0.05 for a 5 s window (engineering descriptor, not a clinical threshold)",
            "active_bout": "consecutive 5 s windows with active_fraction >= 0.20 (engineering descriptor)",
            "day": "08:00-20:00 local server study timezone",
            "night": "22:00-06:00 local server study timezone",
            "timezone": str(tz),
            "sleep": "No sleep or posture diagnosis is inferred from wrist movement alone.",
        },
    }


def summary(study_id: str, now_ms: int | None = None) -> dict[str, Any]:
    now_ms = now_ms or int(time.time() * 1000)
    start_24h = now_ms - DAY_MS
    start_7d = now_ms - 7 * DAY_MS
    annotations = db.get_annotations(study_id)

    # Minute-bucketed series keep high-rate streams tractable while preserving the
    # timescale used by acute and baseline comparisons.
    cognition_events = db.get_events(study_id, start_ms=start_7d, end_ms=now_ms, types=["cognition_summary"], limit=5_000)

    def cognition_series(task: str, key: str) -> list[tuple[int, float]]:
        out: list[tuple[int, float]] = []
        for e in cognition_events:
            p = e["payload"]
            v = p.get(key)
            if p.get("task") == task and isinstance(v, (int, float)) and math.isfinite(float(v)):
                out.append((e["ts_ms"], float(v)))
        return out

    feature_series = {
        "movement_rms": db.get_numeric_series(study_id, "movement_5s", "rms", start_7d, now_ms, MINUTE_MS),
        "active_fraction": db.get_numeric_series(study_id, "movement_5s", "active_fraction", start_7d, now_ms, MINUTE_MS),
        "heart_rate": db.get_numeric_series(study_id, "heart_rate", "bpm", start_7d, now_ms, MINUTE_MS),
        "skin_temperature": db.get_numeric_series(study_id, "temperature", "skin_temp_c", start_7d, now_ms, MINUTE_MS),
        "attention_accuracy": cognition_series("attention", "accuracy"),
        "attention_rt": cognition_series("attention", "median_rt_ms"),
        "one_back_accuracy": cognition_series("one_back", "accuracy"),
        "one_back_rt": cognition_series("one_back", "median_rt_ms"),
    }

    current_after = now_ms - 30 * MINUTE_MS
    reference_start = now_ms - 6 * HOUR_MS
    reference_end = now_ms - HOUR_MS
    current: dict[str, float | None] = {
        name: _latest_median(series, current_after) for name, series in feature_series.items()
    }

    acute_z: dict[str, float | None] = {}
    for name, series in feature_series.items():
        ref = [v for ts, v in series if reference_start <= ts <= reference_end]
        acute_z[name] = _robust_z(current[name], ref)

    intervals = _state_intervals(annotations)
    baseline_z: dict[str, float | None] = {}
    baseline_counts: dict[str, int] = {}
    for name, series in feature_series.items():
        vals = [v for ts, v in series if _is_in_state(ts, intervals, BASELINE_STATE) and ts < current_after]
        baseline_counts[name] = len(vals)
        baseline_z[name] = _robust_z(current[name], vals)

    movement_events = db.get_events(study_id, start_ms=start_24h, end_ms=now_ms, types=["movement_5s"], limit=20_000)
    latest_movement = {"ts_ms": movement_events[-1]["ts_ms"], **movement_events[-1]["payload"]} if movement_events else None
    latest_cognition = {"ts_ms": cognition_events[-1]["ts_ms"], **cognition_events[-1]["payload"]} if cognition_events else None

    # HRV uses original recent IBI arrays (not the minute-aggregated HR series).
    recent_hr_events = db.get_events(
        study_id, start_ms=now_ms - 10 * MINUTE_MS, end_ms=now_ms,
        types=["heart_rate"], limit=2_000,
    )
    ibi_values: list[float] = []
    squared_differences: list[float] = []
    previous_ibi = None
    previous_ts = None
    for e in recent_hr_events:
        if previous_ts is not None and e["ts_ms"] - previous_ts > 10_000:
            previous_ibi = None
        previous_ts = e["ts_ms"]
        vals = e["payload"].get("ibi_ms")
        statuses = e["payload"].get("ibi_status")
        if not isinstance(vals, list) or not isinstance(statuses, list):
            previous_ibi = None
            continue
        for idx, val in enumerate(vals):
            ok = idx < len(statuses) and statuses[idx] == 0
            if ok and isinstance(val, (int, float)) and 300 <= float(val) <= 2000:
                ibi = float(val)
                ibi_values.append(ibi)
                if previous_ibi is not None:
                    squared_differences.append((ibi - previous_ibi) ** 2)
                previous_ibi = ibi
            else:
                # Never calculate adjacent-beat differences across invalid/missing beats.
                previous_ibi = None
    rmssd = round(math.sqrt(statistics.mean(squared_differences)), 1) if len(squared_differences) >= 2 else None

    acute_score, acute_domains = _domain_change_indices(acute_z)
    baseline_score, baseline_domains = _domain_change_indices(baseline_z)
    acute_directions = _domain_directions(acute_z, acute_domains)
    baseline_directions = _domain_directions(baseline_z, baseline_domains)

    movement_metrics = _movement_metrics(movement_events, now_ms)
    measurement_confidence = _measurement_confidence(
        current=current,
        movement_metrics=movement_metrics,
        latest_movement=latest_movement,
        latest_cognition=latest_cognition,
        baseline_domains=baseline_domains,
        now_ms=now_ms,
    )
    reassessment_prompt = _reassessment_prompt(
        baseline_domains,
        baseline_directions,
        measurement_confidence,
    )
    patient_quality = _patient_quality_context(study_id, now_ms)

    event_count_24h = db.count_events(study_id, start_ms=start_24h, end_ms=now_ms)

    return {
        "study_id": study_id,
        "generated_ms": now_ms,
        "event_count_24h": event_count_24h,
        "current": {
            "heart_rate_bpm": round(current["heart_rate"], 1) if current["heart_rate"] is not None else None,
            "hrv_rmssd_ms": rmssd,
            "skin_temp_c": round(current["skin_temperature"], 2) if current["skin_temperature"] is not None else None,
            "movement": latest_movement,
            "cognition": latest_cognition,
        },
        "movement_metrics": movement_metrics,
        "measurement_confidence": measurement_confidence,
        "reassessment_prompt": reassessment_prompt,
        "patient_quality": patient_quality,
        "acute_change": {
            "index": acute_score,
            "domain_indices": acute_domains,
            "domain_directions": acute_directions,
            "z": acute_z,
            "description": "Research-only magnitude of change in the last 30 min versus approximately 1-6 h earlier; not a delirium or sepsis probability.",
        },
        "confirmed_baseline_change": {
            "index": baseline_score,
            "domain_indices": baseline_domains,
            "domain_directions": baseline_directions,
            "z": baseline_z,
            "reference_counts": baseline_counts,
            "description": "Uses only periods explicitly labelled NON_DELIRIOUS_CONFIRMED; unavailable until enough labelled reference data exist.",
        },
        "population_model": {
            "available": False,
            "description": "No validated population delirium model is bundled. Add one only after prospective model development and validation.",
        },
        "annotations": annotations[-20:],
    }