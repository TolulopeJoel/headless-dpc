"""
Tolu's DPC, laptop side. The phone is held by the DPC; this holds the laptop.

Free time is free; protected time is protected. The calendar says which is which:
  "Work ..."   only the work tools run: code editors, Brave, Terminal, the developer tools, Music, and QuickTime or VLC
               for JW videos. Anything else closes (the normal way, so it can ask to save); Brave tabs on the resting
               sites close.
  "Study ..."  only the study tools run: Brave (search, ChatGPT, dictionaries; the same sites rest as in Work),
               JWLManager, Music, notes, JW videos. Code editors and Terminal close.
  "Field ..."  the field morning (the phone takes the hotspot too).
  "Bank ..."   the banks; Kowane stays open for the demo.
  The night, 21:00 (Saturday 20:00) to 05:00, is protected as well.
At sleep time the laptop's Wi-Fi goes off, whatever the network: from 21:00 (Saturday 20:00, and 22:00 the night
before a Thursday or Sunday "Assignment", as on the phone) until 05:00. Switched back on, it goes off again within
seconds, and that's noted for Sunday. In the morning it's switched back on.
The personal projects in ~/Documents/Projects (and any new folder made there) are open in free time and locked (the
folder can't be opened) in protected time. work, headless-dpc and Portfolio are never locked.

Which calendar an event is in decides what it is: Work, Study, Ministry (all of it is the field, whatever the title:
metro, magazine, return visits; only meetings and preparation stay free), Business (the banks: Kowane stays open). Reading and Home are free
time. An event in any calendar whose title starts with "Work", "Study", "Field" or "Bank" counts too.
A session holds from the moment it's in the calendar, even if it was added that day. One that was in the calendar
before its day keeps holding if it's deleted or moved on the day. Each calendar is read from its secret iCal address:
  python tolus_focus.py add-calendar Ministry 'https://calendar.google.com/calendar/ical/.../basic.ics'

There's no off switch. It starts at login and comes back if it's stopped. If it was stopped by hand, the gap goes in the
week's report, and so does every time a locked folder was opened or moved by hand; the report is for the Sunday numbers.

  python tolus_focus.py run           the loop (launchd runs this)
  python tolus_focus.py status        what's on now, and the sessions ahead
  python tolus_focus.py report        this week, for the Sunday numbers
  python tolus_focus.py try Work 2    a 2-minute Work (or Study) session now, to see it (folders aren't touched)
  python tolus_focus.py add-calendar <name> <secret iCal address>
"""
import json, os, plistlib, re, subprocess, sys, time, urllib.parse, urllib.request
from datetime import date, datetime, timedelta
from pathlib import Path

HOME = Path.home()
# TOLUS_FOCUS_HOME points it at a test folder, for trying it without touching the real projects.
HERE = Path(os.environ.get("TOLUS_FOCUS_HOME", HOME / "Library/Application Support/TolusFocus"))
CONFIG = HERE / "config.json"
STATE = HERE / "state.json"          # first sight of each session, sessions held, original folder modes, last heartbeat
LOG = HERE / "events.jsonl"          # one line per session, lock, unlock, re-lock, move and gap
CACHE_DIR = HERE / "calendars"
WEEK_FILE = HERE / "week.txt" if "TOLUS_FOCUS_HOME" in os.environ else HOME / "Documents/justme/laptop-this-week.txt"

TICK = 20                    # seconds between checks
FAST_TICK = 3                # during Work or Study, so a closed app or tab goes within seconds
FETCH_EVERY = 300            # seconds between calendar reads
GAP = 120                    # a silence longer than this is a gap (or sleep, if the process never stopped)
AHEAD = timedelta(days=21)
LOCKED_MODE = 0o000

# The work tools, shared by the session lists below.
EDITORS = ["com.microsoft.VSCode", "com.todesktop.230313mzl4w4u92", "com.google.antigravity-ide", "com.google.antigravity",
           "com.exafunction.windsurf", "com.anthropic.claudefordesktop"]
DEV_TOOLS = ["com.postmanlabs.mac", "io.beekeeperstudio.desktop", "net.sourceforge.sqlitebrowser", "com.mongodb.compass",
             "dev.kdrag0n.MacVirt", "com.github.GitHubClient", "com.electron.ollama", "com.loom.desktop", "us.zoom.xos",
             "com.figma.Desktop"]
UTILITIES = ["com.raycast.macos", "com.windscribe.gui.macos", "com.grammarly.ProjectLlama", "org.localsend.localsendApp",
             "com.google.android.mtpviewer", "design.yugen.Flow", "github.com.thewh1teagle.vibe"]

