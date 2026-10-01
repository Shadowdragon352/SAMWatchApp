# DeliriumWatch

Research prototype for a Galaxy Watch + Android phone + laptop dashboard workflow that combines continuous movement/physiology with brief repeated cognitive tasks.

**This software is research-only. It does not diagnose, exclude, predict, or treat delirium, sepsis, or any other medical condition. Do not use dashboard scores for clinical decisions.**

## Components

- **watch/** — Wear OS application. Continuous movement and heart-rate capture, optional Samsung Health Sensor SDK integration for IBI/raw PPG/skin temperature, local buffering, cognition tasks, scheduled prompts, and Data Layer transfer.
- **mobile/** — Android companion/gateway. Receives watch events, buffers them, forwards them to a configured laptop/server URL (including a Tailscale hostname/IP), and pushes configuration to the watch.
- **server/** — FastAPI + SQLite research server and browser dashboard. Live ingestion, authenticated participant-data APIs, time-series display, annotations, complete streaming CSV/JSONL export, short-term change metrics, movement qualification, and confirmed-baseline comparisons.

## Intended data flow

```text
Galaxy Watch
  accelerometer + HR/IBI + optional PPG/temp + cognition
      |
      | Wear OS Data Layer (Bluetooth/Wi-Fi)
      v
Android phone
  durable queue + Tailscale-compatible HTTP relay
      |
      v
Laptop
  FastAPI + SQLite + WebSocket dashboard
```

## Acute-first baseline model

The software never assumes the first hours are "normal." It works in acute mode immediately using interpretable current/short-term features. Personal baseline deviation is enabled only from data labelled `NON_DELIRIOUS_CONFIRMED` by the research team. No validated population delirium model is bundled.

## Movement pipeline

The watch produces 5-second windows containing RMS, mean amplitude deviation (MAD), SD, peak acceleration, jerk RMS, active fraction, transition count, and sample count. Raw acceleration can optionally be included. The server derives longer-window descriptors including near-immobile-window fraction, active-bout count/duration, movement RMSSD, day/night activity ratio, M10, L5 and relative amplitude. The default active threshold is a configurable engineering threshold, **not a clinical cutoff**.

Samsung's Health Sensor SDK accelerometer is 25 Hz and gravity-removed. If the Samsung SDK is unavailable, the app requests Android `TYPE_LINEAR_ACCELERATION` at ~25 Hz to preserve the same conceptual signal. Continuous PPG is configurable and defaults off to reduce battery use; it can be enabled as green-only or green/red/IR, with raw PPG storage independently selectable.

## Cognition tasks

Two short, standardized tasks are included:

1. **Target attention task** — respond to a target symbol among non-targets; captures omissions, false taps, reaction time and RT variability.
2. **1-back shape task** — decide whether the current shape matches the previous one; captures accuracy, omissions and reaction time.

Detailed trial-level events are retained. The watch intentionally gives neutral completion feedback rather than telling a patient they "did badly."

## Quick start

1. Install Android Studio Quail 4 (2026.1.4) or later, JDK 17+, Android SDK 36, and Wear OS tooling.
2. Open this repository in Android Studio.
3. Optional but recommended for Samsung-specific sensors: download Samsung Health Sensor SDK and copy `samsung-health-sensor-api.aar` into `watch/libs/`.
4. Build/install **watch** on a Galaxy Watch4 or later. Skin temperature requires Watch5 or later.
5. Build/install **mobile** on the paired Android phone. Both modules intentionally use the same application ID so Wear Data Layer communication works when signed with the same key.
6. Start the laptop server: `cd server && python -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt && uvicorn app.main:app --host 0.0.0.0 --port 8000`.
7. On the phone, set the server URL to your laptop's Tailscale address, e.g. `http://my-laptop:8000`, set a pseudonymous study ID and the same API token as the server, then tap **Save + send to watch**. Enter that token once in the browser dashboard as well.

See `INSTALL.md`, `SAFETY.md`, `RESEARCH_NOTES.md`, `DATA_DICTIONARY.md`, and `PROJECT_STATUS.md` before using it with participants.

## External technical references

- Samsung Health Sensor SDK overview: https://developer.samsung.com/health/sensor/guide/introduction.html
- Samsung data specifications: https://developer.samsung.com/health/sensor/guide/data-specifications.html
- Samsung permission guide: https://developer.samsung.com/health/sensor/guide/permission-request.html
- Samsung app/partner process: https://developer.samsung.com/health/sensor/process.html
- Wear OS Data Layer: https://developer.android.com/training/wearables/data/data-layer
- Android 16 health permission changes: https://developer.android.com/about/versions/16/behavior-changes-16

## Research rationale references

The design is informed by published work on wrist actigraphy in delirium, repeated smartwatch cognitive assessment, DelApp/attention testing, and 4AT principles. These references motivate feature collection; they **do not validate DeliriumWatch itself**.

- Smartwatch 1-back in older adults: https://pmc.ncbi.nlm.nih.gov/articles/PMC11786922/
- ICU delirium and accelerometry: https://www.nature.com/articles/s41598-019-44004-w
- Delirium classification with actigraphy: https://www.frontiersin.org/journals/neurology/articles/10.3389/fneur.2023.1135472/full
- DelApp attention assessment: https://pmc.ncbi.nlm.nih.gov/articles/PMC6980392/
- 4AT validation: https://pmc.ncbi.nlm.nih.gov/articles/PMC4066613/
