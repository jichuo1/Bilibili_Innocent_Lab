#requires -Version 7.0
param([Parameter(Mandatory)][string]$Directory, [Parameter(Mandatory)][string]$Output)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
[void][Drawing.Bitmap].GetInterfaces()
$references = @([Drawing.Bitmap].Assembly.Location, [Drawing.Color].Assembly.Location)
$references += [AppDomain]::CurrentDomain.GetAssemblies() |
    Where-Object { $_.GetName().Name -like 'System.Private.Windows.*' } | ForEach-Object Location
Add-Type -ReferencedAssemblies ($references | Select-Object -Unique) -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
public static class HostUiPixelComparison {
    public static long[] Compare(string first, string second) {
        using var a = new Bitmap(first);
        using var b = new Bitmap(second);
        if (a.Size != b.Size) throw new ArgumentException("Image dimensions changed");
        var rect = new Rectangle(0, 0, a.Width, a.Height);
        var ad = a.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        var bd = b.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        try {
            var ab = new byte[ad.Stride * a.Height];
            var bb = new byte[bd.Stride * b.Height];
            Marshal.Copy(ad.Scan0, ab, 0, ab.Length); Marshal.Copy(bd.Scan0, bb, 0, bb.Length);
            long pixels = 0, changed = 0, total = 0, maximum = 0;
            // Exclude system status/navigation areas, as in the original comparison.
            for (int y = 140; y < a.Height - 80; y++) for (int x = 10; x < a.Width - 10; x++) {
                bool different = false;
                for (int c = 0; c < 3; c++) {
                    int d = Math.Abs(ab[y * ad.Stride + x * 4 + c] - bb[y * bd.Stride + x * 4 + c]);
                    total += d; maximum = Math.Max(maximum, d); different |= d != 0;
                }
                pixels++; if (different) changed++;
            }
            return new long[] { pixels, changed, total, maximum };
        } finally { a.UnlockBits(ad); b.UnlockBits(bd); }
    }
}
'@
$run = Get-Content -LiteralPath (Join-Path $Directory 'campaign.json') -Raw | ConvertFrom-Json
if (-not $run.complete) { throw 'Incomplete campaign' }
$reference = $run.phases | Where-Object variant -eq A | Select-Object -First 1
$results = @($run.phases | ForEach-Object {
    $phase = $_
    foreach ($offset in 0, 137, 559) {
        $a = Join-Path $Directory "phase-$($reference.index)-A/offset-$offset.png"
        $b = Join-Path $Directory "phase-$($phase.index)-$($phase.variant)/offset-$offset.png"
        $diff = [HostUiPixelComparison]::Compare($a, $b)
        [pscustomobject]@{ phase = $phase.index; variant = $phase.variant; offset = $offset; pixels = $diff[0]; changedPixels = $diff[1]; meanChannelError = $diff[2] / [double]($diff[0] * 3); maxChannelError = $diff[3] }
    }
})
$results | ConvertTo-Json | Set-Content -LiteralPath $Output -Encoding utf8
$results | Format-Table
