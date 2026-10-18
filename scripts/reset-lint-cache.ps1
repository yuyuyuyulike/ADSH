<#
.SYNOPSIS
清理 :app:lint 的增量中间结果（源码大改后会腐坏，导致 lint 内部 K2 直接崩）。

.DESCRIPTION
lint 的部分分析结果落在 build/intermediates 下，腐坏时报
KaBasePropertyGetterSymbolPointer pointer already disposed（ExperimentalDetector），
--rerun 救不回，只能删掉这些目录再跑。默认扫描仓库里各模块的 build/intermediates。

.PARAMETER Root
仓库根目录，默认脚本所在目录的上一级。

.PARAMETER DryRun
只打印将要删除的内容，不真删。

.PARAMETER Force
即使检测到 Gradle 项目锁被占用（可能有构建在跑）也照删。

.EXAMPLE
.\scripts\reset-lint-cache.ps1 -DryRun

.EXAMPLE
.\scripts\reset-lint-cache.ps1
#>
[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [switch]$DryRun,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function Get-DirBytes {
    param([string]$Path)
    $sum = (Get-ChildItem -LiteralPath $Path -Recurse -File -ErrorAction SilentlyContinue |
        Measure-Object -Property Length -Sum).Sum
    if ($null -eq $sum) { return [long]0 }
    return [long]$sum
}

function Format-MB {
    param([long]$Bytes)
    return ('{0:N1} MB' -f ($Bytes / 1MB))
}

function Test-GradleLockHeld {
    param([string]$RepoRoot)
    $gradleDir = Join-Path $RepoRoot '.gradle'
    if (-not (Test-Path -LiteralPath $gradleDir)) { return $false }
    $locks = Get-ChildItem -LiteralPath $gradleDir -Filter 'executionHistory.lock' -Recurse -File -ErrorAction SilentlyContinue
    foreach ($lock in $locks) {
        try {
            $fs = [System.IO.File]::Open($lock.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
            $fs.Close()
        } catch {
            return $true
        }
    }
    return $false
}

$intermediates = @()
$rootTop = Join-Path $Root 'build/intermediates'
if (Test-Path -LiteralPath $rootTop) { $intermediates += $rootTop }
foreach ($module in (Get-ChildItem -LiteralPath $Root -Directory -ErrorAction SilentlyContinue)) {
    $candidate = Join-Path $module.FullName 'build/intermediates'
    if (Test-Path -LiteralPath $candidate) { $intermediates += $candidate }
}

$targets = @()
foreach ($dir in $intermediates) {
    $targets += Get-ChildItem -LiteralPath $dir -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like '*lint*partial_results*' -or $_.Name -eq 'lint-cache' }
}

if ($targets.Count -eq 0) {
    Write-Host '没有可清理的 lint 中间结果。'
    exit 0
}

if ((Test-GradleLockHeld -RepoRoot $Root) -and -not $Force) {
    Write-Warning '检测到 Gradle 项目锁被占用（可能有构建在跑）。等它结束再清，或用 -Force 强制清。'
    exit 1
}

Write-Host ('发现 {0} 个 lint 中间结果目录：' -f $targets.Count)
$total = [long]0
foreach ($target in $targets) {
    $bytes = Get-DirBytes -Path $target.FullName
    $total += $bytes
    $rel = $target.FullName.Substring($Root.Length).TrimStart('\', '/')
    if ($DryRun) {
        Write-Host ('  [DryRun] {0}  ({1})' -f $rel, (Format-MB $bytes))
    } else {
        Remove-Item -LiteralPath $target.FullName -Recurse -Force
        Write-Host ('  已删    {0}  ({1})' -f $rel, (Format-MB $bytes))
    }
}

if ($DryRun) {
    Write-Host ('DryRun：一个都没删，合计 {0}。' -f (Format-MB $total))
} else {
    Write-Host ('完成：释放 {0}。' -f (Format-MB $total))
}
exit 0
