/**
 * 网页端「我的黑名单」(/blocked) 验收。
 *
 * 这个页面补的是一个**功能缺口**：拉黑/解除的动作入口一直有（聊天窗、用户主页），
 * 但没有任何地方能查看自己拉黑过谁 —— `fetchBlockedUsers()` 早就写好了却从没被调用。
 *
 * 覆盖：
 *   A. 接口层：GET /users/blocks 必须带昵称/头像（原来只有 blockedUserId，列表没法渲染）
 *   B. 空状态：没拉黑过人时要有引导文案，不能是空白
 *   C. 有数据：头像/昵称/信用/拉黑时间/双向标记都要显示出来
 *   D. 解除拉黑：真实点「解除拉黑」→ 二次确认 → 该行消失 → 再刷新接口确认真的删了
 *   E. 入口：个人中心与聊天页都能进这个页面
 *
 * 用法：node scripts/verify-blocklist.mjs
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
const SHOT_DIR = path.join(ROOT, '.build', 'blocked-verify')
const API = process.env.PROFILE_API ?? 'http://127.0.0.1:8080/api/v1'
const WEB = process.env.WEB_BASE ?? 'http://localhost:5173'
const REDIS_PASSWORD = process.env.REDIS_PASSWORD
const MYSQL_PWD = process.env.MYSQL_PASSWORD
const PHONE_A = '13800000007' // 主体：会被拉黑 / 拉黑别人
const PHONE_B = '13800000006' // 用来造"双向拉黑"

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
const del = (url, token) => req('DELETE', url, { token })

function resetSmsLimit(phones) {
  for (const phone of phones) {
    try {
      const keys = execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'KEYS', `ershou:sms:limit:${phone}:*`], { encoding: 'utf8' })
        .split('\n').map((s) => s.trim()).filter((s) => s.startsWith('ershou:'))
      if (keys.length) execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'DEL', ...keys], { stdio: 'ignore' })
    } catch {
      /* 清不掉就按原样跑 */
    }
  }
}

async function login(phone) {
  await post(`${API}/auth/sms-code`, { phone })
  const res = await post(`${API}/auth/login`, { phone, code: '123456', schoolId: 1, campusId: 1 })
  if (res.code !== 0) throw new Error(`登录失败 ${phone}：${res.code} ${res.message}`)
  return { token: res.data.token, id: res.data.user.id }
}

const CHROME = ['C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe'].find((p) => existsSync(p))
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

const browser = await puppeteer.default.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--disable-gpu', '--window-size=1400,950'] })
const page = await browser.newPage()
await page.setViewport({ width: 1400, height: 950 })
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(String(e)))
page.on('console', (m) => {
  if (m.type() === 'error') pageErrors.push('console: ' + m.text())
})
const shot = (name) => page.screenshot({ path: path.join(SHOT_DIR, name), fullPage: true })

async function openPage(token, urlPath) {
  await page.goto(WEB, { waitUntil: 'domcontentloaded' })
  await page.evaluate((t) => localStorage.setItem('ershou_token', t), token)
  await page.goto(`${WEB}${urlPath}`, { waitUntil: 'networkidle2' })
  await sleep(1600)
}

const readList = () =>
  page.evaluate(() => ({
    rows: Array.from(document.querySelectorAll('[data-blocked-id]')).map((el) => ({
      id: Number(el.getAttribute('data-blocked-id')),
      name: el.querySelector('.profile-block__name')?.textContent?.replace(/\s+/g, ' ').trim(),
      meta: el.querySelector('.profile-block__meta')?.textContent?.replace(/\s+/g, ' ').trim(),
      time: el.querySelector('.profile-block__time')?.textContent?.replace(/\s+/g, ' ').trim(),
      hasAvatar: !!el.querySelector('.el-avatar'),
    })),
    mutualBadges: Array.from(document.querySelectorAll('.profile-block__badge.is-warn')).map((e) => e.textContent.trim()),
    isEmpty: !!document.querySelector('.profile-block__empty'),
    emptyText: document.querySelector('.profile-block__empty')?.textContent?.replace(/\s+/g, ' ').trim(),
  }))

/** 把 A 的黑名单清空（用真实接口，保证后端状态和页面一致） */
async function clearBlocks(user, list) {
  for (const row of list) {
    await del(`${API}/users/${row.blockedUserId}/block`, user.token)
  }
}

let created = []

