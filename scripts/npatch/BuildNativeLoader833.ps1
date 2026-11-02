[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $ArchivesDirectory,
    [Parameter(Mandatory)] [string] $BasePatcherJar,
    [Parameter(Mandatory)] [string] $NdkDirectory,
    [Parameter(Mandatory)] [string] $CMakeDirectory,
    [Parameter(Mandatory)] [string] $Ninja,
    [Parameter(Mandatory)] [string] $OutputDirectory,
    [ValidateRange(1, 8)] [int] $Parallel = 4,
    [switch] $AllowDownload
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$lock = Get-Content (Join-Path $PSScriptRoot 'native/sources-833.json') -Raw | ConvertFrom-Json
function Hash([string] $Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
foreach ($name in 'ArchivesDirectory','BasePatcherJar','NdkDirectory','CMakeDirectory','Ninja') {
    Set-Variable -Name $name -Value (Resolve-Path -LiteralPath (Get-Variable -Name $name -ValueOnly)).Path
}
if ((Hash $BasePatcherJar) -ne $lock.basePatcherSha256) { throw 'Supply the verified Java-fixed 833 patcher.' }
if ((Get-Content (Join-Path $NdkDirectory 'source.properties') -Raw) -notmatch '(?m)^Pkg\.Revision\s*=\s*29\.0\.14206865\s*$') {
    throw 'This recipe requires NDK 29.0.14206865.'
}
$cmake = Join-Path $CMakeDirectory 'bin/cmake.exe'
$clang = Join-Path $NdkDirectory 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
$objcopy = Join-Path $NdkDirectory 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objcopy.exe'
if ((& $cmake --version | Select-Object -First 1) -ne 'cmake version 3.31.5' -or
    (& $Ninja --version) -ne '1.12.1') { throw 'Use the pinned CMake 3.31.5 and Ninja 1.12.1 recipe.' }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Output directory exists; choose a fresh directory.' }
$output = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory).Replace('\','/')
if ($output -match '[^\x00-\x7f]' -or $NdkDirectory -match '[^\x00-\x7f]') { throw 'Use ASCII output and NDK paths.' }

foreach ($archive in $lock.archives) {
    $file = Join-Path $ArchivesDirectory $archive.name
    if (!(Test-Path -LiteralPath $file)) {
        if (!$AllowDownload) { throw "Missing fixed archive: $($archive.name); use -AllowDownload to fetch it." }
        Invoke-WebRequest -Uri "https://codeload.github.com/$($archive.repo)/zip/$($archive.commit)" -OutFile $file
    }
    if ((Hash $file) -ne $archive.sha256) { throw "Archive hash mismatch: $($archive.name)" }
}

New-Item -ItemType Directory -Path $output | Out-Null
$source = "$output/src"
New-Item -ItemType Directory -Path $source | Out-Null
foreach ($archive in $lock.archives) {
    $destination = [IO.Path]::GetFullPath((Join-Path $source $archive.destination))
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $prefix = $destination.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $ArchivesDirectory $archive.name))
    try {
        $archiveRoot = $zip.Entries[0].FullName.Split('/')[0] + '/'
        foreach ($entry in $zip.Entries) {
            if (!$entry.FullName.StartsWith($archiveRoot, [StringComparison]::Ordinal)) { throw 'Unexpected archive root.' }
            $relative = $entry.FullName.Substring($archiveRoot.Length)
            if (!$relative -or $entry.FullName.EndsWith('/')) { continue }
            $target = [IO.Path]::GetFullPath((Join-Path $destination $relative))
            if (!$target.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Archive path escapes source directory.' }
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($target)) -Force | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    } finally { $zip.Dispose() }
}

$native = "$source/patch-loader/src/main/jni"
$bypass = "$native/src/jni/bypass_sig.cpp"
$bootstrap = "$native/src/patch_loader.cpp"
if ((Hash $bypass) -ne '1d6c1dc9d5a2d7cccf45b5e58cc9c9fdbc6da2cf4a595aad3ac9871735ccc8f0' -or
    (Hash $bootstrap) -ne '9fb56819b377e373a0faeec4c640577826130bb03e812ce43c7cc35eba99fbf8') {
    throw 'Unsupported native source files.'
}
$text = [IO.File]::ReadAllText($bypass)
$text = $text.Replace('#include "bypass_sig.h"', "#include `"bypass_sig.h`"`n#include `"RedirectPathBuffer.h`"")
$text = $text.Replace('static thread_local std::string g_redirect_buffer;', 'static thread_local bil::npatch::RedirectPathBuffer g_redirect_buffer;')
$text = $text.Replace("g_redirect_buffer = redirectApkPath;`n                return g_redirect_buffer.c_str();",
    "const char* copied = g_redirect_buffer.copy(redirectApkPath);`n                return copied != nullptr ? copied : pathname;")
$text = $text.Replace("g_redirect_buffer = g_lib_snapshots[snapshot_index].path;`n            return g_redirect_buffer.c_str();",
    "const char* copied = g_redirect_buffer.copy(g_lib_snapshots[snapshot_index].path);`n            return copied != nullptr ? copied : pathname;")
if ($text.Contains('g_redirect_buffer.c_str()') -or !$text.Contains('g_redirect_buffer.copy(redirectApkPath)')) { throw 'Incomplete buffer patch.' }
[IO.File]::WriteAllText($bypass, $text, [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'native/RedirectPathBuffer.h') -Destination "$native/src/jni/RedirectPathBuffer.h"
# The release source relies on a transitive declaration; standalone builds need this direct include.
$text = [IO.File]::ReadAllText($bootstrap).Replace('#include "jni/svc_bypass.h"', "#include `"jni/svc_bypass.h`"`n#include `"jni/bypass_sig.h`"")
[IO.File]::WriteAllText($bootstrap, $text, [Text.UTF8Encoding]::new($false))

$libraries = [ordered]@{}
$previousSourceDateEpoch = $env:SOURCE_DATE_EPOCH
$env:SOURCE_DATE_EPOCH = $lock.sourceDateEpoch
try {
    foreach ($abi in 'arm64-v8a','x86_64') {
        $build = "$output/$abi"
        $flags = "-g0 -fno-rtti -fvisibility=hidden -fvisibility-inlines-hidden -ffile-prefix-map=$source=. -ffile-prefix-map=$build=build"
        & $cmake -S $native -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$Ninja" `
            "-DCMAKE_TOOLCHAIN_FILE=$NdkDirectory/build/cmake/android.toolchain.cmake" `
            "-DANDROID_ABI=$abi" '-DANDROID_PLATFORM=android-28' '-DANDROID_STL=c++_static' `
            '-DCMAKE_BUILD_TYPE=Release' "-DCORE_ROOT=$source/core/native" "-DEXTERNAL_ROOT=$source/core/external" `
            "-DVECTOR_ROOT=$source/core" '-DVERSION_CODE=833' '-DVERSION_NAME=1.0.9' `
            '-DDEX_BUILDER_BUILD_SHARED=OFF' "-DDEBUG_SYMBOLS_PATH=$build/symbols" `
            "-DCMAKE_CXX_FLAGS=$flags" "-DCMAKE_C_FLAGS=-g0 -ffile-prefix-map=$source=. -ffile-prefix-map=$build=build" *> "$output/configure-$abi.log"
        if ($LASTEXITCODE -ne 0) { throw "Native configure failed; see configure-$abi.log" }
        & $cmake --build $build --target npatch --parallel $Parallel *> "$output/build-$abi.log"
        if ($LASTEXITCODE -ne 0) { throw "Native build failed; see build-$abi.log" }
        & $objcopy --remove-section=.gnu_debuglink "$build/libnpatch.so"
        if ($LASTEXITCODE -ne 0) { throw 'Native metadata normalization failed.' }
        $triple = if ($abi -eq 'arm64-v8a') { 'aarch64-linux-android28' } else { 'x86_64-linux-android28' }
        & $clang "--target=$triple" "--sysroot=$NdkDirectory/toolchains/llvm/prebuilt/windows-x86_64/sysroot" `
            -std=c++23 -O2 -static-libstdc++ '-Wl,--export-dynamic' `
            (Join-Path $PSScriptRoot 'native/RedirectPathBufferTest.cpp') -ldl -o "$build/redirect-buffer-test"
        if ($LASTEXITCODE -ne 0) { throw 'Native regression test compilation failed.' }
        & $clang "--target=$triple" "--sysroot=$NdkDirectory/toolchains/llvm/prebuilt/windows-x86_64/sysroot" `
            -std=c++23 -O2 -shared -fPIC -static-libstdc++ (Join-Path $PSScriptRoot 'native/LinkerLockFixture.cpp') `
            '-Wl,--allow-shlib-undefined' -o "$build/linker-lock-fixture.so"
        if ($LASTEXITCODE -ne 0) { throw 'Native lock fixture compilation failed.' }
        $libraries[$abi] = Hash "$build/libnpatch.so"
    }
} finally { $env:SOURCE_DATE_EPOCH = $previousSourceDateEpoch }

$patchedJar = "$output/patcher-833-native-fixed.jar"
$original = [IO.Compression.ZipFile]::OpenRead($BasePatcherJar)
$patched = [IO.Compression.ZipFile]::Open($patchedJar, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($entry in $original.Entries) {
        $copy = $patched.CreateEntry($entry.FullName, [IO.Compression.CompressionLevel]::Optimal)
        $copy.LastWriteTime = $entry.LastWriteTime
        $stream = $copy.Open()
        $abi = if ($entry.FullName -match '^assets/npatch/so/(arm64-v8a|x86_64)/libnpatch.so$') { $Matches[1] } else { $null }
        $inputStream = if ($abi) { [IO.File]::OpenRead("$output/$abi/libnpatch.so") } else { $entry.Open() }
        try { $inputStream.CopyTo($stream) } finally { $inputStream.Dispose(); $stream.Dispose() }
    }
} finally { $original.Dispose(); $patched.Dispose() }

function EntryHash($Entry) {
    $stream = $Entry.Open(); $sha = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose(); $stream.Dispose() }
}
$original = [IO.Compression.ZipFile]::OpenRead($BasePatcherJar)
$patched = [IO.Compression.ZipFile]::OpenRead($patchedJar)
try {
    if ($original.Entries.Count -ne $patched.Entries.Count) { throw 'Patcher entry count changed.' }
    foreach ($entry in $original.Entries) {
        $copy = $patched.GetEntry($entry.FullName)
        if ($null -eq $copy) { throw 'Patcher entry disappeared.' }
        $expected = if ($entry.FullName -match '^assets/npatch/so/(arm64-v8a|x86_64)/libnpatch.so$') {
            $libraries[$Matches[1]]
        } else { EntryHash $entry }
        if ((EntryHash $copy) -ne $expected) { throw "Unexpected patcher content: $($entry.FullName)" }
    }
} finally { $original.Dispose(); $patched.Dispose() }
[ordered]@{
    profile = '833-manager-binder-and-trivial-tls-v1'
    basePatcherSha256 = $lock.basePatcherSha256
    patcherSha256 = Hash $patchedJar
    headerSha256 = Hash (Join-Path $PSScriptRoot 'native/RedirectPathBuffer.h')
    sourceDateEpoch = $lock.sourceDateEpoch
    toolchain = [ordered]@{
        ndk = '29.0.14206865'; cmake = '3.31.5'; ninja = '1.12.1'
        powerShell = $PSVersionTable.PSVersion.ToString()
    }
    libraries = $libraries
} | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 "$output/verified.json"
Write-Output "Built native-fixed patcher: $patchedJar"
