# Beacon Glasses — Android companion app

Meta Global AI Developer Hackathon 2026, Indie Dev Track (individual).
Part of the Beacon glasses demo (see the repo root README for the full stack).

## What it is

An Android companion app on Alan's Pixel phone. It takes a frame from his
Ray-Ban Meta glasses plus a spoken or typed question, sends both to the
home-box backend, and shows/relays the answer:

```
glasses frame + question → phone app → backend (port 8901)
  → Muse Wonder answer (+ SAM 3.1 frame context) → phone screen + watch
```

Built from Meta's DAT `CameraAccess` sample (kept intact for the glasses
session/stream/capture lifecycle), plus a Beacon layer that talks to the
backend. The Kotlin namespace stays the sample's
(`com.meta.wearable.dat.externalsampleapps.cameraaccess`); the installed
package is `us.ninjatek.beacon.glasses` and the launcher label is
"Beacon Glasses".

## Beacon files (the new code)

- `app/src/main/java/.../beacon/BackendClient.kt` — HTTP client for the
  FIXED backend contract, no third-party deps, no key anywhere:
  `GET /health → {"ok": true}`,
  `POST /ask {"image_b64", "question"} → {"answer", "sam"}`,
  `GET /latest → {"question", "answer", "at"}`.
  Frames are downscaled (long side ≤ 1280px) and JPEG-encoded (quality 85).
