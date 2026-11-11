[CmdletBinding()]
param(
    [string] $SdkRoot,
    [string] $Name = 'bil-npatch-api34',
    [string] $SystemImage = 'system-images;android-34;google_apis;x86_64',
    [ValidateRange(1536, 4096)] [int] $RamMb = 2048
)

# Creates the dedicated AVD for the NPatch Remote API contract tests. It never touches existing AVDs: if
# $Name already exists the script stops. The system image must already be installed (no download here).
. (Join-Path $PSScriptRoot 'Common.ps1')
$sdk = Get-AndroidSdkRoot $SdkRoot
$avdManager = Join-Path $sdk 'cmdline-tools/latest/bin/avdmanager.bat'
if (-not (Test-Path -LiteralPath $avdManager)) { throw "avdmanager not found: $avdManager" }
$imageDirectory = Join-Path $sdk ($SystemImage -replace ';', '/')
if (-not (Test-Path -LiteralPath $imageDirectory)) { throw "System image is not installed: $SystemImage" }

$avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE '.android/avd' }
if (Test-Path -LiteralPath (Join-Path $avdHome "$Name.ini")) { throw "AVD '$Name' already exists; remove it deliberately if you want a new one." }

$env:ANDROID_SDK_ROOT = $sdk
'no' | & $avdManager create avd --name $Name --package $SystemImage --device pixel_6
if ($LASTEXITCODE -ne 0) { throw 'avdmanager failed.' }

$config = Join-Path $avdHome "$Name.avd/config.ini"
if (-not (Test-Path -LiteralPath $config)) { throw "AVD config not found: $config" }
$lines = Get-Content -LiteralPath $config | Where-Object { $_ -notmatch '^(hw\.ramSize|hw\.gpu\.mode|showDeviceFrame)=' }
$lines += "hw.ramSize=$RamMb"
$lines += 'hw.gpu.mode=swiftshader_indirect'
$lines += 'showDeviceFrame=no'
Set-Content -LiteralPath $config -Value $lines -Encoding ASCII
Write-Output "Created AVD '$Name' ($SystemImage, ${RamMb} MB RAM)."
