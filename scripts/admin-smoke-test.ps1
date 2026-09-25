
# =============================================================
# 运营后台（审核工作台）端到端冒烟测试
# 覆盖：权限隔离 → 商品机审转人工 → 审核通过/驳回/批量 → 点位审核
#       用户封禁/解封 → 信用分调整（流水）→ 强制下架 → 操作审计日志
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\admin-smoke-test.ps1 [-Base http://127.0.0.1:8080/api/v1]
# 前置：后端已启动；MySQL 可访问；.build/test-item.png 存在
# 注意：本文件必须带 UTF-8 BOM，否则 PS 5.1 会按 GBK 读，中文串全部报语法错
# =============================================================
param([string]$Base = 'http://127.0.0.1:8080/api/v1')

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$MYSQL = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$origin = $Base -replace '/api/v1/?$', ''

$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  Get-Content $envFile | Where-Object { $_ -match '^\s*[A-Z_]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
  }
}
$env:MYSQL_PWD = $env:MYSQL_PASSWORD

$script:fail = 0
function Check($name, $actual, $expected) {
  if ("$actual" -eq "$expected") { Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
  else { Write-Host ("  [FAIL] " + $name + "  期望=" + $expected + "  实际=" + $actual) -ForegroundColor Red; $script:fail++ }
}
function CheckTrue($name, $cond) {
  if ($cond) { Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
  else { Write-Host ("  [FAIL] " + $name) -ForegroundColor Red; $script:fail++ }
}
function DbScalar($sql) {
  $out = & $MYSQL -h 127.0.0.1 -P 3306 -u ershou -N -B -e "use ershou; $sql" 2>$null
  return ($out | Select-Object -First 1)
}
function Sql($sql) {
  & $MYSQL -h 127.0.0.1 -P 3306 -u ershou -e "use ershou; $sql" 2>&1 | Out-Null
}
function Post($url, $obj, $headers) {
  $json = $obj | ConvertTo-Json -Depth 6 -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  if ($headers) { return Invoke-RestMethod -Uri $url -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $bytes }
  return Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json; charset=utf-8' -Body $bytes
}
function Get($url, $headers) {
  if ($headers) { return Invoke-RestMethod -Uri $url -Headers $headers }
  return Invoke-RestMethod -Uri $url
}

Write-Host ''
Write-Host '===== 运营后台（审核工作台）冒烟测试 =====' -ForegroundColor Cyan
Write-Host ("  Base = " + $Base) -ForegroundColor DarkGray

try { Invoke-RestMethod -Uri ($origin + '/actuator/health') -TimeoutSec 5 | Out-Null }
catch { Write-Host '后端未启动，请先运行 .\scripts\dev-server.ps1' -ForegroundColor Red; exit 1 }

# ---------- 0. 登录三个角色 ----------
Write-Host '=== 0. 登录 运营 / 卖家 / 买家 ===' -ForegroundColor Cyan
$adminPhone = '13800000009'; $sellerPhone = '13800000008'; $buyerPhone = '13800000007'
Post "$Base/auth/sms-code" @{phone=$adminPhone} | Out-Null
$adminLogin = Post "$Base/auth/login" @{phone=$adminPhone;code='123456';schoolId=1;campusId=1}
$adminToken = $adminLogin.data.token; $adminId = $adminLogin.data.user.id
Post "$Base/auth/sms-code" @{phone=$sellerPhone} | Out-Null
$sellerLogin = Post "$Base/auth/login" @{phone=$sellerPhone;code='123456';schoolId=1;campusId=1}
$sellerToken = $sellerLogin.data.token; $sellerId = $sellerLogin.data.user.id
Post "$Base/auth/sms-code" @{phone=$buyerPhone} | Out-Null
$buyerLogin = Post "$Base/auth/login" @{phone=$buyerPhone;code='123456';schoolId=1;campusId=1}
$buyerToken = $buyerLogin.data.token; $buyerId = $buyerLogin.data.user.id
Write-Host ("  运营 id=$adminId  卖家 id=$sellerId  买家 id=$buyerId") -ForegroundColor DarkGray

# 授予运营位（role_flags |= 8）
Sql "update sys_user set role_flags = role_flags | 8 where id = $adminId;"
Sql "delete from item where seller_id = $sellerId;"
Sql "delete from trade_point where creator_id in ($sellerId, $buyerId) and source = 2;"
Sql "update sys_user set credit_score = 100, credit_level = 2, status = 1, ban_until = null where id in ($sellerId, $buyerId);"
# 测试账号每次都要发 5 件商品，放开"新号每日发布上限"，否则 Redis 计数器累积后本脚本无法重复执行
Sql "update sys_user set publish_limit = 200 where id = $sellerId;"

$AH = @{ Authorization = "Bearer $adminToken" }
$SH = @{ Authorization = "Bearer $sellerToken" }
$BH = @{ Authorization = "Bearer $buyerToken" }

# ---------- 1. 权限隔离 ----------
Write-Host '=== 1. 权限隔离（普通用户访问运营接口应 10003）===' -ForegroundColor Cyan
Check '卖家访问 /admin/dashboard' (Get "$Base/admin/dashboard" $SH).code 10003
Check '买家访问 /admin/audit/items' (Get "$Base/admin/audit/items" $BH).code 10003
Check '未登录访问 /admin/dashboard' (Get "$Base/admin/dashboard" $null).code 10002
$dash = Get "$Base/admin/dashboard" $AH
Check '运营访问 /admin/dashboard' $dash.code 0
Write-Host ("  待审商品=" + $dash.data.pendingItems + " 待审点位=" + $dash.data.pendingPoints) -ForegroundColor DarkGray

# ---------- 2. 发布可疑商品 → 转人工 ----------
Write-Host '=== 2. 商品机审转人工 ===' -ForegroundColor Cyan
$imgPath = Join-Path $root '.build\test-item.png'
$up = (& curl.exe -s -X POST "$Base/files/upload?biz=item" -H "Authorization: Bearer $sellerToken" -F "file=@$imgPath") | ConvertFrom-Json
$img = $up.data.url
function PublishSuspect($title) {
  return Post "$Base/items" @{ title=$title; description='支持定金，加微信详聊'; categoryId=1; conditionLevel=3; price=9.9; images=@($img); preferPointId=1 } $SH
}
$r1 = PublishSuspect '测试商品A 加微信转账有优惠'
Check '可疑商品A 状态=1 审核中' $r1.data.status 1
$r2 = PublishSuspect '测试商品B 加微信转账有优惠'
$r3 = PublishSuspect '测试商品C 加微信转账有优惠'
$r4 = PublishSuspect '测试商品D 加微信转账有优惠'
$idA = $r1.data.itemId; $idB = $r2.data.itemId; $idC = $r3.data.itemId; $idD = $r4.data.itemId

# 回归用例：商品可以不选面交点（prefer_point_id = NULL）。
# 曾经因为 Map.of() 的 get(null) 抛 NPE，导致整页商品都没选面交点时审核队列直接 90001。
$r5 = Post "$Base/items" @{ title='测试商品E 加微信转账 未选面交点'; description='支持定金'; categoryId=1; conditionLevel=3; price=8.8; images=@($img) } $SH
Check '商品E（无面交点）状态=1' $r5.data.status 1
$queueNull = Get "$Base/admin/audit/items?status=1&keyword=未选面交点" $AH
Check '队列：无可选面交点的商品不报错' $queueNull.code 0
CheckTrue '队列：返回该商品' (($queueNull.data.records | ForEach-Object { $_.id }) -contains $r5.data.itemId)

$queue = Get "$Base/admin/audit/items?status=1&pageSize=50" $AH
CheckTrue '待审队列包含商品A' (($queue.data.records | ForEach-Object { $_.id }) -contains $idA)
Check '队列返回审核原因' (($queue.data.records | Where-Object { $_.id -eq $idA }).auditReason -ne $null) $true

# ---------- 3. 审核通过 / 驳回 / 批量 ----------
Write-Host '=== 3. 审核通过 / 驳回 / 批量通过 ===' -ForegroundColor Cyan
Check '通过商品A' (Post "$Base/admin/audit/items/$idA/approve" @{} $AH).code 0
Check '商品A 状态=2' (DbScalar "select status from item where id=$idA") 2
Check '重复通过应报 60001' (Post "$Base/admin/audit/items/$idA/approve" @{} $AH).code 60001
Check '驳回商品B' (Post "$Base/admin/audit/items/$idB/reject" @{reasonCode='CONTACT';reason='含微信号'} $AH).code 0
Check '商品B 状态=6' (DbScalar "select status from item where id=$idB") 6
Check '商品B reject_code=CONTACT' (DbScalar "select reject_code from item where id=$idB") 'CONTACT'
Check '无原因驳回应报 60003' (Post "$Base/admin/audit/items/$idC/reject" @{} $AH).code 60003
$batch = Post "$Base/admin/audit/items/batch" @{ids=@($idC,$idD);action='approve'} $AH
Check '批量通过' $batch.data.success 2
Check '商品C 状态=2' (DbScalar "select status from item where id=$idC") 2
Check '商品D 状态=2' (DbScalar "select status from item where id=$idD") 2

# ---------- 4. 点位审核 ----------
Write-Host '=== 4. 用户提点 → 运营审核 ===' -ForegroundColor Cyan
Post "$Base/trade-points/suggest" @{campusId=1;name='冒烟测试点位-通过';coordX=111;coordY=222;guideText='快递柜旁'} $SH | Out-Null
Post "$Base/trade-points/suggest" @{campusId=1;name='冒烟测试点位-驳回';coordX=333;coordY=444;guideText='无'} $BH | Out-Null
$p1 = DbScalar "select id from trade_point where creator_id=$sellerId and name='冒烟测试点位-通过' order by id desc limit 1"
$p2 = DbScalar "select id from trade_point where creator_id=$buyerId and name='冒烟测试点位-驳回' order by id desc limit 1"
CheckTrue '点位已入库且待审' (($p1 -ne '') -and ((DbScalar "select audit_status from trade_point where id=$p1") -eq 0))
$pq = Get "$Base/admin/audit/points?auditStatus=0&pageSize=50" $AH
CheckTrue '待审点位队列包含 P1' (($pq.data.records | ForEach-Object { $_.id }) -contains ([int]$p1))
Check '点位通过' (Post "$Base/admin/audit/points/$p1/approve" @{safetyScore=88;guideText='东门快递柜旁'} $AH).code 0
Check 'P1 audit_status=1' (DbScalar "select audit_status from trade_point where id=$p1") 1
Check 'P1 safety_score=88' (DbScalar "select safety_score from trade_point where id=$p1") 88
$tp = Get "$Base/trade-points?campusId=1" $null
CheckTrue '地图点位列表已出现 P1' (($tp.data | ForEach-Object { $_.id }) -contains ([int]$p1))
Check '点位驳回' (Post "$Base/admin/audit/points/$p2/reject" @{reasonCode='DUPLICATE';reason='与门卫室重复'} $AH).code 0
Check 'P2 audit_status=2' (DbScalar "select audit_status from trade_point where id=$p2") 2
# 官方点位 creator_id 为 NULL，列表接口也不能报错
Check '已通过点位队列（含 creator=NULL 的官方点位）' (Get "$Base/admin/audit/points?auditStatus=1" $AH).code 0

# ---------- 5. 用户管理 ----------
Write-Host '=== 5. 用户封禁 / 解封 / 信用分 ===' -ForegroundColor Cyan
Check '运营封禁自己应报 60004' (Post "$Base/admin/users/$adminId/status" @{status=3} $AH).code 60004
Check '封禁买家 3 天' (Post "$Base/admin/users/$buyerId/status" @{status=3;banDays=3;reason='测试封禁'} $AH).code 0
Check '买家 status=3' (DbScalar "select status from sys_user where id=$buyerId") 3
Check '买家 ban_until 已写入' ((DbScalar "select ifnull(ban_until,'no') from sys_user where id=$buyerId") -ne 'no') $true
Check '解封买家' (Post "$Base/admin/users/$buyerId/status" @{status=1;reason='测试解封'} $AH).code 0
Check '买家 ban_until 已清空' (DbScalar "select ifnull(ban_until,'no') from sys_user where id=$buyerId") 'no'

$credit = Post "$Base/admin/users/$sellerId/credit" @{changeValue=5;reason='冒烟测试加分'} $AH
Check '信用分调整返回' $credit.code 0
Check '卖家信用分=105' (DbScalar "select credit_score from sys_user where id=$sellerId") 105
Check 'credit_log 已写入' (DbScalar "select count(*) from credit_log where user_id=$sellerId and reason='冒烟测试加分'") 1

# ---------- 6. 强制下架 ----------
Write-Host '=== 6. 强制下架违规在售商品 ===' -ForegroundColor Cyan
Check '强制下架商品C' (Post "$Base/admin/items/$idC/offline" @{reason='违规商品，强制下架'} $AH).code 0
Check '商品C 状态=8 违规下架' (DbScalar "select status from item where id=$idC") 8
Check '详情页仍可查看该商品' (Get "$Base/items/$idC" $null).data.status 8

# ---------- 7. 操作审计 ----------
Write-Host '=== 7. 操作审计日志 ===' -ForegroundColor Cyan
$logs = Get "$Base/admin/audit-logs?pageSize=50" $AH
Check '审计日志有记录' $logs.code 0
CheckTrue '审计日志包含审核动作' (($logs.data.records | ForEach-Object { $_.action }) -contains 'approve')
CheckTrue '审计日志包含信用调整' (($logs.data.records | ForEach-Object { $_.action }) -contains 'adjust-credit')
Write-Host ("  审计日志 total=" + $logs.data.total) -ForegroundColor DarkGray

# ---------- 8. 清理 ----------
Write-Host '=== 8. 清理测试数据 ===' -ForegroundColor Cyan
Sql "delete from item where seller_id=$sellerId;"
Sql "delete from trade_point where id in ($p1,$p2);"
Sql "delete from credit_log where user_id=$sellerId and reason='冒烟测试加分';"
Sql "update sys_user set credit_score=100, credit_level=2 where id=$sellerId;"
Write-Host '  已清理测试商品/点位/信用流水（保留用户与审计日志）' -ForegroundColor DarkGray

Write-Host ''
if ($script:fail -eq 0) {
  Write-Host '===== 全部通过 =====' -ForegroundColor Green
  exit 0
} else {
  Write-Host ("===== 失败 " + $script:fail + " 项 =====") -ForegroundColor Red
  exit 1
}
