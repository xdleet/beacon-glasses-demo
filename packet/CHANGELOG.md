# Build history — Beacon glasses + watch, one-tap

Short version, for the hackathon packet. Error → fix → result.

## Watch app (us.ninjatek.beacon)

- 2.6 — One-tap button added. Not proven.
- 2.6.1 — Scrollable drawer, One-tap first, Web Chat removed. Error: tap crashed, sender had no guards.
- 2.6.2 — Answers land in the main transcript, no Demo tap needed. Never actually installed — the file saved from chat was a .bin, not an .apk.
- 2.6.3 — Crash guards: try/catch, safeToast, null guards. Never actually installed — same .bin problem.
- 2.6.4 — Bottom version label added. Installed via a real .apk link, Nov 1. Error: "Failed resolution of: androidx/collection/ArraySet" — the bundled collection library had no Android classes.
- 2.6.5 — Added ArraySet / ArrayMap / SimpleArrayMap / LruCache. Error: "Failed resolution of: androidx/fragment/app/FragmentActivity" — next missing library class.
- 2.6.6 — Added Fragment / FragmentActivity / FragmentManager stubs. Built, skipped, never tested.
- 2.6.7 — Fragment stubs + one automatic retry + verbose errors (class + cause chain in the transcript). Error: ApiException 10, "GooglePlayServicesMissingManifestValueException" — manifest had no Play Services version tag.
- 2.6.8 — Added manifest meta-data com.google.android.gms.version = 12451000. Result: SUCCESS, Nov 1 evening — watch tap → glasses light up → preview on phone → answer back on the watch. Final blocker was not code: the desktop's LAN address had changed from 192.168.1.227 to 192.168.1.248, so the phone Backend URL was updated to .248.

## Phone app (us.ninjatek.beacon)

- 1.1 — Original phone app. Uninstalled Oct 30.
- 1.2 — Package changed to us.ninjatek.beacon.
- 1.2.1-crashfix — Crash fix, glasses detection after restart. Phone Ask proven Oct 30, one-tap success with watch 2.6.8 on Nov 1. Only phone app left on the phone.

## Demo video

- Submission video: InShot_20261101_190408379.mp4, 90.8 seconds, 1080x1920, branding on, no music, watch answer screenshots at the end. Verified intact, md5 23a074bbf7913278aeaa469ab8a529c8.
