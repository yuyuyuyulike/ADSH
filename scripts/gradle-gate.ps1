<#
.SYNOPSIS
Gradle 串行门：同一时刻只允许一个构建（AGENTS.md「跑 Gradle」）。

.DESCRIPTION
用 .tmp/gradle-gate.lock 做原子抢锁：抢到才执行 -Command，跑完（含失败、Ctrl+C）
在 finally 里交回。抢不到就等待并打印当前持锁者；持锁进程已消失时按陈旧锁接管。
命令统一走 cmd /c 执行，.bat / .exe 都能跑，退出码原样返回。

.PARAMETER Command
要执行的命令行，整串传（跟直接在 shell 里敲一样）：
  -Command '.\gradlew.bat :app:testDebugUnitTest --console=plain'

.PARAMETER TimeoutSec
等待锁的上限，默认 1800；0 或负数表示一直等。

.PARAMETER HoldSec
-DryRun 时持锁的秒数，用来验证并发串行（不执行任何命令）。

.PARAMETER JavaHome
JAVA_HOME 未设时用它兜底，默认 D:\WSN2005\Android\jbr；路径不存在就不动。

.PARAMETER Force
先删掉现有锁再抢（确认对方已经死了才用）。

.PARAMETER DryRun
抢锁 → 持锁 HoldSec 秒 → 交回，不执行命令。

.EXAMPLE
.\scripts\gradle-gate.ps1 -Command '.\gradlew.bat :app:testDebugUnitTest :app:lint :app:spotlessCheck --console=plain'

.EXAMPLE
.\scripts\gradle-gate.ps1 -DryRun -HoldSec 5
#>
[CmdletBinding()]
param(
    [string]$Command = '',
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [int]$TimeoutSec = 1800,
    [int]$HoldSec = 0,
    [string]$JavaHome = 'D:\WSN2005\Android\jbr',
    [switch]$Force,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Get-LockOwner {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $first = Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($first -match '^pid=(\d+)$') { return [int]$Matches[1] }
    return $null
}

function Get-LockSummary {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return '（锁文件已消失）' }
    return ((Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue) -join ' ')
}

$tmpDir = Join-Path $Root '.tmp'
New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null
$lockPath = Join-Path $tmpDir 'gradle-gate.lock'

if ($Force -and (Test-Path -LiteralPath $lockPath)) {
    Write-Warning (' -Force：删掉现有锁 -> ' + (Get-LockSummary $lockPath))
    Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
}

$deadline = $null
if ($TimeoutSec -gt 0) { $deadline = (Get-Date).AddSeconds($TimeoutSec) }

$mine = $false
$waited = $false
$start = Get-Date
while (-not $mine) {
    try {
        New-Item -ItemType File -Path $lockPath -ErrorAction Stop | Out-Null
        $mine = $true
        break
    } catch {
        $owner = Get-LockOwner -Path $lockPath
        if ($null -ne $owner -and -not (Get-Process -Id $owner -ErrorAction SilentlyContinue)) {
            Write-Warning ('锁的持有进程 pid=' + $owner + ' 已不存在，按陈旧锁接管。')
            Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
            continue
        }
        if (-not $waited) {
            Write-Host ('等待 Gradle 门：' + (Get-LockSummary $lockPath))
            $waited = $true
        }
        if ($null -ne $deadline -and (Get-Date) -ge $deadline) {
            Write-Warning ('等锁超时（-TimeoutSec ' + $TimeoutSec + ' 秒），锁仍被持有：' + (Get-LockSummary $lockPath))
            Write-Warning '没有执行任何命令。确认对方死了可以用 -Force 抢锁。'
            exit 2
        }
        Start-Sleep -Milliseconds 500
        $elapsed = [int]((Get-Date) - $start).TotalSeconds
        if ($elapsed -gt 0 -and $elapsed % 5 -eq 0) { Write-Host ('  已等 ' + $elapsed + ' 秒…') }
    }
}

$shown = $Command
if ($shown -eq '') { $shown = '(dry-run)' }
$info = @(
    'pid=' + $PID
    'user=' + $env:USERNAME
    'start=' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    'command=' + $shown
) -join [Environment]::NewLine
Set-Content -LiteralPath $lockPath -Value $info -Encoding UTF8

try {
    Write-Host ('拿到 Gradle 门（pid=' + $PID + '，锁 ' + $lockPath + '）')
    if ($waited) { Write-Host ('  等了 ' + [int]((Get-Date) - $start).TotalSeconds + ' 秒') }

    if ($DryRun) {
        if ($HoldSec -gt 0) { Start-Sleep -Seconds $HoldSec }
        Write-Host (' -DryRun：不执行命令，持锁 ' + $HoldSec + ' 秒后交回。')
        exit 0
    }

    if ($Command -eq '') {
        Write-Warning '没给命令。用法：.\scripts\gradle-gate.ps1 -Command ''.\gradlew.bat :app:lint'''
        exit 64
    }

    if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath $JavaHome)) {
        $env:JAVA_HOME = $JavaHome
        $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
        Write-Host ('  JAVA_HOME 未设，已用 ' + $JavaHome)
    }

    Write-Host ('  > ' + $Command)
    & cmd.exe /c $Command
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
    Write-Host ('命令退出码 ' + $code + '，交回 Gradle 门。')
    exit $code
} finally {
    if ($mine -and (Test-Path -LiteralPath $lockPath)) {
        $owner = Get-LockOwner -Path $lockPath
        if ($null -eq $owner -or $owner -eq $PID) {
            Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
        }
    }
}
