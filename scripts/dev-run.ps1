<#
.SYNOPSIS
  一条命令进入可热重载的调试会话（改完代码按 r 热重载 / R 热重启）。
.DESCRIPTION
  热重载靠的是 flutter_tools 常驻的 kernel 编译器，所以必须由 'flutter run' 持有会话；
  光连 Dart VM service 调 reloadSources 是不行的（会报 Kernel isolate task 错误）。

  日常迭代流程：
    1) powershell -ExecutionPolicy Bypass -File .\scripts\dev-run.ps1
    2) 改 Dart 代码 -> 在同一个窗口按 r（热重载，保留页面状态）
    3) 改了 initState / 全局变量 / main() -> 按 R（热重启）
    4) 只有改了 pubspec 依赖、原生配置（AndroidManifest/gradle）才需要停掉重跑
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $root 'ershou_app')
Write-Host '正在启动 flutter run（保持这个窗口打开，按 r 热重载 / R 热重启 / q 退出）' -ForegroundColor Green
flutter run -d AQQB9X1526W02408
