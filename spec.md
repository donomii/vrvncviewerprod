# VR VNC Viewer - Spec Summary

## Purpose
- Android VR viewer to display a desktop stream inside a VR headset (e.g., Google Cardboard or similar).
- Connects to a VNC server or an MJPEG stream on the local network and shows frames on the screen.

## High-level behavior
- Start on a welcome screen.
- Accept a stream URL for VNC or MJPEG.
- Accept a VNC password (optional).
- Provide a left-right flip checkbox; default is checked and mirrors the feed.
- Save URLs for next time and show them in a list.
- Keep the password in memory only for the current session; do not persist it.
- Two buttons: Connect VNC and Connect MJPEG.
- Tap a button to connect to a new stream.
- Failure shows an error and stays on the welcome screen.
- Success shows a stream preview and stays on the welcome screen.
- Start Fullscreen opens a single image in landscape. Cardboard (two eyes) opens two synchronized copies of the same flat feed, clipped and centered in the left and right halves. Each half uses the same mirror and head-pan settings.
- The system Back button returns to the welcome screen.
- A stream loss closes fullscreen or Cardboard view and returns to the welcome screen with the disconnect or error message. Connect again to retry.
- Full-screen mode shows the feed in landscape.
- If the screen is small, scale the feed to fit the screen.
- If the screen is large, pan around the feed using head tracking (gyro + accelerometer).
- Touch input recenters the view.
- Rendering must be efficient enough for low-end devices.

## Implementation details (current)
- Java implementation under `android/`.
- VNC password is used if provided; otherwise fallback password is `aaaaaaaa`.
- Password cache is in-memory only and keyed by URL for the current session.
- Mirror/flip state applies to both preview and full-screen and defaults to enabled.
- Target SDK and compile SDK are set to 35.

## Build and deploy helpers
- `android/run_android.sh` builds, starts an emulator if needed, installs, and launches the app.
- `android/deploy.sh` builds, installs to a connected device, and launches the app.
- Both scripts use the included Gradle 8.2 wrapper. An update installs over the
  existing Android app, preserving its saved stream URLs.

## Assets
- Reuse icon assets from `assets/` and `assets/drawable-*` in the Android project.
