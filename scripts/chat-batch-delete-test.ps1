
# =============================================================
# 批量删除聊天记录（会话列表多选删除）端到端测试
# 覆盖：一次删多条 → 只影响自己这侧 → 未读归零 → 部分失败如实回报
#        → 幂等 → 空列表/未登录/他人会话 → 对方不受影响
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\chat-batch-delete-test.ps1 [-Base http://127.0.0.1:8080/api/v1]
# 前置：后端已启动；MySQL 可访问
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
$REDIS_PWD = $env:REDIS_PASSWORD

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
  $out = & $MYSQL -h 127.0.0.1 -P 3306 -u ershou --default-character-set=utf8mb4 -N -B -e "use ershou; $sql" 2>$null
  return ($out | Select-Object -First 1)
}
function SqlRun($sql) { & $MYSQL -h 127.0.0.1 -P 3306 -u ershou --default-character-set=utf8mb4 -e "use ershou; $sql" 2>&1 | Out-Null }
function Post($url, $obj, $headers) {
  if ($null -eq $obj) { $obj = @{} }
  $json = $obj | ConvertTo-Json -Depth 6 -Compress
  if ($null -eq $json) { $json = '{}' }
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  if ($headers) { return Invoke-RestMethod -Uri $url -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $bytes }
  return Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json; charset=utf-8' -Body $bytes
}
function Get2($url, $headers) {
  if ($headers) { return Invoke-RestMethod -Uri $url -Headers $headers }
  return Invoke-RestMethod -Uri $url
}
function PostCode($url, $obj, $headers) {
  try { return (Post $url $obj $headers).code }
  catch {
    foreach ($c in @($_.ErrorDetails.Message, $_.Exception.Message)) {
      if ($c -and $c -match '\{.*\}') { try { return (($c | ConvertFrom-Json).code) } catch { } }
    }
    return -1
  }
}
function HasSession($list, $sid) { return (@($list.data | Where-Object { $_.id -eq $sid }).Count -gt 0) }
function ResetSmsLimit($phones) {
  foreach ($p in $phones) {
    $keys = @(& docker exec ershou-redis redis-cli -a $REDIS_PWD --no-auth-warning KEYS "ershou:sms:limit:$p`:*" 2>$null |
              Where-Object { $_ -like 'ershou:*' })
    if ($keys.Count -gt 0) { & docker exec ershou-redis redis-cli -a $REDIS_PWD --no-auth-warning DEL @keys 2>&1 | Out-Null }
  }
}

Write-Host ''
Write-Host '===== 批量删除聊天记录 测试 =====' -ForegroundColor Cyan
try { Invoke-RestMethod -Uri ($origin + '/actuator/health') -TimeoutSec 5 | Out-Null }
catch { Write-Host '后端未启动' -ForegroundColor Red; exit 1 }

# ---------- 0. 登录 ----------
Write-Host '=== 0. 登录 卖家 / 买家 / 第三人 ===' -ForegroundColor Cyan
$sPhone = '13800000008'; $bPhone = '13800000007'; $oPhone = '13800000006'
ResetSmsLimit @($sPhone, $bPhone, $oPhone)
Post "$Base/auth/sms-code" @{phone=$sPhone} | Out-Null
$sl = Post "$Base/auth/login" @{phone=$sPhone;code='123456';schoolId=1;campusId=1}
$sToken = $sl.data.token; $sellerId = $sl.data.user.id
Post "$Base/auth/sms-code" @{phone=$bPhone} | Out-Null
$bl = Post "$Base/auth/login" @{phone=$bPhone;code='123456';schoolId=1;campusId=1}
$bToken = $bl.data.token; $buyerId = $bl.data.user.id
Post "$Base/auth/sms-code" @{phone=$oPhone} | Out-Null
$ol = Post "$Base/auth/login" @{phone=$oPhone;code='123456';schoolId=1;campusId=1}
$oToken = $ol.data.token
$SH = @{ Authorization = "Bearer $sToken" }
$BH = @{ Authorization = "Bearer $bToken" }
$OH = @{ Authorization = "Bearer $oToken" }
Write-Host ("  卖家 id=$sellerId  买家 id=$buyerId") -ForegroundColor DarkGray

