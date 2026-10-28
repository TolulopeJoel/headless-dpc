#!/usr/bin/env python3
"""Read Field Service (Ministry Assistant) backups: AutoBackup.mabackup is gzip + Smile (binary JSON, Jackson).

Usage: fieldservice.py BACKUP.mabackup [--dump]      prints the people (or the whole tree with --dump)
"""
import gzip
import json
import struct
import sys


class Many(list):
    """A key that appeared more than once in one object."""


def many(v):
    """Always a list: one value, or the repeats."""
    return list(v) if isinstance(v, Many) else ([] if v is None else [v])


class Smile:
    """A small Smile decoder: enough for Jackson's output (shared names, no shared values, no raw binary)."""

    def __init__(self, data):
        assert data[:3] == b":)\n", "not Smile"
        flags = data[3]
        self.shared_names = bool(flags & 1)
        self.shared_values = bool(flags & 2)
        self.d, self.i = data, 4
        self.names, self.values = [], []

    def byte(self):
        b = self.d[self.i]; self.i += 1; return b

    def vint(self):
        v = 0
        while True:
            b = self.byte()
            if b & 0x80:
                return (v << 6) | (b & 0x3F)
            v = (v << 7) | b

    @staticmethod
    def zz(n):
        return (n >> 1) ^ -(n & 1)

    def until_fc(self):
        j = self.d.index(0xFC, self.i); s = self.d[self.i:j]; self.i = j + 1; return s

    def text(self, n):
        s = self.d[self.i:self.i + n].decode("utf-8", "replace"); self.i += n; return s

    def remember_value(self, s):
        if self.shared_values and len(s.encode()) <= 64:
            if len(self.values) >= 1024: self.values = []
            self.values.append(s)
        return s

    def key(self):
        b = self.byte()
        if b == 0xFB: return None
        if b == 0x20: return ""
        if 0x30 <= b <= 0x33: return self.names[((b & 3) << 8) | self.byte()]
        if 0x40 <= b <= 0x7F: return self.names[b & 0x3F]
        if b == 0x34: s = self.until_fc().decode("utf-8", "replace")
        elif 0x80 <= b <= 0xBF: s = self.text((b & 0x3F) + 1)
        elif 0xC0 <= b <= 0xF7: s = self.text((b & 0x3F) + 2)
        else: raise ValueError(f"bad key byte {b:#x} at {self.i - 1}")
        if self.shared_names and len(s.encode()) <= 64:
            if len(self.names) >= 1024: self.names = []
            self.names.append(s)
        return s

    def value(self):
        b = self.byte()
        if b < 0x20: return self.values[b]
        if b == 0x20: return ""
        if b == 0x21: return None
        if b == 0x22: return False
        if b == 0x23: return True
        if b in (0x24, 0x25): return self.zz(self.vint())
        if b == 0x26:  # BigInteger: length + 7-bit bytes
            n = self.vint(); raw = self.seven(n); return int.from_bytes(raw, "big", signed=True)
        if b == 0x28: return struct.unpack(">f", self.seven(4))[0]
        if b == 0x29: return struct.unpack(">d", self.seven(8))[0]
        if b == 0x2A:
            scale = self.zz(self.vint()); n = self.vint(); raw = self.seven(n)
            return int.from_bytes(raw, "big", signed=True) / (10 ** scale)
        if 0x40 <= b <= 0x5F: return self.remember_value(self.text((b & 0x1F) + 1))
        if 0x60 <= b <= 0x7F: return self.remember_value(self.text((b & 0x1F) + 33))
        if 0x80 <= b <= 0x9F: return self.remember_value(self.text((b & 0x1F) + 2))
        if 0xA0 <= b <= 0xBF: return self.remember_value(self.text((b & 0x1F) + 34))
        if 0xC0 <= b <= 0xDF: return self.zz(b & 0x1F)
        if b in (0xE0, 0xE4): return self.until_fc().decode("utf-8", "replace")
        if b == 0xE8: n = self.vint(); return self.seven(n).hex()
        if 0xEC <= b <= 0xEF: return self.values[((b & 3) << 8) | self.byte()]
        if b == 0xF8:
            out = []
            while self.d[self.i] != 0xF9: out.append(self.value())
            self.i += 1; return out
        if b == 0xFA:
            out = {}
            while True:
                k = self.key()
                if k is None: return out
                v = self.value()
                # Repeated keys (the format came from XML: one "P" per person) become a list.
                if k in out:
                    if not isinstance(out[k], Many): out[k] = Many([out[k]])
                    out[k].append(v)
                else:
                    out[k] = v
        raise ValueError(f"bad value byte {b:#x} at {self.i - 1}")

    def seven(self, n):
        """n bytes packed 7 bits per byte (the last group right-aligned)."""
        groups = (n * 8 + 6) // 7
        bits = 0
        for _ in range(groups): bits = (bits << 7) | (self.byte() & 0x7F)
        extra = groups * 7 - n * 8
        return (bits >> extra).to_bytes(n, "big") if extra >= 0 else bits.to_bytes(n, "big")


def load(path):
    raw = open(path, "rb").read()
    if raw[:2] == b"\x1f\x8b": raw = gzip.decompress(raw)
    return Smile(raw).value()


