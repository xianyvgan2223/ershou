
# =============================================================
# 删除聊天记录（会话）端到端冒烟测试
# 覆盖：只删自己这侧 → 对方不受影响 → 未读归零 → 幂等 → 双方各自删
#        → 对方再发消息自动恢复 → 重新从商品发起会话撤销删除 → 权限隔离
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\chat-delete-smoke-test.ps1 [-Base http://127.0.0.1:8080/api/v1]
# 前置：后端已启动；MySQL 可访问
# 注意：本文件必须带 UTF-8 BOM，否则 PS 5.1 会按 GBK 读，中文串全部报语法错
#
# 三个踩过的坑（改脚本时别踩回去）：
#   1) 函数不能叫 Del —— Del 是 PowerShell 里 Remove-Item 的内置别名，会被解析成删文件；
#   2) HTTP DELETE 用 Invoke-RestMethod，不要用 Invoke-WebRequest ——
#      后者在 PS 5.1 上处理 DELETE 偶发 NullReferenceException（与参数无关，很误导）；
#   3) 会话必须用【卖家自己的商品】去建：随便抓一件别人的商品，卖家不是会话成员，
#      下一步全都 70002，看起来像功能坏了，其实是测试写错了。
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
function SqlRun($sql) {
  & $MYSQL -h 127.0.0.1 -P 3306 -u ershou --default-character-set=utf8mb4 -e "use ershou; $sql" 2>&1 | Out-Null
}
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
# 返回业务 code；HTTP 4xx 时从响应体里取 code（取不到返回 -1）
function DeleteCode($url, $headers) {
  try {
    $r = Invoke-RestMethod -Uri $url -Method Delete -Headers $headers -ContentType 'application/json'
    return $r.code
  } catch {
    foreach ($candidate in @($_.ErrorDetails.Message, $_.Exception.Message)) {
      if ($candidate -and $candidate -match '\{.*\}') {
        try { return (($candidate | ConvertFrom-Json).code) } catch { }
      }
    }
    return -1
  }
}
function HasSession($list, $sid) {
  return (@($list.data | Where-Object { $_.id -eq $sid }).Count -gt 0)
}
function UnreadOf($unreadResp, $sid) {
  $s = $unreadResp.data.sessions."$sid"
  if ($null -eq $s) { return 0 }
  return [int]$s
}
function ResetSmsLimit($phones) {
  foreach ($p in $phones) {
    $keys = @(& docker exec ershou-redis redis-cli -a $REDIS_PWD --no-auth-warning KEYS "ershou:sms:limit:$p`:*" 2>$null |
              Where-Object { $_ -like 'ershou:*' })
    if ($keys.Count -gt 0) { & docker exec ershou-redis redis-cli -a $REDIS_PWD --no-auth-warning DEL @keys 2>&1 | Out-Null }
  }
}

Write-Host ''
Write-Host '===== 删除聊天记录 冒烟测试 =====' -ForegroundColor Cyan
Write-Host ("  Base = " + $Base) -ForegroundColor DarkGray

try { Invoke-RestMethod -Uri ($origin + '/actuator/health') -TimeoutSec 5 | Out-Null }
catch { Write-Host '后端未启动' -ForegroundColor Red; exit 1 }

# ---------- 0. 登录 ----------
Write-Host '=== 0. 登录 卖家 / 买家 / 无关第三人 ===' -ForegroundColor Cyan
$sellerPhone = '13800000008'; $buyerPhone = '13800000007'; $otherPhone = '13800000006'
ResetSmsLimit @($sellerPhone, $buyerPhone, $otherPhone)
Post "$Base/auth/sms-code" @{phone=$sellerPhone} | Out-Null
$sl = Post "$Base/auth/login" @{phone=$sellerPhone;code='123456';schoolId=1;campusId=1}
$sToken = $sl.data.token; $sellerId = $sl.data.user.id
Post "$Base/auth/sms-code" @{phone=$buyerPhone} | Out-Null
$bl = Post "$Base/auth/login" @{phone=$buyerPhone;code='123456';schoolId=1;campusId=1}
$bToken = $bl.data.token; $buyerId = $bl.data.user.id
Post "$Base/auth/sms-code" @{phone=$otherPhone} | Out-Null
$ol = Post "$Base/auth/login" @{phone=$otherPhone;code='123456';schoolId=1;campusId=1}
$oToken = $ol.data.token; $otherId = $ol.data.user.id
$SH = @{ Authorization = "Bearer $sToken" }
$BH = @{ Authorization = "Bearer $bToken" }
$OH = @{ Authorization = "Bearer $oToken" }
Write-Host ("  卖家 id=$sellerId  买家 id=$buyerId  第三人 id=$otherId") -ForegroundColor DarkGray

SqlRun "update sys_user set publish_limit = 500 where id = $sellerId;"
$pub = Post "$Base/items" @{title=('删除聊天测试商品-' + (Get-Random));description='仅用于删除聊天记录测试';categoryId=1;conditionLevel=3;price=12;images=@('http://127.0.0.1:9010/ershou/test.png');preferPointId=1} $SH
Check '卖家发布测试商品' $pub.code 0
$itemId = $pub.data.itemId
Write-Host ("  用卖家自己的商品 id=" + $itemId + " 开会话") -ForegroundColor DarkGray

# ---------- 1. 建会话 ----------
Write-Host '=== 1. 建会话：双方列表都能看到 ===' -ForegroundColor Cyan
$open = Post "$Base/chat/sessions" @{itemId=$itemId} $BH
Check '买家从商品发起会话' $open.code 0
$sid = $open.data.id
CheckTrue ("买家列表含会话 #" + $sid) (HasSession (Get2 "$Base/chat/sessions" $BH) $sid)
CheckTrue ("卖家列表含会话 #" + $sid) (HasSession (Get2 "$Base/chat/sessions" $SH) $sid)

# ---------- 2. 买家删除 ----------
Write-Host '=== 2. 买家删除聊天记录：只有自己这侧消失 ===' -ForegroundColor Cyan
# ⚠️ chat_session 按 user_a = min(双方 id) 存储，所以"买家对应哪一列"取决于 id 大小：
#    买家 id 比卖家大就是 user_b。**断言必须按实际列来推导，不要写死 deleted_a** ——
#    写死过一次，功能明明是对的、测试却红了 3 项，排查方向全被带偏。
$buyerIsA = $buyerId -lt $sellerId
$myCol = if ($buyerIsA) { 'deleted_a' } else { 'deleted_b' }
$peerCol = if ($buyerIsA) { 'deleted_b' } else { 'deleted_a' }
$myAtCol = if ($buyerIsA) { 'deleted_a_at' } else { 'deleted_b_at' }
$myUnreadCol = if ($buyerIsA) { 'unread_a' } else { 'unread_b' }
Write-Host ("  买家 id=$buyerId 卖家 id=$sellerId → 买家对应列 = " + $myCol) -ForegroundColor DarkGray
Check '删除接口返回成功' (DeleteCode "$Base/chat/sessions/$sid" $BH) 0
Check ("DB " + $myCol + "=1（我这侧已删除）") (DbScalar "select $myCol from chat_session where id=$sid") 1
Check ("DB " + $peerCol + " 仍为 0（对方不受影响）") (DbScalar "select $peerCol from chat_session where id=$sid") 0
CheckTrue '记录了删除时间' ((DbScalar "select $myAtCol from chat_session where id=$sid") -ne '')
CheckTrue '买家列表里已消失' (-not (HasSession (Get2 "$Base/chat/sessions" $BH) $sid))
CheckTrue '卖家列表里还在（别人的数据不能被删）' (HasSession (Get2 "$Base/chat/sessions" $SH) $sid)

Write-Host '=== 3. 未读一起归零（否则"列表没有、红点还在"）===' -ForegroundColor Cyan
Check '买家未读里该会话为 0' (UnreadOf (Get2 "$Base/chat/unread" $BH) $sid) 0
CheckTrue '卖家未读接口仍正常' ((Get2 "$Base/chat/unread" $SH).code -eq 0)

Write-Host '=== 4. 幂等：重复删除不报错 ===' -ForegroundColor Cyan
Check '重复删除仍返回成功' (DeleteCode "$Base/chat/sessions/$sid" $BH) 0
Check 'DB 状态仍是已删除' (DbScalar "select $myCol from chat_session where id=$sid") 1

# ---------- 5. 对方发消息 → 自动恢复 ----------
Write-Host '=== 5. 卖家再发消息 → 买家这侧自动恢复 ===' -ForegroundColor Cyan
$msg = Post "$Base/chat/messages" @{sessionId=$sid;content='在吗？这个还在的';msgType=1;clientMsgId=('dt' + (Get-Random))} $SH
Check '卖家发消息成功' $msg.code 0
Check 'DB 我这侧的标记被重置为 0' (DbScalar "select $myCol from chat_session where id=$sid") 0
Check 'DB 删除时间被清空' (DbScalar "select ifnull($myAtCol,'null') from chat_session where id=$sid") 'null'
CheckTrue '买家列表里会话重新出现' (HasSession (Get2 "$Base/chat/sessions" $BH) $sid)
CheckTrue '买家收到未读 >= 1' ((UnreadOf (Get2 "$Base/chat/unread" $BH) $sid) -ge 1)

# ---------- 6. 有未读时删除 ----------
Write-Host '=== 6. 有未读时删除：未读一并归零 ===' -ForegroundColor Cyan
$msg2 = Post "$Base/chat/messages" @{sessionId=$sid;content='方便的话下午图书馆见';msgType=1;clientMsgId=('dt' + (Get-Random))} $SH
Check '卖家再发一条' $msg2.code 0
CheckTrue '买家未读 >= 1' ((UnreadOf (Get2 "$Base/chat/unread" $BH) $sid) -ge 1)
DeleteCode "$Base/chat/sessions/$sid" $BH | Out-Null
Check '删除后 DB 我这侧未读归零' (DbScalar "select $myUnreadCol from chat_session where id=$sid") 0
Check '买家未读汇总里该会话为 0' (UnreadOf (Get2 "$Base/chat/unread" $BH) $sid) 0

# ---------- 7. 重新发起会话 ----------
Write-Host '=== 7. 重新从商品页发起会话 → 撤销删除 ===' -ForegroundColor Cyan
$reopen = Post "$Base/chat/sessions" @{itemId=$itemId} $BH
Check '重新发起会话成功' $reopen.code 0
Check '复用的是同一条会话（唯一索引）' $reopen.data.id $sid
Check 'DB 我这侧标记已重置' (DbScalar "select $myCol from chat_session where id=$sid") 0
CheckTrue '买家列表里恢复可见' (HasSession (Get2 "$Base/chat/sessions" $BH) $sid)

# ---------- 8. 双方各自删 ----------
Write-Host '=== 8. 双方各自删：两边都消失，消息记录仍在 ===' -ForegroundColor Cyan
$msgBefore = [int](DbScalar "select count(*) from chat_message where session_id=$sid")
DeleteCode "$Base/chat/sessions/$sid" $BH | Out-Null
DeleteCode "$Base/chat/sessions/$sid" $SH | Out-Null
Check ("DB " + $myCol + "=1（我这侧已删除）") (DbScalar "select $myCol from chat_session where id=$sid") 1
Check 'DB 对方那侧=1' (DbScalar "select $peerCol from chat_session where id=$sid") 1
CheckTrue '买家列表看不到' (-not (HasSession (Get2 "$Base/chat/sessions" $BH) $sid))
CheckTrue '卖家列表看不到' (-not (HasSession (Get2 "$Base/chat/sessions" $SH) $sid))
Check '消息记录没有被物理删除（对方还要看历史）' ([int](DbScalar "select count(*) from chat_message where session_id=$sid")) $msgBefore
Check '会话行本身还在（不是 DELETE 整行）' (DbScalar "select count(*) from chat_session where id=$sid") 1

$msg3 = Post "$Base/chat/messages" @{sessionId=$sid;content='最后确认一下时间';msgType=1;clientMsgId=('dt' + (Get-Random))} $SH
Check '双方都删后仍能发消息' $msg3.code 0
Check 'DB 两侧标记都被恢复' ((DbScalar "select deleted_a from chat_session where id=$sid") + '/' + (DbScalar "select deleted_b from chat_session where id=$sid")) '0/0'

# ---------- 9. 权限隔离 ----------
Write-Host '=== 9. 权限隔离 ===' -ForegroundColor Cyan
Check '非会话成员删除被拒 70002' (DeleteCode "$Base/chat/sessions/$sid" $OH) 70002
Check '未登录删除被拒 10002' (DeleteCode "$Base/chat/sessions/$sid" @{}) 10002
Check '不存在的会话返回 70002' (DeleteCode "$Base/chat/sessions/99999999" $BH) 70002
Check '第三人删不掉（DB 标记没被改动）' (DbScalar "select ifnull($myCol,0) from chat_session where id=$sid") 0

# ---------- 10. includeDeleted ----------
Write-Host '=== 10. includeDeleted=true 能查回已删除的会话（排查用）===' -ForegroundColor Cyan
DeleteCode "$Base/chat/sessions/$sid" $BH | Out-Null
CheckTrue '默认列表不含' (-not (HasSession (Get2 "$Base/chat/sessions" $BH) $sid))
CheckTrue 'includeDeleted=true 含' (HasSession (Get2 "$Base/chat/sessions?includeDeleted=true" $BH) $sid)

# ---------- 11. 清理 ----------
Write-Host '=== 11. 清理本脚本造的测试数据 ===' -ForegroundColor Cyan
SqlRun "delete from chat_message where session_id=$sid; delete from chat_session where id=$sid; delete from item_review where item_id=$itemId; delete from item where id=$itemId;"
Check '测试会话已清理' (DbScalar "select count(*) from chat_session where id=$sid") 0
Check '测试商品已清理' (DbScalar "select count(*) from item where id=$itemId") 0

# ---------- 汇总 ----------
Write-Host ''
if ($script:fail -eq 0) {
  Write-Host '===== 删除聊天记录：全部通过 =====' -ForegroundColor Green
  exit 0
} else {
  Write-Host ("===== 失败 " + $script:fail + " 项 =====") -ForegroundColor Red
  exit 1
}
