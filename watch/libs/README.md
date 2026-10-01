# Optional Samsung Health Sensor SDK

The project builds and runs without Samsung's proprietary AAR, using Android's linear-acceleration and heart-rate sensors.

For raw PPG, IBI/status data and skin temperature, download Samsung Health Sensor SDK v1.4.1 (or a compatible later version) from Samsung Developer and copy:

`samsung-health-sensor-api.aar`

into this directory. The watch module automatically packages any `*.aar` placed here. `SamsungHealthSensorBridge` uses runtime reflection so the source remains buildable without redistributing Samsung's SDK.

Samsung requires Health Sensor Service developer mode for development unless your app/package/signing certificate has been approved through the Samsung Health Sensor SDK partner process.
