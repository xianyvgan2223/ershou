
# =============================================================
# 验收：商品「变得不可交易」时，挂在上面的面交单必须被一起作废
#
# 背景（修复前）：
#   只有两条路会收掉别人的单 ——「卖家接受某一单」和「锁过期定时任务」。
#   商品自己变得不可交易时一张都不收，其中「已锁库的商品被下架」最狠：
#   锁只在 status=3 时才会被释放，商品变成 5/8 之后永远等不到释放，
#   那张「待面交」单会永久卡住，买家顶栏红点永远不消。
#
# 覆盖 4 个场景：
#   A. 在售商品被运营强制下架 → 两张「待卖家确认」的意向单一起作废
#   B. 已接受（商品锁定）后被运营强制下架 → 那张「待面交」单作废（永久僵尸回归用例）
#   C. 已接受后被卖家自己下架          → 同上
#   D. 正常成交不受影响（单子已完成、商品已售出、红点清零）
#
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\verify-close-orders-on-unavailable.ps1 [-Base http://127.0.0.1:8081/api/v1]
# 前置：后端已启动；MySQL 可访问；.build/test-upload.jpg 存在
# 注意：本文件必须带 UTF-8 BOM，否则 PS 5.1 会按 GBK 读，中文串全部报语法错
# =============================================================
param([string]$Base = 'http://127.0.0.1:8081/api/v1')

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$MYSQL = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$origin = $Base -replace '/api/v1/?$', ''

$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
    $_ = $_.TrimStart([char]0xFEFF).Trim()
    $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
    if (-not $k -or -not $v) { continue }
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
  $out = & $MYSQL --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u ershou -N -B -e "use ershou; $sql" 2>$null
  return ($out | Select-Object -First 1)
}
function Sql($sql) {
  & $MYSQL --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u ershou -e "use ershou; $sql" 2>&1 | Out-Null
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
Write-Host '===== 验收：商品不可交易时收掉挂着的面交单 =====' -ForegroundColor Cyan
Write-Host ("  Base = " + $Base) -ForegroundColor DarkGray

try { Invoke-RestMethod -Uri ($origin + '/actuator/health') -TimeoutSec 5 | Out-Null }
catch { Write-Host '后端未启动，请先运行 .\scripts\dev-server.ps1' -ForegroundColor Red; exit 1 }

# ---------- 0. 登录：卖家 / 买家A / 买家B ----------
Write-Host '=== 0. 登录 卖家 / 买家A / 买家B ===' -ForegroundColor Cyan
$sellerPhone = '13800000008'; $buyerAPhone = '13800000007'; $buyerBPhone = '13800000011'
$adminPhone = '13800000009'
function Login($phone) {
  Post "$Base/auth/sms-code" @{phone=$phone} | Out-Null
  $r = Post "$Base/auth/login" @{phone=$phone;code='123456';schoolId=1;campusId=1}
  if ($r.code -ne 0) { Write-Host ("  登录失败 " + $phone + "：" + $r.code + " " + $r.message) -ForegroundColor Red; exit 1 }
  return $r
}
$sellerLogin = Login $sellerPhone; $sellerId = $sellerLogin.data.user.id
$buyerALogin = Login $buyerAPhone; $buyerAId = $buyerALogin.data.user.id
$buyerBLogin = Login $buyerBPhone; $buyerBId = $buyerBLogin.data.user.id
$adminLogin = Login $adminPhone; $adminId = $adminLogin.data.user.id

$SH = @{ Authorization = "Bearer $($sellerLogin.data.token)" }
$AH = @{ Authorization = "Bearer $($buyerALogin.data.token)" }
$BH = @{ Authorization = "Bearer $($buyerBLogin.data.token)" }
$MH = @{ Authorization = "Bearer $($adminLogin.data.token)" }
Write-Host ("  卖家 id=$sellerId  买家A id=$buyerAId  买家B id=$buyerBId  运营 id=$adminId") -ForegroundColor DarkGray

Sql "update sys_user set role_flags = role_flags | 8 where id = $adminId;"
Sql "update sys_user set publish_limit = 200 where id = $sellerId;"

# 上传一张图，后面所有商品复用
$imgPath = Join-Path $root '.build\test-upload.jpg'
$up = (& curl.exe -s -X POST "$Base/files/upload?biz=item" -H "Authorization: Bearer $($sellerLogin.data.token)" -F "file=@$imgPath") | ConvertFrom-Json
if ($up.code -ne 0) { Write-Host ('图片上传失败：' + $up.message) -ForegroundColor Red; exit 1 }
$img = $up.data.url

# 发一件"可疑"商品（含加微信 → 机审转人工 status=1），再由运营通过 → 在售
function NewOnSaleItem($title) {
  $r = Post "$Base/items" @{ title=$title; description='支持定金，加微信详聊'; categoryId=1; conditionLevel=3; price=9.9; images=@($img) } $SH
  if ($r.code -ne 0) { Write-Host ('发布失败：' + $r.message) -ForegroundColor Red; exit 1 }
  $id = $r.data.itemId
  $ok = Post "$Base/admin/audit/items/$id/approve" @{} $MH
  if ($ok.code -ne 0) { Write-Host ('审核通过失败：' + $ok.message) -ForegroundColor Red; exit 1 }
  return $id
}
function NewIntent($itemId, $headers) {
  $r = Post "$Base/meetup-orders" @{ itemId=$itemId } $headers
  if ($r.code -ne 0) { Write-Host ('约面交失败：' + $r.code + ' ' + $r.message) -ForegroundColor Red; exit 1 }
  # 返回体是 CheckoutResult：orders[] 每项是 { orderId, orderNo, sellerId }
  $oid = $r.data.orders[0].orderId
  if (-not $oid) { Write-Host '约面交没有返回 orderId' -ForegroundColor Red; exit 1 }
  return $oid
}
function TodoOf($headers) { return (Get "$Base/meetup-orders/todo-count" $headers).data }
function OrderStatus($orderId, $headers) { return (Get "$Base/meetup-orders/$orderId" $headers).data.status }
function OrderReason($orderId, $headers) { return (Get "$Base/meetup-orders/$orderId" $headers).data.cancelReason }

$stamp = (Get-Date).ToString('HHmmss')
$created = @()

# ---------- A. 在售商品被运营强制下架 ----------
Write-Host ''
Write-Host '=== A. 在售商品被强制下架 → 两张意向单一起作废 ===' -ForegroundColor Cyan
$itemA = NewOnSaleItem "验收A-强制下架-在售-$stamp 加微信"
$created += $itemA
$oA1 = NewIntent $itemA $AH
$oA2 = NewIntent $itemA $BH
Check '买家A 意向单 status=1' (OrderStatus $oA1 $AH) 1
Check '买家B 意向单 status=1' (OrderStatus $oA2 $BH) 1
Check '卖家待办=2（两张待我确认）' (TodoOf $SH).asSeller 2
Check '强制下架商品A' (Post "$Base/admin/items/$itemA/offline" @{reason='验收：违规处置'} $MH).code 0
Check '商品A status=8 违规下架' (DbScalar "select status from item where id=$itemA") 8
Check '买家A 的单被自动作废(5)' (OrderStatus $oA1 $AH) 5
Check '买家B 的单被自动作废(5)' (OrderStatus $oA2 $BH) 5
Check '卖家待办清零' (TodoOf $SH).asSeller 0
Check '买家A 待办清零' (TodoOf $AH).asBuyer 0
Check '买家B 待办清零' (TodoOf $BH).asBuyer 0
Check '作废原因写进 cancel_reason' (DbScalar "select cancel_reason from meetup_order where id=$oA1") '商品已被平台下架，本次约面交自动作废'
# 原因必须随订单一起下发，否则买家在「我的面交」只看得到一个光秃秃的「已失效」
Check '订单详情下发 cancelReason' (OrderReason $oA1 $AH) '商品已被平台下架，本次约面交自动作废'
Check '订单列表也带 cancelReason' (((Get "$Base/meetup-orders?role=buyer&pageSize=50" $AH).data.records | Where-Object { $_.id -eq $oA1 }).cancelReason) '商品已被平台下架，本次约面交自动作废'

# ---------- B. 已接受（商品锁定）后被强制下架：永久僵尸回归 ----------
Write-Host ''
Write-Host '=== B. 已锁定后被强制下架 → 待面交单作废（原有永久僵尸回归）===' -ForegroundColor Cyan
$itemB = NewOnSaleItem "验收B-锁定后被强制下架-$stamp 加微信"
$created += $itemB
$oB1 = NewIntent $itemB $AH
$oB2 = NewIntent $itemB $BH
Check '卖家接受 A 的单' (Post "$Base/meetup-orders/$oB1/accept" @{} $SH).code 0
Check '商品B 已被锁定 status=3' (DbScalar "select status from item where id=$itemB") 3
Check '买家A 的单 status=2 待面交' (OrderStatus $oB1 $AH) 2
Check '买家B 的单被接受动作顺带作废(5)' (OrderStatus $oB2 $BH) 5
Check '买家A 待办=1（待我点我收到了）' (TodoOf $AH).asBuyer 1
Check '强制下架已锁定的商品B' (Post "$Base/admin/items/$itemB/offline" @{reason='验收：锁定后违规'} $MH).code 0
Check '商品B status=8' (DbScalar "select status from item where id=$itemB") 8
Check '买家A 的待面交单被作废(5)' (OrderStatus $oB1 $AH) 5
Check '买家A 待办清零（修复前会永久挂 1）' (TodoOf $AH).asBuyer 0

# ---------- C. 已接受后被卖家自己下架 ----------
Write-Host ''
Write-Host '=== C. 已锁定后被卖家自己下架 → 待面交单作废 ===' -ForegroundColor Cyan
$itemC = NewOnSaleItem "验收C-锁定后卖家下架-$stamp 加微信"
$created += $itemC
$oC1 = NewIntent $itemC $AH
Check '卖家接受 A 的单' (Post "$Base/meetup-orders/$oC1/accept" @{} $SH).code 0
Check '商品C status=3' (DbScalar "select status from item where id=$itemC") 3
Check '卖家自己下架商品C' (Post "$Base/items/$itemC/offline" @{} $SH).code 0
Check '商品C status=5 已下架' (DbScalar "select status from item where id=$itemC") 5
Check '买家A 的待面交单被作废(5)' (OrderStatus $oC1 $AH) 5
Check '卖家下架的原因文案正确' (OrderReason $oC1 $AH) '商品已被卖家下架，本次约面交自动作废'
Check '买家A 待办清零' (TodoOf $AH).asBuyer 0
Check '卖家待办清零' (TodoOf $SH).asSeller 0

# ---------- D. 正常成交不受影响 ----------
Write-Host ''
Write-Host '=== D. 正常成交链路不受影响 ===' -ForegroundColor Cyan
$itemD = NewOnSaleItem "验收D-正常成交-$stamp 加微信"
$created += $itemD
$oD = NewIntent $itemD $AH
Check '卖家接受' (Post "$Base/meetup-orders/$oD/accept" @{} $SH).code 0
Check '买家点「我收到了」' (Post "$Base/meetup-orders/$oD/confirm" @{} $AH).code 0
$finish = Post "$Base/meetup-orders/$oD/confirm" @{} $SH
Check '卖家确认成交' $finish.code 0
Check '订单 status=3 已完成' (OrderStatus $oD $AH) 3
Check '商品D status=4 已售出' (DbScalar "select status from item where id=$itemD") 4
Check '成交后双方待办清零' (TodoOf $AH).asBuyer 0
Check '卖家待办清零' (TodoOf $SH).asSeller 0

# ---------- 清理 ----------
Write-Host ''
Write-Host '=== 清理测试数据 ===' -ForegroundColor Cyan
foreach ($id in $created) {
  Sql "delete from meetup_order where item_id=$id;"
  Sql "delete from item where id=$id;"
}
Write-Host ("  已删除 " + $created.Count + " 件验收商品及其面交单") -ForegroundColor DarkGray

Write-Host ''
if ($script:fail -eq 0) {
  Write-Host '===== 全部通过 =====' -ForegroundColor Green
  exit 0
} else {
  Write-Host ("===== 失败 " + $script:fail + " 项 =====") -ForegroundColor Red
  exit 1
}
