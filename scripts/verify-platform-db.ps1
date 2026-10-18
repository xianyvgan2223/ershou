<#
.SYNOPSIS
  第 2 轮「平台代卖」验收 · DB 侧证据脚本（只读 SELECT / information_schema，不写库）。

.DESCRIPTION
  对应 `智能体团队/第2轮-平台代卖-设计.md`：
    - 第二节 2.2 表结构 + 种子数据
    - 第七节验收标准 3：迁移落库、item.seller_type 列、6 张 platform_* 表、种子 4 商品/3 券/1 场次/3 点位
  断言分 7 组：
    G1 连接与环境（MySQL 可达、库名、客户端版本、迁移文件存在）
    G2 item 表 ALTER：seller_type / platform_point_id 列 + idx_item_platform 索引
    G3 6 张 platform_* 表存在 + 行数
    G4 平台官方账号 id=1000（昵称/role_flags&16/identity_flag/status/phone_hash）
    G5 种子：4 平台商品 / 3 代卖点 / 3 优惠券 / 1 秒杀场次 + 2 场次商品
    G6 唯一键（幂等/防重复领取的 DB 兜底）：coupon_user、flash_sale_item、seckill_record
    G7 与 Redis 对账口径：每张券/每个秒杀商品的「DB 口径剩余库存」

  幂等验收（验收标准 3 要求「可重复执行」）：
    本脚本**不执行**迁移（迁移由 Lead 执行），只提供快照对比：
      第一次： .\scripts\verify-platform-db.ps1 -Snapshot .build\qa\db-before.json
      Lead 第二遍执行 sql/migration-platform.sql 之后：
              .\scripts\verify-platform-db.ps1 -Compare  .build\qa\db-before.json
    对比结果显示计数无漂移 => 迁移幂等（第二遍没重复插入）。

.PARAMETER OutJson
  结果 JSON 输出路径（默认 .build/qa/platform-db-result.json）。目录会自动创建。

.PARAMETER Snapshot
  把当前计数写进快照 JSON（幂等对比的「之前」）。

.PARAMETER Compare
  与快照 JSON 对比计数（幂等对比的「之后」），有漂移则 FAIL。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\verify-platform-db.ps1

.NOTES
  凭据从仓库根 `.env` 读取（MYSQL_HOST/PORT/USER/PASSWORD/DATABASE），可被参数覆盖。
  退出码 = 失败断言数（0 = 全通过；>0 = 有 FAIL）。
