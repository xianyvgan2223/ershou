# 验证购物车：/api/v1/cart
#
# 关注点：
#   1. 加购 / 分组列表 / 改数量 / 删除 的基本正确性
#   2. **加购时的商品状态校验** —— 不可面交的商品不该能进购物车
#      （否则前端显示成"可结算"，到结算才失败，用户白跑一趟）
#   3. 不能把自己的商品加进购物车
#   4. 数量不能超过库存（非孤品）
#   5. 结算：正常合并拆单、生成后购物车条目被清掉
#   6. 越权：改/删别人的购物车条目
#
# 隔离：测试实例 8081 + Redis 1 号库
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\verify-cart.ps1
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
  $f = Join-Path $root '.build\_tmp-cart.sql'
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
  -RedirectStandardOutput (Join-Path $root '.build\cart.log') `
  -RedirectStandardError (Join-Path $root '.build\cart.err.log')
for ($i = 1; $i -le 90; $i++) {
  Start-Sleep -Seconds 2
  try { $p = Invoke-RestMethod -Uri "$base/categories" -TimeoutSec 5; if ($null -ne $p.code) { break } } catch { }
}
Write-Host '测试实例就绪' -ForegroundColor Green

$itemIds = @()
try {
  # 三个账号：卖家A / 卖家B / 买家C（两个卖家用来验证"按卖家分组"）
  $pa = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
  $pb = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
  $pc = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
  Req POST "$base/auth/sms-code" @{ phone = $pa } $null | Out-Null
  $A = (Req POST "$base/auth/login" @{ phone = $pa; code = '123456'; schoolId = 1; campusId = 2 } $null).data
  Req POST "$base/auth/sms-code" @{ phone = $pb } $null | Out-Null
  $B = (Req POST "$base/auth/login" @{ phone = $pb; code = '123456'; schoolId = 1; campusId = 2 } $null).data
  Req POST "$base/auth/sms-code" @{ phone = $pc } $null | Out-Null
  $C = (Req POST "$base/auth/login" @{ phone = $pc; code = '123456'; schoolId = 1; campusId = 2 } $null).data
  $ta = $A.token; $tb = $B.token; $tc = $C.token
  $ua = $A.user.id; $ub = $B.user.id; $uc = $C.user.id

  # 造商品：A 的在售、A 的已锁定、B 的在售、B 的已售出、B 的多库存
  function NewItem($sellerId, $title, $status, $stock, $unique, $price) {
    $id = 770000 + (Get-Random -Minimum 1 -Maximum 9999)
    SqlFile ("INSERT INTO item (id, seller_id, category_id, campus_id, title, description, price, " +
             "condition_level, stock, is_unique, negotiable, status, images, created_at) VALUES " +
             "($id, $sellerId, 1, 2, '$title', 'x', $price, 1, $stock, $unique, 1, $status, " +
             "JSON_ARRAY('/static/t.jpg'), NOW());")
    return $id
  }
  $iOkA = NewItem $ua '购物车测试-A在售' 2 1 1 10.00
  $iLockA = NewItem $ua '购物车测试-A已锁定' 3 1 1 20.00
  $iOkB = NewItem $ub '购物车测试-B在售' 2 1 1 30.00
  $iSoldB = NewItem $ub '购物车测试-B已售出' 4 1 1 40.00
  $iStockB = NewItem $ub '购物车测试-B五件库存' 2 5 0 5.00
  $itemIds = @($iOkA, $iLockA, $iOkB, $iSoldB, $iStockB)
  Check '准备：5 个测试商品已就绪' ((Sql1 "SELECT COUNT(*) FROM item WHERE id IN ($($itemIds -join ','));") -eq '5') '入库失败'

  Write-Host ''
  Write-Host '=== 1. 加购 + 分组 ===' -ForegroundColor Cyan
  $r1 = Req POST "$base/cart" @{ itemId = $iOkA; quantity = 1 } $tc
  Check '加购在售商品成功' ($r1.code -eq 0) ($r1 | ConvertTo-Json -Compress)
  Req POST "$base/cart" @{ itemId = $iOkB; quantity = 1 } $tc | Out-Null
  $cart = Req GET "$base/cart" $null $tc
  Check '购物车返回 2 个卖家分组' (@($cart.data).Count -eq 2) ('实际 ' + @($cart.data).Count)
  Check '分组里带卖家名' (@($cart.data)[0].sellerName) '缺卖家名'
  Check '条目带封面/价格/状态文案' (@($cart.data)[0].items[0].title -and @($cart.data)[0].items[0].statusText) '字段缺失'

  Write-Host ''
  Write-Host '=== 2. 不可面交的商品不该能加进购物车 ===' -ForegroundColor Cyan
  $rSold = Req POST "$base/cart" @{ itemId = $iSoldB; quantity = 1 } $tc
  Check '已售出的商品加购被拒绝' ($rSold.code -ne 0) ('code=' + $rSold.code + ' msg=' + $rSold.message)
  $rLock = Req POST "$base/cart" @{ itemId = $iLockA; quantity = 1 } $tc
  Check '已锁定的商品加购被拒绝' ($rLock.code -ne 0) ('code=' + $rLock.code + ' msg=' + $rLock.message)
  $cnt = Sql1 "SELECT COUNT(*) FROM cart_item WHERE user_id=$uc AND item_id IN ($iSoldB,$iLockA);"
  Check '库里没有留下不可面交的条目' ($cnt -eq '0') ('实际 ' + $cnt)

  Write-Host ''
  Write-Host '=== 3. 不能加自己的商品 ===' -ForegroundColor Cyan
  $rSelf = Req POST "$base/cart" @{ itemId = $iOkA; quantity = 1 } $ta
  Check '卖家加自己的商品被拒绝' ($rSelf.code -ne 0) ('code=' + $rSelf.code + ' msg=' + $rSelf.message)

  Write-Host ''
  Write-Host '=== 4. 数量校验 ===' -ForegroundColor Cyan
  $rOver = Req POST "$base/cart" @{ itemId = $iStockB; quantity = 99 } $tc
  Check '数量超过库存被拒绝（库存 5）' ($rOver.code -ne 0) ('code=' + $rOver.code + ' msg=' + $rOver.message)
  $rOk5 = Req POST "$base/cart" @{ itemId = $iStockB; quantity = 5 } $tc
  Check '数量等于库存可以加购' ($rOk5.code -eq 0) ($rOk5 | ConvertTo-Json -Compress)
  # 孤品：数量只能是 1
  $cidOkA = Sql1 "SELECT id FROM cart_item WHERE user_id=$uc AND item_id=$iOkA;"
  $rUpd = Req PATCH "$base/cart/$cidOkA" @{ quantity = 3 } $tc
  Check '孤品改数量会被夹成 1（不报错）' ((Sql1 "SELECT quantity FROM cart_item WHERE id=$cidOkA;") -eq '1') `
    ('code=' + $rUpd.code + ' qty=' + (Sql1 "SELECT quantity FROM cart_item WHERE id=$cidOkA;"))

  Write-Host ''
  Write-Host '=== 5. 越权：改/删别人的条目 ===' -ForegroundColor Cyan
  $rb = Req PATCH "$base/cart/$cidOkA" @{ quantity = 2 } $tb
  Check '别人改不了我的条目' ($rb.code -ne 0) ('code=' + $rb.code)
  $rd = Req DELETE "$base/cart/$cidOkA" $null $tb
  Check '别人删不掉我的条目' ((Sql1 "SELECT COUNT(*) FROM cart_item WHERE id=$cidOkA;") -eq '1') '被删了'
  $noLogin = Req GET "$base/cart" $null $null
  Check '未登录 → 10002' ($noLogin.code -eq 10002) ('code=' + $noLogin.code)

  Write-Host ''
  Write-Host '=== 6. 结算：合并拆单 + 清购物车 ===' -ForegroundColor Cyan
  $cartNow = Req GET "$base/cart" $null $tc
  $allCartIds = @($cartNow.data | ForEach-Object { $_.items } | ForEach-Object { $_.cartId })
  Check ('准备：购物车有 ' + $allCartIds.Count + ' 条') ($allCartIds.Count -ge 3) '条目太少'
  $co = Req POST "$base/cart/checkout" @{ cartIds = $allCartIds } $tc
  Check '结算成功' ($co.code -eq 0) ($co | ConvertTo-Json -Compress)
  # 拆单语义：**每件商品一张单**，同一个卖家的多件也是多张单（只是共享批次号）
  $orderCount = @($co.data.orders).Count
  $distinctSellers = (@($co.data.orders) | ForEach-Object { $_.sellerId } | Sort-Object -Unique).Count
  $validCnt = @($cartNow.data | ForEach-Object { $_.items } | Where-Object { $_.status -eq 2 }).Count
  Check ('每件可面交的商品各拆一张单（可面交 ' + $validCnt + ' 件 → ' + $orderCount + ' 张单）') `
    ($orderCount -eq $validCnt) ('实际 ' + $orderCount)
  Check ('单数不少于卖家数（' + $distinctSellers + ' 个卖家）') ($orderCount -ge $distinctSellers) "卖家 $distinctSellers 单 $orderCount"
  Check '共用同一个批次号' ($co.data.batchNo) '缺批次号'
  Check '结算后购物车被清空' ((Sql1 "SELECT COUNT(*) FROM cart_item WHERE user_id=$uc;") -eq '0') ('剩余 ' + (Sql1 "SELECT COUNT(*) FROM cart_item WHERE user_id=$uc;"))
  # 关键回归：购物车里有"已锁定"的条目时，**可面交的照常结算**，不可面交的逐条回报
  # （以前是一条坏条目把整批拖垮，2 张单变 0 张，用户还不知道是哪件）
  $again = Req GET "$base/cart" $null $tc
  Check '上一步把不可面交的条目也一起结算掉了' ($co.data.skipped -eq $null -or @($co.data.skipped).Count -eq 0) ('skipped=' + (@($co.data.skipped) | ConvertTo-Json -Compress))

  Write-Host ''
  Write-Host '=== 7. 结算失败不能留下半截数据 ===' -ForegroundColor Cyan
  # 造一个"库里在售、但结算前已被别人买走"的场景：加购后立刻把商品改成已售出
  Req POST "$base/cart" @{ itemId = $iOkB; quantity = 1 } $tc | Out-Null
  $cid2 = Sql1 "SELECT id FROM cart_item WHERE user_id=$uc AND item_id=$iOkB;"
  Check '准备：重新加购成功' ($cid2) '没加进去'
  Sql1 "UPDATE item SET status=4 WHERE id=$iOkB;" | Out-Null
  $co2 = Req POST "$base/cart/checkout" @{ cartIds = @([long]$cid2) } $tc
  Check '单品不可面交时结算被拒绝（没有可结算的）' ($co2.code -ne 0) ('code=' + $co2.code)
  # 混合场景：一件好的 + 一件坏的 → 好的成交，坏的回报 skipped
  Req POST "$base/cart" @{ itemId = $iOkA; quantity = 1 } $tc | Out-Null
  $goodCid = Sql1 "SELECT id FROM cart_item WHERE user_id=$uc AND item_id=$iOkA;"
  $co3 = Req POST "$base/cart/checkout" @{ cartIds = @([long]$goodCid, [long]$cid2) } $tc
  Check '混合场景：可面交的那件结算成功' ($co3.code -eq 0) ($co3 | ConvertTo-Json -Compress)
  Check '混合场景：只生成 1 张面交单' (@($co3.data.orders).Count -eq 1) ('实际 ' + @($co3.data.orders).Count)
  Check '混合场景：不可面交的那件被回报在 skipped 里' (@($co3.data.skipped).Count -eq 1) ('skipped=' + (@($co3.data.skipped) | ConvertTo-Json -Compress))
  Check '混合场景：skipped 带商品名与原因' ((@($co3.data.skipped)[0].title) -and (@($co3.data.skipped)[0].reason)) '字段缺失'
  Check '混合场景：不可面交的条目仍留在购物车（用户可自己删）' ((Sql1 "SELECT COUNT(*) FROM cart_item WHERE id=$cid2;") -eq '1') '被清了'
  Check '失败后购物车条目还在（可重试）' ((Sql1 "SELECT COUNT(*) FROM cart_item WHERE id=$cid2;") -eq '1') '被清了'
  # 只针对"这一次失败的结算"：单品不可面交时不该为它建单
  # （注意不能用全局计数——后面混合场景会成功建单）
  # 用"前后对比"判断这次失败有没有建单：更稳，不会被历史数据或后续步骤干扰
  $ordersBefore = [int](Sql1 "SELECT COUNT(*) FROM meetup_order WHERE buyer_id=$uc AND item_id=$iOkB;")
  $co2b = Req POST "$base/cart/checkout" @{ cartIds = @([long]$cid2) } $tc
  $ordersAfter = [int](Sql1 "SELECT COUNT(*) FROM meetup_order WHERE buyer_id=$uc AND item_id=$iOkB;")
  Check '失败结算没有新增面交单' ($ordersAfter -eq $ordersBefore) ("$ordersBefore → $ordersAfter")
}
finally {
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  $c = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
  if ($c) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
  # 清理要彻底：跨轮次残留会污染"按卖家拆单"这类计数断言
  Sql1 "DELETE FROM meetup_order WHERE item_title LIKE '购物车测试-%';" | Out-Null
  Sql1 "DELETE FROM cart_item WHERE item_id IN (SELECT id FROM item WHERE title LIKE '购物车测试-%');" | Out-Null
  Sql1 "DELETE FROM item WHERE title LIKE '购物车测试-%';" | Out-Null
  Sql1 "DELETE FROM meetup_order WHERE item_title LIKE '购物车测试-%';" | Out-Null
  if ($itemIds.Count) { Sql1 ("DELETE FROM item WHERE id IN (" + ($itemIds -join ',') + ");") | Out-Null }
  Write-Host '已清理测试数据' -ForegroundColor DarkGray
}

Write-Host ''
if ($fail -eq 0) { Write-Host ('全部通过：' + $pass + ' 项断言') -ForegroundColor Green }
else { Write-Host ('通过 ' + $pass + ' 项，失败 ' + $fail + ' 项') -ForegroundColor Red }
exit $fail
