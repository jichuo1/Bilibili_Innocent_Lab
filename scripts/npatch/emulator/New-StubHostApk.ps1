[CmdletBinding()]
param(
    [string] $SdkRoot,
    [string] $BuildToolsVersion = '37.0.0',
    [string] $PlatformDirectory = 'android-37.0',
    [string] $Keytool,
    [int] $LoaderVersionCode = 851,
    [Parameter(Mandatory)] [string] $OutputDirectory
)

# Builds the stub "patched host" used by gate G2: an APK whose package is the real host package and whose only
# content is the manifest `npatch` marker NPatch writes into a Manager-mode patched app. It has no code and no
# resources, so aapt2 alone is enough. It exists to exercise the settings UI, the scope probe and Manager
# authorisation WITHOUT a real patched Bilibili. Install it only on the dedicated emulator (the install script
# enforces that); the signing key is generated here and thrown away with the output directory.
. (Join-Path $PSScriptRoot 'Common.ps1')
$sdk = Get-AndroidSdkRoot $SdkRoot
$buildTools = Join-Path $sdk "build-tools/$BuildToolsVersion"
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$androidJar = Join-Path $sdk "platforms/$PlatformDirectory/android.jar"
foreach ($required in $aapt2, $apksigner, $androidJar) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing SDK component: $required" }
}
if (-not $Keytool) {
    $Keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/keytool.exe' } else { (Get-Command keytool -ErrorAction Stop).Source }
}
if (Test-Path -LiteralPath $OutputDirectory) { throw "Output directory already exists: $OutputDirectory" }
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$work = (Resolve-Path -LiteralPath $OutputDirectory).Path

$marker = @{
    useManager = $true; sigBypassLevel = 2; debuggable = $false
    lspConfig = @{ API_CODE = 102; VERSION_CODE = $LoaderVersionCode }
} | ConvertTo-Json -Compress -Depth 4
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($marker))

$manifest = Join-Path $work 'AndroidManifest.xml'
@"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$($script:HostPackage)"
    android:versionCode="1"
    android:versionName="npatch-stub-host">
    <application android:label="NPatch stub host" android:hasCode="false">
        <meta-data android:name="npatch" android:value="$encoded" />
    </application>
</manifest>
"@ | Set-Content -LiteralPath $manifest -Encoding UTF8

$unsigned = Join-Path $work 'stub-host-unsigned.apk'
& $aapt2 link -o $unsigned --manifest $manifest -I $androidJar --min-sdk-version 28 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed.' }

$keystore = Join-Path $work 'stub.jks'
$secret = [Guid]::NewGuid().ToString('N')
& $Keytool -genkeypair -keystore $keystore -alias stub -keyalg RSA -keysize 2048 -validity 3650 `
    -storepass $secret -keypass $secret -dname 'CN=NPatch stub host' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'keytool failed.' }

$aligned = Join-Path $work 'stub-host-aligned.apk'
& (Join-Path $buildTools 'zipalign.exe') -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }
$signed = Join-Path $work 'stub-host.apk'
$env:BIL_STUB_KS_PASS = $secret
try {
    & $apksigner sign --ks $keystore --ks-pass env:BIL_STUB_KS_PASS --key-pass env:BIL_STUB_KS_PASS --out $signed $aligned
    if ($LASTEXITCODE -ne 0) { throw 'apksigner failed.' }
} finally { Remove-Item Env:BIL_STUB_KS_PASS -ErrorAction SilentlyContinue }
Remove-Item -LiteralPath $keystore, $unsigned, $aligned -Force

[pscustomobject]@{
    apk = $signed; sha256 = (Get-FileSha256 $signed); marker = $marker; package = $script:HostPackage
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $work 'stub-host.json') -Encoding UTF8
Write-Output $signed
