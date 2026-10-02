
# =============================================================
# 验收：商品有效期 7 天 + 到期自动下架 + 一键重新上架
#
# 背景（修复前）：
#   ItemService 里写的是 `DEFAULT_HOURS_VALID = 60 * 24`，名字是"小时"，
#   用的却是 `plusMinutes(...)` —— 商品实际只挂 24 小时，而 schema 注释与文档
#   一直写着 60 天。单位写错且没有测试覆盖，挂了很久没人发现。
#
# 覆盖：
#   1. 发布后 expireAt = 发布时刻 + 7 天（这条断言本身就是那个单位 bug 的回归）
#   2. 到期自动下架：在售 → 已过期(7)；已锁定(3) 的还要把「待面交」单一起作废
#   3. 一键重新上架：已过期 / 已下架可重挂；已售出、审核未通过、别人的商品都不行
#   4. 页面提示：上架闲置页必须写明 7 天规则
#
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\verify-item-validity.ps1
# 前置：**必须用一个 expire-cron 很短的测试实例**，脚本自己会起停（端口 8081），
#       所以跑之前先腾空 8081；MySQL 可访问；.build/test-upload.jpg 存在
# 注意：本文件必须带 UTF-8 BOM，否则 PS 5.1 会按 GBK 读，中文串全部报语法错
# =============================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$MYSQL = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$base = 'http://127.0.0.1:8081/api/v1'
$origin = 'http://127.0.0.1:8081'
$mvn = 'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'

