#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SDK_ROOT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BUILD_TOOLS="${BUILD_TOOLS:-$SDK_ROOT/build-tools/36.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$SDK_ROOT/platforms/android-36/android.jar}"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$SDK_ROOT/cmdline-tools/latest/bin/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

rm -rf "$ROOT/build"
mkdir -p \
  "$ROOT/build/stub-classes" \
  "$ROOT/build/compiled" \
  "$ROOT/build/gen" \
  "$ROOT/build/classes" \
  "$ROOT/build/dex"

javac -source 8 -target 8 \
  -d "$ROOT/build/stub-classes" \
  $(find "$ROOT/stub/src" -name '*.java')
jar cf "$ROOT/build/xposed-stubs.jar" -C "$ROOT/build/stub-classes" .

"$AAPT2" compile --dir "$ROOT/app/src/main/res" -o "$ROOT/build/compiled/res.zip"
"$AAPT2" link \
  -o "$ROOT/build/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --java "$ROOT/build/gen" \
  -A "$ROOT/app/src/main/assets" \
  "$ROOT/build/compiled/res.zip"

javac -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ROOT/build/gen:$ROOT/build/xposed-stubs.jar" \
  -d "$ROOT/build/classes" \
  $(find "$ROOT/build/gen" "$ROOT/app/src/main/java" -name '*.java')

"$D8" --min-api 30 --output "$ROOT/build/dex" $(find "$ROOT/build/classes" -name '*.class')
(
  cd "$ROOT/build/dex"
  zip -q "$ROOT/build/base.apk" classes.dex
)
(
  cd "$ROOT/app/src/main/resources"
  zip -qr "$ROOT/build/base.apk" META-INF
)

"$ZIPALIGN" -f 4 "$ROOT/build/base.apk" "$ROOT/build/aligned.apk"
"$APKSIGNER" sign \
  --ks "${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}" \
  --ks-pass "pass:${DEBUG_KEYSTORE_PASS:-android}" \
  --key-pass "pass:${DEBUG_KEY_PASS:-android}" \
  --out "$ROOT/build/pictorial-sync-lsposed.apk" \
  "$ROOT/build/aligned.apk"
"$APKSIGNER" verify "$ROOT/build/pictorial-sync-lsposed.apk"

ls -lh "$ROOT/build/pictorial-sync-lsposed.apk"

