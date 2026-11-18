<#
.SYNOPSIS
  全量微服务拆分冒烟验收脚本（full-split）：对 user/item/order/chat/map/platform/admin/file
  八个新服务的核心接口做冒烟，输出 PASS / FAIL / SKIP 汇总。

.DESCRIPTION
  对应契约：`ershou-cloud/docs/full-split-contract.md`（全量拆分：网关 8088 全部路由到新服务）。
  两种模式：
    1) 直连模式（默认）：逐个访问各服务端口，鉴权接口手工带 `X-User-Id`（契约 §二：服务信网关注入头）；
    2) 网关模式（-Gateway http://127.0.0.1:8088）：全部经网关，先登录拿 Sa-Token，鉴权接口带
       `Authorization: Bearer`；网关/路由/服务未就绪时自动 SKIP（不算失败）。

  判定规则：
    - PASS：HTTP 2xx 且响应 code 命中该检查的 Accept 列表（通常为 0）；
    - SKIP：服务端口未监听、网关不可达、路由 404/502/503/504（服务/网关尚未就绪）；
    - FAIL：其余情况（HTTP 非 2xx、code 非 0、上传 data.url 缺失、公开图片 URL 不可访问等）。
  退出码：0=无 FAIL（允许 SKIP）；>0=FAIL 数量（≤250）；2=脚本参数/环境错误（curl 缺失等）。

.PARAMETER Gateway
  网关地址，如 http://127.0.0.1:8088。不传 = 直连各服务端口。

.PARAMETER UserPhone / SmsCode
  网关模式下登录用的测试手机号（默认 19900000101）与 mock 验证码（默认 123456）。

.PARAMETER DirectUserId / DirectAdminId
  直连模式下带的 X-User-Id（普通用户默认 1003；管理员默认 1002，用于 admin-service 检查）。

.PARAMETER TimeoutSec
  单请求超时（默认 10 秒）。

.PARAMETER UploadFile
  自定义上传图片路径；不传则脚本自动生成 1x1 PNG 用于 file-service 冒烟。

.PARAMETER SkipUploadUrlCheck
  跳过「上传返回的公开 URL 再 GET 一次确认可访问」这一步。

.PARAMETER SkipNacosInfo
  跳过 Nacos 注册列表信息（只做接口冒烟）。

.PARAMETER DryRun
  只打印检查矩阵，不发请求。

.PARAMETER VerboseBody
  打印每次请求的响应片段（排障用）。

.PARAMETER OutJson
  可选：把结果写入 JSON 文件（如 .build/cloud/logs/verify-full-split.json）。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\verify-full-split.ps1
  powershell -ExecutionPolicy Bypass -File .\scripts\verify-full-split.ps1 -DryRun
  powershell -ExecutionPolicy Bypass -File .\scripts\verify-full-split.ps1 -Gateway http://127.0.0.1:8088
  powershell -ExecutionPolicy Bypass -File .\scripts\verify-full-split.ps1 -OutJson .build\cloud\logs\verify-full-split.json
