# =============================================================
# 真实地址选点功能 · 端到端验证
#
# 为什么不用现成的 8080：那是用户在 IDEA 里跑的实例，代码是旧的（没有新接口）。
# 这里用 8081 + 独立 Redis 库起一个测试实例，验证完就关掉，不影响用户环境。
#
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\amap-point-test.ps1
# =============================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$base = 'http://127.0.0.1:8081/api/v1'

# 载入 .env
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
  }
}

# 测试实例专用：8081 + Redis 1 号库（避免污染用户正在用的会话/缓存）
$env:SERVER_PORT = '8081'
$env:REDIS_DATABASE = '1'

$jdk = 'C:\Program Files\Java\jdk-21'
$env:JAVA_HOME = $jdk
$env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path
$mvn = 'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'

function Post($url, $obj, $token) {
  $json = $obj | ConvertTo-Json -Depth 6 -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  $headers = @{}
  if ($token) { $headers['Authorization'] = "Bearer $token" }
  return Invoke-RestMethod -Uri $url -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $bytes
}
function Get2($url, $token) {
  $headers = @{}
  if ($token) { $headers['Authorization'] = "Bearer $token" }
  return Invoke-RestMethod -Uri $url -Headers $headers
}
function Patch($url, $obj, $token) {
  $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 6 -Compress))
  $h = @{}
  if ($token) { $h['Authorization'] = "Bearer $token" }
  return Invoke-RestMethod -Uri $url -Method Patch -Headers $h -ContentType 'application/json; charset=utf-8' -Body $bytes
}

$pass = 0; $fail = 0
function Check($desc, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ("  [PASS] " + $desc) -ForegroundColor Green }
  else { $script:fail++; Write-Host ("  [FAIL] " + $desc + "  " + $detail) -ForegroundColor Red }
}

# ---------- 启动测试实例 ----------
Write-Host '=== 启动测试实例（8081, redis db1） ===' -ForegroundColor Cyan
$serverDir = Join-Path $root 'ershou-server'
$logFile = Join-Path $root '.build\amap-point-test-server.log'
$proc = Start-Process -FilePath $mvn `
  -ArgumentList '-B', '-o', 'spring-boot:run', '-Dspring-boot.run.profiles=dev,local' `
  -WorkingDirectory $serverDir -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $root '.build\amap-point-test-server.err.log')
Write-Host ("  mvn pid=" + $proc.Id + "，等待启动…") -ForegroundColor DarkGray

$ready = $false
for ($i = 1; $i -le 60; $i++) {
  Start-Sleep -Seconds 2
  # 注意：不能用 /actuator/health —— 本机 ES 缺 X-Elastic-Product 头会让健康检查恒为 DOWN，
  # 但业务接口是正常的，所以直接探业务接口的连通性。
  try {
    $probe = Invoke-RestMethod -Uri 'http://127.0.0.1:8081/api/v1/trade-points?campusId=2' -TimeoutSec 5
    if ($null -ne $probe.code) { $ready = $true; break }
  } catch {
    $statusCode = 0
    if ($_.Exception.Response) { $statusCode = [int]$_.Exception.Response.StatusCode }
    if ($statusCode -in 400, 401, 403, 404, 500) { $ready = $true; break }
  }
}
if (-not $ready) {
  Write-Host '  测试实例启动失败，日志尾部：' -ForegroundColor Red
  Get-Content $logFile -Tail 30 -ErrorAction SilentlyContinue
  Get-Content (Join-Path $root '.build\amap-point-test-server.err.log') -Tail 20 -ErrorAction SilentlyContinue
  if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
  exit 1
}
Write-Host '  测试实例已就绪' -ForegroundColor Green

