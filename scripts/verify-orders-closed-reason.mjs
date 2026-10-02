// 验收：没买到的买家，在「我的面交」里能不能看到"为什么没成"
//
// 背景：作废原因一直只写进 meetup_order.cancel_reason + 发一条私聊，`OrderVO` 根本没下发，
// 于是买家回到「我的面交」只看得到一个光秃秃的「已失效」，私聊消息刷过去就再也找不回原因。
//
// 覆盖：
//   1. 接口层：商品售出后，被挤掉的买家订单 status=5 且 cancelReason 有值
//   2. 页面层：买家「我的面交」卡片上真的把原因渲染出来（.orders__closed）
//   3. 一致性：同一件商品在详情页显示「已售出」，两处说法不打架
//
// 用法： node scripts/verify-orders-closed-reason.mjs
//   可选环境变量：ORDER_API（默认 http://127.0.0.1:8081/api/v1）
//                WEB_BASE（默认 http://localhost:5174）
//   前置：后端 + 前端 dev server 都已启动；.build/test-upload.jpg 存在；MySQL 可访问（用于清理）
import { existsSync, readFileSync } from 'node:fs'
import { mkdir, readFile } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SHOT_DIR = path.join(ROOT, '.build', 'admin-console')
const API = process.env.ORDER_API ?? 'http://127.0.0.1:8081/api/v1'
const WEB = process.env.WEB_BASE ?? 'http://localhost:5174'
const IMG = path.join(ROOT, '.build', 'test-upload.jpg')

let pass = 0
let fail = 0
const check = (d, c, detail = '') => {
  if (c) {
    pass++
    console.log(`  [PASS] ${d}`)
  } else {
    fail++
    console.log(`  [FAIL] ${d}  ${detail}`)
  }
}

async function req(method, url, { token, body, form } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  let payload
  if (form) payload = form
  else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  const res = await fetch(url, { method, headers, body: payload })
  return JSON.parse(await res.text())
}
const get = (u, t) => req('GET', u, { token: t })
const post = (u, b, t) => req('POST', u, { token: t, body: b })

async function login(phone) {
  await post(`${API}/auth/sms-code`, { phone })
  const r = await post(`${API}/auth/login`, { phone, code: '123456', schoolId: 1, campusId: 1 })
  if (r.code !== 0) throw new Error(`登录失败 ${phone}：${r.code} ${r.message}`)
  return r.data
}

/** 清理测试数据；MYSQL_PWD 从 .env 读，缺了就跳过（不影响验收结论） */
function cleanup(itemId) {
  try {
    const envFile = path.join(ROOT, '.env')
    let pwd = process.env.MYSQL_PASSWORD ?? ''
    if (existsSync(envFile)) {
      for (const line of readFileSync(envFile, 'utf8').split(/\r?\n/)) {
        const m = line.match(/^\s*MYSQL_PASSWORD\s*=\s*(.+?)\s*$/)
        if (m) pwd = m[1]
      }
    }
    execFileSync('C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe', [
      '-h', '127.0.0.1', '-P', '3306', '-u', 'ershou',
      '-e', `use ershou; delete from meetup_order where item_id=${itemId}; delete from item where id=${itemId};`,
    ], { env: { ...process.env, MYSQL_PWD: pwd }, stdio: 'ignore' })
    return true
  } catch {
    return false
  }
}

await mkdir(SHOT_DIR, { recursive: true })

