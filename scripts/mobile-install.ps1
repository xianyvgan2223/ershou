<#
.SYNOPSIS
  安全安装移动端 APK：**保留 App 数据与登录态**。

.DESCRIPTION
  为什么需要这个脚本（2026-10-02 踩的坑）：

    `flutter install` 每次都会先 "Uninstalling old version" —— 这会把 App 的数据**整个清掉**，
    包括 SharedPreferences 里的登录 token 和后端地址。表现就是：
      - 每次我改完代码装一次 App，你打开就发现又要「重新连后端 + 重新登录」；
      - 而那个时间点往往刚好也在重启后端，于是看起来像"重启后端就要重新登录"。
    实测：后端重启**不影响**登录态（token 存在 Redis，10 年有效期，重启后照样有效）；
    真正清掉登录态的是 `flutter install`。

    `adb install -r` 是增量重装，**保留数据**（已实测：注入标记 → 重装 → 标记还在）。

用法：
  powershell -ExecutionPolicy Bypass -File .\scripts\mobile-install.ps1
  可选参数：
    -Phone <设备号>   默认 AQQB9X1526W02408
    -SkipBuild        跳过 flutter build（APK 已经是最新的）
    -Release          构建 release 包（默认 debug）

前置：手机已用 USB 连接且 `adb devices` 能看到它。
#>
param(
  [string]$Phone = 'AQQB9X1526W02408',
  [switch]$SkipBuild,
  [switch]$Release
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$appDir = Join-Path $root 'ershou_app'
$adb = 'D:\tools\Android\Sdk\platform-tools\adb.exe'
$pkg = 'com.ershou.ershou_app'

# ---------- 0. 检查 adb 与设备 ----------
if (-not (Test-Path $adb)) {
  Write-Host "找不到 adb：$adb" -ForegroundColor Red
  Write-Host '请确认 Android SDK 路径，或改脚本里的 $adb。' -ForegroundColor Yellow
  exit 1
}
$devices = & $adb devices 2>&1 | Select-String -Pattern "$Phone\s+device"
if (-not $devices) {
  Write-Host "设备 $Phone 未连接（adb devices 里没看到）。" -ForegroundColor Red
  & $adb devices
  exit 1
}
Write-Host "设备 $Phone 已连接" -ForegroundColor Green

# ---------- 1. 构建 ----------
$mode = if ($Release) { 'release' } else { 'debug' }
$apk = Join-Path $appDir "build\app\outputs\flutter-apk\app-$mode.apk"

if (-not $SkipBuild) {
  Write-Host "构建 $mode APK..." -ForegroundColor Cyan
  Push-Location $appDir
  if ($Release) { & flutter build apk --release } else { & flutter build apk --debug }
  $buildExit = $LASTEXITCODE
  Pop-Location
  if ($buildExit -ne 0) {
    Write-Host "构建失败（exit=$buildExit）" -ForegroundColor Red
    exit 1
  }
}

if (-not (Test-Path $apk)) {
  Write-Host "APK 不存在：$apk" -ForegroundColor Red
  Write-Host '去掉 -SkipBuild 重新构建试试。' -ForegroundColor Yellow
  exit 1
}
# 打印构建时间：只说"改完代码"不够 —— 用户看到的可能是旧包
# （踩过：后端重启了、代码也改了，却忘了重新构建 APK，用户反馈"还是老样子"）
$apkInfo = Get-Item $apk
Write-Host ("APK: " + $apk + "  (" + [math]::Round($apkInfo.Length / 1MB, 1) + " MB，构建于 " + $apkInfo.LastWriteTime.ToString('MM-dd HH:mm:ss') + ")") -ForegroundColor DarkGray

# ---------- 2. 记录安装前有没有登录态（装完对比，用来确认没清数据） ----------
function Read-Token {
  $xml = & $adb shell "run-as $pkg cat /data/data/$pkg/shared_prefs/FlutterSharedPreferences.xml" 2>&1
  $line = $xml | Select-String -Pattern 'ershou\.token'
  if (-not $line) { return $null }
  return [regex]::Match($line.Line, 'ershou\.token">([^<]+)<').Groups[1].Value
}
$tokenBefore = Read-Token
if ($tokenBefore) {
  Write-Host ("安装前：已有登录态（token " + $tokenBefore.Substring(0, [Math]::Min(13, $tokenBefore.Length)) + "…）") -ForegroundColor DarkGray
} else {
  Write-Host '安装前：没有登录态（装完需要连后端 + 登录一次）' -ForegroundColor DarkGray
}

# ---------- 3. 增量安装（-r 保留数据） ----------
Write-Host '增量安装（adb install -r，保留 App 数据）...' -ForegroundColor Cyan
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) {
  Write-Host '安装失败' -ForegroundColor Red
  exit 1
}

# ---------- 4. 校验数据没被清掉 ----------
Start-Sleep -Seconds 2
$tokenAfter = Read-Token
if ($tokenBefore) {
  if ($tokenAfter -eq $tokenBefore) {
    Write-Host '✓ 登录态已保留（不用重新登录）' -ForegroundColor Green
  } else {
    Write-Host '⚠ 登录态没保留住！这次安装把数据清掉了 —— 请把这个现象告诉开发者' -ForegroundColor Red
  }
} else {
  Write-Host '装好了。首次使用请在 App 里连后端地址并登录。' -ForegroundColor Green
}

Write-Host ''
Write-Host '提示：以后改完移动端代码都用这个脚本，别再用 flutter install。' -ForegroundColor DarkGray
Write-Host '      flutter install 每次都先卸载 → 清空 App 数据 → 需要重新登录。' -ForegroundColor DarkGray
