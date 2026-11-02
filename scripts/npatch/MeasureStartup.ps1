[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidatePattern('^[A-Za-z0-9_.:-]+$')] [string] $Serial,
    [Parameter(Mandatory)] [ValidateSet('embedded', 'manager')] [string] $Mode,
    [Parameter(Mandatory)] [string] $VerifiedManifest,
    [Parameter(Mandatory)] [string] $OutputDirectory,
    [ValidateRange(1, 10)] [int] $Rounds = 1,
    [ValidateSet('default', 'combined')] [string] $ConfigureProfile,
    [switch] $Warmup
)

$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Choose a new output directory; previous evidence is never overwritten.' }
$manifest = Get-Content -Raw -LiteralPath $VerifiedManifest | ConvertFrom-Json
if ($manifest.mode -ne $Mode) { throw 'Manifest mode differs from the requested measurement.' }
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$directory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$runner = 'com.Bilibili_Innocent_Lab.xposedmodule.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'com.Bilibili_Innocent_Lab.xposedmodule.runtime.NPatchStartupBenchmarkInstrumentedTest'

function Get-InstalledHash([string] $Package) {
    $paths = @(& adb -s $Serial shell pm path $Package)
    if ($LASTEXITCODE -ne 0) { throw "Cannot resolve installed package: $Package" }
    $base = @($paths | Where-Object { $_ -match '^package:/data/app/[A-Za-z0-9_./=+~-]+/base\.apk$' })
    if ($base.Count -ne 1) { throw "Cannot identify one installed base APK: $Package" }
    $hash = & adb -s $Serial shell sha256sum $base[0].Substring(8)
    if ($LASTEXITCODE -ne 0 -or $hash -notmatch '^([a-f0-9]{64})\s') { throw "Cannot hash installed package: $Package" }
    $Matches[1]
}

function Invoke-Check([string] $Method, [string] $Name, [string[]] $Arguments) {
    $log = Join-Path $directory "$Name.log"
    & adb -s $Serial shell am instrument -w -r -e class "${testClass}#$Method" @Arguments $runner *> $log
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $log" }
    $raw = Get-Content -Raw -LiteralPath $log
    if ($raw -notmatch 'OK \(1 test\)' -or $raw -match 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=') {
        throw "Instrumentation did not pass; inspect $log"
    }
    $raw
}

$hostHash = Get-InstalledHash 'tv.danmaku.bili'
$moduleHash = Get-InstalledHash 'com.Bilibili_Innocent_Lab.xposedmodule'
if ($hostHash -ne $manifest.apkSha256 -or $moduleHash -ne $manifest.moduleSha256) {
    throw 'Installed host/module bytes differ from the verified measurement artifact.'
}
& adb -s $Serial shell dumpsys battery | Set-Content -LiteralPath (Join-Path $directory 'battery-before.txt') -Encoding utf8
if ($ConfigureProfile) {
    Invoke-Check 'configureFixedProfile' 'configure' @('-e', 'startupProfile', $ConfigureProfile) | Out-Null
}
$benchmarkArgs = @('-e', 'startupBenchmark', 'true', '-e', 'expectedMode', $Mode.ToUpperInvariant())
if ($Mode -eq 'embedded') { $benchmarkArgs[-1] = 'EMBEDDED' }
if ($Warmup) {
    Invoke-Check 'collectColdProcessRuns' 'warmup' ($benchmarkArgs + @('-e', 'rounds', '1')) | Out-Null
}
$raw = Invoke-Check 'collectColdProcessRuns' 'samples' ($benchmarkArgs + @('-e', 'rounds', "$Rounds"))
$runs = @([regex]::Matches($raw, '(?m)^STARTUP_RUN=(\{[^\r\n]+\})') | ForEach-Object {
    $_.Groups[1].Value | ConvertFrom-Json
})
if ($runs.Count -ne $Rounds) { throw 'Sample count differs from the requested rounds.' }
if (@($runs.fingerprint | Sort-Object -Unique).Count -ne 1) { throw 'Configuration changed across samples.' }
& adb -s $Serial shell dumpsys battery | Set-Content -LiteralPath (Join-Path $directory 'battery-after.txt') -Encoding utf8
[ordered]@{
    schema = 1; serial = $Serial; mode = $Mode; measuredAt = [DateTimeOffset]::Now.ToString('o')
    hostSha256 = $hostHash; moduleSha256 = $moduleHash; verified = $manifest
    settingsEndpoint = 'instrumentation-alive'; excludedWarmup = [bool]$Warmup
    configuredProfile = $ConfigureProfile; runs = $runs
} | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $directory 'samples.json') -Encoding utf8
Write-Output "Collected $Rounds current-identity startup samples: $directory"
