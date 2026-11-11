# Shared helpers for the dedicated NPatch Remote API emulator (gate G2). Dot-source this file.
#
# Safety model: these scripts only ever talk to an emulator whose serial is on the allow-list AND whose AVD
# name is the dedicated one. A phone, or an emulator that another project is using, is refused before any
# adb command that changes state.

$ErrorActionPreference = 'Stop'

$script:DedicatedAvdName = 'bil-npatch-api34'
$script:DefaultSystemImage = 'system-images;android-34;google_apis;x86_64'
$script:DefaultPort = 5584
$script:AllowedSerialPattern = '^emulator-(5584|5586|5588)$'
$script:ModulePackage = 'com.Bilibili_Innocent_Lab.xposedmodule'
$script:ManagerPackage = 'top.nkbe.npatch'
$script:HostPackage = 'tv.danmaku.bili'

function Get-AndroidSdkRoot {
    param([string] $Explicit)
    $candidates = @($Explicit, $env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, (Join-Path $env:LOCALAPPDATA 'Android/Sdk')) |
        Where-Object { $_ }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'platform-tools')) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    throw 'Android SDK not found. Pass -SdkRoot or set ANDROID_SDK_ROOT.'
}

function Get-AdbPath {
    param([Parameter(Mandatory)] [string] $SdkRoot)
    $adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
    if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found under $SdkRoot" }
    $adb
}

function Assert-DedicatedEmulator {
    param(
        [Parameter(Mandatory)] [string] $Adb,
        [Parameter(Mandatory)] [string] $Serial,
        [string] $AvdName = $script:DedicatedAvdName
    )
    if ($Serial -notmatch $script:AllowedSerialPattern) {
        throw "Refusing serial '$Serial': only dedicated emulators ($($script:AllowedSerialPattern)) may be used."
    }
    # Read the whole output before taking the first line: stopping the pipeline early can turn adb's exit code non-zero.
    $lines = @(& $Adb -s $Serial emu avd name 2>$null)
    $exitCode = $LASTEXITCODE
    $reported = $lines | Select-Object -First 1
    if ($exitCode -ne 0 -or -not $reported) { throw "Cannot read the AVD name of $Serial." }
    if ($reported.Trim() -ne $AvdName) {
        throw "Refusing ${Serial}: it runs AVD '$($reported.Trim())', expected '$AvdName'."
    }
}

function Assert-MemoryHeadroom {
    param([double] $RequiredGb = 3.5, [switch] $Force)
    $os = Get-CimInstance Win32_OperatingSystem
    $freeVirtualGb = $os.FreeVirtualMemory / 1MB
    Write-Output ("Commit headroom: {0:N1} GB free virtual, {1:N1} GB free physical" -f $freeVirtualGb, ($os.FreePhysicalMemory / 1MB))
    if ($freeVirtualGb -lt $RequiredGb -and -not $Force) {
        throw ("Only {0:N1} GB of commit headroom; an emulator plus Gradle can crash other builds. Close other work or pass -Force." -f $freeVirtualGb)
    }
}

function Read-NPatchPins {
    param([string] $PinFile = (Join-Path (Split-Path $PSScriptRoot -Parent) 'pins/npatch-baselines.json'))
    Get-Content -Raw -LiteralPath $PinFile | ConvertFrom-Json
}

function Get-FileSha256 {
    param([Parameter(Mandatory)] [string] $Path)
    (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}
