<#
Checks that the upstream NPatch sources pinned in pins/npatch-baselines.json still carry the behaviour our
Remote API gateway and loader assumptions rely on (gate G0).

Each contract file is identified by its git blob SHA-1 at the pinned commit, so a changed file fails before any
anchor is evaluated. When the pin is bumped, the blob ids change and every anchor below is re-read against the
new content; an anchor that no longer holds is a finding, not something to loosen silently.

Offline use: -SourceDirectory points at a checkout (or a directory laid out with the repo-relative paths) of the
pinned commit. Without it the files are fetched from raw.githubusercontent.com at the pinned commit.
#>
[CmdletBinding()]
param(
    [string] $SourceDirectory,
    [string] $PinFile = (Join-Path $PSScriptRoot 'pins/npatch-baselines.json')
)

$ErrorActionPreference = 'Stop'
$pins = Get-Content -Raw -LiteralPath $PinFile | ConvertFrom-Json
$commit = [string] $pins.upstream.devCommit
if ($commit -notmatch '^[0-9a-f]{40}$') { throw 'Pinned commit must be a full 40-character id.' }

function Get-BlobSha1([byte[]] $Bytes) {
    $header = [Text.Encoding]::ASCII.GetBytes("blob $($Bytes.Length)" + [char]0)
    $all = New-Object byte[] ($header.Length + $Bytes.Length)
    [Array]::Copy($header, 0, $all, 0, $header.Length)
    [Array]::Copy($Bytes, 0, $all, $header.Length, $Bytes.Length)
    $sha1 = [Security.Cryptography.SHA1]::Create()
    try { ([BitConverter]::ToString($sha1.ComputeHash($all)) -replace '-', '').ToLowerInvariant() }
    finally { $sha1.Dispose() }
}

function Read-ContractBytes([string] $RelativePath) {
    if ($SourceDirectory) {
        $local = Join-Path $SourceDirectory $RelativePath
        if (-not (Test-Path -LiteralPath $local)) { throw "Missing $RelativePath under $SourceDirectory" }
        return [IO.File]::ReadAllBytes($local)
    }
    $url = "https://raw.githubusercontent.com/$($pins.upstream.repo)/$commit/$RelativePath"
    $response = Invoke-WebRequest -UseBasicParsing -Uri $url
    $stream = New-Object IO.MemoryStream
    try { $response.RawContentStream.CopyTo($stream); return $stream.ToArray() } finally { $stream.Dispose() }
}

$texts = @{}
$failures = New-Object System.Collections.Generic.List[string]
foreach ($entry in $pins.contractFiles) {
    $bytes = Read-ContractBytes $entry.path
    $actual = Get-BlobSha1 $bytes
    if ($actual -ne $entry.blobSha1) {
        $failures.Add("blob mismatch: $($entry.path) expected $($entry.blobSha1) actual $actual")
        continue
    }
    $texts[$entry.path] = [Text.Encoding]::UTF8.GetString($bytes) -replace "`r`n", "`n"
}

function Get-Block([string] $Text, [string] $Header) {
    # Method bodies in these files close at the first line that is exactly the method indent plus "}".
    $index = $Text.IndexOf($Header, [StringComparison]::Ordinal)
    if ($index -lt 0) { return $null }
    $close = $Text.IndexOf("`n    }`n", $index, [StringComparison]::Ordinal)
    if ($close -lt 0) { return $null }
    return $Text.Substring($index, $close - $index)
}

$checks = New-Object System.Collections.Generic.List[object]
function Add-Check([string] $Path, [string] $Name, [scriptblock] $Test) {
    $checks.Add([pscustomobject] @{ Path = $Path; Name = $Name; Test = $Test })
}

$store = 'share/android/src/main/java/top/nkbe/npatch/util/NPatchRemoteStore.java'
Add-Check $store 'updatePreferences runs clear as a separate statement before applyPrefsDiff' {
    param($t)
    $body = Get-Block $t 'public void updatePreferences('
    $clear = if ($body) { $body.IndexOf('deleteModulePrefs(userId, safeGroup);', [StringComparison]::Ordinal) } else { -1 }
    $apply = if ($body) { $body.IndexOf('applyPrefsDiff(userId, safeGroup, values);', [StringComparison]::Ordinal) } else { -1 }
    $clear -ge 0 -and $apply -gt $clear
}
Add-Check $store 'deleteModulePrefs does not open a transaction' {
    param($t)
    $body = Get-Block $t 'private void deleteModulePrefs('
    $body -and ($body -notmatch 'beginTransaction')
}
Add-Check $store 'applyPrefsDiff applies deletes and puts in one transaction' {
    param($t)
    $body = Get-Block $t 'private void applyPrefsDiff('
    $body -and ($body -match 'beginTransaction\(\)') -and ($body -match 'setTransactionSuccessful\(\)') -and ($body -match 'CONFLICT_REPLACE')
}
Add-Check $store 'a non-zero user falls back to the user 0 document when its own is empty' {
    param($t) $t -match 'result\.isEmpty\(\) && userId != 0'
}
Add-Check $store 'remote files live in the Manager data directory keyed only by module package' {
    param($t) $t -match 'new File\(context\.getFilesDir\(\), "npatch/remote/" \+ modulePackageName\)'
}

