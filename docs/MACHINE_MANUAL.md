# Popcorn Vending Machine: Reconstructed Manual

Built from the vendor's own Chinese protocol documents, found inside the machine's app (`com.yuchen.popcorn`), plus the backup. **No maker, model or official manual was found.** *Revised after reading the machine's own logs: the vendor document's command table turned out to be wrong for this machine, so the coil table in section 5 was then checked against the vendor's own code.* Items marked **(unconfirmed)** are my inference or the vendor documents contradict themselves. Verify at the machine.

> **Safety:** the machine has a heater, a fan, motors and mains power. Unplug it before opening. Don't send test commands (heat, fan, motors) while hands or tools are inside.

## 1. How it works

- **Android board** (cloud name `AM9D-200`) runs the vendor app and talks over a serial port to the **controller board**.
- **Controller board** drives the heater, fan, feed motors A and B, cup dropper, cup carriage, infrared cup sensor, and the coin and bill acceptors. It stores all settings and sales counters.
- **DWIN touchscreen** is driven over the same Modbus-style protocol.
- Two flavors, **A** and **B**. Popcorn is air-popped, then blown out into a cup.
- Everything below the Android layer works even if the app is replaced.

## 2. Settings (registers)

Function `03` reads, `10` writes. 2 bytes per register. "Time" units are 0.1 s.

| Reg | Name | Unit | Default | Meaning |
|---|---|---|---|---|
| 0x00 | Flags | bits | | Status bits (section 3) |
| 0x01 | Progress | % | | Current job progress |
| 0x02 | Fan speed now | rpm | | Live fan speed |
| 0x03 | Heater temp now | °C | | Live heating plate temperature |
| 0x04 | Coin balance now | | | Credit currently inserted (the vendor labeled this °C by mistake) |
| 0x05 | Error bits | bits | | Section 4 |
| 0x06 / 0x07 | 0x08 | Preset temp | °C | 130 | Heater target |
| 0x09 | Feed temp | °C | 190 | Temperature at which ingredients are dropped |
| 0x0A | Bill acceptor multiplier | | 100 | Scales bill pulses to credit |
| 0x0B | Coin acceptor multiplier | | 50 | Scales coin pulses to credit |
| 0x0C / 0x0D | Feed A / B | | | Feed motor setting. Your machine reads 20 / 20, so the vendor's small-number examples were right |
| 0x0E / 0x0F | Popping fan speed A / B | rpm | 2000 | Fan speed while making each flavor |
| 0x10 / 0x11 | Popping time A / B | 0.1 s | | Your machine reads 1200 (120 s); a real cycle observed ~100 s of popping |
| 0x12 | Preheat time | 0.1 s | 1000 | 1000 means 100.0 s |
| 0x13 | Settings password | | 888 | Needed to change settings |
| 0x14 | Flags 2 | bits | | Upper status bits (read only) |
| 0x15 | Init flag | | 0 | Low byte 0xAA means initialized |
| 0x17 | Blow-out fan speed | rpm | 2000 | Fan speed that blows popcorn out after popping |
| 0x18 | Cup drop stop time | 0.1 s | 286 | 28.6 s |
| 0x19 / 0x1A | Price A / B | | | Credit required. **Your controller reads 0 / 0, which is where "free" is set** |
| 0x1B | Machine ID | | | Device number |
| 0x1C | Manufacture date | | | Year and month |
| 0x1D | Payment requested | | | Amount requested |
| 0x1E / 0x1F | Bill / coin total | | | Cumulative money counters |
| 0x20 | EEPROM check | | 0xAA55 | Memory validity code. **Don't change.** |

## 2b. Values read from one controller

Defaults in the table above are the vendor's template. These are the real values, read by the app about every 1.3 s:

| Setting | Real value |
|---|---|
| Reg 0x08 / 0x09 ("preset" / "feed" temp) | 140 / 140 |
| Bill / coin multiplier | 100 / 50 |
| Feed A / B | 20 / 20 |
| Popping fan A / B | 6200 / 6200 rpm |
| Popping time A / B | 1200 / 1200 (120 s) |
| Preheat time | 200 (20 s) |
| Settings password (0x13) | (vendor default is a 4-digit code; change it) |
| Blow-out fan | 18000 rpm |
| Cup drop stop time | 35 (3.5 s) |
| Price A / B | 0 / 0 (free) |
| Date code (0x1C) | probably YYMM |

**One observed vend (flavor B):** about 2 min 55 s total. Heat-up from 70 to ~188 °C took ~45 s. The plate then held ~187-190 °C while popping, well above the "140" settings, so those two register names in the vendor document are unreliable. Popping fan ran 6,000-6,800 rpm, then ~20,000 rpm for ~8 s to blow the corn out, then the heater switched off and the cycle ended.

