/**
 * 复现：网页端聊天「多选批量删除」在**真实鼠标点击**下到底哪里断。
 *
 * 与 verify-chat-batch-delete.mjs 的差别（这是关键）：
 *   那个脚本用 page.evaluate 直接 el.click()（DOM 层点击，绕过真实命中测试）；
 *   这里一律用 page.click(selector) —— 真实鼠标坐标点击，会走命中测试，
 *   能暴露"元素被遮挡 / 不在视口 / 事件被别的元素吃掉"这类问题。
 *
 * 用法：node scripts/repro-chat-batch-delete.mjs
 */
import { existsSync } from 'node:fs'
import { mkdir } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import { loadEnvFile } from 'node:process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

try { loadEnvFile(new URL('../.env', import.meta.url)) } catch {}

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SHOT_DIR = path.join(ROOT, '.build', 'chat-batch-repro')
const API = process.env.CHAT_API ?? 'http://127.0.0.1:8080/api/v1'
const WEB = process.env.WEB_BASE ?? 'http://localhost:5173'
const REDIS_PASSWORD = process.env.REDIS_PASSWORD
const SELLER_PHONE = '13800000008'
const BUYER_PHONE = '13800000007'

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
    return { code: -1, message: `非 JSON（HTTP ${res.status}）`, raw: text.slice(0, 200) }
  }
}
const post = (url, body, token) => req('POST', url, { token, body })

function resetSmsLimit(phones) {
  for (const phone of phones) {
    try {
      const keys = execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'KEYS', `ershou:sms:limit:${phone}:*`], { encoding: 'utf8' })
        .split('\n').map((s) => s.trim()).filter((s) => s.startsWith('ershou:'))
      if (keys.length) execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'DEL', ...keys], { stdio: 'ignore' })
    } catch {}
  }
}

const CHROME = ['C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe'].find((p) => existsSync(p))
let puppeteer = null
for (const rel of ['../ershou-web', '..', '.']) {
  try {
    puppeteer = await import(new URL(rel + '/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js', import.meta.url).href)
    break
  } catch {}
}
await mkdir(SHOT_DIR, { recursive: true })

const browser = await puppeteer.default.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--disable-gpu', '--window-size=1500,1000'] })
const page = await browser.newPage()
await page.setViewport({ width: 1500, height: 1000 })

// 把所有信息都打出来，别只捕 error
const logs = []
page.on('console', (m) => logs.push(`[console.${m.type()}] ${m.text()}`))
page.on('pageerror', (e) => logs.push(`[pageerror] ${String(e)}`))
page.on('requestfailed', (r) => logs.push(`[requestfailed] ${r.method()} ${r.url()} → ${r.failure()?.errorText}`))
// 关键：把删除相关的请求与响应都记下来
const apiCalls = []
page.on('response', async (res) => {
  const url = res.url()
  if (url.includes('/chat/sessions')) {
    let body = ''
    try {
      body = (await res.text()).slice(0, 240)
    } catch {}
    apiCalls.push(`${res.request().method()} ${url.replace(/^.*\/api\/v1/, '')} → HTTP ${res.status()}  ${body}`)
  }
})

let sids = []
let itemIds = []

