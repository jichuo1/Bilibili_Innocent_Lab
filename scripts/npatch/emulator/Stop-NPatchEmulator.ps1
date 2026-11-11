[CmdletBinding()]
param(
    [string] $SdkRoot,
    [string] $AvdName = 'bil-npatch-api34',
    [ValidateSet(5584, 5586, 5588)] [int] $Port = 5584
)

# Stops the dedicated emulator, after proving that the serial really is that AVD.
. (Join-Path $PSScriptRoot 'Common.ps1')
$sdk = Get-AndroidSdkRoot $SdkRoot
$adb = Get-AdbPath $sdk
$serial = "emulator-$Port"
Assert-DedicatedEmulator -Adb $adb -Serial $serial -AvdName $AvdName
& $adb -s $serial emu kill | Out-Null
Write-Output "Stop requested for $serial ($AvdName)."