try {
  # ---------- 1. 登录 ----------
  # 用随机手机号：固定测试号会被"新账号每日最多发布 5 件"限流，跑几轮就出现非功能性的失败
  $buyerPhone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
  $sellerPhone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
  Write-Host ''
  Write-Host ("=== 1. 登录测试用户（花江校区 campusId=2；随机号 " + $buyerPhone + " / " + $sellerPhone + "） ===") -ForegroundColor Cyan
  Post "$base/auth/sms-code" @{ phone = $buyerPhone } | Out-Null
  $login = Post "$base/auth/login" @{ phone = $buyerPhone; code = '123456'; schoolId = 1; campusId = 2 }
  Check '登录成功' ($login.code -eq 0) ($login | ConvertTo-Json -Compress)
  $token = $login.data.token
  $userId = $login.data.user.id
  Write-Host ("  userId=" + $userId + "  campusId=" + $login.data.user.campusId) -ForegroundColor DarkGray

  # ---------- 1.5 校区底图隔离（回归：曾经因为 fetchMapPoints() 漏传 campusId 串到金鸡岭） ----------
  Write-Host ''
  Write-Host '=== 1.5 校区底图必须各归各（防"发布页选址显示成别的校区"回归） ===' -ForegroundColor Cyan
  $map1 = Get2 "$base/map/points?campusId=1" $token
  $map2 = Get2 "$base/map/points?campusId=2" $token
  $c1 = $map1.data.mapConfig.center
  $c2 = $map2.data.mapConfig.center
  Write-Host ("    金鸡岭 campusId=1 → " + $c1.lng + "," + $c1.lat + "  zoom=" + $map1.data.mapConfig.zoom + "  size=" + $map1.data.mapConfig.campusSizeMeters) -ForegroundColor DarkGray
  Write-Host ("    花江   campusId=2 → " + $c2.lng + "," + $c2.lat + "  zoom=" + $map2.data.mapConfig.zoom + "  size=" + $map2.data.mapConfig.campusSizeMeters) -ForegroundColor DarkGray
  Check '两个校区的中心点不相同' (($c1.lng -ne $c2.lng) -or ($c1.lat -ne $c2.lat)) ("c1=" + $c1.lng + "," + $c1.lat + " c2=" + $c2.lng + "," + $c2.lat)
  Check '花江中心为 110.416819,25.311724' ([math]::Abs([double]$c2.lng - 110.416819) -lt 0.000001 -and [math]::Abs([double]$c2.lat - 25.311724) -lt 0.000001) ($c2 | ConvertTo-Json -Compress)
  Check '金鸡岭中心为 110.336881,25.283964' ([math]::Abs([double]$c1.lng - 110.336881) -lt 0.000001 -and [math]::Abs([double]$c1.lat - 25.283964) -lt 0.000001) ($c1 | ConvertTo-Json -Compress)
  Check '花江底图尺度 1500 米 / zoom 15' ($map2.data.mapConfig.campusSizeMeters -eq 1500 -and $map2.data.mapConfig.zoom -eq 15) ("size=" + $map2.data.mapConfig.campusSizeMeters + " zoom=" + $map2.data.mapConfig.zoom)

  # ---------- 2. 官方点位（应能读到花江 8 个真实点位） ----------
  Write-Host ''
  Write-Host '=== 2. 官方点位列表（花江校区真实坐标） ===' -ForegroundColor Cyan
  $official = Get2 "$base/trade-points?campusId=2" $token
  Check '官方点位接口可用' ($official.code -eq 0) ($official | ConvertTo-Json -Compress)
  $withGeo = @($official.data | Where-Object { $_.lng -and $_.lat })
  Check ("官方点位带真实经纬度（" + $withGeo.Count + "/" + @($official.data).Count + "）") ($withGeo.Count -ge 8) "实际 $($withGeo.Count)"
  $line = $line.TrimStart([char]0xFEFF).Trim()
  # ⚠️ 同一行可能带注释（`# ... KEY=value`），先砍注释再解析
  $k, $v = (($line -split '#', 2)[0]) -split '=', 2
  if (-not $k -or -not $v) { continue }

  # ---------- 3. 地图上新增真实地址 ----------
  Write-Host ''
  Write-Host '=== 3. 地图新增真实地址（POST /trade-points） ===' -ForegroundColor Cyan
  $newPoint = Post "$base/trade-points" @{
    campusId  = 2
    name      = '体育馆东侧看台'
    lng       = 110.417900
    lat       = 25.310500
    guideText = '体育馆东侧看台入口，靠近田径场'
    pointType = 3
  } $token
  Check '新增点位成功' ($newPoint.code -eq 0) ($newPoint | ConvertTo-Json -Compress)
  Check '返回了点位 id' ($newPoint.data.id -gt 0) ($newPoint.data | ConvertTo-Json -Compress)
  Check '经纬度已保存' ($newPoint.data.lng -eq 110.4179 -and $newPoint.data.lat -eq 25.3105) ($newPoint.data | ConvertTo-Json -Compress)
  Check '新点位免审核（auditStatus=1，直接通过）' ($newPoint.data.auditStatus -eq 1) ("auditStatus=" + $newPoint.data.auditStatus)
  $pointId = $newPoint.data.id
  Write-Host ("  新点位 id=" + $pointId) -ForegroundColor DarkGray

  # ---------- 4. 同名点位**不合并**（曾经会复用旧点位，用户以为"被吞了"） ----------
  $again = Post "$base/trade-points" @{
    campusId = 2; name = '体育馆东侧看台'; lng = 110.417900; lat = 25.3105; pointType = 3
  } $token
  Check '同名点位不合并，各自独立' ($again.data.id -ne $pointId) ("第一次=" + $pointId + " 第二次=" + $again.data.id)
  $sameNameCount = @((Get2 "$base/trade-points?campusId=2" $token).data | Where-Object { $_.name -eq '体育馆东侧看台' }).Count
  Check '同名点位在列表里是两条' ($sameNameCount -eq 2) ("实际=" + $sameNameCount)

  # ---------- 5. 免审核 → 买卖双方都能看到 ----------
  Write-Host ''
  Write-Host '=== 4. 自定义点位免审核：买卖双方都能看到 ===' -ForegroundColor Cyan
  $official2 = Get2 "$base/trade-points?campusId=2" $token
  $inOfficial = @($official2.data | Where-Object { $_.id -eq $pointId }).Count
  Check '新增点位立刻出现在校区点位列表（买家/卖家/所有人可见）' ($inOfficial -eq 1) "出现次数=$inOfficial"

  $mapPoints = Get2 "$base/map/points?campusId=2" $token
  $inMap = @($mapPoints.data.points | Where-Object { $_.id -eq $pointId }).Count
  Check '新增点位立刻出现在地图上' ($inMap -eq 1) "出现次数=$inMap"

  # 换一个用户（卖家视角）确认"对方也能看到"
  Post "$base/auth/sms-code" @{ phone = $sellerPhone } | Out-Null
  $sellerLogin = Post "$base/auth/login" @{ phone = $sellerPhone; code = '123456'; schoolId = 1; campusId = 2 }
  $sellerToken = $sellerLogin.data.token
  $sellerId = $sellerLogin.data.user.id
  $sellerView = Get2 "$base/trade-points?campusId=2" $sellerToken
  $seenBySeller = @($sellerView.data | Where-Object { $_.id -eq $pointId }).Count
  Check '另一个用户（卖家）也能看到该点位' ($seenBySeller -eq 1) ("卖家 userId=" + $sellerId + " 看到次数=" + $seenBySeller)

  $mine = Get2 "$base/trade-points/my-points?campusId=2" $token
  Check '自己的点位列表接口可用' ($mine.code -eq 0) ($mine | ConvertTo-Json -Compress)
  $inMine = @($mine.data | Where-Object { $_.id -eq $pointId }).Count
  Check '自己的点位列表里也含该点位' ($inMine -eq 1) "出现次数=$inMine"

  # ---------- 6. 用它发布商品 ----------
  Write-Host ''
  Write-Host '=== 5. 用新增的真实地址发布商品 ===' -ForegroundColor Cyan
  $publish = Post "$base/items" @{
    title          = '桂电花江-测试折叠椅'
    description    = '真实地址选点端到端验证商品，可忽略'
    categoryId     = 4
    conditionLevel = 2
    price          = 25.00
    negotiable     = 1
    stock          = 1
    isUnique       = 1
    images         = @('/ershou/demo/chair.jpg')
    preferPointId  = $pointId
  } $token
  Check '发布成功' ($publish.code -eq 0) ($publish | ConvertTo-Json -Compress)

  if ($publish.code -eq 0) {
    $itemId = $publish.data.itemId
    $detail = Get2 "$base/items/$itemId" $token
    Check '商品详情能带出该点位' ($detail.data.preferPointId -eq $pointId) ("preferPointId=" + $detail.data.preferPointId)
    Check '商品详情显示点位名称' ($detail.data.preferPointName -eq '体育馆东侧看台') ("name=" + $detail.data.preferPointName)
  }

  # ---------- 7. 参数校验 ----------
  Write-Host ''
  Write-Host '=== 6. 参数校验 ===' -ForegroundColor Cyan
  $noCoord = Post "$base/trade-points" @{ campusId = 2; name = '没有坐标的点' } $token
  Check '缺坐标被拦截' ($noCoord.code -ne 0) ("code=" + $noCoord.code)
  $noName = Post "$base/trade-points" @{ campusId = 2; lng = 110.41; lat = 25.31 } $token
  Check '缺点位名被拦截' ($noName.code -ne 0) ("code=" + $noName.code)

  # ---------- 8. 未登录不可用 ----------
  $anon = Get2 "$base/trade-points/my-points?campusId=2" $null
  Check '未登录访问我的点位被拒' ($anon.code -ne 0) ("code=" + $anon.code)

  # ---------- 9. 私聊页改面交地址（PATCH /meetup-orders/{id}/point） ----------
  Write-Host ''
  Write-Host '=== 7. 私聊页改面交地址 ===' -ForegroundColor Cyan
  # 当前 $token = 买家（随机号），$sellerToken = 卖家（随机号）
  $itPublish = Post "$base/items" @{
    title = '桂电花江-测试改地址用商品'; description = '改面交地址端到端验证，可忽略'
    categoryId = 4; conditionLevel = 2; price = 30.00; negotiable = 1; stock = 1; isUnique = 1
    images = @('/ershou/demo/chair2.jpg'); preferPointId = $pointId
  } $sellerToken
  Check '卖家发布测试商品成功' ($itPublish.code -eq 0) ($itPublish | ConvertTo-Json -Compress)
  $addrItemId = $itPublish.data.itemId

  $pointA = ($official.data | Where-Object { $_.name -eq '中央食堂正门' } | Select-Object -First 1)
  Check '找到用于初始面交的官方点位' ($null -ne $pointA) "点位 中央食堂正门"

  $created = Post "$base/meetup-orders" @{
    itemId = $addrItemId; quantity = 1; pointId = $pointA.id
  } $token
  Check '买家提交约面交成功' ($created.code -eq 0) ($created | ConvertTo-Json -Compress)
  $orderId = $created.data.orders[0].orderId
  Write-Host ("  面交单 id=" + $orderId + " 初始面交点=" + $pointA.name) -ForegroundColor DarkGray

  # 买家改到自建点位（免审核的那个）
  $changed = Patch "$base/meetup-orders/$orderId/point" @{ pointId = $pointId } $token
  Check '买家可以改面交地点' ($changed.code -eq 0) ($changed | ConvertTo-Json -Compress)
  Check '返回的面交点已更新' ($changed.data.pointId -eq $pointId) ("pointId=" + $changed.data.pointId)
  Check '返回点位名正确' ($changed.data.pointName -eq '体育馆东侧看台') ("pointName=" + $changed.data.pointName)

  # 卖家再改回官方点位（双方都能改）
  $changedBack = Patch "$base/meetup-orders/$orderId/point" @{ pointId = $pointA.id } $sellerToken
  Check '卖家也能改面交地点（双方都可改）' ($changedBack.code -eq 0 -and $changedBack.data.pointId -eq $pointA.id) ("code=" + $changedBack.code + " pointId=" + $changedBack.data.pointId)

  # 改地址要发私聊提醒。断言口径说明（踩过坑）：
  #   GET /chat/sessions               → List<ChatSessionVO>，lastMsgText 即最新一条消息
  #   GET /chat/sessions/{id}/messages → PageResult，字段是 records/total（没有外层 data）
  # 用 lastMsgText 断言最直接：改地址的提醒必然是该会话最新一条。
  $sessions = Get2 "$base/chat/sessions" $sellerToken
  $addrSession = @($sessions.data | Where-Object { $_.itemId -eq $addrItemId } | Select-Object -First 1)
  Check '改地址后自动建了私聊会话' ($addrSession.Count -eq 1) ("会话数=" + $addrSession.Count)
  if ($addrSession.Count -eq 1) {
    Check '卖家收到的提醒就是「面交地点已更新」' ($addrSession[0].lastMsgText -match '面交地点已更新') ("lastMsgText=" + $addrSession[0].lastMsgText)
    Check '提醒里带上了新地点名' ($addrSession[0].lastMsgText -match '中央食堂正门') ("lastMsgText=" + $addrSession[0].lastMsgText)
  }

  # 非法输入
  $badPoint = Patch "$base/meetup-orders/$orderId/point" @{ pointId = 999999 } $token
  Check '不存在的点位被拦截' ($badPoint.code -ne 0) ("code=" + $badPoint.code)
  $noPoint = Patch "$base/meetup-orders/$orderId/point" @{} $token
  Check '不传点位被拦截' ($noPoint.code -ne 0) ("code=" + $noPoint.code)
  $badSlot = Patch "$base/meetup-orders/$orderId/point" @{ pointId = $pointId; timeSlotStart = '2020-01-01 10:00:00'; timeSlotEnd = '2020-01-01 12:00:00' } $token
  Check '过去的时间窗被拦截' ($badSlot.code -ne 0) ("code=" + $badSlot.code)

  # 别人的单子改不了（同一个号码走"发码 → 登录"，两次随机会对不上验证码）
  $strangerPhone = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
  Post "$base/auth/sms-code" @{ phone = $strangerPhone } | Out-Null
  $strangerLogin = Post "$base/auth/login" @{ phone = $strangerPhone; code = '123456'; schoolId = 1; campusId = 2 }
  $strangerToken = $strangerLogin.data.token
  $strangerTry = Patch "$base/meetup-orders/$orderId/point" @{ pointId = $pointId } $strangerToken
  Check '无关用户改别人的面交单被拒' ($strangerTry.code -ne 0) ("code=" + $strangerTry.code)

  # ---------- 10. 地图页"选点模式"回归：经纬度不能被当成像素坐标 ----------
  Write-Host ''
  Write-Host '=== 9. 地图页选点回归（曾经把经纬度存成像素坐标 coord_x/coord_y） ===' -ForegroundColor Cyan
  $picked = Post "$base/trade-points/suggest" @{
    campusId = 2; name = '选点回归-图书馆西侧'; lng = 110.4181; lat = 25.3129
    guideText = '图书馆西侧台阶'; pointType = 5
  } $token
  Check '选点接口返回点位本身（不再是 void）' ($picked.code -eq 0 -and $picked.data.id -gt 0) ($picked | ConvertTo-Json -Compress)
  Check '经纬度写进了 lng/lat' ($picked.data.lng -eq 110.4181 -and $picked.data.lat -eq 25.3129) ("lng=" + $picked.data.lng + " lat=" + $picked.data.lat)
  Check '没有把经纬度误写成像素 coordX/coordY' (($null -eq $picked.data.coordX) -and ($null -eq $picked.data.coordY)) ("coordX=" + $picked.data.coordX + " coordY=" + $picked.data.coordY)
  Check '点位类型已保存' ($picked.data.type -eq 5) ("type=" + $picked.data.type)

  $afterPick = Get2 "$base/trade-points?campusId=2" $token
  Check '新选的点立刻出现在「全部面交点」列表' (@($afterPick.data | Where-Object { $_.id -eq $picked.data.id }).Count -eq 1) '未找到'
  $mapAfterPick = Get2 "$base/map/points?campusId=2" $token
  Check '新选的点立刻出现在地图数据里' (@($mapAfterPick.data.points | Where-Object { $_.id -eq $picked.data.id }).Count -eq 1) '未找到'

  # ---------- 清理测试数据 ----------
  Write-Host ''
  Write-Host '=== 10. 清理测试数据 ===' -ForegroundColor Cyan
  $mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
  $env:MYSQL_PWD = $env:MYSQL_PASSWORD
  & $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou -e "DELETE FROM meetup_order WHERE item_id = $addrItemId; DELETE FROM item WHERE title LIKE '桂电花江-测试%'; DELETE FROM trade_point WHERE name IN ('体育馆东侧看台','选点回归-图书馆西侧');" 2>&1 | Out-Null  Write-Host '  已删除测试商品 / 面交单 / 测试点位' -ForegroundColor DarkGray
}
finally {
  Write-Host ''
  Write-Host '=== 关闭测试实例 ===' -ForegroundColor Cyan
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  # mvn 会派生 java 子进程，一并收掉 8081 的监听者
  Start-Sleep -Seconds 2
  $conn = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
  if ($conn) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue }
  Write-Host '  已关闭' -ForegroundColor DarkGray
}

Write-Host ''
if ($fail -eq 0) { Write-Host ("全部通过：" + $pass + " 项断言") -ForegroundColor Green }
else { Write-Host ("通过 " + $pass + " 项，失败 " + $fail + " 项") -ForegroundColor Red }
exit $fail