let itemId = 0
let browser = null
try {
  console.log('')
  console.log(`=== 0. 准备数据（API=${API}）===`)
  const seller = await login('13800000008')
  const b1 = await login('13800000007')
  const b2 = await login('13800000011')
  const admin = await login('13800000009')
  console.log(`  卖家=${seller.user.id} 买家1=${b1.user.id} 买家2=${b2.user.id}`)

  const buf = await readFile(IMG)
  const form = new FormData()
  form.append('file', new Blob([buf], { type: 'image/jpeg' }), 'test-upload.jpg')
  const up = await req('POST', `${API}/files/upload?biz=item`, { token: seller.token, form })
  const title = `验收-没买到的买家-${Date.now().toString().slice(-6)} 加微信`
  const pub = await post(
    `${API}/items`,
    { title, description: '支持定金，加微信详聊', categoryId: 1, conditionLevel: 3, price: 9.9, images: [up.data.url] },
    seller.token,
  )
  itemId = pub.data.itemId
  await post(`${API}/admin/audit/items/${itemId}/approve`, {}, admin.token)

  // 两个买家都约，卖家只接受第一个 —— 第二个被挤掉，然后商品成交
  const o1 = (await post(`${API}/meetup-orders`, { itemId }, b1.token)).data.orders[0].orderId
  const o2 = (await post(`${API}/meetup-orders`, { itemId }, b2.token)).data.orders[0].orderId
  await post(`${API}/meetup-orders/${o1}/accept`, {}, seller.token)
  await post(`${API}/meetup-orders/${o1}/confirm`, {}, b1.token)
  await post(`${API}/meetup-orders/${o1}/confirm`, {}, seller.token)

  console.log('')
  console.log('=== 1. 接口层 ===')
  const sold = await get(`${API}/items/${itemId}`, seller.token)
  check('商品已售出（status=4）', sold.data.status === 4, String(sold.data.status))
  const o2vo = await get(`${API}/meetup-orders/${o2}`, b2.token)
  check('没买到的买家：订单已失效（status=5）', o2vo.data.status === 5, String(o2vo.data.status))
  check(
    '没买到的买家：能看到失效原因',
    o2vo.data.cancelReason === '卖家已把商品确认给其他同学',
    String(o2vo.data.cancelReason),
  )
  const listVo = (await get(`${API}/meetup-orders?role=buyer&pageSize=50`, b2.token)).data.records.find(
    (o) => o.id === o2,
  )
  check('列表接口同样带 cancelReason', !!listVo?.cancelReason, JSON.stringify(listVo?.cancelReason))

  console.log('')
  console.log('=== 2. 页面层（买家2 视角）===')
  const CHROME = [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  ].find((p) => existsSync(p))
  if (!CHROME) {
    console.log('  找不到 Chrome，跳过页面层')
  } else {
    const puppeteer = await import(
      new URL('../ershou-web/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js', import.meta.url).href
    )
    browser = await puppeteer.default.launch({
      executablePath: CHROME,
      headless: 'new',
      args: ['--no-sandbox', '--disable-gpu', '--window-size=1440,1000'],
    })
    const page = await browser.newPage()
    await page.setViewport({ width: 1440, height: 1000 })
    const errors = []
    page.on('pageerror', (e) => errors.push(String(e)))

    await page.goto(WEB, { waitUntil: 'domcontentloaded' })
    await page.evaluate((t) => localStorage.setItem('ershou_token', t), b2.token)

    await page.goto(`${WEB}/orders`, { waitUntil: 'networkidle2' })
    await page.waitForFunction(() => !!document.querySelector('.orders'), { timeout: 20000 })
    await new Promise((r) => setTimeout(r, 1500))
    const closed = await page.$$eval('.orders__closed', (els) =>
      els.map((e) => e.textContent.replace(/\s+/g, ' ').trim()),
    )
    console.log('  卡片上的原因：' + JSON.stringify(closed))
    check('卡片上出现失效原因', closed.some((t) => t.includes('卖家已把商品确认给其他同学')), JSON.stringify(closed))

    // 终态单的标签必须是灰的：以前按文案猜色，"已失效"落到 primary（品牌绿），像好消息
    const tagType = await page.$eval('.orders .el-tag', (e) => e.className)
    check('「已失效」标签不是品牌绿（primary）', !tagType.includes('el-tag--primary'), tagType)
    await page.screenshot({ path: path.join(SHOT_DIR, '20-orders-buyer-closed-reason.png') })

    await page.goto(`${WEB}/item/${itemId}`, { waitUntil: 'networkidle2' })
    await new Promise((r) => setTimeout(r, 1200))
    const bodyText = await page.evaluate(() => document.body.innerText)
    check('商品详情显示已售出（两处说法一致）', bodyText.includes('已售出'), bodyText.slice(0, 100).replace(/\s+/g, ' '))
    await page.screenshot({ path: path.join(SHOT_DIR, '21-item-sold.png') })
    check('页面无 JS 报错', errors.length === 0, errors.slice(0, 3).join(' | '))
  }
} catch (err) {
  fail++
  console.log('')
  console.log(`  [FATAL] ${err.message}`)
} finally {
  if (browser) await browser.close()
  if (itemId && cleanup(itemId)) console.log('  已清理测试商品与面交单')
  else if (itemId) console.log(`  清理失败，请手工删除 item ${itemId} 及其面交单`)
}

console.log('')
if (fail === 0) console.log(`全部通过：${pass} 项断言`)
else console.log(`通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
