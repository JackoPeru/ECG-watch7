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

## Safety Language
All BP and ECG screens must identify data as wellness/research information only. Results must not claim detection, diagnosis, treatment, or medical-grade accuracy.

## Development Setup
- Java 17.
- Android SDK API 36 installed.
- ADB available at `C:\Users\Matteo\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- Gradle wrapper included in project after setup.

