
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$base = 'http://127.0.0.1:8080/api/v1'
$MYSQL = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'

# 自动载入根目录 .env（MYSQL_PASSWORD / REDIS_PASSWORD 等）
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  Get-Content $envFile | Where-Object { $_ -match '^\s*[A-Z_]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
  }
}
$env:MYSQL_PWD = $env:MYSQL_PASSWORD

# 前置检查：后端必须已启动
try { Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 5 | Out-Null }
catch { Write-Host '后端未启动，请先运行 .\scripts\dev-server.ps1' -ForegroundColor Red; exit 1 }

function Post($url, $obj, $headers) {
  $json = $obj | ConvertTo-Json -Depth 6 -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  if ($headers) { return Invoke-RestMethod -Uri $url -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $bytes }
  return Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json; charset=utf-8' -Body $bytes
}
function Show($step, $r) {
  if ($null -eq $r) { Write-Host ("  " + $step + " => 无响应") -ForegroundColor Red; return }
  if ($null -ne $r.code) {
    $color = if ($r.code -eq 0) { 'Green' } else { 'Yellow' }
    Write-Host ("  " + $step + " => code=" + $r.code + "  " + $r.message) -ForegroundColor $color
    if ($null -ne $r.data) {
      $p = ($r.data | ConvertTo-Json -Depth 3 -Compress)
      if ($p.Length -gt 200) { $p = $p.Substring(0,200) + '...' }
      Write-Host ("       " + $p) -ForegroundColor DarkGray
    }
  } else { Write-Host ("  " + $step + " => " + ($r | ConvertTo-Json -Compress)) -ForegroundColor DarkGray }
}

# 端到端冒烟测试：登录 → 发布（含 AI 机审）→ 购物车 → 合并面交 → 核销 → 双向确认
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
# 前置：后端已在 8080 运行（.\scripts\dev-server.ps1）
Write-Host '=== 0. 清理上一轮测试数据 ===' -ForegroundColor Cyan
& $MYSQL -h 127.0.0.1 -P 3306 -u ershou -e "use ershou; delete from meetup_order; delete from cart_item; delete from favorite; delete from item; delete from credit_log; alter table item auto_increment=1; alter table meetup_order auto_increment=1; alter table cart_item auto_increment=1;" 2>&1 | Out-Null
# 读缓存必须一起清：本脚本是直接删库，Redis 里商品详情 / 地图点位缓存会盖住刚清掉的数据
$cacheLua = "for i=1,#ARGV do local ks=redis.call('keys',ARGV[i]); for _,k in ipairs(ks) do redis.call('del',k) end end return 'ok'"
docker exec ershou-redis redis-cli -a $env:REDIS_PASSWORD --no-auth-warning eval $cacheLua 0 'ershou:item:detail:*' 'ershou:item:null:*' 'ershou:item:view:*' 'ershou:map:points:*' 'ershou:dict:*' 'ershou:admin:dashboard' 2>&1 | Out-Null
Write-Host '  已清空 item/meetup_order/cart_item/favorite（保留用户与点位）' -ForegroundColor DarkGray
Write-Host '  已清空 Redis 读缓存（商品详情 / 浏览量计数 / 地图点位 / 字典 / 后台统计）' -ForegroundColor DarkGray

Write-Host '=== 1. 登录卖家(13800000002) 与 买家(13800000001) ===' -ForegroundColor Cyan
Post "$base/auth/sms-code" @{phone='13800000002'} | Out-Null
$sellerLogin = Post "$base/auth/login" @{phone='13800000002';code='123456';schoolId=1;campusId=1}
$sellerToken = $sellerLogin.data.token
$sellerId = $sellerLogin.data.user.id
Write-Host ("  卖家 id=" + $sellerId) -ForegroundColor DarkGray
Post "$base/auth/sms-code" @{phone='13800000001'} | Out-Null
$buyerLogin = Post "$base/auth/login" @{phone='13800000001';code='123456';schoolId=1;campusId=1}
$buyerToken = $buyerLogin.data.token
$buyerId = $buyerLogin.data.user.id
Write-Host ("  买家 id=" + $buyerId) -ForegroundColor DarkGray
$SH = @{ Authorization = "Bearer $sellerToken" }
$BH = @{ Authorization = "Bearer $buyerToken" }

Write-Host '=== 2. 卖家上传图片 + 发布 3 件商品（中文）===' -ForegroundColor Cyan
$imgPath = 'E:\ershou\.build\test-item.png'
$up = (& curl.exe -s -X POST "$base/files/upload?biz=item" -H "Authorization: Bearer $sellerToken" -F "file=@$imgPath") | ConvertFrom-Json
$img = $up.data.url
Write-Host ("  图片: " + $img) -ForegroundColor DarkGray
$items = @(
  @{ title='高等数学 同济第七版 九成新'; description='只用了一学期，无笔记无划线，可小刀，图书馆东门当面验货'; categoryId=1; price=15.00; originalPrice=45.00; preferPointId=1 },
  @{ title='捷安特山地车 骑行两年'; description='车况良好，刚换过刹车皮，3号宿舍楼下看车'; categoryId=3; price=380.00; originalPrice=1200.00; preferPointId=3 },
  @{ title='小米台灯 护眼版 几乎全新'; description='买了没多久就换宿舍了，一直闲置'; categoryId=4; price=35.00; originalPrice=99.00; preferPointId=2 }
)
$ids = @()
foreach ($it in $items) {
  $body = @{ title=$it.title; description=$it.description; categoryId=$it.categoryId; conditionLevel=2; price=$it.price; originalPrice=$it.originalPrice; negotiable=1; stock=1; isUnique=1; images=@($img); preferPointId=$it.preferPointId }
  $r = Post "$base/items" $body $SH
  Show ("发布 " + $it.title) $r
  if ($r.code -eq 0) { $ids += $r.data.itemId }
}

