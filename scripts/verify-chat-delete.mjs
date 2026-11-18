/**
 * 网页端「删除聊天」真实浏览器验收。
 *
 * 接口层已由 scripts/chat-delete-test.ps1 覆盖（46 项）；这里只验**页面交互**：
 *   W1. 会话列表项的「⋯」菜单里有「删除聊天」
 *   W2. 点它弹确认框，取消 → 会话还在（不误删）
 *   W3. 确认 → 该会话从左侧列表消失
 *   W4. 刷新页面后仍然不出现（确认是服务端生效，不是只在前端内存里抹掉）
 *   W5. 对方（卖家）列表里该会话仍在 —— 不能删掉别人的数据
 *   W6. 卖家再发消息 → 买家刷新后会话重新出现（与微信一致）
 *   W7. 页面无 JS 报错
 *
 * 用法：node scripts/verify-chat-delete.mjs
 *   可选：CHAT_API（默认 http://127.0.0.1:8080/api/v1）、WEB_BASE（默认 http://localhost:5173）
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
const SHOT_DIR = path.join(ROOT, '.build', 'chat-delete')
const API = process.env.CHAT_API ?? 'http://127.0.0.1:8080/api/v1'
const WEB = process.env.WEB_BASE ?? 'http://localhost:5173'
const REDIS_PASSWORD = process.env.REDIS_PASSWORD

const SELLER_PHONE = '13800000008'
const BUYER_PHONE = '13800000007'
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

/** 清"每日验证码次数"（key 带日期后缀，必须按 pattern 删），否则跑几轮就登录不了 */
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
        execFileSync(
          'docker',
          ['exec', 'ershou-redis', 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'DEL', ...keys],
          { stdio: 'ignore' },
        )
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

// ---------------- 浏览器 ----------------
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
    /* 换下一个候选 */
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

/**
 * 左侧列表里是否存在**指定 sessionId** 的会话项。
 *
 * ⚠️ 不要用"对方昵称"判断！测试账号之间可能有几十条会话、对方都叫「同学0008」，
 * 删掉的那条消失了、其它同名会话还在，用昵称做判据永远为真 —— 这个坑真踩过，
 * 表现是"功能明明是好的、测试却一直红"，比功能坏了更费时间。
 * 准确判据只有一个：会话 id。
 */
const hasSessionItem = (sid) =>
  page.$$eval(
    '.chat__sessions .session',
    (els, id) => els.some((e) => Number(e.getAttribute('data-session-id')) === Number(id)),
    sid,
  )
const sessionCount = () => page.$$eval('.chat__sessions .session', (els) => els.length)

let seller = null
let buyer = null
let itemId = null
let sessionId = null

