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
- After the first VNC frame, a quiet connection keeps showing the last image until the desktop changes. The five-second read timeout still applies while waiting for the first frame or receiving an incomplete server message.
- Full-screen mode shows the feed in landscape.
- If the screen is small, scale the feed to fit the screen.
- If the screen is large, pan around the feed using head tracking (gyro + accelerometer).
- Touch input recenters the view.
- Rendering must be efficient enough for low-end devices.

## Implementation details (current)
- Java implementation under `android/`.
- Android negotiates RFB 3.3, 3.7, and 3.8 with unauthenticated access or classic VNC password authentication. [RFB version differences](https://www.rfc-editor.org/rfc/rfc6143.html#appendix-A)
- Android requests CopyRect, ZRLE, Hextile, Raw, DesktopSize, and LastRect in that order. CopyRect copies pixels within the current framebuffer, including when source and destination overlap. Both rectangles must fit within the framebuffer. [CopyRect protocol](https://www.rfc-editor.org/rfc/rfc6143.html#section-7.7.2)
- LastRect ends the current update even when the server advertised more rectangles. It carries no payload. DesktopSize may be followed by LastRect, and the next request still asks for a full frame at the new size. [LastRect protocol](https://github.com/rfbproto/rfbproto/blob/master/rfbproto.rst#lastrect-pseudo-encoding)
- Hextile applies raw or solid-color tiles and optional colored subrectangles to the same framebuffer used by preview, fullscreen, and Cardboard. Tiles have up to 16 by 16 pixels. The decoder validates color carry-over and edge-tile bounds, and rejects an update that paints more than 67,108,864 subrectangle pixels. [Hextile protocol](https://www.rfc-editor.org/rfc/rfc6143.html#section-7.7.4)
- Android ZRLE reads one continuous zlib stream per connection in bounded chunks. It decodes raw, solid, packed-palette, plain run-length, and palette run-length tiles up to 64 by 64 pixels. It rejects invalid lengths, palette indexes, run lengths, and truncated or surplus tile data before publishing the update. [ZRLE protocol](https://www.rfc-editor.org/rfc/rfc6143.html#section-7.7.6)
- Android keeps displaying the previous image while requesting a full update after the server changes resolution. It preserves overlapping pixels internally for servers that send only part of the new frame. Frame dimensions are limited to 4,096 pixels per side, and out-of-bounds Raw rectangles are rejected. [DesktopSize protocol](https://www.rfc-editor.org/rfc/rfc6143.html#section-7.8.2)
- Android ignores desktop names, server clipboard messages, and unsolicited cursor shapes. It reads and discards these payloads in bounded chunks. Desktop names and clipboard messages are limited to 1 MiB each. Cursor shapes are limited to 4,096 pixels per side. An oversized header closes the connection before reading the payload and reports which limit was exceeded. [RFB server messages](https://www.rfc-editor.org/rfc/rfc6143.html#section-7.6)
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

## macOS VNC and MJPEG viewer

Run `./run.sh` to build and open the native viewer on macOS. Edit the single line in `desktop-stream.txt` to select an HTTP or HTTPS multipart MJPEG feed or a `vnc://host[:port]` address. The default is `http://127.0.0.1:8080/`, and VNC uses port 5900 when omitted. Reopen the viewer to connect to a different address or retry after a stream ends.

The resizable window shows the latest decoded frame, fitted to preserve its proportions. Press Space to switch between a single view and two identical views side by side. The status line explains the shortcut and reports a failed or ended stream. The preview clears when a stream ends. An MJPEG frame may occupy up to 8 MiB of JPEG data and measure up to 4,096 pixels per side. Eight seconds without a complete MJPEG frame closes that connection. Closing the window cancels either kind of stream.

VNC is view-only. It uses RFB 3.3, 3.7 or 3.8 with unauthenticated access by default. Set `VRVNC_PASSWORD` in the launcher environment for a server that uses classic VNC password authentication. The viewer uses the first eight password bytes and does not save the password. It requests a full frame initially and after resize. It advertises CopyRect, ZRLE, Hextile, Raw, DesktopSize, and LastRect in that order. CopyRect supports overlapping rectangles and checks both bounds. VNC frame dimensions are limited to 4,096 pixels per side. An unchanged desktop can leave its next update pending. Use a trusted network or an encrypted tunnel for VNC traffic. [RFB protocol specification](https://www.rfc-editor.org/rfc/rfc6143.html)

The desktop client also ends an update on LastRect. After a DesktopSize rectangle, consume the terminator if present and request a full frame at the new size. [LastRect protocol](https://github.com/rfbproto/rfbproto/blob/master/rfbproto.rst#lastrect-pseudo-encoding)

Hextile updates use the same framebuffer as Raw and CopyRect. The decoder supports raw tiles, solid colors, and foreground or individually colored subrectangles. It validates color carry-over and actual edge-tile bounds and limits subrectangle painting to 67,108,864 pixels per update. An invalid tile ends the stream before that update is published. [Hextile protocol](https://www.rfc-editor.org/rfc/rfc6143.html#section-7.7.4)

ZRLE uses the macOS zlib library and preserves compression history across rectangles and framebuffer resizes on the same connection. It decodes raw, solid, packed-palette, plain run-length, and palette run-length tiles. Each compressed payload is bounded by its rectangle size. Invalid tile data or compressed lengths end the stream before that update is published. [ZRLE protocol](https://www.rfc-editor.org/rfc/rfc6143.html#section-7.7.6)

An empty update before the first frame or after a resize keeps the full-frame request active. The previous image stays visible during a resize. The first update with pixels at the new size preserves unchanged pixels in the overlapping area.

`./demo.sh` opens the viewer with a synthetic MJPEG feed served on the same Mac. `./test.sh` builds and runs `-test`, which checks HTTP playback, both composed eye views, the VNC password response, and VNC incremental updates and validation. `./build.sh` and `./install.sh` place the executable in `.build/`. The Android app provides headset motion and mobile viewing.
