# =============================================================
# 发布 Nacos 配置：把 ershou-cloud/nacos-configs/*.yaml 推送到 Nacos 配置中心
# 用法：powershell -ExecutionPolicy Bypass -File scripts/cloud-publish-config.ps1
# 说明：Nacos 2.3.x OpenAPI（无鉴权，NACOS_AUTH_ENABLE=false）
# =============================================================
param(
  [string]$NacosAddr = 'http://127.0.0.1:8848',
  [string]$Group = 'DEFAULT_GROUP',
  [string]$ConfigDir = ''
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
if (-not $ConfigDir) { $ConfigDir = Join-Path $root 'ershou-cloud\nacos-configs' }

Write-Host "Nacos: $NacosAddr  Group: $Group  ConfigDir: $ConfigDir"

# 等待 Nacos 就绪（最多 120s）
$deadline = (Get-Date).AddSeconds(120)
do {
  try {
    $r = Invoke-WebRequest -Uri "$NacosAddr/nacos/v1/console/health/readiness" -TimeoutSec 3 -UseBasicParsing
    if ($r.StatusCode -eq 200) { Write-Host "[ OK ] Nacos 已就绪"; break }
  } catch { Start-Sleep -Seconds 3 }
} while ((Get-Date) -lt $deadline)

if (-not (Test-Path $ConfigDir)) { Write-Host "[FAIL] 配置目录不存在: $ConfigDir"; exit 1 }
$files = Get-ChildItem $ConfigDir -Filter '*.yaml'
if ($files.Count -eq 0) { Write-Host "[FAIL] $ConfigDir 下没有 yaml 配置"; exit 1 }

$ok = 0; $fail = 0
foreach ($f in $files) {
  $dataId = $f.BaseName + '.yaml'   # dataId 与文件名一致（如 ershou-gateway.yaml）
  $content = Get-Content $f.FullName -Raw -Encoding UTF8
  try {
    $body = @{
      dataId  = $dataId
      group   = $Group
      type    = 'yaml'
      content = $content
    }
    $r = Invoke-WebRequest -Uri "$NacosAddr/nacos/v1/cs/configs" -Method Post -Body $body -TimeoutSec 8 -UseBasicParsing
    if ($r.Content -match 'true') { Write-Host "[ OK ] 发布 $dataId"; $ok++ }
    else { Write-Host "[FAIL] 发布 $dataId -> $($r.Content)"; $fail++ }
  } catch {
    Write-Host ("[FAIL] 发布 {0} -> {1}" -f $dataId, $_.Exception.Message); $fail++
  }
}

# 校验列表
try {
  $r = Invoke-WebRequest -Uri "$NacosAddr/nacos/v1/cs/configs?search=accurate&dataId=&group=$Group&pageNo=1&pageSize=20" -TimeoutSec 5 -UseBasicParsing
  Write-Host "Nacos 配置列表: $($r.Content)"
} catch { Write-Host "（配置列表查询失败，可忽略）" }

Write-Host "=============================================="
Write-Host "发布完成：成功 $ok / 失败 $fail"
if ($fail -gt 0) { exit 1 }
