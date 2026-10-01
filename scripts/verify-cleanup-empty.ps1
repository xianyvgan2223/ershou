# 验证"清理没有商品的面交点"规则
#
# 规则（与 TradePointService.cleanupEmptyPoints 等价）：
#   点位上没有任何"还有戏"的商品（草稿0/审核中1/在售2/已锁定3），且点位创建超过 7 天 → 删除
#
# 用测试实例 8081 无关，这里直接对同一个库用测试校区 id=9 造数据 + 跑 SQL，
# 因为定时任务在本地不会立刻触发。跑完清理干净。
#
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\verify-cleanup-empty.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'

[System.IO.File]::ReadAllLines((Join-Path $root '.env'), [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
  $_ = $_.TrimStart([char]0xFEFF).Trim()
  # .env 里有的变量把注释写在同一行（例：`# 说明... VITE_AMAP_JS_KEY=xxx`），
  # 直接 split 会把 key 读成注释文本，所以先砍掉 # 之后的内容。
  $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
  if (-not $k -or -not $v) { continue }
  Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
}
$env:MYSQL_PWD = $env:MYSQL_PASSWORD

function Sql([string]$q) {
  (& $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou -N -B --default-character-set=utf8mb4 -e $q 2>$null) -join "`n"
}

$pass = 0; $fail = 0
function Check($desc, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ("  [PASS] " + $desc) -ForegroundColor Green }
  else { $script:fail++; Write-Host ("  [FAIL] " + $desc + "  " + $detail) -ForegroundColor Red }
}

Write-Host '=== 造测试数据（测试校区 id=9）===' -ForegroundColor Cyan
Sql @"
DELETE FROM item WHERE campus_id = 9;
DELETE FROM trade_point WHERE campus_id = 9;
DELETE FROM campus WHERE id = 9;
INSERT INTO campus (id, school_id, name, map_type, status) VALUES (9, 1, '清理验证校区', 2, 1);

-- A: 老点位（30 天前）+ 完全没有商品 → 应删
INSERT INTO trade_point (id, campus_id, name, source, point_type, status, audit_status, created_at)
VALUES (991001, 9, 'A-老点位无商品', 2, 3, 1, 1, NOW() - INTERVAL 30 DAY),
       -- B: 老点位 + 只有"已售出/已下架"商品 → 应删
       (991002, 9, 'B-老点位只有终结态商品', 2, 3, 1, 1, NOW() - INTERVAL 30 DAY),
       -- C: 老点位 + 有"审核中"商品 → 不能删（审核通过后面交点还要用）
       (991003, 9, 'C-老点位有审核中商品', 2, 3, 1, 1, NOW() - INTERVAL 30 DAY),
       -- D: 新点位（1 天前）+ 无商品 → 不能删（宽限期内）
       (991004, 9, 'D-新点位无商品', 2, 3, 1, 1, NOW() - INTERVAL 1 DAY),
       -- E: 老点位 + 有在售商品 → 不能删
       (991005, 9, 'E-老点位有在售商品', 2, 3, 1, 1, NOW() - INTERVAL 30 DAY);

INSERT INTO item (id, seller_id, category_id, campus_id, title, price, status, prefer_point_id)
VALUES (9910021, 2, 1, 9, 'B-已售出商品', 10.00, 4, 991002),
       (9910022, 2, 1, 9, 'B-已下架商品', 10.00, 5, 991002),
       (9910031, 2, 1, 9, 'C-审核中商品', 10.00, 1, 991003),
       (9910051, 2, 1, 9, 'E-在售商品',   10.00, 2, 991005);
"@ | Out-Null
Write-Host ("  造了 " + (Sql "SELECT COUNT(*) FROM trade_point WHERE campus_id=9;") + " 个点位") -ForegroundColor DarkGray

try {
  Write-Host ''
  Write-Host '=== 执行清理规则 ===' -ForegroundColor Cyan
  # 与 Java 里 countActiveItemsByPoint + 7 天宽限期完全等价
  Sql @"
DELETE p FROM trade_point p
WHERE p.campus_id = 9
  AND NOT EXISTS (
        SELECT 1 FROM item i
        WHERE i.prefer_point_id = p.id AND i.status IN (0, 1, 2, 3)
      )
  AND (p.created_at IS NULL OR p.created_at < NOW() - INTERVAL 7 DAY);
"@ | Out-Null

  $left = Sql "SELECT GROUP_CONCAT(name ORDER BY id) FROM trade_point WHERE campus_id = 9;"
  Write-Host ("  剩余点位：" + $left) -ForegroundColor DarkGray
  Write-Host ''

  Check 'A（老点位+无商品）被删除' ((Sql "SELECT COUNT(*) FROM trade_point WHERE id=991001;") -eq '0') '仍存在'
  Check 'B（老点位+只有已售出/已下架）被删除' ((Sql "SELECT COUNT(*) FROM trade_point WHERE id=991002;") -eq '0') '仍存在'
  Check 'C（老点位+有审核中商品）被保留' ((Sql "SELECT COUNT(*) FROM trade_point WHERE id=991003;") -eq '1') '被误删'
  Check 'D（新点位+无商品，宽限期内）被保留' ((Sql "SELECT COUNT(*) FROM trade_point WHERE id=991004;") -eq '1') '被误删'
  Check 'E（老点位+有在售商品）被保留' ((Sql "SELECT COUNT(*) FROM trade_point WHERE id=991005;") -eq '1') '被误删'
  Check '共删除 2 个' ((Sql "SELECT COUNT(*) FROM trade_point WHERE campus_id=9;") -eq '3') ("剩余=" + (Sql "SELECT COUNT(*) FROM trade_point WHERE campus_id=9;"))
}
finally {
  Sql "DELETE FROM item WHERE campus_id = 9; DELETE FROM trade_point WHERE campus_id = 9; DELETE FROM campus WHERE id = 9;" | Out-Null
  Write-Host '已清理测试数据' -ForegroundColor DarkGray
}

Write-Host ''
if ($fail -eq 0) { Write-Host ("全部通过：" + $pass + " 项断言") -ForegroundColor Green }
else { Write-Host ("通过 " + $pass + " 项，失败 " + $fail + " 项") -ForegroundColor Red }
exit $fail
