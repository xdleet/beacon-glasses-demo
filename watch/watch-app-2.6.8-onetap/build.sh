#!/bin/bash
# Beacon Wear OS app v2.6.8-onetap — 2.6.4 change only: version label at
# the bottom of the main screen (below the brand, outside the drawer),
# reading BuildConfig.VERSION_NAME/VERSION_CODE. All 2.6.3 crash guards
# preserved; crash itself NOT claimed fixed (logcat is the next step).
# NOTE: aapt2 link does not generate BuildConfig (that is a Gradle
# feature), so step 2b below generates BuildConfig.java from the
# manifest's versionCode/versionName — the manifest stays the single
# source of truth, and MainActivity never hardcodes a version string.
# Beacon Wear OS app v2.6.3-onetap — 2.6.3 changes only: defensive
# crash hardening of the One-tap/trigger send path + answer-render path
# (see MainActivity 2.6.3 comments); layout, flow, paths, and
# 2.6.2 main-transcript result path UNCHANGED.
# Beacon Wear OS app v2.6.2-onetap — 2.6.2 changes only: latest demo answer renders in the MAIN transcript (onResume + live broadcast), Demo button kept as history/fallback; 2.6.1 layout preserved (scrollable screen, One-tap first, 36dp, no Web Chat) — raw-toolchain build (no Gradle).
# 2.6.1 changes only: scrollable main screen + One-tap first in the
# drawer + 36dp buttons (fixes 2.6 clipping One-tap off the round
# screen), and Web Chat button removed at Alan's direction Oct 30.
# COPY of the v2.5-trigger tree plus the /beacon/one_tap button and the
# v2.6 Demo-answer fixes (content-aware dedupe, error slot separate from
# the stored answer, logged listener, pathPrefix + BIND_LISTENER filters).
# The v2.3 original at ~/workspace/watch-app-beacon/ is untouched.
# Uses the JDK + Android SDK symlinked under tools/ (real location:
# ~/workspace/beacon-watch-app/tools, from the earlier Beacon build).
# Uses the JDK + Android SDK symlinked under tools/ (real location:
# ~/workspace/beacon-watch-app/tools, from the earlier Beacon build).
# Play Services Wearable jars live in gms/ (fetched from Google Maven;
# see README.md for the exact download commands to re-fetch them).
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
JDK="$ROOT/tools/jdk"
SDK="$ROOT/tools/sdk"
ANDROID_JAR="$SDK/platforms/android-34-ext12/android.jar"
BT="$SDK/build-tools/android-14"
BUILD="$ROOT/build"
# Signing MUST reuse the v2.3 debug keystore so this installs OVER v2.3
# (same package, same cert, higher versionCode) without an uninstall —
# an uninstall would wipe Alan's saved API key. The keystore lives OUTSIDE
# this repo on purpose and is never committed.
KEYSTORE="$ROOT/../../../watch-app-beacon/debug.keystore"
GMS_CP="$(ls "$ROOT"/gms/*.jar | tr '\n' ':')"
export PATH="$JDK/bin:$PATH"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen"

echo "== 1. compile resources =="
"$BT/aapt2" compile --dir "$ROOT/app/res" -o "$BUILD/compiled.zip"

echo "== 2. link (generates R.java + resource APK) =="
"$BT/aapt2" link -o "$BUILD/unsigned.apk" -I "$ANDROID_JAR" \
  --manifest "$ROOT/app/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  "$BUILD/compiled.zip"

echo "== 2b. generate BuildConfig from manifest (aapt2 does not) =="
VC="$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$ROOT/app/AndroidManifest.xml" | head -1)"
VN="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$ROOT/app/AndroidManifest.xml" | head -1)"
if [ -z "$VC" ] || [ -z "$VN" ]; then echo "FAILED to parse version from manifest" >&2; exit 1; fi
mkdir -p "$BUILD/gen/us/ninjatek/beacon"
cat > "$BUILD/gen/us/ninjatek/beacon/BuildConfig.java" <<EOF
package us.ninjatek.beacon;
public final class BuildConfig {
  public static final int VERSION_CODE = $VC;
  public static final String VERSION_NAME = "$VN";
}
EOF
echo "BuildConfig: VERSION_NAME=$VN VERSION_CODE=$VC"

echo "== 3. compile java =="
find "$ROOT/app/src" "$BUILD/gen" -name "*.java" > "$BUILD/sources.txt"
"$JDK/bin/javac" -source 8 -target 8 -nowarn -encoding UTF-8 -cp "$ANDROID_JAR:$GMS_CP" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "== 4. dex =="
"$JDK/bin/jar" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BT/d8" --output "$BUILD/dex" --lib "$ANDROID_JAR" "$BUILD/classes.jar" "$ROOT"/gms/*.jar

echo "== 5. add dex to apk =="
cd "$BUILD/dex" && zip -q "../unsigned.apk" classes.dex && cd "$ROOT"

echo "== 6. align =="
"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "== 7. keystore (v2.3 key, shared — never copied into this repo) =="
if [ ! -f "$KEYSTORE" ]; then
  echo "MISSING keystore: $KEYSTORE — refusing to sign with a fresh key,"
  echo "because that would force an uninstall and wipe the saved API key."
  exit 1
fi

echo "== 8. sign =="
"$BT/apksigner" sign --ks "$KEYSTORE" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/Beacon-2.6.8-onetap.apk" "$BUILD/aligned.apk"

echo "== 9. verify =="
"$BT/apksigner" verify "$ROOT/Beacon-2.6.8-onetap.apk"
"$BT/aapt" dump badging "$ROOT/Beacon-2.6.8-onetap.apk"
ls -la "$ROOT/Beacon-2.6.8-onetap.apk"
