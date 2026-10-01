# DeliriumWatch repair release — 0.1.2 (project package V1.2)

Based on the uploaded DeliriumWatchV1.1.zip, reviewed on 1 October 2026.

## Install the update

1. Extract this ZIP to a new folder. Open its **DeliriumWatch** folder in Android Studio.
2. Let Gradle sync. Use Android Studio's supported bundled Gradle JDK (Java 17 or newer); SDK 36 is required.
3. Build and run **mobile** on the phone, then **watch** on the Galaxy Watch. Both apps must be updated because durable transfer receipts are new.
4. Keep the same signing key as your existing installation to retain queued data and settings. Android Studio's normal debug key should do this on the same Ubuntu account. Avoid uninstalling or clearing app data while records remain queued.
5. Open the phone app and press **Save + send to watch**. On the watch press **Start monitoring**, respond to the foreground permission prompts, and allow physical activity and/or body sensors. Missing heart-rate permission does not prevent movement-only recording.
6. To enable background body-sensor access on supported watches, use **Sensor permissions** again after granting foreground sensors. Android may require changing the app's permissions in Settings. This permission is requested separately, never together with the initial sensor request.
7. Move your arm, scroll through the watch status, try **Cognitive check**, then **Send queued data**. Data is marked sent only after the phone acknowledges committing the batch.

Command-line checks from the project root on Ubuntu:

```bash
chmod +x gradlew
./gradlew :mobile:assembleDebug :watch:assembleDebug :watch:testDebugUnitTest
cd server
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python -m pytest tests -q
```

## Findings and fixes

| Area | Finding | Change |
|---|---|---|
| Phone launch | A persisted JobScheduler job was scheduled without RECEIVE_BOOT_COMPLETED. Android throws SecurityException on this path. | Added the required manifest permission; scheduling failures are also surfaced without terminating the activity. |
| Watch Start | Sensor permissions were requested asynchronously and the health foreground service started immediately; heart rate registration was unconditional. | Start waits for permission results and checks the foreground prerequisites; heart rate registration is gated and denied access is handled. |
| Watch display | Main screen padding used raw pixels; full-width rows extended into the circular edge. Cognition controls also exceeded the available width. | Added density-aware sizing, round-screen insets and scrollable content. Cognition uses smaller stimuli and a compact SAME/OTHER response row. |
| PPG | Missing statuses were counted as normal, invalid readings entered summaries, and windows could grow indefinitely after a timestamp reset. The precise reported compiler error was not supplied. | Reworked the PPG processor, explicit nullable JSON serialization, quality-qualified summaries, timestamp reset handling and bounded windows. The replacement compiles against SDK 36. |
| Cognition | Answering early left a timeout scheduled; it could finish a later trial and log the wrong response. Wall-clock changes could distort reaction times. | Cancel old timeouts and guard them with a trial generation token; use elapsed realtime for RT. Interrupted sessions are logged. |
| Watch transfer | sendMessage success was used as proof of durable phone storage. A phone crash before insertion could lose the watch's only pending copy. | Added batch receipts after a transactional phone save; no receipt leaves data queued for idempotent retry. |
| Study assignment | The phone retroactively replaced UNASSIGNED study IDs with its current ID. Configuration changes could also relabel partially collected windows/sessions. | Preserve acquisition IDs, flush sensor windows before applying configuration, and pin cognition records to the session's original ID. |
| Sensor consistency | Samsung acceleration (including gravity) could replace Android linear acceleration inside the same movement feature stream. | Movement consistently uses Android linear acceleration or a gravity-filtered Android accelerometer fallback; Samsung remains optional for HR/IBI/PPG/temperature. |
| Samsung bridge | Reflection proxies returned null for Object methods, and delayed callbacks could outlive stopped trackers. Some reflection results assumed non-null values. | Implement identity equals/hashCode/toString, lifecycle generations, synchronized tracker lifecycle and null-safe reflection. |
| Sensor quality | Unsuccessful Samsung HR/temperature measurements could enter numeric summaries; Samsung HR failure could permanently suppress Android fallback. | Store unavailable numeric readings as null; use Android fallback after a Samsung HR gap; filter nonfinite Android samples. |
| Screen-off collection | Foreground service alone did not keep the CPU awake for non-wakeup movement sensors. | Acquire a bounded partial wake lock while monitoring, renew during queue maintenance, release on stop. This increases battery use; measure endurance on your watch. |
| Service lifecycle | Sending queued data unnecessarily started a health service. Boot/config receivers could try to create health services from the background. | Transfer directly; reconfigure an existing service rather than create one in a message receiver. After reboot, open the app and explicitly resume monitoring. |
| Upload lifecycle | HTTP connections/database helpers/executor jobs were not consistently closed or cancelled. | Close connections/helpers/statements, serialize uploads, cancel stopped jobs and shut down executors. |
| Configuration | Invalid URLs, NaN thresholds and malformed prompt times could be saved. | Validate phone inputs, clamp finite thresholds, and report failed configuration delivery. |
| Prompt timing | Fixed 24-hour repeating alarms drifted from local clock times across DST. Notifications could be logged as presented when disabled. | Recalculate each next local-time prompt after an alarm; log notification_blocked when notifications are disabled. Prompts remain inexact. |
| Server HRV | RMSSD joined values across invalid/missing beat statuses and long recording gaps. | Require recorded normal IBI status and do not form differences across invalid beats or long gaps. |
| Server validation | Whitespace-only study IDs and nested NaN/Infinity payload values could be accepted. | Validate stripped IDs and recursively reject nonfinite numbers. |
| Build setup | Gradle wrapper binary/scripts were missing; daemon criteria forced a JDK 25 download. | Included the official Gradle 9.6 wrapper JAR and scripts, kept the distribution checksum, removed the forced toolchain. |

## Verification performed here

- All Kotlin production sources for **both modules** compiled with Kotlin 2.2 against Google's actual **Android SDK 36 stubs** and the project's declared Play Services libraries.
- Both modules' resources compiled and manifests linked with **Android Build Tools 36 aapt2**.
- **8/8** watch JVM regression tests passed: missing/invalid PPG quality, unused channels, JSON nulls, bounded/reset windows, movement calculations and stale/interrupted cognition trials.
- **9/9** server tests passed: existing authentication, UUID deduplication, export, baseline and movement behavior plus new HRV and input-validation regressions. Tests now use independent temporary databases and a real server lifespan.
- XML, Python syntax, dashboard JavaScript syntax, archive contents and wrapper checksums checked.

A full Gradle build was attempted, but Gradle itself fails during environment initialization with **java.io.IOException: Mount point not found**, before project configuration. Therefore these are source-compilation/resource-link checks, **not verified APK assembly or instrumented Android runtime tests**. No APK is included. There is no connected phone/watch here, so first-launch crashes, permission dialogs, circular layout appearance, screen-off endurance and optional Samsung SDK behavior still require the device checks above. The exact PPG error and crash stacks were not provided, so device-specific additional failures cannot be ruled out.

If a crash remains, capture it immediately after reproducing it:

```bash
adb -s DEVICE_SERIAL logcat -d -v threadtime AndroidRuntime:E DeliriumWatch:E '*:S'
```

## Reference documentation checked

- Android JobInfo.Builder.setPersisted: https://developer.android.com/reference/android/app/job/JobInfo.Builder#setPersisted(boolean)
- Health foreground service prerequisites: https://developer.android.com/develop/background-work/services/fgs/service-types#health
- Samsung HR and IBI status definitions: https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.HeartRateSet.html
- Samsung PPG status definitions: https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.PpgSet.html
- Samsung temperature status definitions: https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.SkinTemperatureSet.html
