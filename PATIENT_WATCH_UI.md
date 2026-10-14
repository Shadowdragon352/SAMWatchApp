# Patient-facing watch home screen

The watch home screen shows SAM, the research-only label, Start recording, Stop recording, Cognitive check, Sync recordings, Sensor permissions, and Recording is on/off. It does not show numerical physiology, patient names, device/study IDs, queue counts, or raw sensor diagnostics. Recording status describes the service state; it does not assert that every sensor is available or that uploads have succeeded.

Data capture, durable queues, phone relay, clinician dashboard metrics and cognitive tasks are unchanged. Sync confirmation says recordings reached the phone, not the server. Start failures retain internal diagnostic detail but show a neutral request for study-team assistance. No normal/abnormal interpretation or diagnosis is displayed.

Device IDs remain part of recorded events and can be detected by the companion phone after receiving a batch. Use the phone's watch device ID field for setup; the watch home screen no longer exposes it. Configure and verify linking before participant use.

Verification completed: source inspection confirms measurement and identity fields are absent from MainActivity and start/stop/flush/cognitive task calls remain. Android SDK/build tools were unavailable in the editing environment; an APK was not built or installed.

Before installing on participants' watches, build with Android Studio and check: home screen with populated sensor preferences; recording on/off; denied permissions; successful/failed sync; patient handoff; phone device-ID detection; cognitive task completion; and continued metric updates in the clinician dashboard. This change covers the SAM app home screen, not other watch faces, system health apps or operating-system sensor indicators.