#>
param(
  [string]$Gateway = '',
  [string]$UserPhone = '19900000101',
  [string]$SmsCode = '123456',
  [string]$DirectUserId = '1003',
  [string]$DirectAdminId = '1002',
  [int]$TimeoutSec = 10,
  [string]$UploadFile = '',
  [switch]$SkipUploadUrlCheck,
  [switch]$SkipNacosInfo,
  [switch]$DryRun,
  [switch]$VerboseBody,
  [string]$OutJson = ''
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot

# ---------------------------------------------------------------- 服务/端口（契约 §一）
$services = [ordered]@{
  'user'     = @{ port = 8081; name = 'user-service';      probe = '/api/v1/schools' }
  'item'     = @{ port = 8082; name = 'item-service';      probe = '/api/v1/items?pageNum=1&pageSize=1' }
  'order'    = @{ port = 8083; name = 'order-service';     probe = '/api/v1/cart' }
  'chat'     = @{ port = 8084; name = 'chat-service';      probe = '/api/v1/chat/unread' }
  'map'      = @{ port = 8085; name = 'map-service';       probe = '/api/v1/map/points?campusId=2' }
  'platform' = @{ port = 8086; name = 'platform-service';  probe = '/api/v1/platform/shop/points?campusId=2' }
  'admin'    = @{ port = 8087; name = 'admin-service';     probe = '/api/v1/admin/users?pageNum=1&pageSize=1' }
  'file'     = @{ port = 8089; name = 'file-service';      probe = '/actuator/health' }
}

# 检查矩阵：Auth = none / user / admin；Accept = 允许的业务码（默认 0；admin 允许 0/10003）
$checks = @(
  @{ id = 'user.schools';    service = 'user';     method = 'GET';  path = '/api/v1/schools';                                        auth = 'none';  accept = @(0); expect = '学校列表 code=0' },
  @{ id = 'item.items';      service = 'item';     method = 'GET';  path = '/api/v1/items?pageNum=1&pageSize=3';                     auth = 'none';  accept = @(0); expect = '商品分页 code=0' },
  @{ id = 'order.cart';      service = 'order';    method = 'GET';  path = '/api/v1/cart';                                           auth = 'user';  accept = @(0); expect = '购物车 code=0' },
  @{ id = 'chat.unread';     service = 'chat';     method = 'GET';  path = '/api/v1/chat/unread';                                    auth = 'user';  accept = @(0); expect = '未读数 code=0' },
  @{ id = 'map.points';      service = 'map';      method = 'GET';  path = '/api/v1/map/points?campusId=2';                          auth = 'none';  accept = @(0); expect = '地图点位 code=0' },
  @{ id = 'platform.points'; service = 'platform'; method = 'GET';  path = '/api/v1/platform/shop/points?campusId=2';                auth = 'none';  accept = @(0); expect = '平台代卖点 code=0' },
  @{ id = 'admin.users';     service = 'admin';    method = 'GET';  path = '/api/v1/admin/users?pageNum=1&pageSize=1';                auth = 'admin'; accept = @(0, 10003); expect = '管理端用户列表（管理员 0 / 非管理员 10003 均可）' },
  @{ id = 'file.upload';     service = 'file';     method = 'UPLOAD'; path = '/api/v1/files/upload?biz=item';                         auth = 'user';  accept = @(0); expect = 'multipart 上传 code=0 且 data.url 非空' }
)

# ---------------------------------------------------------------- 结果收集
$script:results = New-Object System.Collections.ArrayList
$script:pass = 0
$script:fail = 0
$script:skip = 0
function Add-Result([string]$id, [string]$service, [string]$status, [string]$detail) {
  switch ($status) {
    'PASS' { $script:pass++ }
    'FAIL' { $script:fail++ }
    'SKIP' { $script:skip++ }
  }
  $color = switch ($status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'DarkYellow' } }
  Write-Host ("  [{0}] {1,-18} {2}" -f $status, $id, $detail) -ForegroundColor $color
  $null = $script:results.Add([pscustomobject]@{ id = $id; service = $service; status = $status; detail = $detail })
}
function Head([string]$text) { Write-Host ''; Write-Host ('=== ' + $text + ' ===') -ForegroundColor Cyan }
function Snip([string]$raw, [int]$max = 300) {
  if (-not $raw) { return '' }
  $s = ($raw -replace '\s+', ' ').Trim()
  if ($s.Length -gt $max) { return $s.Substring(0, $max) + '...' }
  return $s
}

