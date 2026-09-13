#Requires -Version 5.1

[CmdletBinding()]
param(
    [ValidateSet('x86_64', 'arm64')]
    [string]$Architecture,
    [switch]$CheckOnly,
    [switch]$ForceDownload
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$GStreamerVersion = '1.28.5'
$GStreamerPackages = @{
    x86_64 = @{
        File   = "gstreamer-1.0-msvc-x86_64-$GStreamerVersion.exe"
        Sha256 = '51ee5eaec33008e8409d8cf6f6884457f22aa3bd515f8856f993a3eaab903530'
    }
    arm64  = @{
        File   = "gstreamer-1.0-msvc-arm64-$GStreamerVersion.exe"
        Sha256 = 'c079ce6a64d182ea5648ec993033dfcbf8d073c542f2c596427991331a38dc27'
    }
}
$Workspace = Split-Path -Parent $PSScriptRoot
$RuntimeRoot = Join-Path $Workspace '.runtime'
$DownloadRoot = Join-Path $RuntimeRoot 'downloads'
$EmbeddedJava = Join-Path $Workspace 'runtime\bin\java.exe'
$JavaExecutable = if (Test-Path -LiteralPath $EmbeddedJava) {
    (Resolve-Path -LiteralPath $EmbeddedJava).Path
} else {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) { $javaCommand.Source } else { $null }
}

function Get-PeArchitecture([string]$Path) {
    if (!(Test-Path -LiteralPath $Path)) {
        return $null
    }
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        $reader = New-Object System.IO.BinaryReader $stream
        if ($reader.ReadUInt16() -ne 0x5A4D) {
            return $null
        }
        $null = $stream.Seek(0x3C, [System.IO.SeekOrigin]::Begin)
        $peOffset = $reader.ReadInt32()
        $null = $stream.Seek($peOffset, [System.IO.SeekOrigin]::Begin)
        if ($reader.ReadUInt32() -ne 0x00004550) {
            return $null
        }
        switch ($reader.ReadUInt16()) {
            0x8664 { return 'x86_64' }
            0xAA64 { return 'arm64' }
            default { return $null }
        }
    } finally {
        $stream.Dispose()
    }
}

function ConvertTo-GStreamerArchitecture([string]$Value) {
    switch -Regex ($Value.ToLowerInvariant()) {
        '^(amd64|x86_64|x64)$' { return 'x86_64' }
        '^(aarch64|arm64)$' { return 'arm64' }
        default { return $null }
    }
}

