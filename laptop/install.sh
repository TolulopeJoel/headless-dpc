#!/bin/bash
# Installs (or updates) the laptop side of Tolu's DPC for this user. No sudo.
#   ./install.sh                       install or update, keeping the current config
# Then add each calendar by name with its secret iCal address (Work, Study, Ministry, Business, Reading, Home):
#   ~/Library/Application\ Support/TolusFocus/venv/bin/python ~/Library/Application\ Support/TolusFocus/tolus_focus.py add-calendar Work 'https://...basic.ics'
set -euo pipefail

HERE="$HOME/Library/Application Support/TolusFocus"
PLIST="$HOME/Library/LaunchAgents/com.tolu.focus.plist"
SRC="$(cd "$(dirname "$0")" && pwd)"

mkdir -p "$HERE"
if [ ! -x "$HERE/venv/bin/python" ]; then
    /Library/Frameworks/Python.framework/Versions/3.12/bin/python3 -m venv "$HERE/venv"
fi
"$HERE/venv/bin/pip" install -q --disable-pip-version-check icalendar recurring-ical-events

# It runs from outside Documents, so editing the repo copy changes nothing until this is run again.
cp "$SRC/tolus_focus.py" "$HERE/tolus_focus.py"

if [ "${1:-}" != "" ]; then
    "$HERE/venv/bin/python" - "$1" <<'EOF'
import json, sys
from pathlib import Path
p = Path.home() / "Library/Application Support/TolusFocus/config.json"
sys.path.insert(0, str(p.parent))
from tolus_focus import DEFAULT_CONFIG, load, save
config = load(p, DEFAULT_CONFIG)
config["ical_url"] = sys.argv[1]
save(p, config)
EOF
fi

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>com.tolu.focus</string>
    <key>ProgramArguments</key>
    <array>
        <string>$HERE/venv/bin/python</string>
        <string>$HERE/tolus_focus.py</string>
        <string>run</string>
    </array>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
    <key>ThrottleInterval</key><integer>10</integer>
    <key>StandardErrorPath</key><string>$HERE/errors.log</string>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)/com.tolu.focus" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Installed. Status:"
"$HERE/venv/bin/python" "$HERE/tolus_focus.py" status
