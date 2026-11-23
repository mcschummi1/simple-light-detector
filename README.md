# Daylight Logger (Galaxy J3 2017 dedicated)

A camera-based daylight logger for an old **Samsung Galaxy J3 (2017)** (no ambient
light sensor, Camera2 `LIMITED` hardware level). The app never controls the camera
manually — it lets **auto-exposure** decide and simply **reads back** the chosen
`SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY` (ISO) and aperture, then computes:

```
EV100 = log2(N^2 / t) - log2(ISO / 100)
lux   = k * 2^EV100          (k is the calibration constant, default 2.5)
ppfd  = lux / 54             (daylight approximation, umol/m2/s)
DLI   = sum(ppfd * interval_s) / 1,000,000   (mol/m2/day, today so far)
```

## Features

- **Main screen**: live camera display (hideable, for aiming the phone), big lux
  reading, EV100, PPFD (µmol/m²/s), running **Daily Light Integral for today**,
  current k, and a line chart of today's lux (log scale, drawn on a Canvas).
- **Gear icon** (top-right corner) → Settings: sample interval, manual k,
  **device mode**, keep-screen-on switch, camera details, Export CSV, Clear data.
- **Calibration**: put the phone next to a reference lux meter (or calibrated app),
  type the reference lux → app stores `k = reference_lux / 2^EV100`. Several
  points can be stored; their **average** is used.
- **24+ hour logging**: foreground service with persistent notification and a
  partial wake lock. The camera reopens automatically if it is disconnected.
- **CSV log**: one ISO-8601-stamped CSV per day in app storage:

  `timestamp, exposure_time_s, iso, aperture, ev100, lux, ppfd, saturated`

  The `saturated` column is `true` for samples taken in direct sun where the
  tiny J3 sensor maxed out - those rows underestimate the real light level.

  "Export CSV" shares today's file through the system share sheet.
  "Clear data" (in Settings and on the main screen) deletes everything after a
  confirmation dialog.
- Readings shown on screen update live every second straight from the camera
  frames; CSV samples average ~10–16 frames (dropping 25% outlier frames) and
  the first ~10 frames after opening are skipped so auto-exposure can settle.

## Device modes (dedicated + optional compatibility)

The app is **dedicated to the Galaxy J3 (2017)** and auto-detects it
(`SM-J330*` models, device `j3y17lte`): fixed f/2.2 aperture fallback, J3-tuned
saturation warning and frame averaging. On the gear icon → Settings → **Device
mode** you can force it:

- **Auto (detect)** – default, picks J3 or generic automatically
- **Galaxy J3 (2017) – dedicated** – the J3 profile even on other hardware
- **Other device – compatibility** – optional mode so the same algorithm works
  on other phones (aperture falls back to the camera's first available aperture
  or a safe default)

## Accuracy notes

Readings are **approximate (about ±30%)** — a phone lens is not a real lux meter.
**Direct sun will saturate the sensor.** Lay the phone flat, **face up**, with the
back camera aimed straight up at the sky, or at a white sheet of paper for
reflected light.

## Build

1. Install **Android Studio** (JDK 17 comes bundled).
2. Open Android Studio → `File → Open…` → select this project folder.
3. Let Gradle sync (it downloads Gradle 8.7 automatically; if the wrapper jar
   is missing, run `gradle wrapper --gradle-version 8.7` in the project folder
   or accept Android Studio's suggestion to re-create it).
4. `Build → Make Project` (or `./gradlew assembleDebug`).
5. Enable **USB debugging** on the J3, plug it in, press ▶ Run.

## Install / first run on the phone

1. Accept the **Camera** permission (and the notification permission on
   Android 13+ when asked).
2. Optional: press **Show camera preview** to aim, then hide it again.
3. Press **Start logging** → a persistent "Logging daylight samples…"
   notification appears. Press **Stop logging** when done.
4. Calibrate once in **⚙ Settings → Calibration** using a known lux value.
5. Use **Export CSV** to share the data (WhatsApp, Drive, email, …).

## Releases, versions and updating

- Version is **v0.1.0** (`versionName "0.1.0"`, `versionCode 1`).
- GitHub Actions builds a debug APK on every push (see the **CI** tab) and
  publishes a GitHub **Release** with the APK whenever a `vX.Y.Z` tag is pushed.
- Updating: install the new APK over the old one — no uninstall needed — as
  long as the `applicationId` is unchanged and `versionCode` was bumped.
- To cut a release: bump `versionCode` (and `versionName`) in
  `app/build.gradle`, commit, then `git tag vX.Y.Z && git push origin vX.Y.Z`.

## Project layout

```
app/src/main/java/com/mcschummi/daylightlogger/
  MainActivity.kt        main screen: camera display, readings, chart, gear icon
  SettingsActivity.kt    gear icon screen (interval, k, device mode, export, clear)
  CalibrationActivity.kt k = reference_lux / 2^EV100, multiple points, average
  LoggingService.kt      foreground service, notification, partial wake lock
  CameraEngine.kt        Camera2: auto-exposure only, reads back what it chose
  LightMath.kt           the EV100 / lux / PPFD / DLI formulas
  LogState.kt            shared in-memory readings + today's chart history
  CsvStore.kt            per-day CSV files + share/export/clear
  DeviceProfile.kt       Galaxy J3 (2017) detection + device mode resolution
  LuxChartView.kt        simple Canvas line chart (log lux scale)
```

Single module, no third-party libraries (chart drawn with Canvas).