Write-Host '=== 3. 违规内容拦截（应返回 50001）===' -ForegroundColor Cyan
Show '发布"管制刀具"' (Post "$base/items" @{ title='出一把管制刀具 便宜出'; description='懂的来'; categoryId=1; conditionLevel=3; price=10; images=@($img) } $SH)
Show '发布"加微信转账"' (Post "$base/items" @{ title='出教材 加微信转账有优惠'; description='支持定金'; categoryId=1; conditionLevel=3; price=5; images=@($img) } $SH)

Write-Host '=== 4. 中文是否正确入库 ===' -ForegroundColor Cyan
& $MYSQL -h 127.0.0.1 -P 3306 -u ershou -N -B -e "use ershou; select id, title, status from item order by id;" 2>&1

Write-Host '=== 5. 买家：加购物车 2 件 → 合并面交 ===' -ForegroundColor Cyan
foreach ($id in $ids[0..1]) { Post "$base/cart" @{itemId=$id;quantity=1} $BH | Out-Null }
$cart = Invoke-RestMethod -Uri "$base/cart" -Headers $BH
$cartIds = @($cart.data[0].items | ForEach-Object { $_.cartId })
Write-Host ("  购物车 " + $cartIds.Count + " 件，卖家=" + $cart.data[0].sellerName) -ForegroundColor DarkGray
$start = (Get-Date).AddHours(2).ToString('yyyy-MM-dd HH:mm:ss')
$end = (Get-Date).AddHours(4).ToString('yyyy-MM-dd HH:mm:ss')
$co = Post "$base/cart/checkout" @{cartIds=$cartIds;pointId=3;timeSlotStart=$start;timeSlotEnd=$end;remark='我穿黑色外套，快递柜旁等'} $BH
Show 'checkout' $co
$orderIds = @($co.data.orders | ForEach-Object { $_.orderId })
Write-Host ("  生成 " + $orderIds.Count + " 张面交单，行程号 " + $co.data.batchNo) -ForegroundColor DarkGray

Write-Host '=== 6. 卖家：接受面交 → 读取核销码 ===' -ForegroundColor Cyan
foreach ($oid in $orderIds) { Show ("accept 单" + $oid) (Post "$base/meetup-orders/$oid/accept" @{} $SH) }
$sellerOrders = Invoke-RestMethod -Uri "$base/meetup-orders?role=seller" -Headers $SH
$codes = @{}
foreach ($rec in $sellerOrders.data.records) { $codes[$rec.id] = $rec.verifyCode }
foreach ($k in $codes.Keys) { Write-Host ("  单" + $k + " 核销码=" + $codes[$k] + "  状态=" + ($sellerOrders.data.records | Where-Object {$_.id -eq $k}).statusText) -ForegroundColor DarkGray }

Write-Host '=== 7. 买家：核销 + 确认；卖家：确认 ===' -ForegroundColor Cyan
$first = $orderIds[0]
Show 'verify 错误码' (Post "$base/meetup-orders/$first/verify" @{code='000000'} $BH)
Show 'verify 正确码' (Post "$base/meetup-orders/$first/verify" @{code=$codes[$first]} $BH)
Show 'verify 重复使用' (Post "$base/meetup-orders/$first/verify" @{code=$codes[$first]} $BH)
Show '买家 confirm' (Post "$base/meetup-orders/$first/confirm" @{} $BH)
Show '卖家 confirm（应完成）' (Post "$base/meetup-orders/$first/confirm" @{} $SH)

Write-Host '=== 8. 完成后的数据核对 ===' -ForegroundColor Cyan
$final = & $MYSQL -h 127.0.0.1 -P 3306 -u ershou -N -B -e "use ershou; select concat('item', id, ' status=', status, ' stock=', stock) from item order by id; select concat('order', id, ' status=', status, ' verified=', ifnull(verified_at,'no'), ' buyer_confirm=', ifnull(buyer_confirm_at,'no'), ' seller_confirm=', ifnull(seller_confirm_at,'no')) from meetup_order order by id; select concat('user', id, ' credit=', credit_score) from sys_user order by id; select concat('cart rows=', count(*)) from cart_item;" 2>&1
$final
Write-Host '=== 9. 健康检查 ===' -ForegroundColor Cyan
(Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health') | ConvertTo-Json -Compress