DEFAULT_CONFIG = {
    # His Google calendars, each by name with its secret iCal address: [{"name": "Work", "url": "..."}].
    "calendars": [],
    # What each calendar is, by name. In Ministry, events whose title has one of the "except" words stay free time.
    "calendar_kinds": {
        "work": {"kind": "Work"},
        "study": {"kind": "Study"},
        "ministry": {"kind": "Field", "except": ["meeting", "prep"]},
        "business": {"kind": "Bank"},
        "businesses": {"kind": "Bank"},
    },
    # Every folder in here is a personal project, new ones included, except these.
    "projects": {"folder": "~/Documents/Projects", "always_open": ["work", "headless-dpc", "Portfolio"]},
    # Protected every night: from 21:00 (20:00 on Saturdays, before the weekend meeting) until 05:00, like the Wi-Fi
    # (07:00 until he moved it on 10 Oct).
    "night": {"from": "21:00", "saturday_from": "20:00", "until": "05:00"},
    # Wi-Fi off at sleep time, any network. The night before a Thursday or Sunday assignment it waits until 22:00, as
    # the phone's hotspot does. It has its own start date: he asked for it from the night of 10 Oct, before the rest.
    "wifi_night": {"from": "21:00", "saturday_from": "20:00", "assignment_from": "22:00", "until": "05:00", "active_from": "",
                   # Saturdays that keep 21:00 like the rest: 10 Oct 2026, asked for at 19:51 that evening.
                   "late_dates": ["2026-10-10"]},
    # The protected sessions, by the first word of the calendar event's title.
    #   allowed_apps   only these installed apps run (Apple's own apps run, except blocked_system_apps)
    #   blocked_sites  Brave tabs on these close        allowed_sites  Brave tabs on anything else close
    #   open           project folders that stay open during it
    "sessions": {
        "Work": {
            "allowed_apps": EDITORS + ["com.brave.Browser", "com.apple.Terminal"] + DEV_TOOLS
                            + ["com.apple.Music", "JWLManager"] + UTILITIES,
            "blocked_sites": ["x.com", "twitter.com", "instagram.com", "youtube.com", "youtu.be", "facebook.com", "tiktok.com"],
        },
        "Study": {
            "allowed_apps": ["com.brave.Browser", "JWLManager", "com.apple.Music", "com.apple.iWork.Pages"] + UTILITIES,
            # Search, ChatGPT and dictionaries are fine for study (his call, 10 Oct); only the distractions rest.
            "blocked_sites": ["x.com", "twitter.com", "instagram.com", "youtube.com", "youtu.be", "facebook.com", "tiktok.com"],
            # Apple's own apps that rest in Study too (they'd otherwise run like Finder and Notes).
            "blocked_apps": ["com.apple.Terminal"],
        },
        "Field": {},
        "Bank": {"open": ["~/Documents/Projects/kowane"]},
    },
    # Video players that may only play JW videos in Work and Study (in a JW folder, named like jw.org's files, or
    # downloaded from jw.org), and Apple's apps that rest in Work and Study.
    "jw_only_players": ["com.apple.QuickTimePlayerX", "org.videolan.vlc"],
    "jw_folders": ["~/Documents/Spiritual"],
    "blocked_system_apps": ["com.apple.Safari", "com.apple.TV", "com.apple.podcasts", "com.apple.news",
                            "com.apple.iBooksX", "com.apple.Photos", "com.apple.MobileSMS", "com.apple.FaceTime"],
    # The typical week (approved 10 Oct). For each day, each kind the calendar hasn't planned before that day comes from
    # here, so an unplanned day isn't free. An event starting "Off" in a calendar, made before the day, frees that kind.
    # (kind, weekday Mon=0, start, end, title)
    "default_week": [
        *[["Field", d, "09:00", "14:00", "Field service, then return visits and studies"] for d in (1, 2, 4, 5)],
        ["Field", 6, "11:30", "14:00", "Field service with the group"],
        ["Study", 0, "06:00", "07:00", "Study: a return-visit question"],
        *[["Study", d, "06:00", "07:30", t] for d, t in ((1, "Study: deep topic"), (2, "Study: deep topic"), (3, "Study: a Bible book"),
                                                          (4, "Study: characters and accounts"), (5, "Study: consolidate the week"))],
        ["Study", 1, "19:00", "20:30", "Deep study evening"],
        ["Study", 3, "17:00", "17:45", "Bible study prep"],
        ["Work", 0, "09:00", "17:00", "Office"], ["Work", 0, "19:30", "20:30", "Work: prep for Tuesday"],
        *[["Work", d, "15:00", "18:00", "Work"] for d in (1, 2, 4)],
        ["Work", 3, "14:00", "17:00", "Work"], ["Work", 4, "18:00", "20:00", "Career: portfolio and 3 applications"],
        ["Bank", 0, "13:00", "13:45", "Bank walk-in near the office"], ["Bank", 3, "09:30", "13:00", "Bank visits"],
    ],
    # Nothing is locked before this (local time); he asked for the weekend of 10–11 Oct to finalise how it works.
    "active_from": "",
}
# When two sessions overlap, the stricter one decides the apps.
STRICTEST_FIRST = ["Study", "Work"]


# ── Small helpers ─────────────────────────────────────────────────────────────

def load(path, default):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return default


