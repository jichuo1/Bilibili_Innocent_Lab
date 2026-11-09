param(
    [Parameter(Mandatory)][string]$Campaign,
    [Parameter(Mandatory)][string]$Samples,
    [Parameter(Mandatory)][string]$Output,
    [Parameter(Mandatory)][string]$UniquePresents
)
$ErrorActionPreference = 'Stop'
$run = Get-Content -LiteralPath $Campaign -Raw | ConvertFrom-Json
if (-not $run.complete) { throw 'Incomplete campaign' }
$timestamps = [Collections.Generic.HashSet[long]]::new()
foreach ($line in [IO.File]::ReadLines((Resolve-Path -LiteralPath $Samples))) {
    # Accept raw SF triples or the saved, deduplicated phase/timestamp pairs.
    if ($line -match '^\d+\s+(\d+)(?:\s+\d+)?\s*$') {
        $actual = [long]$Matches[1]
        if ($actual -gt 0 -and $actual -lt [long]::MaxValue) { [void]$timestamps.Add($actual) }
    }
}
$ordered = @($timestamps | Sort-Object)
$deduplicated = [Collections.Generic.List[string]]::new()
$deduplicated.Add('phase actual_present_monotonic_ns')
$results = @($run.phases | ForEach-Object {
    $phase = $_
    # Fence presentation is CLOCK_MONOTONIC; don't compare it to elapsedRealtime.
    $start = $phase.startMonotonicNs + 5e9
    $end = $phase.endMonotonicNs - 2e9
    if ($start -le 5e9 -or $end -le $start) { throw 'Missing monotonic phase bounds' }
    $frames = @($ordered | Where-Object { $_ -ge $start -and $_ -le $end })
    if ($frames.Count -lt ($end - $start) / 1e9 * 80) { throw 'Insufficient actual presentation coverage' }
    if ($frames[0] - $start -gt 1e8 -or $end - $frames[-1] -gt 1e8) { throw 'Missing phase boundary presentations' }
    $gaps = [Collections.Generic.List[double]]::new()
    foreach ($timestamp in $frames) { $deduplicated.Add("$($phase.index) $timestamp") }
    for ($i = 1; $i -lt $frames.Count; $i++) { $gaps.Add(($frames[$i] - $frames[$i - 1]) / 1e6) }
    $gaps = @($gaps | Sort-Object)
    $long = @($gaps | Where-Object { $_ -gt 1500 / $phase.displayHz }).Count
    [pscustomobject]@{
        index = $phase.index; variant = $phase.variant; frames = $frames.Count
        presentFps = ($frames.Count - 1) * 1e9 / ($frames[-1] - $frames[0])
        intervalP50Ms = $gaps[[int][Math]::Floor($gaps.Count * .50)]
        intervalP95Ms = $gaps[[int][Math]::Floor($gaps.Count * .95)]
        intervalP99Ms = $gaps[[int][Math]::Floor($gaps.Count * .99)]
        maxIntervalMs = $gaps[-1]; overOneAndHalfPeriods = $long
        overOneAndHalfPeriodsPercent = 100 * $long / [double]$gaps.Count
    }
})
$results | ConvertTo-Json | Set-Content -LiteralPath $Output -Encoding utf8
$deduplicated | Set-Content -LiteralPath $UniquePresents -Encoding utf8
$results | Format-Table
