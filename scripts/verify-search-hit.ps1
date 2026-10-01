# 验证「先数据库、后高德」：搜索到的地点落库 + 幂等复用 + 不刷屏
#
# 断言：
#   1. POST /trade-points/search-hit 落库，source=3
#   2. 同名再落一次 → 返回同一条 id（不重复插），这就是"先查库"的落点
#   3. 用户自建点位 POST /trade-points → source=2
#   4. GET /trade-points 能看到 source=2 的自建点位
#   5. GET /trade-points **看不到**没挂商品的 source=3 点位（否则地图会被搜过的地名刷屏）
#   6. 同一名字存两次后，库里只有 1 条
#
# 隔离：测试实例 8081 + Redis 1 号库
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\verify-search-hit.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$base = 'http://127.0.0.1:8081/api/v1'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'

[System.IO.File]::ReadAllLines((Join-Path $root '.env'), [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
  $_ = $_.TrimStart([char]0xFEFF).Trim()
  # .env 里有的变量把注释写在同一行（例：`# 说明... VITE_AMAP_JS_KEY=xxx`），
  # 直接 split 会把 key 读成注释文本，所以先砍掉 # 之后的内容。
  $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
  if (-not $k -or -not $v) { continue }
  Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
}
$env:MYSQL_PWD = $env:MYSQL_PASSWORD
$env:SERVER_PORT = '8081'; $env:REDIS_DATABASE = '1'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = 'C:\Program Files\Java\jdk-21\bin;' + $env:Path
$mvn = 'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'

function Post($url, $obj, $token) {
  $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 6 -Compress))
  $h = @{}
  if ($token) { $h['Authorization'] = "Bearer $token" }
  Invoke-RestMethod -Uri $url -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body $bytes
}
function Sql1([string]$q) { ((& $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou -N -B -e $q 2>$null) -join '') }

$pass = 0; $fail = 0
function Check($desc, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ('  [PASS] ' + $desc) -ForegroundColor Green }
  else { $script:fail++; Write-Host ('  [FAIL] ' + $desc + '  ' + $detail) -ForegroundColor Red }
}

$marker = 'ZQ' + (Get-Random -Minimum 1000 -Maximum 9999)
$searchName = '相思湖' + $marker
$userName = '手工点' + $marker

$proc = Start-Process -FilePath $mvn -ArgumentList '-B','-o','spring-boot:run','-Dspring-boot.run.profiles=dev,local' `
  -WorkingDirectory (Join-Path $root 'ershou-server') -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $root '.build\searchhit.log') `
  -RedirectStandardError (Join-Path $root '.build\searchhit.err.log')
for ($i = 1; $i -le 90; $i++) {
  Start-Sleep -Seconds 2
  try { $p = Invoke-RestMethod -Uri "$base/trade-points?campusId=2" -TimeoutSec 5; if ($null -ne $p.code) { break } } catch { }
}
Write-Host '测试实例就绪' -ForegroundColor Green

try {
  $phone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
  Post "$base/auth/sms-code" @{ phone = $phone } | Out-Null
  $login = Post "$base/auth/login" @{ phone = $phone; code = '123456'; schoolId = 1; campusId = 2 }
  $t = $login.data.token

  Write-Host ''
  Write-Host '=== 1~2. 搜索落库与幂等 ===' -ForegroundColor Cyan
  $first = Post "$base/trade-points/search-hit" `
    @{ campusId = 2; name = $searchName; lng = 110.42110; lat = 25.31260; guideText = '灵川县'; pointType = 3 } $t
  Check 'search-hit 落库成功' ($first.code -eq 0) ($first | ConvertTo-Json -Compress)
  Check '返回的点位 source=3（高德搜索落库）' ($first.data.source -eq 3) ('source=' + $first.data.source)
  Check '坐标原样保存' ($first.data.lng -eq 110.4211 -and $first.data.lat -eq 25.3126) ("lng=" + $first.data.lng)

  $second = Post "$base/trade-points/search-hit" @{ campusId = 2; name = $searchName; lng = 110.42110; lat = 25.31260 } $t
  Check '同名再落一次返回同一条 id（不重复插）' ($second.data.id -eq $first.data.id) ("first=" + $first.data.id + " second=" + $second.data.id)
  Check '库里只有 1 条同名记录' ((Sql1 "SELECT COUNT(*) FROM trade_point WHERE name='$searchName';") -eq '1') ('实际 ' + (Sql1 "SELECT COUNT(*) FROM trade_point WHERE name='$searchName';"))

  Write-Host ''
  Write-Host '=== 3~4. 用户自建点位仍是 source=2 且立即可见 ===' -ForegroundColor Cyan
  $mine = Post "$base/trade-points" @{ campusId = 2; name = $userName; lng = 110.41700; lat = 25.31100 } $t
  Check '自建点位 source=2' ($mine.data.source -eq 2) ('source=' + $mine.data.source)

  $list = @((Invoke-RestMethod -Uri "$base/trade-points?campusId=2").data)
  Check '列表能看到自建点位（免审核）' (@($list | Where-Object { $_.id -eq $mine.data.id }).Count -eq 1) '未出现'
  Check '列表**看不到**没挂商品的搜索落库点位（不刷屏）' (@($list | Where-Object { $_.id -eq $first.data.id }).Count -eq 0) '出现了'

  Write-Host ''
  Write-Host '=== 5. 搜索落库点位的 safetyScore 更低（不是推荐面交点）===' -ForegroundColor Cyan
  Check 'source=3 的安全分低于用户自建' ($first.data.safetyScore -lt $mine.data.safetyScore) ("search=" + $first.data.safetyScore + " user=" + $mine.data.safetyScore)

  Write-Host ''
  Write-Host '=== 6. 落库的点位确实进了"我的点位"（下次选点不用再搜）===' -ForegroundColor Cyan
  $myPoints = @((Invoke-RestMethod -Uri "$base/trade-points/my-points?campusId=2" -Headers @{ Authorization = "Bearer $t" }).data)
  Check 'my-points 里能找到刚落库的搜索点位' (@($myPoints | Where-Object { $_.id -eq $first.data.id }).Count -eq 1) '未找到'
}
finally {
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  $c = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
  if ($c) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
  Sql1 "DELETE FROM trade_point WHERE name IN ('$searchName','$userName');" | Out-Null
  Write-Host '已清理测试点位' -ForegroundColor DarkGray
}

Write-Host ''
if ($fail -eq 0) { Write-Host ('全部通过：' + $pass + ' 项断言') -ForegroundColor Green }
else { Write-Host ('通过 ' + $pass + ' 项，失败 ' + $fail + ' 项') -ForegroundColor Red }
exit $fail