def load_config():
    """The config file over the defaults, so a setting missing from an older file falls back instead of meaning "none"."""
    file = load(CONFIG, {})
    config = {**DEFAULT_CONFIG, **file}
    # Sessions from an older file (lists of folders) are replaced; newer ones fill in from the defaults per session.
    sessions = file.get("sessions") if isinstance(file.get("sessions"), dict) else {}
    if any(not isinstance(v, dict) for v in sessions.values()):
        sessions = {}
    config["sessions"] = {k: {**DEFAULT_CONFIG["sessions"].get(k, {}), **sessions.get(k, {})}
                          for k in {**DEFAULT_CONFIG["sessions"], **sessions}}
    config["night"] = {**DEFAULT_CONFIG["night"], **config.get("night", {})}
    config["wifi_night"] = {**DEFAULT_CONFIG["wifi_night"], **config.get("wifi_night", {})}
    return config


def save(path, data):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=1, default=str))
    tmp.replace(path)


def log(kind, **fields):
    line = {"at": datetime.now().isoformat(timespec="seconds"), "kind": kind, **fields}
    with LOG.open("a") as f:
        f.write(json.dumps(line) + "\n")


def notify(text):
    if "TOLUS_FOCUS_HOME" in os.environ:
        return
    subprocess.run(["osascript", "-e", f'display notification {json.dumps(text)} with title "Tolu\'s DPC"'],
                   capture_output=True)


def boot_time():
    out = subprocess.run(["sysctl", "-n", "kern.boottime"], capture_output=True, text=True).stdout
    return int(out.split("sec = ")[1].split(",")[0])


def clock() -> datetime:
    """Now; tests can pretend another time with TOLUS_FOCUS_NOW."""
    return datetime.fromisoformat(os.environ["TOLUS_FOCUS_NOW"]) if "TOLUS_FOCUS_NOW" in os.environ else datetime.now()


def week_start(d: date) -> date:
    """Sunday on or before d: his week begins on a Sunday."""
    return d - timedelta(days=(d.weekday() + 1) % 7)


def at(clock: str, day: date) -> datetime:
    h, m = map(int, clock.split(":"))
    return datetime.combine(day, datetime.min.time()).replace(hour=h, minute=m)


def night_until(config, now):
    """If it's the night now, when it ends; otherwise None."""
    n = config["night"]
    ends_today = at(n["until"], now.date())
    if now < ends_today:
        return ends_today
    starts = at(n["saturday_from"] if now.weekday() == 5 else n["from"], now.date())
    return at(n["until"], now.date() + timedelta(days=1)) if now >= starts else None


def project_folders(config):
    """Every folder in the projects folder, except the always-open ones (hidden folders aside)."""
    proj = config.get("projects", {})
    root = Path(os.path.expanduser(proj.get("folder", "~/Documents/Projects")))
    keep_open = set(proj.get("always_open", []))
    try:
        return {str(p) for p in root.iterdir() if p.is_dir() and not p.is_symlink() and not p.name.startswith(".") and p.name not in keep_open}
    except OSError:
        return set()


def folders(config, state=None):
    """The projects, plus the ones locked before (so a moved one is noticed)."""
    found = project_folders(config)
    if state is not None:
        found |= {path for path, locked in state.get("locked", {}).items() if locked}
    return sorted(found)


# ── Sleep time: Wi-Fi off ─────────────────────────────────────────────────────

ASSIGNMENT_CACHE = {}


def wifi_device():
    out = subprocess.run(["networksetup", "-listallhardwareports"], capture_output=True, text=True).stdout
    found = re.search(r"Hardware Port: Wi-Fi\nDevice: (\S+)", out)
    return found.group(1) if found else "en0"


def wifi_is_on(dev, state):
    if "TOLUS_FOCUS_HOME" in os.environ:             # tests pretend; the real Wi-Fi is never touched
        return state.get("test_wifi", True)
    out = subprocess.run(["networksetup", "-getairportpower", dev], capture_output=True, text=True).stdout
    return out.strip().endswith("On")


def set_wifi(dev, on, state):
    if "TOLUS_FOCUS_HOME" in os.environ:
        state["test_wifi"] = on
        return
    subprocess.run(["networksetup", "-setairportpower", dev, "on" if on else "off"], capture_output=True)


def assignment_tomorrow(config, state, now):
    """As on the phone: an event with "Assignment" in its title tomorrow, a Thursday or Sunday, that was in the
    calendar before today began (so it can't be added on the night itself)."""
    tomorrow = now.date() + timedelta(days=1)
    if tomorrow.weekday() not in (3, 6):
        return False
    cached = ASSIGNMENT_CACHE.get(now.date())
    if cached and time.time() - cached[0] < 300:
        return cached[1]
    import icalendar, recurring_ical_events
    seen = state.setdefault("assignment_seen", {})
    today_start = datetime.combine(now.date(), datetime.min.time()).timestamp()
    t0 = datetime.combine(tomorrow, datetime.min.time())
    found = False
    for c in calendar_list(config):
        if not cache_of(c).exists():
            continue
        cal = icalendar.Calendar.from_ical(cache_of(c).read_bytes())
        for ev in recurring_ical_events.of(cal).between(t0, t0 + timedelta(days=1)):
            if "assignment" not in str(ev.get("SUMMARY", "")).lower():
                continue
            key = f'{c.get("name")}:{ev.get("UID")}|{ev["DTSTART"].dt}'
            first = seen.setdefault(key, now.timestamp())
            stamp = ev.get("LAST-MODIFIED") or ev.get("CREATED")
            edited = stamp.dt.timestamp() if stamp is not None and isinstance(stamp.dt, datetime) else float("inf")
            found = found or min(first, edited) < today_start
    ASSIGNMENT_CACHE[now.date()] = (time.time(), found)
    return found


