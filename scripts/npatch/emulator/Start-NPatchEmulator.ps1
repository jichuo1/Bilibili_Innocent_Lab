[CmdletBinding()]
param(
    [string] $SdkRoot,
    [string] $AvdName = 'bil-npatch-api34',
    [ValidateSet(5584, 5586, 5588)] [int] $Port = 5584,
    [ValidateRange(1536, 4096)] [int] $MemoryMb = 2048,
    [ValidateRange(60, 600)] [int] $BootTimeoutSeconds = 240,
    [switch] $Force
)

# Starts the dedicated emulator headless with a clean data partition (every run is a fresh Android user).
. (Join-Path $PSScriptRoot 'Common.ps1')
$sdk = Get-AndroidSdkRoot $SdkRoot
$adb = Get-AdbPath $sdk
$emulator = Join-Path $sdk 'emulator/emulator.exe'
$serial = "emulator-$Port"

$running = (& $adb devices) -match "^$serial\s"
if ($running) { throw "$serial is already running; stop it first with Stop-NPatchEmulator.ps1." }
Assert-MemoryHeadroom -RequiredGb ($MemoryMb / 1024 + 1.5) -Force:$Force

$arguments = @('-avd', $AvdName, '-port', $Port, '-memory', $MemoryMb, '-no-window', '-no-snapshot', '-no-boot-anim',
    '-no-audio', '-gpu', 'swiftshader_indirect', '-wipe-data')
$process = Start-Process -FilePath $emulator -ArgumentList $arguments -PassThru -WindowStyle Hidden
Write-Output "Started emulator process $($process.Id) for $AvdName on $serial; waiting for boot..."

$deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    if ($process.HasExited) { throw "Emulator exited early with code $($process.ExitCode)." }
    $state = (& $adb -s $serial get-state 2>$null)
    if ($state -eq 'device') {
        $boot = (& $adb -s $serial shell getprop sys.boot_completed 2>$null)
        if ($boot -and $boot.Trim() -eq '1') {
            Assert-DedicatedEmulator -Adb $adb -Serial $serial -AvdName $AvdName
            Write-Output "$serial is ready (Android $((& $adb -s $serial shell getprop ro.build.version.release).Trim()))."
            return
        }
    }
}
Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
throw "Emulator did not finish booting within $BootTimeoutSeconds seconds."
