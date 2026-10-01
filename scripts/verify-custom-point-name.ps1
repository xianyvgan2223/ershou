# 验证：自定义名称的点位能正常创建（不被"必须是高德已有的地名"限制）
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://127.0.0.1:8080/api/v1'

function Post($url, $obj, $token) {
  $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 6 -Compress))
  $h = @{}
  if ($token) { $h['Authorization'] = "Bearer $token" }
  Invoke-RestMethod -Uri $url -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body $bytes
}

$phone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
Post "$base/auth/sms-code" @{ phone = $phone } | Out-Null
$login = Post "$base/auth/login" @{ phone = $phone; code = '123456'; schoolId = 1; campusId = 2 }
$t = $login.data.token

$pass = 0; $fail = 0
function Check($desc, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ('  [PASS] ' + $desc) -ForegroundColor Green }
  else { $script:fail++; Write-Host ('  [FAIL] ' + $desc + '  ' + $detail) -ForegroundColor Red }
}

Write-Host '=== 模拟用户在选点弹窗里自己填名字 ===' -ForegroundColor Cyan
# 这些都是高德上不一定有、或者叫法不同的"自定义名称"
$cases = @(
  @{ name = '相思湖';                     lng = 110.420100; lat = 25.314000; type = 3; guide = '图书馆东侧，人工湖边' },
  @{ name = '清苑11栋';                   lng = 110.415524; lat = 25.319077; type = 7 },
  @{ name = '三号门旁边那棵大榕树';        lng = 110.416000; lat = 25.310000; type = 9 },
  @{ name = 'C区快递柜(靠篮球场)';         lng = 110.417000; lat = 25.311000; type = 4 }
)

$ids = @()
foreach ($c in $cases) {
  $body = @{ campusId = 2; name = $c.name; lng = $c.lng; lat = $c.lat; pointType = $c.type }
  if ($c.guide) { $body.guideText = $c.guide }
  $r = Post "$base/trade-points" $body $t
  $ok = $r.code -eq 0 -and $r.data.name -eq $c.name
  Check ("自定义名称「" + $c.name + "」创建成功且名称原样保存") $ok ($r | ConvertTo-Json -Compress)
  if ($ok) { $ids += $r.data.id }
}

Write-Host ''
Write-Host '=== 列表里能否看到这些自定义名称 ===' -ForegroundColor Cyan
$list = @((Invoke-RestMethod -Uri "$base/trade-points?campusId=2").data)
foreach ($c in $cases) {
  $hit = @($list | Where-Object { $_.name -eq $c.name }).Count
  Check ("列表含「" + $c.name + "」") ($hit -ge 1) ("出现次数=" + $hit)
}

Write-Host ''
Write-Host '=== 清理 ===' -ForegroundColor Cyan
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$env:MYSQL_PWD = $env:MYSQL_PASSWORD
$names = ($cases | ForEach-Object { "'" + $_.name + "'" }) -join ','
& $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou -e "DELETE FROM trade_point WHERE name IN ($names);" 2>$null | Out-Null
docker exec ershou-redis redis-cli -a $env:REDIS_PASSWORD --no-auth-warning INCR 'ershou:map:points:ver' 2>&1 | Out-Null
Write-Host '  已删除测试点位并失效缓存' -ForegroundColor DarkGray

Write-Host ''
if ($fail -eq 0) { Write-Host ("全部通过：" + $pass + " 项断言") -ForegroundColor Green }
else { Write-Host ("通过 " + $pass + " 项，失败 " + $fail + " 项") -ForegroundColor Red }
exit $fail