def sleep_until(config, state, now):
    """If it's sleep time now, when it ends; otherwise None."""
    n = config["wifi_night"]
    ends_today = at(n["until"], now.date())
    if now < ends_today:
        return ends_today
    late = now.date().isoformat() in n.get("late_dates", [])     # a Saturday that keeps 21:00, one at a time
    start = n["saturday_from"] if now.weekday() == 5 and not late else n["from"]
    if now >= at(min(start, n["assignment_from"]), now.date()) and assignment_tomorrow(config, state, now):
        start = n["assignment_from"]
    return at(n["until"], now.date() + timedelta(days=1)) if now >= at(start, now.date()) else None


def wifi_step(config, state, now):
    """At sleep time, Wi-Fi off (and off again if it's switched back on); in the morning, back on. True at sleep time."""
    n = config["wifi_night"]
    if n.get("active_from") and now < datetime.fromisoformat(n["active_from"]):
        return False
    dev = wifi_device()
    until = sleep_until(config, state, now)
    if until:
        night = until.date().isoformat()
        if wifi_is_on(dev, state):
            if state.get("wifi_off_night") == night:
                log("wifi_on", note="switched back on at sleep time")
                notify(f"It's sleep time. Wi-Fi is off till {until:%H:%M}.")
            else:
                log("wifi_off", until=until.isoformat(timespec="minutes"))
                notify(f"Sleep time. Wi-Fi is off till {until:%H:%M}. Goodnight, Tolu.")
            set_wifi(dev, False, state)
            state["wifi_off_night"] = night
        return True
    if state.pop("wifi_off_night", None):
        # Morning: back on, as it was before the night.
        if not wifi_is_on(dev, state):
            set_wifi(dev, True, state)
        log("wifi_back")
    return False


# ── The calendar ──────────────────────────────────────────────────────────────

def calendar_list(config):
    """The calendars to read: the named ones, plus a single address from an older config."""
    cals = [c for c in config.get("calendars", []) if c.get("url")]
    if config.get("ical_url"):
        cals.append({"name": "", "url": config["ical_url"]})
    return cals


def cache_of(cal):
    slug = re.sub(r"[^a-z0-9]+", "-", cal.get("name", "").lower()).strip("-") or "calendar"
    return CACHE_DIR / f"{slug}.ics"


def fetch(config):
    """Refresh each cached calendar; when one can't be read, its last copy stands."""
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    for cal in calendar_list(config):
        try:
            with urllib.request.urlopen(cal["url"], timeout=20) as r:
                data = r.read()
            if b"BEGIN:VCALENDAR" in data:
                cache_of(cal).write_bytes(data)
        except Exception as e:
            print(f"calendar read failed ({cal.get('name')}): {e}", file=sys.stderr)


def kind_of(config, calendar_name, title):
    """What an event is: by its calendar (in Ministry, not meetings or preparation), or else by its title's first word."""
    rule = config.get("calendar_kinds", {}).get(calendar_name.strip().lower())
    if rule and not any(w in title.lower() for w in rule.get("except", [])):
        return rule["kind"]
    return next((k for k in config["sessions"] if title.lower().startswith(k.lower())), None)


def sessions(config, now):
    """Every session from yesterday to three weeks ahead: (key, kind, title, start, end, edited)."""
    import icalendar, recurring_ical_events
    found = []
    for c in calendar_list(config):
        if cache_of(c).exists():
            found += calendar_sessions(config, c.get("name", ""), icalendar.Calendar.from_ical(cache_of(c).read_bytes()), now)
    return sorted(found, key=lambda s: s[3])


def calendar_sessions(config, name, cal, now):
    import recurring_ical_events
    found = []
    for ev in recurring_ical_events.of(cal).between(now - timedelta(days=1), now + AHEAD):
        title = str(ev.get("SUMMARY", "")).strip()
        kind = kind_of(config, name, title)
        if not kind:
            continue
        if OFF_TITLE.match(title):                     # "Off – public holiday": that kind is free that day (not "Office")
            kind = "Off:" + kind
        start, end = ev["DTSTART"].dt, ev["DTEND"].dt if "DTEND" in ev else None
        if not isinstance(start, datetime):          # all-day: the whole day
            start = datetime.combine(start, datetime.min.time())
            end = start + timedelta(days=1)
        start = start.astimezone().replace(tzinfo=None) if start.tzinfo else start
        end = (end.astimezone().replace(tzinfo=None) if getattr(end, "tzinfo", None) else end) or start + timedelta(hours=1)
        # When the calendar says it was last changed: a session made days ago counts as known even if the laptop slept.
        stamp = ev.get("LAST-MODIFIED") or ev.get("CREATED")
        edited = stamp.dt.timestamp() if stamp is not None and isinstance(stamp.dt, datetime) else None
        found.append((f'{name}:{ev.get("UID")}|{start.isoformat()}', kind, title, start, end, edited))
    return found


