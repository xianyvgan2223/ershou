// 验收脚本：网页端运营管理后台（功能补全 + 交互体验 + 状态字典）
//
// 覆盖：
//   A. 接口层：概览新增订单统计 / 面交单关键词检索 / 日志动作与时间范围筛选 / 点位启用停用
//   B. 状态字典：被驳回的商品在卖家「我的发布」里必须显示「审核未通过」而不是「违规下架」
//   C. 页面层（真实 Chrome）：
//      - 侧栏 6 项导航 + 面交单管理页可用（筛选、详情抽屉时间线）
//      - 概览页面交单统计卡片
//      - 商品管理：URL 状态同步、详情抽屉
//      - 审核工作台：行级 loading（只转当前行）、待办角标审核后立刻减一（缓存失效）
//      - 点位下架 / 恢复闭环
//      - 日志页动作 + 时间范围筛选
//
// 用法：node scripts/verify-admin-console.mjs
//   可选环境变量：ADMIN_API（默认 http://127.0.0.1:8080/api/v1）、WEB_BASE（默认 http://localhost:5173）
//                WEB_BASE（默认 http://localhost:5174）
// 前置：后端已启动、MySQL 可访问、.build/test-upload.jpg 存在
import { existsSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { mkdir, readFile } from 'node:fs/promises'
import { loadEnvFile } from 'node:process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

try { loadEnvFile(new URL('../.env', import.meta.url)) } catch {}

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SHOT_DIR = path.join(ROOT, '.build', 'admin-console')
const API = process.env.ADMIN_API ?? 'http://127.0.0.1:8080/api/v1'
const WEB = process.env.WEB_BASE ?? 'http://localhost:5173'
const UPLOAD_IMG = path.join(ROOT, '.build', 'test-upload.jpg')

const ADMIN_PHONE = '13800000009'
const SELLER_PHONE = '13800000008'
const BUYER_PHONE = '13800000007'
/** 清理阶段要清掉概览的 30 秒读缓存，需要连 Redis */
const REDIS_PASSWORD = process.env.REDIS_PASSWORD
const SMS_CODE = '123456'

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

// ---------------- HTTP helpers ----------------
async function req(method, url, { token, body, form } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  let payload
  if (form) {
    payload = form
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  const res = await fetch(url, { method, headers, body: payload })
  const text = await res.text()
  try {
    return JSON.parse(text)
  } catch {
    return { code: -1, message: `非 JSON 响应（HTTP ${res.status}）`, raw: text.slice(0, 200) }
  }
}
const get = (url, token) => req('GET', url, { token })
const post = (url, body, token) => req('POST', url, { token, body })

async function login(phone) {
  await post(`${API}/auth/sms-code`, { phone })
  const res = await post(`${API}/auth/login`, { phone, code: SMS_CODE, schoolId: 1, campusId: 1 })
  if (res.code !== 0) throw new Error(`登录失败 ${phone}：${res.code} ${res.message}`)
  return { token: res.data.token, id: res.data.user.id }
}

async function publishSuspect(token, title) {
  const buf = await readFile(UPLOAD_IMG)
  const form = new FormData()
  form.append('file', new Blob([buf], { type: 'image/jpeg' }), 'test-upload.jpg')
  const up = await req('POST', `${API}/files/upload?biz=item`, { token, form })
  if (up.code !== 0) throw new Error(`图片上传失败：${up.code} ${up.message}`)
  const res = await post(
    `${API}/items`,
    {
      title,
      description: '支持定金，加微信详聊',
      categoryId: 1,
      conditionLevel: 3,
      price: 9.9,
      images: [up.data.url],
      preferPointId: 1,
    },
    token,
  )
  if (res.code !== 0) throw new Error(`发布失败：${res.code} ${res.message}`)
  return res.data.itemId
}

/**
 * 造一单面交单。
 *
 * ⚠️ 这个脚本原来**假设库里已经有面交单**（前置条件里只写了"后端已启动"），
 * 所以只要清过测试数据，面交单相关的断言就必然失败 —— 看起来像"后台坏了"，
 * 其实是测试没有自备数据。改成自己下单：买家买卖家刚发布的商品。
 *
 * ⚠️ 两个坑：
 * 1. 下单接口是 `POST /meetup-orders`（不是 `/orders` —— 写错会被 Spring 当成静态资源，
 *    报 NoResourceFoundException / 90001，看着像"系统故障"，其实是路径不存在）；
 * 2. **必须用标题干净的商品**：`publishSuspect` 的标题带敏感词，商品会进"审核中"(status=1)，
 *    而审核中的商品不允许约面交（10001 商品当前不可约面交）。所以要单独发一件正常商品。
 *
 * 返回结构是 { batchNo, orders: [orderBrief] }，不是单个对象。
 */
async function publishClean(token, title) {
  const buf = await readFile(UPLOAD_IMG)
  const form = new FormData()
  form.append('file', new Blob([buf], { type: 'image/jpeg' }), 'test-upload.jpg')
  const up = await req('POST', `${API}/files/upload?biz=item`, { token, form })
  if (up.code !== 0) throw new Error(`图片上传失败：${up.code} ${up.message}`)
  const res = await post(
    `${API}/items`,
    {
      title,
      description: '后台验收用，面交即可',
      categoryId: 1,
      conditionLevel: 3,
      price: 8.8,
      images: [up.data.url],
      preferPointId: 1,
    },
    token,
  )
  if (res.code !== 0) throw new Error(`发布失败：${res.code} ${res.message}`)
  return res.data.itemId
}

async function seedOneOrder(sellerToken, buyerToken, title) {
  const itemId = await publishClean(sellerToken, title)
  const res = await post(`${API}/meetup-orders`, { itemId, quantity: 1, remark: '后台验收用面交单' }, buyerToken)
  if (res.code !== 0) throw new Error(`下单失败：${res.code} ${res.message}`)
  const order = res.data?.orders?.[0]
  const orderId = order?.id ?? order?.orderId
  if (!orderId) throw new Error(`下单返回里没有单号：${JSON.stringify(res.data)?.slice(0, 200)}`)

  // 推到「已完成」：卖家接受（status 1→2）→ 卖家确认成交（1/2→3）。
  // 有已完成单，"按状态筛选"那条断言才有意义；否则筛完成必然为空。
  const accepted = await post(`${API}/meetup-orders/${orderId}/accept`, {}, sellerToken)
  if (accepted.code !== 0) throw new Error(`卖家接受失败：${accepted.code} ${accepted.message}`)
  const done = await post(`${API}/meetup-orders/${orderId}/confirm`, {}, sellerToken)
  if (done.code !== 0) throw new Error(`确认成交失败：${done.code} ${done.message}`)
  return { orderId, orderNo: order?.orderNo, itemId }
}

// ---------------- 浏览器 ----------------
const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]
const chromePath = CHROME_CANDIDATES.find((p) => existsSync(p))
if (!chromePath) {
  console.error('找不到 Chrome，无法做页面层验收')
  process.exit(2)
}
async function loadPuppeteer() {
  for (const rel of ['../ershou-web', '..', '.']) {
    try {
      const url = new URL(rel + '/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js', import.meta.url)
      return await import(url.href)
    } catch {
      /* 换下一个候选 */
    }
  }
  return null
}
const puppeteer = await loadPuppeteer()
if (!puppeteer) {
  console.error('未安装 puppeteer-core')
  process.exit(2)
}

await mkdir(SHOT_DIR, { recursive: true })

const browser = await puppeteer.default.launch({
  executablePath: chromePath,
  headless: 'new',
  args: ['--no-sandbox', '--disable-gpu', '--window-size=1600,1000'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1600, height: 1000 })

const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(String(e)))
page.on('console', (m) => {
  if (m.type() === 'error') pageErrors.push('console: ' + m.text())
})

async function setToken(token) {
  if (page.url() === 'about:blank') {
    await page.goto(WEB, { waitUntil: 'domcontentloaded' })
  }
  await page.evaluate((t) => localStorage.setItem('ershou_token', t), token)
}

async function gotoAdmin(pathname) {
  await page.goto(`${WEB}${pathname}`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.admin__side'), { timeout: 20000 })
  await sleep(600)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const shot = (name) => page.screenshot({ path: path.join(SHOT_DIR, name) })

/**
 * Element Plus 的 el-select 不是原生 <select>，page.select() 用不了。
 * 这里点开下拉再从可见的下拉面板里按文案选项。
 */
async function pickSelect(selectHandleOrSelector, optionText) {
  if (typeof selectHandleOrSelector === 'string') await page.click(selectHandleOrSelector)
  else await selectHandleOrSelector.click()
  await sleep(400)
  const ok = await page.evaluate((text) => {
    const panels = Array.from(document.querySelectorAll('.el-select-dropdown')).filter(
      (p) => p.offsetParent !== null,
    )
    for (const panel of panels) {
      const items = Array.from(panel.querySelectorAll('.el-select-dropdown__item'))
      const target = items.find((i) => i.textContent.trim() === text) ?? items.find((i) => i.textContent.includes(text))
      if (target) {
        target.click()
        return true
      }
    }
    return false
  }, optionText)
  if (!ok) throw new Error(`下拉里找不到选项「${optionText}」`)
  await sleep(500)
}

let seller = null
let admin = null
const seededIds = []

try {
  console.log('')
  console.log(`=== 0. 登录并准备数据（API=${API}）===`)
  admin = await login(ADMIN_PHONE)
  seller = await login(SELLER_PHONE)
  console.log(`  运营 id=${admin.id}  卖家 id=${seller.id}`)

  const me = await get(`${API}/users/me`, admin.token)
  if (!me.data?.isAdmin) {
    throw new Error(`运营账号 ${ADMIN_PHONE} 没有运营位，无法验收（需要 sys_user.role_flags |= 8）`)
  }

  // 4 件可疑商品：1 件用于角标断言、1 件用于行级 loading 断言、1 件驳回、1 件留作截图
  const stamp = Date.now().toString().slice(-6)
  for (let i = 1; i <= 4; i++) {
    const id = await publishSuspect(seller.token, `后台验收商品${i}-${stamp} 加微信转账有优惠`)
    seededIds.push(id)
  }
  console.log(`  已发布待审商品：${seededIds.join(', ')}`)

  // 面交单：自己造一单，别指望库里现成有（清过数据就全挂）
  const buyer = await login(BUYER_PHONE)
  const seededOrder = await seedOneOrder(seller.token, buyer.token, `后台验收面交单-${stamp}`)
  seededIds.push(seededOrder.itemId)
  console.log(`  已创建面交单：order#${seededOrder.orderId}（商品 #${seededOrder.itemId}）`)

  // ---------------- A. 接口层 ----------------
  console.log('')
  console.log('=== A. 接口层断言 ===')
  // 概览接口套了 30 秒 Redis 读缓存（key = ershou:admin:dashboard）。
  // 如果同一套 Redis 上还挂着升级前的旧实例，缓存里可能先读到只有 8 个字段的旧结构，
  // 所以这里重试几次；前端对缺字段是 `?? 0` 兜底的，不会白屏。
  let dash = await get(`${API}/admin/dashboard`, admin.token)
  for (let i = 0; i < 4 && typeof dash.data?.totalOrders !== 'number'; i++) {
    await sleep(1500)
    dash = await get(`${API}/admin/dashboard`, admin.token)
  }
  check('GET /admin/dashboard 成功', dash.code === 0, JSON.stringify(dash).slice(0, 160))
  for (const key of ['totalOrders', 'activeOrders', 'doneOrders', 'riskOrders', 'todayOrders', 'disabledPoints']) {
    check(`概览下发 ${key}`, typeof dash.data?.[key] === 'number', String(dash.data?.[key]))
  }

  const orders = await get(`${API}/admin/orders?pageNum=1&pageSize=5`, admin.token)
  check('GET /admin/orders 成功', orders.code === 0, JSON.stringify(orders).slice(0, 160))
  const anyOrder = orders.data?.records?.[0]
  check('面交单含状态文案', !!anyOrder?.statusText, JSON.stringify(anyOrder)?.slice(0, 120))
  if (anyOrder) {
    const kw = await get(
      `${API}/admin/orders?keyword=${encodeURIComponent(anyOrder.itemTitle)}&pageNum=1&pageSize=5`,
      admin.token,
    )
    check('按商品标题检索命中', (kw.data?.records ?? []).some((o) => o.id === anyOrder.id), JSON.stringify(kw).slice(0, 160))
    const byNo = await get(`${API}/admin/orders?keyword=${anyOrder.orderNo}&pageNum=1&pageSize=5`, admin.token)
    check('按单号检索命中', (byNo.data?.records ?? []).some((o) => o.id === anyOrder.id))
  }

  const logsByAction = await get(`${API}/admin/audit-logs?action=approve&pageNum=1&pageSize=20`, admin.token)
  check('日志动作筛选成功', logsByAction.code === 0)
  check(
    '日志动作筛选只返回 approve',
    (logsByAction.data?.records ?? []).length > 0 &&
      (logsByAction.data?.records ?? []).every((l) => l.action === 'approve'),
    JSON.stringify((logsByAction.data?.records ?? []).map((l) => l.action)),
  )
  const today = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const todayText = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`
  const logsRange = await get(
    `${API}/admin/audit-logs?startTime=${todayText}&endTime=${todayText}&pageNum=1&pageSize=5`,
    admin.token,
  )
  check('日志按单日范围筛选成功', logsRange.code === 0 && (logsRange.data?.total ?? 0) > 0, JSON.stringify(logsRange.data?.total))
  const logsBadRange = await get(`${API}/admin/audit-logs?startTime=不是日期&pageNum=1&pageSize=5`, admin.token)
  check('脏时间参数被忽略而不是报错', logsBadRange.code === 0)

  // 点位启用 / 停用
  const pts = await get(`${API}/admin/audit/points?auditStatus=1&status=1&pageNum=1&pageSize=1`, admin.token)
  const point = pts.data?.records?.[0]
  check('存在可处置的点位', !!point, JSON.stringify(pts).slice(0, 160))
  if (point) {
    const pointKw = `keyword=${encodeURIComponent(point.name)}`
    const noReason = await post(`${API}/admin/points/${point.id}/status`, { status: 0 }, admin.token)
    check('下架不填原因被拒', noReason.code !== 0, JSON.stringify(noReason))

    const off = await post(
      `${API}/admin/points/${point.id}/status`,
      { status: 0, reason: '验收脚本：临时下架' },
      admin.token,
    )
    check('填写原因后下架成功', off.code === 0, JSON.stringify(off))
    const offList = await get(`${API}/admin/audit/points?auditStatus=-1&status=0&${pointKw}`, admin.token)
    check(
      '下架后出现在「已下架」筛选',
      (offList.data?.records ?? []).some((p) => p.id === point.id),
      JSON.stringify(offList.data?.total),
    )
    const offAgain = await post(
      `${API}/admin/points/${point.id}/status`,
      { status: 0, reason: '验收脚本：重复下架' },
      admin.token,
    )
    check('重复下架被拒', offAgain.code !== 0, JSON.stringify(offAgain))

    const on = await post(`${API}/admin/points/${point.id}/status`, { status: 1 }, admin.token)
    check('恢复无需原因即可成功', on.code === 0, JSON.stringify(on))
    const onList = await get(`${API}/admin/audit/points?auditStatus=-1&status=1&${pointKw}`, admin.token)
    check(
      '恢复后回到「启用中」筛选',
      (onList.data?.records ?? []).some((p) => p.id === point.id),
      JSON.stringify(onList.data?.total),
    )
  }

  // ---------------- B. 状态字典（学生端） ----------------
  console.log('')
  console.log('=== B. 商品状态字典（被驳回 ≠ 违规下架）===')
  const rejectId = seededIds[2]
  const rej = await post(
    `${API}/admin/audit/items/${rejectId}/reject`,
    { reasonCode: 'CONTACT', reason: '留了微信号' },
    admin.token,
  )
  check('驳回商品成功', rej.code === 0, JSON.stringify(rej))
  const mine = await get(`${API}/items/mine?pageNum=1&pageSize=50`, seller.token)
  const rejected = (mine.data?.records ?? []).find((i) => i.id === rejectId)
  check('被驳回商品 status=6', rejected?.status === 6, String(rejected?.status))
  check('后端文案为「审核未通过」', rejected?.statusText === '审核未通过', String(rejected?.statusText))

  // ---------------- C. 页面层 ----------------
  console.log('')
  console.log('=== C1. 概览页：导航与面交单统计 ===')
  await setToken(admin.token)
  await gotoAdmin('/admin')
  const navLabels = await page.$$eval('.admin__menu .admin__nav-label', (els) => els.map((e) => e.textContent.trim()))
  // 7 项 = 概览 / 上架审核 / 审核工作台 / 商品 / 面交单 / 用户 / 日志
  check('侧栏 7 项导航', navLabels.length === 7, JSON.stringify(navLabels))
  check('侧栏包含「面交单管理」', navLabels.includes('面交单管理'), JSON.stringify(navLabels))
  check('侧栏包含「上架审核」', navLabels.includes('上架审核'), JSON.stringify(navLabels))
  // 视觉整改后概览页卡片类名变了（dash__label/dash__value → admin-stat__* 与 dash__row-*）。
  // 断言只关心「标签文字有没有渲染出来」，所以把两种都取上，不绑死某一种 DOM 实现。
  // ⚠️ 必须用 $$eval（复数）：$eval 回调拿到的是**单个元素**，对它调 .map 会直接抛
  //    "els.map is not a function"，测试看起来是"页面坏了"，其实是选择器 API 用错了。
  const dashLabels = await page.$$eval('.admin-stat__label, .dash__row-label', (els) => els.map((e) => e.textContent.trim()))
  for (const label of ['进行中面交', '今日新增', '爽约 / 纠纷', '已完成', '面交单总数', '已下架点位']) {
    check(`概览卡片「${label}」`, dashLabels.includes(label), JSON.stringify(dashLabels))
  }
  const dashValues = await page.$$eval('.admin-stat__num, .dash__row-value', (els) => els.map((e) => e.textContent.trim()))
  check('概览卡片数值都不是 undefined', dashValues.every((v) => v !== '' && v !== 'undefined'), JSON.stringify(dashValues))
  await shot('01-dashboard.png')

  console.log('')
  console.log('=== C2. 面交单管理页 ===')
  await gotoAdmin('/admin/orders')
  await page.waitForFunction(() => document.querySelectorAll('.admin-orders .el-table__row').length > 0, { timeout: 15000 })
  const orderRows = await page.$$eval('.admin-orders .el-table__row', (els) => els.length)
  check('面交单列表渲染出数据', orderRows > 0, `rows=${orderRows}`)
  await shot('02-orders-list.png')

  // 详情抽屉 + 时间线
  await page.click('.admin-orders .el-table__row .el-button')
  await page.waitForFunction(() => !!document.querySelector('.el-drawer__body .el-descriptions'), { timeout: 10000 })
  await sleep(400)
  const drawerTitle = await page.$eval('.el-drawer__title', (e) => e.textContent.trim())
  check('详情抽屉标题正确', drawerTitle.includes('面交单详情'), drawerTitle)
  const timelineItems = await page.$$eval('.el-drawer__body .el-timeline-item', (els) => els.length)
  check('详情抽屉有时间线', timelineItems > 0, `items=${timelineItems}`)
  await shot('03-order-detail.png')
  await page.keyboard.press('Escape')
  await sleep(500)

  // 状态筛选写进 URL
  await pickSelect('.admin-orders__toolbar .el-select', '已完成')
  await sleep(1500)
  const ordersUrl = page.url()
  check('面交单状态筛选同步进 URL', ordersUrl.includes('status=3'), ordersUrl)
  const statusTexts = await page.$$eval('.admin-orders .el-table__row .admin-orders__tag', (els) =>
    els.map((e) => e.textContent.trim()),
  )
  check('筛选后只剩已完成', statusTexts.length > 0 && statusTexts.every((t) => t === '已完成'), JSON.stringify(statusTexts))
  await shot('04-orders-filtered.png')

  console.log('')
  console.log('=== C3. 商品管理：URL 同步 + 详情抽屉 ===')
  await page.goto(`${WEB}/admin/items?status=2`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.admin-items .el-table'), { timeout: 20000 })
  await sleep(1000)
  const itemsSelectText = await page.$eval('.admin-items__toolbar .el-select', (e) => ({
    text: e.textContent.trim(),
    selected: e.querySelector('.el-select__selected-item')?.textContent.trim() ?? '',
    input: e.querySelector('input')?.value ?? '',
  }))
  check(
    '商品管理按 URL 回填筛选（在售）',
    JSON.stringify(itemsSelectText).includes('在售'),
    JSON.stringify(itemsSelectText),
  )
  const itemTags = await page.$$eval('.admin-items .el-table__row .admin-items__tag', (els) =>
    els.map((e) => e.textContent.trim()),
  )
  check(
    '筛选后只剩在售商品',
    itemTags.length === 0 || itemTags.every((t) => t === '在售'),
    JSON.stringify(itemTags),
  )
  await shot('05-items-status-filter.png')

  // 改筛选 -> URL 跟着变
  await pickSelect('.admin-items__toolbar .el-select', '审核未通过')
  await sleep(1500)
  check('商品状态筛选写进 URL', page.url().includes('status=6'), page.url())
  await shot('06-items-rejected.png')

  // 详情抽屉
  await page.goto(`${WEB}/admin/items`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => document.querySelectorAll('.admin-items .el-table__row').length > 0, { timeout: 20000 })
  await sleep(800)
  const detailBtn = await page.$$('.admin-items .el-table__row .el-button')
  // 操作列最后一个按钮可能是「下架」，用文本精确找「详情」
  const clicked = await page.evaluate(() => {
    const btns = Array.from(document.querySelectorAll('.admin-items .el-table__row .el-button'))
    const target = btns.find((b) => b.textContent.trim() === '详情')
    if (!target) return false
    target.click()
    return true
  })
  check('商品行有「详情」按钮', clicked && detailBtn.length > 0)
  if (clicked) {
    await page.waitForFunction(() => !!document.querySelector('.el-drawer__body .admin-items__detail-title'), { timeout: 10000 })
    await sleep(400)
    const drawerDesc = await page.$eval('.el-drawer__body .admin-items__detail-desc', (e) => e.textContent.trim())
    check('详情抽屉渲染描述表', drawerDesc.includes('卖家'), drawerDesc.slice(0, 80))
    await shot('07-item-detail.png')
    await page.keyboard.press('Escape')
    await sleep(400)
  }

  console.log('')
  console.log('=== C4. 审核工作台：行级 loading + 待办角标 ===')
  await gotoAdmin('/admin/audit')
  await page.waitForFunction(
    () => document.querySelectorAll('.audit__panel--item .el-table__row').length > 0,
    { timeout: 20000 },
  )
  await sleep(800)
  await shot('08-audit-items.png')

  /**
   * ⚠️ 侧栏有**两个**不同的角标：上架审核（待人工确认上架数）和审核工作台（待审商品+点位）。
   * `.admin__badge` 只会取到第一个 —— 拿它跟 pendingItems 比就是"读错了角标"，
   * 会得到一个永远对不上的数字（实测：工作台 19，取到的是上架审核的 8）。
   * 所以按菜单项文字精确定位到「审核工作台」那个角标。
   */
  const workbenchBadge = () =>
    page.evaluate(() => {
      const item = Array.from(document.querySelectorAll('.el-menu-item')).find((el) =>
        el.textContent.includes('审核工作台'),
      )
      const badge = item?.querySelector('.admin__badge')
      return badge ? Number(badge.textContent.trim()) : null
    })
  const badgeBefore = await workbenchBadge()
  check('侧栏有待办角标', typeof badgeBefore === 'number' && badgeBefore > 0, String(badgeBefore))

  // 行级 loading：把网络压慢，点第一行的「通过」，同一时刻只应有 1 个 loading 按钮
  const cdp = await page.createCDPSession()
  await cdp.send('Network.enable')
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 1500,
    downloadThroughput: -1,
    uploadThroughput: -1,
  })
  await page.evaluate(() => {
    const btn = Array.from(
      document.querySelectorAll('.audit__panel--item .el-table__row .el-button'),
    ).find((b) => b.textContent.trim() === '通过')
    btn?.click()
  })
  await sleep(400)
  const loadingCount = await page.$$eval(
    '.audit__panel--item .el-table__row .el-button.is-loading',
    (els) => els.length,
  )
  check('行级 loading：同时只有 1 个按钮转圈', loadingCount === 1, `loading=${loadingCount}`)
  await shot('09-audit-row-loading.png')

  await cdp.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 0,
    downloadThroughput: -1,
    uploadThroughput: -1,
  })
  await page.waitForFunction(
    () => !document.querySelector('.audit__panel--item .el-table__row .el-button.is-loading'),
    { timeout: 20000 },
  )
  await sleep(2500)
  const badgeAfter = await workbenchBadge()
  check(
    `审核后待办角标立刻减一（${badgeBefore} -> ${badgeAfter}）`,
    typeof badgeAfter === 'number' && badgeAfter === badgeBefore - 1,
    `before=${badgeBefore} after=${badgeAfter}`,
  )

  console.log('')
  console.log('=== C5. 点位下架 / 恢复（页面闭环）===')
  // 点位 tab 默认「不限审核状态」，进页面就该有数据
  await gotoAdmin('/admin/audit?tab=point')
  await page.waitForFunction(
    () => document.querySelectorAll('.audit__panel--point .el-table__row').length > 0,
    { timeout: 20000 },
  )
  await sleep(800)
  const activeTab = await page.$eval('.el-tabs__item.is-active', (e) => e.textContent.trim())
  check('URL 的 tab=point 生效', activeTab.includes('点位'), activeTab)
  await shot('10-audit-points.png')

  // 切到「已通过」，找一行点「下架」
  const auditSelects = await page.$$('.audit__panel--point .audit__toolbar .el-select')
  await pickSelect(auditSelects[0], '已通过')
  await sleep(1500)
  check('点位工具栏有两个筛选（审核状态 / 地图状态）', auditSelects.length >= 2, `selects=${auditSelects.length}`)

  const offlineClicked = await page.evaluate(() => {
    const btns = Array.from(document.querySelectorAll('.audit__panel--point .el-table__row .el-button'))
    const target = btns.find((b) => b.textContent.trim() === '下架')
    if (!target) return false
    target.click()
    return true
  })
  check('已通过点位有「下架」入口', offlineClicked)
  // 记住点的是哪个点位，验收完恢复原状，别把测试数据留在库里
  const offlinePointName = offlineClicked
    ? await page.evaluate(() => {
        const btns = Array.from(document.querySelectorAll('.audit__panel--point .el-table__row .el-button'))
        const target = btns.find((b) => b.textContent.trim() === '下架')
        return target?.closest('.el-table__row')?.querySelector('.audit__strong')?.textContent.trim() ?? ''
      })
    : ''
  if (offlineClicked) {
    await page.waitForFunction(
      () => {
        const title = document.querySelector('.el-dialog__title')
        return title && title.textContent.includes('下架点位')
      },
      { timeout: 10000 },
    )
    await sleep(1500)
    // 选第一个原因模板再补充说明，验证「下架必须填原因」
    const reasonPicked = await page.evaluate(() => {
      const dialogs = Array.from(document.querySelectorAll('.el-dialog'))
      const dlg = dialogs.find((d) => d.querySelector('.el-dialog__title')?.textContent.includes('下架点位'))
      if (!dlg) return false
      const tag = dlg.querySelector('.audit__reason')
      if (!tag) return false
      tag.click()
      return true
    })
    check('下架弹窗能选原因模板', reasonPicked)
    await shot('11-point-offline-dialog.png')
    await page.evaluate(() => {
      const dialogs = Array.from(document.querySelectorAll('.el-dialog'))
      const dlg = dialogs.find((d) => d.querySelector('.el-dialog__title')?.textContent.includes('下架点位'))
      const btn = Array.from(dlg.querySelectorAll('.el-dialog__footer .el-button')).find((b) =>
        b.textContent.includes('确认下架'),
      )
      btn?.click()
    })
    // 等「点位已下架」的成功提示
    let successMsg = ''
    try {
      await page.waitForFunction(
        () => !!document.querySelector('.el-message--success'),
        { timeout: 15000 },
      )
      successMsg = await page.$eval('.el-message--success', (e) => e.textContent.trim())
    } catch {
      /* 下面按断言失败处理 */
    }
    check('下架提交后出现成功提示', successMsg.includes('点位已下架'), successMsg)
    await sleep(1500)
    const mapTags = await page.$$eval('.audit__panel--point .el-table__row .el-tag', (els) =>
      els.map((e) => e.textContent.trim()),
    )
    check('列表里出现「未上架」标记', mapTags.includes('未上架'), JSON.stringify(mapTags.slice(0, 12)))
    await shot('12-point-offline-done.png')

    // 还原：把刚才下架的点位重新启用，避免验收留下脏数据
    if (offlinePointName) {
      const found = await get(
        `${API}/admin/audit/points?auditStatus=-1&status=0&keyword=${encodeURIComponent(offlinePointName)}`,
        admin.token,
      )
      const target = (found.data?.records ?? []).find((p) => p.name === offlinePointName)
      if (target) {
        const restored = await post(`${API}/admin/points/${target.id}/status`, { status: 1 }, admin.token)
        check('验收后已还原该点位为启用', restored.code === 0, JSON.stringify(restored))
      }
    }
  }

  console.log('')
  console.log('=== C6. 操作日志：动作 + 时间范围 ===')
  await gotoAdmin('/admin/logs')
  await page.waitForFunction(() => !!document.querySelector('.admin-logs .el-table'), { timeout: 20000 })
  await sleep(800)
  const logSelects = await page.$$eval('.admin-logs__toolbar .el-select', (els) =>
    els.map((e) => e.querySelector('.el-select__placeholder')?.textContent.trim() ?? e.textContent.trim()),
  )
  check('日志页有两个下拉（模块 / 动作）', logSelects.length >= 2, JSON.stringify(logSelects))
  check('日志页有日期区间选择', (await page.$$('.admin-logs__toolbar .el-date-editor')).length === 2)
  await shot('13-logs.png')

  // 点「近 7 天」快捷区间，URL 应带上 from/to
  await page.evaluate(() => {
    const btn = Array.from(document.querySelectorAll('.admin-logs__quick .el-button')).find((b) =>
      b.textContent.includes('近 7 天'),
    )
    btn?.click()
  })
  await sleep(1500)
  check('快捷区间写进 URL', page.url().includes('from=') && page.url().includes('to='), page.url())
  const logRows = await page.$$eval('.admin-logs .el-table__row', (els) => els.length)
  check('时间范围筛选后有数据', logRows > 0, `rows=${logRows}`)
  await shot('14-logs-date-range.png')

  // 动作筛选
  const logSelects2 = await page.$$('.admin-logs__toolbar .el-select')
  await pickSelect(logSelects2[1], '下架点位')
  await sleep(1800)
  check('动作筛选写进 URL', page.url().includes('action=point-offline'), page.url())
  const offlineLogs = await page.$$eval('.admin-logs .el-table__row', (els) =>
    els.map((e) => e.querySelector('.el-tag')?.textContent.trim()),
  )
  check(
    '日志页能看到刚做的「下架点位」',
    offlineLogs.includes('点位'),
    JSON.stringify(offlineLogs.slice(0, 6)),
  )
  await shot('15-logs-action-filter.png')

  console.log('')
  console.log('=== C7. 卖家「我的发布」状态文案 ===')
  await setToken(seller.token)
  await page.goto(`${WEB}/publish-list`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.my-items'), { timeout: 20000 })
  await sleep(1500)
  await page.evaluate(() => {
    const tab = Array.from(document.querySelectorAll('.el-tabs__item')).find((t) =>
      t.textContent.includes('已下架'),
    )
    tab?.click()
  })
  await sleep(1500)
  const sellerTags = await page.$$eval('.my-items .el-tag', (els) => els.map((e) => e.textContent.trim()))
  check('被驳回商品显示「审核未通过」', sellerTags.includes('审核未通过'), JSON.stringify(sellerTags))
  check('卖家页不再把它显示成「违规下架」', !sellerTags.includes('违规下架'), JSON.stringify(sellerTags))
  check('tab 名为「已下架 / 驳回」', (await page.$$eval('.el-tabs__item', (e) => e.map((x) => x.textContent.trim()))).some((t) => t.includes('驳回')))
  await shot('16-seller-publish-list.png')

  console.log('')
  console.log('=== 页面运行时报错 ===')
  const realErrors = pageErrors.filter(
    (e) => !e.includes('favicon') && !e.includes('Download the Vue Devtools') && !e.includes('ResizeObserver'),
  )
  check('页面无 JS 报错', realErrors.length === 0, realErrors.slice(0, 5).join(' | '))
} catch (err) {
  fail++
  console.log('')
  console.log(`  [FATAL] ${err.message}`)
  try {
    await shot('99-fatal.png')
  } catch {
    /* 截图失败就算了 */
  }
} finally {
  await browser.close()

  // 本脚本每跑一次都会发 5 件商品（4 件可疑 + 1 件用于面交单）。不清理的话它们会一直堆在
  // 后台的「待审队列」里 —— 实测攒到过 20 多条，后台看着一片"待审核"，
  // 还把真实的待办数字顶高了（用户看到的"花"有一部分就是这么来的）。
  if (seededIds.length) {
    console.log('')
    console.log('=== 清理本次造的数据 ===')
    const ids = seededIds.join(',')
    try {
      execFileSync(
        'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
        [
          '-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '--default-character-set=utf8mb4', '-e',
          `use ershou;
           DELETE FROM chat_message WHERE session_id IN (SELECT id FROM chat_session WHERE item_id IN (${ids}));
           DELETE FROM chat_session WHERE item_id IN (${ids});
           DELETE FROM meetup_order WHERE item_id IN (${ids});
           DELETE FROM cart_item    WHERE item_id IN (${ids});
           DELETE FROM favorite     WHERE item_id IN (${ids});
           DELETE FROM item_review  WHERE item_id IN (${ids});
           DELETE FROM item         WHERE id IN (${ids});`,
        ],
        { stdio: 'ignore', env: { ...process.env, MYSQL_PWD: process.env.MYSQL_PASSWORD } },
      )
      // 概览有 30 秒 Redis 读缓存，清掉让下次进后台看到真实数字
      execFileSync(
        'docker',
        ['exec', 'ershou-redis', 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'DEL', 'ershou:admin:dashboard'],
        { stdio: 'ignore' },
      )
      console.log(`  已清理商品 ${ids} 及其关联数据`)
      // 清理是直接 SQL DELETE，ES 收不到通知 —— 不重建就会攒下幽灵文档，
      // 导致搜索 total 虚高（"共 N 件"却列不出东西）。走接口按当前索引重建。
      if (admin?.token) {
        try {
          await post(`${API}/search/reindex?sync=true`, {}, admin.token)
          console.log('  已触发搜索索引重建（清掉本次测试留下的幽灵文档）')
        } catch {
          console.log('  索引重建未触发（可手动调 POST /api/v1/search/reindex）')
        }
      }
    } catch (e) {
      console.log(`  清理失败（可手动删）：${e.message}`)
    }
  }
}

console.log('')
console.log(`截图目录：${SHOT_DIR}`)
if (fail === 0) console.log(`全部通过：${pass} 项断言`)
else console.log(`通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
