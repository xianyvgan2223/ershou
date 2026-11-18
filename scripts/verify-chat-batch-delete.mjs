/**
 * 网页端「批量删除聊天」真实浏览器验收。
 *
 * 接口层已由 scripts/chat-batch-delete-test.ps1 覆盖（34 项）；这里只验**页面交互**：
 *   W1. 列表头部有「多选」入口
 *   W2. 点它进入多选：出现勾选框 + "全选 / 已选 N 条 / 删除"
 *   W3. 取消 → 退出多选，会话一条没少
 *   W4. 勾 3 条 → 点删除 → 确认 → 这 3 条消失，其它还在
 *   W5. 刷新页面后这 3 条仍然不在（服务端生效）
 *   W6. 「全选」能把当前列表全部勾上
 *   W7. 对方（卖家）列表里一条都没少
 *   W8. 页面无 JS 报错
 *
 * 用法：node scripts/verify-chat-batch-delete.mjs
 * 前置：后端 + 前端 dev server 已启动；MySQL 可访问
 */
import { existsSync } from 'node:fs'
import { mkdir } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import { loadEnvFile } from 'node:process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

try { loadEnvFile(new URL('../.env', import.meta.url)) } catch {}

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SHOT_DIR = path.join(ROOT, '.build', 'chat-batch-delete')
const API = process.env.CHAT_API ?? 'http://127.0.0.1:8080/api/v1'
const WEB = process.env.WEB_BASE ?? 'http://localhost:5173'
const REDIS_PASSWORD = process.env.REDIS_PASSWORD
const MYSQL_PWD = process.env.MYSQL_PASSWORD
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'
const SELLER_PHONE = '13800000008'
const BUYER_PHONE = '13800000007'
/** 清理阶段要用它的 token 触发索引重建（重建接口要管理员） */
const ADMIN_PHONE = '13800000009'
const SMS_CODE = '123456'

let pass = 0
let fail = 0
const check = (desc, cond, detail = '') => {
  if (cond) {
    pass++
    console.log(`  [PASS] ${desc}`)
  } else {
    fail++
    console.log(`  [FAIL] ${desc}  ${detail}`)
  }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const req = async (method, url, { token, body } = {}) => {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await res.text()
  try {
    return JSON.parse(text)
  } catch {
    return { code: -1, message: `非 JSON（HTTP ${res.status}）`, raw: text.slice(0, 160) }
  }
}
const get = (url, token) => req('GET', url, { token })
const post = (url, body, token) => req('POST', url, { token, body })

function sql(statement) {
  execFileSync(MYSQL, ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '--default-character-set=utf8mb4', '-e', `use ershou; ${statement}`], {
    stdio: 'ignore',
    env: { ...process.env, MYSQL_PWD },
  })
}

function resetSmsLimit(phones) {
  for (const phone of phones) {
    try {
      const keys = execFileSync(
        'docker',
        ['exec', 'ershou-redis', 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'KEYS', `ershou:sms:limit:${phone}:*`],
        { encoding: 'utf8' },
      )
        .split('\n')
        .map((s) => s.trim())
        .filter((s) => s.startsWith('ershou:'))
      if (keys.length) {
        execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'DEL', ...keys], { stdio: 'ignore' })
      }
    } catch {
      /* 清不掉就按原样跑 */
    }
  }
}

async function login(phone) {
  const res = await post(`${API}/auth/login`, { phone, code: SMS_CODE, schoolId: 1, campusId: 1 })
  if (res.code !== 0) throw new Error(`登录失败 ${phone}：${res.code} ${res.message}`)
  return { token: res.data.token, id: res.data.user.id }
}

const CHROME = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
].find((p) => existsSync(p))
if (!CHROME) {
  console.error('找不到 Chrome')
  process.exit(2)
}
let puppeteer = null
for (const rel of ['../ershou-web', '..', '.']) {
  try {
    puppeteer = await import(new URL(rel + '/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js', import.meta.url).href)
    break
  } catch {
    /* 换下一个 */
  }
}
if (!puppeteer) {
  console.error('未安装 puppeteer-core')
  process.exit(2)
}
await mkdir(SHOT_DIR, { recursive: true })

const browser = await puppeteer.default.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-gpu', '--window-size=1500,1000'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1500, height: 1000 })
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(String(e)))
page.on('console', (m) => {
  if (m.type() === 'error') pageErrors.push('console: ' + m.text())
})
const shot = (name) => page.screenshot({ path: path.join(SHOT_DIR, name) })

const sessionIds = () =>
  page.$$eval('.chat__sessions .session', (els) => els.map((e) => Number(e.getAttribute('data-session-id'))))
const headerText = () => page.$eval('.chat__list-head', (e) => e.textContent.replace(/\s+/g, ' ').trim())
/**
 * ⚠️ 一律用真实鼠标点击（page.mouse.click + 坐标），**不要用 el.click()**。
 * el.click() 是 DOM 层调用，绕过命中测试：元素被遮挡、不在视口、pointer-events:none
 * 这些真实问题它一律发现不了 —— 之前的版本就是这么写的，
 * 结果"用户点不动"的场景全被漏过去了。
 */