**Status flags:** bits 11-14 are always set (reads `0x78xx`), so ignore bits 11 and up. Seen in a vend: 0x7821 started, +0x40 cup detected, +0x80 heating, +0x100 fan, 0x7904 then 0x7804 done (bit 2 = success).

## 3. Status bits (function 01, read)

| Bit | Meaning when set |
|---|---|
| 0 | Not ready |
| 1 | Fault present |
| 2 | Last vend succeeded |
| 3 | Already paid |
| 4 | Cups present in tube |
| 5 | Vending in progress |
| 6 | Cup detected by infrared |
| 7 | Heating |
| 8 | Fan running |
| 9 | Feed motor running |
| 10 | Arm / carriage moving |
| 11 | Screen has read the QR URL |
| 12 / 13 / 14 | QR URL present for A / B / refill |
| 15 | Refund flag |

Ready to vend: bit 0 clear, bit 1 clear, bit 4 set.

## 4. Error bits (register 0x05)

| Bit | Error | Things to check first |
|---|---|---|
| 0 | General error | Unknown fault; the specific bits below may be clear |
| 1 | Heater timeout | Heating plate never reached target: element, thermistor, wiring |
| 2 | Fan error | Fan or fan wiring |
| 3 | Feed error | Kernel hopper empty or jammed, feed motor |
| 4 | Network timeout | Cloud link; **irrelevant for a free personal machine (unconfirmed)** |
| 5 | Out of cups | Refill the cup tube |
| 6 | Cup did not drop | Jam, or infrared sensor dirty or misaligned |
| 7 | Fan speed mismatch | Fan speed sensor disagrees with commanded speed |

Clear a fault with the Repair command (below) after fixing the cause. (My cause list is general guidance, not from the vendor.)

## 5. Commands (function 05, bit write)

Coil addresses below are decimal and come from the vendor app's own Test screen code. Make B (17) was also confirmed from the machine's log.

| Coil | Action |
|---|---|
| 16 (0x10) | Make flavor A |
| 17 (0x11) | Make flavor B (log-confirmed: `00 05 00 11 FF 00 DD EE`) |
| 19 | Fan stop (write off) |
| 20 | Heater |
| 21 | Fan start |
| 22 | Drop cup |
| 23 / 24 | Fan accelerate / decelerate |
| 25 / 26 | Dispense A / B |
| 28 | Continuous making |
| 29 | Repair (clear faults) |
| 0x0B / 0x0C (value 0) | "Finished" acknowledgement sent after a vend, A / B |

Heater, fan and motor coils are hazardous: keep hands out and use the Tests screen supervised. The replacement app auto-stops the heater after 2 minutes.

**Vendor setting names:** registers 0x0C / 0x0D are the vendor's "feeding setting" for A / B, which is the **stuff amount** (portion). Register 0x09 is the "stuff temp" (feeding temperature).

## 6. Serial protocol

- **Port: `/dev/ttyS1`, 115200 baud** (from the log line "open: /dev/ttyS1 115200"; I assume 8N1). Device address `00`.
- The vendor app polls "read all registers" every ~1.3 s: `00 03 00 00 00 1D` plus CRC (29 registers, reply 63 bytes).
- **CRC is standard Modbus CRC-16, low byte first.** It reproduced the logged frames exactly.
- A separate port, `/dev/ttyUSB0`, is for MDB payment hardware; all MDB options are switched off.

```
Read all registers:  00 03 00 00 00 1D 84 12
Read error register: 00 03 00 05 00 01 95 DA
Make flavor B:       00 05 00 11 FF 00 DD EE
```

```python
def crc(b):
    c = 0xFFFF
    for x in b:
        c ^= x
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return bytes([c & 255, c >> 8])
```

## 7. Touchscreen (DWIN)

- Display firmware and graphics are loaded from an SD card made on a PC.
- **Format the SD card with the DOS command** `format x: /q /fs:fat32 /a:4096`. Windows' normal formatter produces cards that don't work.
- Create a folder named `DWIN_SET` on the card.
- Editing the screen needs DWIN's DGUS tool; the vendor's files for it were not in the backup.

## 8. Still to confirm

1. The flavor A make command, and what the other coil addresses do.
2. Whether the vendor app needs the vendor's server at startup. The log shows "authorization success" on each of six starts, and the machine was online.
3. Nameplate, model and maker.
4. Contents of the SD card.

**Backup file password:** the same number as the controller's settings password.
