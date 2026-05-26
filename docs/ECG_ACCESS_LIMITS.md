# ECG Access Limits

## What This App Avoids
- No Samsung phone requirement.
- No Samsung Health Monitor phone app requirement.
- No Samsung Health Monitor reverse engineering.

## What Cannot Be Removed With Public APIs
Samsung exposes raw ECG from Galaxy Watch through Samsung Health Sensor SDK. Samsung's official docs state that the SDK provides watch sensor data through Health Platform and that the SDK works with the Health Platform app/service on Galaxy Watch.

That means the phone companion can replace the Samsung phone-side workflow, but public ECG sensor access on the watch still needs:

- Samsung Health Sensor SDK AAR bundled in the watch APK.
- Compatible Health Platform service on the watch.
- Required sensor permissions.
- Developer mode or partner approval, depending on Samsung's SDK access rules.

There is no Android `SensorManager` ECG sensor API available for Galaxy Watch ECG electrodes in the public Wear OS APIs. Without Samsung's sensor SDK/service, the app can only show a clear error and log it to the phone.

Practical consequence:

- The phone app is the replacement for the Samsung phone companion/manager.
- The watch app still needs Samsung's sensor SDK library to talk to the physical ECG electrodes.
- If the APK was built without `samsung-health-sensor-api.aar`, ECG fails before measurement starts.