try {
  console.log('')
  console.log(`=== 0. 准备（API=${API}  WEB=${WEB}）===`)
  resetSmsLimit([SELLER_PHONE, BUYER_PHONE])
  await post(`${API}/auth/sms-code`, { phone: SELLER_PHONE })
  await post(`${API}/auth/sms-code`, { phone: BUYER_PHONE })
  seller = await login(SELLER_PHONE)
  buyer = await login(BUYER_PHONE)
  console.log(`  卖家 id=${seller.id}  买家 id=${buyer.id}`)

  // 卖家发一件商品，买家从它发起会话（保证双方都是会话成员）
  const pub = await post(
    `${API}/items`,
    {
      title: `网页删聊天验收-${Date.now().toString().slice(-6)}`,
      description: '仅用于删除聊天验收',
      categoryId: 1,
      conditionLevel: 3,
      price: 9,
      images: ['http://127.0.0.1:9010/ershou/test.png'],
      preferPointId: 1,
    },
    seller.token,
  )
  if (pub.code !== 0) throw new Error(`发布商品失败：${pub.code} ${pub.message}`)
  itemId = pub.data.itemId
  const opened = await post(`${API}/chat/sessions`, { itemId }, buyer.token)
  if (opened.code !== 0) throw new Error(`开会话失败：${opened.code} ${opened.message}`)
  sessionId = opened.data.id
  const peerName = opened.data.peerName
  console.log(`  会话 #${sessionId}，对方昵称=${peerName}`)

  await page.goto(WEB, { waitUntil: 'domcontentloaded' })
  await page.evaluate((t) => localStorage.setItem('ershou_token', t), buyer.token)
  await page.goto(`${WEB}/chat`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.chat__sessions'), { timeout: 25000 })
  await sleep(1200)

  // ---------------- W1 ----------------
  console.log('')
  console.log('=== W1. 会话项有「删除聊天」入口 ===')
  const beforeCount = await sessionCount()
  check(
    '会话列表渲染出该会话',
    await hasSessionItem(sessionId),
    `共 ${beforeCount} 条会话，目标 #${sessionId} 不在其中`,
  )
  await page.hover('.chat__sessions .session')
  await sleep(300)
  await page.click('.chat__sessions .session .session__more-btn')
  await sleep(500)
  const menuText = await page.$$eval('.el-dropdown-menu__item', (els) => els.map((e) => e.textContent.trim()))
  check('菜单里有「删除聊天」', menuText.includes('删除聊天'), JSON.stringify(menuText))
  await shot('01-menu.png')

  // ---------------- W2 ----------------
  console.log('')
  console.log('=== W2. 取消不会删（防误触）===')
  await page.evaluate(() => {
    const item = Array.from(document.querySelectorAll('.el-dropdown-menu__item')).find((e) =>
      e.textContent.includes('删除聊天'),
    )
    item?.click()
  })
  await sleep(700)
  const dialogTitle = await page.$eval('.el-message-box__title', (e) => e.textContent.trim()).catch(() => '')
  check('弹出确认框', dialogTitle.includes('删除聊天'), dialogTitle)
  await shot('02-confirm.png')
  await page.evaluate(() => {
    const btn = Array.from(document.querySelectorAll('.el-message-box__btns .el-button')).find((b) =>
      b.textContent.includes('取消'),
    )
    btn?.click()
  })
  await sleep(900)
  check('取消后会话仍在列表里', await hasSessionItem(sessionId))

  // ---------------- W3 ----------------
  console.log('')
  console.log('=== W3. 确认删除 → 从左侧列表消失 ===')
  await page.hover('.chat__sessions .session')
  await sleep(300)
  await page.click('.chat__sessions .session .session__more-btn')
  await sleep(500)
  await page.evaluate(() => {
    const item = Array.from(document.querySelectorAll('.el-dropdown-menu__item')).find((e) =>
      e.textContent.includes('删除聊天'),
    )
    item?.click()
  })
  await sleep(700)
  await page.evaluate(() => {
    const btn = Array.from(document.querySelectorAll('.el-message-box__btns .el-button')).find((b) =>
      b.textContent.includes('删除'),
    )
    btn?.click()
  })
  const confirmBtnText = await page.$$eval('.el-message-box__btns .el-button', (els) => els.map((e) => e.textContent.trim())).catch(() => [])
  console.log('    [debug] 确认框按钮 =', JSON.stringify(confirmBtnText))
  let gone = false
  for (let i = 0; i < 32; i++) {
    if (!(await hasSessionItem(sessionId))) {
      gone = true
      break
    }
    await sleep(250)
  }
  const afterCount = await sessionCount()
  console.log(`    [debug] 删除前 ${beforeCount} 条 → 删除后 ${afterCount} 条（目标 #${sessionId}）`)
  check('会话已从列表消失', gone, `目标 #${sessionId} 仍在，共 ${afterCount} 条`)
  await sleep(500)
  await shot('03-deleted.png')

  // ---------------- W4 ----------------
  console.log('')
  console.log('=== W4. 刷新页面后仍然不出现（服务端真的删了）===')
  await page.reload({ waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.chat__sessions, .el-empty, .empty'), { timeout: 20000 })
  await sleep(1500)
  check('刷新后列表里没有该会话', !(await hasSessionItem(sessionId)))

  // ---------------- W5 ----------------
  console.log('')
  console.log('=== W5. 对方（卖家）列表里该会话仍在 ===')
  const sellerSessions = await get(`${API}/chat/sessions`, seller.token)
  check(
    '卖家侧会话未被删除',
    (sellerSessions.data ?? []).some((s) => s.id === sessionId),
    JSON.stringify((sellerSessions.data ?? []).map((s) => s.id)),
  )

  // ---------------- W6 ----------------
  console.log('')
  console.log('=== W6. 卖家再发消息 → 买家刷新后会话重新出现 ===')
  const msg = await post(
    `${API}/chat/messages`,
    { sessionId, content: '你好，这件还在的', msgType: 1, clientMsgId: `wd${Date.now()}` },
    seller.token,
  )
  check('卖家发消息成功', msg.code === 0, JSON.stringify(msg).slice(0, 120))
  await page.reload({ waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.chat__sessions'), { timeout: 20000 })
  await sleep(1500)
  check('会话重新出现（微信同款行为）', await hasSessionItem(sessionId))
  const unreadBadge = await page.$$eval('.session__unread', (els) => els.map((e) => e.textContent.trim()))
  check('并带未读角标', unreadBadge.length > 0, JSON.stringify(unreadBadge))
  await shot('04-recovered.png')

  // ---------------- W7 ----------------
  console.log('')
  console.log('=== W7. 页面运行时报错 ===')
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
  // 清理本脚本造的测试数据（商品 + 会话 + 消息）
  if (sessionId) {
    console.log('')
    console.log('=== 清理测试数据 ===')
    try {
      execFileSync(
        'docker',
        [
          'exec', 'ershou-mysql', 'mysql', '-h', '127.0.0.1', '-uroot',
          '-p' + process.env.MYSQL_ROOT_PASSWORD,
          '-e', `use ershou; delete from chat_message where session_id=${sessionId}; delete from chat_session where id=${sessionId};`,
        ],
        { stdio: 'ignore' },
      )
    } catch {
      /* 用容器清不了就用宿主机 mysql（下面那步） */
    }
    try {
      execFileSync(
        'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
        ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '--default-character-set=utf8mb4',
         '-e', `use ershou; delete from chat_message where session_id=${sessionId}; delete from chat_session where id=${sessionId}; delete from item_review where item_id=${itemId}; delete from item where id=${itemId};`],
        { stdio: 'ignore', env: { ...process.env, MYSQL_PWD: process.env.MYSQL_PASSWORD } },
      )
      console.log(`  已清理会话 #${sessionId} 与商品 #${itemId}`)
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
