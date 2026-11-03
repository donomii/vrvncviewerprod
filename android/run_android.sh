#!/bin/bash
set -e

echo "=== Android Build and Run Script ==="

export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$PATH:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"

cd "$(dirname "$0")"

PACKAGE="com.praeceptamachinae.vrvncviewer"
ACTIVITY="com.praeceptamachinae.vrvncviewer.MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

echo "Building APK..."
if [ -x "./gradlew" ]; then
    ./gradlew assembleDebug
elif command -v gradle >/dev/null 2>&1; then
    gradle assembleDebug
else
    echo "Error: gradle not found. Install Gradle or add ./gradlew."
    exit 1
fi

DEVICE=$(adb devices | grep -w "device" | grep -v "List" | head -n 1 | awk '{print $1}')
if [ -z "$DEVICE" ]; then
    echo "No emulator running. Starting emulator..."
    AVD=$(emulator -list-avds | head -n 1)
    if [ -z "$AVD" ]; then
        echo "Error: No AVD found. Create one using Android Studio."
        exit 1
    fi

    echo "Starting AVD: $AVD"
    emulator -avd "$AVD" -no-snapshot-load &

    echo "Waiting for emulator..."
    adb -e wait-for-device

    while [ "$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        sleep 2
        echo -n "."
    done
    echo ""
    echo "Emulator ready."
    DEVICE=$(adb -e get-serialno)
else
    echo "Using device: $DEVICE"
fi

if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found at $APK_PATH"
    exit 1
fi

echo "Installing APK..."
adb -s "$DEVICE" install -r "$APK_PATH"

echo "Launching app..."
adb -s "$DEVICE" shell am start -n "$PACKAGE/$ACTIVITY"

echo "Done!"
