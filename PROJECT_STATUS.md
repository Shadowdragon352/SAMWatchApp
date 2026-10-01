> **Repair release 0.1.2:** Read [FIXES.md](FIXES.md) first for update instructions, verified checks and current limitations. The Gradle wrapper is now included; after a watch reboot, resume monitoring from the app.

# Project status and validation boundary

## Implemented

- Wear OS foreground sensor service with local SQLite buffering
- 25 Hz-style gravity-removed accelerometer pathway (Samsung Health Sensor SDK or Android fallback)
- Samsung HR/IBI, skin temperature and configurable PPG bridge
- 5-second movement feature extraction with optional raw samples
- Brief target-attention and 1-back tasks with trial-level reaction-time data
- Scheduled cognition prompts and prompt/session outcome logging
- Wear Data Layer transfer with retry-safe UUID event IDs
- Android phone durable queue and configurable Tailscale/LAN HTTP relay
- FastAPI + SQLite server, WebSocket refresh, authenticated API when a token is configured
- Dashboard for HR, HRV, skin temperature, movement, cognition, acute change, confirmed-baseline change and movement qualification
- State/4AT/context annotations
- Complete streaming CSV and JSONL export (no fixed 50k export cap)
- Acute-first analysis that never assumes the first observations are a healthy baseline
- Tests for server ingestion/authentication/deduplication and baseline behaviour

## Deliberately not implemented as if validated

- No delirium classifier or sepsis classifier
- No population “risk probability” model
- No automatic clinical alert
- No inference that wrist inactivity equals sleep, sitting or lying
- No claim that peripheral skin temperature equals core temperature

Those functions require prospective clinical data, a pre-specified reference standard, participant-level validation and appropriate governance/regulatory review.

## Hardware/build boundary

The Python server has been executed and tested in the generation environment. The Android modules have been written against current Android/Wear OS and Samsung Health Sensor SDK documentation, but this environment does not contain an Android SDK/device farm or Samsung's proprietary AAR, so an APK was not built or physically tested here. Follow `INSTALL.md` and perform a first-device validation before any participant use.
