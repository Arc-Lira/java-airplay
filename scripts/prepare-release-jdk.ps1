#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('x86_64', 'arm64')]
    [string]$Architecture
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$JdkVersion = '25.0.4.1'
$JdkPackages = @{
    x86_64 = @{
        Url    = "https://aka.ms/download-jdk/microsoft-jdk-$JdkVersion-windows-x64.zip"
        File   = "microsoft-jdk-$JdkVersion-windows-x64.zip"
        Sha256 = '3c9099e60a82e17f5847052f50f3b107bfc1d32b366e1a20adbd1d0f303760e7'
    }
    arm64  = @{
        Url    = "https://aka.ms/download-jdk/microsoft-jdk-$JdkVersion-windows-aarch64.zip"
        File   = "microsoft-jdk-$JdkVersion-windows-aarch64.zip"
        Sha256 = '7118ce03f24d4460c5d4ccaae03a2c695a83cd3e48686fe9bbf3b34166dce588'
    }
}

$Workspace = Split-Path -Parent $PSScriptRoot
$RuntimeRoot = Join-Path $Workspace '.runtime'
$DownloadRoot = Join-Path $RuntimeRoot 'downloads'
$Destination = Join-Path $RuntimeRoot "jdk-windows-$Architecture"
$Stamp = Join-Path $Destination '.java-airplay-jdk'
$ExpectedStamp = "$JdkVersion-$Architecture"

function Get-ClashProxy {
    try {
        $config = Invoke-RestMethod -Uri 'http://127.0.0.1:9090/configs' -TimeoutSec 5
        $mixedPort = [int]$config.'mixed-port'
        if ($mixedPort -le 0) { return $null }
        return "http://127.0.0.1:$mixedPort"
    } catch {
        return $null
    }
}

function Test-JdkHome([string]$Path) {
    return (Test-Path -LiteralPath (Join-Path $Path 'jmods')) -and
        (Test-Path -LiteralPath (Join-Path $Path 'bin\java.exe'))
}

function ConvertTo-GStreamerArchitecture([string]$Value) {
    switch -Regex ($Value.ToLowerInvariant()) {
        '^(amd64|x86_64|x64)$' { return 'x86_64' }
        '^(aarch64|arm64)$' { return 'arm64' }
        default { return $null }
    }
}

function Get-HostJavaHome {
    $javaHome = $env:JAVA_HOME
    if ($javaHome -and (Test-JdkHome $javaHome)) {
        return (Resolve-Path -LiteralPath $javaHome).Path
    }
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if (!$javaCommand) {
        return $null
    }
    $javaFile = Get-Item -LiteralPath $javaCommand.Source
    $binDirectory = $javaFile.Directory
    if ($binDirectory -and $binDirectory.Parent) {
        $candidate = $binDirectory.Parent.FullName
        if (Test-JdkHome $candidate) {
            return $candidate
        }
    }
    return $null
}

function Get-HostArchitecture([string]$JavaHome) {
    $javaExe = Join-Path $JavaHome 'bin\java.exe'
    $settingsOutput = (& cmd.exe /d /c "`"$javaExe`" -XshowSettings:properties -version 2>&1" | Out-String)
    if ($settingsOutput -match 'os\.arch\s*=\s*(\S+)') {
        return ConvertTo-GStreamerArchitecture $Matches[1]
    }
    return $null
}

if ((Test-Path -LiteralPath $Stamp) -and (Get-Content -LiteralPath $Stamp -Raw).Trim() -eq $ExpectedStamp -and (Test-JdkHome $Destination)) {
    Write-Host "[OK] Reusing JDK $JdkVersion ($Architecture) at $Destination." -ForegroundColor Green
    Write-Output $Destination
    return
}

$hostJavaHome = Get-HostJavaHome
if ($hostJavaHome) {
    $hostArchitecture = Get-HostArchitecture $hostJavaHome
    if ($hostArchitecture -eq $Architecture) {
        if (Test-Path -LiteralPath $Destination) {
            Remove-Item -LiteralPath $Destination -Recurse -Force
        }
        New-Item -ItemType Directory -Path (Join-Path $Destination 'bin') | Out-Null
        Write-Host "Copying host JDK jmods from '$hostJavaHome' to '$Destination'..."
        Copy-Item -LiteralPath (Join-Path $hostJavaHome 'jmods') -Destination (Join-Path $Destination 'jmods') -Recurse -Force
        Copy-Item -LiteralPath (Join-Path $hostJavaHome 'bin\java.exe') -Destination (Join-Path $Destination 'bin\java.exe') -Force
        $releaseFile = Join-Path $hostJavaHome 'release'
        if (Test-Path -LiteralPath $releaseFile) {
            Copy-Item -LiteralPath $releaseFile -Destination (Join-Path $Destination 'release') -Force
        }
        Set-Content -LiteralPath $Stamp -Value $ExpectedStamp -Encoding ASCII
        Write-Host "[OK] Host JDK $Architecture jmods are ready at $Destination." -ForegroundColor Green
        Write-Output $Destination
        return
    }
}

$package = $JdkPackages[$Architecture]
if (!(Test-Path -LiteralPath $DownloadRoot)) {
    New-Item -ItemType Directory -Path $DownloadRoot -Force | Out-Null
}
$archive = Join-Path $DownloadRoot $package.File
$proxy = Get-ClashProxy
if (!(Test-Path -LiteralPath $archive)) {
    Write-Host "Downloading Microsoft JDK $JdkVersion ($Architecture)..."
    if ($proxy) {
        Invoke-WebRequest -Uri $package.Url -OutFile $archive -Proxy $proxy -UseBasicParsing
    } else {
        Invoke-WebRequest -Uri $package.Url -OutFile $archive -UseBasicParsing
    }
}
$actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne $package.Sha256) {
    throw "JDK archive checksum mismatch. Expected $($package.Sha256) but got $actualHash."
}
Write-Host '[OK] JDK archive checksum verified.' -ForegroundColor Green

$extractRoot = Join-Path $DownloadRoot "jdk-$Architecture-extract"
if (Test-Path -LiteralPath $extractRoot) {
    Remove-Item -LiteralPath $extractRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $extractRoot | Out-Null
Expand-Archive -LiteralPath $archive -DestinationPath $extractRoot
$extractedHome = Get-ChildItem -LiteralPath $extractRoot -Directory | Where-Object {
    Test-JdkHome $_.FullName
} | Select-Object -First 1
if (!$extractedHome) {
    throw "The JDK archive did not contain jmods for $Architecture."
}
if (Test-Path -LiteralPath $Destination) {
    Remove-Item -LiteralPath $Destination -Recurse -Force
}
Move-Item -LiteralPath $extractedHome.FullName -Destination $Destination
Remove-Item -LiteralPath $extractRoot -Recurse -Force
Set-Content -LiteralPath $Stamp -Value $ExpectedStamp -Encoding ASCII
Write-Host "[OK] JDK $JdkVersion ($Architecture) is ready at $Destination." -ForegroundColor Green
Write-Output $Destination