try {
  console.log('')
  console.log('=== 准备：两个账号 ===')
  resetSmsLimit([PHONE_A, PHONE_B])
  const a = await login(PHONE_A)
  const b = await login(PHONE_B)
  console.log(`  A id=${a.id}（${PHONE_A}）  B id=${b.id}（${PHONE_B}）`)

  // 先清空 A 的既有黑名单，保证从干净状态开始
  const initial = await get(`${API}/users/blocks`, a.token)
  await clearBlocks(a, initial.data ?? [])
  await del(`${API}/users/${a.id}/block`, b.token)
  await sleep(300)

  // ---------------- B. 空状态 ----------------
  console.log('')
  console.log('=== B. 空状态（没有拉黑任何人）===')
  const emptyApi = await get(`${API}/users/blocks`, a.token)
  check('接口：未拉黑时返回空数组', Array.isArray(emptyApi.data) && emptyApi.data.length === 0, JSON.stringify(emptyApi.data))
  await openPage(a.token, '/blocked')
  const e = await readList()
  check('页面显示空状态而不是空白', e.isEmpty, JSON.stringify(e))
  check('空状态有引导文案', (e.emptyText ?? '').includes('拉黑'), String(e.emptyText))
  await shot('01-empty.png')

  // ---------------- A/C. 拉黑两人（其中一个双向） ----------------
  console.log('')
  console.log('=== C. 有数据：拉黑 2 人，其中 1 人反向拉黑（双向）===')
  // 拉黑两个真实存在的用户（用演示卖家 5/6）
  const targets = [5, 6]
  for (const id of targets) {
    const r = await post(`${API}/users/${id}/block`, undefined, a.token)
    if (r.code !== 0) throw new Error(`拉黑 ${id} 失败：${r.message}`)
    created.push(id)
  }
  // 造双向：让 B 拉黑 A，A 也拉黑 B
  await post(`${API}/users/${a.id}/block`, undefined, b.token)
  const rAB = await post(`${API}/users/${b.id}/block`, undefined, a.token)
  if (rAB.code !== 0) throw new Error(`拉黑 B 失败：${rAB.message}`)
  created.push(b.id)

  const apiList = await get(`${API}/users/blocks`, a.token)
  const rows = apiList.data ?? []
  check('接口：返回 3 条', rows.length === 3, `rows=${rows.length}`)
  check('接口：带上了昵称（不再是裸 id）', rows.every((r) => !!r.nickname), JSON.stringify(rows.map((r) => r.nickname)))
  check('接口：带上了 creditscore 等展示字段', rows.every((r) => r.creditScore != null), JSON.stringify(rows[0]))
  check('接口：不含手机号（黑名单页不该比公开主页看到更多）', !('phone' in (rows[0] ?? {})), JSON.stringify(Object.keys(rows[0] ?? {})))
  check(
    '接口：双向拉黑的那条 blockedByPeer=true',
    rows.find((r) => r.blockedUserId === b.id)?.blockedByPeer === true,
    JSON.stringify(rows.map((r) => [r.blockedUserId, r.blockedByPeer])),
  )

  await openPage(a.token, '/blocked')
  const list = await readList()
  check('页面：渲染出 3 行', list.rows.length === 3, `rows=${list.rows.length}`)
  check('页面：每行都有头像', list.rows.every((r) => r.hasAvatar), JSON.stringify(list.rows))
  check('页面：每行都有昵称（不是"用户 #xx"）', list.rows.every((r) => r.name && !r.name.includes('用户 #')), JSON.stringify(list.rows.map((r) => r.name)))
  check('页面：每行都有拉黑时间', list.rows.every((r) => (r.time ?? '').includes('拉黑于')), JSON.stringify(list.rows.map((r) => r.time)))
  check('页面：标出了"对方也在拉黑你"', list.mutualBadges.length === 1, JSON.stringify(list.mutualBadges))
  check('页面：不再显示空状态', !list.isEmpty)
  await shot('02-list.png')

  // ---------------- D. 解除拉黑 ----------------
  console.log('')
  console.log('=== D. 真实点「解除拉黑」 ===')
  const victim = targets[0]
  const btnBox = await page.evaluate((id) => {
    const row = document.querySelector(`[data-blocked-id="${id}"]`)
    const btn = row && Array.from(row.querySelectorAll('button')).find((b) => b.textContent.includes('解除拉黑'))
    if (!btn) return null
    const r = btn.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
  }, victim)
  check('找到该行的「解除拉黑」按钮', !!btnBox, String(victim))
  if (btnBox) {
    await page.mouse.click(btnBox.x, btnBox.y)
    await sleep(700)
    const dlg = await page.evaluate(() => {
      const box = document.querySelector('.el-message-box')
      return box
        ? {
            title: box.querySelector('.el-message-box__title')?.textContent?.trim(),
            body: box.querySelector('.el-message-box__message')?.textContent?.replace(/\s+/g, ' ').trim(),
            buttons: Array.from(box.querySelectorAll('.el-message-box__btns .el-button')).map((b) => b.textContent.trim()),
          }
        : null
    })
    check('弹出二次确认', !!dlg && dlg.title === '解除拉黑', JSON.stringify(dlg))
    // 确认按钮
    const okBox = await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('.el-message-box__btns .el-button'))
      const btn = btns.find((b) => b.textContent.trim() === '解除') ?? btns[btns.length - 1]
      if (!btn) return null
      const r = btn.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
    })
    if (okBox) await page.mouse.click(okBox.x, okBox.y)
    await sleep(1500)
    const after = await readList()
    check('确认后该行从页面消失', !after.rows.some((r) => r.id === victim), JSON.stringify(after.rows.map((r) => r.id)))
    // 再查接口确认真删了（不是只改了前端）
    const apiAfter = await get(`${API}/users/blocks`, a.token)
    check(
      '接口确认真的解除了（不只是前端移除）',
      !(apiAfter.data ?? []).some((r) => r.blockedUserId === victim),
      JSON.stringify((apiAfter.data ?? []).map((r) => r.blockedUserId)),
    )
    created = created.filter((id) => id !== victim)
    await shot('03-after-unblock.png')
  }

  // ---------------- E. 入口 ----------------
  console.log('')
  console.log('=== E. 两个入口都能进黑名单 ===')
  await openPage(a.token, '/profile')
  const profileEntry = await page.evaluate(() => {
    const el = Array.from(document.querySelectorAll('.profile__entry')).find((e) => e.textContent.includes('黑名单'))
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, text: el.textContent.replace(/\s+/g, ' ').trim() }
  })
  check('个人中心有「我的黑名单」入口', !!profileEntry, JSON.stringify(profileEntry))
  if (profileEntry) {
    await page.mouse.click(profileEntry.x, profileEntry.y)
    await sleep(1500)
    check('从个人中心能进入黑名单页', page.url().includes('/blocked'), page.url())
  }

  await openPage(a.token, '/chat')
  const chatEntry = await page.evaluate(() => {
    const el = Array.from(document.querySelectorAll('.chat__list-head button')).find((b) => b.textContent.includes('黑名单'))
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
  })
  check('聊天页头部有「黑名单」入口', !!chatEntry, JSON.stringify(chatEntry))
  if (chatEntry) {
    await page.mouse.click(chatEntry.x, chatEntry.y)
    await sleep(1500)
    check('从聊天页能进入黑名单页', page.url().includes('/blocked'), page.url())
  }

  console.log('')
  console.log('=== F. 页面运行时报错 ===')
  const real = pageErrors.filter((e) => !e.includes('favicon') && !e.includes('Vue Devtools') && !e.includes('ResizeObserver') && !e.includes('Failed to load resource'))
  check('页面无 JS 报错', real.length === 0, real.slice(0, 3).join(' | '))
} catch (err) {
  fail++
  console.log('')
  console.log(`  [FATAL] ${err.message}`)
  await shot('99-fatal.png').catch(() => {})
} finally {
  await browser.close()
  // 清掉本次造的拉黑关系（避免污染演示账号）
  if (created.length) {
    console.log('')
    console.log('=== 清理本次造的拉黑关系 ===')
    try {
      execFileSync('C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
        ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '--default-character-set=utf8mb4', '-e',
          `use ershou; DELETE FROM user_block WHERE blocked_user_id IN (${created.join(',')}) OR user_id IN (${created.join(',')});`],
        { stdio: 'ignore', env: { ...process.env, MYSQL_PWD } })
      console.log(`  已清理与 ${created.join(', ')} 相关的拉黑关系`)
    } catch (e) {
      console.log(`  清理失败：${e.message}`)
    }
  }
}

console.log('')
console.log(`截图目录：${SHOT_DIR}`)
if (fail === 0) console.log(`全部通过：${pass} 项断言`)
else console.log(`通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
