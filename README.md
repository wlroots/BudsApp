# BudsApp

A native Android app for controlling **Nothing** and **CMF** earbuds and
headphones over Bluetooth Classic (RFCOMM/SPP). It is an independent, free
software alternative to the Nothing X app.

BudsApp talks the same vendor protocol as Nothing X and exposes the per-model
capability table used by [BudsLink](https://github.com/maniacx/BudsLink) and
[kalm](https://github.com/p4radigma/kalm).

## Features

- **Battery** — left, right, case or single-battery models, including charging state
- **Noise control** — Off / Transparency / Noise cancellation, plus per-model
  cancellation levels (Low / Mid / High / Adaptive) and personalized ANC
- **Equalizer** — model presets and a 3-band custom EQ (bass / mid / treble, ±6 dB)
- **Enhanced bass** — enable switch and level slider on supported models
- **Sound features** — low latency mode, spatial audio, in-ear detection
- **Find my buds** — ring left/right/single and an ear-tip fit test
- **Multipoint** — enable dual connection and connect/disconnect known devices
- **Gestures** — view and change the action assigned to each touch gesture
- **Device information** — model, firmware version, serial number and address
- **Diagnostics** — live protocol log with copy/clear, useful for bug reports
- **Demo mode** — explore every screen without earbuds connected

The UI is built with XML layouts, Material 3 components and view binding. State
is exposed by a single repository as Kotlin `StateFlow`, so the screens stay in
sync with notifications from the earbuds.

## Requirements

- Android 5.0 (API 21) or newer; tested build configuration targets API 30
  (required for the legacy Bluetooth permission model)
- Bluetooth Classic, paired Nothing/CMF earbuds or headphones
- Nothing X must not be connected to the earbuds at the same time; the vendor
  SPP service only accepts one controlling app

No location permission is needed: BudsApp only lists already-paired devices.

## Building

BudsApp uses Gradle 8.2, AGP 8.2.2 and Kotlin 1.9.22, and requires JDK 17.

1. Point Gradle at your Android SDK. Either set `ANDROID_HOME`, or create
   `local.properties` in the project root:

   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

2. Build and run the unit tests:

   ```bash
   ./gradlew assembleDebug testDebugUnitTest
   ```

3. The debug APK is written to
   `app/build/outputs/apk/debug/app-debug.apk`.

Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Usage

1. Pair the earbuds in Android Bluetooth settings.
2. Open BudsApp and tap the earbuds in the list.
3. Grant the connection; the notification keeps the session alive while the app
   is in the background.

Use **Show all paired devices** if a model name is not recognised, and
**Demo mode** to explore the UI without hardware. **Diagnostics** shows the raw
protocol exchange.

## Architecture

| Package | Responsibility |
| --- | --- |
| `protocol` | CRC-16/MODBUS, frame encoder/decoder, command IDs, payload parsers, custom-EQ float codec |
| `model` | Device capability table (`assets/models.json`), `BudsState`, `StateFlow` model |
| `bt` | RFCOMM transport, serialized request queue/session, repository, foreground service |
| `ui` | Device list, device controls, diagnostics screens |

The protocol frame is:

```
55 60 01 | command LE16 | payload length LE16 | seq | payload | CRC-16 LE
```

Requests are serialized with a short response timeout (~120 ms), matching the
reference implementation. The decoder is incremental, validates CRCs and
resynchronizes on garbage.

## Device catalog

`app/src/main/assets/models.json` is generated from BudsLink's per-model
JavaScript configs, so the 22 supported models share the same capability flags,
ANC byte values, EQ presets, gesture mappings and default gestures.

To regenerate it (requires Node.js and a local BudsLink checkout):

```bash
node tools/convert-configs.mjs /path/to/BudsLink/src/lib/devices/nothingBuds/deviceConfigs
```

Models without a catalog entry can still be tried with a generic Nothing/CMF
fallback configuration.

## Known limitations

- Spatial **head tracking** and immersive modes are exposed as capability
  information only; the reference implementations do not document their
  commands.
- Auto power-off values are read from the catalog but no command is known.
- Firmware updates are not implemented.
- The app is verified by unit tests and a successful build in this repository;
  on-device behaviour depends on the earbuds model and firmware.

## License

BudsApp is free software under the **GNU General Public License v3.0**. See
[`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) for attribution to BudsLink and
kalm, from which the protocol knowledge and device tables were ported.

Nothing and CMF are trademarks of their respective owners. This project is not
affiliated with Nothing Technology Ltd.
