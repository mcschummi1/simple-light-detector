# Changelog

All notable changes to DaylightLogger. Newer versions install **over** the old
app without uninstalling (versionCode always increases). Going back to an
older version needs a quick uninstall first. APKs live on the
[Releases page](https://github.com/mcschummi1/simple-light-detector/releases);
they can also be opened in-app via **⚙ Settings → Update log / older versions**.

## v0.3.0 — highly precise predictive loop + weather context (in development)

- **Open-Meteo weather integration** (free, no API key): hourly cloud cover
  (%), UV index and temperature for Timișoara (45.7489, 21.2087), matched to
  each sample's hour.
- **Resilient fetching** on Kotlin Coroutines (`Dispatchers.IO` — the camera
  loop is never blocked): exponential backoff 30 s → 1 m → 2 m → 4 m → 8 m
  (max 5 attempts), then it waits for the network; a connectivity callback
  triggers an **immediate fetch** when Wi-Fi returns.
- **CSV fallback**: while offline the weather columns hold the last known
  values, or `Pending` before the first successful fetch.
- **Exact CSV schema**:
  `timestamp, exposure_time_s, iso, aperture, ev100, lux, ppfd, calibration_k, cloud_cover_pct, uv_index, weather_temp_c, capture_status`
  with `capture_status` = `Normal` | `Auto-Mode` | `Forced-Save`.
- **In-app update log** with notes and download buttons for every older
  release (⚙ Settings → Update log / older versions).
- Camera reopen-on-disconnect, partial wake lock, screen stays off: unchanged.

## v0.2.0 — predictive 10-second state machine

- Capture cycles aligned to **exact round clock times** (12:00:00, 12:00:10…).
- **Hybrid auto-manual exposure**: tries `CONTROL_AE_MODE_OFF` with manual
  `SENSOR_EXPOSURE_TIME` + `SENSOR_SENSITIVITY`; per-frame verification of the
  reported values against the requested ones; if the driver silently ignores
  manual control, falls back to reading AE and flags the row `Auto-Mode`.
- **Histogram dialer**: 64-bin luma histogram checks blown highlights /
  crushed shadows and dials the exposure in half-EV steps (prefers moving
  exposure time, then ISO); dials once per 2 s max.
- **Predictive headstart**: dialing runs during the idle part of the cycle, so
  at the exact mark the save lands and the next capture starts with the
  pre-calculated settings, zero delay.
- **Overtime safety net**: up to +20 s when the camera struggles, then a
  force-save flagged `Forced-Save` and a re-sync to the next round clock time.

## v0.1.0 — first release

- EV100 / lux / PPFD / DLI camera-based daylight logging for the Galaxy J3
  (2017), pure auto-exposure read-back (Camera2, no manual sensor control).
- Live camera display, view check (open-sky % vs. dark wall), center AE
  metering when supported, foreground service + partial wake lock, camera
  auto-reopen on disconnect, per-day CSV, share-sheet export, clear data,
  calibration points (average k), canvas lux chart, dedicated J3 profile with
  optional generic-device compatibility mode.