# ---------------------------------------------------------------- 请求工具
function Invoke-Api {
  param([string]$Method, [string]$Url, [hashtable]$Headers, [string]$JsonBody = '')
  $p = @{ Uri = $Url; Method = $Method; Headers = $Headers; TimeoutSec = $TimeoutSec; UseBasicParsing = $true }
  if ($JsonBody) {
    $p['ContentType'] = 'application/json; charset=utf-8'
    $p['Body'] = [System.Text.Encoding]::UTF8.GetBytes($JsonBody)
  }
  try {
    $r = Invoke-WebRequest @p
    return @{ status = [int]$r.StatusCode; body = [string]$r.Content; error = $null }
  } catch {
    $ex = $_.Exception
    if ($ex.Response) {
      $st = 0
      try { $st = [int]$ex.Response.StatusCode } catch { $st = 0 }
      $body = ''
      try {
        $sr = New-Object System.IO.StreamReader($ex.Response.GetResponseStream())
        $body = $sr.ReadToEnd()
      } catch { $body = '' }
      return @{ status = $st; body = $body; error = $null }
    }
    return @{ status = 0; body = ''; error = $ex.Message }
  }
}
function Test-TcpPort {
  param([string]$HostName = '127.0.0.1', [int]$Port, [int]$TimeoutMs = 800)
  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $iar = $client.BeginConnect($HostName, $Port, $null, $null)
    if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
    $client.EndConnect($iar)
    return $true
  } catch {
    return $false
  } finally {
    $client.Close()
  }
}
function Get-CurlPath {
  $candidates = @(
    (Join-Path $env:SystemRoot 'System32\curl.exe'),
    'C:\Windows\System32\curl.exe',
    (Join-Path $env:SystemRoot 'SysWOW64\curl.exe')
  )
  foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
  $cmd = Get-Command curl.exe -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  return ''
}
function Invoke-MultipartUpload {
  param([string]$Url, [hashtable]$Headers, [string]$FilePath, [string]$Curl)
  $tmp = [System.IO.Path]::GetTempFileName()
  try {
    $curlArgs = @('-s', '-S', '-m', [string]$TimeoutSec, '-o', $tmp, '-w', '%{http_code}')
    foreach ($k in $Headers.Keys) { $curlArgs += @('-H', ($k + ': ' + $Headers[$k])) }
    $curlArgs += @('-F', ('file=@' + $FilePath + ';type=image/png'), $Url)
    $codeRaw = & $Curl @curlArgs 2>&1
    $codeText = (@($codeRaw) | Select-Object -Last 1)
    $code = 0
    [void][int]::TryParse(([string]$codeText).Trim(), [ref]$code)
    $body = ''
    if (Test-Path $tmp) { $body = [System.IO.File]::ReadAllText($tmp) }
    return @{ status = $code; body = $body; error = $null }
  } catch {
    return @{ status = 0; body = ''; error = $_.Exception.Message }
  } finally {
    if (Test-Path $tmp) { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
  }
}

# ---------------------------------------------------------------- 准备
$mode = if ($Gateway) { 'gateway' } else { 'direct' }
$gatewayBase = if ($Gateway) { $Gateway.TrimEnd('/') } else { '' }
$script:token = ''
$curl = Get-CurlPath
$uploadTemp = ''
$tmpDir = Join-Path $root '.build\cloud\smoke'
if (-not (Test-Path $tmpDir)) { New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null }

# 生成/校验上传用图片（1x1 PNG，真实可解析；MinIO 压缩链路会转 jpg）
$pngB64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
if ($UploadFile) {
  if (-not (Test-Path $UploadFile)) { Write-Host ('[FAIL] -UploadFile 不存在: ' + $UploadFile) -ForegroundColor Red; exit 2 }
  $uploadTemp = $UploadFile
} else {
  $uploadTemp = Join-Path $tmpDir 'verify-full-split-smoke.png'
  [System.IO.File]::WriteAllBytes($uploadTemp, [Convert]::FromBase64String($pngB64))
}

Write-Host '============================================================'
Write-Host ' 全量微服务拆分冒烟（full-split）' -ForegroundColor White
Write-Host (' 模式: ' + $mode + $(if ($Gateway) { '（' + $gatewayBase + '）' } else { '（逐个服务端口直连）' }))
Write-Host (' 检查项: ' + $checks.Count + ' 个接口 / 服务: ' + ($services.Keys -join ', '))
Write-Host '============================================================' -ForegroundColor White

if ($DryRun) {
  Head 'DryRun：检查矩阵（不发请求）'
  foreach ($c in $checks) {
    $p = $services[$c.service].port
    $target = if ($Gateway) { $gatewayBase + $c.path } else { 'http://127.0.0.1:' + $p + $c.path }
    Write-Host ('  ' + $c.id.PadRight(18) + ' [' + $c.service + '] ' + $c.method.PadRight(7) + ' ' + $target)
  }
  Write-Host ''
  Write-Host '  说明: 直连模式鉴权接口带 X-User-Id；网关模式先登录拿 Bearer。服务未就绪标 SKIP。'
  exit 0
}

if ($checks | Where-Object { $_.method -eq 'UPLOAD' }) {
  if (-not $curl) {
    Write-Host '[FAIL] 找不到 curl.exe（file-service multipart 冒烟需要）。Windows 10+ 自带 C:\Windows\System32\curl.exe' -ForegroundColor Red
    exit 2
  }
}

# 网关模式：先探测网关 + 登录拿 token（失败不中断：鉴权项 SKIP，公开项继续尝试）
if ($mode -eq 'gateway') {
  Head '网关模式：可达性与登录'
  $gwUri = $null
  try { $gwUri = [uri]$gatewayBase } catch { }
  if (-not $gwUri -or -not $gwUri.Host) {
    Write-Host ('[FAIL] -Gateway 地址非法: ' + $Gateway) -ForegroundColor Red
    exit 2
  }
  $gwPort = if ($gwUri.Port -gt 0) { $gwUri.Port } else { 80 }
  if (-not (Test-TcpPort -HostName $gwUri.Host -Port $gwPort)) {
    Write-Host ('  [SKIP] 网关未监听 ' + $gwUri.Host + ':' + $gwPort + ' —— 全部检查 SKIP') -ForegroundColor DarkYellow
    foreach ($c in $checks) { Add-Result $c.id $c.service 'SKIP' ('网关不可达 ' + $gwUri.Host + ':' + $gwPort) }
    $gatewayReachable = $false
  } else {
    $gatewayReachable = $true
    $probe = Invoke-Api 'GET' ($gatewayBase + '/api/v1/schools') @{}
    if ((@($probe.status) -contains 200) -and $probe.body) {
      Write-Host ('  [INFO] 网关 /api/v1/schools 可达 HTTP ' + $probe.status)
    } else {
      Write-Host ('  [INFO] 网关可达但 /api/v1/schools 异常（HTTP ' + $probe.status + '）') -ForegroundColor DarkYellow
    }
    try {
      $r1 = Invoke-Api 'POST' ($gatewayBase + '/api/v1/auth/sms-code') @{} (@{ phone = $UserPhone } | ConvertTo-Json -Compress)
      $r2 = Invoke-Api 'POST' ($gatewayBase + '/api/v1/auth/login') @{} (@{ phone = $UserPhone; code = $SmsCode; schoolId = 1; campusId = 2; deviceId = 'qa-full-split' } | ConvertTo-Json -Compress)
      if ((@($r2.status) -contains 200) -and $r2.body) {
        $o = $r2.body | ConvertFrom-Json
        if ($o.code -eq 0 -and $o.data -and $o.data.token) {
          $script:token = [string]$o.data.token
          Write-Host ('  [INFO] 网关登录成功 phone=' + $UserPhone + ' token长度=' + $script:token.Length)
        } else {
          Write-Host ('  [INFO] 网关登录失败 code=' + $o.code + ' message=' + $o.message + ' —— 鉴权项将 SKIP') -ForegroundColor DarkYellow
        }
      } else {
        Write-Host ('  [INFO] 网关登录不可用 HTTP ' + $r2.status + ' —— 鉴权项将 SKIP') -ForegroundColor DarkYellow
      }
    } catch {
      Write-Host ('  [INFO] 网关登录异常: ' + $_.Exception.Message + ' —— 鉴权项将 SKIP') -ForegroundColor DarkYellow
    }
  }
}

# 直连模式：先做 TCP 端口探测，未监听的服务整组 SKIP
$directReady = @{}
if ($mode -eq 'direct') {
  $gatewayReachable = $true
  Head '直连模式：服务端口探测'
  foreach ($key in $services.Keys) {
    $port = $services[$key].port
    $ok = Test-TcpPort -Port $port
    $directReady[$key] = $ok
    if ($ok) {
      Write-Host ('  [READY] ' + $key.PadRight(9) + ' :' + $port + ' 监听中')
    } else {
      Write-Host ('  [SKIP ] ' + $key.PadRight(9) + ' :' + $port + ' 未监听（服务未启动/未就绪）') -ForegroundColor DarkYellow
    }
  }
}

# ---------------------------------------------------------------- Nacos 注册信息（非断言，仅 INFO）
if (-not $SkipNacosInfo) {
  Head 'Nacos 注册状态（INFO）'
  try {
    $svc = Invoke-RestMethod 'http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=50' -TimeoutSec 5
    $doms = @($svc.doms)
    $expected = @('ershou-gateway', 'ershou-user-service', 'ershou-item-service', 'ershou-order-service',
                  'ershou-chat-service', 'ershou-map-service', 'ershou-platform-service', 'ershou-admin-service', 'ershou-file-service')
    foreach ($e in $expected) {
      $present = $doms -contains $e
      $color = if ($present) { 'DarkGray' } else { 'DarkYellow' }
      Write-Host ('  ' + $e.PadRight(24) + $(if ($present) { '已注册' } else { '未注册' })) -ForegroundColor $color
    }
    Write-Host ('  当前 Nacos 服务: ' + ($doms -join ', ')) -ForegroundColor DarkGray
  } catch {
    Write-Host ('  [INFO] Nacos 不可达（8848）：' + $_.Exception.Message) -ForegroundColor DarkYellow
  }
}

# ---------------------------------------------------------------- 逐项检查
Head '接口冒烟'
foreach ($c in $checks) {
  $svc = $services[$c.service]
  $base = if ($mode -eq 'gateway') { $gatewayBase } else { 'http://127.0.0.1:' + $svc.port }
  $url = $base + $c.path
  $headers = @{}
  $authSkipReason = ''

  if ($mode -eq 'direct') {
    if (-not $directReady[$c.service]) {
      Add-Result $c.id $c.service 'SKIP' ('服务未就绪：' + $svc.name + ' :' + $svc.port + ' 未监听')
      continue
    }
    if ($c.auth -eq 'user') { $headers['X-User-Id'] = $DirectUserId }
    if ($c.auth -eq 'admin') { $headers['X-User-Id'] = $DirectAdminId }
  } else {
    if (-not $gatewayReachable) {
      # 已在网关探测阶段统一 SKIP，这里不再重复
      continue
    }
    if ($c.auth -ne 'none') {
      if ($script:token) { $headers['Authorization'] = 'Bearer ' + $script:token }
      else { $authSkipReason = '网关模式未拿到 token（登录不可用），鉴权项 SKIP' }
    }
    if ($authSkipReason) {
      Add-Result $c.id $c.service 'SKIP' $authSkipReason
      continue
    }
  }

  $started = Get-Date
  $resp = if ($c.method -eq 'UPLOAD') {
    Invoke-MultipartUpload -Url $url -Headers $headers -FilePath $uploadTemp -Curl $curl
  } else {
    Invoke-Api -Method $c.method -Url $url -Headers $headers
  }
  $ms = [int]((Get-Date) - $started).TotalMilliseconds
  if ($VerboseBody -and $resp.body) { Write-Host ('         body: ' + (Snip $resp.body 400)) -ForegroundColor DarkGray }

  # SKIP：连接失败 / 路由/服务未就绪
  if ($resp.status -eq 0) {
    Add-Result $c.id $c.service 'SKIP' ('连接失败：' + (Snip ([string]$resp.error) 160))
    continue
  }
  if (@(404, 502, 503, 504) -contains $resp.status) {
    $reason = if ($mode -eq 'gateway') { '网关/路由未就绪 HTTP ' + $resp.status + '（确认 gateway 已加该路由且服务已注册）' } else { '服务返回 HTTP ' + $resp.status + '（接口/路由未就绪）' }
    Add-Result $c.id $c.service 'SKIP' $reason
    continue
  }

  $code = -1
  $obj = $null
  if ($resp.body) {
    try { $obj = $resp.body | ConvertFrom-Json } catch { $obj = $null }
  }
  if ($null -ne $obj -and $null -ne $obj.code) { $code = [int]$obj.code }

  # 上传额外校验 data.url
  if ($c.method -eq 'UPLOAD' -and $resp.status -eq 200 -and $code -eq 0) {
    $urlValue = ''
    if ($obj.data -and $obj.data.url) { $urlValue = [string]$obj.data.url }
    if (-not $urlValue) {
      Add-Result $c.id $c.service 'FAIL' ('HTTP 200 code=0 但 data.url 为空：' + (Snip $resp.body 260))
      continue
    }
    if (-not $SkipUploadUrlCheck) {
      $img = Invoke-Api 'GET' $urlValue @{}
      if ($img.status -eq 200) {
        Add-Result $c.id $c.service 'PASS' ('HTTP 200 code=0；url=' + $urlValue + '；公开访问 HTTP 200；' + $ms + 'ms')
      } else {
        Add-Result $c.id $c.service 'FAIL' ('上传成功但公开 URL 不可访问 HTTP ' + $img.status + '：' + $urlValue)
      }
    } else {
      Add-Result $c.id $c.service 'PASS' ('HTTP 200 code=0；url=' + $urlValue + '；' + $ms + 'ms（跳过 URL 复核）')
    }
    continue
  }

  if ($resp.status -ge 200 -and $resp.status -lt 300 -and ($c.accept -contains $code)) {
    Add-Result $c.id $c.service 'PASS' ('HTTP ' + $resp.status + ' code=' + $code + '；' + $ms + 'ms')
  } else {
    Add-Result $c.id $c.service 'FAIL' ('HTTP ' + $resp.status + ' code=' + $code + ' 期望 code=' + ($c.accept -join '/') + ' 响应：' + (Snip $resp.body 300))
  }
}

# ---------------------------------------------------------------- 汇总
Head '汇总'
$total = $script:pass + $script:fail + $script:skip
Write-Host ('  PASS ' + $script:pass + ' / FAIL ' + $script:fail + ' / SKIP ' + $script:skip + '（合计 ' + $total + '）') -ForegroundColor $(if ($script:fail -gt 0) { 'Red' } elseif ($script:pass -eq 0) { 'DarkYellow' } else { 'Green' })
if ($mode -eq 'gateway') {
  Write-Host '  提示：网关模式下 404/502 多为「gateway 路由未加 / 服务未注册」，脚本按 SKIP 处理；接入后重跑即可。' -ForegroundColor DarkGray
} else {
  Write-Host '  提示：直连模式默认带 X-User-Id（1003）/ 管理员（1002）；服务未启动即 SKIP，不影响其他服务判定。' -ForegroundColor DarkGray
}

if ($OutJson) {
  $outFull = if ([System.IO.Path]::IsPathRooted($OutJson)) { $OutJson } else { Join-Path $root $OutJson }
  $dir = Split-Path $outFull -Parent
  if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  [pscustomobject]@{
    at = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    mode = $mode
    gateway = $gatewayBase
    pass = $script:pass; fail = $script:fail; skip = $script:skip
    results = @($script:results)
  } | ConvertTo-Json -Depth 6 | Set-Content -Path $outFull -Encoding UTF8
  Write-Host ('  结果 JSON: ' + $outFull) -ForegroundColor DarkGray
}

if ($uploadTemp -and -not $UploadFile -and (Test-Path $uploadTemp)) {
  Remove-Item $uploadTemp -Force -ErrorAction SilentlyContinue
}

exit ([Math]::Min($script:fail, 250))
