[CmdletBinding()]
param(
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

# 保留已有 embed 调用方式；产物校验和签名流程只有一个实现。
& (Join-Path $PSScriptRoot 'NewPatchedApk.ps1') -Mode embedded @PSBoundParameters