try {
  console.log('')
  console.log('=== 0. 准备：造 3 个会话 ===')
  resetSmsLimit([SELLER_PHONE, BUYER_PHONE])
  await post(`${API}/auth/sms-code`, { phone: SELLER_PHONE })
  await post(`${API}/auth/sms-code`, { phone: BUYER_PHONE })
  const sl = await post(`${API}/auth/login`, { phone: SELLER_PHONE, code: '123456', schoolId: 1, campusId: 1 })
  const bl = await post(`${API}/auth/login`, { phone: BUYER_PHONE, code: '123456', schoolId: 1, campusId: 1 })
  const seller = { token: sl.data.token, id: sl.data.user.id }
  const buyer = { token: bl.data.token, id: bl.data.user.id }
  for (let i = 1; i <= 3; i++) {
    const pub = await post(`${API}/items`, { title: `复现用商品${i}`, description: 'x', categoryId: 1, conditionLevel: 3, price: 10 + i, images: ['http://127.0.0.1:9010/ershou/test.png'], preferPointId: 1 }, seller.token)
    itemIds.push(pub.data.itemId)
    const op = await post(`${API}/chat/sessions`, { itemId: pub.data.itemId }, buyer.token)
    sids.push(op.data.id)
  }
  console.log(`  会话：${sids.join(', ')}`)

  await page.goto(WEB, { waitUntil: 'domcontentloaded' })
  await page.evaluate((t) => localStorage.setItem('ershou_token', t), buyer.token)
  await page.goto(`${WEB}/chat`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.chat__sessions'), { timeout: 25000 })
  await sleep(1200)

  console.log('')
  console.log('=== 1. 点「多选」（真实鼠标）===')
  const multiBtnText = await page.$eval('.chat__list-head', (e) => e.textContent.replace(/\s+/g, ' ').trim())
  console.log(`  头部文本: ${multiBtnText}`)
  // 用真实点击：找到按钮的坐标
  const btnBox = await page.evaluate(() => {
    const btn = Array.from(document.querySelectorAll('.chat__list-head button')).find((b) => b.textContent.includes('多选'))
    if (!btn) return null
    const r = btn.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width, h: r.height }
  })
  console.log(`  「多选」按钮位置: ${JSON.stringify(btnBox)}`)
  if (!btnBox) throw new Error('找不到「多选」按钮')
  await page.mouse.click(btnBox.x, btnBox.y)
  await sleep(800)
  const afterMulti = await page.$eval('.chat__list-head', (e) => e.textContent.replace(/\s+/g, ' ').trim())
  console.log(`  点击后头部: ${afterMulti}`)
  const boxes = await page.$$eval('.chat__sessions .session .session__check', (els) => els.length)
  console.log(`  勾选框数量: ${boxes}`)
  await page.screenshot({ path: path.join(SHOT_DIR, '01-after-multi.png') })

  console.log('')
  console.log('=== 2. 真实点击勾选 2 条 ===')
  for (const id of sids.slice(0, 2)) {
    const box = await page.evaluate((sid) => {
      const row = document.querySelector(`.chat__sessions .session[data-session-id="${sid}"]`)
      if (!row) return null
      const r = row.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
    }, id)
    console.log(`  第 ${id} 行位置: ${JSON.stringify(box)}`)
    if (!box) continue
    await page.mouse.click(box.x, box.y)
    await sleep(400)
    const head = await page.$eval('.chat__list-head', (e) => e.textContent.replace(/\s+/g, ' ').trim())
    console.log(`    点击后头部: ${head}`)
  }
  const checkedCount = await page.$$eval('.chat__sessions .session.is-checked', (els) => els.length)
  console.log(`  已勾选行数（.is-checked）: ${checkedCount}`)
  await page.screenshot({ path: path.join(SHOT_DIR, '02-after-select.png') })

  console.log('')
  console.log('=== 3. 真实点击「删除」===')
  const delBox = await page.evaluate(() => {
    const btn = Array.from(document.querySelectorAll('.chat__list-head button')).find((b) => b.textContent.includes('删除'))
    if (!btn) return null
    const r = btn.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, disabled: btn.disabled, text: btn.textContent.trim() }
  })
  console.log(`  「删除」按钮: ${JSON.stringify(delBox)}`)
  if (delBox) {
    await page.mouse.click(delBox.x, delBox.y)
    await sleep(800)
  }
  const dlg = await page.evaluate(() => {
    const box = document.querySelector('.el-message-box')
    if (!box) return null
    return {
      title: box.querySelector('.el-message-box__title')?.textContent.trim(),
      content: box.querySelector('.el-message-box__message')?.textContent.replace(/\s+/g, ' ').trim(),
      buttons: Array.from(box.querySelectorAll('.el-message-box__btns .el-button')).map((b) => b.textContent.trim()),
    }
  })
  console.log(`  确认框: ${JSON.stringify(dlg)}`)
  await page.screenshot({ path: path.join(SHOT_DIR, '03-confirm.png') })

  if (dlg) {
    console.log('')
    console.log('=== 4. 真实点击确认框的「删除」===')
    const okBox = await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('.el-message-box__btns .el-button'))
      const btn = btns.find((b) => b.textContent.trim() === '删除') ?? btns[btns.length - 1]
      if (!btn) return null
      const r = btn.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2, text: btn.textContent.trim() }
    })
    console.log(`  确认按钮: ${JSON.stringify(okBox)}`)
    if (okBox) {
      await page.mouse.click(okBox.x, okBox.y)
      await sleep(2500)
    }
  }

  console.log('')
  console.log('=== 5. 结果 ===')
  const left = await page.$$eval('.chat__sessions .session', (els) => els.map((e) => Number(e.getAttribute('data-session-id'))))
  console.log(`  列表剩余会话: ${JSON.stringify(left)}`)
  console.log(`  被删的 ${JSON.stringify(sids.slice(0, 2))} 是否还在列表: ${sids.slice(0, 2).map((id) => `${id}=${left.includes(id)}`).join(' ')}`)
  await page.screenshot({ path: path.join(SHOT_DIR, '04-after-delete.png') })

  console.log('')
  console.log('=== 6. 期间所有 /chat/sessions 请求 ===')
  if (!apiCalls.length) console.log('  （一个都没有！说明请求根本没发出去）')
  apiCalls.forEach((c) => console.log('  ' + c))

  console.log('')
  console.log('=== 7. 控制台/网络错误 ===')
  const interesting = logs.filter((l) => !l.includes('favicon') && !l.includes('Vue Devtools') && !l.includes('ResizeObserver'))
  if (!interesting.length) console.log('  （无）')
  interesting.slice(0, 25).forEach((l) => console.log('  ' + l))
} catch (e) {
  console.log('')
  console.log(`  [FATAL] ${e.message}`)
  await page.screenshot({ path: path.join(SHOT_DIR, '99-fatal.png') }).catch(() => {})
} finally {
  await browser.close()
  // 清理
  execFileSync('C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe', ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '--default-character-set=utf8mb4', '-e',
    `use ershou; delete from chat_message where session_id in (${sids.join(',') || 0}); delete from chat_session where id in (${sids.join(',') || 0}); delete from item_review where item_id in (${itemIds.join(',') || 0}); delete from item where id in (${itemIds.join(',') || 0});`],
    { stdio: 'ignore', env: { ...process.env, MYSQL_PWD: process.env.MYSQL_PASSWORD } })
  console.log('')
  console.log(`截图：${SHOT_DIR}`)
  console.log('测试数据已清理')
}