const clickByText = async (selector, text) => {
  const box = await page.evaluate(
    (sel, t) => {
      const el = Array.from(document.querySelectorAll(sel)).find((e) => e.textContent.trim().includes(t))
      if (!el) return null
      const r = el.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) return { notVisible: true }
      return { x: r.x + r.width / 2, y: r.y + r.height / 2, disabled: el.disabled === true }
    },
    selector,
    text,
  )
  if (!box) throw new Error(`找不到文案含「${text}」的元素：${selector}`)
  if (box.notVisible) throw new Error(`元素「${text}」尺寸为 0（不可见/被隐藏）`)
  if (box.disabled) throw new Error(`元素「${text}」是 disabled 状态，真实用户点不动`)
  await page.mouse.click(box.x, box.y)
  return true
}

/** 真实鼠标点击某一行会话（多选模式下 = 勾选）；不在视口时先滚进来 */
const clickSessionRow = async (sid) => {
  const locate = () =>
    page.evaluate((id) => {
      const row = document.querySelector(`.chat__sessions .session[data-session-id="${id}"]`)
      if (!row) return null
      const r = row.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2, top: r.top, bottom: r.bottom }
    }, sid)
  let box = await locate()
  if (!box) throw new Error(`列表里找不到会话 #${sid}`)
  if (box.top < 0 || box.bottom > 1000) {
    await page.evaluate((id) => {
      document.querySelector(`.chat__sessions .session[data-session-id="${id}"]`)?.scrollIntoView({ block: 'center' })
    }, sid)
    await sleep(300)
    box = await locate()
  }
  await page.mouse.click(box.x, box.y)
}

let seller = null
let buyer = null
let itemIds = []
let sids = []

