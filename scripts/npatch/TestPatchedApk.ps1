[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $OriginalApk,
    [Parameter(Mandatory)] [string] $ModuleApk,
    [Parameter(Mandatory)] [string] $ReferencePatchedApk,
    [Parameter(Mandatory)] [string] $PatcherJar,
    [Parameter(Mandatory)] [string] $BcprovJar,
    [Parameter(Mandatory)] [string] $Java21,
    [Parameter(Mandatory)] [string] $ApkSigner,
    [Parameter(Mandatory)] [string] $KeyTool,
    [Parameter(Mandatory)] [string] $OutputDirectory,
    [string] $RoamingModuleApk
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Choose a new test output directory.' }
$root = (New-Item -ItemType Directory -Path $OutputDirectory).FullName
$fixture = Join-Path $root 'module-fixture.apk'
Copy-Item -LiteralPath $ModuleApk -Destination $fixture
$ModuleApk = $fixture
$common = @{
    OriginalApk=$OriginalApk; ModuleApk=$ModuleApk; ReferencePatchedApk=$ReferencePatchedApk
    PatcherJar=$PatcherJar; BcprovJar=$BcprovJar; Java21=$Java21; ApkSigner=$ApkSigner
}
$variable = 'BIL_PATCH_TEST_' + [guid]::NewGuid().ToString('N')
$wrongVariable = $variable + '_WRONG'
$secret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$wrongSecret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$key = Join-Path $root 'throwaway-test-key.p12'
$results = [Collections.Generic.List[string]]::new()
function Expect-Rejection([string] $Name, [hashtable] $Arguments) {
    $rejected = $false
    try { & "$PSScriptRoot/NewPatchedApk.ps1" @Arguments | Out-Null }
    catch { $rejected = $true }
    if (!$rejected) { throw "Expected rejection: $Name" }
    if ($Arguments.OutputDirectory -and (Test-Path (Join-Path $Arguments.OutputDirectory 'verified.json'))) {
        throw "Rejected case published a verified artifact: $Name"
    }
    $results.Add("PASS reject $Name")
}
try {
    [Environment]::SetEnvironmentVariable($variable, $secret, 'Process')
    & $KeyTool -genkeypair -storetype PKCS12 -keystore $key -storepass:env $variable `
        -keypass:env $variable -alias fixture -keyalg RSA -keysize 2048 -validity 2 `
        -dname 'CN=NPatch tool acceptance fixture' *> (Join-Path $root 'keytool.log')
    if ($LASTEXITCODE -ne 0) { throw 'Test key generation failed.' }
    $seed = Join-Path $root 'certificate-reference.apk'
    Copy-Item -LiteralPath $ModuleApk -Destination $seed
    & $ApkSigner sign --ks $key --ks-key-alias fixture --ks-pass "env:$variable" `
        --key-pass "env:$variable" --v4-signing-enabled false $seed *> (Join-Path $root 'seed-sign.log')
    if ($LASTEXITCODE -ne 0) { throw 'Test certificate reference failed.' }
    $signed = $common.Clone()
    $signed.ReferencePatchedApk = $seed
    $signed.SigningKeyStore = $key; $signed.SigningKeyAlias = 'fixture'
    $signed.SigningStorePasswordEnvironment = $variable; $signed.SigningKeyPasswordEnvironment = $variable
    $signed.OutputDirectory = Join-Path $root 'custom-embedded'
    & "$PSScriptRoot/NewPatchedApk.ps1" @signed | Out-Null
    $first = Get-Content (Join-Path $signed.OutputDirectory 'verified.json') -Raw | ConvertFrom-Json
    $signed.ReferencePatchedApk = (Get-ChildItem $signed.OutputDirectory -Filter '*.apk').FullName
    $signed.OutputDirectory = Join-Path $root 'custom-upgrade'
    & "$PSScriptRoot/NewEmbeddedApk.ps1" @signed | Out-Null
    $second = Get-Content (Join-Path $signed.OutputDirectory 'verified.json') -Raw | ConvertFrom-Json
    if ($first.certificateSha256 -ne $second.certificateSha256 -or $first.signing -ne 'custom-keystore') {
        throw 'Custom certificate was not retained across rebuilds.'
    }
    $results.Add('PASS custom embedded signing and same-certificate rebuild')
    $manager = $common.Clone(); $manager.Mode = 'manager'; $manager.OutputDirectory = Join-Path $root 'manager'
    & "$PSScriptRoot/NewPatchedApk.ps1" @manager | Out-Null
    $profile = Get-Content (Join-Path $manager.OutputDirectory 'verified.json') -Raw | ConvertFrom-Json
    if ($profile.mode -ne 'manager') { throw 'Manager artifact has the wrong mode.' }
    $results.Add('PASS manager artifact with fixed native loader and no embedded module')
    if ($RoamingModuleApk) {
        $roaming = $signed.Clone(); $roaming.RoamingModuleApk = $RoamingModuleApk
        $roaming.OutputDirectory = Join-Path $root 'custom-roaming'
        & "$PSScriptRoot/NewEmbeddedApk.ps1" @roaming | Out-Null
        $roamingResult = Get-Content (Join-Path $roaming.OutputDirectory 'verified.json') -Raw | ConvertFrom-Json
        if ($roamingResult.roamingModule.packageName -ne 'me.iacn.biliroaming' -or
            $roamingResult.roamingModule.sha256 -ne (Get-FileHash -LiteralPath $RoamingModuleApk -Algorithm SHA256).Hash.ToLowerInvariant() -or
            $roamingResult.certificateSha256 -ne $first.certificateSha256) { throw 'Roaming bytes or host signing chain changed.' }
        $results.Add('PASS optional roaming bytes and custom host certificate retained')
    }
    $bad = $common.Clone(); $bad.Mode = 'manager'; $bad.RoamingModuleApk = $ModuleApk; $bad.OutputDirectory = Join-Path $root 'manager-roaming'
    Expect-Rejection 'roaming embedded option in Manager mode' $bad
    $bad = $common.Clone(); $bad.RoamingModuleApk = $ModuleApk; $bad.OutputDirectory = Join-Path $root 'wrong-roaming-package'
    Expect-Rejection 'wrong roaming application ID' $bad
    $bad = $common.Clone(); $bad.OutputDirectory = Join-Path $root 'partial'; $bad.SigningKeyStore = $key
    Expect-Rejection 'partial signing configuration' $bad
    $bad = $common.Clone(); $bad.ReferencePatchedApk = $seed; $bad.OutputDirectory = Join-Path $root 'missing-key'
    Expect-Rejection 'custom certificate without key' $bad
    $bad = $signed.Clone(); $bad.ReferencePatchedApk = $ReferencePatchedApk; $bad.OutputDirectory = Join-Path $root 'wrong-certificate'
    Expect-Rejection 'wrong upgrade certificate' $bad
    $bad = $common.Clone(); $bad.PatcherJar = $BcprovJar; $bad.OutputDirectory = Join-Path $root 'unknown-patcher'
    Expect-Rejection 'unknown patcher' $bad
    $bad = $common.Clone(); $bad.OutputDirectory = Join-Path $root 'existing'
    New-Item -ItemType Directory $bad.OutputDirectory | Out-Null
    Expect-Rejection 'existing output directory' $bad
    $bad = $common.Clone(); $bad.OriginalApk = $ReferencePatchedApk; $bad.OutputDirectory = Join-Path $root 'patched-input'
    Expect-Rejection 'already patched input' $bad
    $bad = $common.Clone(); $bad.ModuleApk = $ReferencePatchedApk; $bad.OutputDirectory = Join-Path $root 'wrong-module'
    Expect-Rejection 'wrong module application ID' $bad
    # 保留真实签名校验，验证放宽 R8 入口名称不会同时接受多入口或未知 API 档案。
    foreach ($case in @(
        @{Name='multiple-entries'; Entry='META-INF/xposed/java_init.list'; Text="a.b.Entry`na.b.Other"},
        @{Name='wrong-api'; Entry='META-INF/xposed/module.prop'; Text="minApiVersion=101`ntargetApiVersion=103`nstaticScope=true`nautoHotReload=false"}
    )) {
        $invalidModule = Join-Path $root ($case.Name + '.apk')
        # 重新建 ZIP，避免 .NET Update 模式保留 APK 对齐字段后破坏未修改项的局部偏移。
        $source = [IO.Compression.ZipFile]::OpenRead($ModuleApk)
        $zip = [IO.Compression.ZipFile]::Open($invalidModule, [IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($entry in $source.Entries) {
                $target = $zip.CreateEntry($entry.FullName).Open()
                try {
                    if ($entry.FullName -eq $case.Entry) {
                        $writer = [IO.StreamWriter]::new($target)
                        try { $writer.Write($case.Text) } finally { $writer.Dispose() }
                    } else {
                        $inputStream = $entry.Open()
                        try { $inputStream.CopyTo($target) } finally { $inputStream.Dispose() }
                    }
                } finally { $target.Dispose() }
            }
        } finally { $zip.Dispose(); $source.Dispose() }
        & $ApkSigner sign --ks $key --ks-key-alias fixture --ks-pass "env:$variable" `
            --key-pass "env:$variable" --v4-signing-enabled false $invalidModule *> (Join-Path $root ($case.Name + '-sign.log'))
        if ($LASTEXITCODE -ne 0) { throw 'Invalid-profile fixture signing failed.' }
        $bad = $common.Clone(); $bad.ModuleApk = $invalidModule; $bad.OutputDirectory = Join-Path $root $case.Name
        Expect-Rejection $case.Name $bad
    }
    [Environment]::SetEnvironmentVariable($wrongVariable, $wrongSecret, 'Process')
    $bad = $signed.Clone(); $bad.OutputDirectory = Join-Path $root 'wrong-password'
    $bad.SigningKeyPasswordEnvironment = $wrongVariable
    Expect-Rejection 'wrong signing password' $bad
    $logs = Get-ChildItem $root -File -Recurse | Where-Object { $_.Extension -eq '.log' -or $_.Name -eq 'verified.json' } | Get-Content -Raw
    if (@($logs | Where-Object { $_.Contains($secret) -or $_.Contains($wrongSecret) }).Count) { throw 'Signing secret appeared in a log.' }
    $results.Add('PASS password absent from logs and artifact metadata')
    $results | Set-Content (Join-Path $root 'results.log')
    # An expected native signing failure must not make a fully passed suite exit as failed.
    $global:LASTEXITCODE = 0
    $results
} finally {
    [Environment]::SetEnvironmentVariable($variable, $null, 'Process')
    [Environment]::SetEnvironmentVariable($wrongVariable, $null, 'Process')
}
