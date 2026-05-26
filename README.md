# GalaxyWatch7Health

Personal Android + Wear OS project for Galaxy Watch7 ECG capture and non-diagnostic BP research workflows.

## Modules
- `shared`: common models, ECG binary codec, JSON helpers, BP research estimator.
- `wear`: Wear OS app, Samsung Health Sensor SDK bridge, Wear Data Layer sender.
- `mobile`: Android phone app, encrypted local storage, charts, cuff calibration, CSV/PDF export.

## Samsung SDK
Download Samsung Health Sensor SDK from Samsung Developer and place the official AAR here:

```text
wear/libs/samsung-health-sensor-api.aar
```

Without that file the apps still build, but real ECG/HR tracker access will fail at runtime with an explicit status message.

For target API 36, the watch manifest requests Samsung's additional health data permission plus Android health heart-rate permission, matching Samsung Health Sensor SDK 1.4.x requirements.

## Build
```powershell
.\gradlew.bat :shared:testDebugUnitTest :mobile:assembleDebug :wear:assembleDebug --no-daemon
```

APK outputs:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
wear/build/outputs/apk/debug/wear-debug.apk
```

## Install
Use the local Android SDK ADB:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r mobile\build\outputs\apk\debug\mobile-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r wear\build\outputs\apk\debug\wear-debug.apk
```

Install the mobile APK on the phone and the wear APK on the watch.

## Safety
This app is wellness/research only. It does not diagnose, treat, or replace clinical ECG/BP tools.
