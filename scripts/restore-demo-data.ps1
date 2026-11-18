﻿<#
.SYNOPSIS
  恢复演示数据到"可演示"状态：把到期下架的演示商品重新上架，并清掉遗留的测试商品。

.DESCRIPTION
  为什么需要它（2026-10-10 发现）：

  1. **演示商品全部到期了**。商品默认挂 7 天，10-02 种下的 9 件演示商品在
     10-09 16:54 被自动过期任务置成「已过期(7)」—— 这是**功能正常**，
     但结果是首页只剩 1 件商品，看起来像"数据丢了"。
     重新上架走的是 relist 路径：只重置 7 天有效期，**不重过机审、不占发布额度**。

  2. **验收脚本留下的测试商品**。例如 `后台验收商品1-396048 加微信转账有优惠`
     这类名字（含禁售词，所以有的停在「违规下架」）。它们是测试产物，
     混在演示数据里会让用户以为系统里有别人的脏数据。

用法：
  powershell -ExecutionPolicy Bypass -File .\scripts\restore-demo-data.ps1            # 干跑，只报告
  powershell -ExecutionPolicy Bypass -File .\scripts\restore-demo-data.ps1 -Apply     # 真的执行
#>
param(
  [switch]$Apply
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Za-z_]+=' } | ForEach-Object {
    $line = $_.TrimStart([char]0xFEFF).Trim()
    $k, $v = (($line -split '#', 2)[0]) -split '=', 2
    if ($k -and $v) { Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim() }
  }
}
if (-not $env:MYSQL_PASSWORD -or -not $env:REDIS_PASSWORD) {
  Write-Host '缺少 MYSQL_PASSWORD / REDIS_PASSWORD：请先在仓库根 .env 中配置' -ForegroundColor Red
  exit 1
}
$MYSQL = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$RedisPwd = $env:REDIS_PASSWORD
$env:MYSQL_PWD = $env:MYSQL_PASSWORD
$API = 'http://127.0.0.1:8080/api/v1'