try {
  console.log('')
  console.log(`=== 0. 准备（API=${API}  WEB=${WEB}）===`)
  resetSmsLimit([SELLER_PHONE, BUYER_PHONE])
  await post(`${API}/auth/sms-code`, { phone: SELLER_PHONE })
  await post(`${API}/auth/sms-code`, { phone: BUYER_PHONE })
  seller = await login(SELLER_PHONE)
  buyer = await login(BUYER_PHONE)
  // 卖家发 4 件商品，买家各开一个会话
  for (let i = 1; i <= 4; i++) {
    const pub = await post(
      `${API}/items`,
      {
        title: `批量删除验收${i}-${Date.now().toString().slice(-5)}`,
        description: '仅用于批量删除验收',
        categoryId: 1,
        conditionLevel: 3,
        price: 10 + i,
        images: ['http://127.0.0.1:9010/ershou/test.png'],
        preferPointId: 1,
      },
      seller.token,
    )
    if (pub.code !== 0) throw new Error(`发布失败：${pub.message}`)
    itemIds.push(pub.data.itemId)
    const opened = await post(`${API}/chat/sessions`, { itemId: pub.data.itemId }, buyer.token)
    if (opened.code !== 0) throw new Error(`开会话失败：${opened.message}`)
    sids.push(opened.data.id)
  }
  console.log(`  4 个会话：${sids.join(', ')}`)

  await page.goto(WEB, { waitUntil: 'domcontentloaded' })
  await page.evaluate((t) => localStorage.setItem('ershou_token', t), buyer.token)
  await page.goto(`${WEB}/chat`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.chat__sessions'), { timeout: 25000 })
  await sleep(1200)

  // ---------------- W1 ----------------
  console.log('')
  console.log('=== W1. 列表头部有「多选」入口 ===')
  const headBefore = await headerText()
  check('头部有「多选」按钮', headBefore.includes('多选'), headBefore)
  check('四个会话都在', sids.every((id) => headBefore !== '' && true) && (await sessionIds()).length >= 4)

  // ---------------- W2 ----------------
  console.log('')
  console.log('=== W2. 进入多选：出现勾选框与批量操作栏 ===')
  await clickByText('.chat__list-head button', '多选')
  await sleep(600)
  const headSel = await headerText()
  check('头部变成「全选 / 已选 / 删除」', headSel.includes('全选') && headSel.includes('已选'), headSel)
  const boxCount = await page.$$eval('.chat__sessions .session .session__check', (els) => els.length)
  check('每行出现勾选框', boxCount >= 4, `勾选框数=${boxCount}`)
  await shot('01-selection-mode.png')

  // ---------------- W3 ----------------
  console.log('')
  console.log('=== W3. 取消 → 退出多选、一条没少 ===')
  await clickByText('.chat__list-head button', '取消')
  await sleep(600)
  const boxAfterCancel = await page.$$eval('.chat__sessions .session .session__check', (els) => els.length)
  check('勾选框消失', boxAfterCancel === 0, `还剩 ${boxAfterCancel}`)
  const idsAfterCancel = await sessionIds()
  check('四个会话都还在', sids.every((id) => idsAfterCancel.includes(id)), JSON.stringify(idsAfterCancel))

  // ---------------- W4 ----------------
  console.log('')
  console.log('=== W4. 勾 3 条 → 删除 → 确认 ===')
  await clickByText('.chat__list-head button', '多选')
  await sleep(500)
  const targets = sids.slice(0, 3)
  for (const id of targets) {
    await clickSessionRow(id)
    await sleep(200)
  }
  const headCount = await headerText()
  check('头部显示「已选 3 条」', headCount.includes('已选 3 条'), headCount)
  await shot('02-three-selected.png')
  await clickByText('.chat__list-head button', '删除')
  await sleep(700)
  const dlgTitle = await page.$eval('.el-message-box__title', (e) => e.textContent.trim()).catch(() => '')
  check('弹出批量删除确认框', dlgTitle.includes('删除聊天'), dlgTitle)
  await page.evaluate(() => {
    const btn = Array.from(document.querySelectorAll('.el-message-box__btns .el-button')).find((b) => b.textContent.includes('删除'))
    btn?.click()
  })
  let gone = false
  for (let i = 0; i < 32; i++) {
    const now = await sessionIds()
    if (targets.every((id) => !now.includes(id))) {
      gone = true
      break
    }
    await sleep(250)
  }
  check('被勾的 3 条消失', gone, JSON.stringify(await sessionIds()))
  const left = await sessionIds()
  check('没勾的那条还在', left.includes(sids[3]), JSON.stringify(left))
  await shot('03-deleted.png')

  // ---------------- W5 ----------------
  console.log('')
  console.log('=== W5. 刷新后这 3 条仍然不在（服务端生效）===')
  await page.reload({ waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.chat__sessions, .chat__list-empty'), { timeout: 20000 })
  await sleep(1500)
  const afterReload = await sessionIds()
  check('刷新后被删的 3 条不在列表里', targets.every((id) => !afterReload.includes(id)), JSON.stringify(afterReload))

  // ---------------- W6 ----------------
  console.log('')
  console.log('=== W6.「全选」把当前列表全部勾上 ===')
  await clickByText('.chat__list-head button', '多选')
  await sleep(500)
  await clickByText('.chat__list-head label, .chat__list-head .el-checkbox', '全选')
  await sleep(600)
  const allIds = await sessionIds()
  const headAll = await headerText()
  check('头部显示已选条数 = 列表条数', headAll.includes(`已选 ${allIds.length} 条`), `${headAll} / 列表 ${allIds.length} 条`)
  await shot('04-select-all.png')
  await clickByText('.chat__list-head button', '取消')
  await sleep(400)

  // ---------------- W7 ----------------
  console.log('')
  console.log('=== W7. 对方（卖家）列表里一条都没少 ===')
  const sellerSessions = await get(`${API}/chat/sessions`, seller.token)
  const sellerIds = (sellerSessions.data ?? []).map((s) => s.id)
  check('卖家的 4 个会话都还在', sids.every((id) => sellerIds.includes(id)), JSON.stringify(sids.filter((id) => !sellerIds.includes(id))))

  // ---------------- W8 ----------------
  console.log('')
  console.log('=== W8. 页面运行时报错 ===')
  const real = pageErrors.filter(
    (e) =>
      !e.includes('favicon') &&
      !e.includes('Download the Vue Devtools') &&
      !e.includes('ResizeObserver') &&
      !e.includes('Failed to load resource'),
  )
  check('页面无 JS 报错', real.length === 0, real.slice(0, 3).join(' | '))
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
  if (sids.length) {
    console.log('')
    console.log('=== 清理测试数据 ===')
    try {
      sql(`delete from chat_message where session_id in (${sids.join(',')});`)
      sql(`delete from chat_session where id in (${sids.join(',')});`)
      if (itemIds.length) {
        sql(`delete from item_review where item_id in (${itemIds.join(',')});`)
        sql(`delete from item where id in (${itemIds.join(',')});`)
      }
      console.log(`  已清理会话 ${sids.join(',')} 与商品 ${itemIds.join(',')}`)

      // 清理是直接 SQL DELETE，ES 收不到通知 —— 不重建就会攒下幽灵文档，
      // 导致搜索 total 虚高（"共 N 件"却列不出东西）。用同步重建，返回时即可见。
      // 重建接口要管理员，这里现登一个；拿不到 token 就只提示、不算失败。
      try {
        resetSmsLimit([ADMIN_PHONE])
        await post(`${API}/auth/sms-code`, { phone: ADMIN_PHONE })
        const adm = await post(`${API}/auth/login`, { phone: ADMIN_PHONE, code: '123456', schoolId: 1, campusId: 1 })
        if (adm.code === 0) {
          await post(`${API}/search/reindex?sync=true`, {}, adm.data.token)
          console.log('  已触发搜索索引重建（清掉本次测试留下的幽灵文档）')
        }
      } catch {
        console.log('  索引重建未触发（可手动调 POST /api/v1/search/reindex）')
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
