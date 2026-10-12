#!/usr/bin/env python3
"""
The weekly report of his life: ~/Documents/justme/weekly-report.html, made on Sunday evening (or by hand).

Where each number comes from:
  the plan      his calendars (Ministry, Study, Work, Business), and the typical week on days they didn't plan
  what happened the "Happened" calendar the phone writes (field, study, prep, banks, places; the day's numbers:
                fun-app minutes, JW Library, the Bible entry, when the phone went dark), WakaTime (time coding on work
                projects and on Kowane), and this laptop's own log (sessions, locks, Wi-Fi, sites in front)
The agency number: showing up (kept against planned), the unforceable (banks, study, meeting prep), choosing ahead
(days planned before they came), rest you chose (dark by 21:30), integrity (no bypasses). Unconfirmed rows from the
phone don't count. A part with no data that week is left out and the others share its weight.

Usage: weekly_report.py [YYYY-MM-DD] [--out FILE] [--dry]
  YYYY-MM-DD  any day in the week to report (default: this week; his weeks run Sunday to Saturday)
  --out       write somewhere else; --dry  don't add the week to the agency history
"""
import base64
import configparser
import json
import re
import sys
import urllib.request
from datetime import date, datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tolus_focus as tf  # noqa: E402

OUT = tf.HOME / "Documents/justme/weekly-report.html"
TEMPLATE = Path(__file__).resolve().parent / "report_template.html"
HISTORY = tf.HERE / "agency.json"
DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]     # his week: Sunday to Saturday
WEIGHTS = {"Showing up": 35, "Unforceable": 25, "Integrity": 15, "Choosing ahead": 15, "Rest you chose": 10}
BANKS_TARGET = 2                   # bank walk-ins a week that count as full marks
DARK_BY = "21:30"
# The phone's kinds (Happened descriptions) → the report's.
PHONE_KIND = {"FIELD": "Field", "STUDY": "Study", "PREP": "Prep", "BANK": "Kowane", "WORK": "Work", "HALL": "Hall",
              "ERRAND": "Other", "OTHER": "Other", "AWAY": "Other"}


# ── Reading ───────────────────────────────────────────────────────────────────

def events_of(cal_name, start, end):
    """Every event in one cached calendar between two datetimes: (title, start, end, description, all_day)."""
    import icalendar
    import recurring_ical_events
    path = tf.cache_of({"name": cal_name})
    if not path.exists():
        return []
    out = []
    for ev in recurring_ical_events.of(icalendar.Calendar.from_ical(path.read_bytes())).between(start, end):
        s, e = ev["DTSTART"].dt, ev["DTEND"].dt if "DTEND" in ev else None
        all_day = not isinstance(s, datetime)
        if all_day:
            s = datetime.combine(s, datetime.min.time())
            e = s + timedelta(days=1)
        s = s.astimezone().replace(tzinfo=None) if s.tzinfo else s
        e = (e.astimezone().replace(tzinfo=None) if getattr(e, "tzinfo", None) else e) or s + timedelta(hours=1)
        stamp = ev.get("LAST-MODIFIED") or ev.get("CREATED")
        out.append({"title": str(ev.get("SUMMARY", "")).strip(), "start": s, "end": e, "desc": str(ev.get("DESCRIPTION", "")),
                    "all_day": all_day, "uid": str(ev.get("UID")),
                    "edited": stamp.dt.timestamp() if stamp is not None and isinstance(stamp.dt, datetime) else None})
    return out


