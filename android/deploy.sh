#!/bin/bash
set -e

echo "=== Android Deploy Script ==="

export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$PATH:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"

cd "$(dirname "$0")"

PACKAGE="com.praeceptamachinae.vrvncviewer"
ACTIVITY="com.praeceptamachinae.vrvncviewer.MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

DEVICE=$(adb devices | grep -w "device" | grep -v "List" | head -n 1 | awk '{print $1}')
if [ -z "$DEVICE" ]; then
    echo "Error: No device connected. Please connect a phone via USB and enable USB debugging."
    exit 1
fi

echo "Found device: $DEVICE"

echo "Building APK..."
if [ -x "./gradlew" ]; then
    ./gradlew assembleDebug
elif command -v gradle >/dev/null 2>&1; then
    gradle assembleDebug
else
    echo "Error: gradle not found. Install Gradle or add ./gradlew."
    exit 1
fi

if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found at $APK_PATH"
    exit 1
fi

echo "Installing APK on device..."
adb -s "$DEVICE" install -r "$APK_PATH"

echo "Launching app..."
adb -s "$DEVICE" shell am start -n "$PACKAGE/$ACTIVITY"

echo "Deploy complete! App launched on $DEVICE"
