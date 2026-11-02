[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidatePattern('^[A-Za-z0-9._:-]+$')] [string] $Serial,
    [Parameter(Mandatory)] [string] $BuildDirectory,
    [string] $Adb = 'adb',
    [ValidateRange(1, 100)] [int] $Iterations = 20
)

$ErrorActionPreference = 'Stop'
$build = (Resolve-Path -LiteralPath $BuildDirectory).Path
$abi = (& $Adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
if ($LASTEXITCODE -ne 0 -or $abi -notin 'arm64-v8a','x86_64') { throw 'Device ABI is unavailable or outside this recipe.' }
$remote = "/data/local/tmp/bil-npatch-native-$abi"
& $Adb -s $Serial shell mkdir -p $remote
if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare native test directory.' }
foreach ($file in 'redirect-buffer-test','linker-lock-fixture.so') {
    & $Adb -s $Serial push (Join-Path $build "$abi/$file") "$remote/$file"
    if ($LASTEXITCODE -ne 0) { throw "Cannot copy $file" }
}
& $Adb -s $Serial shell chmod 700 "$remote/redirect-buffer-test"
if ($LASTEXITCODE -ne 0) { throw 'Cannot mark test executable.' }
# The legacy fixture deliberately times out after five seconds and exits 42.
# Start-Process records this expected nonzero exit independently of shell error preferences.
$adbPath = (Get-Command $Adb -ErrorAction Stop).Source
$legacyLog = Join-Path $build "native-legacy-$Serial.log"
$legacy = Start-Process -FilePath $adbPath -ArgumentList @('-s',$Serial,'shell',"$remote/redirect-buffer-test", "$remote/linker-lock-fixture.so",'legacy') `
    -NoNewWindow -Wait -PassThru -RedirectStandardOutput $legacyLog -RedirectStandardError "$legacyLog.err"
if ($legacy.ExitCode -ne 42 -or (Get-Content $legacyLog -Raw) -notmatch 'lock inversion reproduced') {
    throw 'Legacy fixture did not reproduce the linker/path lock inversion.'
}
$fixedLog = Join-Path $build "native-fixed-$Serial.log"
[IO.File]::WriteAllText($fixedLog, '')
for ($index = 0; $index -lt $Iterations; $index++) {
    $result = & $Adb -s $Serial shell "$remote/redirect-buffer-test" "$remote/linker-lock-fixture.so" fixed
    if ($LASTEXITCODE -ne 0 -or $result -notmatch 'linker lock regression passed') { throw "Fixed regression failed at iteration $index" }
    $result | Add-Content -LiteralPath $fixedLog
}
Write-Output "Native regression passed: legacy reproduced; fixed $Iterations/$Iterations ($abi)."