def plan(config, state, monday):
    """The week's plan: [(day index, kind, start, end, known before its day)], kinds Field/Study/Work/Kowane."""
    start, end = datetime.combine(monday, datetime.min.time()), datetime.combine(monday + timedelta(days=7), datetime.min.time())
    seen = state.get("first_seen", {})
    rows, off = [], set()
    for cal in tf.calendar_list(config):
        name = cal.get("name", "")
        if name.strip().lower() in tf.REPORT_ONLY:
            continue
        for ev in events_of(name, start, end):
            kind = tf.kind_of(config, name, ev["title"])
            if not kind:
                continue
            day = (ev["start"].date() - monday).days
            if tf.OFF_TITLE.match(ev["title"]):
                off.add((day, kind))
                continue
            key = f'{name}:{ev["uid"]}|{ev["start"].isoformat()}'
            known = tf.known_before_day(seen, (key, kind, ev["title"], ev["start"], ev["end"], ev["edited"]))
            rows.append((day, "Kowane" if kind == "Bank" else kind, ev["start"], ev["end"], known))
    # The typical week fills each kind a day didn't plan (and no "Off" freed).
    planned = {(d, k) for d, k, *_ in rows}
    for kind, weekday, a, b, _title in config.get("default_week", []):      # weekday: Monday is 0
        k = "Kowane" if kind == "Bank" else kind
        d = (weekday + 1) % 7                                                # Sunday is day 0 here
        if (d, k) not in planned and (d, kind) not in off:
            day = monday + timedelta(days=d)
            rows.append((d, k, tf.at(a, day), tf.at(b, day), False))
    # Nothing counts from before it all went live (12 Oct).
    live = config.get("active_from")
    if live:
        rows = [r for r in rows if r[2] >= datetime.fromisoformat(live)]
    return sorted(rows, key=lambda r: r[2])


def happened(monday):
    """The phone's week: (blocks, days). Blocks: (day, kind, start, end, status, late, title). Days: {day: numbers}."""
    start = datetime.combine(monday, datetime.min.time())
    blocks, days = [], {}
    for ev in events_of("Happened", start, start + timedelta(days=7)):
        day = (ev["start"].date() - monday).days
        if not 0 <= day < 7:
            continue
        if ev["all_day"] and ev["title"] == "Day":
            try:
                days[day] = json.loads(ev["desc"])
            except ValueError:
                pass
            continue
        fields = dict(re.findall(r"(\w+)=(\w+)", ev["desc"]))
        kind = PHONE_KIND.get(fields.get("kind", ""), "Other")
        blocks.append((day, kind, ev["start"], ev["end"], fields.get("status", "SURE"), fields.get("late") == "true", ev["title"]))
    return blocks, days


def wakatime(monday):
    """Coding time from WakaTime, by day: [(day, "Work"|"Kowane"|"Side", start, end)], or None if it can't be read."""
    cfg = configparser.ConfigParser()
    cfg.read(tf.HOME / ".wakatime.cfg")
    key = cfg.get("settings", "api_key", fallback=None)
    if not key:
        return None
    auth = base64.b64encode(key.encode()).decode()
    work = {p.name.lower() for p in (tf.HOME / "Documents/Projects/work").iterdir() if p.is_dir()} if (tf.HOME / "Documents/Projects/work").exists() else set()
    out = []
    for i in range(7):
        day = monday + timedelta(days=i)
        if day > date.today():
            break
        req = urllib.request.Request(f"https://wakatime.com/api/v1/users/current/durations?date={day.isoformat()}",
                                     headers={"Authorization": f"Basic {auth}"})
        try:
            data = json.loads(urllib.request.urlopen(req, timeout=20).read())["data"]
        except Exception as e:
            print(f"wakatime: {e}", file=sys.stderr)
            return None
        for d in data:
            p = (d.get("project") or "").lower()
            # A work project by its folder or a sub-project of it ("approvehr-backend" is approvehr's).
            cat = "Kowane" if p.startswith(("kowane", "bankops")) else "Work" if (p == "work" or any(p.startswith(w) for w in work)) else "Side"
            s = datetime.fromtimestamp(d["time"])
            out.append((i, cat, s, s + timedelta(seconds=d["duration"])))
    return merge(out, gap=timedelta(minutes=10))


def merge(spans, gap=timedelta(0)):
    """Join spans of the same day and kind that touch (or are `gap` apart)."""
    out = []
    for s in sorted(spans, key=lambda x: (x[0], x[1], x[2])):
        if out and out[-1][0] == s[0] and out[-1][1] == s[1] and s[2] - out[-1][3] <= gap:
            out[-1] = (s[0], s[1], out[-1][2], max(out[-1][3], s[3]))
        else:
            out.append(tuple(s[:4]))
    return out


