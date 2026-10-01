# Installation and first-device test

## 1. Requirements

- Android Studio Quail 4 / 2026.1.4 or later
- JDK 17 or newer
- Android SDK Platform 36 and Build Tools 36.x
- Galaxy Watch4 or newer, paired to an Android phone
- Python 3.11+ on the laptop
- Tailscale on the phone and laptop if the server is not on the same LAN

The repository contains Gradle wrapper configuration for Gradle 9.6.0. The binary wrapper JAR is not redistributed in this generated bundle. Android Studio can configure Gradle, or run `scripts/bootstrap_gradle.sh`/`.ps1` from an environment that already has Gradle installed.

## 2. Optional Samsung SDK

To obtain IBI/status, raw green/red/IR PPG, and skin temperature:

1. Download Samsung Health Sensor SDK v1.4.1 or compatible later SDK from Samsung Developer.
2. Copy `samsung-health-sensor-api.aar` to `watch/libs/`.
3. Enable Health Sensor Service developer mode on the development watch as documented by Samsung.

Do not redistribute Samsung's AAR unless its licence permits you to do so.

Without the AAR the app still supports Android linear acceleration and heart rate where exposed by the device.

## 3. Build and install

Open the repository in Android Studio and let Gradle sync. Select the `watch` run configuration and install it on the watch. Select `mobile` and install it on the paired phone. Use the same signing key for both modules.

On first launch, grant the requested activity/health permissions. Android 16/Wear OS 6 uses granular health permissions; older Wear OS versions use legacy body-sensor permissions.

## 4. Start the server

Linux/macOS:

```bash
cd server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export DELIRIUMWATCH_TOKEN='choose-a-long-random-token'
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Windows PowerShell:

```powershell
cd server
py -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
$env:DELIRIUMWATCH_TOKEN='choose-a-long-random-token'
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Open `http://localhost:8000` on the laptop.

## 5. Tailscale

Install Tailscale on the Android phone and laptop. The watch does not need Tailscale. Enter the laptop's tailnet URL in the phone app, for example:

`http://my-laptop:8000`

or a Tailscale IPv4 address such as:

`http://100.x.y.z:8000`

Traffic can be sent as ordinary HTTP inside the encrypted tailnet. Keep the API token enabled as an additional application-level control; when a token is configured, participant-data reads, exports, writes and WebSocket access all require it. Do not expose the HTTP listener directly to the public internet; use Tailscale/LAN isolation or terminate HTTPS in front of it.

## 6. First test

1. On phone: set Study ID to something pseudonymous such as `PILOT-001`.
2. Set server URL/token and tap **Save + send to watch**. Enter the same token in the dashboard header and click **Use token**.
3. On watch: tap **Start monitoring**.
4. Move the watch and verify movement/HR events appear on the dashboard.
5. Run **Cognitive check** and verify cognition summaries/trials arrive.
6. Disconnect the phone for several minutes; reconnect and verify queued data uploads.
7. Only after basic data integrity is confirmed should you enable raw acceleration/PPG transport.

## 7. Samsung partner approval

Samsung documents that applications using Health Sensor SDK work under developer mode for development/testing. Public distribution requires the Samsung partner process and registration of package name/signing certificate.

## 8. Battery-sensitive options

Start with raw accelerometer and raw PPG **off**. Continuous PPG also defaults off. Enable PPG only if the study protocol needs it; green-only uses fewer optical channels than green/red/IR. Measure battery life on the exact watch model and firmware before participant deployment.

## 9. Study timezone

The server uses `DELIRIUMWATCH_TZ` for descriptive day/night movement features and defaults to `Australia/Melbourne`. Set it to the deployment site's IANA timezone if different. This setting does not alter stored timestamps, which remain Unix milliseconds.
