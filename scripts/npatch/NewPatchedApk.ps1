[CmdletBinding()]
param(
    [ValidateSet('embedded','manager')] [string] $Mode = 'embedded',
    [Parameter(Mandatory)] [string] $OriginalApk,
    [Parameter(Mandatory)] [string] $ModuleApk,
    [Parameter(Mandatory)] [string] $ReferencePatchedApk,
    [Parameter(Mandatory)] [string] $PatcherJar,
    [Parameter(Mandatory)] [string] $BcprovJar,
    [Parameter(Mandatory)] [string] $Java21,
    [Parameter(Mandatory)] [string] $ApkSigner,
    [Parameter(Mandatory)] [string] $OutputDirectory,
    [string] $RoamingModuleApk,
    [string] $SigningKeyStore,
    [string] $SigningKeyAlias,
    [string] $SigningStorePasswordEnvironment,
    [string] $SigningKeyPasswordEnvironment,
    [string] $Aapt2
)

$ErrorActionPreference = 'Stop'
$expectedPatcher = 'dfa41340b717ca4de5a0de8e283e60d21ec38ce4b42f3d67657e01efeeecaff6'
$expectedLibraries = [ordered]@{
    'arm64-v8a' = 'ceac8f206404b5b2267ca70b0d6eb5b0f3738bc11d60e61738ccaa6af4e05a0f'
    'x86_64' = '7933cae217a3f0183d15fee01674d682d4d8eb6a6f227d208cf6bbbc356ca5d1'
}
if ($RoamingModuleApk -and $Mode -ne 'embedded') {
    throw 'RoamingModuleApk is only for embedded mode; configure the Manager scope separately.'
}
if (!$Aapt2) {
    $Aapt2 = @('aapt2.exe','aapt2') | ForEach-Object { Join-Path (Split-Path $ApkSigner) $_ } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (!$Aapt2) { throw 'Provide Aapt2 from the selected Android build-tools directory.' }
$Aapt2 = (Resolve-Path -LiteralPath $Aapt2).Path
foreach ($name in 'OriginalApk','ModuleApk','ReferencePatchedApk','PatcherJar','BcprovJar','Java21','ApkSigner') {
    Set-Variable -Name $name -Value (Resolve-Path -LiteralPath (Get-Variable -Name $name -ValueOnly)).Path
}
if ((Get-FileHash -LiteralPath $PatcherJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedPatcher) {
    throw 'Unsupported patcher: use the verified Java-and-native-fixed 833 JAR.'
}
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Output directory already exists; choose a fresh directory.' }
$customSigning = @($SigningKeyStore, $SigningKeyAlias, $SigningStorePasswordEnvironment, $SigningKeyPasswordEnvironment)
if (@($customSigning | Where-Object { $_ }).Count -notin 0,4) {
    throw 'Provide all four custom signing options together; passwords must be supplied through environment variables.'
}
if ($SigningKeyStore) {
    $SigningKeyStore = (Resolve-Path -LiteralPath $SigningKeyStore).Path
    foreach ($variable in $SigningStorePasswordEnvironment, $SigningKeyPasswordEnvironment) {
        if ($variable -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or
            [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($variable, 'Process'))) {
            throw 'Missing or invalid signing password environment variable.'
        }
    }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-ZipText($Archive, [string] $EntryName) {
    $entry = $Archive.GetEntry($EntryName)
    if ($null -eq $entry -or $entry.Length -gt 65536) { throw "Missing or oversized entry: $EntryName" }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Get-EntryHash($Entry) {
    $stream = $Entry.Open()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose(); $stream.Dispose() }
}

function Get-Signer([string] $Apk) {
    $report = & $ApkSigner verify --print-certs $Apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Signature verification failed: $Apk" }
    $certificates = @($report | ForEach-Object {
        if ("$_" -match 'Signer.*certificate SHA-256 digest: ([a-fA-F0-9]{64})') { $Matches[1].ToLowerInvariant() }
    } | Sort-Object -Unique)
    if ($certificates.Count -ne 1) { throw 'Expected exactly one signing certificate.' }
    $certificates[0]
}

$originalHash = (Get-FileHash -LiteralPath $OriginalApk -Algorithm SHA256).Hash.ToLowerInvariant()
$moduleHash = (Get-FileHash -LiteralPath $ModuleApk -Algorithm SHA256).Hash.ToLowerInvariant()
$moduleSigner = Get-Signer $ModuleApk
$badging = & $Aapt2 dump badging $ModuleApk 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Module manifest inspection failed.' }
$packageLine = $badging | Where-Object { "$_" -match '^package: ' } | Select-Object -First 1
# 包名从 APK 本身读取：正式模块与内测 .embedded 变体共用同一份工具，不再写死基础包名。
if ("$packageLine" -notmatch "^package: name='([A-Za-z0-9_.]+)' versionCode='([0-9]+)' versionName='([^']*)'") {
    throw 'Module APK has invalid package or version metadata.'
}
$modulePackage = $Matches[1]
if ($modulePackage -notmatch '^com\.Bilibili_Innocent_Lab\.xposedmodule(\.|$)') {
    throw "Unexpected module application ID: $modulePackage"
}
$moduleVersion = $Matches[2]
$moduleVersionName = $Matches[3]
$moduleArchive = [IO.Compression.ZipFile]::OpenRead($ModuleApk)
try {
    $moduleEntry = (Read-ZipText $moduleArchive 'META-INF/xposed/java_init.list').Trim()
    # R8 按项目既有规则改写入口内容；不能把 Debug 类名当作 Release 的固定契约。
    if ($moduleEntry -notmatch '^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$') {
        throw 'Module APK has an unexpected modern entry point.'
    }
    $moduleProperties = ConvertFrom-StringData (Read-ZipText $moduleArchive 'META-INF/xposed/module.prop')
    if ($moduleProperties.minApiVersion -ne '101' -or $moduleProperties.targetApiVersion -ne '102' -or
        $moduleProperties.staticScope -ne 'true' -or $moduleProperties.autoHotReload -ne 'false') {
        throw 'Module APK has an unsupported modern API profile.'
    }
} finally { $moduleArchive.Dispose() }
$roamingProfile = $null
if ($RoamingModuleApk) {
    $RoamingModuleApk = (Resolve-Path -LiteralPath $RoamingModuleApk).Path
    $roamingSigner = Get-Signer $RoamingModuleApk
    $roamingBadging = & $Aapt2 dump badging $RoamingModuleApk 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Roaming APK manifest inspection failed.' }
    $roamingPackageLine = $roamingBadging | Where-Object { "$_" -match '^package: ' } | Select-Object -First 1
    if ("$roamingPackageLine" -notmatch "^package: name='me\.iacn\.biliroaming' versionCode='([0-9]+)' versionName='([^']*)'") {
        throw 'Roaming APK must use the me.iacn.biliroaming application ID.'
    }
    $roamingVersion = $Matches[1]; $roamingVersionName = $Matches[2]
    $roamingArchive = [IO.Compression.ZipFile]::OpenRead($RoamingModuleApk)
    try {
        $entry = (Read-ZipText $roamingArchive 'assets/xposed_init').Trim()
        $names = @($entry -split '\r?\n' | Where-Object { $_.Trim() -and !$_.Trim().StartsWith('#') })
        if (!$names.Count -or @($names | Where-Object { $_.Trim() -notmatch '^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$' }).Count) {
            throw 'Roaming APK has no valid legacy module entry.'
        }
    } finally { $roamingArchive.Dispose() }
    $roamingProfile = [ordered]@{
        packageName = 'me.iacn.biliroaming'; versionCode = $roamingVersion; versionName = $roamingVersionName
        sha256 = (Get-FileHash -LiteralPath $RoamingModuleApk -Algorithm SHA256).Hash.ToLowerInvariant()
        certificateSha256 = $roamingSigner
    }
}
$referenceSigner = Get-Signer $ReferencePatchedApk
if (!$SigningKeyStore -and $referenceSigner -ne '08b00b38cd98762bf261952c2c1014c09208ac2c278fd3085421994a516c3e23') {
    throw 'Reference uses a custom certificate; provide its matching custom signing options.'
}
$source = [IO.Compression.ZipFile]::OpenRead($OriginalApk)
try {
    if ($source.GetEntry('assets/npatch/config.json') -or $source.GetEntry('assets/npatch/origin.apk')) {
        throw 'Input APK is already patched; supply the original APK.'
    }
} finally { $source.Dispose() }

$output = (New-Item -ItemType Directory -Path $OutputDirectory).FullName
$log = Join-Path $output 'patch.log'
# 固定普通 entry-hook 和等级 2；模式来自显式枚举，不接受追加任意参数。
[string[]]$modeArguments = if ($Mode -eq 'embedded') { @('--embed', $ModuleApk) } else { @('--manager') }
if ($RoamingModuleApk) { $modeArguments += @('--embed', $RoamingModuleApk) }
& $Java21 -cp "$BcprovJar;$PatcherJar" (Join-Path $PSScriptRoot 'RunPatcher.java') `
    @modeArguments --sigbypasslv 2 -o $output $OriginalApk *> $log
if ($LASTEXITCODE -ne 0) { throw "Patcher failed; inspect $log" }
$apks = @(Get-ChildItem -LiteralPath $output -Filter '*.apk' -File)
if ($apks.Count -ne 1) { throw 'Expected exactly one output APK.' }
$patched = $apks[0].FullName
if ($SigningKeyStore) {
    # 口令不进入参数、日志或 verified.json。签名工具直接从指定进程环境读取。
    # 原生 loader 和原包仍逐字节核对；重签名不允许改变 payload。
    $signLog = Join-Path $output 'sign.log'
    & $ApkSigner sign --ks $SigningKeyStore --ks-key-alias $SigningKeyAlias `
        --ks-pass "env:$SigningStorePasswordEnvironment" --key-pass "env:$SigningKeyPasswordEnvironment" `
        --v4-signing-enabled false $patched *> $signLog
    if ($LASTEXITCODE -ne 0) { throw 'Custom APK signing failed; no verified artifact was produced.' }
}
$archive = [IO.Compression.ZipFile]::OpenRead($patched)
try {
    $config = (Read-ZipText $archive 'assets/npatch/config.json') | ConvertFrom-Json
    if ($config.useManager -isnot [bool] -or $config.useManager -ne ($Mode -eq 'manager') -or
        $config.sigBypassLevel -ne 2 -or $config.lspConfig.VERSION_CODE -ne 833) {
        throw 'Patched configuration differs from the requested verified profile.'
    }
    $embedded = $archive.GetEntry("assets/npatch/modules/$modulePackage.apk")
    $origin = $archive.GetEntry('assets/npatch/origin.apk')
    $roaming = $archive.GetEntry('assets/npatch/modules/me.iacn.biliroaming.apk')
    if (($roamingProfile -and ($null -eq $roaming -or (Get-EntryHash $roaming) -ne $roamingProfile.sha256)) -or
        (!$roamingProfile -and $null -ne $roaming)) {
        throw 'Embedded roaming bytes differ from the explicitly supplied APK.'
    }
    if ($null -eq $origin -or (Get-EntryHash $origin) -ne $originalHash -or
        ($Mode -eq 'embedded' -and ($null -eq $embedded -or (Get-EntryHash $embedded) -ne $moduleHash)) -or
        ($Mode -eq 'manager' -and $null -ne $embedded)) {
        throw 'Embedded module or original APK does not match the supplied bytes.'
    }
    foreach ($abi in $expectedLibraries.Keys) {
        $library = $archive.GetEntry("assets/npatch/so/$abi/libnpatch.so")
        if ($null -eq $library -or (Get-EntryHash $library) -ne $expectedLibraries[$abi]) {
            throw "Patched native loader does not match the fixed profile: $abi"
        }
    }
} finally { $archive.Dispose() }
$signer = Get-Signer $patched
if ($signer -ne $referenceSigner) { throw 'Signing certificate mismatch; do not install this output APK.' }
$verification = [ordered]@{
    mode = $Mode; signatureLevel = 2; injectdex = $false
    loaderProfile = '833-manager-binder-and-trivial-tls-v1'; nativeLibraries = $expectedLibraries
    patcherSha256 = $expectedPatcher; originalSha256 = $originalHash; moduleSha256 = $moduleHash
    apkSha256 = (Get-FileHash -LiteralPath $patched -Algorithm SHA256).Hash.ToLowerInvariant()
    certificateSha256 = $signer
    moduleCertificateSha256 = $moduleSigner
    moduleVersionCode = $moduleVersion; moduleVersionName = $moduleVersionName
    moduleEntry = $moduleEntry
    signing = if ($SigningKeyStore) { 'custom-keystore' } else { 'npatch-test-key' }
}
if ($roamingProfile) { $verification.roamingModule = $roamingProfile }
$verification | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'verified.json') -Encoding utf8
Write-Output "Verified $Mode APK: $patched"
Write-Output "Evidence: $(Join-Path $output 'verified.json')"
