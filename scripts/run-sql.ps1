<#
.SYNOPSIS
  从仓库根 .env 读取密码并执行 SQL 文件（SQL 里用 __MYSQL_PASSWORD__ 占位）。

.DESCRIPTION
  为什么需要它：SQL 文件不能直接读环境变量。脚本在内存里把占位符替换成 .env 的真实值，
  再管道给 mysql，仓库里不会留下明文密码。

  用法：
    powershell -ExecutionPolicy Bypass -File .\scripts\run-sql.ps1 -File .\ershou-cloud\sql\init-cloud-databases.sql
    powershell -ExecutionPolicy Bypass -File .\scripts\run-sql.ps1 -File .\ershou-cloud\sql\split-admin.sql
    powershell -ExecutionPolicy Bypass -File .\scripts\run-sql.ps1 -File .\x.sql -User ershou_item -Database ershou_item
#>
param(
  [Parameter(Mandatory = $true)][string]$File,
  [string]$User = 'root',
  [string]$Database = '',
  [string]$MySQLPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# 载入 .env
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) {
  Write-Host '缺少 .env：请先 copy .env.example .env 并填写密码' -ForegroundColor Red
  exit 1
}
[System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) |
  Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Za-z_]+=' } |
  ForEach-Object {
    $line = $_.TrimStart([char]0xFEFF).Trim()
    $k, $v = (($line -split '#', 2)[0]) -split '=', 2
    if ($k -and $v) { Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim() }
  }

if (-not (Test-Path $MySQLPath)) {
  Write-Host ("找不到 mysql 客户端：{0}（可用 -MySQLPath 指定）" -f $MySQLPath) -ForegroundColor Red
  exit 1
}

$sqlPath = if ([System.IO.Path]::IsPathRooted($File)) { $File } else { Join-Path $root $File }
if (-not (Test-Path $sqlPath)) {
  Write-Host ("找不到 SQL 文件：{0}" -f $sqlPath) -ForegroundColor Red
  exit 1
}

$password = if ($User -eq 'root') { $env:MYSQL_ROOT_PASSWORD } else { $env:MYSQL_PASSWORD }
if (-not $password) {
  Write-Host ("缺少密码：请在 .env 配置 {0}" -f $(if ($User -eq 'root') { 'MYSQL_ROOT_PASSWORD' } else { 'MYSQL_PASSWORD' })) -ForegroundColor Red
  exit 1
}

$sql = [System.IO.File]::ReadAllText($sqlPath, [System.Text.Encoding]::UTF8)
$sql = $sql.Replace('__MYSQL_PASSWORD__', $env:MYSQL_PASSWORD)
$sql = $sql.Replace('__MYSQL_ROOT_PASSWORD__', $env:MYSQL_ROOT_PASSWORD)

# 临时文件放 .build（已被 .gitignore 忽略），避免 PowerShell 管道编码问题
$tmpDir = Join-Path $root '.build\sql'
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$tmp = Join-Path $tmpDir 'last.sql'
[System.IO.File]::WriteAllText($tmp, $sql, (New-Object System.Text.UTF8Encoding($false)))

$oldPwd = $env:MYSQL_PWD
$oldEncoding = $OutputEncoding
try {
  $env:MYSQL_PWD = $password
  $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
  $mysqlArgs = @('--host=127.0.0.1', '--port=3306', ('--user=' + $User), '--default-character-set=utf8mb4')
  if ($Database) { $mysqlArgs += ('--database=' + $Database) }
  Get-Content -Raw -Encoding UTF8 $tmp | & $MySQLPath @mysqlArgs
  exit $LASTEXITCODE
} finally {
  $env:MYSQL_PWD = $oldPwd
  $OutputEncoding = $oldEncoding
  Remove-Item -Force $tmp -ErrorAction SilentlyContinue
}