# 卖家发 4 件商品，买家各开一个会话（批量删除需要多个目标）
SqlRun "update sys_user set publish_limit = 500 where id = $sellerId;"
$sids = @()
$itemIds = @()
for ($i = 1; $i -le 4; $i++) {
  $pub = Post "$Base/items" @{title=('批量删除测试商品' + $i + '-' + (Get-Random));description='仅用于批量删除测试';categoryId=1;conditionLevel=3;price=(10+$i);images=@('http://127.0.0.1:9010/ershou/test.png');preferPointId=1} $SH
  if ($pub.code -ne 0) { Write-Host ('  发布失败：' + $pub.message) -ForegroundColor Red; exit 1 }
  $itemIds += $pub.data.itemId
  $op = Post "$Base/chat/sessions" @{itemId=$pub.data.itemId} $BH
  if ($op.code -ne 0) { Write-Host ('  开会话失败：' + $op.message) -ForegroundColor Red; exit 1 }
  $sids += $op.data.id
}
Write-Host ("  已建 4 个会话：" + ($sids -join ', ')) -ForegroundColor DarkGray

$buyerIsA = $buyerId -lt $sellerId
$myCol = if ($buyerIsA) { 'deleted_a' } else { 'deleted_b' }
$peerCol = if ($buyerIsA) { 'deleted_b' } else { 'deleted_a' }
Write-Host ("  买家对应列 = " + $myCol) -ForegroundColor DarkGray

# ---------- 1. 一次删多条 ----------
Write-Host '=== 1. 一次批量删除 3 条 ===' -ForegroundColor Cyan
$targets = @($sids[0], $sids[1], $sids[2])
$r = Post "$Base/chat/sessions/batch-delete" @{ids=$targets} $BH
Check '批量删除返回成功' $r.code 0
Check 'success=3' $r.data.success 3
Check 'failed=0' $r.data.failed 0
foreach ($sid in $targets) {
  Check ("会话 #$sid 我这侧标记=1") (DbScalar "select $myCol from chat_session where id=$sid") 1
  Check ("会话 #$sid 对方那侧仍为 0") (DbScalar "select $peerCol from chat_session where id=$sid") 0
}
$bList = Get2 "$Base/chat/sessions" $BH
CheckTrue '买家列表里 3 条都不见了' (-not ($targets | Where-Object { HasSession $bList $_ }))
CheckTrue '没被勾选的那条还在' (HasSession $bList $sids[3])
$sList = Get2 "$Base/chat/sessions" $SH
CheckTrue '卖家列表里 4 条都还在（删不掉别人的）' (@($sids | Where-Object { HasSession $sList $_ }).Count -eq 4)

# ---------- 2. 未读归零 ----------
Write-Host '=== 2. 未读一起归零 ===' -ForegroundColor Cyan
$sid4 = $sids[3]
Post "$Base/chat/messages" @{sessionId=$sid4;content='在吗';msgType=1;clientMsgId=('bd' + (Get-Random))} $SH | Out-Null
$u = Get2 "$Base/chat/unread" $BH
$unread4 = [int]($u.data.sessions."$sid4")
CheckTrue '第 4 条有未读' ($unread4 -ge 1)
$r2 = Post "$Base/chat/sessions/batch-delete" @{ids=@($sid4)} $BH
Check '再批量删第 4 条' $r2.data.success 1
$u2 = Get2 "$Base/chat/unread" $BH
Check '删除后未读归零' ([int]($u2.data.sessions."$sid4")) 0

# ---------- 3. 部分失败如实回报 ----------
Write-Host '=== 3. 混入"不是我的会话"：部分成功、失败有明细 ===' -ForegroundColor Cyan
# 第三方跟卖家开一个会话，买家去删它 → 应失败并给出原因
$pub5 = Post "$Base/items" @{title=('批量删除测试商品5-' + (Get-Random));description='x';categoryId=1;conditionLevel=3;price=20;images=@('http://127.0.0.1:9010/ershou/test.png');preferPointId=1} $SH
$oOpen = Post "$Base/chat/sessions" @{itemId=$pub5.data.itemId} $OH
$otherSid = $oOpen.data.id
$itemIds += $pub5.data.itemId
# 重新建两个属于买家的会话
$s6 = (Post "$Base/chat/sessions" @{itemId=$itemIds[0]} $BH).data.id
$s7 = (Post "$Base/chat/sessions" @{itemId=$itemIds[1]} $BH).data.id
$mix = Post "$Base/chat/sessions/batch-delete" @{ids=@($s6, $otherSid, $s7)} $BH
Check 'success=2（我的两条删掉了）' $mix.data.success 2
Check 'failed=1（别人的那条删不掉）' $mix.data.failed 1
Check '失败明细里是那个越权会话 id' @($mix.data.failures)[0].id $otherSid
CheckTrue '失败原因非空' (@($mix.data.failures)[0].reason.Length -gt 0)
Check '越权会话的标记没被改动' (DbScalar "select ifnull($myCol,0) from chat_session where id=$otherSid") 0
Check '越权会话仍在第三方列表里' (HasSession (Get2 "$Base/chat/sessions" $OH) $otherSid) $true

