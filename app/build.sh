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

PREBUILT="prebuilt/mondiad-classes.jar:prebuilt/mpv-classes.jar:prebuilt/androidx-core-classes.jar:prebuilt/androidx-core-ktx-classes.jar:prebuilt/kotlinx-coroutines-android-1.8.1.jar:prebuilt/kotlinx-coroutines-core-jvm-1.8.1.jar:prebuilt/gms-auth-classes.jar:prebuilt/gms-auth-base-classes.jar:prebuilt/gms-base-classes.jar:prebuilt/gms-basement-classes.jar:prebuilt/gms-tasks-classes.jar:prebuilt/ax-fragment-1.0.0-classes.jar:prebuilt/ax-recyclerview-1.1.0-classes.jar:prebuilt/ax-customview-1.0.0-classes.jar:prebuilt/ax-versionedparcelable-1.1.0-classes.jar:prebuilt/ax-interpolator-1.0.0-classes.jar:prebuilt/ax-loader-1.0.0-classes.jar:prebuilt/ax-viewpager-classes.jar:prebuilt/ax-annotation.jar:prebuilt/ax-collection.jar:prebuilt/ax-lifecycle-common-2.7.0.jar:prebuilt/ax-lifecycle-runtime-2.7.0-classes.jar:prebuilt/ax-lifecycle-viewmodel-2.0.0-classes.jar:prebuilt/ax-lifecycle-livedata-2.0.0-classes.jar:prebuilt/ax-lifecycle-livedata-core-2.0.0-classes.jar:prebuilt/ax-core-common.jar:prebuilt/ax-core-runtime-2.0.0-classes.jar:prebuilt/ax-concurrent-futures.jar:prebuilt/ax-listenablefuture.jar:prebuilt/gms-fido-classes.jar"
GMS_JARS="prebuilt/gms-auth-classes.jar prebuilt/gms-auth-base-classes.jar prebuilt/gms-base-classes.jar prebuilt/gms-basement-classes.jar prebuilt/gms-tasks-classes.jar prebuilt/ax-fragment-1.0.0-classes.jar prebuilt/ax-recyclerview-1.1.0-classes.jar prebuilt/ax-customview-1.0.0-classes.jar prebuilt/ax-versionedparcelable-1.1.0-classes.jar prebuilt/ax-interpolator-1.0.0-classes.jar prebuilt/ax-loader-1.0.0-classes.jar prebuilt/ax-viewpager-classes.jar prebuilt/ax-annotation.jar prebuilt/ax-collection.jar prebuilt/ax-lifecycle-common-2.7.0.jar prebuilt/ax-lifecycle-runtime-2.7.0-classes.jar prebuilt/ax-lifecycle-viewmodel-2.0.0-classes.jar prebuilt/ax-lifecycle-livedata-2.0.0-classes.jar prebuilt/ax-lifecycle-livedata-core-2.0.0-classes.jar prebuilt/ax-core-common.jar prebuilt/ax-core-runtime-2.0.0-classes.jar prebuilt/ax-concurrent-futures.jar prebuilt/ax-listenablefuture.jar prebuilt/gms-fido-classes.jar"

for f in "$PLATFORM" "$BT/aapt2" "$BT/d8" "$KOTLINC" "$STDLIB" "$KS" "$KS_PASS_FILE" prebuilt/mondiad-classes.jar prebuilt/mpv-classes.jar prebuilt/jni/arm64-v8a/libmpv.so assets/cacert.pem; do
  [ -e "$f" ] || { echo "MISSING: $f"; exit 1; }
done

rm -rf build
mkdir -p build/gen build/gen-src/androidx/core build/gen-src/com/mndiad/android/sdk build/gen-src/is/xyz/mpv build/gen-src/com/google/android/gms/base build/gen-src/com/google/android/gms/common build/gen-src/androidx/lifecycle/runtime build/gen-src/androidx/recyclerview build/gen-src/androidx/core build/classes build/dex build/lib/arm64-v8a

echo "== aapt2 compile (app + Mondiad + mpv res) =="
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" compile --dir "$MONDIAD_RES" -o build/mondiad-res.zip
"$BT/aapt2" compile --dir "$MPV_RES" -o build/mpv-res.zip
"$BT/aapt2" compile --dir "$GMS_RES/auth-res" -o build/gms-auth-res.zip
"$BT/aapt2" compile --dir "$GMS_RES/base-res" -o build/gms-base-res.zip
"$BT/aapt2" compile --dir "$GMS_RES/basement-res" -o build/gms-basement-res.zip
"$BT/aapt2" compile --dir "$GMS_RES/lifecycle-runtime-res" -o build/gms-lcrt-res.zip
"$BT/aapt2" compile --dir "$PROJ/sdk/ax-res/recyclerview-res" -o build/ax-rv-res.zip
"$BT/aapt2" compile --dir "$PROJ/sdk/ax-res/core-res" -o build/ax-core-res.zip

echo "== aapt2 link =="
"$BT/aapt2" link -o build/base.apk \
  --manifest AndroidManifest.xml \
  -I "$PLATFORM" \
  -A assets \
  --java build/gen \
  --min-sdk-version 26 --target-sdk-version 34 \
  build/res.zip build/mondiad-res.zip build/mpv-res.zip build/gms-auth-res.zip build/gms-base-res.zip build/gms-basement-res.zip build/gms-lcrt-res.zip build/ax-rv-res.zip build/ax-core-res.zip

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
python3 gen_lib_r.py "$APPR" build/gen-src/androidx/lifecycle/runtime/R.java androidx.lifecycle.runtime \
  "id:*"
python3 gen_lib_r.py "$APPR" build/gen-src/androidx/recyclerview/R.java androidx.recyclerview \
  "attr:*;color:*;dimen:*;drawable:*;id:*;integer:*;layout:*;string:*;style:*;styleable:*"
python3 gen_lib_r.py "$APPR" build/gen-src/androidx/core/R.java androidx.core \
  "attr:*;color:*;dimen:*;drawable:*;id:*;integer:*;layout:*;string:*;style:*;styleable:*"
javac -cp "$PLATFORM" -d build/classes \
  build/gen-src/com/mndiad/android/sdk/R.java build/gen-src/is/xyz/mpv/R.java \
  build/gen-src/com/google/android/gms/base/R.java build/gen-src/com/google/android/gms/common/R.java build/gen-src/androidx/lifecycle/runtime/R.java build/gen-src/androidx/recyclerview/R.java build/gen-src/androidx/core/R.java

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