def known_before_day(seen, s):
    """In the calendar by the end of the day before: seen here by then, or last edited in the calendar by then."""
    key, kind, title, start, end, edited = s
    day_start = datetime.combine(start.date(), datetime.min.time()).timestamp()
    return min(seen.get(key, time.time()), edited if edited is not None else float("inf")) < day_start


def live_sessions(config, state, all_sessions, now):
    """The sessions holding now, as (key, kind, title, end). Anything in the calendar holds at once; one known before
    its day also holds if it's since been deleted or moved."""
    seen = state.setdefault("first_seen", {})
    held = state.setdefault("held", {})
    # An "Off" remembered for a title that isn't one (before 12 Oct, "Office" counted as "Off") goes back to its kind.
    for key, v in held.items():
        if v[0].startswith("Off:") and not OFF_TITLE.match(v[1]):
            v[0] = v[0][4:]
    now_ts = now.timestamp()
    windows = {}
    for s in all_sessions:
        key, kind, title, start, end, edited = s
        seen.setdefault(key, now_ts)
        windows[key] = (kind, title, start, end)
        if known_before_day(seen, s):
            held[key] = [kind, title, start.isoformat(), end.isoformat()]
    for key, (kind, title, a, b) in held.items():
        if kind in config["sessions"] or kind.startswith("Off:"):
            windows.setdefault(key, (kind, title, datetime.fromisoformat(a), datetime.fromisoformat(b)))
    # The default week, for each kind today's calendar didn't plan before today began (and no "Off" made before today).
    today = now.date()
    planned = {s[1] for s in all_sessions if s[3].date() == today and known_before_day(seen, s)}
    planned |= {v[0] for v in held.values() if datetime.fromisoformat(v[2]).date() == today}
    off = {k[4:] for k in planned if k.startswith("Off:")}
    for kind, weekday, a, b, title in config.get("default_week", []):
        if weekday == today.weekday() and kind not in planned and kind not in off:
            windows[f"default:{kind}:{today}:{a}"] = (kind, f"{title} (typical week)", at(a, today), at(b, today))
    windows = {k: v for k, v in windows.items() if not v[0].startswith("Off:") and v[0] not in off}
    # Forget what ended more than a couple of days ago.
    cutoff = (now - timedelta(days=2)).isoformat()
    state["held"] = {k: v for k, v in held.items() if v[3] >= cutoff}
    state["first_seen"] = {k: v for k, v in seen.items() if k.rsplit("|", 1)[1] >= (now - timedelta(days=8)).isoformat()}
    # Mondays only: "work done" (tolus_focus.py done) ends the rest of today's Work early; it's in the week's report.
    if state.get("work_done") == today.isoformat():
        windows = {k: v for k, v in windows.items() if not (v[0] == "Work" and v[2].date() == today)}
    return [(key, kind, title, end) for key, (kind, title, start, end) in windows.items() if start <= now < end]


# ── The folders ───────────────────────────────────────────────────────────────

def apply(config, state, open_now):
    """Lock every project that isn't open now; open the rest. Returns what changed."""
    modes = state.setdefault("modes", {})
    changed = []
    for path in folders(config, state):
        p = Path(path)
        if not p.exists():
            # A locked folder can't be moved without opening it first, so this one was opened by hand and moved.
            if state.get("locked", {}).pop(path, False):
                log("missing", folder=p.name, note="moved or renamed while locked")
            continue
        mode = p.stat().st_mode & 0o777
        should_open = path in open_now
        if should_open and mode == LOCKED_MODE:
            p.chmod(int(modes.get(path, "755"), 8))
            changed.append(("unlocked", path))
        elif not should_open and mode != LOCKED_MODE:
            if state.get("locked", {}).get(path):
                log("relocked", folder=p.name, note="opened by hand while locked")
            modes[path] = format(mode, "o")
            p.chmod(LOCKED_MODE)
            changed.append(("locked", path))
        state.setdefault("locked", {})[path] = not should_open
    return changed


def run():
    HERE.mkdir(parents=True, exist_ok=True)
    if not CONFIG.exists():
        save(CONFIG, DEFAULT_CONFIG)
    state = load(STATE, {})

    # A silence since the last heartbeat with no reboot in between means this was stopped by hand.
    last = state.get("heartbeat")
    if last and time.time() - last > GAP and boot_time() < last:
        gap = (datetime.fromtimestamp(last), datetime.now())
        log("gap", start=gap[0].isoformat(timespec="minutes"), end=gap[1].isoformat(timespec="minutes"),
            minutes=round((gap[1] - gap[0]).total_seconds() / 60))

    last_fetch = 0.0
    while True:
        if time.time() - last_fetch > FETCH_EVERY:
            fetch(load_config())
            last_fetch = time.time()
        busy = False
        try:
            busy = step(state)
        except Exception as e:                       # one bad check never stops the loop
            print(f"check failed: {e!r}", file=sys.stderr)
        time.sleep(FAST_TICK if busy else TICK)


