﻿﻿﻿﻿﻿﻿﻿# 验证移动端高德地图页（GET /api/v1/map/amap-html）
#
# 为什么单独验：这个页面是给 Flutter WebView 用的，出问题在 App 里只能看到白屏，
# 很难定位。所以先在真实浏览器里加载它，确认：
#   1. 接口返回 HTML、key 已注入（不是占位符）
#   2. 同一 origin 下相对路径 /api/v1/map/points 能通
#   3. 高德 JS 真的把地图画出来了（截图人工确认，本脚本只保证产出截图）
#
# 隔离：测试实例 8081 + Redis 1 号库
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\verify-amap-page.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$base = 'http://127.0.0.1:8081'

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
# 后端配置项是 ershou.amap.js-key（读环境变量 AMAP_JS_KEY），而 .env 里用的是 VITE_ 前缀
# （那个前缀是给 vite 注入前端用的），这里做一次映射
$env:AMAP_JS_KEY = $env:VITE_AMAP_JS_KEY
$env:AMAP_JS_SECURITY_CODE = $env:VITE_AMAP_JS_SECURITY_CODE
if (-not $env:AMAP_JS_KEY) { Write-Host '⚠️ 没从 .env 读到 VITE_AMAP_JS_KEY' -ForegroundColor Yellow }
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = 'C:\Program Files\Java\jdk-21\bin;' + $env:Path
$mvn = 'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'

$pass = 0; $fail = 0
function Check($desc, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ('  [PASS] ' + $desc) -ForegroundColor Green }
  else { $script:fail++; Write-Host ('  [FAIL] ' + $desc + '  ' + $detail) -ForegroundColor Red }
}

$proc = Start-Process -FilePath $mvn -ArgumentList '-B','-o','spring-boot:run','-Dspring-boot.run.profiles=dev,local' `
  -WorkingDirectory (Join-Path $root 'ershou-server') -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $root '.build\amappage.log') `
  -RedirectStandardError (Join-Path $root '.build\amappage.err.log')
for ($i = 1; $i -le 90; $i++) {
  Start-Sleep -Seconds 2
  try { $p = Invoke-RestMethod -Uri "$base/api/v1/trade-points?campusId=2" -TimeoutSec 5; if ($null -ne $p.code) { break } } catch { }
}
Write-Host '测试实例就绪' -ForegroundColor Green

