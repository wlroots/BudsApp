# BudsApp

> [!WARNING]
> **Beta software.** BudsApp was written with the assistance of a large language model (LLM). It builds, passes its automated tests, and has been used with a Galaxy A6 (2018) running AOSP 11 and CMF Buds 2 — but it is not a finished, commercially supported product. Expect rough edges, and use it at your own risk. Please report anything that looks wrong.

BudsApp is a small, free Android app for controlling **Nothing** and **CMF** earbuds and headphones. It speaks the same Bluetooth Classic (RFCOMM/SPP) protocol as the vendor app, so you can adjust your earbuds without installing Nothing X.

> BudsApp is an independent project and is **not** affiliated with, endorsed by, or supported by Nothing Technology Ltd. Nothing and CMF are trademarks of their respective owners.

## What you can do

- See battery levels for the left bud, right bud and case (or a single battery on headphones/neckbands), including charging state
- Switch between Off, Transparency and Noise Cancellation, with per-model levels such as Low / Mid / High / Adaptive
- Pick an EQ preset, or fine-tune bass, mid and treble in a custom 3-band EQ
- Turn enhanced bass on/off and set its level where the model supports it
- Toggle low latency mode, spatial audio and in-ear detection where supported
- Ring a lost bud (left, right or both) and run the ear-tip fit test
- Enable multipoint and connect or disconnect remembered devices
- View and change what each touch gesture does
- See the model, firmware version, serial number and Bluetooth address
- Open the built-in diagnostics log when something misbehaves
- Explore the whole interface in **Demo mode** without any earbuds connected

The interface is Material 3, works in light and dark themes, and supports Android 5.0 (API 21) and newer.

## Before you start

1. **Pair your earbuds in Android's Bluetooth settings first.** BudsApp only lists devices you have already paired, so it does not need location permission.
2. **Close Nothing X** (and force-stop it if necessary). The earbuds' SPP service accepts one controlling app at a time; if another app is connected, BudsApp cannot connect.
3. Make sure Bluetooth is on. BudsApp needs Bluetooth Classic, not just BLE.

A notification keeps the connection alive while the app is in the background. You can disconnect from the device screen or from the notification.

## Installing

Every commit is built automatically by the **Build** GitHub Actions workflow. On the workflow run page, download the `budsapp-debug` artifact, unzip it, and install `app-debug.apk`.

You can also build it yourself — see [Building from source](#building-from-source). Android must be allowed to install apps from unknown sources (outside the Play Store).

## Using BudsApp

1. Open BudsApp and tap your earbuds in the list. Recognised models show their model name; unknown paired devices can be shown with **Show all paired devices** in the menu.
2. Wait a moment for the earbuds to report their state. The screen fills in as data arrives.
3. Use the cards to change settings. Changes are written to the earbuds immediately.

The menu also offers:

| Menu item | What it does |
| --- | --- |
| Show all paired devices | Include paired devices that do not match a known Nothing/CMF name |
| Auto-reconnect | Reconnect to the last used device when the app starts or the link drops |
| Demo mode | Open a simulated device so you can look around without hardware |
| Diagnostics | View, copy or clear the raw protocol log — useful when reporting a bug |
| Bluetooth settings | Jump to Android's Bluetooth settings |

Gesture changes are saved as soon as you pick an action. If a gesture supports cycling noise-control modes, a second dropdown appears for choosing the exact cycle.

## Privacy

- The app declares **no INTERNET permission** — there is no account, analytics, telemetry or network access.
- It only uses Bluetooth to talk to devices you paired yourself, plus a foreground service notification to keep that connection alive.
- It does not ask for location, contacts, storage or camera access.
- The diagnostics log is kept in memory and only leaves the app if you copy it yourself.

## Limitations and troubleshooting

- This is **beta** software, written with LLM assistance. It is not a complete replacement for Nothing X; firmware updates, for example, are not implemented.
- The app has a capability table for 22 Nothing/CMF models. Unknown devices are tried with a generic configuration, but some features may not work. If your model is missing or misdetected, please report it.
- Spatial **head tracking**, immersive audio modes and auto-power-off values are shown as supported capabilities, but the reference implementations do not document their commands, so BudsApp cannot toggle them.
- Battery values marked `--` simply mean the earbuds did not report that battery.
- **It will not connect while Nothing X is connected.** Close or force-stop Nothing X, then reopen BudsApp.
- If connecting repeatedly fails: toggle Bluetooth off/on, restart the earbuds, forget and re-pair them in Android settings, and check Diagnostics for the exchange.
- If the app crashes, open Diagnostics after restarting and copy the log. An `AndroidRuntime` stack trace in logcat is also useful (`adb logcat -v threadtime | grep -i fatal`).

## Building from source

Requirements: JDK 17, the Android SDK (platform 34 / build-tools 34), and the Android Gradle plugin dependencies downloaded by Gradle.

Create `local.properties` in the project root (it is gitignored):

```properties
sdk.dir=/path/to/Android/Sdk
```

Then:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`app/src/main/assets/models.json` is generated from BudsLink's device configs with `tools/convert-configs.mjs`; you do not need to regenerate it to build the app.

## License and credits

BudsApp is free software under the **GNU General Public License v3.0**. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

The protocol and per-model capability tables were ported from two GPL-3.0 projects:

- [BudsLink](https://github.com/maniacx/BudsLink) — protocol framing, command set and device configs
- [kalm](https://github.com/p4radigma/kalm) — protocol constants and capability semantics

This application was written with the assistance of a large language model. It is beta software and comes with no warranty; see the license for details.
