# 验证「同一买家对同一商品只能有一张进行中的面交单」
#
# 背景：以前没有这道校验，买家可以反复点「约面交」刷出一堆重复单 ——
# 卖家那边看到 N 条一模一样的申请，接受哪条都别扭。
# 前端按钮置灰只是体验，**守规矩要靠后端**（直接调接口也得拦住）。
#
# 断言：
#   1. 首次约面交成功
#   2. 再约一次被拒绝（40003），且库里只有 1 张进行中的单
#   3. 卖家拒绝后可以重新约
#   4. 成交之后不能再约（商品已锁定/已售出）
#   5. 购物车合并结算也受同一道校验约束
#   6. 买家提交意向时**不决定**面交点与时间（传了也不该生效 / 前端不再传）
#
# 隔离：测试实例 8081 + Redis 1 号库
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\verify-book-once.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$base = 'http://127.0.0.1:8081/api/v1'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'

[System.IO.File]::ReadAllLines((Join-Path $root '.env'), [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
  $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
  if (-not $k -or -not $v) { return }
  Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
}
$env:MYSQL_PWD = $env:MYSQL_PASSWORD
$env:SERVER_PORT = '8081'; $env:REDIS_DATABASE = '1'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = 'C:\Program Files\Java\jdk-21\bin;' + $env:Path
$mvn = 'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'

function Sql1([string]$q) { ((& $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou -N -B -e $q 2>$null) -join '') }
function SqlFile([string]$sql) {
  $f = Join-Path $root '.build\_tmp-book.sql'
  [System.IO.File]::WriteAllText($f, $sql, (New-Object System.Text.UTF8Encoding($false)))
  & $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou --default-character-set=utf8mb4 -e "source $f" 2>$null | Out-Null
}
function Req($method, $url, $obj, $token) {
  $h = @{}
  if ($token) { $h['Authorization'] = 'Bearer ' + $token }
  $a = @{ Uri = $url; Method = $method; Headers = $h; ContentType = 'application/json; charset=utf-8' }
  if ($obj) { $a['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 6 -Compress)) }
  try { Invoke-RestMethod @a } catch { @{ code = -1; message = $_.Exception.Message } }
}

$pass = 0; $fail = 0
function Check($desc, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ('  [PASS] ' + $desc) -ForegroundColor Green }
  else { $script:fail++; Write-Host ('  [FAIL] ' + $desc + '  ' + $detail) -ForegroundColor Red }
}

$proc = Start-Process -FilePath $mvn -ArgumentList '-B','-o','spring-boot:run','-Dspring-boot.run.profiles=dev,local' `
  -WorkingDirectory (Join-Path $root 'ershou-server') -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $root '.build\book.log') `
  -RedirectStandardError (Join-Path $root '.build\book.err.log')
for ($i = 1; $i -le 90; $i++) {
  Start-Sleep -Seconds 2
  try { $p = Invoke-RestMethod -Uri "$base/categories" -TimeoutSec 5; if ($null -ne $p.code) { break } } catch { }
}
Write-Host '测试实例就绪' -ForegroundColor Green

$itemId = 0
try {
  $ps_ = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)   # 卖家
  $pb = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)   # 买家
  Req POST "$base/auth/sms-code" @{ phone = $ps_ } $null | Out-Null
  $S = (Req POST "$base/auth/login" @{ phone = $ps_; code = '123456'; schoolId = 1; campusId = 2 } $null).data
  Req POST "$base/auth/sms-code" @{ phone = $pb } $null | Out-Null
  $B = (Req POST "$base/auth/login" @{ phone = $pb; code = '123456'; schoolId = 1; campusId = 2 } $null).data
  $st = $S.token; $bt = $B.token

  $itemId = 770000 + (Get-Random -Minimum 1 -Maximum 9999)
  SqlFile ("INSERT INTO item (id, seller_id, category_id, campus_id, title, description, price, " +
           "condition_level, stock, is_unique, negotiable, status, images, created_at) VALUES " +
           "($itemId, $($S.user.id), 1, 2, '防重测试商品', 'x', 12.00, 1, 1, 1, 1, 2, " +
           "JSON_ARRAY('/static/t.jpg'), NOW());")
  Check '准备：测试商品已就绪' ((Sql1 "SELECT COUNT(*) FROM item WHERE id=$itemId;") -eq '1') '入库失败'

  $activeCount = { Sql1 "SELECT COUNT(*) FROM meetup_order WHERE buyer_id=$($B.user.id) AND item_id=$itemId AND status IN (1,2);" }

  Write-Host ''
  Write-Host '=== 1. 首次约面交 + 重复约被拦 ===' -ForegroundColor Cyan
  $r1 = Req POST "$base/meetup-orders" @{ itemId = $itemId; quantity = 1 } $bt
  $orderId = @($r1.data.orders)[0].orderId
  Check '首次约面交成功' ($r1.code -eq 0 -and $orderId) ($r1 | ConvertTo-Json -Compress)
  $r2 = Req POST "$base/meetup-orders" @{ itemId = $itemId; quantity = 1 } $bt
  Check '再约一次被拒绝' ($r2.code -ne 0) ('code=' + $r2.code)
  Check '拒绝文案说清了「已经约过」' ($r2.message -match '已经约过') ('message=' + $r2.message)
  Check '库里只有 1 张进行中的单' ((& $activeCount) -eq '1') ('实际 ' + (& $activeCount))
  $r3 = Req POST "$base/meetup-orders" @{ itemId = $itemId; quantity = 1 } $bt
  $r4 = Req POST "$base/meetup-orders" @{ itemId = $itemId; quantity = 1 } $bt
  Check '连点 4 次仍然只有 1 张单' ((& $activeCount) -eq '1') ('实际 ' + (& $activeCount))

  Write-Host ''
  Write-Host '=== 2. 买家提交意向不决定面交点与时间 ===' -ForegroundColor Cyan
  $slotS = '2026-10-05 09:00:00'
  $slotE = '2026-10-05 10:00:00'
  # 卖家拒绝掉，让买家可以再约一次来验"传了也不该生效"
  Req POST "$base/meetup-orders/$orderId/reject" @{ reason = '测试' } $st | Out-Null
  Check '卖家拒绝后可重新约（进行中降为 0）' ((& $activeCount) -eq '0') ('实际 ' + (& $activeCount))
  # 注意：这里刻意传 pointId/时间，验证服务端**不再把它们写进单子**（由卖家定）
  $r5 = Req POST "$base/meetup-orders" @{ itemId = $itemId; quantity = 1; pointId = 2; timeSlotStart = $slotS; timeSlotEnd = $slotE } $bt
  $newOrderId = @($r5.data.orders)[0].orderId
  Check '再次约面交成功' ($r5.code -eq 0 -and $newOrderId) ($r5 | ConvertTo-Json -Compress)
  # 买家**传了也白传**：服务端忽略，面交点取商品自己关联的点位、时间窗留空
  # （商品没设点位时是 '-'）
  $sent = (Sql1 "SELECT CONCAT(IFNULL(point_id,'-'),'|',IFNULL(DATE_FORMAT(time_slot_start,'%Y-%m-%d %H:%i'),'-')) FROM meetup_order WHERE id=$newOrderId;")
  Check '买家传的面交点被忽略（不采纳 2）' ($sent -notlike '2|*') ('实际 ' + $sent)
  Check '买家传的时间窗被忽略（留空 = 待协商）' ($sent -like '*-') ('实际 ' + $sent)
  Check '面交点改成用商品自己关联的点位' `
    ($sent -like ((Sql1 "SELECT CONCAT(IFNULL(prefer_point_id,'-'),'|') FROM item WHERE id=$itemId;")) + '*') `
    ('实际 ' + $sent + ' 商品点位=' + (Sql1 "SELECT IFNULL(prefer_point_id,'-') FROM item WHERE id=$itemId;"))

  Write-Host ''
  Write-Host '=== 3. 卖家接受后仍不能重复约 ===' -ForegroundColor Cyan
  $acc = Req POST "$base/meetup-orders/$newOrderId/accept" $null $st
  Check '卖家接受成功' ($acc.code -eq 0) ($acc | ConvertTo-Json -Compress)
  Check '接受后商品被锁定（status=3）' ((Sql1 "SELECT status FROM item WHERE id=$itemId;") -eq '3') ('实际 ' + (Sql1 "SELECT status FROM item WHERE id=$itemId;"))
  $r6 = Req POST "$base/meetup-orders" @{ itemId = $itemId; quantity = 1 } $bt
  Check '状态 2（待面交）时重复约也被拦' ($r6.code -ne 0) ('code=' + $r6.code)
  Check '仍然只有 1 张进行中的单' ((& $activeCount) -eq '1') ('实际 ' + (& $activeCount))

  Write-Host ''
  Write-Host '=== 4. 购物车合并结算也受同一道校验 ===' -ForegroundColor Cyan
  # 换一件新商品，放进购物车再结算 → 会走 doCreate，同样应被防重覆盖
  $item2 = 780000 + (Get-Random -Minimum 1 -Maximum 9999)
  SqlFile ("INSERT INTO item (id, seller_id, category_id, campus_id, title, description, price, " +
           "condition_level, stock, is_unique, negotiable, status, images, created_at) VALUES " +
           "($item2, $($S.user.id), 1, 2, '防重测试商品2', 'x', 8.00, 1, 1, 1, 1, 2, " +
           "JSON_ARRAY('/static/t.jpg'), NOW());")
  # 买家先直接约一次
  Req POST "$base/meetup-orders" @{ itemId = $item2; quantity = 1 } $bt | Out-Null
  # 再加进购物车结算：应当被防重拦下（不能因为走购物车就绕过去）
  Req POST "$base/cart" @{ itemId = $item2; quantity = 1 } $bt | Out-Null
  $cid = Sql1 "SELECT id FROM cart_item WHERE user_id=$($B.user.id) AND item_id=$item2;"
  $co = Req POST "$base/cart/checkout" @{ cartIds = @([long]$cid) } $bt
  Check '购物车结算绕不过防重' ($co.code -ne 0) ('code=' + $co.code)
  Check '第 2 件商品仍只有 1 张进行中的单' `
    ((Sql1 "SELECT COUNT(*) FROM meetup_order WHERE buyer_id=$($B.user.id) AND item_id=$item2 AND status IN (1,2);") -eq '1') '多了'
  Sql1 "DELETE FROM cart_item WHERE item_id=$item2;" | Out-Null
  Sql1 "DELETE FROM meetup_order WHERE item_id=$item2;" | Out-Null
  Sql1 "DELETE FROM item WHERE id=$item2;" | Out-Null
}
finally {
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  $c = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
  if ($c) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
  Sql1 "DELETE FROM meetup_order WHERE item_title LIKE '防重测试商品%';" | Out-Null
  if ($itemId -gt 0) { Sql1 "DELETE FROM item WHERE id=$itemId;" | Out-Null }
  Sql1 "DELETE FROM item WHERE title LIKE '防重测试商品%';" | Out-Null
  Write-Host '已清理测试数据' -ForegroundColor DarkGray
}

Write-Host ''
if ($fail -eq 0) { Write-Host ('全部通过：' + $pass + ' 项断言') -ForegroundColor Green }
else { Write-Host ('通过 ' + $pass + ' 项，失败 ' + $fail + ' 项') -ForegroundColor Red }
exit $fail