#>
param(
  [string]$MySQLPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$DbHost    = '',
  [int]   $DbPort    = 0,
  [string]$DbUser    = '',
  [string]$DbPassword = '',
  [string]$DbName    = '',
  [string]$OutJson   = '',
  [string]$Snapshot  = '',
  [string]$Compare   = ''
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$migrationFile = Join-Path $root 'sql\migration-platform.sql'

# ---------------------------------------------------------------- .env 载入（与 scripts/smoke-test.ps1 同款解析）
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
    $_ = $_.TrimStart([char]0xFEFF).Trim()
    $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
    if (-not $k -or -not $v) { return }
    Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
  }
}
if (-not $DbHost)     { $DbHost = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' } }
if ($DbPort -le 0)    { $DbPort = if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { 3306 } }
if (-not $DbUser)     { $DbUser = if ($env:MYSQL_USER) { $env:MYSQL_USER } else { 'ershou' } }
if (-not $DbPassword) { $DbPassword = if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { '' } }
if (-not $DbName)     { $DbName = if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { 'ershou' } }

$qaDir = Join-Path $root '.build\qa'
if ($OutJson) {
  if (-not $OutJson) { }
  $outFull = if ([System.IO.Path]::IsPathRooted($OutJson)) { $OutJson } else { Join-Path $root $OutJson }
} else {
  $outFull = Join-Path $qaDir 'platform-db-result.json'
}
$snapFull  = if ($Snapshot) { if ([System.IO.Path]::IsPathRooted($Snapshot)) { $Snapshot } else { Join-Path $root $Snapshot } } else { '' }
$cmpFull   = if ($Compare)  { if ([System.IO.Path]::IsPathRooted($Compare))  { $Compare }  else { Join-Path $root $Compare } } else { '' }
if (-not (Test-Path $qaDir)) { New-Item -ItemType Directory -Force -Path $qaDir | Out-Null }

# ---------------------------------------------------------------- 结果收集
$script:pass = 0; $script:fail = 0; $script:skip = 0; $script:warn = 0
$script:results = New-Object System.Collections.ArrayList
function Check([string]$Id, [string]$Item, [bool]$Ok, [string]$Expect, [string]$Actual) {
  if ($Ok) { $script:pass++ } else { $script:fail++ }
  $status = if ($Ok) { 'PASS' } else { 'FAIL' }
  $color = if ($Ok) { 'Green' } else { 'Red' }
  Write-Host ('  [' + $status + '] ' + $Id + ' ' + $Item) -ForegroundColor $color
  if (-not $Ok) { Write-Host ('         期望: ' + $Expect) -ForegroundColor Red; Write-Host ('         实际: ' + $Actual) -ForegroundColor Red }
  $null = $script:results.Add([pscustomobject]@{ id=$Id; item=$Item; status=$status; expect=$Expect; actual=$Actual })
}
function CheckContains([string]$Id, [string]$Item, [string]$Actual, [string]$Needle) {
  Check $Id $Item ($Actual.Contains($Needle)) ('包含 "' + $Needle + '"') ('实际 "' + $Actual + '"')
}
function CheckEq([string]$Id, [string]$Item, $Expected, $Actual) {
  Check $Id $Item ([string]$Expected -eq [string]$Actual) ([string]$Expected) ([string]$Actual)
}
function Skip([string]$Id, [string]$Item, [string]$Reason) {
  $script:skip++
  Write-Host ('  [SKIP] ' + $Id + ' ' + $Item + ' —— ' + $Reason) -ForegroundColor DarkYellow
  $null = $script:results.Add([pscustomobject]@{ id=$Id; item=$Item; status='SKIP'; expect=''; actual=$Reason })
}
function Warn([string]$Id, [string]$Item, [string]$Detail) {
  $script:warn++
  Write-Host ('  [WARN] ' + $Id + ' ' + $Item + ' —— ' + $Detail) -ForegroundColor Yellow
  $null = $script:results.Add([pscustomobject]@{ id=$Id; item=$Item; status='WARN'; expect=''; actual=$Detail })
}
function Head([string]$Text) { Write-Host ''; Write-Host ('=== ' + $Text + ' ===') -ForegroundColor Cyan }

# ---------------------------------------------------------------- MySQL 调用（长参数，避免 PowerShell 把 -h 当开关）
if (-not (Test-Path $MySQLPath)) {
  Write-Host ('找不到 mysql 客户端：' + $MySQLPath) -ForegroundColor Red
  Write-Host '可用 -MySQLPath 指定，例如 "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"' -ForegroundColor Yellow
  exit 1
}
$script:mysqlArgs = @(
  ('--host=' + $DbHost), ('--port=' + $DbPort), ('--user=' + $DbUser),
  ('--password=' + $DbPassword), ('--database=' + $DbName),
  '--default-character-set=utf8mb4', '-N', '-B'
)
function SqlRaw([string]$q) {
  # 用 stdin 传 SQL：PS 5.1 把 `-e '... "..." ...'` 传给原生 mysql.exe 时双引号会被吞掉 → 1064 语法错误
  $out = $q | & $MySQLPath @script:mysqlArgs 2>$null
  if ($LASTEXITCODE -ne 0) { throw ('mysql 退出码 ' + $LASTEXITCODE + '：' + $q) }
  return @($out)
}
function Sql1([string]$q) {
  $rows = @(SqlRaw $q)
  if ($rows.Count -eq 0) { return '' }
  return ([string]$rows[0]).Trim()
}
function SqlCount([string]$q) { $v = Sql1 $q; if (-not $v) { return 0 } return [int]$v }
function SqlRows([string]$q) { return @(SqlRaw $q) }

Write-Host '============================================================' -ForegroundColor White
Write-Host ' 第 2 轮 · 平台代卖验收 · DB 检查（只读）' -ForegroundColor White
Write-Host (' 目标: ' + $DbUser + '@' + $DbHost + ':' + $DbPort + '/' + $DbName) -ForegroundColor DarkGray
Write-Host (' 时间: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')) -ForegroundColor DarkGray
Write-Host '============================================================' -ForegroundColor White

# ================================================================ G1 连接与环境
Head 'G1 连接与环境'
$connOk = $false
try {
  $opened = Sql1 'SELECT DATABASE();'
  CheckEq 'G1-1' 'MySQL 可连接且库正确' $DbName $opened
  $ver = Sql1 'SELECT VERSION();'
  Warn 'G1-2' 'MySQL 版本' $ver
  $connOk = $true
} catch {
  Check 'G1-1' 'MySQL 可连接' $false '能连上并返回库名' ('异常: ' + $_.Exception.Message)
}

