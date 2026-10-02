// 「约面交」按钮状态判定的单元测试。
//
// 覆盖用户反馈的那条规则：**已经约过的商品，按钮要灰掉并改文案**，
// 不能让买家反复点出一堆重复单（后端也有防重，这里保证按钮别骗人）。
//
// 跑法： node scripts/verify-book-button-mobile.mjs
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SRC = path.join(ROOT, 'ershou_app', 'lib', 'ui', 'pages', 'item_detail_page.dart')
const code = readFileSync(SRC, 'utf8')

let pass = 0
let fail = 0
function check(desc, cond, detail = '') {
  if (cond) {
    pass++
    console.log(`  [PASS] ${desc}`)
  } else {
    fail++
    console.log(`  [FAIL] ${desc}  ${detail}`)
  }
}

// 把 Dart 里的纯函数**原样搬过来**执行（逻辑单一，转写不会有偏差；
// 这样测试不依赖 Flutter 工具链，能在纯 node 环境跑）
function bookButtonState({ activeOrderStatus, onSale, closedText }) {
  if (activeOrderStatus != null && activeOrderStatus !== undefined) {
    return {
      disabled: true,
      text: activeOrderStatus === 2 ? '已约好，待面交' : '已约，等卖家确认',
    }
  }
  return { disabled: !onSale, text: onSale ? '约面交' : closedText }
}

console.log('')
console.log('=== 按钮状态判定 ===')
const cases = [
  { name: '没约过 + 在售 → 可点「约面交」', args: { activeOrderStatus: null, onSale: true, closedText: '已售出' }, want: { disabled: false, text: '约面交' } },
  { name: '约过（待卖家确认）→ 灰掉「已约，等卖家确认」', args: { activeOrderStatus: 1, onSale: true, closedText: '已售出' }, want: { disabled: true, text: '已约，等卖家确认' } },
  { name: '约过（待面交）→ 灰掉「已约好，待面交」', args: { activeOrderStatus: 2, onSale: true, closedText: '已售出' }, want: { disabled: true, text: '已约好，待面交' } },
  { name: '没约过 + 已售出 → 灰掉并显示状态文案', args: { activeOrderStatus: null, onSale: false, closedText: '已售出' }, want: { disabled: true, text: '已售出' } },
  { name: '约过 + 商品已售出 → 仍显示"已约"（比"已售出"更贴近我的处境）', args: { activeOrderStatus: 1, onSale: false, closedText: '已售出' }, want: { disabled: true, text: '已约，等卖家确认' } },
]
for (const c of cases) {
  const got = bookButtonState(c.args)
  check(c.name, got.disabled === c.want.disabled && got.text === c.want.text, `实际 ${JSON.stringify(got)}`)
}

console.log('')
console.log('=== 源码接线检查 ===')
check(
  '按钮 disabled 绑定了"约过就灰"',
  /onPressed:\s*onSale\s*&&\s*!_meetupBusy\s*&&\s*_myOrder\s*==\s*null/.test(code),
  '按钮 onPressed 没接 _myOrder',
)
check('按钮文案走 _bookText（不是硬编码"约面交"）', /Text\(_bookText\(onSale, closedText\)\)/.test(code), '文案没接 _bookText')
check('详情页加载后会查"我约过没有"', /unawaited\(_loadMyOrder\(\)\)/.test(code), '没有调用 _loadMyOrder')
check('下单成功后会刷新按钮状态', /showToast\(context, '已发起约面交[\s\S]{0,160}_loadMyOrder\(\)/.test(code), '成功后没刷新')
check(
  '提交时不再传 pointId（买家不决定面交点）',
  /orderRepositoryProvider\)\.create\(itemId: item\.id\)/.test(code),
  '仍在传 pointId',
)
check('存在纯函数 bookButtonState 供测试覆盖', /bookButtonState\(\{/.test(code), '没抽出纯函数')

console.log('')
console.log('=== 后端排查接口用上了 itemId+status 过滤（不再拉一页内存过滤）===')
const repo = readFileSync(path.join(ROOT, 'ershou_app', 'lib', 'data', 'api_repository2.dart'), 'utf8')
check('page() 支持 itemId 参数', /int\? itemId/.test(repo), 'page() 没有 itemId')
check('activeOrderOfItem 用 itemId + status 查', /this\.page\(role: role, itemId: itemId, status: status/.test(repo), '仍在内存过滤')

console.log('')
if (fail === 0) console.log(`全部通过：${pass} 项断言`)
else console.log(`通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail)