try {
  Write-Host ''
  Write-Host '=== 1. amap-status（不下发 key，只报可用性）===' -ForegroundColor Cyan
  $st = Invoke-RestMethod -Uri "$base/api/v1/map/amap-status"
  Check 'code=0' ($st.code -eq 0) ($st | ConvertTo-Json -Compress)
  Check 'configured=true（key 已从环境变量读到）' ($st.data.configured -eq $true) ($st.data.hint)
  Check '响应里不含 key 本身' (($st | ConvertTo-Json -Compress) -notmatch [regex]::Escape($env:AMAP_JS_KEY)) '疑似泄露'

  Write-Host ''
  Write-Host '=== 2. amap-html（WebView 要加载的页面）===' -ForegroundColor Cyan
  $html = (Invoke-WebRequest -Uri "$base/api/v1/map/amap-html?campusId=2" -UseBasicParsing).Content
  Check '返回 HTML 长度正常（>2000 字符）' ($html.Length -gt 2000) ('实际 ' + $html.Length)
  Check 'key 占位符已被替换' ($html -notmatch '%JS_KEY%') '仍是占位符'
  Check '注入了真实 key' ($env:AMAP_JS_KEY -and $html.Contains($env:AMAP_JS_KEY)) '未注入'
  Check '注入了安全密钥' ($html -match 'securityJsCode') '未注入'
  Check 'campusId 已带入页面' ($html -match 'var CAMPUS_ID = 2;') '未带入'
  Check '含点位气泡渲染逻辑' ($html -match 'bubble' -and $html -match 'AMap.Marker') '缺渲染逻辑'
  Check '含 Flutter 桥接（FlutterBridge）' ($html -match 'FlutterBridge') '缺桥接'
  Check '气泡点击会回传 select 事件' ($html -match "type: 'select'") '缺事件回传'
  Check '点位数据走同源接口（相对路径）' ($html -match "fetch\('/api/v1/map/points") '不是相对路径'

  Write-Host ''
  Write-Host '=== 2.5 选点打点标记（pickable 时点地图必须出针）===' -ForegroundColor Cyan
  # 这个 bug 的现象是"点了地图什么都不出现"，用户没法确认自己点到哪了。
  # 成因：map.on('click') 里只 post 了坐标，**根本没画标记**。
  $pick = (Invoke-WebRequest -Uri "$base/api/v1/map/amap-html?campusId=2&pickable=true&wantRegeo=true" -UseBasicParsing).Content
  Check 'pickable 页面带打点标记的样式（.pickmark）' ($pick -match '\.pickmark') '缺样式'
  Check '点击处理里会调用打点函数' ($pick -match 'setPickMarker\(lng, lat\)') '点击没打点'
  Check '打点标记用 anchor: bottom-center（针尖对准坐标）' ($pick -match "anchor: 'bottom-center'") '锚点不对'
  Check '打点标记不拦截地图点击（pointer-events:none）' ($pick -match '\.pickmark\{[^}]*pointer-events:none') '会挡住点击'
  Check '暴露 __ershouPickMark 给 Flutter 调用' ($pick -match '__ershouPickMark') '缺钩子'
  Check '候选地点带 POI 坐标（拍板候选时把针挪过去）' ($pick -match 'lng: pois\[i\]\.location') '缺 POI 坐标'
  # 非选点页不该出现打点（避免地图页误打针）
  Check '非 pickable 页面同样带样式但点击被拦掉' ($html -match 'if \(!PICKABLE\) return;') '缺 PICKABLE 判断'

  Write-Host ''
  Write-Host '=== 3. 页面依赖的同源接口可用 ===' -ForegroundColor Cyan
  $pts = Invoke-RestMethod -Uri "$base/api/v1/map/points?campusId=2"
  Check '同源 /map/points 返回 code=0' ($pts.code -eq 0) '失败'
  Check ('返回点位 ' + @($pts.data.points).Count + ' 个') (@($pts.data.points).Count -gt 0) '空'
  Check 'mapConfig 带 center/zoom（移动端要用来居中）' ($null -ne $pts.data.mapConfig.center) ($pts.data.mapConfig | ConvertTo-Json -Compress)

  Write-Host ''
  Write-Host '=== 4. 真实浏览器渲染（截图人工确认）===' -ForegroundColor Cyan
  Get-Process chrome -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
  $shot = Join-Path $root '.build\amap-mobile-page.png'
  Remove-Item -Force $shot -ErrorAction SilentlyContinue
  $prof = Join-Path $root '.build\chrome-amappage'
  New-Item -ItemType Directory -Force -Path $prof | Out-Null
  for ($i = 1; $i -le 3; $i++) {
    # 用 localhost:8081 作为 origin，相对路径 fetch 才会打到同一个后端
    # ⚠️ 不要用反引号续行调用原生命令（&）：PowerShell 5.1 下反引号 + 双引号参数会解析失败，
    # 报"The string is missing the terminator"。改成先把参数放进数组再展开。
    $chromeArgs = @(
      '--headless=new', '--disable-gpu', '--no-first-run',
      ('--user-data-dir=' + $prof),
      '--window-size=420,860', '--virtual-time-budget=30000',
      ('--screenshot=' + $shot),
      ($base + '/api/v1/map/amap-html?campusId=2')
    )
    & 'C:\Program Files\Google\Chrome\Application\chrome.exe' @chromeArgs 2>$null
    if ((Test-Path $shot) -and (Get-Item $shot).Length -gt 3000) { break }
    Start-Sleep -Seconds 3
  }
  $size = if (Test-Path $shot) { (Get-Item $shot).Length } else { 0 }
  Check ('截图已产出（' + $size + ' B）：' + $shot) ($size -gt 3000) '截图失败'
}
finally {
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  $c = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
  if ($c) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
}

Write-Host ''
if ($fail -eq 0) { Write-Host ('全部通过：' + $pass + ' 项断言') -ForegroundColor Green }
else { Write-Host ('通过 ' + $pass + ' 项，失败 ' + $fail + ' 项') -ForegroundColor Red }
exit $fail
