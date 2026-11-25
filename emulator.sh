#!/usr/bin/env bash
# Test the TV app on this Mac, no Fire TV needed. An Android TV emulator (a virtual TV in a
# window) runs the real app: the same APK the Fire TV downloads, the same Android WebView
# sandbox, the same live page + config.json from GitHub.
#
#   ./emulator.sh            start the virtual TV with the released app (what the Fire TV gets)
#   ./emulator.sh local      build the app from this folder and test that instead (before pushing)
#   ./emulator.sh logs       watch what the app does: video links found, popups/downloads/redirects blocked
#   ./emulator.sh logs FILE  same, but save to FILE instead of the screen (e.g. debug/output/tv-log.txt)
#   ./emulator.sh stop       turn the virtual TV off
#
# Remote control = your keyboard, with the emulator window focused:
#   arrow keys = D-pad   Enter = OK/select   Esc = Back   Cmd+M = Menu (reload)
#   (or click "..." on the emulator's side bar > Directional pad for an on-screen remote)
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$ROOT/.android-sdk" ANDROID_SDK_ROOT="$ROOT/.android-sdk"
IMAGE="system-images;android-31;android-tv;arm64-v8a"   # Android 12 TV (Fire OS 8 is based on Android 11)
AVD=video-tv
APK_URL="https://github.com/tylerhartmanbiz/weather-tv/releases/latest/download/weather-tv.apk"
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"
BOOTSTRAP=/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin/sdkmanager

setup() {
  [ -d "$JAVA_HOME" ] || brew install openjdk@17
  if [ ! -x "$EMU" ] || [ ! -d "$ANDROID_HOME/system-images/android-31" ]; then
    [ -x "$BOOTSTRAP" ] || brew install --cask android-commandlinetools
    echo "First run: downloading the Android TV emulator (~2 GB, into .android-sdk/)..."
    yes | "$BOOTSTRAP" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
    "$BOOTSTRAP" --sdk_root="$ANDROID_HOME" "cmdline-tools;latest" "platform-tools" "emulator" \
      "platforms;android-30" "build-tools;35.0.0" "$IMAGE" | grep -v '^\[' || true
  fi
  if ! "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" list avd -c 2>/dev/null | grep -qx "$AVD"; then
    echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d tv_1080p >/dev/null
    # Keyboard works as the remote; a bit more RAM like a real Fire TV stick 4K.
    printf 'hw.keyboard=yes\nhw.ramSize=2048\nhw.dPad=yes\n' >> "$HOME/.android/avd/$AVD.avd/config.ini"
  fi
}

boot() {
  if ! "$ADB" devices | grep -q '^emulator-'; then
    echo "Starting the virtual TV (a window will open)..."
    # -no-snapshot: fresh start every time, nothing the website did is kept between runs.
    nohup "$EMU" -avd "$AVD" -no-snapshot -no-boot-anim -netfast >"$ANDROID_HOME/emulator.log" 2>&1 &
  fi
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
}

install_apk() {  # $1 = path to apk
  "$ADB" install -r "$1" >/dev/null 2>&1 || { "$ADB" uninstall app.weathertv >/dev/null 2>&1 || true; "$ADB" install "$1" >/dev/null; }
  echo "Installed $("$ADB" shell dumpsys package app.weathertv | grep -m1 versionName | tr -d ' \r')"
}

launch() {
  "$ADB" shell am force-stop app.weathertv
  "$ADB" shell am start -n app.weathertv/.MainActivity >/dev/null
  echo "App is open in the emulator window. Keyboard = remote: arrows, Enter = OK, Esc = Back, Cmd+M = Menu."
  echo "Run ./emulator.sh logs in another terminal to watch what it does."
}

case "${1:-}" in
  ""|start)
    setup; boot
    tmp=$(mktemp -d)
    # Retry: while GitHub is publishing a new build, the download briefly returns 404.
    curl -fsSL --retry 12 --retry-delay 5 --retry-all-errors -o "$tmp/app.apk" "$APK_URL" \
      || { echo "Couldn't download the app. Is the Build APK action still running? Try again in a minute."; exit 1; }
    install_apk "$tmp/app.apk"; rm -rf "$tmp"
    launch ;;
  local)
    setup; boot
    KEYSTORE="$ROOT/signing-key.p12" KEYSTORE_PASS="$(cat "$ROOT/signing-key-password.txt")" \
      ANDROID_SDK="$ANDROID_HOME" bash app/build.sh >/dev/null
    install_apk app/build/weather-tv.apk
    launch ;;
  logs)
    "$ADB" logcat -c
    if [ -n "${2:-}" ]; then
      mkdir -p "$(dirname "$2")"
      echo "Saving what the app does to $2 (press Open on the emulator, then Ctrl+C to stop)..."
      "$ADB" logcat -v time VideoTV:I AndroidRuntime:E '*:S' > "$2"
    else
      echo "Watching the app (Ctrl+C to stop)..."
      "$ADB" logcat -v time VideoTV:I AndroidRuntime:E '*:S'
    fi ;;
  stop)
    "$ADB" emu kill >/dev/null 2>&1 || true; echo "Virtual TV turned off." ;;
  *) sed -n '2,16p' "$0" ;;
esac
