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
#   ./emulator.sh -N         test fix method N for websites that work in Chrome but not here
#                            (e.g. ./emulator.sh -3). See testing websites/README.md
#   ./emulator.sh methods    list the test methods
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
# Android 16 TV: its built-in web engine (WebView) is recent, like an up-to-date Fire TV's.
# (The old Android 12 image had a 2021 engine that many websites no longer work on.)
IMAGE="system-images;android-36;android-tv;arm64-v8a"
AVD=video-tv-36
APK_URL="https://github.com/tylerhartmanbiz/weather-tv/releases/latest/download/weather-tv.apk"
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"
BOOTSTRAP=/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin/sdkmanager

setup() {
  [ -d "$JAVA_HOME" ] || brew install openjdk@17
  if [ ! -x "$EMU" ] || [ ! -d "$ANDROID_HOME/system-images/android-36/android-tv/arm64-v8a" ]; then
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

EMU_FLAGS=""   # extra emulator options (test methods); the running TV is restarted if they change
FLAGS_FILE="$ANDROID_HOME/emulator-flags.txt"

boot() {
  # An older virtual TV (e.g. the Android 12 one), or one started with other test options,
  # still running? Turn it off first.
  if "$ADB" devices | grep -q '^emulator-'; then
    if [ "$("$ADB" emu avd name 2>/dev/null | head -1 | tr -d '\r')" != "$AVD" ] \
       || [ "$(cat "$FLAGS_FILE" 2>/dev/null)" != "$EMU_FLAGS" ] || [[ "$EMU_FLAGS" == *-wipe-data* ]]; then
      echo "Restarting the virtual TV${EMU_FLAGS:+ with: $EMU_FLAGS}..."
      "$ADB" emu kill >/dev/null 2>&1 || true
      while "$ADB" devices | grep -q '^emulator-'; do sleep 1; done
    fi
  fi
  if ! "$ADB" devices | grep -q '^emulator-'; then
    echo "Starting the virtual TV (a window will open)..."
    # -no-snapshot: fresh start every time, nothing the website did is kept between runs.
    # shellcheck disable=SC2086
    nohup "$EMU" -avd "$AVD" -no-snapshot -no-boot-anim -netfast $EMU_FLAGS >"$ANDROID_HOME/emulator.log" 2>&1 &
    echo "$EMU_FLAGS" > "$FLAGS_FILE"
  fi
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
  echo "Web engine on the virtual TV: Chrome $("$ADB" shell dumpsys webviewupdate | sed -n 's/.*Current WebView package (name, version): ([^,]*, \([0-9.]*\)).*/\1/p' | tr -d '\r')"
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

# ---------- test methods (testing websites/README.md) ----------
TESTDIR="$ROOT/testing websites"

list_methods() {  # the table in Methods.java is the one list of methods
  sed -n 's/.*\/\* \([0-9]*\) \*\/ {.*, "\(.*\)"},.*/  -\1  \2/p' app/src/app/weathertv/Methods.java
}

build_test_apk() {  # build from this folder, only if the code changed since the last build
  local sum; sum=$(find app/src app/res app/AndroidManifest.xml app/build.sh -type f -exec shasum {} + | shasum | cut -c1-40)
  if [ ! -f app/build/weather-tv.apk ] || [ "$(cat app/build/.source-sum 2>/dev/null)" != "$sum" ]; then
    echo "Building the test app from this folder..."
    KEYSTORE="$ROOT/signing-key.p12" KEYSTORE_PASS="$(cat "$ROOT/signing-key-password.txt")" \
      ANDROID_SDK="$ANDROID_HOME" bash app/build.sh >/dev/null
    echo "$sum" > app/build/.source-sum
  fi
}

# The video website's domain(s), read from your settings by this script, so the saved log can
# say <site> instead (the log can then be shared, e.g. with Claude, without the address).
site_domains() {
  local f=docs/config-noclaude.json; [ -f "$f" ] || f=docs/config.json
  python3 -I -c 'import re,sys
t=open(sys.argv[1],encoding="utf-8",errors="ignore").read()
for h in sorted({".".join(h.lower().split(".")[-2:]) for h in re.findall(r"https?://([A-Za-z0-9.-]+)",t)}):print(h)' "$f" 2>/dev/null \
    | grep -vxE 'media-imdb\.com|imdb\.com|tvmaze\.com' || true
}

redact() {  # stdin -> stdout, every site domain (and its subdomains) becomes <site>
  SITES="$(site_domains | tr '\n' ' ')" perl -pe 'BEGIN{$|=1; @s=split " ",$ENV{SITES}} for my $d (@s){s/(?:[a-z0-9-]+\.)*\Q$d\E/<site>/gi}'
}

run_method() {  # $1 = method number
  local n=$1 desc
  desc=$(list_methods | sed -n "s/^  -$n  //p")
  [ -n "$desc" ] || { echo "There's no method $n. The methods are:"; list_methods; exit 1; }
  local private_dns=no
  case $n in
    5) private_dns=yes ;;
    6) EMU_FLAGS="-dns-server 1.1.1.1,8.8.8.8" ;;
    26) EMU_FLAGS="-gpu host" ;;
    27) EMU_FLAGS="-gpu swiftshader_indirect" ;;
    28) EMU_FLAGS="-memory 4096" ;;
    29) EMU_FLAGS="-wipe-data" ;;
    33|34) EMU_FLAGS="-gpu host -memory 4096"; private_dns=yes ;;
  esac
  echo "=== Method $n: $desc ==="
  setup; boot
  [ "$EMU_FLAGS" = "-wipe-data" ] && echo "" > "$FLAGS_FILE"   # wipe once, not every run
  if [ $private_dns = yes ]; then
    "$ADB" shell settings put global private_dns_mode hostname
    "$ADB" shell settings put global private_dns_specifier dns.google
    echo "Encrypted DNS (dns.google) is on for the virtual TV."
  else
    "$ADB" shell settings put global private_dns_mode opportunistic
  fi
  build_test_apk
  install_apk app/build/weather-tv.apk
  "$ADB" shell pm clear app.weathertv >/dev/null    # start clean: no cookies/cache from the last method
  "$ADB" shell am force-stop app.weathertv
  "$ADB" logcat -c
  "$ADB" shell am start -n app.weathertv/.MainActivity --ei method "$n" >/dev/null
  mkdir -p "$TESTDIR/logs"
  local log="$TESTDIR/logs/method-$n.txt"
  echo
  echo "The app is open in the emulator with method $n. Search a title, press Open, and try to"
  echo "play the video on the website (arrows move the cursor, Enter = click, Esc = Back)."
  echo "To see the website's own errors: open chrome://inspect in Chrome on this Mac (> inspect)."
  echo "Watching what the app does below (also saved to testing websites/logs/method-$n.txt)."
  echo "Press Ctrl+C when you're done, then say whether method $n worked."
  echo
  trap 'echo; echo "Saved: testing websites/logs/method-$n.txt"; exit 0' INT
  "$ADB" logcat -v time VideoTV:I AndroidRuntime:E chromium:E '*:S' | redact | tee "$log"
}

case "${1:-}" in
  -[0-9]|-[0-9][0-9]) run_method "${1#-}" ;;
  methods) list_methods ;;
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
  *) sed -n '2,19p' "$0" ;;
esac
