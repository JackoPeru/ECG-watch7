# Galaxy Watch7 ECG/BP App Specification

## Goal
Create a native Android + Wear OS app pair for personal use:

- Watch app records ECG raw samples from Galaxy Watch7.
- Phone app stores sessions locally, renders charts, manages cuff calibration, and exports data.
- Blood pressure feature is a non-diagnostic research estimate using watch HR/PPG-derived metrics plus cuff calibration.

## Non-goals
- No Samsung Health Monitor reverse engineering.
- No medical diagnosis.
- No public store release in v1.
- No cloud sync in v1.

## Functional v1
- Wear app checks Samsung Health Sensor SDK availability.
- Wear app records 30 second ECG sessions when `ECG_ON_DEMAND` is supported.
- Wear app rejects/flags bad electrode contact.
- Wear app transfers ECG sample binary as Wear Data Layer asset.
- Phone app stores metadata and sample data in encrypted app-local files.
- Phone app allows cuff calibration entry.
- Phone app computes BP estimate only when valid calibration exists.
- Phone app exports CSV and PDF into app documents directory.
- Mobile and wear apps check GitHub Releases, download matching APK assets, and open the platform installer for updates.

## Hermes Agent Integration
- Blocked medical channels (Samsung ECG raw and official BP) stay out of scope unless Samsung grants package/signature access.
- Watch app can package public Wear OS/SensorManager data into a Hermes snapshot: live HR/steps/motion/light where available, sensor catalog, permission state, app/device metadata, and blocked-source markers.
- Phone app stores the latest watch snapshot, adds local calibration/session/log context, and POSTs `hermes.health.packet.v1` JSON to a user-configured Hermes server URL with optional bearer token.
- Future sources should prefer official APIs: Health Connect on phone for data written by Samsung Health/other apps, Wear OS Health Services for passive/exercise metrics, Samsung Health Data SDK only with partner/dev authorization.

## Safety Language
All BP and ECG screens must identify data as wellness/research information only. Results must not claim detection, diagnosis, treatment, or medical-grade accuracy.

## Update System
- Latest release endpoint: `https://api.github.com/repos/JackoPeru/ECG-watch7/releases/latest`.
- Mobile asset names must include `mobile` and end in `.apk`.
- Wear asset names must include `wear` and end in `.apk`.
- Android requires same package id and same APK signing certificate for upgrades.
- Wear Data Layer requires same package id and signing certificate across phone and watch apps; both APKs use `com.galaxywatch7.health.mobile` from v0.1.1.
- Install is user-confirmed; no silent update in v1.

## Development Setup
- Java 17.
- Android SDK API 36 installed.
- ADB available at `C:\Users\Matteo\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- Gradle wrapper included in project after setup.