if (Test-Path $migrationFile) {
  $mi = Get-Item $migrationFile
  Check 'G1-3' '迁移文件 sql/migration-platform.sql 存在' $true '存在' ('存在，' + $mi.Length + ' 字节，改于 ' + $mi.LastWriteTime.ToString('yyyy-MM-dd HH:mm') + '（由 Lead 执行，本脚本不执行）')
} else {
  Check 'G1-3' '迁移文件 sql/migration-platform.sql 存在' $false '存在' '不存在（platform-dev 尚未交付或尚未合并）'
}

if (-not $connOk) {
  Write-Host ''
  Write-Host 'MySQL 连不上，后续 DB 断言全部跳过。' -ForegroundColor Red
  exit 1
}

# ================================================================ G2 item 表 ALTER
Head 'G2 item 表 ALTER（设计 2.2）'
$colSql = "SELECT CONCAT_WS('|', COLUMN_TYPE, IS_NULLABLE, COALESCE(COLUMN_DEFAULT,'<NULL>')) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='item' AND COLUMN_NAME='{0}';"
$sellerCol = Sql1 ($colSql -f 'seller_type')
if (-not $sellerCol) {
  Check 'G2-1' 'item.seller_type 列存在' $false '存在 tinyint NOT NULL DEFAULT 1' '列不存在'
} else {
  $p = $sellerCol -split '\|'
  CheckContains 'G2-1' 'item.seller_type 类型为 tinyint' $p[0] 'tinyint'
  CheckEq       'G2-2' 'item.seller_type NOT NULL' 'NO' $p[1]
  CheckEq       'G2-3' 'item.seller_type DEFAULT 1' '1' $p[2]
}
$pointCol = Sql1 ($colSql -f 'platform_point_id')
if (-not $pointCol) {
  Check 'G2-4' 'item.platform_point_id 列存在' $false '存在 bigint NULL' '列不存在'
} else {
  $p = $pointCol -split '\|'
  CheckContains 'G2-4' 'item.platform_point_id 类型为 bigint' $p[0] 'bigint'
  CheckEq       'G2-5' 'item.platform_point_id 允许 NULL' 'YES' $p[1]
}
$idxCols = Sql1 "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='item' AND INDEX_NAME='idx_item_platform';"
CheckEq 'G2-6' 'item 索引 idx_item_platform(seller_type,status,campus_id)' 'seller_type,status,campus_id' $idxCols

