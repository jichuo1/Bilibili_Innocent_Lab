param(
    [Parameter(Mandatory)][string]$Campaign,
    [Parameter(Mandatory)][string]$Samples,
    [Parameter(Mandatory)][string]$Output,
    [double]$TrimStartSeconds = 5,
    [double]$TrimEndSeconds = 2
)
$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture
$run = Get-Content -LiteralPath $Campaign -Raw | ConvertFrom-Json
if (-not $run.complete) { throw 'Incomplete benchmark' }
$rows = @(Get-Content -LiteralPath $Samples | Select-Object -Skip 1 | ForEach-Object {
    $v = $_ -split '\s+'
    if ($v.Count -notin @(10, 11)) { throw "Invalid sample: $_" }
    $unplugged = $v.Count -eq 11 -and $v[10] -eq '0'
    if ($v[6] -ne 'Discharging' -or ($v[5] -ne '1' -and -not $unplugged)) { throw 'Sample was charging' }
    [pscustomobject]@{
        time = [double]::Parse($v[0], $culture)
        watts = [double]::Parse($v[1], $culture) * [double]::Parse($v[2], $culture) / 1e12
        voltage = [double]::Parse($v[2], $culture) / 1e6
        charge = [double]::Parse($v[3], $culture)
        temp = [double]::Parse($v[4], $culture) / 10
    }
})
$phases = @($run.phases | ForEach-Object {
    $phase = $_
    $start = $phase.startElapsedNs / 1e9 + $TrimStartSeconds
    $end = $phase.endElapsedNs / 1e9 - $TrimEndSeconds
    $s = @($rows | Where-Object { $_.time -ge $start -and $_.time -le $end })
    if ($s.Count -lt 10 -or $s[0].time - $start -gt 1 -or $end - $s[-1].time -gt 1) { throw 'Insufficient power coverage' }
    $energy = 0.0
    for ($i = 1; $i -lt $s.Count; $i++) {
        $dt = $s[$i].time - $s[$i - 1].time
        if ($dt -le 0 -or $dt -gt 1.5) { throw 'Power sampling gap' }
        $energy += $dt * ($s[$i].watts + $s[$i - 1].watts) / 2
    }
    $duration = $s[-1].time - $s[0].time
    $meanVoltage = ($s.voltage | Measure-Object -Average).Average
    $counterWatts = ($s[0].charge - $s[-1].charge) * .0036 * $meanVoltage / $duration
    [pscustomobject]@{
        index = $phase.index; variant = $phase.variant; seconds = $duration
        watts = $energy / $duration; chargeCounterWatts = $counterWatts
        tempStart = $s[0].temp; tempEnd = $s[-1].temp
        frames = $phase.frames; framesPerSecond = $phase.frames / ($phase.elapsedMs / 1000)
        gpuMeanMs = $phase.gpuMeanMs; gpuP95Ms = $phase.gpuP95Ms
        mainCpuMs = $phase.mainCpuMs; thermalStatus = $phase.thermalStatus
        materialRecordings = $phase.after.materialRecordings - $phase.before.materialRecordings
        prismProxyRecordings = $phase.after.top.proxyRecordings + $phase.after.bottom.proxyRecordings - $phase.before.top.proxyRecordings - $phase.before.bottom.proxyRecordings
    }
})
$pairs = @()
for ($i = 0; $i + 1 -lt $phases.Count; $i += 2) {
    $a = @($phases[$i..($i + 1)] | Where-Object variant -eq A)
    $b = @($phases[$i..($i + 1)] | Where-Object variant -eq B)
    if ($a.Count -ne 1 -or $b.Count -ne 1) { throw 'Every adjacent pair must contain A and B' }
    $pairs += [pscustomobject]@{ pair = $i / 2; wattsSaved = $a[0].watts - $b[0].watts; percentSaved = 100 * (1 - $b[0].watts / $a[0].watts) }
}
$result = [ordered]@{ phases = $phases; pairs = $pairs; trimStartSeconds = $TrimStartSeconds; trimEndSeconds = $TrimEndSeconds }
if ($pairs.Count -eq 4) {
    # Four phase pairs are the replicates, not the correlated half-second driver samples.
    $mean = ($pairs.wattsSaved | Measure-Object -Average).Average
    $sumSquares = ($pairs | ForEach-Object { [Math]::Pow($_.wattsSaved - $mean, 2) } | Measure-Object -Sum).Sum
    $margin = 3.182446 * [Math]::Sqrt($sumSquares / 3) / 2
    $result.meanWattsSaved = $mean
    $result.paired95CiWatts = @(($mean - $margin), ($mean + $margin))
}
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Output -Encoding utf8
$phases | Format-Table index, variant, watts, chargeCounterWatts, tempStart, tempEnd, framesPerSecond, gpuMeanMs, materialRecordings, prismProxyRecordings
$pairs | Format-Table
if ($pairs.Count -eq 4) { "Paired 95% CI (W saved): $($result.paired95CiWatts -join ', ')" }
