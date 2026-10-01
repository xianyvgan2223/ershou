# 前端验证：造一个新面交点，截图看地图页是否显示它 + 商品定位是否只留一个点位
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$base = 'http://127.0.0.1:8080/api/v1'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'

[System.IO.File]::ReadAllLines((Join-Path $root '.env'), [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
  $_ = $_.TrimStart([char]0xFEFF).Trim()
  # .env 里有的变量把注释写在同一行（例：`# 说明... VITE_AMAP_JS_KEY=xxx`），
  # 直接 split 会把 key 读成注释文本，所以先砍掉 # 之后的内容。
  $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
  if (-not $k -or -not $v) { continue }
  Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
}
$env:MYSQL_PWD = $env:MYSQL_PASSWORD

function Post($url, $obj, $token) {
  $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 6 -Compress))
  $h = @{}
  if ($token) { $h['Authorization'] = "Bearer $token" }
  Invoke-RestMethod -Uri $url -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body $bytes
}

# 1) 测试账号
$phone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
Post "$base/auth/sms-code" @{ phone = $phone } | Out-Null
$login = Post "$base/auth/login" @{ phone = $phone; code = '123456'; schoolId = 1; campusId = 2 }
$token = $login.data.token
Write-Host ("测试账号 " + $phone + " 登录成功")

# 2) 造一个新面交点（等价于发布页"地图上新增地点"）
$name = '发布新点-' + (Get-Random -Minimum 1000 -Maximum 9999)
$new = Post "$base/trade-points" @{ campusId = 2; name = $name; lng = 110.4212; lat = 25.3169; pointType = 2; guideText = '发布流程新增' } $token
Write-Host ("已新增面交点 " + $new.data.name + " (id=" + $new.data.id + ")")

# 3) 造一个带该点位面交点的商品，用于 ?itemId= 焦点模式验证
$pub = Post "$base/items" @{
  title = '焦点验证商品'; description = '仅用于截图验证'; categoryId = 4; conditionLevel = 2
  price = 12.0; negotiable = 1; stock = 1; isUnique = 1; images = @('/ershou/demo/v.jpg'); preferPointId = $new.data.id
} $token
$itemId = $pub.data.itemId
Write-Host ("已发布验证商品 id=" + $itemId)

try {
  Start-Sleep -Seconds 3
  # Chrome 无头截图偶发不产出文件，重试几次
  function Shoot([string]$profileName, [string]$shotPath, [string]$url) {
    for ($i = 1; $i -le 4; $i++) {
      $prof = Join-Path $root ('.build\' + $profileName)
      New-Item -ItemType Directory -Force -Path $prof | Out-Null
      Remove-Item -Force $shotPath -ErrorAction SilentlyContinue
      & $chrome --headless=new --disable-gpu --no-first-run "--user-data-dir=$prof" `
        --window-size=1500,950 --virtual-time-budget=30000 --screenshot="$shotPath" $url 2>$null
      if (Test-Path $shotPath) { return (Get-Item $shotPath).Length }
      Start-Sleep -Seconds 3
    }
    return 0
  }

  $shot1 = Join-Path $root '.build\v-all.png'
  Write-Host ("  截图1（全部点位）: " + (Shoot 'chrome-v-all' $shot1 "http://localhost:5173/token-set2.html?t=$token&to=/map") + " B")

  $shot2 = Join-Path $root '.build\v-focus.png'
  Write-Host ("  截图2（只看这件商品）: " + (Shoot 'chrome-v-focus' $shot2 "http://localhost:5173/token-set2.html?t=$token&to=/map%3FitemId%3D$itemId") + " B")
}
finally {
  # 6) 清理
  & $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou -e "DELETE FROM item WHERE id=$itemId; DELETE FROM trade_point WHERE id=$($new.data.id);" 2>$null | Out-Null
  docker exec ershou-redis redis-cli -a $env:REDIS_PASSWORD --no-auth-warning INCR 'ershou:map:points:ver' 2>&1 | Out-Null
  Write-Host '已清理验证数据并失效缓存'
}