function Invoke-Sql([string]$sql, [switch]$Table) {
  $args = @('-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '--default-character-set=utf8mb4')
  if ($Table) { $args += '-t' } else { $args += @('-N', '-B') }
  $args += @('-e', "use ershou; $sql")
  & $MYSQL @args 2>&1
}

$mode = if ($Apply) { '执行' } else { '干跑（加 -Apply 才会真的改）' }
Write-Host "=== 恢复演示数据：$mode ===" -ForegroundColor Cyan
Write-Host ''

# ---------- 1. 报告当前状态 ----------
Write-Host '当前商品状态分布：' -ForegroundColor DarkGray
Invoke-Sql 'SELECT status, COUNT(*) AS 件数 FROM item GROUP BY status ORDER BY status;' -Table | ForEach-Object { "  $_" }

# 演示商品：按标题识别（10-02 种下的那批），避免误伤用户自己发的东西
$demoTitles = @(
  '高等数学 同济第七版 上下册',
  '小米台灯 护眼版 几乎全新',
  '考研英语真题 黄皮书 全套',
  '捷安特 ATX660 山地车 27速',
  '宿舍小冰箱 40L 静音款',
  '篮球 斯伯丁 7号 室内外',
  'iPad 第九代 64G WiFi 版',
  '民谣吉他 41寸 入门款',
  '冬季羽绒服 男款 L 码'
)
$quoted = ($demoTitles | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ','
$expiredDemo = (Invoke-Sql "SELECT GROUP_CONCAT(id) FROM item WHERE title IN ($quoted) AND status = 7;" | Out-String).Trim()
# ⚠️ mysql -N -B 无结果时返回字面量 "NULL"，必须转成空串，否则 if 会当成真值
if ($expiredDemo -eq "NULL") { $expiredDemo = "" }

# 测试残留：后台验收脚本造的（标题里带"后台验收"或"验收商品"）
$testIds = (Invoke-Sql "SELECT GROUP_CONCAT(id) FROM item WHERE title LIKE '后台验收%' OR title LIKE '%验收商品%' OR title LIKE '编辑功能测试商品%' OR title LIKE '合并点位验证%';" | Out-String).Trim()
if ($testIds -eq "NULL") { $testIds = "" }

Write-Host ''
Write-Host '计划动作：' -ForegroundColor DarkGray
Write-Host ("  ① 重新上架到期的演示商品： " + $(if ($expiredDemo) { $expiredDemo } else { '（没有）' }))
Write-Host ("  ② 删除遗留的测试商品：     " + $(if ($testIds) { $testIds } else { '（没有）' }))

if (-not $Apply) {
  Write-Host ''
  Write-Host '这是干跑。确认上面的 id 都是预期的之后，加 -Apply 执行。' -ForegroundColor Yellow
  exit 0
}

# ---------- 2. 重新上架演示商品 ----------
if ($expiredDemo) {
  Write-Host ''
  Write-Host '① 重新上架演示商品（relist：重置 7 天，不重过机审、不占额度）' -ForegroundColor Cyan

  # relist 是"卖家操作"，必须用**该商品的卖家**登录，不能随便用一个号 ——
  # 用错号会返回 30005「只能操作自己发布的商品」（第一版就是这么踩的）。
  # ⚠️ 另外 Invoke-RestMethod 对"HTTP 200 + 业务错误码"**不会抛异常**，
  #    必须自己判 code，否则会打印出"好像成功了"的假象。
  $loginPhones = @('13800000008', '13800000002', '13800000005', '13800000006')
  $tokens = @{}
  foreach ($phone in $loginPhones) {
    try {
      $keys = & docker exec ershou-redis redis-cli -a $RedisPwd --no-auth-warning KEYS "ershou:sms:limit:${phone}:*" 2>$null |
        Where-Object { $_ -like 'ershou:*' }
      if ($keys) { & docker exec ershou-redis redis-cli -a $RedisPwd --no-auth-warning DEL @keys 2>&1 | Out-Null }
      Invoke-RestMethod "$API/auth/sms-code" -Method Post -ContentType 'application/json' `
        -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 10 | Out-Null
      $login = Invoke-RestMethod "$API/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ phone = $phone; code = '123456'; schoolId = 1; campusId = 1 } | ConvertTo-Json) -TimeoutSec 10
      if ($login.code -eq 0) {
        $tokens[[string]$login.data.user.id] = $login.data.token
        Write-Host ("  已登录 " + $phone + "（id " + $login.data.user.id + "）")
      }
    } catch {
      Write-Host ("  " + $phone + " 登录异常：" + $_.Exception.Message) -ForegroundColor DarkGray
    }
  }
  if (-not $tokens.Count) { Write-Host '  一个号都没登上，全部走 SQL 兜底' -ForegroundColor Yellow }

  # 每件商品用它自己的卖家账号去 relist
  foreach ($id in ($expiredDemo -split ',')) {
    $sellerId = (Invoke-Sql "SELECT seller_id FROM item WHERE id = $id;" | Out-String).Trim()
    $sellerToken = if ($tokens.ContainsKey($sellerId)) { $tokens[$sellerId] } else { $null }
    $done = $false
    if ($sellerToken) {
      try {
        $h = @{ Authorization = "Bearer $sellerToken" }
        $r = Invoke-RestMethod "$API/items/$id/relist" -Method Post -Headers $h `
          -ContentType 'application/json' -Body '{}' -TimeoutSec 10
        if ($r.code -eq 0) {
          Write-Host ("  商品 " + $id + "（卖家 " + $sellerId + "）→ 已重新上架")
          $done = $true
        } else {
          Write-Host ("  商品 " + $id + " → relist 返回 " + $r.code + "：" + $r.message) -ForegroundColor Yellow
        }
      } catch {
        Write-Host ("  商品 " + $id + " → relist 异常：" + $_.Exception.Message) -ForegroundColor Yellow
      }
    } else {
      Write-Host ("  商品 " + $id + "（卖家 " + $sellerId + "）→ 无该卖家登录态，走 SQL") -ForegroundColor DarkGray
    }
    if (-not $done) {
      # 兜底：直接重置（只改状态与有效期，语义与 relist 一致）
      Invoke-Sql "UPDATE item SET status = 2, expire_at = DATE_ADD(NOW(), INTERVAL 7 DAY) WHERE id = $id AND status = 7;" | Out-Null
      Write-Host ("  商品 " + $id + " → 已用 SQL 重置状态与有效期")
    }
  }
}

# ---------- 3. 清掉测试残留 ----------
if ($testIds) {
  Write-Host ''
  Write-Host '② 删除遗留的测试商品（连同收藏/购物车引用）' -ForegroundColor Cyan
  Invoke-Sql "DELETE FROM favorite WHERE item_id IN ($testIds); DELETE FROM cart_item WHERE item_id IN ($testIds); DELETE FROM item WHERE id IN ($testIds);" | Out-Null
  $left = (Invoke-Sql "SELECT COUNT(*) FROM item WHERE id IN ($testIds);" | Out-String).Trim()
  Write-Host ("  已删除，残留 " + $left + " 条")
}

# ---------- 4. 重建索引（改了状态必须重算，否则搜索与首页对不上）----------
Write-Host ''
Write-Host '③ 重建搜索索引' -ForegroundColor Cyan
# reindex 要求**运营权限**（普通卖家会拿到 10003 需要运营人员权限），所以单独用运营号登录
$adminToken = $null
try {
  $adminPhone = '13800000009'
  $keys = & docker exec ershou-redis redis-cli -a $RedisPwd --no-auth-warning KEYS "ershou:sms:limit:${adminPhone}:*" 2>$null |
    Where-Object { $_ -like 'ershou:*' }
  if ($keys) { & docker exec ershou-redis redis-cli -a $RedisPwd --no-auth-warning DEL @keys 2>&1 | Out-Null }
  Invoke-RestMethod "$API/auth/sms-code" -Method Post -ContentType 'application/json' `
    -Body (@{ phone = $adminPhone } | ConvertTo-Json) -TimeoutSec 10 | Out-Null
  $adminLogin = Invoke-RestMethod "$API/auth/login" -Method Post -ContentType 'application/json' `
    -Body (@{ phone = $adminPhone; code = '123456'; schoolId = 1; campusId = 1 } | ConvertTo-Json) -TimeoutSec 10
  if ($adminLogin.code -eq 0) { $adminToken = $adminLogin.data.token }
} catch {
  Write-Host ("  运营号登录异常：" + $_.Exception.Message) -ForegroundColor DarkGray
}

if (-not $adminToken) {
  Write-Host '  跳过：没拿到运营登录态（reindex 需要运营权限）' -ForegroundColor Yellow
} else {
  try {
    $h = @{ Authorization = "Bearer $adminToken" }
    $r = Invoke-RestMethod "$API/search/reindex?sync=true" -Method Post -Headers $h -TimeoutSec 90
    if ($r.code -eq 0) {
      Write-Host ("  索引文档数 = " + $r.data.visible + "（写入 " + $r.data.indexed + " 条，重建=" + $r.data.rebuilt + "）")
    } else {
      Write-Host ("  重建被拒：" + $r.code + " " + $r.message) -ForegroundColor Yellow
    }
  } catch {
    Write-Host ("  重建异常：" + $_.Exception.Message) -ForegroundColor Red
  }
}

Write-Host ''
Write-Host '恢复后的状态：' -ForegroundColor Green
Invoke-Sql 'SELECT status, COUNT(*) AS 件数 FROM item GROUP BY status ORDER BY status;' -Table | ForEach-Object { "  $_" }
try {
  $feed = Invoke-RestMethod "$API/items?pageNum=1&pageSize=20" -TimeoutSec 10
  Write-Host ("  首页可见商品数 = " + $feed.data.total)
} catch {
  Write-Host '  首页接口读取失败'
}
