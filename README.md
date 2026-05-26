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

## Internal Updates
Both apps can check GitHub Releases, download their matching APK asset, and open Android's installer.

Expected release assets:

```text
Watch7Health-mobile-X.Y.Z.apk
Watch7Health-wear-X.Y.Z.apk
```

Create a release from this machine:

```powershell
.\scripts\create-github-release.ps1 -Tag v0.1.1 -Notes "Release notes"
```

Android still requires user confirmation for APK install. APK signatures must match the currently installed app.

Communication note: Wear Data Layer requires phone and watch APKs to share package id and signing certificate. From `v0.1.1`, both APKs use `com.galaxywatch7.health.mobile`. If you installed the old watch `v0.1.0`, uninstall `com.galaxywatch7.health.wear` once before installing the new watch APK.

ECG note: this app replaces the Samsung phone companion/manager role, but raw ECG electrodes live on the watch and are exposed publicly only through Samsung Health Sensor SDK. Local builds now use `wear/libs/samsung-health-sensor-api.aar` when present. See [docs/ECG_ACCESS_LIMITS.md](docs/ECG_ACCESS_LIMITS.md).

Check SDK locally:

```powershell
.\scripts\check-samsung-sensor-sdk.ps1
```

Raw ECG analysis is implemented locally in `shared` and displayed on the phone after a watch session arrives. See [docs/ECG_RAW_ANALYSIS.md](docs/ECG_RAW_ANALYSIS.md).

If the watch logs `SDK_POLICY_ERROR`, enable **Health Platform** developer mode on the watch: `Settings > Apps > Health Platform`, then tap the `Health Platform` title about 10 times until `[Dev mode]` appears. This is different from Android Developer options.

From v0.1.8, ECG sessions are saved only after real raw samples arrive. If Samsung policy blocks ECG, the app shows the policy block and does not create a zero-sample ECG. BP research can fall back to Android's public heart-rate sensor, but public Android APIs do not expose Galaxy Watch ECG electrodes or raw PPG.

## Safety
This app is wellness/research only. It does not diagnose, treat, or replace clinical ECG/BP tools.