$binder = 'manager/src/main/java/top/nkbe/npatch/manager/XposedServiceBinder.kt'
Add-Check $binder 'every IXposedService entry point enforces the caller uid' {
    param($t) ([regex]::Matches($t, 'enforceCaller\(\)')).Count -ge 15
}
Add-Check $binder 'requestScope activates the module for each requested package without a prompt' {
    param($t)
    $i = $t.IndexOf('override fun requestScope', [StringComparison]::Ordinal)
    $i -ge 0 -and $t.IndexOf('ConfigManager.activateModule(', $i, [StringComparison]::Ordinal) -gt $i -and
        $t.IndexOf('onScopeRequestApproved(requested)', $i, [StringComparison]::Ordinal) -gt $i
}
Add-Check $binder 'getScope reads the apps recorded for the module' {
    param($t) $t -match 'getAppsForModule\(packageName\)'
}

$provider = 'manager/src/main/java/top/nkbe/npatch/manager/RemoteApiProvider.kt'
Add-Check $provider 'writable service requires the module package to belong to the caller uid' {
    param($t) $t -match 'modulePackageName !in callerPackages'
}
Add-Check $provider 'unknown modules are rejected before any binder is created' {
    param($t) $t -match 'ConfigManager\.isModuleConfigured\(modulePackageName\)'
}
Add-Check $provider 'injected service requires the caller to be a scoped target' {
    param($t) $t -match 'callerPackages\.none\(scopedTargets::contains\)'
}
Add-Check $provider 'services are cached per module and uid and dropped when the client token dies' {
    param($t) ($t -match 'computeIfAbsent\(cacheKey\)') -and ($t -match 'KEY_CLIENT_TOKEN') -and ($t -match 'linkToDeath')
}

$config = 'manager/src/main/java/top/nkbe/npatch/manager/ConfigProvider.kt'
Add-Check $config 'scope query is keyed by target package and joins the module table' {
    param($t) ($t -match 'getQueryParameter\("package"\)') -and ($t -match 'ConfigManager\.getModulesForApp\(targetPackage\)')
}
Add-Check $config 'a null cursor means unknown and an empty cursor is authoritative' {
    param($t) ($t -match 'if \(modulesList == null\) return null') -and ($t -match 'MatrixCursor\(arrayOf\("packageName"\)\)')
}

$injected = 'share/android/src/main/java/top/nkbe/npatch/util/LocalInjectedModuleService.java'
Add-Check $injected 'the injected view is read-only for files and exposes no preference writer' {
    param($t) ($t -match 'store\.openFile\(path, false\)') -and ($t -notmatch 'updateRemotePreferences')
}

$wrapper = 'patch-loader/src/main/java/top/nkbe/npatch/service/FallbackModuleServiceWrapper.java'
Add-Check $wrapper 'the host mirrors preference snapshots locally but never mirrors files' {
    param($t)
    ($t -match 'localStore\.updatePreferences\(group, diff\)') -and ($t -match 'localService\.openRemoteFile\(path\)') -and
        ($t -notmatch 'localStore\.openFile')
}

$remoteApp = 'patch-loader/src/main/java/top/nkbe/npatch/service/RemoteApplicationService.java'
Add-Check $remoteApp 'the loader service is a real local Binder that survives Manager loss' {
    param($t) ($t -match 'class RemoteApplicationService extends IFrameworkService\.Stub') -and ($t -match '(?s)public IBinder asBinder\(\)\s*\{\s*return this;')
}

$embedded = 'patch-loader/src/main/java/top/nkbe/npatch/service/EmbeddedXposedService.java'
Add-Check $embedded 'embedded mode rejects runtime scope requests' {
    param($t) $t -match 'onScopeRequestFailed\("scope is set by patching'
}

$loader = 'patch-loader/src/main/java/top/nkbe/npatch/loader/LSPLoader.java'
Add-Check $loader 'LSPLoader exposes the active module APK paths' {
    param($t) $t -match 'public static Map<String, String> getActiveModuleApkPaths\(\)'
}

$sig = 'patch-loader/src/main/jni/src/jni/bypass_sig.cpp'
Add-Check $sig 'the redirect buffer is a trivially destructible thread_local array' {
    param($t) $t -match 'thread_local std::array<char, PATH_MAX> g_redirect_buffer;'
}
Add-Check $sig 'no thread_local object with a non-trivial destructor' {
    param($t) $t -notmatch 'thread_local\s+(?:static\s+)?(?:const\s+)?std::(?:string|wstring|vector|map|unordered_map|set|unordered_set|deque|list|function|shared_ptr|unique_ptr|optional)\b'
}
$paths = 'patch-loader/src/main/jni/src/jni/sig_bypass_paths.h'
Add-Check $paths 'CopyPathBuffer truncates and terminates instead of allocating' {
    param($t) ($t -match 'std::strncpy\(dest, src, dest_size - 1\)') -and ($t -match 'dest\[dest_size - 1\] = ''\\0''')
}

foreach ($check in $checks) {
    if (-not $texts.ContainsKey($check.Path)) { continue }   # blob mismatch already reported
    $ok = $false
    try { $ok = [bool] (& $check.Test $texts[$check.Path]) } catch { $ok = $false }
    if ($ok) { Write-Output "ok    $($check.Name)" } else { $failures.Add("anchor failed: $($check.Name) [$($check.Path)]") }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Output "FAIL  $_" }
    throw "Upstream contract verification failed ($($failures.Count))."
}
Write-Output "Upstream contracts hold at $commit ($($checks.Count) anchors, $($pins.contractFiles.Count) files)."
