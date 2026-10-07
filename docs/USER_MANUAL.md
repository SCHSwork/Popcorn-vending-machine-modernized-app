# Free Popcorn: User Manual

For owners and operators of a popcorn vending machine running the Free Popcorn app.

> **Safety first.** The machine has a heater, a fan, motors and mains power. Unplug it before opening it. Never run a test with hands or tools inside. Use the app at your own risk; it is not made by or affiliated with the machine's maker.

## 1. What customers see
1. **Ready screen:** the headline, the flavor buttons (A and B), and an optional location name and hot line.
2. **Pick a flavor:** the machine drops a cup and starts making popcorn.
3. **Popping screen:** kernels fall in, cook and pop while a progress meter fills. When the cup is done the popcorn drains away.
4. **Thank-you screen:** a check mark and your thank-you message, then back to the ready screen.
5. **Idle videos:** after a quiet period, videos from the machine's `popcron/adv` folder play full screen. A touch brings back the menu.

## 2. Installing
1. Build the APK (see the README) and install it by USB or `adb install`.
2. Open it once. When Android asks for a home app, choose **Free Popcorn** and **Always**.
3. Restart the machine. The app should start by itself and stop the old vendor app.
4. Make one supervised cup to check everything works.

## 3. Opening the service menu
1. **Hold the title** on the ready screen for about 1.5 seconds.
2. Enter the **service password**. The default is `0000`. The password is stored in the machine's controller, so a machine that was set up before keeps its old password until you change it (section 4.10).
3. Close with **Close** (drops unsaved changes) or **Save** (keeps them). The menu also closes by itself after 5 minutes without a touch (switch in the Screen section).

The row of buttons under the red bar jumps straight to a section: Lockout, Status, Faults, Flavors, Feeding, Time, Fan, Blow-out, Temp, Price, Tests, Screen, Videos, Connection, Password, Maintenance.

## 4. Service menu sections

### 4.1 Service lockout
Switch to put the machine **out of service**. Customers see a "Temporarily out of service" screen and cannot order. Use it while cleaning or repairing.

### 4.2 Work status
Live view: link state, plate temperature, fan speed, progress, status flags, errors and cups made (A and B).

### 4.3 Fault history
A list of recent faults with times, and a summary. Use it to spot repeating problems.

### 4.4 Flavors
Names, show/hide for each flavor button, and pictures (put `a.bmp` and `b.bmp`, or .png/.jpg, in the machine's `popcron/res` folder).

### 4.5 Machine settings
Each group has **Apply to machine** (asks for confirmation, writes, reads back and tells you if it did not stick) and **Restore as-found values**.

| Group | What it does |
|---|---|
| Feeding (stuff amount) | Kernels dropped per cup, per flavor. Higher is believed to be more kernels; test with "Dispense kernels only" and change in small steps. |
| Time | Popping time per flavor and boiler preheat, in tenths of a second. |
| Fan speed | Fan speed while popping, per flavor. |
| Blow-out | Fan speed and time used to blow the popcorn into the cup. |
| Temperature | Preset and feeding temperature. The limits are the app's own safe range. |
| Price and machine | Price per flavor (0 is free), machine number, date code. |
| Payment multipliers | Bill and coin settings. Not used while free. |

Change one value by a small step, make a cup and check it. After a time or feed change the progress meter relearns the cup length over the next cup or two.

### 4.6 Tests
These move real parts. Run them only with the machine open, watched and hands clear. **STOP ALL** turns off the heater, fan and continuous mode.
- Dispense kernels only (A or B), drop a cup, make a cup (A or B)
- Heater on (turns off after 2 minutes) and off
- Fan start, faster, slower, stop
- Continuous making on (turns off after 10 minutes) and off

Everything stops when you close the menu.

### 4.7 Screen
Headline, line under it, thank-you message and seconds, location name, hot line, floating-popcorn animation, service line at the bottom, and the 5-minute menu auto-close.

### 4.8 Attract videos
Turn videos on or off, sound, idle seconds (10 to 3600) and the video folder. Needs storage permission.

### 4.9 Connection
Serial port (default `/dev/ttyS1`), baud rate (115200), link type (`serial` for the real machine, `sim` for the PC simulator), whether to stop the old vendor app, and the vendor app's package name. Changes apply after **Save**.

### 4.10 Password
Change the service password. It is saved in the controller, 0 to 65535. Pick a new one before you leave a machine in public.

### 4.11 Maintenance
Reconnect to the controller, save and restart the app, or exit to Android (for updates).

## 5. Error screens
When the controller reports a problem, customers see a picture and a short message instead of the menu:
general fault, heater, fan, feed motor, communication, out of cups, cup did not drop, fan mismatch. Faults are logged in the fault history. Most clear on their own once fixed; refill cups for "out of cups".

## 6. Trying it without a machine
1. On a PC run `python tools/fake_controller.py` (TCP port 7777).
2. Set **Link** to `sim` in the service menu. The Android emulator reaches the PC at `10.0.2.2:7777`.
3. Type commands in the simulator window: `status`, `fault <general|heat|fan|corn|comm|lackcup|fallcup|wind>`, `clear`, `cups N`, `credit N`, `quit`.

## 7. Troubleshooting
| Problem | Try |
|---|---|
| App does not start at power-on | Check Free Popcorn is the home app; restart; check `RECEIVE_BOOT_COMPLETED` was not blocked. |
| Menu does not connect to the controller | Check the serial port and baud rate under Connection, then Reconnect. Make sure the old vendor app is closed. |
| Progress meter sticks near the end | Normal for a short moment; the app learns the real time after a cup or two. |
| A setting does not stick | The app reads each value back and says if it did not stick. Try a smaller change. |
| Forgot the password | Use the vendor tools or the controller's own reset to set register 0x13 again. |

The machine's fan runs briefly at power-on before Android starts. That is controller firmware and cannot be changed from this app.
