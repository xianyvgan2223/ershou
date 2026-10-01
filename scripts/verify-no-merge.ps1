# 回归：同名点位不再被合并
#
# 背景：以前 createByUser 遇到同名点位会直接返回已有一条，用户"选了个新地址"却被
# 悄悄合并到旧点位上，看起来像没生效。现在每次都必须新建一条独立记录。
#
# 隔离：测试实例 8081 + Redis 1 号库
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\verify-no-merge.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
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
$env:SERVER_PORT = '8081'
$env:REDIS_DATABASE = '1'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = 'C:\Program Files\Java\jdk-21\bin;' + $env:Path
$mvn = 'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'

function Post($url, $obj, $token) {
  $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 6 -Compress))
  $h = @{}
  if ($token) { $h['Authorization'] = "Bearer $token" }
  Invoke-RestMethod -Uri $url -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body $bytes
}
function Sql1([string]$q) { (& $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou -N -B -e $q 2>$null) -join '' }

$pass = 0; $fail = 0
function Check($desc, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ("  [PASS] " + $desc) -ForegroundColor Green }
  else { $script:fail++; Write-Host ("  [FAIL] " + $desc + "  " + $detail) -ForegroundColor Red }
}

$proc = Start-Process -FilePath $mvn -ArgumentList '-B', '-o', 'spring-boot:run', '-Dspring-boot.run.profiles=dev,local' `
  -WorkingDirectory (Join-Path $root 'ershou-server') -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $root '.build\nomerge-server.log') `
  -RedirectStandardError (Join-Path $root '.build\nomerge-server.err.log')
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

  $name = '同名不合并-' + (Get-Random -Minimum 1000 -Maximum 9999)
  Write-Host ''
  Write-Host ("=== 用同一个名字「" + $name + "」建两次（坐标不同） ===") -ForegroundColor Cyan

  $a = Post "$base/trade-points" @{ campusId = 2; name = $name; lng = 110.41910; lat = 25.31210; pointType = 3 } $t
  $b = Post "$base/trade-points" @{ campusId = 2; name = $name; lng = 110.42020; lat = 25.31320; pointType = 3 } $t

  Check '第一次创建成功' ($a.code -eq 0 -and $a.data.id -gt 0) ($a | ConvertTo-Json -Compress)
  Check '第二次创建成功' ($b.code -eq 0 -and $b.data.id -gt 0) ($b | ConvertTo-Json -Compress)
  Check '两次拿到的是不同点位 id（没有被合并）' ($a.data.id -ne $b.data.id) ("a=" + $a.data.id + " b=" + $b.data.id)
  Check '第二次的坐标是自己的（没被第一条覆盖）' ($b.data.lng -eq 110.4202 -and $b.data.lat -eq 25.3132) ("lng=" + $b.data.lng + " lat=" + $b.data.lat)

  $inDb = Sql1 "SELECT COUNT(*) FROM trade_point WHERE campus_id=2 AND name='$name';"
  Check '数据库里确实有两条同名记录' ($inDb -eq '2') ("实际 = " + $inDb)

  $lst = @((Invoke-RestMethod -Uri "$base/trade-points?campusId=2").data | Where-Object { $_.name -eq $name })
  Check '列表接口返回两条（地图上会出现两个标记）' ($lst.Count -eq 2) ("实际 = " + $lst.Count)

  # 顺带确认 suggest 接口也不合并
  $c = Post "$base/trade-points/suggest" @{ campusId = 2; name = $name; lng = 110.42130; lat = 25.31430 } $t
  Check 'suggest 接口同样不合并' ($c.data.id -ne $a.data.id -and $c.data.id -ne $b.data.id) ("c=" + $c.data.id)

  Write-Host ''
  Write-Host ("  三条记录 id = " + $a.data.id + " / " + $b.data.id + " / " + $c.data.id) -ForegroundColor DarkGray
}
finally {
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  $conn = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
  if ($conn) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue }
  if ($name) {
    & $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou -e "DELETE FROM trade_point WHERE name LIKE '同名不合并-%';" 2>$null | Out-Null
  }
  Write-Host '已清理测试点位并关闭测试实例' -ForegroundColor DarkGray
}

Write-Host ''
if ($fail -eq 0) { Write-Host ("全部通过：" + $pass + " 项断言") -ForegroundColor Green }
else { Write-Host ("通过 " + $pass + " 项，失败 " + $fail + " 项") -ForegroundColor Red }
exit $fail
