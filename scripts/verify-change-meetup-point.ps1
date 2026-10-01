# 验证「改面交地点与时间」：PATCH /meetup-orders/{id}/point
#
# 产品规则（2026-10-01）：**只有卖家能改**面交地点与时间 —— 商品在卖家手上，
# 见面的地点与时间由他定；买家想改就在私聊里商量。后端与前端都要守这条。
#
# 断言：
#   1. 买家改 → 被拒绝，且库里没被动过（point_id / reschedule_count 都不变）
#   2. 卖家改 → 成功，point_id 与 reschedule_count 都更新
#   3. 改完给对方发私聊提醒（走接口读明文——库里是加密的）
#   4. 异校区点位被拒绝且回滚
#   5. 不存在的点位 / 未登录 10002 / 第三方不可改
#   6. 状态限制：状态 2 可改；成交后不可改
#
# 隔离：测试实例 8081 + Redis 1 号库
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\verify-change-meetup-point.ps1
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
# 写 SQL 文件再 source：`item.images` 是 **JSON 列**，PowerShell 走 `-e` 传参时
# 会把 JSON 里的双引号吃掉一层，报 "Invalid JSON text ... at position 1"。
# 落成文件就绕开这层引号解析（用 JSON_ARRAY() 生成，也不用自己拼引号）。
function SqlFile([string]$sql) {
  $f = Join-Path $root '.build\_tmp-sql.sql'
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
  -RedirectStandardOutput (Join-Path $root '.build\chgpoint.log') `
  -RedirectStandardError (Join-Path $root '.build\chgpoint.err.log')
for ($i = 1; $i -le 90; $i++) {
  Start-Sleep -Seconds 2
  try { $p = Invoke-RestMethod -Uri "$base/categories" -TimeoutSec 5; if ($null -ne $p.code) { break } } catch { }
}
Write-Host '测试实例就绪' -ForegroundColor Green

$itemId = 0
try {
  # 三个账号：卖家 / 买家 / 无关第三方
  $sellerPhone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
  $buyerPhone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
  $thirdPhone = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
  Req POST "$base/auth/sms-code" @{ phone = $sellerPhone } $null | Out-Null
  $sl = Req POST "$base/auth/login" @{ phone = $sellerPhone; code = '123456'; schoolId = 1; campusId = 2 } $null
  Req POST "$base/auth/sms-code" @{ phone = $buyerPhone } $null | Out-Null
  $bl = Req POST "$base/auth/login" @{ phone = $buyerPhone; code = '123456'; schoolId = 1; campusId = 2 } $null
  Req POST "$base/auth/sms-code" @{ phone = $thirdPhone } $null | Out-Null
  $tl = Req POST "$base/auth/login" @{ phone = $thirdPhone; code = '123456'; schoolId = 1; campusId = 2 } $null
  $st = $sl.data.token; $bt = $bl.data.token; $tt = $tl.data.token

  # 点位：本校区 A/B + 异校区（金鸡岭）
  $ptA = Req POST "$base/trade-points" @{ campusId = 2; name = '改址测试点A'; lng = 110.4188; lat = 25.3133 } $st
  $ptB = Req POST "$base/trade-points" @{ campusId = 2; name = '改址测试点B'; lng = 110.4175; lat = 25.3123 } $st
  $ptJj = Req POST "$base/trade-points" @{ campusId = 1; name = '改址测试点金鸡岭'; lng = 110.3369; lat = 25.2840 } $st
  Check '准备：两个本校区点位 + 一个异校区点位' `
    ($ptA.code -eq 0 -and $ptB.code -eq 0 -and $ptJj.code -eq 0) '建点失败'

  # 商品直接用 SQL 造：POST /items 要求至少 1 张图（要走 MinIO），测试没必要依赖对象存储
  $itemId = 880000 + (Get-Random -Minimum 1 -Maximum 9999)
  SqlFile ("INSERT INTO item (id, seller_id, category_id, campus_id, title, description, price, " +
           "condition_level, stock, is_unique, negotiable, status, images, prefer_point_id, created_at) " +
           "VALUES ($itemId, $($sl.data.user.id), 1, 2, '改址测试商品', 'x', 10.50, " +
           "1, 1, 1, 1, 2, JSON_ARRAY('/static/test.jpg'), $($ptA.data.id), NOW());")
  Check '准备：测试商品已就绪' ((Sql1 "SELECT COUNT(*) FROM item WHERE id=$itemId;") -eq '1') '入库失败'

  # 订单走真实接口。返回结构是 { batchNo, orders: [ { orderId .. } ] }，
  # 且 Invoke-RestMethod 会把单元素数组拆成对象，必须 @() 包一层
  $order = Req POST "$base/meetup-orders" @{ itemId = $itemId; quantity = 1; pointId = $ptA.data.id } $bt
  $orderId = @($order.data.orders)[0].orderId
  Check '准备：买家约面交成功' ($order.code -eq 0 -and $orderId) ($order | ConvertTo-Json -Compress)

  Write-Host ''
  Write-Host '=== 1. 买家改 → 必须被拒绝，且库里不动 ===' -ForegroundColor Cyan
  $b = Req PATCH "$base/meetup-orders/$orderId/point" @{ pointId = $ptB.data.id } $bt
  Check '买家改地点被拒绝' ($b.code -ne 0) ('code=' + $b.code)
  Check '拒绝文案说明只有卖家能改' ($b.message -match '卖家') ('message=' + $b.message)
  Check '库里 point_id 没变' ((Sql1 "SELECT point_id FROM meetup_order WHERE id=$orderId;") -eq "$($ptA.data.id)") ('实际 ' + (Sql1 "SELECT point_id FROM meetup_order WHERE id=$orderId;"))
  Check 'reschedule_count 没变' ((Sql1 "SELECT reschedule_count FROM meetup_order WHERE id=$orderId;") -eq '0') ('实际 ' + (Sql1 "SELECT reschedule_count FROM meetup_order WHERE id=$orderId;"))

  Write-Host ''
  Write-Host '=== 2. 卖家改 → 成功 ===' -ForegroundColor Cyan
  $r1 = Req PATCH "$base/meetup-orders/$orderId/point" @{ pointId = $ptB.data.id } $st
  Check '卖家改地点成功' ($r1.code -eq 0) ($r1 | ConvertTo-Json -Compress)
  Check '返回的 pointName 是新点位' ($r1.data.pointName -eq '改址测试点B') ('实际 ' + $r1.data.pointName)
  Check '库里 point_id 已更新' ((Sql1 "SELECT point_id FROM meetup_order WHERE id=$orderId;") -eq "$($ptB.data.id)") ('实际 ' + (Sql1 "SELECT point_id FROM meetup_order WHERE id=$orderId;"))
  Check 'reschedule_count +1' ((Sql1 "SELECT reschedule_count FROM meetup_order WHERE id=$orderId;") -eq '1') ('实际 ' + (Sql1 "SELECT reschedule_count FROM meetup_order WHERE id=$orderId;"))

  Write-Host ''
  Write-Host '=== 2.5 卖家可以一次同时改地点 + 时间 ===' -ForegroundColor Cyan
  # 移动端卖家入口就是这么调的：选点位后可选时间，一次 PATCH 全改掉
  $slotS = '2026-10-03 12:00:00'
  $slotE = '2026-10-03 13:00:00'
  $rs = Req PATCH "$base/meetup-orders/$orderId/point" @{ pointId = $ptB.data.id; timeSlotStart = $slotS; timeSlotEnd = $slotE } $st
  Check '卖家一次改地点+时间成功' ($rs.code -eq 0) ($rs | ConvertTo-Json -Compress)
  Check '时间窗已写入' ((Sql1 "SELECT DATE_FORMAT(time_slot_start,'%Y-%m-%d %H:%i:%s') FROM meetup_order WHERE id=$orderId;") -eq $slotS) ('实际 ' + (Sql1 "SELECT time_slot_start FROM meetup_order WHERE id=$orderId;"))
  Check '只改地点（不传时间）不会清掉已有时间' `
    ((Req PATCH "$base/meetup-orders/$orderId/point" @{ pointId = $ptA.data.id } $st).code -eq 0) '改失败'
  Check '时间窗仍在' ((Sql1 "SELECT DATE_FORMAT(time_slot_end,'%Y-%m-%d %H:%i:%s') FROM meetup_order WHERE id=$orderId;") -eq $slotE) ('实际 ' + (Sql1 "SELECT time_slot_end FROM meetup_order WHERE id=$orderId;"))
  # 买家即使混传时间也改不动（服务端先判 sellerId）
  $rb2 = Req PATCH "$base/meetup-orders/$orderId/point" @{ pointId = $ptB.data.id; timeSlotStart = $slotS; timeSlotEnd = $slotE } $bt
  Check '买家混传时间也改不动' ($rb2.code -ne 0) ('code=' + $rb2.code)
  Check '买家改完后地点仍是原值' ((Sql1 "SELECT point_id FROM meetup_order WHERE id=$orderId;") -eq "$($ptA.data.id)") ('实际 ' + (Sql1 "SELECT point_id FROM meetup_order WHERE id=$orderId;"))

  Write-Host ''
  Write-Host '=== 3. 改完给对方发私聊提醒 ===' -ForegroundColor Cyan
  # 提醒走 TxHooks.afterCommit（提交后才发）；且**私聊内容是加密存储的**，
  # 必须走接口读明文，SQL LIKE 明文永远查不到
  Start-Sleep -Seconds 2
  $sessions = Req GET "$base/chat/sessions" $null $bt
  $sess = @($sessions.data) | Where-Object { $_.itemId -eq $itemId } | Select-Object -First 1
  if (-not $sess) { $sess = @($sessions.data) | Select-Object -First 1 }
  $msgs = Req GET "$base/chat/sessions/$($sess.id)/messages" $null $bt
  $contents = @(@($msgs.data.records) + @($msgs.data)) | Where-Object { $_ -and $_.content } | ForEach-Object { $_.content }
  $joined = ($contents -join "`n")
  Check ('会话里存在面交地点提醒（读到 ' + @($contents).Count + ' 条）') ($joined -match '面交地点已更新') '没找到'
  Check '提醒里带了新点位名' ($joined -match '改址测试点B') '没带点位名'
  Check '提醒文案说卖家改的' ($joined -match '卖家把面交点改成') '文案不对'

  Write-Host ''
  Write-Host '=== 4. 异校区点位被拒绝且回滚 ===' -ForegroundColor Cyan
  $r3 = Req PATCH "$base/meetup-orders/$orderId/point" @{ pointId = $ptJj.data.id } $st
  Check '异校区点位被拒绝' ($r3.code -ne 0) ('code=' + $r3.code)
  # 2.5 段结束后点位是 A（那一步把地点改回了 A），所以这里比对 A
  Check '点位没有被改掉（回滚）' ((Sql1 "SELECT point_id FROM meetup_order WHERE id=$orderId;") -eq "$($ptA.data.id)") ('实际 ' + (Sql1 "SELECT point_id FROM meetup_order WHERE id=$orderId;"))

  Write-Host ''
  Write-Host '=== 5. 不存在的点位 / 未登录 / 第三方 ===' -ForegroundColor Cyan
  $r4 = Req PATCH "$base/meetup-orders/$orderId/point" @{ pointId = 99999999 } $st
  Check '不存在的点位被拒绝' ($r4.code -ne 0) ('code=' + $r4.code)
  $r5 = Req PATCH "$base/meetup-orders/$orderId/point" @{ pointId = $ptA.data.id } $null
  Check '未登录 → 10002' ($r5.code -eq 10002) ('code=' + $r5.code)
  $r6 = Req PATCH "$base/meetup-orders/$orderId/point" @{ pointId = $ptA.data.id } $tt
  Check '第三方改不了别人的单子' ($r6.code -ne 0) ('code=' + $r6.code)

  Write-Host ''
  Write-Host '=== 6. 状态限制 ===' -ForegroundColor Cyan
  $acc = Req POST "$base/meetup-orders/$orderId/accept" $null $st
  Check '准备：卖家接受（状态→2）' ($acc.code -eq 0) ($acc | ConvertTo-Json -Compress)
  $r7 = Req PATCH "$base/meetup-orders/$orderId/point" @{ pointId = $ptA.data.id } $st
  Check '状态 2（待面交）卖家仍可改' ($r7.code -eq 0) ('code=' + $r7.code)
  $sold = Req POST "$base/meetup-orders/$orderId/confirm" $null $st
  Check '准备：卖家确认成交' ($sold.code -eq 0) ($sold | ConvertTo-Json -Compress)
  $r8 = Req PATCH "$base/meetup-orders/$orderId/point" @{ pointId = $ptB.data.id } $st
  Check '成交后不允许再改' ($r8.code -ne 0) ('code=' + $r8.code)
}
finally {
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  $c = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
  if ($c) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
  if ($itemId -gt 0) {
    Sql1 "DELETE FROM meetup_order WHERE item_id=$itemId;" | Out-Null
    Sql1 "DELETE FROM item WHERE id=$itemId;" | Out-Null
  }
  Sql1 "DELETE FROM trade_point WHERE name LIKE '改址测试点%';" | Out-Null
  Write-Host '已清理测试数据' -ForegroundColor DarkGray
}

Write-Host ''
if ($fail -eq 0) { Write-Host ('全部通过：' + $pass + ' 项断言') -ForegroundColor Green }
else { Write-Host ('通过 ' + $pass + ' 项，失败 ' + $fail + ' 项') -ForegroundColor Red }
exit $fail
