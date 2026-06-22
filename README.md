# Android DPC — Headless Device Owner

Minimal Device Owner app that locks your specific NextDNS profile as Private DNS
(DNS-over-TLS). No app drawer icon. No VPN overhead. Self-disabling setup screen.

---

## Before you build — add your NextDNS profile ID

1. Go to nextdns.io → your profile → **Setup tab**
2. Find the DoT hostname, e.g. `abc123.dns.nextdns.io`
3. Copy the profile ID (just `abc123`, not the full hostname)
4. Open `app/src/main/java/com/tolu/dpc/SetupActivity.kt`
5. Replace `YOUR_PROFILE_ID` on line 12 with your actual ID

---

## Prerequisites

- JDK 17+
- Android SDK Command Line Tools with `ANDROID_HOME` set
- `adb` working
- Android 9+ phone (for Private DNS support)

---

## Build

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

---

## Deploy (do these steps in order)

**1. Remove Google accounts from the phone**
Settings → Accounts → remove all Google accounts temporarily.

**2. Install the APK**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

**3. Set as Device Owner**
```bash
adb shell dpm set-device-owner com.tolu.dpc/.AdminReceiver
```

You should see: `Success: Device owner set to package com.tolu.dpc`

**4. Re-add your Google account** (safe to do now)

**5. Run setup — one time only**
```bash
adb shell am start -n com.tolu.dpc/.SetupActivity
```

A toast will confirm the DNS hostname. The setup screen immediately disables itself.

---

## What happens after setup

- Private DNS is set to `{your-profile-id}.dns.nextdns.io` on ALL connections
- On Android 12+: Private DNS settings are greyed out — user can't change them
- On Android 11 and below: setting is applied but UI isn't locked (see note below)
- The app has no icon anywhere
- Setup activity is permanently disabled

### Android 11 and below note

The restriction `no_config_private_dns` only works on Android 12+. On older versions,
the DNS setting is applied by the Device Owner but a determined user could change it
via Settings → Network → Private DNS. If you need hard locking on Android 11,
switch to the VPN lockdown approach instead (see VPN branch).

---

## To verify it's working

Settings → Network & Internet → Private DNS — should show your hostname.
Or check nextdns.io/test from the phone browser.

---

## To undo / remove

```bash
adb shell dpm remove-active-admin com.tolu.dpc/.AdminReceiver
```

On some Android versions this requires a factory reset.
