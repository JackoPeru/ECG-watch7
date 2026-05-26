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

## SDK Note
Place Samsung's official `samsung-health-sensor-api.aar` at:

```text
wear/libs/samsung-health-sensor-api.aar
```

The project compiles without the AAR. Real watch ECG/BP sensor capture requires the AAR plus Health Platform developer mode or Samsung partner approval.
