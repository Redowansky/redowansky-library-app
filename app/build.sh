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
PROJ="$HOME/workspace/redowansky-library-app"
MONDIAD_RES="$PROJ/sdk/mondiad-res"
MPV_RES="$PROJ/sdk/mpv-res"
GMS_RES="$PROJ/sdk/gms-res"

PREBUILT="prebuilt/mondiad-classes.jar:prebuilt/mpv-classes.jar:prebuilt/androidx-core-classes.jar:prebuilt/androidx-core-ktx-classes.jar:prebuilt/kotlinx-coroutines-android-1.8.1.jar:prebuilt/kotlinx-coroutines-core-jvm-1.8.1.jar:prebuilt/gms-auth-classes.jar:prebuilt/gms-auth-base-classes.jar:prebuilt/gms-base-classes.jar:prebuilt/gms-basement-classes.jar:prebuilt/gms-tasks-classes.jar"
GMS_JARS="prebuilt/gms-auth-classes.jar prebuilt/gms-auth-base-classes.jar prebuilt/gms-base-classes.jar prebuilt/gms-basement-classes.jar prebuilt/gms-tasks-classes.jar"

for f in "$PLATFORM" "$BT/aapt2" "$BT/d8" "$KOTLINC" "$STDLIB" "$KS" "$KS_PASS_FILE" prebuilt/mondiad-classes.jar prebuilt/mpv-classes.jar prebuilt/jni/arm64-v8a/libmpv.so assets/cacert.pem; do
  [ -e "$f" ] || { echo "MISSING: $f"; exit 1; }
done

rm -rf build
mkdir -p build/gen build/gen-src/com/mndiad/android/sdk build/gen-src/is/xyz/mpv build/gen-src/com/google/android/gms/base build/gen-src/com/google/android/gms/common build/classes build/dex build/lib/arm64-v8a

echo "== aapt2 compile (app + Mondiad + mpv res) =="
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" compile --dir "$MONDIAD_RES" -o build/mondiad-res.zip
"$BT/aapt2" compile --dir "$MPV_RES" -o build/mpv-res.zip
"$BT/aapt2" compile --dir "$GMS_RES/auth-res" -o build/gms-auth-res.zip
"$BT/aapt2" compile --dir "$GMS_RES/base-res" -o build/gms-base-res.zip
"$BT/aapt2" compile --dir "$GMS_RES/basement-res" -o build/gms-basement-res.zip

echo "== aapt2 link =="
"$BT/aapt2" link -o build/base.apk \
  --manifest AndroidManifest.xml \
  -I "$PLATFORM" \
  -A assets \
  --java build/gen \
  --min-sdk-version 26 --target-sdk-version 34 \
  build/res.zip build/mondiad-res.zip build/mpv-res.zip build/gms-auth-res.zip build/gms-base-res.zip build/gms-basement-res.zip

echo "== generate library R classes (Mondiad SDK + mpv-lib) =="
APPR=build/gen/app/pepper/library/R.java
python3 gen_lib_r.py "$APPR" build/gen-src/com/mndiad/android/sdk/R.java com.mndiad.android.sdk \
  "attr:zone_id,position,size,offset,apply_safe_offset;drawable:mnd_;style:Theme_Transparent;styleable:BannerView"
python3 gen_lib_r.py "$APPR" build/gen-src/is/xyz/mpv/R.java is.xyz.mpv \
  "string:*"
python3 gen_lib_r.py "$APPR" build/gen-src/com/google/android/gms/base/R.java com.google.android.gms.base \
  "attr:*;color:*;drawable:*;id:*;string:*;styleable:*"
python3 gen_lib_r.py "$APPR" build/gen-src/com/google/android/gms/common/R.java com.google.android.gms.common \
  "integer:*;string:*"
javac -cp "$PLATFORM" -d build/classes \
  build/gen-src/com/mndiad/android/sdk/R.java build/gen-src/is/xyz/mpv/R.java \
  build/gen-src/com/google/android/gms/base/R.java build/gen-src/com/google/android/gms/common/R.java

echo "== kotlinc =="
"$KOTLINC" -nowarn \
  -cp "$PLATFORM:$PREBUILT" \
  -jvm-target 17 -d build/classes \
  $(find src -name '*.kt') \
  build/gen/app/pepper/library/R.java

echo "== d8 =="
"$BT/d8" --min-api 26 --lib "$PLATFORM" --output build/dex \
  $(find build/classes -name '*.class') "$STDLIB" \
  prebuilt/mondiad-classes.jar prebuilt/mpv-classes.jar \
  prebuilt/androidx-core-classes.jar prebuilt/androidx-core-ktx-classes.jar \
  prebuilt/kotlinx-coroutines-android-1.8.1.jar \
  prebuilt/kotlinx-coroutines-core-jvm-1.8.1.jar \
  $GMS_JARS

echo "== package =="
cp build/base.apk build/unsigned.apk
cp prebuilt/jni/arm64-v8a/*.so build/lib/arm64-v8a/
( cd build/dex && zip -q ../unsigned.apk classes*.dex )
( cd build && zip -q unsigned.apk lib/arm64-v8a/*.so )
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

KS_PASS=$(cat "$KS_PASS_FILE")
cp build/aligned.apk ../redowansky-library.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" ../redowansky-library.apk

echo "== verify =="
"$BT/apksigner" verify --print-certs ../redowansky-library.apk | head -4
"$BT/aapt" dump badging ../redowansky-library.apk | head -8
ls -la ../redowansky-library.apk
echo "BUILD OK"
