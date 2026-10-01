# 验证：点位名称长度上限（30 字）与地图跟随（前端）
#
# 起点：用户反馈"地址名称有时候会超出上限，上限改为 30 个字"
# 后端侧断言：30 字通过、31 字被拦、超长被拦且提示明确
#
# 隔离：测试实例 8081 + Redis 1 号库
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\verify-name-limit.ps1
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
  if ($cond) { $script:pass++; Write-Host ('  [PASS] ' + $desc) -ForegroundColor Green }
  else { $script:fail++; Write-Host ('  [FAIL] ' + $desc + '  ' + $detail) -ForegroundColor Red }
}

$proc = Start-Process -FilePath $mvn -ArgumentList '-B', '-o', 'spring-boot:run', '-Dspring-boot.run.profiles=dev,local' `
  -WorkingDirectory (Join-Path $root 'ershou-server') -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $root '.build\namelimit.log') `
  -RedirectStandardError (Join-Path $root '.build\namelimit.err.log')
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

  # 30 个汉字
  $name30 = '一二三四五六七八九十' + '一二三四五六七八九十' + '一二三四五六七八九十'
  # 31 个汉字
  $name31 = $name30 + '多'
  Write-Host ''
  Write-Host ('=== 名称长度边界（30 通过 / 31 拦截；len30=' + $name30.Length + ' len31=' + $name31.Length + '）===') -ForegroundColor Cyan

  $ok = Post "$base/trade-points" @{ campusId = 2; name = $name30; lng = 110.41880; lat = 25.31330 } $t
  Check '30 字名称创建成功' ($ok.code -eq 0 -and $ok.data.name -eq $name30) ($ok | ConvertTo-Json -Compress)

  $over = Post "$base/trade-points" @{ campusId = 2; name = $name31; lng = 110.41890; lat = 25.31340 } $t
  Check '31 字名称被拦截' ($over.code -ne 0) ('code=' + $over.code)
  Check '提示里说明了上限是 30' ($over.message -match '30') ('message=' + $over.message)

  $way = Post "$base/trade-points" @{ campusId = 2; name = ('超' * 80); lng = 110.41900; lat = 25.31350 } $t
  Check '远超上限（80 字）同样被拦' ($way.code -ne 0) ('code=' + $way.code)

  # 短名照旧
  $short = Post "$base/trade-points" @{ campusId = 2; name = '相思湖'; lng = 110.42110; lat = 25.31260 } $t
  Check '自由短名（相思湖）仍可用' ($short.code -eq 0 -and $short.data.name -eq '相思湖') ($short | ConvertTo-Json -Compress)

  # 列表里名字没被截断
  $list = @((Invoke-RestMethod -Uri "$base/trade-points?campusId=2").data | Where-Object { $_.id -eq $ok.data.id })
  Check '列表返回的 30 字名称完整' ($list.Count -eq 1 -and $list[0].name.Length -eq 30) ('len=' + $(if ($list.Count) { $list[0].name.Length } else { 'n/a' }))
}
finally {
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  $conn = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
  if ($conn) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue }
  Sql1 "DELETE FROM trade_point WHERE campus_id=2 AND (CHAR_LENGTH(name) >= 30 OR name IN ('相思湖','一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十')); " | Out-Null
  Write-Host '已清理测试点位' -ForegroundColor DarkGray
}

Write-Host ''
if ($fail -eq 0) { Write-Host ('全部通过：' + $pass + ' 项断言') -ForegroundColor Green }
else { Write-Host ('通过 ' + $pass + ' 项，失败 ' + $fail + ' 项') -ForegroundColor Red }
exit $fail
