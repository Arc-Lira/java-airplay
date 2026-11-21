# Java AirPlay 投屏接收器

如果有需要指导帮助的也可以加 QQ 群[目前为开荒]：[289575702](https://qm.qq.com/q/rcnnxs8JLa)

如果这个项目对你有帮助，欢迎在 GitHub 上给仓库点一个 Star。你的支持可以帮助更多开发者发现项目，也会鼓励持续改进。

[![GitHub Stars](https://img.shields.io/github/stars/Arc-Lira/java-airplay?style=flat-square)](https://github.com/Arc-Lira/java-airplay/stargazers)
[![Java 25](https://img.shields.io/badge/Java-25-blue?style=flat-square)](https://jdk.java.net/25/)
[![Platform](https://img.shields.io/badge/platform-Windows%20x64%20%7C%20ARM64-0078D6?style=flat-square)](https://github.com/Arc-Lira/java-airplay)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

[English](README.md) · [Issues](https://github.com/Arc-Lira/java-airplay/issues) · [Releases](https://github.com/Arc-Lira/java-airplay/releases)

这是一个基于 Java 25 的 Windows 桌面 AirPlay 接收器，支持 Windows x64 与 Windows ARM64，用于在局域网接收 iPhone 屏幕镜像。项目支持 H.264 视频、ALAC/AAC-ELD 音频，并通过 GStreamer 提供可选的实验性 HEVC 支持。

## 核心能力

| 能力 | 说明 |
|---|---|
| 屏幕镜像 | 通过传统 AirPlay 传输接收 iPhone 屏幕镜像 |
| 音视频接收 | H.264 视频与 ALAC/AAC-ELD 音频 |
| 实验性 HEVC | 通过 GStreamer 后端提供可选 H.265 支持 |
| 硬件解码 | 按 Windows DXGI GPU 自动选择 D3D12、D3D11 或 NVDEC，并回退到软件解码 |
| 稳定播放 | 默认保留编码参考帧，并在拥塞时使用 TCP 背压 |
| 自适应显示 | 自动检测实际分辨率、帧率和编码格式，支持竖屏视频 |
| 桌面体验 | 中英文界面、独立视频窗口、全屏、系统托盘和主题支持 |

## 快速开始

### 使用发行包

请下载与电脑架构匹配的 ZIP：Intel/AMD 使用 `windows-x64`，Windows on ARM 与骁龙 X Elite 使用 `windows-arm64`。每个包都内置精简版 Java 25 runtime、GStreamer、启动脚本、配置文件和文档，无需另外安装 Java 或 GStreamer。

Windows ARM64 目前处于实验阶段。如果你在使用，请到 [Issues](https://github.com/Arc-Lira/java-airplay/issues) 提交日志、SoC/GPU 信息和一段简短播放样本。

1. 解压对应架构的发行 ZIP 文件。
2. 运行 `start.bat`。
3. 在 iPhone 上打开控制中心。
4. 选择“屏幕镜像”，然后选择程序中显示的接收器名称。

### 从源码运行

从源码构建需要 Windows x64 或 ARM64、JDK 25，以及用于下载 Gradle 依赖的网络连接。启动脚本会按当前架构准备项目内的 GStreamer runtime。

```powershell
./gradlew.bat test
./gradlew.bat :player:app:bootJar
start.bat
```

可执行 JAR 生成位置：

```text
player/app/build/libs/java-airplay-server-1.1.3.jar
```

## 桌面界面

集成式 GStreamer 窗口提供接收器状态、视频信息和完整设置。

- 在“设置 → 语言”或系统托盘中切换中英文界面。
- 将视频 Canvas 分离到独立窗口，并使用 `ESC` 切换或退出全屏。
- 通过系统托盘中的“打开 Java AirPlay”“显示视频窗口”“全屏”“语言”和“退出”操作访问程序。
- 按真实名称选择已检测到的 Windows DXGI GPU，同时保存对应的原生适配器索引。
- 使用“保存并重启”验证并应用网络和播放设置。

## 配置

设置文件位于：

```text
${user.home}/.java-airplay/application.properties
```

命令行参数优先于桌面界面保存的设置。完整配置模板请参考 `config/application.example.properties`。

常用配置：

| 配置项 | 默认值 | 说明 |
|---|---:|---|
| `airplay.serverName` | `Java AirPlay` | 在 iPhone“屏幕镜像”列表中显示的名称 |
| `airplay.width` / `airplay.height` | `1920` / `1080` | 请求的显示尺寸；`auto` 声明 1920×1080，开启 HEVC 后声明 3840×2160 |
| `airplay.fps` | `60` | 向发送端声明的最大帧率，实际发送帧率由时间戳测量 |
| `airplay.requirePairing` | `true` | 接收媒体前是否要求完成 AirPlay 配对 |
| `airplay.hevc` | `false` | 启用实验性 HEVC 协商 |
| `airplay.networkInterface` | `auto` | AirPlay 发现使用的网卡。自动模式优先尝试物理网卡，Bonjour 注册失败时尝试下一块 |
| `player.gstreamer.renderMode` | `balanced` | 选择 balanced、quality 或 low-latency 呈现模式 |
| `player.gstreamer.videoQueueDepth` | `2` | Java 侧缓存的编码视频访问单元数量 |
| `player.gstreamer.aggressiveFrameDropping` | `false` | 实验性模式，拥塞时丢弃编码帧 |

默认启用安全视频路径。除非你更重视最低延迟而不是画面完整性，否则请保持 `player.gstreamer.aggressiveFrameDropping=false`。丢弃 H.264/HEVC 参考帧可能导致花屏，并持续到发送端重新连接。

### 分辨率、清晰度与高分辨率投屏

iPhone 14 Pro Max 的物理屏幕为 1290×2796，但 AirPlay 发送的是由 iOS 选择尺寸的压缩视频流。接收器默认请求 1920×1080，在电脑上放大显示不能恢复已经丢失的细节。设置中的尺寸是显示能力请求，不能强制手机按原生像素采集；实际横屏、竖屏尺寸也可能与请求不同。

希望尝试更清晰的画面，可使用 GStreamer 后端，按下面的配置**保存并重启**，然后在 iPhone 上重新连接：

```properties
airplay.width=2560
airplay.height=1440
airplay.fps=60
airplay.hevc=true
```

HEVC 为实验性功能，可让支持的 Apple 发送端协商更高分辨率。是否使用它由设备、iOS 和连接条件决定，单纯填写更大的数字不能保证原生像素投屏。开启 HEVC 后，将宽高都设为 `auto` 会请求 3840×2160；开启 HEVC 不会覆盖显式填写的宽高，也**不会限制为 30fps**，`airplay.fps=auto` 同样请求 60fps。实测帧率可能更低，静止画面尤其如此。「画质优先」呈现模式改变的是播放时序，不会提高手机的编码码率或分辨率。

视频底部状态栏显示**实际检测到的视频尺寸和实测帧率**，尚未检测到的值显示为等待检测；鼠标悬停可另行查看请求的显示能力。独立视频窗口同步更新这些信息。

以上也适用于 **iPad**，没有写死 iPhone 尺寸或强制按 16:9 显示：画面比例取自收到的视频流，兼容接近 4:3 的 iPad 和横竖屏旋转。例如 2732×2048 的 iPad 可以请求该尺寸并设置 `airplay.fps=60`、开启 HEVC，但实际发送尺寸仍由 iPadOS 决定。开启 HEVC 会保留 H.264 管线供旧款 iPad 使用。原生回归测试覆盖 2048×1536 H.264、2732×2048 / 2048×2732 H.264 与 HEVC，以及同一连接内旋转；这些是生成视频流测试，不代表已经逐一实测所有 iPad 真机。

如果 1080 正常、提高分辨率后黑屏：

- 先查看日志中的 `Detected sender video format`。若连视频编码配置都没有收到，应先排查发送端协商，尝试开启 HEVC 或使用 1920×1080 档位。
- 自动选择 D3D11/D3D12 时，会检查实际视频的宽、高是否均在解码器能力内，包括竖屏。尺寸不支持时改用软件解码，不缩小视频。
- 驱动仍可能在声明支持的范围内解码失败。GStreamer 错误会写入应用日志并显示在视频状态栏；可将解码器设为 `avdec_h264`、GPU 设为 `auto` 后重新连接。开启 HEVC 时会自动使用对应的 `avdec_h265`。高分辨率 60fps 的软解需要足够的 CPU 性能。

[UxPlay 的镜像说明](https://github.com/FDH2/UxPlay#usage)也说明了发送端决定实际尺寸，以及高分辨率下可选 HEVC 的协商机制。H.264 编码本身并不限于 1080p。

## 网络与安全说明

- iPhone 和接收器应连接到同一个支持组播的局域网。
- mDNS 服务发现需要 UDP 端口 `5353` 可用。
- 默认开启配对。请妥善保管 identity 文件，除非需要让接收器作为新设备重新配对，否则不要删除它。
- 请仅在可信网络中使用接收器。
- 本项目实现的是传统屏幕镜像传输，不是完整 AirPlay 2。受保护的 Apple TV 内容和多房间功能不在支持范围内。

### 手机列表里找不到接收器

先确认日志中出现 `service is registered on`，并记下它报告的网卡和 IP。如果这台电脑上还开着另一份 Java AirPlay（包括旧版本目录里的程序），mDNS 无法合并同名服务：后启动的实例会被改名成 `Java AirPlay (2)`、`(3)`，手机可能只看到指向旧随机端口的过期记录，于是表现为"找不到"或连不上。

如果启动时提示 Bonjour 失败，先看提示中的网卡地址和具体错误。可以重连局域网，或在用户设置文件中将 `airplay.networkInterface` 改为与手机同网的网卡名称或当前 IPv4 地址。手动指定的网卡存在时，程序只尝试这一块。

自 1.1.3 起，同一用户目录下只允许运行一个接收器实例。再次启动会直接退出，并让已运行的窗口回到前台，日志会写明 `Refusing to start a second receiver instance (PID ...)`。如果在旧版本中遇到此问题，请先在任务管理器结束多余的 `java.exe`，或在托盘菜单退出后重新启动。

若日志已有注册记录但手机仍然看不到：

- 确认手机和电脑在同一子网；电脑有线、手机 Wi-Fi 时，路由器可能开启了 AP 隔离或访客网络。
- 防火墙需要允许 UDP `5353` 和接收器的临时 TCP 端口。
- 存在多块网卡时，在「设置 → 网络接口」中手动选择实际连接手机的那块网卡。

## 构建与测试

```powershell
./gradlew.bat test
./gradlew.bat :player:app:bootJar
```

在 Windows 环境中从项目根目录同时构建 x64 与 ARM64 发行包：

```powershell
./gradlew.bat release
```

生成文件：

```text
release/java-airplay-<version>.<yyMMdd>-windows-x64.zip
release/java-airplay-<version>.<yyMMdd>-windows-x64.zip.sha256
release/java-airplay-<version>.<yyMMdd>-windows-arm64.zip
release/java-airplay-<version>.<yyMMdd>-windows-arm64.zip.sha256
```

`release` 任务仅支持 Windows，可在 x64 或 ARM64 主机上运行，并分别为两种架构打包匹配的精简 Java runtime 与 GStreamer。也可使用 `releaseWindowsX64` 或 `releaseWindowsArm64` 只构建其中一个架构。首次构建 ARM64 包时会下载对应的 JDK jmods 和 GStreamer runtime。

## 模块

| 模块 | 职责 |
|---|---|
| `lib` | 配对、FairPlay、身份、Bonjour 和媒体工具 |
| `server` | AirPlay 控制、视频、音频、时序和重传 |
| `player:gstreamer` | 桌面界面和 GStreamer 播放后端 |
| `player:app` | Spring Boot 应用、设置和系统托盘 |
| `player:ffmpeg` / `player:vlc` | 备用 H.264 播放后端 |
| `player:h264-dump` | H.264 调试输出后端 |

## 许可证

本项目采用 [MIT License](LICENSE)。在打包或重新分发 GStreamer 前，请审查适用的第三方许可证和重新分发条款。
