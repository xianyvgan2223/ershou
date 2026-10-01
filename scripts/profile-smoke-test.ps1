# =============================================================
# 个人资料编辑（PATCH /users/me）端到端冒烟测试
# 覆盖：资料结构 → 局部更新（只传昵称）→ 全量更新（头像/小铺/校区）
#       → 落库校验 → 公开主页同步 → 清空字段
#       → 参数校验 / 内容审核 / 学校校区校验 / 未登录拦截
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\profile-smoke-test.ps1 [-Base http://127.0.0.1:8080/api/v1]
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
    # .env 里有的变量把注释写在同一行（例：`# 说明... VITE_AMAP_JS_KEY=xxx`），
    # 直接 split 会把 key 读成注释文本，所以先砍掉 # 之后的内容。
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
  $out = & $MYSQL -h 127.0.0.1 -P 3306 -u ershou -N -B -e "use ershou; $sql" 2>$null
  return ($out | Select-Object -First 1)
}
function Sql($sql) {
  & $MYSQL -h 127.0.0.1 -P 3306 -u ershou -e "use ershou; $sql" 2>&1 | Out-Null
}
function Send($method, $url, $obj, $headers) {
  if ($null -eq $obj) {
    if ($headers) { return Invoke-RestMethod -Uri $url -Method $method -Headers $headers }
    return Invoke-RestMethod -Uri $url -Method $method
  }
  $json = $obj | ConvertTo-Json -Depth 6 -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  if ($headers) { return Invoke-RestMethod -Uri $url -Method $method -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $bytes }
  return Invoke-RestMethod -Uri $url -Method $method -ContentType 'application/json; charset=utf-8' -Body $bytes
}
function Post($url, $obj, $headers) { return Send 'Post' $url $obj $headers }
function Patch($url, $obj, $headers) { return Send 'Patch' $url $obj $headers }
function Get($url, $headers) { return Send 'Get' $url $null $headers }

$AVATAR = 'http://127.0.0.1:9010/ershou/avatar/2026/09/smoke.jpg'
$TEMP_SCHOOL = 9001
$TEMP_CAMPUS = 9001

Write-Host ''
Write-Host '===== 个人资料编辑（PATCH /users/me）冒烟测试 =====' -ForegroundColor Cyan
Write-Host ("  Base = " + $Base) -ForegroundColor DarkGray

try { Invoke-RestMethod -Uri ($origin + '/actuator/health') -TimeoutSec 5 | Out-Null }
catch { Write-Host '后端未启动，请先运行 .\scripts\dev-server.ps1' -ForegroundColor Red; exit 1 }

# ---------- 0. 登录测试账号 ----------
Write-Host '=== 0. 登录测试账号(13800000003) ===' -ForegroundColor Cyan
$phone = '13800000003'
Post "$Base/auth/sms-code" @{phone=$phone} | Out-Null
$login = Post "$Base/auth/login" @{phone=$phone;code='123456';schoolId=1;campusId=1}
$token = $login.data.token
$userId = $login.data.user.id
$H = @{ Authorization = "Bearer $token" }
CheckTrue '登录成功拿到 token' ($null -ne $token)
Write-Host ("  测试用户 id=" + $userId) -ForegroundColor DarkGray

# 起始状态：默认昵称 + 空头像 + 空小铺 + 主校区
$defaultNickname = '同学' + $phone.Substring(7)
Sql "update sys_user set nickname = '$defaultNickname', avatar = null, shop_name = null, shop_intro = null, school_id = 1, campus_id = 1, status = 1 where id = $userId;"

# ---------- 1. 资料结构 ----------
Write-Host '=== 1. GET /users/me 资料结构 ===' -ForegroundColor Cyan
$me = Get "$Base/users/me" $H
Check 'code' $me.code 0
CheckTrue '带 schoolId（编辑弹窗回填学校）' ($null -ne $me.data.schoolId)
CheckTrue '带 campusId（编辑弹窗回填校区）' ($null -ne $me.data.campusId)
CheckTrue '带 createdAt（加入时间）' ($null -ne $me.data.createdAt)
Check '昵称为默认值' $me.data.nickname $defaultNickname

# ---------- 2. 局部更新：只传昵称 ----------
Write-Host '=== 2. 局部更新（只传昵称，其余字段不动）===' -ForegroundColor Cyan
$r = Patch "$Base/users/me" @{nickname='二手工匠'} $H
Check 'code' $r.code 0
Check '返回新昵称' $r.data.nickname '二手工匠'
Check '头像未被清空' (DbScalar "select ifnull(avatar,'NULL') from sys_user where id = $userId") 'NULL'
Check '校区未被改动' (DbScalar "select campus_id from sys_user where id = $userId") 1

# ---------- 3. 全量更新：头像 + 小铺 + 校区 ----------
Write-Host '=== 3. 全量更新（头像 / 小铺名 / 小铺简介 / 校区）===' -ForegroundColor Cyan
$r = Patch "$Base/users/me" @{nickname='二手工匠';avatar=$AVATAR;shopName='旧书小铺';shopIntro='只卖教材和考研书';campusId=1} $H
Check 'code' $r.code 0
Check '返回头像' $r.data.avatar $AVATAR
Check '返回小铺名' $r.data.shopName '旧书小铺'
Check '返回小铺简介' $r.data.shopIntro '只卖教材和考研书'
Check '返回校区名' $r.data.campusName '主校区'

