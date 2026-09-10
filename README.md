# Java AirPlay Receiver

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
player/app/build/libs/java-airplay-server-1.1.0.jar
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
| `airplay.width` / `airplay.height` | `1920` / `1080` | Declared display capability; use `auto` to detect the stream |
| `airplay.fps` | `60` | Declared maximum frame rate; the sender rate is measured from timestamps |
| `airplay.requirePairing` | `true` | Require AirPlay pairing before accepting media |
| `airplay.hevc` | `false` | Enable experimental HEVC negotiation |
| `player.gstreamer.renderMode` | `balanced` | Choose balanced, quality, or low-latency presentation |
| `player.gstreamer.videoQueueDepth` | `2` | Number of encoded video access units buffered in Java |
| `player.gstreamer.aggressiveFrameDropping` | `false` | Experimental mode that drops encoded frames under pressure |

The safe video path is enabled by default. Keep `player.gstreamer.aggressiveFrameDropping=false` unless the lowest possible latency is more important than picture integrity. Dropping H.264/HEVC reference frames can cause block corruption until the sender reconnects.

## Network and Security Notes

- Keep the iPhone and receiver on the same multicast-capable LAN.
- UDP port `5353` must be available for mDNS discovery.
- Pairing is enabled by default. Keep the identity file private and do not delete it unless the receiver should pair as a new device.
- Use the receiver only on trusted networks.
- This project implements legacy screen mirroring, not full AirPlay 2. Protected Apple TV content and multi-room features are out of scope.

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
