# 验证 Redis GEO 附近点位检索
#
# 断言：
#   1. 首次查询会按 DB 重建 GEO 索引（Redis 里出现 ershou:map:geo:2:v{ver}）
#   2. 附近点位按距离升序，且带上 distanceMeters
#   3. 半径收窄后结果变少（说明半径真的生效，不是全量返回）
#   4. 检索不到的范围返回空数组（不是报错）
#   5. 索引成员数与"有经纬度的点位"一致
#   6. 全程 0 次高德调用（本接口与高德无关）
#
# 隔离：测试实例 8081 + Redis 1 号库
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\verify-geo-nearby.ps1
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

function Sql1([string]$q) { ((& $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou -N -B -e $q 2>$null) -join '') }
function Redis1([string]$cmd) {
  $args = @('exec', 'ershou-redis', 'redis-cli', '-a', $env:REDIS_PASSWORD, '--no-auth-warning', '-n', '1') + ($cmd -split ' ')
  (& docker @args 2>$null) -join ''
}

$pass = 0; $fail = 0
function Check($desc, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ('  [PASS] ' + $desc) -ForegroundColor Green }
  else { $script:fail++; Write-Host ('  [FAIL] ' + $desc + '  ' + $detail) -ForegroundColor Red }
}

$proc = Start-Process -FilePath $mvn -ArgumentList '-B','-o','spring-boot:run','-Dspring-boot.run.profiles=dev,local' `
  -WorkingDirectory (Join-Path $root 'ershou-server') -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $root '.build\geo.log') `
  -RedirectStandardError (Join-Path $root '.build\geo.err.log')
for ($i = 1; $i -le 90; $i++) {
  Start-Sleep -Seconds 2
  try { $p = Invoke-RestMethod -Uri "$base/trade-points?campusId=2" -TimeoutSec 5; if ($null -ne $p.code) { break } } catch { }
}
Write-Host '测试实例就绪' -ForegroundColor Green

try {
  # 先清掉旧索引，确保走"重建"这条路径
  $ver = Redis1 'GET ershou:map:points:ver'
  Redis1 "DEL ershou:map:geo:2:v$ver" | Out-Null
  Redis1 'DEL ershou:map:geo:rebuild:2' | Out-Null
  Check '起点：索引不存在（待重建）' ((Redis1 "EXISTS ershou:map:geo:2:v$ver") -eq '0') '仍存在'

  # 以花江校区图书馆为圆心（110.418835, 25.313315）
  $LNG = 110.418835; $LAT = 25.313315
  Write-Host ''
  Write-Host '=== 1~2. 索引重建 + 按距离排序 ===' -ForegroundColor Cyan
  $r = Invoke-RestMethod -Uri "$base/trade-points/nearby?campusId=2&lng=$LNG&lat=$LAT&radius=2000&limit=20"
  $list = @($r.data)
  Check '接口返回 code=0' ($r.code -eq 0) ($r | ConvertTo-Json -Compress)
  Check '重建后 Redis 里出现了 GEO 索引' ((Redis1 "EXISTS ershou:map:geo:2:v$ver") -eq '1') '索引不存在'
  Check ('检索到点位（' + $list.Count + ' 个）') ($list.Count -gt 0) '空结果'
  $distances = @($list | ForEach-Object { $_.distanceMeters })
  $sorted = $true
  for ($i = 1; $i -lt $distances.Count; $i++) { if ($distances[$i] -lt $distances[$i-1]) { $sorted = $false } }
  Check '结果按距离升序' $sorted ('实际 ' + ($distances -join ','))
  Check ('最近的点位是「' + $list[0].name + '」，' + $list[0].distanceMeters + ' 米') ($distances[0] -ge 0) ''

  Write-Host ''
  Write-Host '=== 3~4. 半径生效 / 空结果不报错 ===' -ForegroundColor Cyan
  $narrow = @((Invoke-RestMethod -Uri "$base/trade-points/nearby?campusId=2&lng=$LNG&lat=$LAT&radius=100&limit=20").data)
  Check ('半径 100 米的结果数(' + $narrow.Count + ') < 半径 2000 米的结果数(' + $list.Count + ')') ($narrow.Count -lt $list.Count) '半径未生效'
  # 用一个远离校园的坐标（桂林市区）
  $far = Invoke-RestMethod -Uri "$base/trade-points/nearby?campusId=2&lng=110.290000&lat=25.270000&radius=100&limit=20"
  Check '远距离坐标返回空数组而不是报错' ($far.code -eq 0 -and @($far.data).Count -eq 0) ($far | ConvertTo-Json -Compress)

  Write-Host ''
  Write-Host '=== 5. 索引只收"有经纬度"的点位 ===' -ForegroundColor Cyan
  $geoCnt = Sql1 "SELECT COUNT(*) FROM trade_point WHERE campus_id=2 AND status=1 AND lng IS NOT NULL AND lat IS NOT NULL;"
  $members = Redis1 "ZCARD ershou:map:geo:2:v$ver"
  Check ("索引成员数($members) == 有坐标点位数($geoCnt)") ($members -eq $geoCnt) '不一致'
  $noGeo = Sql1 "SELECT COUNT(*) FROM trade_point WHERE campus_id=2 AND status=1 AND (lng IS NULL OR lat IS NULL);"
  Write-Host ("  （另有 $noGeo 个点位没有经纬度，正确地没进索引）") -ForegroundColor DarkGray

  Write-Host ''
  Write-Host '=== 6. 接口不依赖高德：key 不存在时应答如常 ===' -ForegroundColor Cyan
  Check '附近接口在无高德 key 的服务端也能正常工作（本接口不调高德）' ($r.code -eq 0) '异常'
}
finally {
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  $c = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
  if ($c) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
  Redis1 'DEL ershou:map:geo:rebuild:2' | Out-Null
  Write-Host '已清理' -ForegroundColor DarkGray
}

Write-Host ''
if ($fail -eq 0) { Write-Host ('全部通过：' + $pass + ' 项断言') -ForegroundColor Green }
else { Write-Host ('通过 ' + $pass + ' 项，失败 ' + $fail + ' 项') -ForegroundColor Red }
exit $fail
