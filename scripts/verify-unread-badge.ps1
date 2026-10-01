# 验证「底部消息角标（未读数）」：GET /chat/unread
#
# 背景（真 bug）：Redis Hash `ershou:chat:unread:{uid}` 被当成未读的**权威来源**，
# "缓存非空就直接用缓存"。于是**已被删除的会话在 Hash 里留下僵尸字段**，
# 未读数只增不减 —— 实测出现"角标一直显示 11，但库里未读其实是 0"。
# 修法：DB 是权威，缓存只提供"快速路径"；用 DB 的会话集合校验缓存，剔除僵尸字段。
#
# 断言：
#   1. 新账号未读 = 0
#   2. 无会话时缓存为空 → total = 0
#   3. 塞入 N 个不存在的会话（僵尸）→ total 仍为 0，且僵尸字段被清掉
#   4. 真实会话的未读能正确统计；发消息后 +1
#   5. 已读后归零
#   6. 删除会话后，未读立刻归零（回归：以前会残留）
#
# 隔离：测试实例 8081 + Redis 1 号库
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\verify-unread-badge.ps1
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
$redisPass = $env:REDIS_PASSWORD
$env:SERVER_PORT = '8081'; $env:REDIS_DATABASE = '1'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = 'C:\Program Files\Java\jdk-21\bin;' + $env:Path
$mvn = 'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'

