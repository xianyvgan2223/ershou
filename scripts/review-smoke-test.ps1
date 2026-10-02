
# =============================================================
# 上架审核（运营后台「上架审核」页）端到端冒烟测试
# 覆盖：正常上架不拦截但入队 → 已审核后消失 → 改名重新入队 → 下架
#        → 违规二次申请上架必须审核 → 权限隔离 → 实时推送(WS) → 审计留痕
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\review-smoke-test.ps1 [-Base http://127.0.0.1:8080/api/v1]
# 前置：后端已启动；MySQL 可访问；.build/test-item.png 存在；本机 node 可用
# 注意：本文件必须带 UTF-8 BOM，否则 PS 5.1 会按 GBK 读，中文串全部报语法错
# =============================================================
param([string]$Base = 'http://127.0.0.1:8080/api/v1')

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$MYSQL = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$origin = $Base -replace '/api/v1/?$', ''

$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
    $_ = $_.TrimStart([char]0xFEFF).Trim()
    $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
    if (-not $k -or -not $v) { continue }
    Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
  }
}
$env:MYSQL_PWD = $env:MYSQL_PASSWORD

$script:fail = 0
function Check($name, $actual, $expected) {
  if ("$actual" -eq "$expected") { Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
  else { Write-Host ("  [FAIL] " + $name + "  期望=" + $expected + "  实际=" + $actual) -ForegroundColor Red; $script:fail++ }
}
function CheckTrue($name, $cond) {
  if ($cond) { Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
  else { Write-Host ("  [FAIL] " + $name) -ForegroundColor Red; $script:fail++ }
}
function DbScalar($sql) {
  # --default-character-set=utf8mb4：不指定时 mysql.exe 按控制台代码页输出，
  # 中文取值回到 PS 里就是乱码，跟期望值永远比不相等（这个坑很费时间，别删）
  $out = & $MYSQL -h 127.0.0.1 -P 3306 -u ershou --default-character-set=utf8mb4 -N -B -e "use ershou; $sql" 2>$null
  return ($out | Select-Object -First 1)
}
function Sql($sql) {
  & $MYSQL -h 127.0.0.1 -P 3306 -u ershou --default-character-set=utf8mb4 -e "use ershou; $sql" 2>&1 | Out-Null
}
function Post($url, $obj, $headers) {
  # 无 body 的接口要发空 JSON 而不是 null：ConvertTo-Json $null 出来是字符串 'null'，
  # 但调用方传 $null 时 GetBytes(null) 会直接抛异常（补登记接口就是这种）
  if ($null -eq $obj) { $obj = @{} }
  $json = $obj | ConvertTo-Json -Depth 6 -Compress
  if ($null -eq $json) { $json = '{}' }
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  if ($headers) { return Invoke-RestMethod -Uri $url -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $bytes }
  return Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json; charset=utf-8' -Body $bytes
}
function Get($url, $headers) {
  if ($headers) { return Invoke-RestMethod -Uri $url -Headers $headers }
  return Invoke-RestMethod -Uri $url
}
function Put($url, $obj, $headers) {
  $json = $obj | ConvertTo-Json -Depth 6 -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  return Invoke-RestMethod -Uri $url -Method Put -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $bytes
}

Write-Host ''
Write-Host '===== 上架审核 冒烟测试 =====' -ForegroundColor Cyan
Write-Host ("  Base = " + $Base) -ForegroundColor DarkGray

try { Invoke-RestMethod -Uri ($origin + '/actuator/health') -TimeoutSec 5 | Out-Null }
catch { Write-Host '后端未启动，请先运行 .\scripts\dev-server.ps1' -ForegroundColor Red; exit 1 }

# ---------- 0. 登录三个角色 ----------
Write-Host '=== 0. 登录 运营 / 卖家 / 买家 ===' -ForegroundColor Cyan
$adminPhone = '13800000009'; $sellerPhone = '13800000008'; $buyerPhone = '13800000007'
# 后端限"每个手机号每天 10 次验证码"，本脚本一天要跑很多轮，
# 先清掉计数，否则跑到第 4 轮就变成"登录失败：验证码错误"——很像验证码逻辑坏了，其实只是限流。
# ⚠️ 计数器 key 带日期后缀（ershou:sms:limit:<phone>:yyyyMMdd）：
#    直接 DEL ershou:sms:limit:<phone> 删的是个不存在的 key（返回 0），
#    看着"清理成功"其实计数器原封不动，所以必须按 pattern 删。
foreach ($p in @($adminPhone, $sellerPhone, $buyerPhone)) {
  $keys = @(& docker exec ershou-redis redis-cli -a $env:REDIS_PASSWORD --no-auth-warning KEYS "ershou:sms:limit:$p`:*" 2>$null |
            Where-Object { $_ -like 'ershou:*' })
  if ($keys.Count -gt 0) {
    & docker exec ershou-redis redis-cli -a $env:REDIS_PASSWORD --no-auth-warning DEL @keys 2>&1 | Out-Null
  }
}
Post "$Base/auth/sms-code" @{phone=$adminPhone} | Out-Null
$adminLogin = Post "$Base/auth/login" @{phone=$adminPhone;code='123456';schoolId=1;campusId=1}
$adminToken = $adminLogin.data.token; $adminId = $adminLogin.data.user.id
Post "$Base/auth/sms-code" @{phone=$sellerPhone} | Out-Null
$sellerLogin = Post "$Base/auth/login" @{phone=$sellerPhone;code='123456';schoolId=1;campusId=1}
$sellerToken = $sellerLogin.data.token; $sellerId = $sellerLogin.data.user.id
Post "$Base/auth/sms-code" @{phone=$buyerPhone} | Out-Null
$buyerLogin = Post "$Base/auth/login" @{phone=$buyerPhone;code='123456';schoolId=1;campusId=1}
$buyerToken = $buyerLogin.data.token; $buyerId = $buyerLogin.data.user.id
Write-Host ("  运营 id=$adminId  卖家 id=$sellerId  买家 id=$buyerId") -ForegroundColor DarkGray

Sql "update sys_user set role_flags = role_flags | 8 where id = $adminId;"
# 清掉本脚本上次留下的商品，保证可重复执行
Sql "delete from item_review where seller_id = $sellerId;"
Sql "delete from item where seller_id = $sellerId;"
Sql "update sys_user set publish_limit = 500 where id = $sellerId;"

$AH = @{ Authorization = "Bearer $adminToken" }
$SH = @{ Authorization = "Bearer $sellerToken" }
$BH = @{ Authorization = "Bearer $buyerToken" }

# ---------- 1. 权限隔离 ----------
Write-Host '=== 1. 权限隔离 ===' -ForegroundColor Cyan
Check '卖家访问 /admin/review/feed' (Get "$Base/admin/review/feed" $SH).code 10003
Check '买家访问 /admin/review/stats' (Get "$Base/admin/review/stats" $BH).code 10003
Check '未登录访问 /admin/review/feed' (Get "$Base/admin/review/feed" $null).code 10002
Check '运营访问 /admin/review/feed' (Get "$Base/admin/review/feed" $AH).code 0

# ---------- 2. 正常上架：不拦截，但立刻进审核页 ----------
Write-Host '=== 2. 正常上架不拦截（status=2）但自动入队 ===' -ForegroundColor Cyan
$imgPath = Join-Path $root '.build\test-item.png'
if (-not (Test-Path $imgPath)) { $imgPath = Join-Path $root '.build\test-upload.jpg' }
if (-not (Test-Path $imgPath)) { Write-Host '缺测试图片（.build\test-item.png 或 test-upload.jpg）' -ForegroundColor Red; exit 1 }
$up = (& curl.exe -s -X POST "$Base/files/upload?biz=item" -H "Authorization: Bearer $sellerToken" -F "file=@$imgPath") | ConvertFrom-Json
$img = $up.data.url

$p1 = Post "$Base/items" @{ title='上架审核测试A 自行车'; description='九成新，校内面交'; categoryId=3; conditionLevel=2; price=199; images=@($img); preferPointId=1 } $SH
$itemA = $p1.data.itemId
Check '商品A 直接上架（不拦截）status=2' $p1.data.status 2
Check '商品A 入库 status=2' (DbScalar "select status from item where id=$itemA") 2

$reviewA = DbScalar "select id from item_review where item_id=$itemA"
CheckTrue '商品A 自动进入上架审核队列' ($reviewA -ne '')
Check '队列记录 review_type=1（新上架）' (DbScalar "select review_type from item_review where item_id=$itemA") 1
Check '队列记录 status=0（待审核）' (DbScalar "select status from item_review where item_id=$itemA") 0
Check '上架快照已写入 last_live_title' (DbScalar "select last_live_title from item where id=$itemA") '上架审核测试A 自行车'
CheckTrue '上架快照已写入 last_live_desc_hash' ((DbScalar "select last_live_desc_hash from item where id=$itemA") -ne '')

$feed = Get "$Base/admin/review/feed?scope=pending&pageSize=100" $AH
$rowA = $feed.data.records | Where-Object { $_.itemId -eq $itemA }
CheckTrue '待审列表包含商品A' ($null -ne $rowA)
Check '商品A 可处置 actionable=true' $rowA.actionable $true
Check '商品A 审核类型文案' $rowA.reviewTypeText '新上架'
Check '商品A 不是已改名' $rowA.contentChanged $false

$stats = Get "$Base/admin/review/stats" $AH
CheckTrue '统计待审数 >= 1' ($stats.data.pending -ge 1)

# ---------- 3. 已审核 → 从待审列表消失 ----------
Write-Host '=== 3. 已审核 → 从待审列表消失（归档）===' -ForegroundColor Cyan
$rv = Post "$Base/admin/review/items/$itemA/reviewed" @{} $AH
Check '标记已审核成功' $rv.code 0
Check '队列记录 status=1' (DbScalar "select status from item_review where item_id=$itemA") 1
Check '处置动作 review_action=1（已审核）' (DbScalar "select review_action from item_review where item_id=$itemA") 1
Check '记录审核人' (DbScalar "select reviewer_id from item_review where item_id=$itemA") $adminId
CheckTrue '记录了处置时间' ((DbScalar "select reviewed_at from item_review where item_id=$itemA") -ne '')
Check '商品仍在售（已审核不改变商品状态）' (DbScalar "select status from item where id=$itemA") 2

$feedAfter = Get "$Base/admin/review/feed?scope=pending&pageSize=100" $AH
$stillThere = $feedAfter.data.records | Where-Object { $_.itemId -eq $itemA -and $_.status -eq 0 }
CheckTrue '已审核的商品不在待审中' ($null -eq $stillThere)
# 24 小时内仍可在"待审核 + 刚处置"视图里回看（否则审核员会以为点了没反应）
$visible = $feedAfter.data.records | Where-Object { $_.itemId -eq $itemA }
CheckTrue '已处置记录 24h 内仍可见（回看用）' ($null -ne $visible)

Check '重复标记已审核 → 61001' (Post "$Base/admin/review/items/$itemA/reviewed" @{} $AH).code 61001

$doneFeed = Get "$Base/admin/review/feed?scope=done&pageSize=100" $AH
CheckTrue '「已处置」视图能看到它' (($doneFeed.data.records | ForEach-Object { $_.itemId }) -contains $itemA)

# ---------- 4. 改名 → 重新入队 ----------
Write-Host '=== 4. 在售期间改名 → 重新回到审核页 ===' -ForegroundColor Cyan
Put "$Base/items/$itemA" @{ title='上架审核测试A 自行车（改名后）' } $SH | Out-Null
Check '改名后商品仍在售' (DbScalar "select status from item where id=$itemA") 2
Check '改名后重新入队 status=0' (DbScalar "select status from item_review where item_id=$itemA") 0
Check '队列 review_type=2（改名）' (DbScalar "select review_type from item_review where item_id=$itemA") 2
Check 'revision 已 +1' (DbScalar "select revision from item_review where item_id=$itemA") 2
CheckTrue '记录了内容变更时间' ((DbScalar "select content_changed_at from item_review where item_id=$itemA") -ne '')

$feedRenamed = Get "$Base/admin/review/feed?scope=pending&pageSize=100" $AH
$rowA2 = $feedRenamed.data.records | Where-Object { $_.itemId -eq $itemA }
CheckTrue '改名后重新出现在待审列表' ($null -ne $rowA2)
Check '标记为已改名' $rowA2.contentChanged $true
Check '列表标题是当前标题（不是入队快照）' $rowA2.title '上架审核测试A 自行车（改名后）'
Check '带入队时快照用于对比' $rowA2.titleSnapshot '上架审核测试A 自行车'

# 只改价格不算内容变更 → 不应该再入队（否则审核员被价格调整刷屏）
Post "$Base/admin/review/items/$itemA/reviewed" @{} $AH | Out-Null
Put "$Base/items/$itemA" @{ price=188 } $SH | Out-Null
Check '只改价格不重新入队' (DbScalar "select status from item_review where item_id=$itemA") 1

# ---------- 5. 下架 ----------
Write-Host '=== 5. 审核页「下架」 ===' -ForegroundColor Cyan
$p2 = Post "$Base/items" @{ title='上架审核测试B 违规风险'; description='描述正常'; categoryId=1; conditionLevel=3; price=10; images=@($img); preferPointId=1 } $SH
$itemB = $p2.data.itemId
Check '商品B 上架 status=2' $p2.data.status 2

$off = Post "$Base/admin/review/items/$itemB/offline" @{reason='标题含联系方式'} $AH
Check '审核页下架成功' $off.code 0
Check '商品B 状态=5（已下架，非违规）' (DbScalar "select status from item where id=$itemB") 5
Check '队列 review_action=2（已下架）' (DbScalar "select review_action from item_review where item_id=$itemB") 2
Check '下架原因写入备注' (DbScalar "select note from item_review where item_id=$itemB") '标题含联系方式'
# 下架不是"违规"：不写 audit_result（留空而不是留个 1 让人误以为刚审核通过）
Check '下架不写 audit_result（非违规）' (DbScalar "select ifnull(audit_result,'null') from item where id=$itemB") 'null'
Check '下架清掉锁库信息' (DbScalar "select ifnull(lock_user_id,'null') from item where id=$itemB") 'null'

$feedB = Get "$Base/admin/review/feed?scope=pending&pageSize=100" $AH
$rowB = $feedB.data.records | Where-Object { $_.itemId -eq $itemB -and $_.status -eq 0 }
CheckTrue '下架后不在待审列表' ($null -eq $rowB)

# 卖家重新上架 → 再次入队（type=3）
Post "$Base/items/$itemB/relist" @{} $SH | Out-Null
Check '重新上架后 status=2' (DbScalar "select status from item where id=$itemB") 2
Check '重新上架重新入队 status=0' (DbScalar "select status from item_review where item_id=$itemB") 0
Check '队列 review_type=3（重新上架）' (DbScalar "select review_type from item_review where item_id=$itemB") 3

# ---------- 6. 违规二次申请上架必须审核 ----------
Write-Host '=== 6. 违规商品二次申请上架：必须审核通过才回到在售 ===' -ForegroundColor Cyan
$p3 = Post "$Base/items" @{ title='上架审核测试C 待违规'; description='描述正常'; categoryId=1; conditionLevel=3; price=20; images=@($img); preferPointId=1 } $SH
$itemC = $p3.data.itemId
# 运营强制下架（违规）
Post "$Base/admin/items/$itemC/offline" @{reason='测试：违禁品'} $AH | Out-Null
Check '商品C 违规下架 status=8' (DbScalar "select status from item where id=$itemC") 8

# 卖家不能用普通「重新上架」复活违规商品
$r = Post "$Base/items/$itemC/relist" @{} $SH
Check '违规商品走 relist 被拒 30006' $r.code 30006

# 走「二次申请上架」→ 进入审核中(1)，不直接可见
$apply = Post "$Base/items/$itemC/apply-relist" @{} $SH
Check '二次申请上架成功' $apply.code 0
Check '申请后 status=1（审核中，未上架）' (DbScalar "select status from item where id=$itemC") 1
$feedC = Get "$Base/admin/review/feed?scope=all&pageSize=100" $AH
CheckTrue '审核中的商品不占上架审核队列' (($feedC.data.records | Where-Object { $_.itemId -eq $itemC -and $_.status -eq 0 }) -eq $null)
# 审核工作台里能看到它
$auditQueue = Get "$Base/admin/audit/items?status=1&keyword=上架审核测试C" $AH
CheckTrue '审核工作台能看到该申请' (($auditQueue.data.records | ForEach-Object { $_.id }) -contains $itemC)

# 运营通过 → 才回到在售，并以上架审核入队（type=4 曾违规）
Post "$Base/admin/audit/items/$itemC/approve" @{} $AH | Out-Null
Check '通过后 status=2' (DbScalar "select status from item where id=$itemC") 2
Check '通过后进入上架审核队列' (DbScalar "select status from item_review where item_id=$itemC") 0
Check '队列 review_type=4（违规后二次上架）' (DbScalar "select review_type from item_review where item_id=$itemC") 4

# 别人不能替卖家申请
$p4 = Post "$Base/items" @{ title='上架审核测试D 越权'; description='描述正常'; categoryId=1; conditionLevel=3; price=30; images=@($img); preferPointId=1 } $SH
$itemD = $p4.data.itemId
Post "$Base/admin/items/$itemD/offline" @{reason='测试：违规'} $AH | Out-Null
Check '买家替卖家申请 → 30005' (Post "$Base/items/$itemD/apply-relist" @{} $BH).code 30005
# 商品D 是违规下架(8)，先让卖家申请成 审核中(1)，此时再申请应被拒（不是"违规下架"状态了）
Post "$Base/items/$itemD/apply-relist" @{} $SH | Out-Null
Check '非违规状态申请二次上架 → 30006' (Post "$Base/items/$itemD/apply-relist" @{} $SH).code 30006

# ---------- 7. 批量处置 ----------
Write-Host '=== 7. 批量已审核 / 批量下架 ===' -ForegroundColor Cyan
$b1 = Post "$Base/items" @{ title='上架审核批量1'; description='描述正常'; categoryId=1; conditionLevel=3; price=11; images=@($img); preferPointId=1 } $SH
$b2 = Post "$Base/items" @{ title='上架审核批量2'; description='描述正常'; categoryId=1; conditionLevel=3; price=12; images=@($img); preferPointId=1 } $SH
$batch = Post "$Base/admin/review/batch/review" @{ids=@($b1.data.itemId,$b2.data.itemId)} $AH
Check '批量已审核 success=2' $batch.data.success 2
Check '批量项1 已审核' (DbScalar "select status from item_review where item_id=$($b1.data.itemId)") 1
Check '批量项2 已审核' (DbScalar "select status from item_review where item_id=$($b2.data.itemId)") 1

# 批量下架必须填原因
$b3 = Post "$Base/items" @{ title='上架审核批量3'; description='描述正常'; categoryId=1; conditionLevel=3; price=13; images=@($img); preferPointId=1 } $SH
$offNoReason = Post "$Base/admin/review/batch/offline" @{ids=@($b3.data.itemId)} $AH
Check '批量下架无原因 → 10001' $offNoReason.code 10001
$offBatch = Post "$Base/admin/review/batch/offline" @{ids=@($b3.data.itemId);reason='批量测试原因'} $AH
Check '批量下架 success=1' $offBatch.data.success 1
Check '批量项3 状态=5' (DbScalar "select status from item where id=$($b3.data.itemId)") 5
# 重复处置：应报"已被处置"而不是崩
$again = Post "$Base/admin/review/batch/review" @{ids=@($b3.data.itemId)} $AH
Check '重复批量处置 failed=1' $again.data.failed 1

# ---------- 8. 实时推送（WebSocket） ----------
Write-Host '=== 8. 实时推送：新上架 1 秒内到达审核员页面 ===' -ForegroundColor Cyan
$probe = Join-Path $PSScriptRoot 'probe-admin-review-ws.mjs'
$nodeDir = 'D:\tools\nodejs1'
$env:REVIEW_BASE = $origin
$env:REVIEW_TOKEN = $adminToken
$env:REVIEW_SELLER_TOKEN = $sellerToken
$env:REVIEW_BUYER_TOKEN = $buyerToken
$env:REVIEW_IMG = $img
$nodeExe = Join-Path $nodeDir 'node.exe'
if (-not (Test-Path $nodeExe)) { $nodeExe = 'node' }
$probeOut = & $nodeExe $probe 2>&1
Write-Host ($probeOut | Out-String).Trim() -ForegroundColor DarkGray
$probeJson = ($probeOut | Where-Object { $_ -like '{*' } | Select-Object -Last 1)
if ($probeJson) {
  $pr = $probeJson | ConvertFrom-Json
  CheckTrue 'WS 握手成功并收到 ready' ($pr.ready -eq $true)
  CheckTrue '新上架推送在 3 秒内到达' ($pr.queuedMs -ge 0 -and $pr.queuedMs -lt 3000)
  CheckTrue '推送 itemId 与实际发布一致' ($pr.queuedItemId -eq $pr.publishedItemId)
  CheckTrue '推送带权威待审条数' ($pr.queuedPending -ge 1)
  CheckTrue '处置后收到 handled 推送' ($pr.handled -eq $true)
  CheckTrue '非运营连 WS 被拒（403）' ($pr.nonAdminRejected -eq $true)
  $wsItem = $pr.publishedItemId
} else {
  CheckTrue 'WS 探针输出可解析' $false
  $wsItem = $null
}

# ---------- 9. 审计留痕 ----------
Write-Host '=== 9. 操作审计留痕 ===' -ForegroundColor Cyan
$logReview = DbScalar "select count(*) from sys_audit_log where action='review-item-reviewed' and target_id=$itemA"
CheckTrue '已审核写入审计日志' ([int]$logReview -ge 1)
$logOffline = DbScalar "select count(*) from sys_audit_log where action='review-item-offline' and target_id=$itemB"
CheckTrue '下架写入审计日志' ([int]$logOffline -ge 1)

# ---------- 10. 补登记 ----------
Write-Host '=== 10. 补登记：在售却没有审核记录的商品 ===' -ForegroundColor Cyan
# 人为制造"在售但没有审核记录"的脏状态，验证兜底能捞回来
$p5 = Post "$Base/items" @{ title='上架审核测试E 漏登记'; description='描述正常'; categoryId=1; conditionLevel=3; price=40; images=@($img); preferPointId=1 } $SH
$itemE = $p5.data.itemId
Sql "delete from item_review where item_id=$itemE;"
Check '制造脏数据：无审核记录' (DbScalar "select count(*) from item_review where item_id=$itemE") 0
$bf = Post "$Base/admin/review/backfill?limit=50" $null $AH
Check '补登记接口成功' $bf.code 0
CheckTrue '补登记捞回该商品' ([int](DbScalar "select count(*) from item_review where item_id=$itemE") -ge 1)
Check '补登记按待审入册' (DbScalar "select status from item_review where item_id=$itemE") 0

# ---------- 11. 在售商品下架后自动退出队列 ----------
Write-Host '=== 11. 卖家自己下架后自动退出待审列表 ===' -ForegroundColor Cyan
$p6 = Post "$Base/items" @{ title='上架审核测试F 待下架'; description='描述正常'; categoryId=1; conditionLevel=3; price=50; images=@($img); preferPointId=1 } $SH
$itemF = $p6.data.itemId
$feedF = Get "$Base/admin/review/feed?scope=pending&pageSize=100" $AH
CheckTrue '下架前在待审列表' (($feedF.data.records | Where-Object { $_.itemId -eq $itemF -and $_.status -eq 0 }) -ne $null)
Post "$Base/items/$itemF/offline" @{} $SH | Out-Null
$feedF2 = Get "$Base/admin/review/feed?scope=pending&pageSize=100" $AH
CheckTrue '卖家下架后不在待审列表' (($feedF2.data.records | Where-Object { $_.itemId -eq $itemF -and $_.status -eq 0 }) -eq $null)
$statsF = Get "$Base/admin/review/stats" $AH
CheckTrue '统计口径与列表一致（不会数字大于条数）' ($statsF.data.pending -le ($feedF2.data.total))

# ---------- 12. 搜索 / 筛选 ----------
Write-Host '=== 12. 搜索与卖家筛选 ===' -ForegroundColor Cyan
$kw = Get "$Base/admin/review/feed?scope=pending&keyword=上架审核批量1&pageSize=50" $AH
CheckTrue '按标题搜索命中' (($kw.data.records | ForEach-Object { $_.itemId }) -contains $b1.data.itemId)
$bySeller = Get "$Base/admin/review/feed?scope=all&sellerId=$sellerId&pageSize=100" $AH
CheckTrue '按卖家筛选可用' ($bySeller.data.records.Count -gt 0)
Check '非法卖家 ID 不报错' (Get "$Base/admin/review/feed?sellerId=abc" $AH).code 0

# ---------- 13. 清理本次造的数据 ----------
# 本脚本每跑一次都会发若干件「上架审核*」商品。以前只按 seller_id 清，而它为了测
# "管理员也能上架"会用运营账号发几件 —— 那几件就永久留在库里。实测残留 3 件
# （status=2 的在售、status=5 的已下架），既脏了首页也把后台数字顶高。
Write-Host '=== 13. 清理测试残留 ===' -ForegroundColor Cyan
$before = [int](& $MYSQL -h 127.0.0.1 -P 3306 -u ershou --default-character-set=utf8mb4 -N -B -e "use ershou; select count(*) from item where title like '上架审核%'" 2>$null)
Sql "delete from meetup_order where item_id in (select id from item where title like '上架审核%')"
Sql "delete from chat_session where item_id in (select id from item where title like '上架审核%')"
Sql "delete from cart_item    where item_id in (select id from item where title like '上架审核%')"
Sql "delete from favorite     where item_id in (select id from item where title like '上架审核%')"
Sql "delete from item_review  where item_id in (select id from item where title like '上架审核%')"
Sql "delete from item_review  where seller_id = $sellerId"
Sql "delete from item where title like '上架审核%'"
Sql "delete from item where seller_id = $sellerId"
$after = [int](& $MYSQL -h 127.0.0.1 -P 3306 -u ershou --default-character-set=utf8mb4 -N -B -e "use ershou; select count(*) from item where title like '上架审核%'" 2>$null)
Check '测试商品已清理干净' $after 0
Write-Host ("  （清理前 $before 件 → 清理后 $after 件）") -ForegroundColor DarkGray
# 概览有 30 秒读缓存，清掉免得后台还显示旧数字
& docker exec ershou-redis redis-cli -a $env:REDIS_PASSWORD --no-auth-warning DEL 'ershou:admin:dashboard' 2>&1 | Out-Null

# 清理走的是直接 SQL DELETE，ES 收不到通知 —— 不重建的话索引里会攒下幽灵文档
# （后果：搜索结果 total 虚高，"共 N 件"却列不出东西）。重建走接口，按当前库重来。
try {
  $re = Invoke-RestMethod -Uri "$Base/search/reindex?sync=true" -Method Post -Headers $AH -TimeoutSec 15
  Write-Host '  已触发搜索索引重建（清掉本次测试留下的幽灵文档）' -ForegroundColor DarkGray
} catch {
  Write-Host '  索引重建触发失败（不影响本次断言，可手动调 POST /api/v1/search/reindex）' -ForegroundColor DarkYellow
}

# ---------- 汇总 ----------
Write-Host ''
if ($script:fail -eq 0) {
  Write-Host '===== 上架审核冒烟测试全部通过 =====' -ForegroundColor Green
  exit 0
} else {
  Write-Host ("===== 失败 " + $script:fail + " 项 =====") -ForegroundColor Red
  exit 1
}
