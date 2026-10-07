#!/usr/bin/env python3
"""Fake popcorn-machine controller board.

Speaks the vendor's Modbus-RTU-style protocol (functions 01, 03, 05, 10) over a TCP
server and/or a pseudo-serial port, simulates a vend cycle, and lets you inject faults.
Behavior is built from the vendor docs; timing and fault reactions are my assumptions.

  python3 fake_controller.py                 # TCP on 127.0.0.1:7777, 10x speed
  python3 fake_controller.py --pty           # also create a pseudo-serial port (Linux/macOS)
  python3 fake_controller.py --speed 1       # real-time vend cycle
Console: help | status | fault <name> | clear | cups N | credit N | quit
"""
import argparse, os, select, socket, sys, threading, time

# status bits (function 01)
NOT_READY, FAULT, OUT_OK, PAID, EXIST_CUP, OUTING, INFRA, HEATING, WINDING, CORNING, ARMING = range(11)
# error bits (register 0x05)
ERRORS = {"general": 0, "heat": 1, "fan": 2, "corn": 3, "comm": 4, "lackcup": 5, "fallcup": 6, "wind": 7}
DEFAULTS = {0x06: 34, 0x07: 70, 0x08: 140, 0x09: 140, 0x0A: 100, 0x0B: 50, 0x0C: 20, 0x0D: 20,
            0x0E: 6200, 0x0F: 6200, 0x10: 1200, 0x11: 1200, 0x12: 200, 0x13: 0, 0x15: 0,
            0x17: 18000, 0x18: 35, 0x19: 0, 0x1A: 0, 0x1B: 0, 0x1C: 2604}   # read from the real controller
COILS = {19: 'wind stop', 20: 'heater', 21: 'wind start', 22: 'drop cup', 23: 'wind accel', 24: 'wind decel', 25: 'dispense A', 26: 'dispense B', 28: 'continuous making', 29: 'repair', 0x11: 'MAKE B (confirmed from machine log)', 0x10: 'make A (assumed, unconfirmed)',
         0x0C: 'B finished ack (confirmed)', 0x0B: 'A finished ack (assumed)'}


def crc16(b):
    c = 0xFFFF
    for x in b:
        c ^= x
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return c


def with_crc(b):
    c = crc16(b)
    return bytes(b) + bytes([c & 255, c >> 8])


def hexs(b):
    return " ".join("%02X" % x for x in b)


