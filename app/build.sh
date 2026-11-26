#!/usr/bin/env bash
# Builds weather-tv.apk with only the Android SDK (no Gradle). Normally run by GitHub Actions.
# Needs: ANDROID_SDK, KEYSTORE (PKCS12 file), KEYSTORE_PASS.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:?}; BT=$SDK/build-tools/35.0.0; JAR=$SDK/platforms/android-30/android.jar
rm -rf build && mkdir -p build/classes build/dex
$BT/aapt2 compile --dir res -o build/res.zip
# A new, higher version number for every build (minutes since 1970), so TVs install it as an
# update and refresh their cached copy of the app's icon/banner.
VERSION_CODE=${VERSION_CODE:-$(( $(date +%s) / 60 ))}
$BT/aapt2 link -o build/base.apk -I $JAR --manifest AndroidManifest.xml build/res.zip \
  --version-code "$VERSION_CODE" --version-name "2.$VERSION_CODE" --replace-version
# libs/: AndroidX WebKit (one plain jar, no other dependencies): scripts in every frame of the website.
LIBS=$(ls libs/*.jar | tr '\n' ':')
javac -source 8 -target 8 -bootclasspath $JAR -classpath "$LIBS" -d build/classes $(find src -name '*.java')
(cd build/classes && jar cf ../classes.jar .)
$BT/d8 --min-api 22 --lib $JAR --output build/dex build/classes.jar libs/*.jar
cp build/base.apk build/unsigned.apk && (cd build/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f -p 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks "$KEYSTORE" --ks-type PKCS12 --ks-pass env:KEYSTORE_PASS --out build/weather-tv.apk build/aligned.apk
$BT/apksigner verify build/weather-tv.apk
