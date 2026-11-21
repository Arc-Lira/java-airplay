# Java AirPlay Receiver

如果有需要指导帮助的也可以加 QQ 群[目前为开荒]：[289575702](https://qm.qq.com/q/rcnnxs8JLa)

If this project is useful to you, please consider giving the repository a Star on GitHub. It helps more developers discover the project and supports continued development.

[![GitHub Stars](https://img.shields.io/github/stars/Arc-Lira/java-airplay?style=flat-square)](https://github.com/Arc-Lira/java-airplay/stargazers)
[![Java 25](https://img.shields.io/badge/Java-25-blue?style=flat-square)](https://jdk.java.net/25/)
[![Platform](https://img.shields.io/badge/platform-Windows%20x64%20%7C%20ARM64-0078D6?style=flat-square)](https://github.com/Arc-Lira/java-airplay)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

[简体中文](README.zh-CN.md) · [Issues](https://github.com/Arc-Lira/java-airplay/issues) · [Releases](https://github.com/Arc-Lira/java-airplay/releases)

Java 25 desktop receiver for iPhone Screen Mirroring on a local network. It runs on Windows x64 and Windows ARM64, receives H.264 video, ALAC/AAC-ELD audio, and supports opt-in experimental HEVC through GStreamer.

## Highlights

| Capability | Details |
|---|---|
| Screen mirroring | Receive iPhone screen mirroring over the legacy AirPlay transport |
| Video and audio | H.264 video with ALAC/AAC-ELD audio |
| Experimental HEVC | Optional H.265 support through the GStreamer backend |
| Hardware decoding | Auto-selects D3D12, D3D11, or NVDEC on a Windows DXGI GPU, with software fallback |
| Reliable playback | Preserves encoded reference frames and applies TCP backpressure by default |
| Adaptive display | Detects the actual stream size, frame rate, and codec, including portrait video |
| Desktop workflow | Bilingual UI, detachable video window, full screen, system tray, and theme support |

## Quick Start

### Packaged Release

Download the ZIP that matches the PC: `windows-x64` for Intel/AMD, or `windows-arm64` for Windows on ARM and Snapdragon X Elite. Each package includes a compact Java 25 runtime, GStreamer, startup scripts, configuration, and documentation. Java and GStreamer do not need to be installed separately.

Windows ARM64 support is experimental. If you run it, please [open an issue](https://github.com/Arc-Lira/java-airplay/issues) with logs, SoC/GPU details, and a short playback sample.

1. Extract the matching release ZIP.
2. Run `start.bat`.
3. Open Control Center on the iPhone.
4. Choose **Screen Mirroring**, then select the receiver name shown by the application.

### From Source

Source builds require Windows x64 or ARM64, JDK 25, and network access for Gradle dependencies. The startup script prepares a project-local GStreamer runtime for the current architecture.

```powershell
./gradlew.bat test
./gradlew.bat :player:app:bootJar
start.bat
```

The executable JAR is generated at:

```text
player/app/build/libs/java-airplay-server-1.1.3.jar
```

## Desktop UI

The integrated GStreamer window provides receiver status, video details, and settings.

- Switch between English and Chinese from **Settings → Language** or the system tray.
- Detach the video Canvas into a separate window and toggle full screen with `ESC`.
- Keep the receiver reachable through **Open Java AirPlay**, **Show video window**, **Full screen**, **Language**, and **Quit** tray actions.
- Select a detected Windows DXGI GPU by its real name while storing its native adapter index.
- Use **Save & Restart** to validate and apply network and playback settings.

## Configuration

Settings are stored in:

```text
${user.home}/.java-airplay/application.properties
```

Command-line properties take precedence over saved desktop settings. Use `config/application.example.properties` as the complete configuration template.

Common settings:

| Property | Default | Description |
|---|---:|---|
| `airplay.serverName` | `Java AirPlay` | Name shown in the iPhone Screen Mirroring list |
| `airplay.width` / `airplay.height` | `1920` / `1080` | Requested display size; `auto` advertises 1920×1080, or 3840×2160 with HEVC |
| `airplay.fps` | `60` | Declared maximum frame rate; the sender rate is measured from timestamps |
| `airplay.requirePairing` | `true` | Require AirPlay pairing before accepting media |
| `airplay.hevc` | `false` | Enable experimental HEVC negotiation |
| `airplay.networkInterface` | `auto` | Adapter for AirPlay discovery. Auto prefers physical adapters and tries the next candidate if Bonjour registration fails |
| `player.gstreamer.renderMode` | `balanced` | Choose balanced, quality, or low-latency presentation |
| `player.gstreamer.videoQueueDepth` | `2` | Number of encoded video access units buffered in Java |
| `player.gstreamer.aggressiveFrameDropping` | `false` | Experimental mode that drops encoded frames under pressure |

The safe video path is enabled by default. Keep `player.gstreamer.aggressiveFrameDropping=false` unless the lowest possible latency is more important than picture integrity. Dropping H.264/HEVC reference frames can cause block corruption until the sender reconnects.

### Resolution, sharpness, and high-resolution mirroring

An iPhone 14 Pro Max has a 1290×2796 physical screen, but AirPlay sends a compressed video stream at a size chosen by iOS. The receiver's default request is 1920×1080; enlarging that stream on a PC cannot restore the missing detail. Display size is a request, not a command to capture the phone at native resolution. Portrait and landscape streams can differ from the requested dimensions.

To try higher-resolution mirroring, use the GStreamer backend and **Save & Restart**, then reconnect the iPhone with:

```properties
airplay.width=2560
airplay.height=1440
airplay.fps=60
airplay.hevc=true
```

HEVC is experimental and allows capable Apple senders to negotiate higher-resolution video. Whether the sender uses it depends on the device, iOS, and connection; a higher number alone does not guarantee native pixels. `auto` for both dimensions requests 3840×2160 when HEVC is enabled. Enabling HEVC does not overwrite explicit dimensions and does **not** impose a 30 FPS limit: `airplay.fps=auto` also requests 60 FPS. The measured rate can be lower, especially on static screens. The quality render mode changes presentation timing, not the sender's bitrate or encoded resolution.

The video status bar shows **actual detected stream dimensions and measured FPS**, with pending values shown as pending. Its tooltip shows the requested display capability separately. The detached window tracks the same live details.

This applies to **iPad** too. There is no iPhone-specific size or forced 16:9 rendering: the received stream determines the aspect ratio, including 4:3-class iPads and rotation. For example, a 2732×2048 iPad can use that display request with `airplay.fps=60` and HEVC enabled, though iPadOS still chooses the actual stream. Enabling HEVC keeps the H.264 pipeline available for older iPads. Native regression tests cover 2048×1536 H.264, 2732×2048 / 2048×2732 H.264 and HEVC, and rotation on an existing connection; these are generated-stream tests, not a claim of testing every physical iPad model.

If 1080 works but a larger request produces a black screen:

- Check `Detected sender video format` in the log. If no codec configuration arrives, investigate sender negotiation before blaming the decoder; try HEVC or the 1920×1080 profile.
- Automatic D3D11/D3D12 selection checks the actual width **and height** against the decoder's capabilities, including portrait streams. If the resolution is unsupported, it falls back to software decoding without downscaling.
- Driver failures can occur even within advertised capabilities. GStreamer errors now reach the application log and video status bar. Try `player.gstreamer.videoDecoder=avdec_h264` with `player.gstreamer.gpuAdapter=auto`, then reconnect. With HEVC enabled, the matching software decoder is `avdec_h265` automatically. Software decoding at high resolution/60 FPS needs a sufficiently fast CPU.

The [UxPlay mirroring notes](https://github.com/FDH2/UxPlay#usage) also describe sender-controlled display sizes and optional HEVC for higher resolutions. H.264 itself is not restricted to 1080p.

## Network and Security Notes

- Keep the iPhone and receiver on the same multicast-capable LAN.
- UDP port `5353` must be available for mDNS discovery.
- Pairing is enabled by default. Keep the identity file private and do not delete it unless the receiver should pair as a new device.
- Use the receiver only on trusted networks.
- This project implements legacy screen mirroring, not full AirPlay 2. Protected Apple TV content and multi-room features are out of scope.

### The iPhone cannot find the receiver

First check that the log reports `service is registered on` and note the interface and IP it names. If another Java AirPlay process is still running (including an older extracted folder), mDNS cannot merge two identical instance names: the second process is renamed to `Java AirPlay (2)`, `(3)`, and the phone may keep seeing a stale record pointing at the old random port. That looks like "not found" or a failed connection.

If startup reports a Bonjour error, check the adapter addresses and errors in that message. Reconnect to the LAN, or set `airplay.networkInterface` in your user settings file to the name or current IPv4 address of the adapter on the phone's network. A manually selected adapter that is present is used on its own.

Since 1.1.3, only one receiver may run per user profile. A second launch exits immediately, asks the running window to come to the front, and logs `Refusing to start a second receiver instance (PID ...)`. On older versions, end the extra `java.exe` processes in Task Manager, or quit from the tray menu and start again.

If the service is registered but the phone still cannot see it:

- Keep both devices on the same subnet. A wired PC with a Wi-Fi phone often hits router AP isolation or a guest network.
- Allow UDP `5353` and the receiver's temporary TCP port through the firewall.
- With several adapters, pick the one on the phone's network in **Settings → Network interface**.

## Build and Test

```powershell
./gradlew.bat test
./gradlew.bat :player:app:bootJar
```

Build the complete Windows x64 and ARM64 distributions from the repository root:

```powershell
./gradlew.bat release
```

Generated files:

```text
release/java-airplay-<version>.<yyMMdd>-windows-x64.zip
release/java-airplay-<version>.<yyMMdd>-windows-x64.zip.sha256
release/java-airplay-<version>.<yyMMdd>-windows-arm64.zip
release/java-airplay-<version>.<yyMMdd>-windows-arm64.zip.sha256
```

The release task is Windows-only. It can run on either Windows x64 or Windows ARM64 and produces both ZIP files, each with a matching compact Java runtime and GStreamer build. Build one architecture with `releaseWindowsX64` or `releaseWindowsArm64`. The first ARM64 build downloads the ARM64 JDK jmods and GStreamer runtime.

## Modules

| Module | Purpose |
|---|---|
| `lib` | Pairing, FairPlay, identity, Bonjour, and media helpers |
| `server` | AirPlay control, video, audio, timing, and retransmission |
| `player:gstreamer` | Desktop UI and GStreamer playback backend |
| `player:app` | Spring Boot application, settings, and system tray |
| `player:ffmpeg` / `player:vlc` | Alternate H.264 playback backends |
| `player:h264-dump` | H.264 debugging output backend |

## License

Released under the [MIT License](LICENSE). Review applicable third-party and redistribution licenses before bundling or redistributing GStreamer.