class Machine:
    def __init__(self, speed):
        self.speed, self.lock = speed, threading.RLock()
        self.regs = {i: 0 for i in range(0x21)}
        self.regs.update(DEFAULTS)
        self.flags, self.err, self.cups, self.temp = 0x7800, 0, 40, 25.0   # bits 11-14 are always set on the real machine
        self.job = None            # dict(flavor, t, phases)
        self.manual_heat = self.manual_fan = self.continuous = False
        self.take_at = None
        self.refresh()

    # ----- state helpers
    def bit(self, n, on):
        self.flags = (self.flags | 1 << n) if on else (self.flags & ~(1 << n))

    def refresh(self):
        self.bit(EXIST_CUP, self.cups > 0)
        self.bit(FAULT, self.err != 0)
        self.bit(NOT_READY, self.job is not None or self.err != 0 or self.cups == 0)
        self.bit(OUTING, self.job is not None)
        r = self.regs
        r[0], r[3], r[5], r[0x14] = self.flags, int(self.temp), self.err, 0
        if self.job is None:
            r[1] = 0 if not self.flags >> OUT_OK & 1 else 100
            r[2] = 3000 if self.manual_fan else 0

    def set_fault(self, name):
        with self.lock:
            self.err |= 1 << ERRORS[name]
            if name != "general":
                self.err |= 1
            self.job = None
            self.bit(HEATING, False); self.bit(WINDING, False); self.bit(CORNING, False); self.bit(ARMING, False)
            self.refresh()

    def clear_faults(self):
        with self.lock:
            self.err = 0
            self.refresh()

    # ----- vend cycle
    def start_job(self, flavor):
        pop = self.regs[0x10 if flavor == "A" else 0x11] / 10.0
        pre = self.regs[0x12] / 10.0
        phases = [("preheat", 45.0), ("cup", 3.0), ("feed", 2.0), ("pop", pop), ("blow", 8.0)]
        self.job = dict(flavor=flavor, t=0.0, phases=[(n, d / self.speed) for n, d in phases])
        self.bit(OUT_OK, False); self.bit(INFRA, False)
        self.refresh()

    def tick(self, dt):
        with self.lock:
            heating = self.manual_heat
            if self.job:
                j = self.job
                j["t"] += dt
                total = sum(d for _, d in j["phases"])
                acc, name = 0.0, "done"
                for n, d in j["phases"]:
                    if j["t"] < acc + d:
                        name = n; break
                    acc += d
                self.regs[1] = min(100, int(100 * j["t"] / total))
                self.bit(HEATING, name in ("preheat", "feed", "pop"))
                self.bit(ARMING, name == "cup")
                self.bit(CORNING, name == "feed")
                self.bit(WINDING, name in ("pop", "blow"))
                heating = heating or name in ("preheat", "feed", "pop")
                key = 0x0E if j["flavor"] == "A" else 0x0F
                self.regs[2] = self.regs[key] if name == "pop" else self.regs[0x17] if name == "blow" else 0
                if name == "done":
                    self.cups -= 1
                    self.regs[6 if j["flavor"] == "A" else 7] += 1
                    self.bit(OUT_OK, True); self.bit(INFRA, True)
                    for b in (HEATING, WINDING, CORNING, ARMING):
                        self.bit(b, False)
                    self.job, self.take_at = None, time.time() + 5 / self.speed
                    if self.continuous and self.cups > 0:
                        self.start_job(j["flavor"])
            else:
                self.bit(HEATING, self.manual_heat)
                self.bit(WINDING, self.manual_fan)
            if self.take_at and time.time() > self.take_at:   # customer takes the cup
                self.bit(INFRA, False); self.take_at = None
            target = 188 if heating else 25      # real plate holds ~187-190 C while popping
            rate = 6.0 * self.speed if heating else 1.5 * self.speed
            self.temp += max(-rate * dt, min(rate * dt, target - self.temp))
            self.refresh()

    # ----- protocol
    def coil(self, addr, on):
        with self.lock:
            if addr in (0x10, 0x11) and on:
                if self.flags >> NOT_READY & 1:
                    return "ignored (not ready)"
                self.start_job("A" if addr == 0x10 else "B")
            elif addr == 20:
                self.manual_heat = on
            elif addr in (21, 23, 24):
                self.manual_fan = True
            elif addr == 19:
                self.manual_fan = False
            elif addr == 22 and on:
                self.cups = max(0, self.cups - 1)
            elif addr == 28:
                self.continuous = on
            elif addr == 29 and on:
                self.err = 0
            self.refresh()
        return COILS.get(addr, "vendor-table coil (simulated)")

    def handle(self, f):
        """Return (response_bytes, description)."""
        a, fn = f[0], f[1]
        if fn == 3:
            s, n = f[2] << 8 | f[3], f[4] << 8 | f[5]
            with self.lock:
                data = b"".join(self.regs.get(s + i, 0).to_bytes(2, "big") for i in range(n))
            return with_crc(bytes([a, 3, len(data)]) + data), "read %d reg(s) from 0x%02X" % (n, s)
        if fn == 1:
            s, n = f[2] << 8 | f[3], f[4] << 8 | f[5]
            with self.lock:
                bits = [(self.flags >> (s + i)) & 1 if s + i < 16 else 0 for i in range(n)]
            out = bytearray((n + 7) // 8)
            for i, v in enumerate(bits):
                out[i // 8] |= v << (i % 8)
            return with_crc(bytes([a, 1, len(out)]) + bytes(out)), "read %d status bit(s) from %d" % (n, s)
        if fn == 5:
            addr, val = f[2] << 8 | f[3], f[4] << 8 | f[5]
            what = self.coil(addr, val == 0xFF00)
            return bytes(f), "coil 0x%02X %s -> %s" % (addr, "ON" if val == 0xFF00 else "OFF", what)
        if fn == 0x10:
            s, n = f[2] << 8 | f[3], f[4] << 8 | f[5]
            with self.lock:
                for i in range(n):
                    self.regs[s + i] = f[7 + 2 * i] << 8 | f[8 + 2 * i]
            return with_crc(bytes(f[:6])), "write %d reg(s) from 0x%02X" % (n, s)
        return None, "unsupported function"


def next_frame(buf):
    """Pull one valid frame off the front of buf; resync by dropping bytes on CRC failure."""
    while len(buf) >= 8:
        fn = buf[1]
        if fn in (1, 3, 5):
            n = 8
        elif fn == 0x10:
            if len(buf) < 7:
                break
            n = 9 + buf[6]
        else:
            del buf[0]; continue
        if len(buf) < n:
            break
        if crc16(buf[:n - 2]) == buf[n - 2] | buf[n - 1] << 8:
            fr = bytes(buf[:n]); del buf[:n]; return fr
        del buf[0]
    return None


def serve(m, recv, send, name, log):
    buf = bytearray()
    while True:
        d = recv()
        if not d:
            return
        buf += d
        while (fr := next_frame(buf)):
            resp, desc = m.handle(fr)
            print("[%s] RX %s  | %s" % (name, hexs(fr), desc)); log.write("RX %s %s\n" % (hexs(fr), desc)); log.flush()
            if resp:
                send(resp)


def tcp_thread(m, port, log):
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port)); srv.listen(1)
    print("TCP listening on 127.0.0.1:%d" % port)
    while True:
        c, _ = srv.accept(); print("[tcp] client connected")
        serve(m, lambda: c.recv(256), c.sendall, "tcp", log)
        print("[tcp] client disconnected")


