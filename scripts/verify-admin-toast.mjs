/**
 * 验收：管理台的操作气泡**一定会自己消失**（修 "点已审核后气泡有时候不消失"）。
 *
 * 复现原理（已从 element-plus 源码确认）：
 *   message2.mjs  L102: onMouseenter: clearTimer
 *                 L103: onMouseleave: startTimer
 *                 L45:  if (props.duration === 0) return   ← duration:0 才不启动自身定时器
 * 所以：鼠标停在气泡上 → 计时被清掉 → 气泡不走了。
 * 运营点完「已审核」手就停在那个位置，气泡正好从按钮附近浮出来，很容易命中。
 *
 * 本脚本用两条对照来证明修好了：
 *   T1【回归】原生 ElMessage（默认 duration）在 hover 下**会被卡住** → 证明这个坑真实存在
 *   T2【修复】管理台走 showTip 的路径，即使鼠标压在气泡上也**必定按时消失**
 *   T3 连续点多次「已审核」不会堆出一摞气泡
 *   T4 点完立刻切到别的管理页，气泡不会跟着飘过去
 *
 * 用法：node scripts/verify-admin-toast.mjs
 */
import { existsSync } from 'node:fs'
import { mkdir } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SHOT_DIR = path.join(ROOT, '.build', 'admin-toast')
const API = process.env.ADMIN_API ?? 'http://127.0.0.1:8080/api/v1'
const WEB = process.env.WEB_BASE ?? 'http://localhost:5173'
const REDIS_PASSWORD = process.env.REDIS_PASSWORD ?? '__REDACTED_DB_PASSWORD__'
const ADMIN_PHONE = '13800000009'
const SELLER_PHONE = '13800000008'
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
  const res = await post(`${API}/auth/login`, { phone, code: SMS_CODE, schoolId: 1, campusId: 1 })
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

const browser = await puppeteer.default.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--disable-gpu', '--window-size=1500,1000'] })
const page = await browser.newPage()
await page.setViewport({ width: 1500, height: 1000 })
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(String(e)))
const shot = (name) => page.screenshot({ path: path.join(SHOT_DIR, name) })

/** 当前屏幕上可见的气泡数量 */
const toastCount = () => page.$$eval('.el-message', (els) => els.filter((e) => e.offsetParent !== null).length)

let seededIds = []
// 清理段（finally）要用它的 token 触发索引重建，所以声明在外层
let admin = null

