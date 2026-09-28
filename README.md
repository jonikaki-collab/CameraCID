# CameraCID

A from-scratch, binary-blob-free Android viewer for cheap WiFi endoscope/IP
cameras (originally shipped with the "YCamera"/"GoSky" app, package
`cn.com.buildwin.YCamera3`). Pure Kotlin — no vendor native library, no
bundled FFmpeg/ijkplayer `.so`, no NDK code at all.

## Why this exists

The original vendor app only shipped 32-bit native libraries and crashed
outright on modern 64-bit-only Android devices. Rather than patch the
vendor's binary, this project reverse-engineers the camera's wire protocol
and reimplements the whole pipeline (RTSP client, RTP/JPEG reassembly,
camera control) in plain Kotlin, so it never depends on a proprietary
`.so` again and works on any current Android version.

## Camera protocol (reverse-engineered)

- The camera hosts its own WiFi AP with a fixed gateway at `192.168.1.1`.
- **Video**: plain RTSP at `rtsp://192.168.1.1:7070/webcam`, UDP transport
  (the camera rejects TCP-interleaved transport with `461 Unsupported
  Transport`). The stream is standard RFC 2435 JPEG-over-RTP (payload type
  26) — confirmed by capturing and hand-decoding real packets. The
  "type-specific"/extension bit is never set in practice, meaning the
  proprietary decoder logic found in the vendor's `libijkffmpeg.so`
  (a custom, non-RFC packet-parsing path) is never actually exercised by
  this camera's stream; the plain RFC 2435 baseline path is all that's
  needed.
- **Camera control**: a separate plaintext protocol on the same host:port
  (`COMMAND /webcam APPO/1.0\r\n\r\n`), e.g. `GETINFO`, `SETSSID`,
  `RESETNET`, `RECSTART`/`RECSTOP`, `ROTATEIMG`.

## Resolution: what "640x480 / 1280x720 / 1600x1200" on the box actually means

The original app's Settings screen lets you pick one of these three
values, but **this is not a real camera capability toggle**:

- The RTSP `DESCRIBE`/SDP response and every captured video frame are
  **always** 640x480 — there is no SDP parameter, SETUP option, or
  BWSocket command that changes the stream's actual resolution.
- The setting only controls the **target size passed to the local photo
  save routine** (`IjkMediaPlayer.takePicture(path, name, width, height,
  ...)`). Selecting 1280x720 passes `(1280, 720)` to the native capture
  call; selecting 640x480 passes `(-1, -1)` (meaning "use the source
  frame's native size"). This is a client-side upscale of the same
  640x480 source frame into a larger JPEG file — it does not add real
  detail.
- The "1600x1200" option is even more telling: the original app's
  `SettingActivity.onOtherButtonClick` never calls
  `saveParameterForPhoto720p(...)` for that branch, only
  `saveParameterForPhoto720petc(2)`. `ControlPanelActivity.takePhoto()`
  only ever branches on the boolean `getParameterForPhoto720p()` (true ->
  1280x720, false -> native), so selecting "1600x1200" doesn't even
  reliably produce a 1600x1200 file in the original app — it falls back
  to whatever the boolean was last set to. This is a genuine bug/gap in
  the vendor app, and further evidence the three "resolutions" were never
  a real hardware feature, just marketing.

**Bottom line**: the sensor/encoder only ever delivers 640x480 over the
wire. CameraCID intentionally does not implement the upscale-on-save
feature, since it provides no real quality benefit.

## Rotate button

The BWSocket `ROTATEIMG` command exists in the protocol but this specific
camera's firmware responds `APPO/1.0 501 Not Implemented` when sent it.
The original app's Rotate button was actually a purely local, client-side
180-degree flip of the rendered video (`IjkVideoView.setRotation180`),
never a network call — CameraCID's Rotate button replicates that local
behavior.

## APN setting — not found

An APN (cellular-style access point) configuration screen was expected to
exist somewhere in the original app, based on memory of using it. A full
search of both decompiled builds (`YCamera3` and the newer `GoSky 5.2.1`)
turned up **no such feature anywhere** — no "APN" string in code or
resources, no cellular-related preference key, no matching protocol
command. The camera has no cellular modem, so a literal APN screen
wouldn't make sense for this hardware anyway. This is either a
misremembering, or a feature specific to a different camera model/app
variant not covered by these two decompiled builds.

## Other undocumented features found in the original app

- **`RESETNET` is a live, fully wired protocol command with no UI trigger
  anywhere in the app.** `BWSocket.resetNet()` sends it and
  `RenameSSIDActivity` has full response handling for it (prompts to jump
  to Android's WiFi settings afterward), but nothing in the app ever
  calls it. Its actual effect on the camera (reboot the radio? factory
  reset the WiFi credentials?) has **not** been tested and isn't
  implemented in CameraCID — sending unknown commands to embedded camera
  firmware risks bricking or resetting it in ways that are hard to
  reverse, so this is left purely as a documented-but-unused finding.
- **`SETPW` (set WiFi password) is a broken stub even in the original
  app.** `BWSocket.setPassword(String)` records the intended request
  state but never actually sends anything over the network — the
  password argument is discarded. `RenameSSIDActivity`'s UI only exposes
  an SSID field, never a password field, confirming this was never
  reachable even by the vendor's own app. Not implemented in CameraCID.
- **`GETINFO` returns more than just the SSID.** The original Help screen
  uses it to show the camera's live firmware version. The full key set is
  `CHIP`, `METHOD`, `SSID`, `VERSION`, `VENDOR` plus protocol/status
  fields — only `SSID` had been exercised in earlier testing.
- **This whole app is a rebrand of a drone/RC flight-controller SDK.**
  `Settings.java` stores (and has getters/setters for) `altitude_hold`,
  `trim_rudd`, `trim_ele`, `trim_ail` (rudder/elevator/aileron trim —
  literal RC transmitter terms), `speed_limit`, and `right_hand_mode` (RC
  stick-layout convention) — none of which are read or written anywhere
  else in the camera app. This matches the earlier Ghidra finding of
  `devicemode` ("Drone/Action DV/etc") and `preferred-video-type` options
  baked into the native ijkplayer library: the vendor built one shared
  native+Java stack for their drone product line and reused it for this
  camera with the flight-control UI simply stripped out.
- **Photo/Video list screens support multi-select batch delete**
  (checkbox selection + "select all"), shared by `PhotoListActivity` and
  `VideoListActivity` via a common base class. Minor, but not previously
  documented here.

## Architecture

- `RtspClient.kt` — RTSP handshake (OPTIONS/DESCRIBE/SETUP/PLAY/TEARDOWN)
  and UDP RTP/RTCP socket handling.
- `RtpJpegReassembler.kt` — RFC 2435 JPEG-over-RTP fragment reassembly
  into standalone JPEG frames, including duplicate/lost-fragment
  detection (frames with an RTP sequence gap or a stray fragment from a
  different RTP timestamp are discarded rather than displayed corrupted).
- `JpegTables.kt` — standard JPEG Huffman tables and the RFC 2435
  Q-factor quantization table scaling algorithm.
- `BwSocketClient.kt` — the camera's plaintext control protocol.
- `AviMuxer.kt` — minimal Motion-JPEG AVI writer for video recording.
- `MainActivity.kt` — live preview (decoded via Android's built-in
  `BitmapFactory`, not a bundled codec), photo capture, video recording,
  mirror/rotate.

## Building

Requires a JDK 17, the Android SDK (compileSdk 34, build-tools 34.0.0),
and Gradle 8.7. From the project root:

```
gradle assembleDebug
```
