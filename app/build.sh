#!/bin/bash
# Manual (no-Gradle) build for Redowansky Library. Gradle does not work on this VM.
set -e
cd "$(dirname "$0")"

export JAVA_HOME="$HOME/workspace/.jdk/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/workspace/.android-sdk"
PLATFORM="$SDK/platforms/android-34/android.jar"
BT="$SDK/build-tools/34.0.0"
KOTLINC="$HOME/workspace/.kotlin-dist/kotlinc/bin/kotlinc"
STDLIB="$HOME/workspace/.kotlin-dist/kotlinc/lib/kotlin-stdlib.jar"
KS="$HOME/workspace/.secrets/library.keystore"
KS_PASS_FILE="$HOME/workspace/.secrets/library-ks.pass"
MONDIAD_RES="$HOME/workspace/redowansky-library-app/sdk/mondiad-res"

PREBUILT="prebuilt/mondiad-classes.jar:prebuilt/androidx-core-classes.jar:prebuilt/androidx-core-ktx-classes.jar:prebuilt/kotlinx-coroutines-android-1.8.1.jar:prebuilt/kotlinx-coroutines-core-jvm-1.8.1.jar"

for f in "$PLATFORM" "$BT/aapt2" "$BT/d8" "$KOTLINC" "$STDLIB" "$KS" "$KS_PASS_FILE" prebuilt/mondiad-classes.jar; do
  [ -e "$f" ] || { echo "MISSING: $f"; exit 1; }
done

rm -rf build
mkdir -p build/gen build/gen-src/com/mndiad/android/sdk build/classes build/dex

echo "== aapt2 compile (app res + Mondiad SDK res) =="
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" compile --dir "$MONDIAD_RES" -o build/mondiad-res.zip

echo "== aapt2 link =="
"$BT/aapt2" link -o build/base.apk \
  --manifest AndroidManifest.xml \
  -I "$PLATFORM" \
  --java build/gen \
  --min-sdk-version 26 --target-sdk-version 34 \
  build/res.zip build/mondiad-res.zip

echo "== generate Mondiad SDK R in its own package =="
python3 gen_sdk_r.py build/gen/app/pepper/library/R.java build/gen-src/com/mndiad/android/sdk/R.java
javac -cp "$PLATFORM" -d build/classes build/gen-src/com/mndiad/android/sdk/R.java

echo "== kotlinc =="
"$KOTLINC" -nowarn \
  -cp "$PLATFORM:$PREBUILT" \
  -jvm-target 17 -d build/classes \
  $(find src -name '*.kt') \
  build/gen/app/pepper/library/R.java

echo "== d8 =="
"$BT/d8" --min-api 26 --lib "$PLATFORM" --output build/dex \
  $(find build/classes -name '*.class') "$STDLIB" \
  prebuilt/mondiad-classes.jar prebuilt/androidx-core-classes.jar \
  prebuilt/androidx-core-ktx-classes.jar \
  prebuilt/kotlinx-coroutines-android-1.8.1.jar \
  prebuilt/kotlinx-coroutines-core-jvm-1.8.1.jar

echo "== package =="
cp build/base.apk build/unsigned.apk
( cd build/dex && zip -q ../unsigned.apk classes*.dex )
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

KS_PASS=$(cat "$KS_PASS_FILE")
cp build/aligned.apk ../redowansky-library.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" ../redowansky-library.apk

echo "== verify =="
"$BT/apksigner" verify --print-certs ../redowansky-library.apk | head -4
"$BT/aapt" dump badging ../redowansky-library.apk | head -6
ls -la ../redowansky-library.apk
echo "BUILD OK"