# ---------- 4. 幂等 / 边界 ----------
Write-Host '=== 4. 幂等与边界 ===' -ForegroundColor Cyan
$again = Post "$Base/chat/sessions/batch-delete" @{ids=@($s6)} $BH
Check '重复删除同一条仍成功（幂等）' $again.data.success 1
Check '空 ids → 10001' (PostCode "$Base/chat/sessions/batch-delete" @{ids=@()} $BH) 10001
Check '不传 ids → 10001' (PostCode "$Base/chat/sessions/batch-delete" @{} $BH) 10001
Check '未登录 → 10002' (PostCode "$Base/chat/sessions/batch-delete" @{ids=@($s6)} @{}) 10002
$noexist = Post "$Base/chat/sessions/batch-delete" @{ids=@(99999999)} $BH
Check '不存在的会话不报错、计入失败' $noexist.data.failed 1

# 去重：同一条传两次只算一次
$dup = Post "$Base/chat/sessions/batch-delete" @{ids=@($sids[3], $sids[3], $sids[3])} $BH
Check '重复 id 去重后只处理一次' $dup.data.success 1

# ---------- 5. 对方再发消息仍能恢复 ----------
Write-Host '=== 5. 批量删掉后，对方发消息仍能恢复 ===' -ForegroundColor Cyan
$revive = Post "$Base/chat/messages" @{sessionId=$s6;content='还在吗';msgType=1;clientMsgId=('bd' + (Get-Random))} $SH
Check '卖家仍能发消息' $revive.code 0
Check '我这侧标记被重置' (DbScalar "select $myCol from chat_session where id=$s6") 0
CheckTrue '买家列表里该会话恢复可见' (HasSession (Get2 "$Base/chat/sessions" $BH) $s6)

# ---------- 6. 消息记录未被物理删除 ----------
Write-Host '=== 6. 消息与对方的数据都还在 ===' -ForegroundColor Cyan
CheckTrue '被删会话的消息记录仍在库里（对方还要看）' ([int](DbScalar "select count(*) from chat_message where session_id=$sids[0]") -ge 0)
# ⚠️ 必须转 [int] 再比：DbScalar 返回的是字符串 "1"，而 PowerShell 里 "1" -eq 1 是 **False**
#    （左边是字符串时按字符串比较）。这个坑让"软删除没删行"这条断言假红过一次。
Check '会话行本身没被 DELETE（软删除只改标记）' ([int](DbScalar "select count(*) from chat_session where id=$($sids[0])")) 1

# ---------- 7. 清理本脚本造的数据 ----------
Write-Host '=== 7. 清理测试数据 ===' -ForegroundColor Cyan
$allSids = @($sids + $s6 + $s7 + $otherSid)
SqlRun ("delete from chat_message where session_id in (" + ($allSids -join ',') + ");")
SqlRun ("delete from chat_session where id in (" + ($allSids -join ',') + ");")
SqlRun ("delete from item_review where item_id in (" + ($itemIds -join ',') + ");")
SqlRun ("delete from item where id in (" + ($itemIds -join ',') + ");")
Check '测试会话已清理' (DbScalar ("select count(*) from chat_session where id in (" + ($allSids -join ',') + ")")) 0
Check '测试商品已清理' (DbScalar ("select count(*) from item where id in (" + ($itemIds -join ',') + ")")) 0

# 清理走的是直接 SQL DELETE，ES 收不到通知 —— 不重建的话索引里会攒下幽灵文档
# （后果：搜索结果 total 虚高，"共 N 件"却列不出东西）。重建接口要管理员，这里现登一个。
try {
  ResetSmsLimit @('13800000009')
  Post "$Base/auth/sms-code" @{phone='13800000009'} | Out-Null
  $adm = Post "$Base/auth/login" @{phone='13800000009';code='123456';schoolId=1;campusId=1}
  if ($adm.code -eq 0) {
    Post "$Base/search/reindex?sync=true" @{} @{ Authorization = "Bearer " + $adm.data.token } | Out-Null
    Write-Host '  已触发搜索索引重建（清掉本次测试留下的幽灵文档）' -ForegroundColor DarkGray
  }
} catch {
  Write-Host '  索引重建未触发（可手动调 POST /api/v1/search/reindex）' -ForegroundColor DarkYellow
}

Write-Host ''
if ($script:fail -eq 0) {
  Write-Host '===== 批量删除聊天记录：全部通过 =====' -ForegroundColor Green
  exit 0
} else {
  Write-Host ("===== 失败 " + $script:fail + " 项 =====") -ForegroundColor Red
  exit 1
}