def step(state):
    """One check: which protected time holds now, then the folders and apps to match. True if apps are being watched."""
    config = load_config()
    now = clock()
    try:
        sleeping = wifi_step(config, state, now)
    except Exception as e:
        print(f"wifi check failed: {e!r}", file=sys.stderr)
        sleeping = False
    if config.get("active_from") and now < datetime.fromisoformat(config["active_from"]):
        # Not on yet: everything open, nothing logged. The heartbeat still runs, so switching on isn't a gap.
        for path in folders(config, state):
            p = Path(path)
            if p.exists() and p.stat().st_mode & 0o777 == LOCKED_MODE:
                p.chmod(int(state.get("modes", {}).get(path, "755"), 8))
            state.setdefault("locked", {})[path] = False
        state["heartbeat"] = time.time()
        save(STATE, state)
        return sleeping

    try:
        live = live_sessions(config, state, sessions(config, now), now)
    except Exception as e:                           # a calendar it can't read: the night still holds
        print(f"calendar parse failed: {e!r}", file=sys.stderr)
        live = []
    night = night_until(config, now)

    # Each session is noted once, for the week's report.
    noted = state.setdefault("noted", {})
    for key, kind, title, end in live:
        if key not in noted:
            noted[key] = end.isoformat()
            log("session", type=kind, title=title)
    state["noted"] = {k: v for k, v in noted.items() if v >= (now - timedelta(days=8)).isoformat()}

    # The folders: open in free time; in protected time, only what the session itself keeps open.
    if night:
        open_now, why, until = set(), "the night", night
    elif live:
        open_now = {str(Path(os.path.expanduser(p))) for _, kind, _, _ in live for p in config["sessions"][kind].get("open", [])}
        _, kind, title, until = max(live, key=lambda s: s[3])
        why = kind
    else:
        open_now, why, until = project_folders(config), None, None
    try:
        changes = apply(config, state, open_now)
    except OSError as e:
        print(f"couldn't change a folder: {e}", file=sys.stderr)
        changes = []
    for what, path in changes:
        log(what, folder=Path(path).name, session=why)
    if any(w == "locked" for w, _ in changes):
        notify(f"Your projects rest during {why}, till {until:%H:%M}." if why == "the night" or why else "Your projects rest now.")
    elif any(w == "unlocked" for w, _ in changes) and not why:
        notify("Free time. Your projects are open.")

    # The apps: the strictest session with an app list decides.
    policy = next(((k, e) for k in STRICTEST_FIRST for _, kind, _, e in live if kind == k and "allowed_apps" in config["sessions"][k]), None)
    if policy and "TOLUS_FOCUS_HOME" in os.environ:
        # A test never closes the real laptop's apps (one closed VS Code mid-test on 9 Oct); only `try` does, on purpose.
        print(f"(test) {policy[0]} would be enforcing apps", file=sys.stderr)
    elif policy:
        kind, end = policy
        try:
            enforce(config, kind, end, TOLD.setdefault((kind, end), set()))
        except Exception as e:
            print(f"{kind} enforcement failed: {e!r}", file=sys.stderr)

    # Sleep is a silence too, but the process never stopped, so it isn't a gap.
    state["heartbeat"] = time.time()
    save(STATE, state)
    write_week()
    return bool(policy) or sleeping


# ── Work and Study: the apps ──────────────────────────────────────────────────

MEDIA = re.compile(r"\.(mp4|m4v|mov|mkv|avi|webm|mp3|m4a)$", re.I)
JW_NAME = re.compile(r"_r\d{3,4}P\.(mp4|m4v|mov)$", re.I)     # jw.org's own file names: ..._E_04_r720P.mp4
# Where jw.org's downloads come from: its own sites, and its media folder on the download server it shares with others.
JW_SOURCES = ("jw.org", "jw-cdn.org", "akamaihd.net/files/media_")
# Brave's own pages, which no site list should close.
BROWSER_PAGES = ("brave:", "chrome:", "about:", "file:")
# "Off" as a word of its own at the start of a title: "Off", "Off – holiday", "Off: travel". Not "Office", not "Offer".
OFF_TITLE = re.compile(r"off\b", re.I)
# What's been said in each session, so each closed app or site is announced once.
TOLD = {}


def running_apps():
    """The apps with a Dock icon, as (app, bundle id, name, path)."""
    from AppKit import NSWorkspace
    from Foundation import NSDate, NSRunLoop
    NSRunLoop.currentRunLoop().runUntilDate_(NSDate.dateWithTimeIntervalSinceNow_(0.05))   # refresh the list
    out = []
    for a in NSWorkspace.sharedWorkspace().runningApplications():
        if a.activationPolicy() != 0:
            continue
        url = a.bundleURL()
        out.append((a, a.bundleIdentifier() or "", a.localizedName() or "", url.path() if url else ""))
    return out


