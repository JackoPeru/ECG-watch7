# Raw ECG Analysis

The app processes ECG raw samples locally after the watch sends `ECG_MV` values.

## Input
- Sample rate: 500 Hz.
- Unit: millivolts.
- Lead contact: Samsung `LEAD_OFF`; value `5` means no electrode contact and samples are ignored on the watch before transfer.

## Current Algorithm
- Baseline wander reduction with moving-average subtraction.
- Light smoothing.
- QRS enhancement using derivative, squaring, and moving-window integration.
- Adaptive threshold peak detection with 250 ms refractory period.
- R-peak refinement on the filtered ECG waveform.
- RR interval, mean RR, heart rate, RR standard deviation, and signal-quality score.

This is inspired by the classic Pan-Tompkins QRS detector family and later mobile ECG practice. It is deliberately limited to wellness/research feedback and is not an atrial-fibrillation classifier or diagnostic engine.

## Output
- R-peak count.
- Estimated heart rate.
- Mean RR interval.
- Signal quality.
- Human-readable status.

## Medical Boundary
The app does not diagnose arrhythmia, ischemia, atrial fibrillation, or any medical condition. It only gives local research metrics from raw ECG once sensor data exists.