- `app/src/main/java/.../beacon/BeaconViewModel.kt` — backend URL
  (editable, persisted in SharedPreferences; default
  `http://100.99.129.53:8901`), question, frame source, ask state, answer,
  `/latest` snapshot, and the watch relay (package-scoped broadcast
  `us.ninjatek.beacon.ACTION_ANSWER` to the Beacon Wear OS app, plus the
  backend's `/latest` which the wrist display can also read).
- `app/src/main/java/.../beacon/BeaconAskScreen.kt` — the Ask-Muse sheet:
  backend URL + health check, frame source buttons, typed question,
  "Speak question" (system speech recogniser), Ask button, answer + SAM
  text, Copy, "Send to watch again", and the `/latest` readout.
- `app/src/test/java/.../beacon/BackendClientTest.kt` — unit tests for the
  JSON helpers (no network).
- `tools/stub_backend.py` — stdlib-only stub of the backend contract for
  testing the app without the real backend. Its answer is canned sample
  text and must never be presented as a real Muse answer.

## Sample-code changes (small, deliberate)

- `MainActivity` / `CameraAccessScaffold`: one activity-scoped
  `BeaconViewModel` passed to both screens, so a frame/question survives
  switching between Home and Camera (e.g. across registration).
- `CameraScreen`: an "Ask Muse" button above the capture controls, and the
  capture preview gains "Ask Muse about this photo" for a just-captured
  glasses photo.
- `HomeScreen`: "Ask Muse without glasses" — the no-glasses path (sample
  frame or a picked image) is reachable before DAT registration.
- `app/build.gradle.kts`: `applicationId us.ninjatek.beacon.glasses`,
  `versionName 1.0-beacon`, `BEACON_BACKEND_URL` BuildConfig field
  (overridable via `beacon_backend_url` in `local.properties`), JUnit for
  the unit tests. DAT manifest placeholders: application id
  `2194479907777415` (the Wearables Developer Center project "Beacon" id —
  an id, not a secret); client token stays empty because Developer Mode is
  ON in the Meta AI app, so manifest credentials are not required.
- `AndroidManifest.xml`: `usesCleartextTraffic="true"` — the backend is
  plain HTTP on the LAN/Tailscale network. No credentials cross it.
- `strings.xml`: app name "Beacon Glasses".

## Security / data rules

- NO API key is in this app, in any file, log, or network call. The Meta
  Model API key lives only on the home box and is used only by the backend
  at runtime. Never add one here.
- Sample data ONLY in code and tests. The bundled frame is the sample's
  `plant.png` asset. No private data, credentials, or client data.

## Build (on the Zorin build box)

Prerequisites: Android SDK at `/home/r00t/Android/Sdk` (platform
`android-36`), JDK 17 at `/home/r00t/Android/jdk-17`. No sudo, user-level
only. The Gradle wrapper downloads its distribution + dependencies on
first run (needs internet on the build box).

```bash
cd ~/beacon-glasses-build        # a copy of this directory on the Zorin box
cat > local.properties <<'EOF'
sdk.dir=/home/r00t/Android/Sdk
mwdat_application_id=2194479907777415
# mwdat_client_token intentionally empty: Developer Mode testing
EOF
export JAVA_HOME=/home/r00t/Android/jdk-17
export ANDROID_HOME=/home/r00t/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug :app:testDebugUnitTest
# APK: app/build/outputs/apk/debug/app-debug.apk
```

SSH proxy sessions die at ~5 minutes, so run long builds detached and
poll the log:

```bash
nohup ./gradlew :app:assembleDebug :app:testDebugUnitTest > build.log 2>&1 &
tail -50 build.log
```

## Install on Alan's Pixel

Needs: the Pixel, Developer Mode ON in the Meta AI app, and the phone
unlocked with USB debugging authorised for the build box (or sideload the
APK file directly on the phone).

```bash
adb install -r app-debug.apk
# or, to replace cleanly: adb uninstall us.ninjatek.beacon.glasses && adb install app-debug.apk
```

Then on the phone: open "Beacon Glasses", grant Bluetooth / Bluetooth
connect / Internet when asked, tap "Connect my glasses" (redirects to the
Meta AI app to confirm — Developer Mode must be ON there), then
"Start session" → "Preview" → capture a photo → "Ask Muse about this
photo". Or, with no glasses: "Ask Muse without glasses" → "Use sample
frame" (or "Pick image") → "Check backend" → "Ask Muse".

Backend URL: default `http://100.99.129.53:8901` (Tailscale). On the home
LAN the same service is reachable at the box's LAN address — edit the URL
in the Ask sheet if the Tailscale address does not work from the phone.
The backend must be up (real backend track, or the stub below) before
"Check backend" can succeed.

## Test without the real backend (stub)

```bash
python3 tools/stub_backend.py 8901   # on the home box (or any host)
# point the app at http://<that-host>:8901, tap "Check backend", then
# "Use sample frame" -> "Ask Muse". Expect the canned "Stub answer" text.
```

## Fallback input path (if DAT blocks)

If the DAT path fails on the phone (registration, camera permission, or
session errors — see "What still needs a live test" below), do NOT stall:
the MetaLensAI rig (glasses → RTMP → OBS on the Zorin box) already
streams. Save a frame from OBS, get it onto the phone (e.g. via the
shared folder / a message to himself), and in the app tap "Pick image
(OBS snapshot)", then ask. The whole Ask flow is identical; only the
frame source changes.

## Watch relay — honest scope

The app broadcasts the answer to the Beacon Wear OS app package
(`us.ninjatek.beacon`, action `us.ninjatek.beacon.ACTION_ANSWER`) and the
answer is also readable from the backend at `GET /latest`. The Wear app
needs a matching broadcast receiver (or a `/latest` poll) to actually
display it — that receiver is a watch-track change and is NOT in this
app, so do not claim the watch shows the answer until that lands and is
tested on the watch.

## Watch one-tap (v1.2-onetap, Oct 30)

One watch tap, no phone taps after launch: the watch sends
`/beacon/one_tap` (preset question only — see `beacon/BeaconTrigger.kt`),
`DemoTriggerListenerService` hands it to `BeaconOneTapRunner`, which drives
`BeaconGlassesController` (the Application-owned session/stream owner;
`CameraViewModel` delegates its foreground lifecycle/capture there too, so
there is exactly one session owner) through startSession → startStreaming
→ capturePhoto → endSession, then POSTs the fresh frame to `/ask` and
relays the answer on the existing paths (`/beacon/demo_answer` Data Layer
+ notification + broadcast). The old `/beacon/demo_trigger` re-ask path is
kept unchanged. Rules enforced in code, not just docs: camera permission
is queried only (never a Meta AI redirect from a background service),
phone UI is never popped from the background path, the foreground-service
start is best-effort from background (Android 12+ may refuse it; the runner
holds a wake lock instead), and only a session/stream the run started is
torn down afterwards.

CROSS-TRACK ALIGNMENT (hard requirement): Play Services delivers Data
Layer messages only between apps whose package AND signing cert match.
This app ships as `us.ninjatek.beacon`, signed with the watch app's key
(see `app/build.gradle.kts` — keystore path/passwords come only from the
build box's `local.properties`, never committed). That makes 1.2 a FRESH
package, not an install-over of 1.1-trigger (`us.ninjatek.beacon.glasses`):
uninstall the old app after the new one is verified, and re-check the
backend URL on first launch (old SharedPreferences do not carry over).
`DemoAnswerWearSender` now logs every send failure (success, per-node
failure, no-nodes, lookup failure) instead of swallowing them.

## What still needs a live test (not verified in this track)

- Real Ray-Ban Meta glasses: DAT registration, session, preview, capture,
  and the full glasses-photo → Ask → answer path on Alan's Pixel.
- MockDeviceKit path: debug menu → MockDeviceKit screen (pair a mock
  Ray-Ban Meta, capture the mock image) — runnable on the phone, not
  verified here because no device/emulator was available at build time.
- The watch actually displaying a relayed answer (needs the watch-track
  receiver above).
- The real backend's `/ask` (Wonder + SAM 3.1) vs. this app — verified
  here only against the stub contract (see the coordinator report).