def is_jw(path, config):
    """A JW video: in a JW folder, named like jw.org's files, or stamped by the browser as downloaded from jw.org."""
    if any(path.startswith(str(Path(os.path.expanduser(f)))) for f in config.get("jw_folders", [])):
        return True
    if JW_NAME.search(path):
        return True
    # The browser's "where from" stamp, read from the file itself (Spotlight can't see Documents here).
    raw = subprocess.run(["xattr", "-px", "com.apple.metadata:kMDItemWhereFroms", path], capture_output=True, text=True).stdout
    try:
        where = " ".join(plistlib.loads(bytes.fromhex("".join(raw.split()))))
    except Exception:
        return False
    return any(src in where for src in JW_SOURCES)


def open_media(pid):
    out = subprocess.run(["lsof", "-p", str(pid), "-Fn"], capture_output=True, text=True).stdout
    return [l[1:] for l in out.splitlines() if l.startswith("n") and MEDIA.search(l)]


def brave_tabs():
    # The separator is set outside the tell block: inside it, "tab" means one of Brave's tabs, not the character.
    script = """set sep to character id 9
    tell application "Brave Browser"
        set out to ""
        repeat with w from 1 to count of windows
            repeat with t from 1 to count of tabs of window w
                set out to out & w & sep & t & sep & (URL of tab t of window w) & linefeed
            end repeat
        end repeat
        return out
    end tell"""
    try:
        r = subprocess.run(["osascript", "-e", script], capture_output=True, text=True, timeout=10)
    except subprocess.TimeoutExpired:                # the one-time macOS permission prompt, unanswered
        return []
    tabs = []
    for line in r.stdout.splitlines():
        parts = line.split("\t", 2)
        if len(parts) == 3:
            tabs.append((int(parts[0]), int(parts[1]), parts[2]))
    return tabs


def on_site(url, sites):
    host = (urllib.parse.urlparse(url).hostname or "").lower()
    return next((d for d in sites if host == d or host.endswith("." + d)), None)


def tab_rests(url, rules):
    """The reason a Brave tab closes under these rules, or None."""
    if url.startswith(BROWSER_PAGES):
        return None
    if "allowed_sites" in rules:
        host = (urllib.parse.urlparse(url).hostname or "")
        return None if on_site(url, rules["allowed_sites"]) else host or url
    return on_site(url, rules.get("blocked_sites", []))


def enforce(config, kind, until, told):
    """Close what rests in this session. `told` remembers what's been said, so each thing is said once."""
    rules = config["sessions"][kind]
    allowed = set(rules.get("allowed_apps", []))
    players = set(config.get("jw_only_players", []))
    blocked_system = set(config.get("blocked_system_apps", [])) | set(rules.get("blocked_apps", []))
    during = f"during {kind.lower()}, till {until:%H:%M}"
    closed = []
    for app, bid, name, path in running_apps():
        if bid in players:
            media = open_media(app.processIdentifier())
            if media and not all(is_jw(m, config) for m in media):
                app.terminate()
                closed.append(name)
                if ("jw", name) not in told:
                    told.add(("jw", name))
                    notify(f"{name} plays JW videos {during}.")
            continue
        system = path.startswith("/System/")
        if (system and bid in blocked_system) or (not system and bid not in allowed):
            app.terminate()
            closed.append(name)
            if name not in told:
                told.add(name)
                notify(f"{name} rests {during}.")
            continue
        if bid == "com.brave.Browser":
            for w, t, url in sorted(brave_tabs(), reverse=True):
                site = tab_rests(url, rules)
                if site:
                    subprocess.run(["osascript", "-e", f'tell application "Brave Browser" to close tab {t} of window {w}'],
                                   capture_output=True, timeout=10)
                    closed.append(site)
                    if site not in told:
                        told.add(site)
                        notify(f"{site} rests {during}.")
    return closed


def try_session(kind, minutes):
    """A Work or Study session now, for `minutes`, to see it. Only apps and Brave tabs; the folders aren't touched."""
    config = load_config()
    until = datetime.now() + timedelta(minutes=minutes)
    told = set()
    notify(f"Trying a {kind} session till {until:%H:%M}.")
    print(f"{kind} session until {until:%H:%M:%S}. Open things and watch.", flush=True)
    while datetime.now() < until:
        for what in enforce(config, kind, until, told):
            print(f"  {datetime.now():%H:%M:%S}  closed {what}", flush=True)
        time.sleep(FAST_TICK)
    notify(f"{kind} session over. Everything's open.")
    print("Over.")


# ── Reading it back ───────────────────────────────────────────────────────────

