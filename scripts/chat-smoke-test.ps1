<#
.SYNOPSIS
  私聊（IM）端到端冒烟测试
.DESCRIPTION
  覆盖：WS 握手鉴权 → 从商品发起会话（复用/自聊拦截）→ REST 与 WS 双向收发 →
        未读数与已读回执 → 聊天风控（可疑词弱提醒 / 高危词拦截留痕且不投递）→
        历史消息游标翻页 → 越权与参数校验。
  脚本会先清空 chat_session / chat_message 与私聊相关的 Redis key，可重复执行。
  前置：后端已启动；卖家账号(13800000002)名下有在售商品（先跑 scripts/smoke-test.ps1 生成）；
        本机装了 node（脚本用 node 内置 fetch 与 WebSocket）；上传用图 .build/test-item.png。
  用法： powershell -ExecutionPolicy Bypass -File .\scripts\chat-smoke-test.ps1 [-Base http://127.0.0.1:8080/api/v1]
#>
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

# 用公开接口探活：这样 -Base 指向 Vite 代理（例如 http://127.0.0.1:5173/api/v1）时同样可用
try { Invoke-RestMethod -Uri ($Base + '/categories') -TimeoutSec 5 | Out-Null }
catch { Write-Host ('后端未启动或代理不通：' + $Base) -ForegroundColor Red; exit 1 }

Write-Host ''
Write-Host '=== 0. 清理上一轮私聊数据 ===' -ForegroundColor Cyan
& $MYSQL -h 127.0.0.1 -P 3306 -u ershou -e "use ershou; delete from chat_message; delete from chat_session; alter table chat_session auto_increment=1; alter table chat_message auto_increment=1;" 2>&1 | Out-Null
$cacheLua = "for i=1,#ARGV do local ks=redis.call('keys',ARGV[i]); for _,k in ipairs(ks) do redis.call('del',k) end end return 'ok'"
docker exec ershou-redis redis-cli -a $env:REDIS_PASSWORD --no-auth-warning eval $cacheLua 0 'ershou:chat:unread:*' 'ershou:chat:online:*' 'ershou:limit:chat:*' 2>&1 | Out-Null
Write-Host '  已清空 chat_session / chat_message 与私聊 Redis key（保留其他业务数据）' -ForegroundColor DarkGray

$env:CHAT_SMOKE_BASE = $Base
node (Join-Path $PSScriptRoot 'chat-smoke.mjs')
$code = $LASTEXITCODE
Write-Host ''
if ($code -eq 0) { Write-Host '===== 私聊冒烟全部通过 =====' -ForegroundColor Green }
else { Write-Host ("===== 私聊冒烟存在失败，退出码 " + $code + " =====") -ForegroundColor Red }
exit $code