def pty_thread(m, log):
    master, slave = os.openpty()
    print("Pseudo-serial port:", os.ttyname(slave))
    def recv():
        select.select([master], [], []); return os.read(master, 256)
    serve(m, recv, lambda b: os.write(master, b), "pty", log)


def show(m):
    with m.lock:
        on = [n for n, b in zip("NOT_READY FAULT OUT_OK PAID CUP OUTING INFRA HEAT FAN CORN ARM".split(), range(11)) if m.flags >> b & 1]
        errs = [n for n, b in ERRORS.items() if m.err >> b & 1]
        print("flags:", on or "-", "| errors:", errs or "-", "| temp %.0fC | cups %d | sold A=%d B=%d | progress %d%% | fan %d rpm" % (
            m.temp, m.cups, m.regs[6], m.regs[7], m.regs[1], m.regs[2]))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--tcp", type=int, default=7777); p.add_argument("--pty", action="store_true")
    p.add_argument("--speed", type=float, default=10.0); p.add_argument("--log", default="fake_controller.log")
    a = p.parse_args()
    m, log = Machine(a.speed), open(a.log, "a")
    threading.Thread(target=tcp_thread, args=(m, a.tcp, log), daemon=True).start()
    if a.pty:
        threading.Thread(target=pty_thread, args=(m, log), daemon=True).start()
    def loop():
        while True:
            time.sleep(0.1); m.tick(0.1)
    threading.Thread(target=loop, daemon=True).start()
    print("Type 'help' for console commands.")
    for line in sys.stdin:
        w = line.split()
        if not w: continue
        if w[0] == "quit": break
        elif w[0] == "status": show(m)
        elif w[0] == "fault" and len(w) > 1 and w[1] in ERRORS: m.set_fault(w[1]); show(m)
        elif w[0] == "clear": m.clear_faults(); show(m)
        elif w[0] == "cups" and len(w) > 1: m.cups = int(w[1]); m.refresh(); show(m)
        elif w[0] == "credit" and len(w) > 1: m.regs[4] = int(w[1])
        else: print("faults:", ", ".join(ERRORS), "\ncommands: status | fault <name> | clear | cups N | credit N | quit")


if __name__ == "__main__":
    main()
