# Setup / install guide

## Versions that work together (proven Nov 1, 2026)

- Watch: 2.6.8-onetap (versionCode 18), package us.ninjatek.beacon.
- Phone: 1.2.1-crashfix (versionCode 4), package us.ninjatek.beacon.
- Backend: FastAPI, 0.0.0.0:8901, on the home desktop.

Do not mix packages: Play Services Data Layer messages only route
between apps whose package name AND signing certificate both match
across devices. Watch and phone must both be us.ninjatek.beacon, signed
with the same key.

## Install the watch app

1. Download the watch APK from a full .apk link (Muse storage link
   ending in .apk). Do NOT use a chat attachment download — those arrive
   as .bin and do not install, which is what kept the watch stuck on
   2.6.1 for days.
2. Install over the current watch app (same key, install-over only).
   Never uninstall and never Clear data / Clear storage — that wipes
   the saved API key, which then has to be re-entered via the Pixel
   Remote Keyboard.
3. Check the bottom label on the watch main screen. It must read
   "Beacon 2.6.8-onetap (18)". If it still reads an older version, the
   install did not actually update — check the file extension first.

## Install the phone app

1. Install phone 1.2.1 over any earlier phone build. Only one phone app
   should be left on the phone (the old 1.1 build was uninstalled
   Oct 30, 2026).
2. Open the phone app at the main screen. It must say Ready.
3. Set Backend URL to the desktop's current LAN address, for example:
   http://192.168.1.248:8901
   Note: this address can change with DHCP. On Nov 1, 2026 it changed
   from 192.168.1.227 to 192.168.1.248, and the old address was the
   final failure point in testing — the server was up, the phone was
   just pointing at the old address. If "Backend unreachable" appears,
   check the desktop's current LAN IP first, update the URL, and tap
   Check backend.
4. Do NOT tap Start session for a one-tap test. Leave the phone on
   Ready, phone in pocket or on desk, Bluetooth range.

## Run the backend

On the home desktop, the backend runs as a user service / uvicorn on
port 8901. Health check: GET /health must return {"ok": true}.
No API key lives in the phone app, the watch app, or this repo. The
model API key lives only in the desktop environment file, never in
chat, notes, or source.

## One-tap test

1. Glasses on, watch on, phone on Ready, Bluetooth range.
2. Fresh scene in front of you (staged, sample data only for a demo).
3. Tap ONE-TAP ASK MUSE on the watch, and only that.
4. Glasses light up, preview appears on the phone Ready screen, the
   phone clicks, the backend asks Muse Wonder, and the answer lands
   in the watch main transcript + notification + Demo screen.

## Build from source

- Watch: cd watch/ && ./build.sh — raw toolchain (JDK + Android SDK
  + aapt2 + d8 + apksigner), signs with the original debug keystore.
  Do NOT sign with a fresh key, or install-over breaks.
- Phone: Gradle, ./gradlew assembleDebug (see phone/README).
- Backend: python -m uvicorn app:app --host 0.0.0.0 --port 8901
  (see backend/README).
