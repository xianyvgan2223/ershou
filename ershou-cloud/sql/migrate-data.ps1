<#
.SYNOPSIS
  第 5 轮 · Spring Cloud 重构：从旧库 `ershou` 一次性快照迁移到分库（ershou_user / ershou_item / ershou_order）。

.DESCRIPTION
  对应契约：智能体团队/第5轮-SpringCloud重构-设计.md §2.1 / §七.4
    ershou_user  <- ershou.sys_user / ershou.school / ershou.campus
    ershou_item  <- ershou.item（新增列 sale_count 用默认 0）/ ershou.item_category
    ershou_order <- ershou.meetup_order
  规则：
    * 保留原 id（AUTO_INCREMENT 从头，迁移显式插入 id）
    * 可重复执行：先 TRUNCATE 目标表，再 INSERT INTO ... SELECT ...（单条 SQL，服务端拷贝，不在客户端搬数据）
    * 列集合 = 旧表列 ∩ 新表列（用 information_schema 求交集，按旧表列序），新库独有列（sale_count）走默认值
    * 只读旧库（除了 TRUNCATE/INSERT 新库，不对 ershou 做任何写操作）
    * 逐表输出「旧库行数 / 新库行数 / 是否一致」，任一行数不一致 → 退出码非 0
  前置：已执行 `ershou-cloud/sql/init-cloud-databases.sql`（建库+建表+独立账号）。

.PARAMETER OldUser
  旧库连接账号，默认 root（跨 schema INSERT SELECT 需要同时有旧库读+新库写权限；root 最省事）

.PARAMETER DryRun
  只打印迁移计划与列交集，不写库。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\ershou-cloud\sql\migrate-data.ps1
  powershell -ExecutionPolicy Bypass -File .\ershou-cloud\sql\migrate-data.ps1 -DryRun

.NOTES
  凭据默认从仓库根 `.env` 读取（MYSQL_HOST/PORT/ROOT_USER/ROOT_PASSWORD/MYSQL_DATABASE），可被参数覆盖。
  日志：.build/cloud/logs/migrate-data-<时间戳>.log
  退出码：0=全部一致；>0=不一致表数；2=环境不可用。

  ⚠️ 注意：本脚本会 TRUNCATE 目标表后从旧库重灌（这是「可重复执行」的实现方式）。
     联调/演示期间不要重跑：云服务写入的新订单/新用户/sale_count 增量会被清回旧库快照基线。
     只想检查一致性时用 -DryRun（只打印计划与列交集，不写库）。
     实测教训（2026-10-19 13:43）：一次重跑把云库 6 张新单、1 个新用户、sale_count 增量清回基线。
