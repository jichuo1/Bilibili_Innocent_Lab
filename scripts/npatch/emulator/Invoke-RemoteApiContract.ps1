[CmdletBinding()]
param(
    [string] $SdkRoot,
    [Parameter(Mandatory)] [string] $Serial,
    [Parameter(Mandatory)] [ValidateSet('1.0.7', '1.0.9', '1.1.0')] [string] $ManagerLabel,
    [Parameter(Mandatory)] [string] $ManagerApkDirectory,
    [Parameter(Mandatory)] [string] $ModuleApk,
    [Parameter(Mandatory)] [string] $TestApk,
    [Parameter(Mandatory)] [string] $StubHostApk,
    [Parameter(Mandatory)] [string] $EvidenceDirectory,
    [string] $AvdName = 'bil-npatch-api34',
    [string] $TestClass = 'com.Bilibili_Innocent_Lab.xposedmodule.runtime.NPatchRemoteApiContractInstrumentedTest'
)

# Gate G2 for one Manager version: install the pinned Manager, the module, the test APK and the stub host on the
# dedicated emulator, run the Remote API contract instrumentation, and keep the raw output plus an identity record.
# The evidence directory must not exist yet. A pass proves the contract on THIS emulator/Android/Manager only.
. (Join-Path $PSScriptRoot 'Common.ps1')
$sdk = Get-AndroidSdkRoot $SdkRoot
$adb = Get-AdbPath $sdk
Assert-DedicatedEmulator -Adb $adb -Serial $Serial -AvdName $AvdName
if (Test-Path -LiteralPath $EvidenceDirectory) { throw "Evidence directory already exists: $EvidenceDirectory" }
foreach ($file in $ModuleApk, $TestApk, $StubHostApk) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing APK: $file" }
}
New-Item -ItemType Directory -Path $EvidenceDirectory | Out-Null
$evidence = (Resolve-Path -LiteralPath $EvidenceDirectory).Path

function Install-Apk([string] $Path) {
    & $adb -s $Serial install -r -t $Path
    if ($LASTEXITCODE -ne 0) { throw "Install failed: $Path" }
}

# Module first, so the Manager's first scan sees it; stub host carries the Manager-mode marker.
& $adb -s $Serial uninstall $script:ModulePackage | Out-Null
& $adb -s $Serial uninstall "$($script:ModulePackage).test" | Out-Null
& $adb -s $Serial uninstall $script:HostPackage | Out-Null
Install-Apk $ModuleApk
Install-Apk $TestApk
Install-Apk $StubHostApk
& (Join-Path $PSScriptRoot 'Install-NPatchManager.ps1') -SdkRoot $sdk -Serial $Serial -Label $ManagerLabel `
    -ApkDirectory $ManagerApkDirectory -AvdName $AvdName

$raw = Join-Path $evidence 'instrumentation.txt'
$output = & $adb -s $Serial shell am instrument -w -r -e verifyRemoteApi true -e class $TestClass `
    "$($script:ModulePackage).test/androidx.test.runner.AndroidJUnitRunner" 2>&1
$output | Set-Content -LiteralPath $raw -Encoding UTF8
& $adb -s $Serial logcat -d -t 400 NPatchRemoteApi:V NPatchRemoteStore:V *:S 2>$null | Set-Content -LiteralPath (Join-Path $evidence 'logcat-npatch.txt') -Encoding UTF8

$text = $output -join "`n"
$ok = $text -match 'OK \(\d+ tests?\)'
$pin = (Read-NPatchPins).managerApks | Where-Object { $_.label -eq $ManagerLabel }
[ordered]@{
    result = if ($ok) { 'pass' } else { 'fail' }
    gate = 'G2'
    claim = 'Remote API contract on this emulator, Android and Manager build only; not a patched-host result.'
    recordedAt = (Get-Date).ToString('o')
    emulator = [ordered]@{
        serial = $Serial; avd = $AvdName
        android = (& $adb -s $Serial shell getprop ro.build.version.release).Trim()
        abi = (& $adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
    }
    manager = [ordered]@{ label = $ManagerLabel; file = $pin.file; sha256 = $pin.sha256; versionCode = $pin.versionCode }
    apks = [ordered]@{
        module = [ordered]@{ file = (Split-Path $ModuleApk -Leaf); sha256 = (Get-FileSha256 $ModuleApk) }
        test = [ordered]@{ file = (Split-Path $TestApk -Leaf); sha256 = (Get-FileSha256 $TestApk) }
        stubHost = [ordered]@{ file = (Split-Path $StubHostApk -Leaf); sha256 = (Get-FileSha256 $StubHostApk) }
    }
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'identity.json') -Encoding UTF8

Write-Output ($text -split "`n" | Select-String -Pattern 'g2\.|OK \(|FAILURES|Tests run|INSTRUMENTATION_RESULT|Error|Exception' | ForEach-Object { $_.ToString().Trim() })
if (-not $ok) { throw "G2 failed for Manager $ManagerLabel; raw output kept in $evidence." }
Write-Output "G2 passed for Manager $ManagerLabel. Evidence: $evidence"
