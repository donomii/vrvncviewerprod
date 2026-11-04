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
- Connecting again clears the preview and switches to the new stream immediately. The previous stream closes in the background. Only the selected connection can update the preview and connection status. A successful connection saves the submitted address, even if the URL field has since changed.
- Start Fullscreen opens a single image in landscape. Cardboard (two eyes) opens two synchronized copies of the same flat feed, clipped and centered in the left and right halves. Each half uses the same mirror and head-pan settings.
- The system Back button returns to the welcome screen.
- A stream loss clears the preview, closes fullscreen or Cardboard view, and returns to the welcome screen with the disconnect or error message. Connect again to retry.
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

## macOS MJPEG viewer

Run `./run.sh` to build and open the native viewer on macOS. Edit the single line in `desktop-stream.txt` to select an HTTP or HTTPS multipart MJPEG feed. The default is `http://127.0.0.1:8080/`. Reopen the viewer to connect to a different address or retry after a stream ends.

The resizable window shows the latest decoded frame, fitted to preserve its proportions. Press Space to switch between a single view and two identical views side by side. The status line explains the shortcut and reports a failed or ended stream. The preview clears when a stream ends. A frame may occupy up to 8 MiB of JPEG data and measure up to 4,096 pixels per side. Eight seconds without a complete frame closes the connection. Closing the window cancels the stream.

`./demo.sh` opens the viewer with a synthetic feed served on the same Mac. `./test.sh` builds and runs `-test`, which receives a demo frame over HTTP and checks the composed pixels in both eye views. `./build.sh` and `./install.sh` place the executable in `.build/`. The Android app provides VNC, headset motion and mobile viewing.
