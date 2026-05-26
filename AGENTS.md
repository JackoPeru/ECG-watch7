# Galaxy Watch7 ECG/BP Project

## Purpose
Build a personal Android + Wear OS system for Galaxy Watch7 that records ECG raw data and supports blood-pressure research workflows without requiring a Samsung phone.

## Hard Constraints
- Use public Samsung SDKs only.
- Do not reverse engineer, patch, clone signatures, or bypass Samsung Health Monitor controls.
- ECG data can come from Samsung Health Sensor SDK on the watch.
- Blood pressure is research/wellness only unless a validated, approved medical algorithm is later added.
- Keep data local by default; no cloud sync in v1.
- Personal/dev-mode sideload target, not store distribution.

## Current Architecture
- `:wear`: Wear OS app for sensor capture and watch UI.
- `:mobile`: Android phone companion for local storage, charts, calibration, export.
- `:shared`: Shared models, codecs, commands, and research BP estimator.
- Gradle wrapper is included; Java 17 and Android SDK API 36 are the verified local build base.
- Internal updater checks GitHub Releases for `JackoPeru/ECG-watch7`, downloads `mobile`/`wear` APK assets, then opens Android installer. No silent install.
- For personal update chain, create release assets from this same machine or a stable signing keystore; Android rejects APKs signed with a different cert.
- Wear Data Layer communication requires phone/watch apps to share applicationId and signing cert. From v0.1.1 both APKs use `com.galaxywatch7.health.mobile`; old watch package `com.galaxywatch7.health.wear` must be uninstalled once.
- Watch UI targets Galaxy Watch7 44mm round 480x480 display: dark compact cards, short labels, no dense phone layout.
- Watch logs are written as Data Layer DataItems so they queue offline and show on the phone when connection returns.
- ECG access reality: app avoids Samsung phone/Samsung Health Monitor, but public raw ECG still requires Samsung Health Sensor SDK AAR and the Samsung watch-side Health Platform service. No standard Wear OS SensorManager ECG fallback exists.
- Raw ECG processing is now local: `EcgAnalyzer` handles baseline correction, QRS/R-peak detection, HR, RR, and signal quality from raw `ECG_MV` samples.
- Samsung Health Sensor SDK v1.4.1 AAR has been placed locally at `wear/libs/samsung-health-sensor-api.aar`; it is ignored by git but included in local APK builds/releases.
- `SDK_POLICY_ERROR` means Health Platform developer mode is not enabled or package/signature is not partner-registered. On watch: Settings > Apps > Health Platform > tap title about 10 times until `[Dev mode]`.
- The app must not claim ECG recording started until raw samples arrive. If Samsung policy blocks ECG, no zero-sample session is saved. BP research can fall back to Android's public `TYPE_HEART_RATE` sensor when Samsung HR tracker is blocked, but there is no public Android ECG/raw PPG fallback.
- v0.1.9 adds watch-side policy diagnostics: app package/signing SHA-256, Health Platform package version, supported Samsung trackers, and public Android sensor scan. These logs are intended to confirm whether Samsung approval/dev mode is the remaining blocker.

## SDK Note
Place Samsung's official `samsung-health-sensor-api.aar` at:

```text
wear/libs/samsung-health-sensor-api.aar
```

The project compiles without the AAR. Real watch ECG/BP sensor capture requires the AAR plus Health Platform developer mode or Samsung partner approval.