[System.IO.File]::ReadAllLines((Join-Path $root '.env'), [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
  $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
  if (-not $k -or -not $v) { return }
  Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
}
$env:MYSQL_PWD = $env:MYSQL_PASSWORD

$pass = 0; $fail = 0
function Check($name, $actual, $expected) {
  if ("$actual" -eq "$expected") { $script:pass++; Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
  else { $script:fail++; Write-Host ("  [FAIL] " + $name + "  期望=" + $expected + "  实际=" + $actual) -ForegroundColor Red }
}
function CheckTrue($name, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
  else { $script:fail++; Write-Host ("  [FAIL] " + $name + "  " + $detail) -ForegroundColor Red }
}
function Sql1([string]$q) { ((& $MYSQL --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u ershou -D ershou -N -B -e $q 2>$null) -join '') }
function Sql([string]$q) { & $MYSQL --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u ershou -D ershou -e $q 2>$null | Out-Null }
function Req($method, $url, $obj, $token) {
  $h = @{}
  if ($token) { $h['Authorization'] = 'Bearer ' + $token }
  $a = @{ Uri = $url; Method = $method; Headers = $h; ContentType = 'application/json; charset=utf-8' }
  if ($obj) { $a['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 6 -Compress)) }
  try { Invoke-RestMethod @a } catch { @{ code = -1; message = $_.Exception.Message } }
}

# 起一个 expire-cron 每 10 秒的测试实例：真跑一遍定时任务，而不是只读代码。
# 端口和 cron 都写进配置文件：Start-Process 不会给含空格的参数加引号，
# 命令行里塞两个 --xxx 会被 Maven 当成两个参数而报未知选项（只剩一个参数才安全）。
$configFile = Join-Path $root '.build\validity-test.yml'
$yml = @'
# 仅验收脚本使用：另起一个实例并把到期扫描压到 10 秒一次，好在测试里真跑一遍定时任务
# （additional-location 的配置优先级高于 application.yml，所以这里的 port 会生效）
server:
  port: 8081
ershou:
  item:
    expire-cron: "*/10 * * * * *"
'@
[System.IO.File]::WriteAllText($configFile, $yml, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host '===== 验收：商品有效期 7 天 =====' -ForegroundColor Cyan
$busy = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
if ($busy) { Write-Host '  8081 已被占用，请先停掉那个实例' -ForegroundColor Red; exit 1 }
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = 'C:\Program Files\Java\jdk-21\bin;' + $env:Path
$cfgArg = '--spring.config.additional-location=file:' + ($configFile -replace '\\', '/')
$proc = Start-Process -FilePath $mvn `
  -ArgumentList '-B', '-o', 'spring-boot:run', '-Dspring-boot.run.profiles=dev,local', `
    ('-Dspring-boot.run.arguments=' + $cfgArg) `
  -WorkingDirectory (Join-Path $root 'ershou-server') -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $root '.build\validity.log') `
  -RedirectStandardError (Join-Path $root '.build\validity.err.log')
$ready = $false
for ($i = 1; $i -le 90; $i++) {
  Start-Sleep -Seconds 2
  try { $p = Invoke-RestMethod -Uri "$base/categories" -TimeoutSec 5; if ($null -ne $p.code) { $ready = $true; break } } catch { }
}
if (-not $ready) {
  Write-Host '  测试实例启动失败，看 .build\validity.log 与 validity.err.log' -ForegroundColor Red
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  exit 1
}
Write-Host '  测试实例就绪（expire-cron 每 10 秒）' -ForegroundColor Green

$created = @()
try {
  # ---------- 0. 登录 ----------
  $ps = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)   # 卖家
  $pb = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)   # 买家
  Req POST "$base/auth/sms-code" @{ phone = $ps } $null | Out-Null
  $S = (Req POST "$base/auth/login" @{ phone = $ps; code = '123456'; schoolId = 1; campusId = 1 } $null).data
  Req POST "$base/auth/sms-code" @{ phone = $pb } $null | Out-Null
  $B = (Req POST "$base/auth/login" @{ phone = $pb; code = '123456'; schoolId = 1; campusId = 1 } $null).data
  $st = $S.token; $bt = $B.token
  Write-Host ("  卖家 id=" + $S.user.id + "  买家 id=" + $B.user.id) -ForegroundColor DarkGray

  $imgPath = Join-Path $root '.build\test-upload.jpg'
  $up = (& curl.exe -s -X POST "$base/files/upload?biz=item" -H "Authorization: Bearer $st" -F "file=@$imgPath") | ConvertFrom-Json
  $img = $up.data.url
  function NewItem($title) {
    $r = Req POST "$base/items" @{ title = $title; description = '验收用'; categoryId = 1; conditionLevel = 3; price = 8.8; images = @($img) } $st
    return $r.data.itemId
  }

  # ---------- 1. 有效期 = 7 天 ----------
  Write-Host ''
  Write-Host '=== 1. 发布后有效期是 7 天（单位 bug 回归）===' -ForegroundColor Cyan
  $item1 = NewItem '有效期验收-7天'
  $created += $item1
  $detail = (Req GET "$base/items/$item1" $null).data
  CheckTrue '详情下发 expireAt' ($null -ne $detail.expireAt) ("expireAt=" + $detail.expireAt)
  $pubAt = [datetime]::Parse(($detail.publishedAt -replace 'T', ' '))
  $expAt = [datetime]::Parse(($detail.expireAt -replace 'T', ' '))
  $days = [math]::Round(($expAt - $pubAt).TotalDays, 3)
  Check '有效期 = 7 天（不是 24 小时）' $days 7
  # 重新取一次详情，确认真实落在库里而不是只有返回体对
  Check '库里 expire_at = published_at + 7 天' (Sql1 "select timestampdiff(day, published_at, expire_at) from item where id=$item1") 7
  # 「我的发布」列表也要带 expireAt，否则倒计时没法显示
  $mine = (Req GET "$base/items/mine?pageNum=1&pageSize=50" $null $st).data.records | Where-Object { $_.id -eq $item1 }
  CheckTrue '我的发布列表带 expireAt' ($null -ne $mine.expireAt) ("expireAt=" + $mine.expireAt)

  # ---------- 2. 到期自动下架 ----------
  Write-Host ''
  Write-Host '=== 2. 到期自动下架（在售 / 已锁定）===' -ForegroundColor Cyan
  # 2a. 在售 → 已过期
  $item2 = NewItem '有效期验收-在售到期'
  $created += $item2
  Sql "update item set status=2, expire_at=date_sub(now(), interval 1 minute) where id=$item2;"
  $waited = 0
  while ($waited -lt 40 -and (Sql1 "select status from item where id=$item2") -ne '7') { Start-Sleep -Seconds 2; $waited += 2 }
  Check '在售到期 → status=7 已过期' (Sql1 "select status from item where id=$item2") 7

  # 2b. 已锁定（有「待面交」单）→ 商品过期，同时那张单必须被作废
  $item3 = NewItem '有效期验收-锁定时到期'
  $created += $item3
  $o = (Req POST "$base/meetup-orders" @{ itemId = $item3 } $bt).data.orders[0].orderId
  Req POST "$base/meetup-orders/$o/accept" @{} $st | Out-Null
  Check '商品已锁定 status=3' (Sql1 "select status from item where id=$item3") 3
  Check '面交单 status=2 待面交' (Sql1 "select status from meetup_order where id=$o") 2
  $todoBefore = (Req GET "$base/meetup-orders/todo-count" $null $bt).data.asBuyer
  Check '买家此时有待办（红点）' $todoBefore 1
  Sql "update item set expire_at=date_sub(now(), interval 1 minute) where id=$item3;"
  $waited = 0
  while ($waited -lt 40 -and (Sql1 "select status from item where id=$item3") -ne '7') { Start-Sleep -Seconds 2; $waited += 2 }
  Check '锁定中的商品到期 → status=7' (Sql1 "select status from item where id=$item3") 7
  Check '挂着的待面交单被一起作废(5)' (Sql1 "select status from meetup_order where id=$o") 5
  CheckTrue '作废原因写清了是到期下架' ((Sql1 "select cancel_reason from meetup_order where id=$o") -like '*有效期自动下架*') (Sql1 "select cancel_reason from meetup_order where id=$o")
  Check '买家红点清零' (Req GET "$base/meetup-orders/todo-count" $null $bt).data.asBuyer 0

  # ---------- 3. 一键重新上架 ----------
  Write-Host ''
  Write-Host '=== 3. 已过期 / 已下架 可以一键重新上架 ===' -ForegroundColor Cyan
  $re = Req POST "$base/items/$item2/relist" @{} $st
  Check '重新上架返回 0' $re.code 0
  Check '回到在售 status=2' (Sql1 "select status from item where id=$item2") 2
  Check '有效期重新算满 7 天' (Sql1 "select timestampdiff(day, now(), expire_at) from item where id=$item2") 7
  CheckTrue '发布时间被刷新（否则会一直埋在列表最后）' ((Sql1 "select timestampdiff(second, published_at, now()) from item where id=$item2") -lt 60) (Sql1 "select published_at from item where id=$item2")
  Check '已是在售时再点重新上架 → 30006' (Req POST "$base/items/$item2/relist" @{} $st).code 30006

  # 自己下架(5) 也能重新上架
  Sql "update item set status=5 where id=$item2;"
  Check '已下架(5) 可以重新上架' (Req POST "$base/items/$item2/relist" @{} $st).code 0
  Check '回到在售 status=2' (Sql1 "select status from item where id=$item2") 2

  # 不能重新上架的情形
  Sql "update item set status=4 where id=$item2;"
  Check '已售出不能重新上架 → 30006' (Req POST "$base/items/$item2/relist" @{} $st).code 30006
  Sql "update item set status=6 where id=$item2;"
  Check '审核未通过不能重新上架 → 30006' (Req POST "$base/items/$item2/relist" @{} $st).code 30006
  Sql "update item set status=8 where id=$item2;"
  Check '违规下架不能重新上架 → 30006' (Req POST "$base/items/$item2/relist" @{} $st).code 30006
  Sql "update item set status=7 where id=$item2;"
  Check '别人的商品不能重新上架 → 30005' (Req POST "$base/items/$item2/relist" @{} $bt).code 30005

  # ---------- 4. 上架闲置页的 7 天提示 ----------
  Write-Host ''
  Write-Host '=== 4. 上架闲置页必须有 7 天提示 ===' -ForegroundColor Cyan
  $web = 'http://localhost:5174'
  $chrome = @('C:\Program Files\Google\Chrome\Application\chrome.exe',
              'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe') |
    Where-Object { Test-Path $_ } | Select-Object -First 1
  if (-not $chrome) {
    Write-Host '  找不到 Chrome，跳过页面检查' -ForegroundColor Yellow
  } else {
    $probe = Join-Path $root '.build\_validity-probe.mjs'
    $js = @'
import { existsSync } from 'node:fs'
const [WEB, TOKEN] = process.argv.slice(2)
const puppeteer = await import(new URL('../ershou-web/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js', import.meta.url).href)
const chrome = ['C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'].find((p) => existsSync(p))
const b = await puppeteer.default.launch({ executablePath: chrome, headless: 'new', args: ['--no-sandbox', '--disable-gpu'] })
const page = await b.newPage()
await page.setViewport({ width: 1440, height: 1100 })
// 注入登录态：不登录会被路由守卫弹回登录页
await page.goto(WEB, { waitUntil: 'domcontentloaded' })
await page.evaluate((t) => localStorage.setItem('ershou_token', t), TOKEN)
await page.goto(WEB + '/publish', { waitUntil: 'networkidle2' })
await page.waitForFunction(() => !!document.querySelector('.publish'), { timeout: 20000 })
await new Promise((r) => setTimeout(r, 1500))
const text = await page.evaluate(() => document.body.innerText)
const notice = await page.$eval('.notice', (e) => e.innerText).catch(() => '')
console.log(JSON.stringify({
  hasNotice: /7\s*天/.test(notice),
  notice: notice.replace(/\s+/g, ' ').trim(),
  mentionsRelist: notice.includes('重新上架'),
  barTip: /7\s*天/.test(text),
}))
await page.screenshot({ path: process.env.SHOT ?? 'publish-redesign.png', fullPage: true })
await b.close()
'@
    [System.IO.File]::WriteAllText($probe, $js, (New-Object System.Text.UTF8Encoding($false)))
    $out = & node $probe $web $st 2>&1 | Select-Object -Last 1
    $info = $out | ConvertFrom-Json
    CheckTrue '上架页有「7 天」提示' $info.hasNotice ("notice=" + $info.notice)
    CheckTrue '提示里说明了可以重新上架' $info.mentionsRelist ("notice=" + $info.notice)
    Write-Host ("  提示文案：" + $info.notice) -ForegroundColor DarkGray
    Remove-Item $probe -Force -ErrorAction SilentlyContinue
  }
} finally {
  # ---------- 清理 ----------
  foreach ($id in $created) {
    Sql "delete from meetup_order where item_id=$id;"
    Sql "delete from item where id=$id;"
  }
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  $c = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
  if ($c) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
  Write-Host ''
  Write-Host ("  已清理 " + $created.Count + " 件验收商品并停掉测试实例") -ForegroundColor DarkGray
}

Write-Host ''
if ($fail -eq 0) { Write-Host ("===== 全部通过：" + $pass + " 项断言 =====") -ForegroundColor Green; exit 0 }
else { Write-Host ("===== 失败 " + $fail + " 项（通过 " + $pass + "）=====") -ForegroundColor Red; exit 1 }