# ================================================================ G3 6 张平台表
Head 'G3 platform_* 6 张表（设计 2.2）'
$expectTables = @('platform_consign_point','platform_coupon','platform_coupon_user','platform_flash_sale','platform_flash_sale_item','platform_seckill_record')
$gotTables = @(SqlRows "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'platform\_%' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;" | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
$missing = @($expectTables | Where-Object { $gotTables -notcontains $_ })
Check 'G3-1' ('6 张 platform_* 表齐全（实际 ' + $gotTables.Count + ' 张：' + ($gotTables -join ',') + '）') ($missing.Count -eq 0) ('齐全: ' + ($expectTables -join ',')) ('缺: ' + ($missing -join ','))
$tableCounts = [ordered]@{}
foreach ($t in $expectTables) {
  if ($gotTables -contains $t) {
    try {
      $c = SqlCount ('SELECT COUNT(*) FROM `' + $t + '`;')
      $tableCounts[$t] = $c
      Warn 'G3-2' ('行数 ' + $t) ($c.ToString())
    } catch { Check ('G3-2') ('行数 ' + $t) $false '可查询' ('异常: ' + $_.Exception.Message) }
  } else { $tableCounts[$t] = -1 }
}
# 主键检查（迁移脚本应全部带 AUTO_INCREMENT 主键）
foreach ($t in @('platform_consign_point','platform_coupon','platform_flash_sale','platform_seckill_record','platform_coupon_user','platform_flash_sale_item')) {
  if ($gotTables -notcontains $t) { continue }
  $pk = @(SqlRows ("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + $t + "' AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION;") | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
  Check ('G3-3') ('主键 ' + $t) ($pk.Count -ge 1) '至少 1 列主键' ($pk -join ',')
}

# ================================================================ G4 平台官方账号 1000
Head 'G4 平台官方账号 sys_user.id=1000（设计 2.1）'
$accSql = "SELECT CONCAT_WS('|', nickname, role_flags, identity_flag, status, COALESCE(phone_hash,'<NULL>')) FROM sys_user WHERE id=1000;"
$acc = Sql1 $accSql
if (-not $acc) {
  Check 'G4-1' '平台账号 id=1000 存在' $false '存在' '不存在'
} else {
  $a = $acc -split '\|'
  CheckEq 'G4-1' '昵称 = 校园二手官方严选' '校园二手官方严选' $a[0]
  $roleOk = ([int]$a[1] -band 16) -ne 0
  Check 'G4-2' ('role_flags 含 16=平台自营（实际 role_flags=' + $a[1] + '）') $roleOk 'role_flags & 16 != 0' $a[1]
  CheckEq 'G4-3' 'identity_flag=1（商家徽章）' '1' $a[2]
  CheckEq 'G4-4' 'status=1（正常）' '1' $a[3]
  # phone_hash 用 PowerShell 自己算 SHA256('ershou-platform-seller')，避免 DB/客户端排序规则差异
  $sha = [System.Security.Cryptography.SHA256]::Create()
  $hashBytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes('ershou-platform-seller'))
  $expectHash = (($hashBytes | ForEach-Object { $_.ToString('x2') }) -join '')
  $hashMatch = ([string]$a[4]).ToLower() -eq $expectHash
  Check 'G4-5' 'phone_hash = SHA256("ershou-platform-seller")' $hashMatch $expectHash ([string]$a[4])
}

# ================================================================ G5 种子数据
Head 'G5 种子数据（设计 2.2）'
$platformItems = @(); $points = @(); $coupons = @(); $flash = @(); $activeFlash = $null
try {

# ---- 5.1 平台商品 4 件
$platformItems = @(SqlRows "SELECT CONCAT_WS('|', id, seller_id, COALESCE(platform_point_id,'<NULL>'), status, price, COALESCE(original_price,'<NULL>'), COALESCE(expire_at,'<NULL>'), (expire_at IS NOT NULL AND expire_at > NOW()), COALESCE(prefer_point_id,'<NULL>'), stock, is_unique, title) FROM item WHERE seller_type=2 ORDER BY id;")
Check 'G5-1' ('平台商品数量 >= 4（实际 ' + $platformItems.Count + '）') ($platformItems.Count -ge 4) '>=4' $platformItems.Count.ToString()
$badSeller = 0; $badPoint = 0; $badStatus = 0; $badExpire = 0; $badTitle = 0; $badPrefer = 0
foreach ($line in $platformItems) {
  $f = ([string]$line) -split '\|'
  if ([string]$f[1] -ne '1000') { $badSeller++ }
  if ($f[2] -eq '<NULL>') { $badPoint++ }
  if ([string]$f[3] -ne '2') { $badStatus++ }
  if ([string]$f[7] -ne '1') { $badExpire++ }
  if ($f[8] -ne '<NULL>') { $badPrefer++ }
  # LIKE '[官方严选]%' 在 MySQL 里 [ 不是通配符，但 PowerShell -like 里 [ 是通配符集合，所以用 StartsWith
  if (-not ([string]$f[11]).StartsWith('[官方严选]')) { $badTitle++ }
}
Check 'G5-2' '平台商品 seller_id 全为 1000' ($badSeller -eq 0) '0 条异常' ($badSeller.ToString() + ' 条异常')
Check 'G5-3' '平台商品 platform_point_id 全非空' ($badPoint -eq 0) '0 条异常' ($badPoint.ToString() + ' 条异常')
Check 'G5-4' '平台商品 status 全为 2（在售）' ($badStatus -eq 0) '0 条异常' ($badStatus.ToString() + ' 条异常')
Check 'G5-5' '平台商品 expire_at 全在未来（+30 天）' ($badExpire -eq 0) '0 条异常' ($badExpire.ToString() + ' 条异常')
Check 'G5-6' '平台商品标题全为 [官方严选] 前缀' ($badTitle -eq 0) '0 条异常' ($badTitle.ToString() + ' 条异常')
Check 'G5-6b' '平台商品 prefer_point_id 全为 NULL（平台点用 platform_point_id，不占 trade_point id 空间）' ($badPrefer -eq 0) '0 条异常' ($badPrefer.ToString() + ' 条异常')
$discountCount = SqlCount "SELECT COUNT(*) FROM item WHERE seller_type=2 AND original_price IS NOT NULL AND original_price > 0 AND price < original_price;"
Warn 'G5-7' '平台商品 85 折（price < original_price）' ($discountCount.ToString() + ' / ' + $platformItems.Count + ' 条')
foreach ($line in $platformItems) {
  $f = ([string]$line) -split '\|'
  Warn 'G5-8' ('种子商品 id=' + $f[0]) ('price=' + $f[4] + ' original=' + $f[5] + ' expire=' + $f[6] + ' stock=' + $f[9] + ' is_unique=' + $f[10] + ' title=' + $f[11])
}

# ---- 5.2 代卖点 3 个
$points = @(SqlRows "SELECT CONCAT_WS('|', id, campus_id, status, COALESCE(lng,'<NULL>'), COALESCE(lat,'<NULL>'), name) FROM platform_consign_point ORDER BY id;")
Check 'G5-9' ('代卖点数量 >= 3（实际 ' + $points.Count + '）') ($points.Count -ge 3) '>=3' $points.Count.ToString()
$pCampus2 = 0; $pStatus1 = 0; $pGeo = 0
foreach ($line in $points) {
  $f = ([string]$line) -split '\|'
  if ([string]$f[1] -eq '2') { $pCampus2++ }
  if ([string]$f[2] -eq '1') { $pStatus1++ }
  if ($f[3] -ne '<NULL>' -and $f[4] -ne '<NULL>') { $pGeo++ }
}
Check 'G5-10' '代卖点 campus_id=2' ($pCampus2 -eq $points.Count) ($points.Count.ToString()) $pCampus2.ToString()
Check 'G5-11' '代卖点 status=1（启用）' ($pStatus1 -eq $points.Count) ($points.Count.ToString()) $pStatus1.ToString()
Check 'G5-12' '代卖点经纬度非空（从 trade_point 复制）' ($pGeo -eq $points.Count) ($points.Count.ToString()) $pGeo.ToString()
foreach ($line in $points) { Warn 'G5-13' ('种子点位 ' + (([string]$line) -split '\|')[0]) (([string]$line) -split '\|')[5] }

# ---- 5.3 优惠券 3 张
$coupons = @(SqlRows "SELECT CONCAT_WS('|', id, type, threshold_amount, COALESCE(discount_amount,'<NULL>'), COALESCE(discount_rate,'<NULL>'), total_stock, per_user_limit, status, (start_at <= NOW()), (end_at > NOW()), name) FROM platform_coupon ORDER BY id;")
Check 'G5-14' ('优惠券数量 >= 3（实际 ' + $coupons.Count + '）') ($coupons.Count -ge 3) '>=3' $coupons.Count.ToString()
$c50 = 0; $c100 = 0; $cRate = 0; $cWindow = 0; $cLimit = 0; $cStock = 0
foreach ($line in $coupons) {
  $f = ([string]$line) -split '\|'
  if ([string]$f[1] -eq '1' -and [decimal]$f[2] -eq 50 -and [decimal]$f[3] -eq 8) { $c50++ }
  if ([string]$f[1] -eq '1' -and [decimal]$f[2] -eq 100 -and [decimal]$f[3] -eq 20) { $c100++ }
  if ([string]$f[1] -eq '2') { $cRate++ }
  if ([string]$f[8] -eq '1' -and [string]$f[9] -eq '1') { $cWindow++ }
  if ([int]$f[6] -ge 1) { $cLimit++ }
  if ([int]$f[5] -gt 0) { $cStock++ }
}
Check 'G5-15' '存在「满50减8」券（type=1 threshold=50 discount=8）' ($c50 -ge 1) '>=1' $c50.ToString()
Check 'G5-16' '存在「满100减20」券' ($c100 -ge 1) '>=1' $c100.ToString()
Check 'G5-17' '存在折扣券（type=2，满30打9折）' ($cRate -ge 1) '>=1' $cRate.ToString()
Check 'G5-18' '券可领取时间窗有效（start_at<=NOW()<end_at）' ($cWindow -eq $coupons.Count) ($coupons.Count.ToString()) $cWindow.ToString()
Check 'G5-19' '券 per_user_limit >= 1' ($cLimit -eq $coupons.Count) ($coupons.Count.ToString()) $cLimit.ToString()
Check 'G5-20' '券 total_stock > 0' ($cStock -eq $coupons.Count) ($coupons.Count.ToString()) $cStock.ToString()
foreach ($line in $coupons) {
  $f = ([string]$line) -split '\|'
  Warn 'G5-21' ('种子券 id=' + $f[0] + ' ' + $f[10]) ('type=' + $f[1] + ' threshold=' + $f[2] + ' discountAmount=' + $f[3] + ' discountRate=' + $f[4] + ' stock=' + $f[5] + ' perUser=' + $f[6])
}

# ---- 5.4 秒杀场次 1 个 + 2 个场次商品
$flash = @(SqlRows "SELECT CONCAT_WS('|', id, campus_id, status, (start_at <= NOW()), (end_at > NOW()), COALESCE(title,'<NULL>')) FROM platform_flash_sale ORDER BY id;")
Check 'G5-22' ('秒杀场次 >= 1（实际 ' + $flash.Count + '）') ($flash.Count -ge 1) '>=1' $flash.Count.ToString()
$activeFlash = $null
foreach ($line in $flash) {
  $f = ([string]$line) -split '\|'
  if ([string]$f[2] -eq '2' -and [string]$f[3] -eq '1' -and [string]$f[4] -eq '1') { $activeFlash = $f; break }
}
Check 'G5-23' '存在 status=2 且当前在时间窗内的秒杀场次' ($null -ne $activeFlash) '有进行中场次' '无'
if ($null -ne $activeFlash) {
  $fid = [int]$activeFlash[0]
  $fItems = @(SqlRows ("SELECT CONCAT_WS('|', id, item_id, seckill_price, seckill_stock, sold_count, per_user_limit) FROM platform_flash_sale_item WHERE flash_sale_id=" + $fid + " ORDER BY id;"))
  Check 'G5-24' ('场次 ' + $fid + ' 商品 >= 2（实际 ' + $fItems.Count + '）') ($fItems.Count -ge 2) '>=2' $fItems.Count.ToString()
  $badStock = 0; $badSold = 0; $badSeckillPrice = 0
  foreach ($line in $fItems) {
    $f2 = ([string]$line) -split '\|'
    if ([int]$f2[3] -le 0) { $badStock++ }
    if ([int]$f2[4] -gt [int]$f2[3]) { $badSold++ }
    if ([decimal]$f2[2] -le 0) { $badSeckillPrice++ }
  }
  Check 'G5-25' '场次商品 seckill_stock > 0' ($badStock -eq 0) '0 条异常' ($badStock.ToString() + ' 条异常')
  Check 'G5-26' '场次商品 sold_count <= seckill_stock' ($badSold -eq 0) '0 条异常' ($badSold.ToString() + ' 条异常')
  Check 'G5-27' '场次商品 seckill_price > 0' ($badSeckillPrice -eq 0) '0 条异常' ($badSeckillPrice.ToString() + ' 条异常')
  Warn 'G5-28' '场次商品 6 折价' ('seckill_stock=' + ((($fItems | ForEach-Object { (([string]$_ -split '\|')[3]) }) -join ',')))
}
$recCount = SqlCount 'SELECT COUNT(*) FROM platform_seckill_record;'
Warn 'G5-29' 'platform_seckill_record 行数（种子为 0，跑过抢购后 >0）' $recCount.ToString()

# ---- 5.5 两套库存平面一致性（v1.1 关键：Redis 秒杀平面 vs item.stock DB 平面）
#  背景：迁移 5.3 从孤品个人商品克隆时 stock=1，而 5.5 场次商品 seckill_stock=5；
#  v1.1 下单只扣 item.stock，若 stock<seckill_stock，则"seller 第 2 人起 30004"（QA 报 P1，已修）。
#  迁移第 6 节幂等 UPDATE 修正为 stock=GREATEST(stock,seckill_stock)、is_unique=0。
if ($null -ne $activeFlash) {
  $fid = [int]$activeFlash[0]
  $crossRows = @(SqlRaw ("SELECT CONCAT_WS('|', fsi.item_id, fsi.seckill_stock, i.stock, i.is_unique, i.status, (SELECT COUNT(*) FROM platform_seckill_record r WHERE r.item_id = fsi.item_id)) FROM platform_flash_sale_item fsi JOIN item i ON i.id = fsi.item_id WHERE fsi.flash_sale_id=" + $fid + " ORDER BY fsi.item_id;"))
  foreach ($line in $crossRows) {
    $c = ([string]$line) -split '\|'
    $it = $c[0]; $seckillStock = [int]$c[1]; $dbStock = [int]$c[2]; $uniq = [string]$c[3]; $st = [string]$c[4]; $sold = [int]$c[5]
    Check ('G5-30-' + $it) ('秒杀商品 ' + $it + ' DB 平面自洽：item.stock(' + $dbStock + ') + 已抢记录(' + $sold + ') == seckill_stock(' + $seckillStock + ')') (($dbStock + $sold) -eq $seckillStock) $seckillStock ($dbStock + $sold)
    Check ('G5-31-' + $it) ('秒杀商品 ' + $it + ' is_unique=0（多份库存；迁移第 6 节修正）') ($uniq -eq '0') '0' $uniq
    Check ('G5-32-' + $it) ('秒杀商品 ' + $it + ' status=(stock>0?2:4) 且 != 3（v1.1 不锁库）（实际 status=' + $st + ', stock=' + $dbStock + '）') ([string]$st -ne '3' -and [string]$st -eq $(if ($dbStock -gt 0) { '2' } else { '4' })) '2 或 4' $st
    Warn ('G5-33-' + $it) ('秒杀两套库存：item.stock(DB)=' + $dbStock + '，Redis 预热应为 seckill_stock-已抢=' + ($seckillStock - $sold))
  }
  $soloRows = @(SqlRaw "SELECT CONCAT_WS('|', id, stock, is_unique, status) FROM item WHERE seller_type=2 AND NOT EXISTS (SELECT 1 FROM platform_flash_sale_item fsi WHERE fsi.item_id = item.id) ORDER BY id;")
  foreach ($line in $soloRows) {
    $s = ([string]$line) -split '\|'
    $sid = $s[0]; $sStock = [int]$s[1]; $sUniq = [string]$s[2]; $sStatus = [string]$s[3]
    Check ('G5-34-' + $sid) ('直购孤品 ' + $sid + ' is_unique=1') ($sUniq -eq '1') '1' $sUniq
    Check ('G5-35-' + $sid) ('直购孤品 ' + $sid + ' stock<=1（实际 ' + $sStock + '）') ($sStock -le 1) '<=1' $sStock
    Check ('G5-36-' + $sid) ('直购孤品 ' + $sid + ' status∈{2,4} 且 (status=2 => stock=1)（实际 status=' + $sStatus + ', stock=' + $sStock + '）') (($sStatus -eq '2' -or $sStatus -eq '4') -and ([string]$sStatus -ne '2' -or $sStock -eq 1)) '2/4 且 2=>1' ($sStatus + '/' + $sStock)
  }
}
} catch {
  Check 'G5-0' '种子数据断言（迁移未执行 / 表或列缺失时整组失败）' $false '平台表与 item.seller_type 列存在' ('查询失败: ' + $_.Exception.Message)
}

# ================================================================ G6 唯一键（幂等/防重复兜底）
Head 'G6 唯一键（DB 兜底，设计 2.2）'
function Get-Indexes([string]$table) {
  $rows = @(SqlRaw ("SELECT CONCAT_WS('|', INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX), NON_UNIQUE) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + $table + "' GROUP BY INDEX_NAME, NON_UNIQUE;"))
  $out = @()
  foreach ($r in $rows) {
    $f = ([string]$r) -split '\|'
    $out += [pscustomobject]@{ name=$f[0]; cols=$f[1]; nonUnique=$f[2] }
  }
  return $out
}
function CheckUnique([string]$Id, [string]$table, [string]$expectCols) {
  if ($gotTables -notcontains $table) { Skip $Id ('唯一键 ' + $table) '表不存在'; return }
  $idx = Get-Indexes $table
  $hit = @($idx | Where-Object { $_.cols -eq $expectCols -and [string]$_.nonUnique -eq '0' })
  # 注意：PowerShell 5.1 里 if 是语句不是表达式，必须包在 $( ) 里当值用
  $detail = '无索引'
  if ($idx.Count -gt 0) {
    $detail = (($idx | ForEach-Object { $_.name + '(' + $_.cols + ') unique=' + (-not [bool][int]$_.nonUnique) }) -join '; ')
  }
  Check $Id ('唯一键 ' + $table + '(' + $expectCols + ')') ($hit.Count -ge 1) 'UNIQUE' $detail
}
CheckUnique 'G6-1' 'platform_coupon_user'  'coupon_id,user_id'
CheckUnique 'G6-2' 'platform_flash_sale_item' 'flash_sale_id,item_id'
CheckUnique 'G6-3' 'platform_seckill_record' 'item_id,user_id'

# ================================================================ G7 与 Redis 对账口径
Head 'G7 DB 口径「剩余库存」（给 verify-platform-redis.ps1 对账）'
$couponStock = @{}
try {
foreach ($line in $coupons) {
  $f = ([string]$line) -split '\|'
  $cid = [int]$f[0]
  $total = [int]$f[5]
  $claimed = SqlCount ('SELECT COUNT(*) FROM platform_coupon_user WHERE coupon_id=' + $cid + ';')
  $dbLeft = $total - $claimed
  $couponStock[$cid] = [pscustomobject]@{ id=$cid; total=$total; claimed=$claimed; dbLeft=$dbLeft }
  Check ('G7-1') ('券 ' + $cid + ' DB 口径剩余 = total_stock(' + $total + ') - 已领(' + $claimed + ') = ' + $dbLeft + ' >= 0') ($dbLeft -ge 0) '>=0' $dbLeft.ToString()
}
if ($null -ne $activeFlash) {
  $fid = [int]$activeFlash[0]
  $rows = @(SqlRaw ("SELECT CONCAT_WS('|', fsi.item_id, fsi.seckill_stock, COUNT(r.id)) FROM platform_flash_sale_item fsi LEFT JOIN platform_seckill_record r ON r.item_id = fsi.item_id WHERE fsi.flash_sale_id=" + $fid + " GROUP BY fsi.item_id, fsi.seckill_stock ORDER BY fsi.item_id;"))
  foreach ($line in $rows) {
    $f = ([string]$line) -split '\|'
    $itemId = [int]$f[0]; $stock = [int]$f[1]; $sold = [int]$f[2]
    $dbLeft = $stock - $sold
    Check ('G7-2') ('秒杀商品 ' + $itemId + ' DB 口径剩余 = seckill_stock(' + $stock + ') - 已抢(' + $sold + ') = ' + $dbLeft) ($dbLeft -ge 0) '>=0' $dbLeft.ToString()
    $dbItemStock = Sql1 ('SELECT stock FROM item WHERE id=' + $itemId + ';')
    Warn ('G7-3-' + $itemId) '秒杀两平面对账（Redis 预热平面 != item.stock DB 平面）' ('Redis 预热应剩 ' + $dbLeft + '；DB item.stock=' + $dbItemStock + '（v1.1 下单扣的是 item.stock，两者不划等号）')
  }
}
} catch {
  Check 'G7-0' 'DB 口径剩余库存对账' $false '平台表可查询' ('查询失败: ' + $_.Exception.Message)
}

# ================================================================ 快照 / 幂等对比
$snapshotData = [ordered]@{
  at = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
  tables = $tableCounts
  platformItems = $platformItems.Count
  points = $points.Count
  coupons = $coupons.Count
  flashSales = $flash.Count
  couponUsers = $tableCounts['platform_coupon_user']
  seckillRecords = $tableCounts['platform_seckill_record']
  user1000 = $(if ($acc) { 1 } else { 0 })
}
if ($snapFull) {
  $snapshotData | ConvertTo-Json -Depth 6 | Set-Content -Path $snapFull -Encoding UTF8
  Write-Host ''
  Write-Host ('快照已写入: ' + $snapFull) -ForegroundColor DarkGray
}
if ($cmpFull) {
  Head 'G8 迁移幂等（与快照对比计数）'
  if (-not (Test-Path $cmpFull)) {
    Check 'G8-1' ('快照文件存在: ' + $cmpFull) $false '存在' '不存在（先跑 -Snapshot 生成）'
  } else {
    $before = Get-Content -Raw -Encoding UTF8 $cmpFull | ConvertFrom-Json
    $diff = 0
    $keys = @('platformItems','points','coupons','flashSales','couponUsers','seckillRecords','user1000')
    foreach ($k in $keys) {
      $b = $before.$k; $n = $snapshotData[$k]
      if ($null -eq $b) { continue }
      if ([string]$b -ne [string]$n) { $diff++; Check ('G8-2-' + $k) ('计数无漂移 ' + $k) $false $b $n }
      else { Check ('G8-2-' + $k) ('计数无漂移 ' + $k + ' = ' + $n) $true $b $n }
    }
    Check 'G8-3' '迁移二次执行后种子计数无漂移（幂等）' ($diff -eq 0) '无漂移' ($diff.ToString() + ' 项漂移')
  }
}

# ================================================================ 汇总 + 输出
$summary = [ordered]@{
  at = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
  target = ($DbUser + '@' + $DbHost + ':' + $DbPort + '/' + $DbName)
  pass = $script:pass; fail = $script:fail; skip = $script:skip; warn = $script:warn
  snapshot = $snapshotData
  results = @($script:results)
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $outFull -Encoding UTF8

Write-Host ''
Write-Host '------------------------------------------------------------' -ForegroundColor White
if ($script:fail -eq 0) {
  Write-Host ('DB 检查全部通过：' + $script:pass + ' 项断言（WARN ' + $script:warn + '，SKIP ' + $script:skip + '）') -ForegroundColor Green
} else {
  Write-Host ('DB 检查：通过 ' + $script:pass + ' 项，失败 ' + $script:fail + ' 项，SKIP ' + $script:skip + '，WARN ' + $script:warn) -ForegroundColor Red
}
Write-Host ('结果 JSON: ' + $outFull) -ForegroundColor DarkGray
Write-Host '------------------------------------------------------------' -ForegroundColor White
exit ([Math]::Min($script:fail, 250))
