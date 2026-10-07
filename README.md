# Free Popcorn

A replacement Android app for second-hand popcorn vending machines whose original vendor app (`com.yuchen.popcorn`) is no longer supported. Red and white kiosk UI, animated popping screen, full service menu, fault history, auto-start at power-on.

Built for one machine (Android 7.1 board, Modbus RTU controller on `/dev/ttyS1` at 115200 baud, two flavors A/B). **Your machine may differ. Use at your own risk.** The machine has a heater, fan and motors on mains power.

## Features
- Kiosk home app, starts at boot, stops the old vendor app
- Animated popping screen with learned progress timing
- Service menu (hold the title 1.5 s, then the PIN): all vendor settings including stuff amount, tests, fault history, out-of-service lockout, quick-jump buttons, 5-minute auto-close
- Error screens, idle attract videos, editable texts
- PC simulator (`tools/fake_controller.py`) so you can try it without a machine

- **Try the live demo:** https://schswork.github.io/Popcorn-vending-machine-modernized-app/ (also available offline as `docs/demo.html`)

## Docs
- [`docs/USER_MANUAL.md`](docs/USER_MANUAL.md): how to install, use and service the machine with this app
- [`docs/MACHINE_MANUAL.md`](docs/MACHINE_MANUAL.md): the reverse-engineered register and coil map
- Default service password is `0000` (you can change it in the service menu). Note: the password is stored in the machine's controller, so a machine that was already set up keeps its existing password until you change it.

## Building
1. Open this folder in Android Studio (SDK 34+; minSdk 23).
2. **Serial library:** the native `libserial_port.so` files are not included. Build them from the open-source [android-serialport-api](https://github.com/cepr/android-serialport-api) (Apache-2.0) and place them in `app/src/main/jniLibs/<abi>/` (`armeabi-v7a`, `x86`). The matching Java class is already in `app/src/main/java/android_serialport_api/`.
3. Build and install by USB/adb. Choose Free Popcorn as the home app (Always).

## Trying without a machine
Run `python tools/fake_controller.py`, set **Link** to `sim` in the service menu. The Android emulator reaches your PC at 10.0.2.2:7777.

## Status
Tested on one real machine. Parts are still untested there: stuff-amount writes, out-of-cups recovery, attract videos, kiosk lockdown, and the newest menu features. Issues and pull requests welcome. Not affiliated with any vendor.