# ---------- 4. 落库校验 ----------
Write-Host '=== 4. 落库校验（sys_user）===' -ForegroundColor Cyan
Check 'nickname' (DbScalar "select nickname from sys_user where id = $userId") '二手工匠'
Check 'avatar' (DbScalar "select avatar from sys_user where id = $userId") $AVATAR
Check 'shop_name' (DbScalar "select shop_name from sys_user where id = $userId") '旧书小铺'
Check 'shop_intro' (DbScalar "select shop_intro from sys_user where id = $userId") '只卖教材和考研书'

# ---------- 5. 公开主页同步 ----------
Write-Host '=== 5. 公开主页 /users/{id}/profile 同步 ===' -ForegroundColor Cyan
$p = Get "$Base/users/$userId/profile" $null
Check 'code' $p.code 0
Check '公开页昵称' $p.data.nickname '二手工匠'
Check '公开页头像' $p.data.avatar $AVATAR
Check '公开页小铺名' $p.data.shopName '旧书小铺'
CheckTrue '公开页仍不带手机号' (-not ($p.data.PSObject.Properties.Name -contains 'phone'))

# ---------- 6. 清空字段 ----------
Write-Host '=== 6. 传空串清空头像 / 小铺 ===' -ForegroundColor Cyan
$r = Patch "$Base/users/me" @{avatar='';shopName='';shopIntro=''} $H
Check 'code' $r.code 0
Check '头像已清空' (DbScalar "select ifnull(avatar,'NULL') from sys_user where id = $userId") 'NULL'
Check '小铺名已清空' (DbScalar "select ifnull(shop_name,'NULL') from sys_user where id = $userId") 'NULL'
Check '小铺简介已清空' (DbScalar "select ifnull(shop_intro,'NULL') from sys_user where id = $userId") 'NULL'
Check '昵称不受影响' (DbScalar "select nickname from sys_user where id = $userId") '二手工匠'

# ---------- 7. 参数与内容校验 ----------
Write-Host '=== 7. 参数 / 内容审核 / 越权校验 ===' -ForegroundColor Cyan
Check '空昵称 → 10001' (Patch "$Base/users/me" @{nickname='   '} $H).code 10001
Check '超长昵称(33 字) → 10001' (Patch "$Base/users/me" @{nickname=('昵' * 33)} $H).code 10001
Check '超长小铺名(65 字) → 10001' (Patch "$Base/users/me" @{shopName=('铺' * 65)} $H).code 10001
Check '昵称带禁售词 → 50001' (Patch "$Base/users/me" @{nickname='代写论文找我'} $H).code 50001
Check '小铺名带禁售词 → 50001' (Patch "$Base/users/me" @{shopName='出售银行卡'} $H).code 50001
Check '违规昵称未落库' (DbScalar "select nickname from sys_user where id = $userId") '二手工匠'
Check '外链头像(javascript:) → 10001' (Patch "$Base/users/me" @{avatar='javascript:alert(1)'} $H).code 10001
Check '相对路径头像(/static/a.jpg) 放行' (Patch "$Base/users/me" @{avatar='/static/avatar/a.jpg'} $H).code 0
Check '不存在的校区 → 10001' (Patch "$Base/users/me" @{campusId=99999} $H).code 10001
Check '不存在的学校 → 10001' (Patch "$Base/users/me" @{schoolId=99999} $H).code 10001
Check '未登录 → 10002' (Patch "$Base/users/me" @{nickname='黑客'} $null).code 10002

# 校区跨校校验：临时造一所学校 + 一个校区，用完删掉
Sql "insert into school (id, name, city) values ($TEMP_SCHOOL, '冒烟测试大学', '测试市') on duplicate key update name = values(name);"
Sql "insert into campus (id, school_id, name, map_type) values ($TEMP_CAMPUS, $TEMP_SCHOOL, '测试校区', 1) on duplicate key update school_id = values(school_id);"
Check '只换学校不换校区 → 10001' (Patch "$Base/users/me" @{schoolId=$TEMP_SCHOOL} $H).code 10001
Check '校区不属于所选学校 → 10001' (Patch "$Base/users/me" @{schoolId=$TEMP_SCHOOL;campusId=1} $H).code 10001
Check '学校校区匹配 → 放行' (Patch "$Base/users/me" @{schoolId=$TEMP_SCHOOL;campusId=$TEMP_CAMPUS} $H).code 0
Check '校区已切换' (DbScalar "select campus_id from sys_user where id = $userId") $TEMP_CAMPUS
Sql "delete from campus where id = $TEMP_CAMPUS;"
Sql "delete from school where id = $TEMP_SCHOOL;"

# ---------- 8. 还原测试账号 ----------
Write-Host '=== 8. 还原测试账号 ===' -ForegroundColor Cyan
Sql "update sys_user set nickname = '$defaultNickname', avatar = null, shop_name = null, shop_intro = null, school_id = 1, campus_id = 1 where id = $userId;"
Check '昵称已还原' (DbScalar "select nickname from sys_user where id = $userId") $defaultNickname

Write-Host ''
if ($script:fail -eq 0) {
  Write-Host '全部通过，个人资料编辑链路正常' -ForegroundColor Green
} else {
  Write-Host ("有 " + $script:fail + " 项未通过") -ForegroundColor Red
}
exit $script:fail
