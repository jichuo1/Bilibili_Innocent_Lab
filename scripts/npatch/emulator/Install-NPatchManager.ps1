[CmdletBinding()]
param(
    [string] $SdkRoot,
    [Parameter(Mandatory)] [string] $Serial,
    [Parameter(Mandatory)] [ValidateSet('1.0.7', '1.0.9', '1.1.0')] [string] $Label,
    [Parameter(Mandatory)] [string] $ApkDirectory,
    [string] $AvdName = 'bil-npatch-api34'
)

# Replaces the NPatch Manager on the dedicated emulator with a pinned build. The APK is located under
# -ApkDirectory by file name and must match the SHA-256 recorded in pins/npatch-baselines.json before it is
# installed. Release builds and CI builds are signed by different keys, so the previous Manager is always
# uninstalled first (emulator data only; never use this on a phone).
. (Join-Path $PSScriptRoot 'Common.ps1')
$sdk = Get-AndroidSdkRoot $SdkRoot
$adb = Get-AdbPath $sdk
Assert-DedicatedEmulator -Adb $adb -Serial $Serial -AvdName $AvdName

$pin = (Read-NPatchPins).managerApks | Where-Object { $_.label -eq $Label }
if (-not $pin) { throw "No pin for Manager '$Label'." }
$apk = Get-ChildItem -LiteralPath $ApkDirectory -Recurse -Filter $pin.file -File | Select-Object -First 1
if (-not $apk) { throw "$($pin.file) not found under $ApkDirectory." }
$actual = Get-FileSha256 $apk.FullName
if ($actual -ne $pin.sha256) { throw "SHA-256 mismatch for $($pin.file): expected $($pin.sha256), got $actual." }

& $adb -s $Serial uninstall $script:ManagerPackage | Out-Null   # exit code ignored: it may not be installed
& $adb -s $Serial install -r $apk.FullName
if ($LASTEXITCODE -ne 0) { throw 'Manager install failed.' }
$info = (& $adb -s $Serial shell dumpsys package $script:ManagerPackage) | Select-String 'versionCode=|versionName=' | Select-Object -First 2
Write-Output ("Installed Manager {0} ({1}); {2}" -f $Label, $pin.file, (($info | ForEach-Object { $_.ToString().Trim() }) -join ' '))

# Opening the Manager once makes it scan installed modules and register this one.
& $adb -s $Serial shell am start -n "$($script:ManagerPackage)/.ui.activity.MainActivity" | Out-Null
Start-Sleep -Seconds 6
& $adb -s $Serial shell input keyevent KEYCODE_HOME | Out-Null