def week_lines():
    start = week_start(date.today())
    events = [json.loads(l) for l in LOG.read_text().splitlines()] if LOG.exists() else []
    events = [e for e in events if date.fromisoformat(e["at"][:10]) >= start]
    kept = {}
    for e in events:
        if e["kind"] == "session":
            kept[e.get("type", "?")] = kept.get(e.get("type", "?"), 0) + 1
    relocks = [e for e in events if e["kind"] == "relocked"]
    gaps = [e for e in events if e["kind"] == "gap"]
    moved = [e for e in events if e["kind"] == "missing"]
    lines = [f"Laptop, week of {start:%a %d %b}"]
    lines.append("  Protected sessions: " + (", ".join(f"{k} {n}" for k, n in sorted(kept.items())) or "none"))
    lines.append(f"  Locked folders opened by hand: {len(relocks)}" +
                 "".join(f"\n    {e['at'][:16].replace('T', ' ')}  {e['folder']}" for e in relocks))
    lines.append(f"  Times the lock was switched off: {len(gaps)}" +
                 "".join(f"\n    {e['start'].replace('T', ' ')} to {e['end'][11:]}  ({e['minutes']} min)" for e in gaps))
    done = [e for e in events if e["kind"] == "work_done"]
    if done:
        lines.append(f"  Monday work ended early: " + ", ".join(e["at"][:16].replace("T", " ") for e in done))
    wifi_by_hand = [e for e in events if e["kind"] == "wifi_on"]
    lines.append(f"  Wi-Fi switched back on at sleep time: {len(wifi_by_hand)}" +
                 "".join(f"\n    {e['at'][:16].replace('T', ' ')}" for e in wifi_by_hand))
    if moved:
        lines.append(f"  Locked folders moved or renamed: {len(moved)}" +
                     "".join(f"\n    {e['at'][:10]}  {e['folder']}" for e in moved))
    return lines


def write_week():
    try:
        WEEK_FILE.write_text("\n".join(week_lines()) + "\n")
    except OSError:
        pass


def status():
    config, state = load_config(), load(STATE, {})
    now = clock()
    if config.get("active_from") and now < datetime.fromisoformat(config["active_from"]):
        print(f"not on yet: starts {datetime.fromisoformat(config['active_from']):%a %d %b %H:%M}; everything is open until then")
    cals = calendar_list(config)
    print("calendars:", ", ".join(
        f"{c.get('name') or '(unnamed)'} (read {datetime.fromtimestamp(cache_of(c).stat().st_mtime):%a %H:%M})" if cache_of(c).exists()
        else f"{c.get('name') or '(unnamed)'} (not read yet)" for c in cals) or "NONE SET (only the night is protected)")
    sleeping = sleep_until(config, json.loads(json.dumps(state)), now)
    if sleeping:
        print(f"Wi-Fi: sleep time, off till {sleeping:%a %H:%M}")
    else:
        nxt = next(t for t in (now.replace(second=0, microsecond=0) + timedelta(minutes=i) for i in range(1, 2 * 1440))
                   if sleep_until(config, json.loads(json.dumps(state)), t))
        print(f"Wi-Fi: on; sleep time starts {nxt:%a %H:%M}")
    night = night_until(config, now)
    live = live_sessions(config, json.loads(json.dumps(state)), sessions(config, now), now)
    print("now:", f"night, till {night:%H:%M}" if night else ", ".join(f"{k} till {e:%H:%M}" for _, k, _, e in live) or "free time")
    locked = [Path(p).name for p in folders(config) if Path(p).stat().st_mode & 0o777 == LOCKED_MODE]
    print(f"projects locked: {', '.join(locked) or 'none'}")
    print("sessions ahead:")
    for key, kind, title, start, end, edited in [s for s in sessions(config, now) if s[4] > now][:12]:
        print(f"  {start:%a %d %b %H:%M}–{end:%H:%M}  {title}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd in ("try", "try-work"):
        kind = sys.argv[2] if cmd == "try" and len(sys.argv) > 2 else "Work"
        minutes = sys.argv[3] if cmd == "try" and len(sys.argv) > 3 else (sys.argv[2] if cmd == "try-work" and len(sys.argv) > 2 else 2)
        try_session(kind, float(minutes))
    elif cmd == "done":                                # Mondays: work's finished at the office; the rest of today's Work ends
        st, now = load(STATE, {}), clock()
        if now.weekday() != 0:
            print("Work done is for Mondays (the office day) only.")
        else:
            st["work_done"] = now.date().isoformat(); save(STATE, st)
            log("work_done", note="Work ended early (Monday)")
            print(f"Work ended for today at {now:%H:%M}. It's in this week's report.")
    elif cmd == "add-calendar":                        # add-calendar <name> <secret iCal address>
        HERE.mkdir(parents=True, exist_ok=True)
        name, url = sys.argv[2], sys.argv[3]
        file = load(CONFIG, {})
        file["calendars"] = [c for c in file.get("calendars", []) if c.get("name", "").lower() != name.lower()] + [{"name": name, "url": url}]
        save(CONFIG, file)
        fetch(load_config())
        rule = load_config()["calendar_kinds"].get(name.lower())
        print(f"{name}: " + (f"counts as {rule['kind']}" + (f" (except titles with {', '.join(rule['except'])})" if rule.get("except") else "") if rule else "free time")
              + (f"; read {len(open(cache_of({'name': name})).read().split('BEGIN:VEVENT')) - 1} events" if cache_of({"name": name}).exists() else "; couldn't read it yet"))
    elif cmd == "step":                                # tests: one fetch and one check
        HERE.mkdir(parents=True, exist_ok=True)
        st = load(STATE, {})
        fetch(load_config())
        step(st)
    else:
        {"run": run, "status": status, "report": lambda: print("\n".join(week_lines()))}[cmd]()
