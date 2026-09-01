#Requires -Version 7.0

[CmdletBinding()]
param(
    [string]$Repository = "jichuo1/Bilibili_Innocent_Lab",
    [string]$KeyStorePath = (Join-Path $env:USERPROFILE "Documents\AndroidSigning\Bilibili_Innocent_Lab\innocent-lab-release.p12"),
    [string]$KeyAlias = "innocent_lab_release",
    [string[]]$Environments = @("alpha-release", "stable-release")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function ConvertTo-PlainText {
    param([Parameter(Mandatory)][Security.SecureString]$SecureValue)

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Read-ConfirmedPassword {
    while ($true) {
        $first = ConvertTo-PlainText (Read-Host "请输入发布密钥密码（至少 16 位 ASCII 字符）" -AsSecureString)
        $second = ConvertTo-PlainText (Read-Host "请再次输入相同密码" -AsSecureString)
        if ($first -ne $second) {
            Write-Warning "两次密码不一致，请重新输入。"
            continue
        }
        if ($first.Length -lt 16 -or $first.Length -gt 128 -or $first -notmatch '^[\x20-\x7E]+$') {
            Write-Warning "密码必须为 16～128 位可打印 ASCII 字符。"
            continue
        }
        return $first
    }
}

function Set-GitHubEnvironmentSecret {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$EnvironmentName
    )

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $script:GitHubCliPath
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @("secret", "set", $Name, "--env", $EnvironmentName, "--repo", $Repository)) {
        $null = $startInfo.ArgumentList.Add($argument)
    }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $null = $process.Start()
    $process.StandardInput.Write($Value)
    $process.StandardInput.Close()
    $standardOutput = $process.StandardOutput.ReadToEnd()
    $standardError = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw (
            "写入 GitHub Environment '$EnvironmentName' 的 Secret '$Name' 失败。" +
                [Environment]::NewLine + $standardError
        )
    }
    if (-not [string]::IsNullOrWhiteSpace($standardOutput)) {
        Write-Verbose $standardOutput.Trim()
    }
}

$repositoryRoot = [IO.Path]::GetFullPath(
    (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent)
).TrimEnd([IO.Path]::DirectorySeparatorChar)
$resolvedKeyStorePath = [IO.Path]::GetFullPath($KeyStorePath)
$repositoryPrefix = "$repositoryRoot$([IO.Path]::DirectorySeparatorChar)"
if ($resolvedKeyStorePath.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "密钥库必须位于仓库目录之外：$resolvedKeyStorePath"
}

$null = Get-Command keytool -ErrorAction Stop
$script:GitHubCliPath = (Get-Command gh -ErrorAction Stop).Source
& gh auth status --hostname github.com
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI 尚未登录 github.com。"
}

$password = Read-ConfirmedPassword
$passwordEnvironmentName = "INNOCENT_LAB_SETUP_KEY_PASSWORD"
$certificateFile = Join-Path ([IO.Path]::GetTempPath()) ("innocent-lab-" + [Guid]::NewGuid().ToString("N") + ".cer")
try {
    Set-Item -Path "Env:$passwordEnvironmentName" -Value $password
    $keyStoreDirectory = Split-Path $resolvedKeyStorePath -Parent
    $null = New-Item -ItemType Directory -Path $keyStoreDirectory -Force

    if (-not (Test-Path -LiteralPath $resolvedKeyStorePath -PathType Leaf)) {
        & keytool `
            -genkeypair `
            -alias $KeyAlias `
            -keyalg RSA `
            -keysize 4096 `
            -sigalg SHA256withRSA `
            -validity 18263 `
            -dname "CN=Bilibili Innocent Lab, OU=Release, O=Bilibili Innocent Lab, C=CN" `
            -storetype PKCS12 `
            -keystore $resolvedKeyStorePath `
            -storepass:env $passwordEnvironmentName `
            -keypass:env $passwordEnvironmentName `
            -noprompt
        if ($LASTEXITCODE -ne 0) {
            throw "生成发布密钥库失败。"
        }
        Write-Host "已在仓库外生成新的发布密钥库：$resolvedKeyStorePath"
    }
    else {
        Write-Host "将复用现有发布密钥库，不会覆盖：$resolvedKeyStorePath"
    }

    & keytool `
        -list `
        -alias $KeyAlias `
        -storetype PKCS12 `
        -keystore $resolvedKeyStorePath `
        -storepass:env $passwordEnvironmentName | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "无法使用指定密码和别名读取发布密钥库。"
    }

    & keytool `
        -exportcert `
        -alias $KeyAlias `
        -storetype PKCS12 `
        -keystore $resolvedKeyStorePath `
        -storepass:env $passwordEnvironmentName `
        -file $certificateFile | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "导出发布证书失败。"
    }

    $certificateSha256 = (Get-FileHash -LiteralPath $certificateFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $keyStoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($resolvedKeyStorePath))

    foreach ($environmentName in $Environments) {
        Set-GitHubEnvironmentSecret -Name "ANDROID_SIGNING_KEY_BASE64" -Value $keyStoreBase64 -EnvironmentName $environmentName
        Set-GitHubEnvironmentSecret -Name "ANDROID_SIGNING_STORE_PASSWORD" -Value $password -EnvironmentName $environmentName
        Set-GitHubEnvironmentSecret -Name "ANDROID_SIGNING_KEY_ALIAS" -Value $KeyAlias -EnvironmentName $environmentName
        Set-GitHubEnvironmentSecret -Name "ANDROID_SIGNING_KEY_PASSWORD" -Value $password -EnvironmentName $environmentName
        Set-GitHubEnvironmentSecret -Name "ANDROID_SIGNING_CERT_SHA256" -Value $certificateSha256 -EnvironmentName $environmentName
        Write-Host "已更新 GitHub Environment：$environmentName"
    }

    Write-Host "固定发布证书 SHA-256：$certificateSha256"
    Write-Host "请立即把密钥库与密码分别保存到可靠的离线备份和密码管理器。"
}
finally {
    Remove-Item -Path "Env:$passwordEnvironmentName" -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $certificateFile -Force -ErrorAction SilentlyContinue
    $password = $null
}
