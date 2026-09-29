# CameraCID

A from-scratch, binary-blob-free Android viewer for cheap WiFi endoscope/IP
cameras — originally shipped as the **"YPC99"** wireless endoscope (the
model name printed on the device itself), running the "YCamera"/"GoSky"
app, package `cn.com.buildwin.YCamera3`. Pure Kotlin — no vendor native
library, no bundled FFmpeg/ijkplayer `.so`, no NDK code at all.

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

## Known security issues (this is the same hardware as other rebrands)

This camera is not a one-off design — it's a white-label Appotech AX3268
reference module sold under many different brand names. [Security
research published on the "Kerui" endoscope
camera](https://utkusen.com/blog/multiple-vulnerabilities-on-kerui-endoscope-camera)
(July 2018) describes the **identical** hardware and protocol: same chip,
same `APPO/1.0` protocol on port 7070, same firmware version naming
scheme (`EVJ-2_20180329` there vs. this unit's `EVJ-2_20190415a` — same
family, later build), and the same "starts with endoscope" WiFi SSID
convention (this unit's is literally `Endoscope_abadaf`). This is almost
certainly the same firmware lineage, not just similar hardware.

That disclosure documents:

- **No WiFi password by default** — the camera's own hotspot is
  unprotected, so anyone in range can join and access the stream/control
  protocol.
- **No authentication anywhere** — neither the RTSP stream nor the
  plaintext control protocol requires any credential; anyone who can join
  the WiFi can send any command.
- **Command injection via `SETSSID`** — a crafted SSID value (e.g.
  `;ping 192.168.1.101`) can execute arbitrary shell commands on the
  camera itself (blind RCE), limited only by the WiFi SSID's 32-character
  field. This is presumably still present in this unit's firmware, since
  it's a later build of the same lineage, not a rewrite.

CameraCID never implements `SETSSID` (or any WiFi-configuration feature)
precisely to avoid touching this — see "APN setting — not found" above
for the related decision not to build WiFi-config features into this app
at all. But the vulnerability lives in the **camera's firmware**, not in
any particular client app, so it's exploitable by anyone on the network
regardless of what app they use, including the original vendor app.

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

## Live `GETINFO` capture from this camera

CameraCID has an `(i)` button (top-right) that sends `GETINFO` and shows
the parsed response — it queries fresh every time it's tapped, nothing is
cached. A real capture from this unit:

```
VENDOR:           APPOTECH
CHIP:             AX3268
VERSION:          EVJ-2_20190415a
SSID:             Endoscope
CHANNEL:          11
SENSOR_DIRECTION: 16
PIXEL:            0
QUALITY:          2
FRAMERATE:        24
TYPE:             DRONE
```

`TYPE: DRONE` is the camera's own firmware directly confirming the
drone/RC-SDK-rebrand finding below — this isn't just dead code in the
Android app, the embedded firmware itself still self-identifies by its
original product line.

`SENSOR_DIRECTION` is almost certainly a fixed hardware/manufacturing
constant (how the sensor die is physically mounted on the PCB, so the
firmware knows whether to compensate for a flipped mount) rather than a
live, changeable orientation state — consistent with `ROTATEIMG` being
unimplemented on this unit and no `SETSENSORDIRECTION`-style command
existing anywhere in the protocol.

The chip itself checks out too: [Appotech's own product page](https://www.en.appotech.com/MPdecoderchip-5.html)
lists the AX3268 as supporting up to **1280x720@40fps**, with **"Wi-Fi
aerial photography" (drone)** and "IP CAM" as its listed applications —
independent, chip-manufacturer-side confirmation of the drone-SDK-rebrand
finding below, and the reason we went looking for a hidden higher-
resolution command (see the next section).

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

## An untested lead: a real resolution-setting protocol exists in GoSky

The chip datasheet for this camera's silicon (Appotech AX3268) claims a
maximum of 1280x720@40fps — well above the 640x480 this product actually
streams. Live `GETINFO` also returns `PIXEL: 0` and `QUALITY: 2`, two
fields no command in the app ever sets. Digging into the newer GoSky
5.2.1 app (same vendor, still maintained) turned up why: GoSky has a
**second, separate binary protocol** on TCP port **5000** (not 7070),
completely independent of the plaintext BWSocket/RTSP protocol this
README otherwise documents.

Wire format (`TCPMessage`/`MessageCenter` in GoSky's decompiled source):
`[4-byte big-endian length][1-byte messageId][1-byte sessionId][2
reserved bytes][content]`, sent to `192.168.1.1:5000`. Relevant message
IDs:

```
MSG_ID_PREVIEW_RESOLUTION = 8    (0=SD, 1=HD, 2=FHD)
MSG_ID_VIDEO_RESOLUTION   = 16   (0=SD, 1=HD, 2=FHD)
MSG_ID_PHOTO_RESOLUTION   = 24   (0=SD, 1=HD, 2=FHD, 3=QHD, 4=UHD)
MSG_ID_PREVIEW_QUALITY    = 9    (0=LOW, 1=MID, 2=HIGH)
MSG_ID_VIDEO_QUALITY      = 17   (0=LOW, 1=MID, 2=HIGH)
MSG_ID_PHOTO_QUALITY      = 25   (0=LOW, 1=MID, 2=HIGH)
```

Plus roughly 30 more IDs for white balance, ISO, sharpness, WDR, motion
detection, date stamp, factory reset, format card, etc. — this reads as
the full generic Appotech action-cam/drone reference SDK protocol, far
beyond what this cheap endoscope product needs.

**Important caveat**: even GoSky's own shipped app never calls
`sendMessagePreviewResolution()`/`sendMessageVideoResolution()`/
`sendMessagePhotoResolution()` from any UI — the methods exist and are
fully wired to `MessageCenter`, which *does* always connect to port 5000
on app startup, but nothing in GoSky's own Settings screen actually sends
these messages. Same dead-API pattern as `RESETNET` above.

This has **not** been tested against the real camera — we don't know
whether this specific unit's firmware even accepts a connection on port
5000, let alone honors these message IDs. It's left here as a fully
specified, ready-to-try lead rather than a confirmed capability. If it
does work, the safe way to check would be: TCP connect to port 5000, and
if accepted, send `MSG_ID_PREVIEW_RESOLUTION=1` (HD) while watching
whether the RTSP stream's actual resolution changes.

## Connecting over WiFi + mobile data at the same time

If the device also has cellular data (4G/5G) turned on, Android can route
general app traffic over cellular instead of the camera's WiFi, since that
WiFi network has no internet access and gets deprioritized as a route —
this shows up as `failed to connect to /192.168.1.1 ... after 5000ms`.
CameraCID works around this automatically by explicitly binding its RTSP
and BWSocket sockets to the WiFi network via `ConnectivityManager`
(`NetworkUtils.kt`), rather than relying on the OS's default route. If a
connection still fails for some other reason, a dialog explains what to
check (WiFi connection, Airplane Mode as a fallback) with a Retry button,
instead of leaving a raw error string in the corner.

## Video recording format

Recording originally used a hand-rolled Motion-JPEG-in-AVI muxer
(structurally valid — confirmed by extracting and independently decoding
its frames), but that format has inconsistent playback support across the
Android app ecosystem: no support at all in Google Photos, and a
chroma-subsampling color quirk in VLC's AVI/MJPEG codec path. Recording
now uses real MP4/H.264 via Android's built-in `MediaCodec` (hardware
encoder) and `MediaMuxer` — still zero third-party dependencies, and
plays correctly everywhere.

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
- `Mp4Recorder.kt` — RGB→YUV420 conversion and H.264/MP4 encoding via
  `MediaCodec`/`MediaMuxer` for video recording.
- `NetworkUtils.kt` — binds sockets to the WiFi network explicitly, so
  the app still reaches the camera when mobile data is also active.
- `GalleryActivity.kt` — a `GridView` of this app's saved photos/videos,
  queried directly from `MediaStore` (no extra dependency); tapping an
  item hands off to whatever viewer/player app is installed via
  `Intent.ACTION_VIEW`, and long-press deletes.
- `MainActivity.kt` — live preview (decoded via Android's built-in
  `BitmapFactory`, not a bundled codec), photo capture, video recording,
  mirror/rotate, and the camera-info dialog.

## Building

Requires a JDK 17, the Android SDK (compileSdk 34, build-tools 34.0.0),
and Gradle 8.7. From the project root:

```
gradle assembleDebug
```