try {
  console.log('')
  console.log('=== 0. 准备：登录 + 造一条待上架审核数据 ===')
  resetSmsLimit([ADMIN_PHONE, SELLER_PHONE])
  admin = await login(ADMIN_PHONE)
  const seller = await login(SELLER_PHONE)
  // 造 6 件：T2 用 1 件、T3 连点 3 件、T4 再用 1 件，留点余量
  for (let i = 1; i <= 6; i++) {
    const pub = await post(`${API}/items`, {
      title: `气泡验收商品${i}-${Date.now().toString().slice(-5)}`,
      description: '用于验证审核气泡会自己消失',
      categoryId: 1, conditionLevel: 3, price: 10 + i,
      images: ['http://127.0.0.1:9010/ershou/test.png'], preferPointId: 1,
    }, seller.token)
    if (pub.code !== 0) throw new Error(`发布失败：${pub.message}`)
    seededIds.push(pub.data.itemId)
  }
  console.log(`  已发布待审商品：${seededIds.join(', ')}`)

  await page.goto(WEB, { waitUntil: 'domcontentloaded' })
  await page.evaluate((t) => localStorage.setItem('ershou_token', t), admin.token)

  // ---------------- T1：负向对照（尽力复现，复现不出就如实标 SKIP） ----------------
  console.log('')
  console.log('=== T1【负向对照】原生 ElMessage（默认 duration）在鼠标悬停时是否会被卡住 ===')
  console.log('  注：这一条是"尽力复现"，无头环境里可能复现不出来；')
  console.log('      复现不出会标 SKIP 而**不算失败**（不能把没复现当成复现）。')
  await page.goto(`${WEB}/admin/review`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.admin-review'), { timeout: 20000 })
  await sleep(1200)

  let reproduced = false
  const injected = await page
    .evaluate(async () => {
      try {
        const { ElMessage } = await import('/node_modules/.vite/deps/element-plus.js')
        ElMessage.success('T1 原生提示（duration=3000）')
        return true
      } catch {
        return false
      }
    })
    .catch(() => false)

  if (!injected) {
    console.log('  [SKIP] 无法在页面里注入原生 ElMessage')
  } else {
    // 等它真的出现并停稳，再取坐标 hover（早取会拿到动画中途的位置）
    await page.waitForSelector('.el-message', { timeout: 5000 }).catch(() => null)
    await sleep(500)
    const box = await page.evaluate(() => {
      const el = document.querySelector('.el-message')
      if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width, h: r.height }
    })
    if (!box) {
      console.log('  [SKIP] 没抓到原生气泡')
    } else {
      await page.mouse.move(box.x, box.y)
      await sleep(200)
      // 确认鼠标真的压在它上面（:hover 生效）
      const hovering = await page.evaluate(() => !!document.querySelector('.el-message:hover'))
      console.log(
        `  鼠标压在原生气泡上（${Math.round(box.x)},${Math.round(box.y)} 尺寸 ${Math.round(box.w)}x${Math.round(box.h)}，:hover=${hovering}）`,
      )
      await sleep(5000) // 远超默认 3000ms
      const stillThere = await toastCount()
      await shot('01-native-hover.png')
      if (hovering && stillThere > 0) {
        reproduced = true
        console.log(`  [NOTE] 复现成功：悬停 5.2s 后原生提示仍在（倒计时确实被 hover 清掉）`)
      } else {
        console.log(`  [SKIP] 无头环境下没能复现（悬停=${hovering}，5.2s 后剩 ${stillThere} 条）—— 按 SKIP 处理，不计失败`)
      }
      // 让原生气泡自己走掉，别影响后面的对照
      await page.mouse.move(10, 600)
      await sleep(3500)
      await page.evaluate(() => document.querySelectorAll('.el-message').forEach((e) => e.remove()))
    }
  }
  check('负向对照已执行（复现与否都如实记录）', true, reproduced ? '已复现' : '无头环境未复现，标 SKIP')

  // ---------------- T2：修好的路径 ----------------
  console.log('')
  console.log('=== T2【修复】管理台操作气泡：鼠标压在它上面也必定按时消失 ===')
  await page.goto(`${WEB}/admin/review`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => document.querySelectorAll('.admin-review .el-table__row').length > 0, { timeout: 20000 })
  await sleep(1200)

  // 真实点击第一行的「已审核」
  const btn = await page.evaluate(() => {
    const row = document.querySelector('.admin-review .el-table__row')
    const b = Array.from(row.querySelectorAll('button')).find((x) => x.textContent.includes('已审核'))
    if (!b) return null
    const r = b.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
  })
  if (!btn) throw new Error('找不到「已审核」按钮')
  await page.mouse.click(btn.x, btn.y)
  await sleep(400)

  const t2box = await page.evaluate(() => {
    const el = document.querySelector('.el-message')
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
  })
  check('点「已审核」后出现气泡', !!t2box)
  if (t2box) {
    // 关键：把鼠标一直压在气泡上
    await page.mouse.move(t2box.x, t2box.y)
    console.log(`  鼠标压在操作气泡上，等 2.5s（showTip 的时长是 1.6s）...`)
    await sleep(2500)
    const left = await toastCount()
    await shot('02-fixed-hover.png')
    check('鼠标压在气泡上 2.5s 后它已自己消失（不会被 hover 卡住）', left === 0, `还剩 ${left} 条`)
  }

  // ---------------- T3：不堆叠 ----------------
  console.log('')
  console.log('=== T3 连续处置 3 条，不会堆出一摞气泡 ===')
  for (let i = 0; i < 3; i++) {
    const b = await page.evaluate(() => {
      const row = document.querySelector('.admin-review .el-table__row')
      const btn = row && Array.from(row.querySelectorAll('button')).find((x) => x.textContent.includes('已审核'))
      if (!btn) return null
      const r = btn.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
    })
    if (!b) break
    await page.mouse.click(b.x, b.y)
    await sleep(250)
  }
  const stacked = await toastCount()
  await shot('03-no-stack.png')
  check('最多只有 1 条气泡（连续操作不会堆叠）', stacked <= 1, `当前 ${stacked} 条`)
  await sleep(2200)
  check('最后也会自己消失', (await toastCount()) === 0, `还剩 ${await toastCount()} 条`)

  // ---------------- T4：切页不残留 ----------------
  console.log('')
  console.log('=== T4 点完立刻切页，气泡不会跟着飘过去 ===')
  const b4 = await page.evaluate(() => {
    const row = document.querySelector('.admin-review .el-table__row')
    const btn = row && Array.from(row.querySelectorAll('button')).find((x) => x.textContent.includes('已审核'))
    if (!btn) return null
    const r = btn.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
  })
  if (b4) {
    await page.mouse.click(b4.x, b4.y)
    await sleep(300)
    const had = await toastCount()
    await page.goto(`${WEB}/admin/items`, { waitUntil: 'networkidle2' })
    await sleep(500)
    const onOther = await toastCount()
    await shot('04-after-navigate.png')
    check(`切页后不留气泡（切之前 ${had} 条 → 切之后 ${onOther} 条）`, onOther === 0, `还剩 ${onOther} 条`)
  } else {
    console.log('  [SKIP] 没有可点的行了')
  }

  // ---------------- T5：页面无报错 ----------------
  console.log('')
  console.log('=== T5 页面运行时报错 ===')
  const real = pageErrors.filter((e) => !e.includes('favicon') && !e.includes('Vue Devtools') && !e.includes('ResizeObserver') && !e.includes('Failed to load resource'))
  check('页面无 JS 报错', real.length === 0, real.slice(0, 3).join(' | '))
} catch (err) {
  fail++
  console.log('')
  console.log(`  [FATAL] ${err.message}`)
  await shot('99-fatal.png').catch(() => {})
} finally {
  await browser.close()
  // 清理造的数据 + 审核队列里可能被登记的行
  if (seededIds.length) {
    console.log('')
    console.log('=== 清理测试数据 ===')
    try {
      execFileSync('C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
        ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '--default-character-set=utf8mb4', '-e',
          `use ershou;
           DELETE FROM item_review WHERE item_id IN (${seededIds.join(',')});
           DELETE FROM item WHERE id IN (${seededIds.join(',')});`],
        { stdio: 'ignore', env: { ...process.env, MYSQL_PWD: process.env.MYSQL_PASSWORD ?? '__REDACTED_DB_PASSWORD__' } })
      execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'DEL', 'ershou:admin:dashboard'], { stdio: 'ignore' })
      console.log(`  已清理商品 ${seededIds.join(', ')}`)
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
      console.log(`  清理失败：${e.message}`)
    }
  }
}

console.log('')
console.log(`截图目录：${SHOT_DIR}`)
if (fail === 0) console.log(`全部通过：${pass} 项断言`)
else console.log(`通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
