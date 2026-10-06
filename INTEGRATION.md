# Dashboard integration (app version 0.1.3)

This app now connects directly to the included modified patient dashboard via HTTPS.
See `../dashboard/GETTING_STARTED.md` in the integration bundle for full server setup,
patient/device linking and migration steps. The gateway does not need an MQTT broker.

Use the dashboard base URL (without `/api/v1`), a scoped device token, the watch device
ID, and a fresh study ID matching its dashboard assignment. The watch displays its device
ID and the phone also detects it from incoming batches. Register it in the dashboard,
issue a token, and link it to a patient. On the phone press Refresh patient link, Use linked
study ID, then Save + send to watch. The cached patient name and lookup time are shown.
The name is sent to the watch only when the study IDs match.

A study-ID change flushes old sensor windows and stops monitoring. Check the new patient
and start monitoring manually. Finish/close a cognition test before a patient handoff:
its acquisition study ID is pinned to the session and intentionally does not change.
Queued events retain their original IDs/timestamps and are never relabelled by a preference change.

Upload acknowledgements are validated before clearing pending events. A redirect, HTML
login page, malformed response or incomplete acknowledgement leaves the queue intact.
The original Python research server remains usable: its explicit `ok`, `inserted` and
`duplicates` response is also recognized. Patient-name lookup requires the new dashboard API.

This is source code, not an APK. Build both modules in Android Studio with SDK 36 and
matching signing keys. The project's earlier permission, PPG, round-screen layout,
queue and cognition fixes are retained. The optional Samsung SDK/privileges are still
needed for Samsung-only IBI/PPG/temperature; their absence is not silently simulated.
No trained delirium model is embedded on the phone or watch.