function Get-JavaArchitecture {
    if (!$JavaExecutable) {
        throw 'Java was not found. Use a complete release package or install JDK 25.'
    }
    try {
        $settingsOutput = (& cmd.exe /d /c "`"$JavaExecutable`" -XshowSettings:properties -version 2>&1" | Out-String)
    } catch {
        throw "Unable to execute Java at '$JavaExecutable'."
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to execute Java at '$JavaExecutable'."
    }
    if ($settingsOutput -notmatch 'version\s+"25(\.|\")') {
        throw "Java 25 is required. Current output: $($settingsOutput.Trim())"
    }
    $detected = $null
    if ($settingsOutput -match 'os\.arch\s*=\s*(\S+)') {
        $detected = ConvertTo-GStreamerArchitecture $Matches[1]
    }
    if (!$detected) {
        $detected = Get-PeArchitecture $JavaExecutable
    }
    if (!$detected) {
        throw "Unable to determine the architecture of Java at '$JavaExecutable'."
    }
    Write-Host "[OK] Java 25 ($detected) is available at $JavaExecutable." -ForegroundColor Green
    return $detected
}

function Find-GStreamerBin([string]$Root, [string]$RequiredArchitecture) {
    $abi = "msvc_$RequiredArchitecture"
    $candidates = @(
        (Join-Path $Root 'bin'),
        (Join-Path $Root "1.0\$abi\bin"),
        (Join-Path $Root "$abi\bin")
    )
    foreach ($candidate in $candidates) {
        $inspect = Join-Path $candidate 'gst-inspect-1.0.exe'
        if (!(Test-Path -LiteralPath $inspect)) {
            continue
        }
        $actual = Get-PeArchitecture $inspect
        if ($actual -and $actual -ne $RequiredArchitecture) {
            continue
        }
        return (Resolve-Path -LiteralPath $candidate).Path
    }
    return $null
}

function Find-LocalGStreamerBin([string]$RequiredArchitecture) {
    $roots = @()
    if ($RequiredArchitecture -eq 'arm64') {
        $roots += (Join-Path $RuntimeRoot 'gstreamer-arm64')
        $roots += (Join-Path $RuntimeRoot 'gstreamer')
    } else {
        $roots += (Join-Path $RuntimeRoot 'gstreamer')
        $roots += (Join-Path $RuntimeRoot 'gstreamer-x86_64')
    }
    foreach ($root in $roots) {
        $bin = Find-GStreamerBin $root $RequiredArchitecture
        if ($bin) { return $bin }
    }
    return $null
}

function Find-SystemGStreamerBin([string]$RequiredArchitecture) {
    $suffix = $RequiredArchitecture.ToUpperInvariant()
    $names = @(
        "GSTREAMER_1_0_ROOT_MSVC_$suffix",
        "GSTREAMER_1_0_ROOT_$suffix"
    )
    if ($RequiredArchitecture -eq 'x86_64') {
        $names += 'GSTREAMER_1_0_ROOT_MINGW_X86_64'
    }
    $roots = @()
    foreach ($name in $names) {
        $roots += [Environment]::GetEnvironmentVariable($name, 'Process')
        $roots += [Environment]::GetEnvironmentVariable($name, 'User')
        $roots += [Environment]::GetEnvironmentVariable($name, 'Machine')
    }
    $abi = "msvc_$RequiredArchitecture"
    if ($env:LOCALAPPDATA) {
        $roots += (Join-Path $env:LOCALAPPDATA "Programs\gstreamer\1.0\$abi")
    }
    if ($env:ProgramFiles) {
        $roots += (Join-Path $env:ProgramFiles "gstreamer\1.0\$abi")
    }
    foreach ($root in $roots) {
        if (!$root) { continue }
        $bin = Find-GStreamerBin $root $RequiredArchitecture
        if ($bin) { return $bin }
    }
    return $null
}

function Get-ClashProxy {
    try {
        $version = Invoke-RestMethod -Uri 'http://127.0.0.1:9090/version' -TimeoutSec 5
        $config = Invoke-RestMethod -Uri 'http://127.0.0.1:9090/configs' -TimeoutSec 5
        $mixedPort = [int]$config.'mixed-port'
        if ($mixedPort -le 0) { return $null }
        $proxy = "http://127.0.0.1:$mixedPort"
        $probe = Invoke-WebRequest -Uri 'https://services.gradle.org/versions/current' -Proxy $proxy -UseBasicParsing -TimeoutSec 15
        if ($probe.StatusCode -eq 200) {
            Write-Host "[OK] FlClash $($version.version) proxy is available at $proxy." -ForegroundColor Green
            return $proxy
        }
    } catch {
        Write-Host "[WARN] FlClash proxy check failed: $($_.Exception.Message)" -ForegroundColor Yellow
    }
    return $null
}

function Test-GStreamerElement([string]$Inspect, [string]$Element) {
    $ErrorActionPreference = 'Continue'
    & $Inspect $Element *> $null
    return $LASTEXITCODE -eq 0
}

function Test-GStreamerPlugins([string]$Bin) {
    $inspect = Join-Path $Bin 'gst-inspect-1.0.exe'
    $required = @('appsrc', 'clocksync', 'h264parse', 'avdec_h264', 'h265parse', 'avdec_h265', 'avdec_aac', 'avdec_alac', 'autovideosink', 'autoaudiosink')
    foreach ($plugin in $required) {
        if (!(Test-GStreamerElement $inspect $plugin)) {
            throw "The project-local GStreamer runtime is missing required plugin '$plugin'."
        }
    }
    $d3d12Available = Test-GStreamerElement $inspect 'd3d12h264dec'
    $d3d11Available = Test-GStreamerElement $inspect 'd3d11h264dec'
    $nvdecAvailable = Test-GStreamerElement $inspect 'nvh264dec'
    $d3d12HevcAvailable = Test-GStreamerElement $inspect 'd3d12h265dec'
    $d3d11HevcAvailable = Test-GStreamerElement $inspect 'd3d11h265dec'
    $nvdecHevcAvailable = Test-GStreamerElement $inspect 'nvh265dec'
    if ($d3d12Available) { Write-Host '[OK] D3D12 hardware H.264 decoding is available.' -ForegroundColor Green }
    if ($nvdecAvailable) { Write-Host '[OK] NVIDIA NVDEC H.264 decoding is available.' -ForegroundColor Green }
    if ($d3d11Available) { Write-Host '[OK] D3D11 hardware H.264 decoding is available.' -ForegroundColor Green }
    if (!$d3d12Available -and !$nvdecAvailable -and !$d3d11Available) {
        Write-Host '[WARN] No Windows hardware H.264 decoder was found; avdec_h264 will be used.' -ForegroundColor Yellow
    }
    if ($d3d12HevcAvailable) { Write-Host '[OK] D3D12 hardware HEVC decoding is available.' -ForegroundColor Green }
    if ($nvdecHevcAvailable) { Write-Host '[OK] NVIDIA NVDEC HEVC decoding is available.' -ForegroundColor Green }
    if ($d3d11HevcAvailable) { Write-Host '[OK] D3D11 hardware HEVC decoding is available.' -ForegroundColor Green }
    if (!$d3d12HevcAvailable -and !$nvdecHevcAvailable -and !$d3d11HevcAvailable) {
        Write-Host '[WARN] No Windows hardware HEVC decoder was found; experimental HEVC uses avdec_h265.' -ForegroundColor Yellow
    }
    Write-Host '[OK] Required GStreamer video and audio plugins are available.' -ForegroundColor Green
}

function Copy-SystemGStreamer([string]$SystemBin, [string]$InstallRoot) {
    $sourceRoot = Split-Path -Parent $SystemBin
    if (!(Test-Path -LiteralPath $InstallRoot)) {
        New-Item -ItemType Directory -Path $InstallRoot | Out-Null
    }
    Write-Host "Copying existing GStreamer runtime from '$sourceRoot' to '$InstallRoot'..."
    & robocopy $sourceRoot $InstallRoot /E /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -gt 7) {
        throw "Unable to copy GStreamer runtime. Robocopy exit code: $LASTEXITCODE"
    }
}

function Install-GStreamer([string]$RequiredArchitecture, [string]$InstallRoot) {
    $package = $GStreamerPackages[$RequiredArchitecture]
    $url = "https://gstreamer.freedesktop.org/data/pkg/windows/$GStreamerVersion/msvc/$($package.File)"
    $installer = Join-Path $DownloadRoot $package.File
    if (!(Test-Path -LiteralPath $DownloadRoot)) {
        New-Item -ItemType Directory -Path $DownloadRoot -Force | Out-Null
    }
    $proxy = Get-ClashProxy
    if (!(Test-Path -LiteralPath $installer) -or $ForceDownload) {
        Write-Host "Downloading GStreamer $GStreamerVersion ($RequiredArchitecture) to '$installer'..."
        if ($proxy) {
            Invoke-WebRequest -Uri $url -OutFile $installer -Proxy $proxy -UseBasicParsing
        } else {
            Invoke-WebRequest -Uri $url -OutFile $installer -UseBasicParsing
        }
    }
    $actualHash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $package.Sha256) {
        throw "GStreamer installer checksum mismatch. Expected $($package.Sha256) but got $actualHash."
    }
    Write-Host '[OK] GStreamer installer checksum verified.' -ForegroundColor Green

    if (!(Test-Path -LiteralPath $InstallRoot)) {
        New-Item -ItemType Directory -Path $InstallRoot -Force | Out-Null
    }
    $arguments = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SP-', "/DIR=`"$InstallRoot`"")
    $process = Start-Process -FilePath $installer -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "GStreamer installer failed with exit code $($process.ExitCode)."
    }
}

$JavaArchitecture = Get-JavaArchitecture
if ($Architecture) {
    if ($Architecture -ne $JavaArchitecture) {
        Write-Host "[WARN] Packaging GStreamer $Architecture while Java is $JavaArchitecture." -ForegroundColor Yellow
    }
} else {
    $Architecture = $JavaArchitecture
}
if ($Architecture -eq 'arm64') {
    Write-Host '[WARN] Windows ARM64 support is experimental. Please report logs, SoC/GPU details, and a short playback sample at https://github.com/Arc-Lira/java-airplay/issues' -ForegroundColor Yellow
}
$InstallRoot = if ($Architecture -eq 'arm64') {
    Join-Path $RuntimeRoot 'gstreamer-arm64'
} else {
    Join-Path $RuntimeRoot 'gstreamer'
}

$localBin = Find-LocalGStreamerBin $Architecture
if (!$localBin -and $CheckOnly) {
    throw "Project-local GStreamer ($Architecture) is missing. Run '$PSScriptRoot\bootstrap-runtime.ps1' without -CheckOnly."
}
if (!$localBin) {
    $systemBin = Find-SystemGStreamerBin $Architecture
    if ($systemBin -and !$ForceDownload) {
        Copy-SystemGStreamer $systemBin $InstallRoot
    } else {
        Install-GStreamer $Architecture $InstallRoot
    }
    $localBin = Find-GStreamerBin $InstallRoot $Architecture
}
if (!$localBin) {
    throw "GStreamer installation completed but a $Architecture gst-inspect-1.0.exe was not found below '$InstallRoot'."
}

Test-GStreamerPlugins $localBin
Write-Host "[OK] Project-local $Architecture runtime: $localBin" -ForegroundColor Green
Write-Output $localBin