function Sql1([string]$q) { ((& $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou -N -B -e $q 2>$null) -join '') }
# Redis 1 号库（与测试实例一致）
function Rds([string[]]$cmdArgs) { (& docker exec ershou-redis redis-cli -a $redisPass --no-auth-warning -n 1 @cmdArgs 2>$null) -join ' ' }
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
  -RedirectStandardOutput (Join-Path $root '.build\unread.log') `
  -RedirectStandardError (Join-Path $root '.build\unread.err.log')
for ($i = 1; $i -le 90; $i++) {
  Start-Sleep -Seconds 2
  try { $p = Invoke-RestMethod -Uri "$base/categories" -TimeoutSec 5; if ($null -ne $p.code) { break } } catch { }
}
Write-Host '测试实例就绪' -ForegroundColor Green

$itemId = 0
try {
  # 两个账号（Redis 1 号库是干净的，从 0 开始）
  $pa = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
  $pb = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
  Req POST "$base/auth/sms-code" @{ phone = $pa } $null | Out-Null
  $A = Req POST "$base/auth/login" @{ phone = $pa; code = '123456'; schoolId = 1; campusId = 2 } $null
  Req POST "$base/auth/sms-code" @{ phone = $pb } $null | Out-Null
  $B = Req POST "$base/auth/login" @{ phone = $pb; code = '123456'; schoolId = 1; campusId = 2 } $null
  $ta = $A.data.token; $tb = $B.data.token
  $ua = $A.data.user.id; $ub = $B.data.user.id
  $keyA = "ershou:chat:unread:$ua"

  Write-Host ''
  Write-Host '=== 1. 新账号未读为 0 ===' -ForegroundColor Cyan
  $r = Req GET "$base/chat/unread" $null $ta
  Check '新账号 total = 0' ($r.code -eq 0 -and $r.data.total -eq 0) ('total=' + $r.data.total)

  Write-Host ''
  Write-Host '=== 2. 塞入僵尸会话字段 → 不能计入未读，且要被清掉 ===' -ForegroundColor Cyan
  # 模拟"会话被删掉、Hash 里留下残留"（就是线上那个 11 的成因）
  Rds @('HSET', $keyA, '888881', '5', '888882', '4', '888883', '2') | Out-Null
  $seeded = Rds @('HGETALL', $keyA)
  Check ('塞入 3 个僵尸字段（5+4+2=11）：' + $seeded) ($seeded -match '888881') '塞入失败'
  $r2 = Req GET "$base/chat/unread" $null $ta
  Check '有僵尸字段时 total 仍为 0（不虚增）' ($r2.data.total -eq 0) ('total=' + $r2.data.total)
  $left = Rds @('HGETALL', $keyA)
  Check '僵尸字段已被自动清理' ([string]::IsNullOrWhiteSpace($left)) ('残留: ' + $left)
  # ⚠️ 日志必须按 UTF-8 读：Get-Content 在 PS 5.1 下按 GBK 解码 UTF-8 会把中文读花，
  # 于是"日志里明明有"却匹配不到（这个坑踩过好几次）
  # 日志文件被 JVM 独占：要 FileShare.ReadWrite 打开，纯 File::ReadAllText 会抛 IOException
  $logPath = Join-Path $root '.build\unread.log'
  $log = ''
  for ($k = 1; $k -le 5; $k++) {
    try {
      $fs = [System.IO.File]::Open($logPath, 'Open', 'Read', 'ReadWrite')
      $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
      $log = $sr.ReadToEnd(); $sr.Close(); $fs.Close()
      break
    } catch { Start-Sleep -Milliseconds 400 }
  }
  Check '日志里记录了清理动作' ($log -match '僵尸未读字段') '没有日志'
  Write-Host ''
  Write-Host '=== 3. 真实会话：未读能正确统计，已读后归零 ===' -ForegroundColor Cyan
  # 造商品 + 约面交（会建会话并给卖家发一条提醒）
  $pt = Req POST "$base/trade-points" @{ campusId = 2; name = '未读测试点'; lng = 110.4188; lat = 25.3133 } $ta
  $itemId = 880000 + (Get-Random -Minimum 1 -Maximum 9999)
  $q = [char]34
  $f = Join-Path $root '.build\_tmp-sql.sql'
  [System.IO.File]::WriteAllText($f, ("INSERT INTO item (id, seller_id, category_id, campus_id, title, description, price, " +
    "condition_level, stock, is_unique, negotiable, status, images, prefer_point_id, created_at) " +
    "VALUES ($itemId, $ua, 1, 2, '未读测试商品', 'x', 10.50, 1, 1, 1, 1, 2, JSON_ARRAY('/static/t.jpg'), $($pt.data.id), NOW());"),
    (New-Object System.Text.UTF8Encoding($false)))
  & $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou --default-character-set=utf8mb4 -e "source $f" 2>$null | Out-Null
  Check '准备：测试商品已就绪' ((Sql1 "SELECT COUNT(*) FROM item WHERE id=$itemId;") -eq '1') '入库失败'

  $order = Req POST "$base/meetup-orders" @{ itemId = $itemId; quantity = 1; pointId = $pt.data.id } $tb
  $orderId = @($order.data.orders)[0].orderId
  Check '准备：B 约面交成功' ($order.code -eq 0 -and $orderId) ($order | ConvertTo-Json -Compress)
  # 约面交会给卖家（A）发"【约面交】"提醒 → A 的未读应为 1
  Start-Sleep -Seconds 2
  $r3 = Req GET "$base/chat/unread" $null $ta
  Check 'A 有 1 条未读' ($r3.data.total -eq 1) ('total=' + $r3.data.total)
  $sid = @($r3.data.sessions.PSObject.Properties)[0].Name
  Check '未读记在那个真实会话上' ($r3.data.sessions.$sid -eq 1) ('sessions=' + ($r3.data.sessions | ConvertTo-Json -Compress))

  Req POST "$base/chat/sessions/$sid/read" $null $ta | Out-Null
  $r4 = Req GET "$base/chat/unread" $null $ta
  Check '标记已读后归零' ($r4.data.total -eq 0) ('total=' + $r4.data.total)

  Write-Host ''
  Write-Host '=== 4. 回归：会话被删除后未读立刻归零（以前会残留）===' -ForegroundColor Cyan
  Req POST "$base/chat/sessions/$sid/read" $null $ta | Out-Null
  # 再让 B 发一条，让 A 有未读
  # ⚠️ 发消息是 POST /chat/messages，sessionId 在 body 里（不是 /sessions/{id}/messages）
  $sent = Req POST "$base/chat/messages" @{ sessionId = [long]$sid; msgType = 1; content = '在吗'; clientMsgId = [guid]::NewGuid().ToString() } $tb
  Check '准备：B 发消息成功' ($sent.code -eq 0) ($sent | ConvertTo-Json -Compress)
  $r5 = Req GET "$base/chat/unread" $null $ta
  Check 'B 发消息后 A 又有未读' ($r5.data.total -ge 1) ('total=' + $r5.data.total)
  # 模拟"会话被删除"：直接删库（Hash 里仍留着未读）
  Sql1 "DELETE FROM chat_session WHERE id=$sid;" | Out-Null
  $r6 = Req GET "$base/chat/unread" $null $ta
  Check '会话删除后 total 归零（僵尸不再虚增）' ($r6.data.total -eq 0) ('total=' + $r6.data.total)
  $left2 = Rds @('HGETALL', $keyA)
  Check '对应的 Hash 字段也被清掉' ($left2 -notmatch ([string]$sid)) ('残留: ' + $left2)
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
  Sql1 "DELETE FROM trade_point WHERE name='未读测试点';" | Out-Null
  Sql1 "DELETE FROM chat_message WHERE item_id=$itemId;" | Out-Null
  Write-Host '已清理测试数据' -ForegroundColor DarkGray
}

Write-Host ''
if ($fail -eq 0) { Write-Host ('全部通过：' + $pass + ' 项断言') -ForegroundColor Green }
else { Write-Host ('通过 ' + $pass + ' 项，失败 ' + $fail + ' 项') -ForegroundColor Red }
exit $fail