if __name__ == "__main__":
    tree = load(sys.argv[1])
    print(json.dumps(tree, ensure_ascii=False, indent=1, default=str)[:6000] if "--dump" in sys.argv else list(tree))


# ── People for the DPC ────────────────────────────────────────────────────────

import math
import re
import struct as _struct

HOME = (6.53205, 3.34726)
SUFFIX = re.compile(r"\b(street|st|road|rd|close|cl|avenue|ave|crescent|cres|lane|ln|way|drive|dr)\b\.?", re.I)


def norm(name):
    """'Disu St' and 'Disu Street' → 'disu'."""
    return re.sub(r"[^a-z]", "", SUFFIX.sub("", name.lower()))


def lev(a, b):
    if abs(len(a) - len(b)) > 1: return 9
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def roads(path):
    """The DPC's roads.bin: {normalised name: [polyline, …]}."""
    d = open(path, "rb").read(); i = 0
    def take(fmt):
        nonlocal i; v = _struct.unpack_from(fmt, d, i); i += _struct.calcsize(fmt); return v
    assert take(">i")[0] == 0x524F4144
    names = []
    for _ in range(take(">i")[0]):
        n = take(">H")[0]; names.append(d[i:i + n].decode("utf-8")); i += n
    out = {}
    for _ in range(take(">i")[0]):
        ni, cls, n, la, lo = take(">hbhii"); pts = [(la / 1e5, lo / 1e5)]
        for _ in range(n - 1):
            a, b = take(">hh"); la += a; lo += b; pts.append((la / 1e5, lo / 1e5))
        if ni >= 0: out.setdefault(norm(names[ni]), []).append(pts)
    return out


def metres(a, b):
    return math.hypot((a[0] - b[0]) * 111320, (a[1] - b[1]) * 111320 * math.cos(math.radians(a[0])))


def street_lines(addr, lines):
    key = norm(addr or "")
    if len(key) < 3: return []
    if key in lines: return lines[key]
    near = [k for k in lines if len(key) >= 5 and lev(key, k) <= 1]
    return lines[near[0]] if len(near) == 1 else []


def people(tree, roads_bin):
    """Each person the phone should know: name, study or return visit, street, where (a pin, or the street's lines),
    last visit, visit count and the latest "next time" note."""
    lines = roads(roads_bin)
    out = []
    for item in many(tree["PersonMap"]["Pe"]):
        p = item["P"]
        if p.get("Hidden"): continue
        visits = sorted(many((p.get("Inf") or {}).get("PI")), key=lambda v: int(v.get("D") or 0))
        street = street_lines(p.get("Add"), lines)
        pin = None
        if p.get("Lat") and p.get("Lon"):
            pt = (p["Lat"] / 1e6, p["Lon"] / 1e6)
            # A pin counts only when it's in the map's square and, if the street is known, on or near it.
            on_street = not street or min(metres(pt, q) for line in street for q in line) < 250
            if metres(pt, HOME) < 3500 and on_street: pin = pt
        where = [[round(pin[0], 6), round(pin[1], 6)]] if pin else [[[round(a, 5), round(b, 5)] for a, b in line] for line in street]
        if not where: continue
        last = visits[-1] if visits else {}
        nxt = next((v.get("Nx") for v in reversed(visits) if v.get("Nx") and len(str(v.get("Nx"))) > 6), "")
        out.append({
            "name": p.get("N", "").strip(), "kind": "study" if p.get("Type") in (2, 3) else "rv",
            "street": (p.get("Add") or "").strip(), "pin": bool(pin), "where": where,
            "last": int(last.get("D") or 0), "visits": len(visits), "next": str(nxt)[:400],
        })
    return out


# ── Sync: phone → laptop → phone (run by the laptop program while the phone is plugged in) ──

ADB_CANDIDATES = ["/Users/tolu/Library/Android/platform-tools/adb", "/Users/tolu/platform-tools/adb", "adb"]
BACKUP = "/sdcard/Android/data/com.lostpixels.fieldservice/files/AutoBackup.mabackup"


def sync(roads_bin, last_stamp=None, workdir="/tmp"):
    """If the phone is connected and Field Service has a newer automatic backup than `last_stamp`, read it and give
    the DPC its people file. Returns the backup's stamp (or last_stamp when nothing changed / no phone)."""
    import os, shutil, subprocess
    adb = next((a for a in ADB_CANDIDATES if shutil.which(a) or os.path.exists(a)), None)
    if not adb: return last_stamp
    run = lambda *a: subprocess.run([adb, *a], capture_output=True, text=True, timeout=60)
    if run("get-state").stdout.strip() != "device": return last_stamp
    stamp = run("shell", "stat", "-c", "%Y", BACKUP).stdout.strip()
    if not stamp.isdigit() or stamp == last_stamp: return last_stamp
    local = os.path.join(workdir, "fs-autobackup.mabackup")
    if run("pull", BACKUP, local).returncode != 0: return last_stamp
    out = os.path.join(workdir, "people.json")
    json.dump(people(load(local), roads_bin), open(out, "w"), ensure_ascii=False)
    os.remove(local)
    run("push", out, "/data/local/tmp/people.json")
    run("shell", "chmod 644 /data/local/tmp/people.json && run-as com.tolu.dpc sh -c 'cat /data/local/tmp/people.json > files/people.json' && rm /data/local/tmp/people.json")
    os.remove(out)
    return stamp