def laptop_log(monday):
    events = [json.loads(l) for l in tf.LOG.read_text().splitlines()] if tf.LOG.exists() else []
    end = monday + timedelta(days=7)
    return [e for e in events if monday <= date.fromisoformat(e["at"][:10]) < end]


# ── Working it out ────────────────────────────────────────────────────────────

def hours(spans):
    return sum((b - a).total_seconds() for a, b in spans) / 3600


def overlap(a0, a1, spans):
    return sum(max(0.0, (min(a1, b1) - max(a0, b0)).total_seconds()) for b0, b1 in spans)


def make(day=None, out=OUT, dry=False):
    config, state = tf.load_config(), tf.load(tf.STATE, {})
    tf.fetch(config)
    today = day or date.today()
    # "monday" is the week's first day, a Sunday; "sunday" its last, a Saturday. (Named before his week was set.)
    monday = today - timedelta(days=(today.weekday() + 1) % 7)
    sunday = monday + timedelta(days=6)

    planned = plan(config, state, monday)
    blocks, phone_days = happened(monday)
    sure = [b for b in blocks if b[4] == "SURE"]
    code = wakatime(monday)
    log = laptop_log(monday)

    # What happened, as spans per kind (confirmed only).
    did = {k: [(b[2], b[3]) for b in sure if b[1] == k] for k in ("Field", "Study", "Prep", "Kowane", "Work")}
    did["Study"] += did["Prep"]                       # meeting prep keeps a Study session too
    if code:
        did["Work"] += [(a, b) for _, k, a, b in code if k == "Work"]
    building = [(d, a, b) for d, k, a, b in (code or []) if k == "Kowane"]

    # Showing up: planned minutes kept.
    # Only what something can see: field, study and banks need the phone's data, work needs WakaTime or the phone.
    phone_ok = bool(phone_days or blocks)
    seeable = ({"Field", "Study", "Kowane", "Work"} if phone_ok else set()) | ({"Work"} if code else set())
    kept_s = plan_s = 0.0
    for d, kind, a, b, _known in planned:
        if b <= datetime.now() and kind in seeable:
            plan_s += (b - a).total_seconds()
            kept_s += min((b - a).total_seconds(), overlap(a, b, did.get(kind, [])))
    showing = kept_s / plan_s if plan_s else None

    # The unforceable.
    bank_stops = [b for b in sure if b[1] == "Kowane"]
    study_planned = len([p for p in planned if p[1] == "Study" and p[3] <= datetime.now()])
    study_logged = len([b for b in sure if b[1] == "Study"])
    meetings = {m for b in sure if b[1] == "Prep" for m in ("Midweek", "Watchtower") if m in b[6]}
    parts_u = [min(1, len(bank_stops) / BANKS_TARGET), min(1, study_logged / study_planned) if study_planned else None, len(meetings) / 2]
    parts_u = [p for p in parts_u if p is not None] if phone_ok else []
    unforceable = sum(parts_u) / len(parts_u) if parts_u else None

    # Choosing ahead: days with something in the calendar before the day began.
    ahead_days = {d for d, _k, _a, _b, known in planned if known}
    choosing = len(ahead_days) / 7

    # Rest: nights dark by 21:30.
    nights = [(d, phone_days[d]["dark"], phone_days[d]["light"]) for d in sorted(phone_days) if phone_days[d].get("dark")]
    dark_ok = sum(1 for _, dark, _ in nights if tf.at(dark, monday) <= tf.at(DARK_BY, monday) and dark >= "12:00")
    rest = dark_ok / len(nights) if nights else None

    # Integrity: bypasses on the laptop.
    count = lambda kind: len([e for e in log if e["kind"] == kind])
    bypasses = count("relocked") + count("gap") + count("wifi_on") + count("missing")
    integrity = max(0.0, 1 - 0.2 * bypasses)

    scores = {"Showing up": showing, "Unforceable": unforceable, "Integrity": integrity, "Choosing ahead": choosing, "Rest you chose": rest}
    have = {k: v for k, v in scores.items() if v is not None}
    agency = round(100 * sum(v * WEIGHTS[k] for k, v in have.items()) / sum(WEIGHTS[k] for k in have)) if have else 0

    history = tf.load(HISTORY, {})
    history[monday.isoformat()] = agency
    if not dry:
        tf.save(HISTORY, history)
    weeks = sorted(history)[-4:]
    before = [w for w in sorted(history) if w < monday.isoformat()]
    delta = agency - history[before[-1]] if before else None

    # The timeline.
    hm = lambda t: t.strftime("%H:%M")
    plan_rows = [[DAYS[d], k, hm(a), hm(b)] for d, k, a, b, _ in planned if 0 <= d < 7]
    did_rows = [[DAYS[b[0]], "Study" if b[1] == "Prep" else b[1], hm(b[2]), hm(b[3]),
                 ("meeting prep" if b[1] == "Prep" else "") + (" · unsure" if b[4] != "SURE" else "") + (" · added later" if b[5] else "")]
                for b in blocks if b[1] in ("Field", "Study", "Prep", "Kowane", "Work")]
    did_rows += [[DAYS[d], k, hm(a), hm(b), "WakaTime"] for d, k, a, b in (code or []) if k in ("Work", "Kowane")]
    did_rows = [[r[0], r[1], r[2], r[3], ("unsure" if "unsure" in r[4] else r[4].strip(" ·"))] for r in did_rows]

    # Tiles and charts.
    field_by_day = [[DAYS[d], round(hours([(b[2], b[3]) for b in sure if b[1] == "Field" and b[0] == d]), 2),
                     round(hours([(p[2], p[3]) for p in planned if p[1] == "Field" and p[0] == d]), 2)] for d in range(7)]
    kowane_by_day = [[DAYS[d], round(hours([(b[2], b[3]) for b in bank_stops if b[0] == d]), 2),
                      round(hours([(a, b) for dd, a, b in building if dd == d]), 2)] for d in range(7)]
    apps = {}
    for d, nums in phone_days.items():
        for app, mins in nums.get("fun", {}).items():
            apps.setdefault(app, [0] * 7)[d] += mins
    ranked = sorted(apps.items(), key=lambda kv: -sum(kv[1]))
    if len(ranked) > 4:
        other = [sum(v[i] for _, v in ranked[3:]) for i in range(7)]
        ranked = ranked[:3] + [("Other", other)]
    fun_total = sum(sum(v) for v in apps.values())
    phone_day_count = len(phone_days) or 1
    sites = {}
    for d in range(7):
        for host, sec in state.get("sites", {}).get((monday + timedelta(days=d)).isoformat(), {}).items():
            sites[host] = sites.get(host, 0) + sec
    site_rows = [[h, round(s / 3600, 1)] for h, s in sorted(sites.items(), key=lambda kv: -kv[1])[:8] if s >= 360]
    sleep_h = [((tf.at(l, monday) - tf.at(dk, monday)).total_seconds() / 3600) % 24 for _, dk, l in nights]
    work_h = hours([(a, b) for _, _, a, b in merge([(0, "W", a, b) for a, b in did["Work"]])])

    tiles = {
        "bible": sum(1 for n in phone_days.values() if n.get("entry")) if phone_days else None,
        "study_h": round(hours([(b[2], b[3]) for b in sure if b[1] == "Study"]), 1) if blocks else None,
        "prep": f"{len(meetings)}<small> / 2</small>" if blocks else "—",
        "field_h": round(hours([(b[2], b[3]) for b in sure if b[1] == "Field"]), 1) if blocks else None,
        "work_h": round(work_h, 1) if (blocks or code) else None,
        "banks": len(bank_stops) if blocks else None,
        "bank_h": round(hours([(b[2], b[3]) for b in bank_stops]), 1) if blocks else None,
        "build_h": round(hours([(a, b) for _, a, b in building]), 1) if code is not None else None,
        "sleep_h": round(sum(sleep_h) / len(sleep_h), 1) if sleep_h else None,
        "dark_ok": dark_ok, "nights": len(nights),
        "fun_h": round(fun_total / 60, 1) if phone_days else None,
        "fun_daily": round(fun_total / phone_day_count) if phone_days else None,
    }

    # The honest list.
    honest = []
    for d, nums in sorted(phone_days.items()):
        if nums.get("passes"):
            honest.append([DAYS[d], f"Field pass{'es' if nums['passes'] > 1 else ''} taken: {nums['passes']}"])
        if not nums.get("reviewed"):
            honest.append([DAYS[d], "Day not reviewed"])
    for b in blocks:
        if b[5]:
            honest.append([f"{DAYS[b[0]]} {hm(b[2])}", f"{b[6]} · added later"])
        if b[4] == "UNKNOWN":
            honest.append([f"{DAYS[b[0]]} {hm(b[2])}", f"Away {round((b[3] - b[2]).total_seconds() / 60)} m, unlabelled"])
    for e in log:
        when = datetime.fromisoformat(e["at"]).strftime("%a %H:%M")
        if e["kind"] == "relocked":
            honest.append([when, f"Opened {e.get('folder')} while it was resting"])
        elif e["kind"] == "work_done":
            honest.append([when, "Work ended early (Monday)"])
    zeros = [[count("wifi_on"), "Wi-Fi on at night"], [count("gap"), "lock switched off"], [count("missing"), "folders moved"]]
    if not phone_days:
        honest.insert(0, ["—", "No phone data: the Happened calendar isn't set up or hasn't synced"])
    if code is None:
        honest.insert(0, ["—", "WakaTime couldn't be read"])

    label = f"{monday:%-d}–{sunday:%-d %B}" if monday.month == sunday.month else f"{monday:%-d %b} – {sunday:%-d %b}"
    pct = lambda v: round(100 * v)
    data = {
        "week": label, "days": DAYS, "made": datetime.now().strftime("%a %-d %b, %H:%M"),
        "agency": agency, "delta": delta,
        "trend": [[date.fromisoformat(w).strftime("%-d %b"), history[w]] for w in weeks],
        "parts": [[k, pct(v), WEIGHTS[k]] for k, v in have.items()],
        "plan": plan_rows, "did": did_rows,
        "tiles": tiles, "field": field_by_day, "kowane": kowane_by_day,
        "apps": [[a, v] for a, v in ranked], "nights": [[DAYS[d], dk, l] for d, dk, l in nights],
        "sites": site_rows, "honest": honest, "zeros": zeros,
        "copy": "\n".join([
            f"{label} · Agency {agency}%" + (f" ({'+' if delta >= 0 else ''}{delta})" if delta is not None else ""),
            f"Bible {tiles['bible'] if tiles['bible'] is not None else '—'} · Field {tiles['field_h'] or 0} h · Study {tiles['study_h'] or 0} h · Prep {len(meetings)}/2",
            f"Kowane: banks {tiles['banks'] or 0} · building {tiles['build_h'] or 0} h",
            f"Sleep {tiles['sleep_h'] or '—'} h · dark by 21:30 {dark_ok}/{len(nights)} · fun apps {tiles['fun_h'] or 0} h",
            f"Bypasses: {bypasses}",
        ]),
    }
    html = TEMPLATE.read_text().replace("/*DATA*/null", json.dumps(data, default=str))
    Path(out).write_text(html)
    return out, agency


if __name__ == "__main__":
    args = sys.argv[1:]
    out = args[args.index("--out") + 1] if "--out" in args else OUT
    when = next((date.fromisoformat(a) for a in args if re.fullmatch(r"\d{4}-\d{2}-\d{2}", a)), None)
    path, agency = make(when, out, dry="--dry" in args)
    print(f"{path} · agency {agency}%")
