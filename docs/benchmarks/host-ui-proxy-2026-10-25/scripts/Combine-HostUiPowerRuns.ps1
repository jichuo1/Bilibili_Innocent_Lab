param(
    [Parameter(Mandatory)][string]$First,
    [Parameter(Mandatory)][string]$Second,
    [Parameter(Mandatory)][string]$Output
)
$ErrorActionPreference = 'Stop'
$runs = @((Get-Content -LiteralPath $First -Raw | ConvertFrom-Json),
    (Get-Content -LiteralPath $Second -Raw | ConvertFrom-Json))
if ($runs[0].pairs.Count -ne 4 -or $runs[1].pairs.Count -ne 4) { throw 'Expected two complete four-pair runs' }
$pairs = @($runs[0].pairs) + @($runs[1].pairs)
$mean = ($pairs.wattsSaved | Measure-Object -Average).Average
$sumSquares = ($pairs | ForEach-Object { [Math]::Pow($_.wattsSaved - $mean, 2) } | Measure-Object -Sum).Sum
$margin = 2.364624251 * [Math]::Sqrt($sumSquares / 7) / [Math]::Sqrt(8)
$phases = @($runs[0].phases) + @($runs[1].phases)
$baseline = @($phases | Where-Object variant -eq A)
$candidate = @($phases | Where-Object variant -eq B)
$a = ($baseline.watts | Measure-Object -Average).Average
$b = ($candidate.watts | Measure-Object -Average).Average
$result = [ordered]@{
    pairs = $pairs
    baselineWatts = $a; candidateWatts = $b
    meanWattsSaved = $mean; percentOfMeanPowerSaved = 100 * (1 - $b / $a)
    paired95CiWatts = @(($mean - $margin), ($mean + $margin))
    positivePairs = @($pairs | Where-Object wattsSaved -gt 0).Count
    negativePairs = @($pairs | Where-Object wattsSaved -lt 0).Count
    baselineMainCpuMs = ($baseline.mainCpuMs | Measure-Object -Average).Average
    candidateMainCpuMs = ($candidate.mainCpuMs | Measure-Object -Average).Average
    statisticallyPositiveInThisMeasurement = $mean - $margin -gt 0
}
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Output -Encoding utf8
$result | ConvertTo-Json -Depth 8