#>
param(
  [string]$MySQLPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$OldHost = '',
  [int]$OldPort = 0,
  [string]$OldUser = '',
  [string]$OldPassword = '',
  [string]$OldDb = '',
  [string]$LogDir = '',
  [switch]$DryRun
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # ershou-cloud/sql -> 项目根
$logDirAbs = if ($LogDir) { if ([System.IO.Path]::IsPathRooted($LogDir)) { $LogDir } else { Join-Path $root $LogDir } } else { Join-Path $root '.build\cloud\logs' }
if (-not (Test-Path $logDirAbs)) { New-Item -ItemType Directory -Force -Path $logDirAbs | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $logDirAbs ('migrate-data-' + $stamp + '.log')
$script:lines = New-Object System.Collections.ArrayList
function Log([string]$s) { Write-Host $s; $null = $script:lines.Add($s) }

# ---------------------------------------------------------------- .env 载入
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
    $_ = $_.TrimStart([char]0xFEFF).Trim()
    $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
    if (-not $k -or -not $v) { return }
    Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
  }
}
if (-not $OldHost)     { $OldHost = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' } }
if ($OldPort -le 0)    { $OldPort = if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { 3306 } }
if (-not $OldUser)     { $OldUser = if ($env:MYSQL_ROOT_USER) { $env:MYSQL_ROOT_USER } else { 'root' } }
if (-not $OldPassword) { $OldPassword = if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { $env:MYSQL_PASSWORD } }
if (-not $OldDb)       { $OldDb = if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { 'ershou' } }

Log '============================================================'
Log (' 第 5 轮 · 分库数据迁移（快照）  ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Log (' 源: ' + $OldUser + '@' + $OldHost + ':' + $OldPort + '/' + $OldDb + '   模式: ' + $(if ($DryRun) { 'DryRun' } else { '实际迁移' }))
Log (' 日志: ' + $logFile)
Log '============================================================'

if (-not (Test-Path -LiteralPath $MySQLPath)) {
  Log ('[FAIL] 找不到 mysql 客户端：' + $MySQLPath)
  exit 2
}
$script:mysqlArgs = @(
  ('--host=' + $OldHost), ('--port=' + $OldPort), ('--user=' + $OldUser),
  ('--password=' + $OldPassword), '--default-character-set=utf8mb4', '-N', '-B', '--raw'
)
function SqlRaw([string]$q) {
  $out = $q | & $MySQLPath @script:mysqlArgs 2>$null
  if ($LASTEXITCODE -ne 0) { throw ('mysql 退出码 ' + $LASTEXITCODE + '：' + $q) }
  return @($out)
}
function Sql1([string]$q) { $r = @(SqlRaw $q); if ($r.Count -eq 0) { return '' }; return ([string]$r[0]).Trim() }

# ---------------------------------------------------------------- 迁移计划
$plan = @(
  @{ dstDb = 'ershou_user';  table = 'sys_user';     src = 'sys_user' },
  @{ dstDb = 'ershou_user';  table = 'school';       src = 'school' },
  @{ dstDb = 'ershou_user';  table = 'campus';       src = 'campus' },
  @{ dstDb = 'ershou_item';  table = 'item';         src = 'item' },
  @{ dstDb = 'ershou_item';  table = 'item_category'; src = 'item_category' },
  @{ dstDb = 'ershou_order'; table = 'meetup_order'; src = 'meetup_order' }
)

# 连通性预检
try {
  $dbNow = Sql1 'SELECT DATABASE();'
  Log ('[ OK ] MySQL 可连接（当前默认库: ' + $(if ($dbNow) { $dbNow } else { '<none>' }) + '）')
  $dbs = @(SqlRaw "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('ershou','ershou_user','ershou_item','ershou_order');" | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
  Log ('[ OK ] 存在的 schema: ' + ($dbs -join ', '))
  foreach ($need in @('ershou', 'ershou_user', 'ershou_item', 'ershou_order')) {
    if ($dbs -notcontains $need) { Log ('[FAIL] 缺少 schema：' + $need + '（先执行 ershou-cloud/sql/init-cloud-databases.sql）'); exit 2 }
  }
} catch {
  Log ('[FAIL] MySQL 连接失败：' + $_.Exception.Message)
  exit 2
}

# ---------------------------------------------------------------- 逐表迁移
$totalOld = 0; $totalNew = 0; $mismatch = 0
foreach ($p in $plan) {
  $srcTable = $p.src; $dstTable = $p.table; $dstDb = $p.dstDb
  Log ''
  Log ('--- ' + $dstDb + '.' + $dstTable + '  <=  ' + $OldDb + '.' + $srcTable + ' ---')
  try {
    # 列交集：旧表列（按序号）∩ 新表列
    $oldCols = @(SqlRaw ("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + $OldDb + "' AND TABLE_NAME='" + $srcTable + "' ORDER BY ORDINAL_POSITION;") | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
    $newCols = @(SqlRaw ("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + $dstDb + "' AND TABLE_NAME='" + $dstTable + "' ORDER BY ORDINAL_POSITION;") | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
    if ($oldCols.Count -eq 0) { Log ('  [SKIP] 源表不存在或无列：' + $OldDb + '.' + $srcTable); continue }
    if ($newCols.Count -eq 0) { Log ('  [SKIP] 目标表不存在或无列：' + $dstDb + '.' + $dstTable + '（先执行 init-cloud-databases.sql）'); continue }
    $cols = @($oldCols | Where-Object { $newCols -contains $_ })
    $dropped = @($oldCols | Where-Object { $newCols -notcontains $_ })
    $added = @($newCols | Where-Object { $oldCols -notcontains $_ })
    Log ('  列交集 ' + $cols.Count + ' 列；新库独有列（走默认值）: ' + $(if ($added.Count) { $added -join ',' } else { '无' }) + '；旧库多出列（不迁移）: ' + $(if ($dropped.Count) { $dropped -join ',' } else { '无' }))
    $colList = ($cols | ForEach-Object { '`' + $_ + '`' }) -join ', '
    $oldCountBefore = [int](Sql1 ('SELECT COUNT(*) FROM `' + $OldDb + '`.`' + $srcTable + '`;'))
    if ($DryRun) {
      Log ('  [DRY] 将执行: TRUNCATE `' + $dstDb + '`.`' + $dstTable + '`; INSERT INTO `' + $dstDb + '`.`' + $dstTable + '` (' + $colList + ') SELECT ' + $colList + ' FROM `' + $OldDb + '`.`' + $srcTable + '`;')
      Log ('  [DRY] 源表行数 = ' + $oldCountBefore)
      continue
    }
    # 单条语句完成 TRUNCATE + INSERT SELECT（同一连接、服务端拷贝）
    $sql = ('TRUNCATE TABLE `' + $dstDb + '`.`' + $dstTable + '`;' +
            'INSERT INTO `' + $dstDb + '`.`' + $dstTable + '` (' + $colList + ') SELECT ' + $colList + ' FROM `' + $OldDb + '`.`' + $srcTable + '`;')
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $null = $sql | & $MySQLPath @script:mysqlArgs 2>$null
    $exit = $LASTEXITCODE
    $sw.Stop()
    if ($exit -ne 0) { Log ('  [FAIL] 迁移 SQL 失败 exit=' + $exit); $mismatch++; continue }
    $newCount = [int](Sql1 ('SELECT COUNT(*) FROM `' + $dstDb + '`.`' + $dstTable + '`;'))
    $oldCountAfter = [int](Sql1 ('SELECT COUNT(*) FROM `' + $OldDb + '`.`' + $srcTable + '`;'))
    $ok = ($newCount -eq $oldCountBefore) -and ($oldCountAfter -eq $oldCountBefore)
    if (-not $ok) { $mismatch++ }
    $totalOld += $oldCountBefore; $totalNew += $newCount
    Log ('  旧库 ' + $oldCountBefore + ' 行 -> 新库 ' + $newCount + ' 行  ' + $(if ($ok) { '[OK]' } else { '[FAIL]' }) + '  耗时 ' + $sw.ElapsedMilliseconds + 'ms（旧库迁移前后行数 ' + $oldCountBefore + '->' + $oldCountAfter + '，未被修改）')
    if ($dstTable -eq 'item') {
      $saleZero = [int](Sql1 ('SELECT COUNT(*) FROM `' + $dstDb + '`.`' + $dstTable + '` WHERE sale_count = 0;'))
      Log ('  sale_count=0 行数 = ' + $saleZero + ' / ' + $newCount + '（新库默认 0，待 MQ 消费累加）')
    }
  } catch {
    Log ('  [FAIL] ' + $_.Exception.Message)
    $mismatch++
  }
}

# ---------------------------------------------------------------- 汇总
Log ''
Log '============================================================'
Log (' 汇总：旧库合计 ' + $totalOld + ' 行 -> 新库合计 ' + $totalNew + ' 行；不一致表数 = ' + $mismatch)
try {
  Log ' 新库表行数：'
  foreach ($db in @('ershou_user', 'ershou_item', 'ershou_order')) {
    $rows = @(SqlRaw ("SELECT CONCAT(TABLE_NAME, '=', TABLE_ROWS) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + $db + "' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;") | ForEach-Object { ([string]$_).Trim() })
    Log ('   ' + $db + ': ' + ($rows -join ', '))
  }
} catch { }
Log (' 日志已写入: ' + $logFile)
Log '============================================================'
$script:lines | Set-Content -Path $logFile -Encoding UTF8
if ($DryRun) { exit 0 }
exit ([Math]::Min($mismatch, 250))
