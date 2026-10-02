// 上架审核页面的真实浏览器验收（headless Chrome + puppeteer-core）
//
// 覆盖"实时"这个核心卖点 —— 接口层已经由 scripts/review-smoke-test.ps1 验过，
// 这里验的是"页面到底有没有反应"：
//   B1. 侧栏有「上架审核」入口，待审角标显示真实数字
//   B2. 页面显示"实时接收中"（WS 真连上了，不是只画了个绿条）
//   B3. 卖家发新商品 → 不刷新页面，列表里自动出现该行（核心：实时）
//   B4. 点「已审核」→ 该行消失 + 待审数字减一 + 出现在「已处置」里
//   B5. 卖家改名 → 该行重新回到列表并带「已改名」标记
//   B6. 「下架」弹窗填写原因 → 商品状态变已下架、行消失
//   B7. 详情抽屉能打开并渲染描述
//   B8. 页面无 JS 报错（渲染期抛错会让整棵子树停止更新，是聊天那边踩过的坑）
//
// 用法：node scripts/verify-admin-review.mjs
//   可选环境变量：ADMIN_API（默认 http://127.0.0.1:8080/api/v1）
//                WEB_BASE（默认 http://localhost:5173）
// 前置：后端已启动、前端 dev server 已启动、MySQL 可访问、.build/test-upload.jpg 存在
import { existsSync } from 'node:fs'
import { mkdir, readFile } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SHOT_DIR = path.join(ROOT, '.build', 'admin-review')
const API = process.env.ADMIN_API ?? 'http://127.0.0.1:8080/api/v1'
const WEB = process.env.WEB_BASE ?? 'http://localhost:5173'
const UPLOAD_IMG = path.join(ROOT, '.build', 'test-upload.jpg')

const ADMIN_PHONE = '13800000009'
const SELLER_PHONE = '13800000008'
const SMS_CODE = '123456'
/** 与 .env 的 REDIS_PASSWORD 一致；仅用于清测试号的验证码次数计数 */
const REDIS_PASSWORD = process.env.REDIS_PASSWORD ?? '__REDACTED_DB_PASSWORD__'

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

// ---------------- HTTP ----------------
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
    return { code: -1, message: `非 JSON（HTTP ${res.status}）`, raw: text.slice(0, 200) }
  }
}
const get = (url, token) => req('GET', url, { token })
const post = (url, body, token) => req('POST', url, { token, body })
const put = (url, body, token) => req('PUT', url, { token, body })

/**
 * 登录。注意<b>不要</b>在这里面调 /auth/sms-code：
 * 同一个手机号再要一次验证码会让上一个 token 失效，
 * 先登录运营再登录卖家时，运营的 token 就废了（报 10002，排查起来很绕）。
 * 所以由调用方先把两个号的验证码都取好，再依次登录。
 */
async function login(phone) {
  const res = await post(`${API}/auth/login`, { phone, code: SMS_CODE, schoolId: 1, campusId: 1 })
  if (res.code !== 0) throw new Error(`登录失败 ${phone}：${res.code} ${res.message}`)
  return { token: res.data.token, id: res.data.user.id }
}

async function uploadImage(token) {
  const buf = await readFile(UPLOAD_IMG)
  const form = new FormData()
  form.append('file', new Blob([buf], { type: 'image/jpeg' }), 'test-upload.jpg')
  const up = await req('POST', `${API}/files/upload?biz=item`, { token, form })
  if (up.code !== 0) throw new Error(`图片上传失败：${up.code} ${up.message}`)
  return up.data.url
}

/** 发一件"正常"商品：不含可疑词 → 机审通过 → 直接在售（这正是要验的"上架不拦截"） */
async function publishNormal(token, title, img) {
  const res = await post(
    `${API}/items`,
    {
      title,
      description: '九成新，校内面交',
      categoryId: 1,
      conditionLevel: 2,
      price: 66,
      images: [img],
      preferPointId: 1,
    },
    token,
  )
  if (res.code !== 0) throw new Error(`发布失败：${res.code} ${res.message}`)
  return { id: res.data.itemId, status: res.data.status }
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
  args: ['--no-sandbox', '--disable-gpu', '--window-size=1600,1100'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1600, height: 1100 })

const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(String(e)))
page.on('console', (m) => {
  if (m.type() === 'error') pageErrors.push('console: ' + m.text())
})

const shot = (name) => page.screenshot({ path: path.join(SHOT_DIR, name) })

/** 列表里某商品是否出现（按行里的 #id 文本判断，DOM 结构变了也不会失效） */
function rowTitles() {
  return page.$$eval('.admin-review .el-table__row', (rows) =>
    rows.map((r) => r.querySelector('.admin-review__title')?.textContent.replace(/\s+/g, ' ').trim() ?? ''),
  )
}
function pendingNumber() {
  return page.$eval('.admin-stat.is-primary .admin-stat__num', (e) => Number(e.textContent.trim()))
}

/** 点某一行里的按钮（按标题定位行，再按文案找按钮） */
function clickRowButton(marker, label) {
  return page.evaluate(
    (marker, label) => {
      const rows = Array.from(document.querySelectorAll('.admin-review .el-table__row'))
      const row = rows.find((r) => r.textContent.includes(marker))
      if (!row) return 'no-row'
      const btn = Array.from(row.querySelectorAll('.el-button')).find((b) => b.textContent.trim() === label)
      if (!btn) return 'no-btn'
      btn.click()
      return 'ok'
    },
    marker,
    label,
  )
}

/**
 * 重置测试号的"每日验证码次数"计数器。
 *
 * 这个脚本一天会被跑很多次，而后端限的是<b>每个手机号每天 10 次</b>
 * （UserService.sendSmsCode），跑几轮就撞上 10004，表现是"登录失败：验证码错误"，
 * 排查起来会误以为是验证码逻辑坏了。测试脚本必须自己把这条计数清掉。
 *
 * ⚠️ 计数器 key 带日期后缀（{@code ershou:sms:limit:<phone>:yyyyMMdd}），
 * 直接 DEL {@code ershou:sms:limit:<phone>} 删的是个不存在的 key、返回 0，
 * 看上去"清理成功"其实计数器原封不动 —— 所以这里按 pattern 删。
 * 清不掉（没有 docker/redis-cli）不算错误：那时只要次数还没用完照样能跑。
 */
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

let seller = null
let admin = null
// 这两件是脚本造出来的商品，声明在外层是为了在 finally 里能清理掉它们
let published = null
let publishedB = null

try {
  console.log('')
  console.log(`=== 0. 准备（API=${API}  WEB=${WEB}）===`)
  // 先把两个号的验证码都取好再登录：同一号码重复取码会让前一个 token 失效（见 login 注释）
  resetSmsLimit([ADMIN_PHONE, SELLER_PHONE])
  await post(`${API}/auth/sms-code`, { phone: ADMIN_PHONE })
  await post(`${API}/auth/sms-code`, { phone: SELLER_PHONE })
  admin = await login(ADMIN_PHONE)
  seller = await login(SELLER_PHONE)
  const me = await get(`${API}/users/me`, admin.token)
  if (!me.data?.isAdmin) throw new Error(`运营账号 ${ADMIN_PHONE} 没有运营位`)
  const img = await uploadImage(seller.token)
  console.log(`  运营 id=${admin.id}  卖家 id=${seller.id}`)

  await page.goto(WEB, { waitUntil: 'domcontentloaded' })
  await page.evaluate((t) => localStorage.setItem('ershou_token', t), admin.token)

  // ---------------- B1/B2 ----------------
  console.log('')
  console.log('=== B1. 侧栏入口与初始渲染 ===')
  await page.goto(`${WEB}/admin/review`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.admin-review'), { timeout: 25000 })
  await sleep(1500)

  const navLabels = await page.$$eval('.admin__menu .admin__nav-label', (els) => els.map((e) => e.textContent.trim()))
  check('侧栏有「上架审核」入口', navLabels.includes('上架审核'), JSON.stringify(navLabels))
  check('导航顺序：概览 → 上架审核 → 审核工作台', navLabels[1] === '上架审核' && navLabels[2] === '审核工作台', JSON.stringify(navLabels))

  const activeLabel = await page.$eval('.el-menu-item.is-active .admin__nav-label', (e) => e.textContent.trim()).catch(() => '')
  check('当前高亮在「上架审核」', activeLabel === '上架审核', activeLabel)

  const preCount = await pendingNumber()
  check('待审数字是真实数字（不是 undefined/NaN）', Number.isFinite(preCount), String(preCount))

  const badge = await page
    .$$eval('.admin__badge', (els) => els.map((e) => e.textContent.trim()))
    .catch(() => [])
  // 角标只在 > 0 时才渲染（0 时不显示是正确行为），所以待审为 0 时不能断言"必须有角标"
  if (preCount > 0) {
    check('上架审核菜单显示待审角标', badge.length > 0 && Number(badge[0]) === preCount, JSON.stringify(badge))
  } else {
    check('待审为 0 时不渲染角标（不显示无意义的 0）', badge.length === 0, JSON.stringify(badge))
  }

  // ---------------- B2 ----------------
  console.log('')
  console.log('=== B2. 实时连接状态 ===')
  // 重构后实时状态是「小圆点 + 文案」结构，不再有 __live-text 这个类
  const liveText = await page.$eval('.admin-review__live', (e) => e.textContent.replace(/\s+/g, ' ').trim())
  check('页面显示「实时接收中」（WS 已连上）', liveText.includes('实时接收中'), liveText)
  const offClass = await page.$eval('.admin-review__live', (e) => e.className.includes('is-off'))
  check('未误显示成断线样式', offClass === false)
  await shot('01-review-page.png')

  // ---------------- B3. 实时：新上架自动出现 ----------------
  console.log('')
  console.log('=== B3. 卖家发新商品 → 页面不刷新自动出现 ===')
  const stamp = Date.now().toString().slice(-6)
  const titleA = `验收实时商品A-${stamp}`
  published = await publishNormal(seller.token, titleA, img)
  check('商品发布即上架（未被拦截，status=2）', published.status === 2, String(published.status))

  let appeared = false
  const waitStart = Date.now()
  for (let i = 0; i < 40; i++) {
    const titles = await rowTitles()
    if (titles.some((t) => t.includes(`#${published.id}`))) {
      appeared = true
      break
    }
    await sleep(250)
  }
  const realtimeMs = Date.now() - waitStart
  check(`新商品在 ${realtimeMs}ms 内自动出现在列表`, appeared && realtimeMs < 8000, `appeared=${appeared} ms=${realtimeMs}`)
  await sleep(600)
  const postCount = await pendingNumber()
  check(`顶部待审数字同步 +1（${preCount} → ${postCount}）`, postCount === preCount + 1, `${preCount} -> ${postCount}`)

  const titlesNow = await rowTitles()
  check('列表里显示的是当前标题', titlesNow.some((t) => t.includes(titleA)), JSON.stringify(titlesNow.slice(0, 3)))
  await shot('02-realtime-arrived.png')

  // ---------------- B4. 已审核 → 消失 ----------------
  console.log('')
  console.log('=== B4. 点「已审核」→ 从待审列表消失 ===')
  const clickResult = await clickRowButton(`#${published.id}`, '已审核')
  check('找到该行的「已审核」按钮', clickResult === 'ok', clickResult)

  let gone = false
  for (let i = 0; i < 32; i++) {
    const titles = await rowTitles()
    if (!titles.some((t) => t.includes(`#${published.id}`))) {
      gone = true
      break
    }
    await sleep(250)
  }
  check('点「已审核」后该行从待审列表消失', gone)
  await sleep(800)
  const afterReview = await pendingNumber()
  check(`待审数字回到原值（${postCount} → ${afterReview}）`, afterReview === postCount - 1, `${postCount} -> ${afterReview}`)

  const successMsg = await page.$eval('.el-message--success', (e) => e.textContent.trim()).catch(() => '')
  check('出现「已审核」成功提示', successMsg.includes('已审核'), successMsg)

  // 切到「已处置」应该能看到它
  await page.evaluate(() => {
    const label = Array.from(document.querySelectorAll('.el-radio-button')).find((b) =>
      b.textContent.includes('已处置'),
    )
    label?.querySelector('input')?.click()
  })
  await sleep(1800)
  const doneTitles = await rowTitles()
  check('「已处置」视图里能看到刚审核的商品', doneTitles.some((t) => t.includes(`#${published.id}`)), JSON.stringify(doneTitles.slice(0, 3)))
  await shot('03-done-tab.png')

  // 切回待审核
  await page.evaluate(() => {
    const label = Array.from(document.querySelectorAll('.el-radio-button')).find((b) =>
      b.textContent.includes('待审核'),
    )
    label?.querySelector('input')?.click()
  })
  await sleep(1500)

  // ---------------- B5. 改名 → 重新入队 + 标记 ----------------
  console.log('')
  console.log('=== B5. 卖家改名 → 重新回到列表并标「已改名」 ===')
  const renamedTitle = `${titleA}（改名版）`
  const upd = await put(`${API}/items/${published.id}`, { title: renamedTitle }, seller.token)
  check('改名接口成功', upd.code === 0, JSON.stringify(upd).slice(0, 120))

  let renamedAppeared = false
  for (let i = 0; i < 32; i++) {
    const titles = await rowTitles()
    if (titles.some((t) => t.includes(`#${published.id}`))) {
      renamedAppeared = true
      break
    }
    await sleep(250)
  }
  check('改名后自动重新出现在待审列表', renamedAppeared)
  await sleep(600)
  const renamedTag = await page.evaluate((id) => {
    const rows = Array.from(document.querySelectorAll('.admin-review .el-table__row'))
    const row = rows.find((r) => r.textContent.includes(`#${id}`))
    // 视觉整改后行内标注是 span.admin-review__label（原来是彩色 el-tag，一屏太多颜色太花）；
    // 断言只看"文字有没有标出来"，不该绑死标签的 DOM 实现
    if (!row) return []
    const labels = Array.from(row.querySelectorAll('.admin-review__label, .el-tag')).map((t) => t.textContent.trim())
    return labels
  }, published.id)
  check('该行带「已改名」标记', renamedTag.includes('已改名'), JSON.stringify(renamedTag))
  check('该行标出进入原因「改名/改描述」', renamedTag.includes('改名/改描述'), JSON.stringify(renamedTag))
  const oldNameShown = await page.evaluate((id) => {
    const rows = Array.from(document.querySelectorAll('.admin-review .el-table__row'))
    const row = rows.find((r) => r.textContent.includes(`#${id}`))
    return row?.querySelector('.admin-review__renamed')?.textContent.trim() ?? ''
  }, published.id)
  check('显示「原名称：…」便于对比', oldNameShown.includes('原名称') && oldNameShown.includes(titleA), oldNameShown)
  await shot('04-renamed.png')

  // ---------------- B7. 详情抽屉 ----------------
  console.log('')
  console.log('=== B7. 详情抽屉 ===')
  const detailClick = await clickRowButton(`#${published.id}`, '详情')
  check('找到「详情」按钮', detailClick === 'ok', detailClick)
  await page.waitForFunction(() => !!document.querySelector('.el-drawer__body .admin-review__detail-body'), { timeout: 12000 })
  await sleep(600)
  const drawer = await page.evaluate(() => {
    const body = document.querySelector('.el-drawer__body')
    return {
      title: body?.querySelector('.admin-review__detail-title')?.textContent.trim() ?? '',
      desc: body?.querySelector('.admin-review__detail-body')?.textContent.trim() ?? '',
      renamed: body?.querySelector('.admin-review__renamed')?.textContent.trim() ?? '',
    }
  })
  check('抽屉标题是改名后的当前标题', drawer.title === renamedTitle, drawer.title)
  check('抽屉渲染了商品描述', drawer.desc.length > 0, drawer.desc.slice(0, 40))
  check('抽屉里也有改名对比', drawer.renamed.includes(titleA), drawer.renamed)
  await shot('05-detail-drawer.png')
  await page.keyboard.press('Escape')
  await sleep(600)

  // ---------------- B6. 下架 ----------------
  console.log('')
  console.log('=== B6. 下架（填原因）→ 商品变为已下架且行消失 ===')
  const titleB = `验收下架商品B-${stamp}`
  publishedB = await publishNormal(seller.token, titleB, img)
  let bAppeared = false
  for (let i = 0; i < 32; i++) {
    const titles = await rowTitles()
    if (titles.some((t) => t.includes(`#${publishedB.id}`))) {
      bAppeared = true
      break
    }
    await sleep(250)
  }
  check('商品B 自动出现在列表', bAppeared)

  const offClick = await clickRowButton(`#${publishedB.id}`, '下架')
  check('找到该行的「下架」按钮', offClick === 'ok', offClick)
  await page.waitForFunction(
    () => {
      const t = document.querySelector('.el-dialog__title')
      return t && t.textContent.includes('下架商品')
    },
    { timeout: 10000 },
  )
  await sleep(400)
  await page.evaluate(() => {
    const ta = document.querySelector('.el-dialog textarea')
    if (ta) {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set
      setter.call(ta, '验收脚本：标题与实际内容不符')
      ta.dispatchEvent(new Event('input', { bubbles: true }))
    }
  })
  await shot('06-offline-dialog.png')
  await page.evaluate(() => {
    const dlg = Array.from(document.querySelectorAll('.el-dialog')).find((d) =>
      d.querySelector('.el-dialog__title')?.textContent.includes('下架商品'),
    )
    const btn = Array.from(dlg.querySelectorAll('.el-dialog__footer .el-button')).find((b) =>
      b.textContent.includes('确认下架'),
    )
    btn?.click()
  })
  let goneB = false
  for (let i = 0; i < 32; i++) {
    const titles = await rowTitles()
    if (!titles.some((t) => t.includes(`#${publishedB.id}`))) {
      goneB = true
      break
    }
    await sleep(250)
  }
  check('下架后该行从待审列表消失', goneB)
  const statusB = await get(`${API}/items/${publishedB.id}`, seller.token)
  check('商品状态变为已下架(5)', statusB.data?.status === 5, String(statusB.data?.status))
  await shot('07-offline-done.png')

  // ---------------- B8. 无 JS 报错 ----------------
  console.log('')
  console.log('=== B8. 页面运行时报错 ===')
  const realErrors = pageErrors.filter(
    (e) =>
      !e.includes('favicon') &&
      !e.includes('Download the Vue Devtools') &&
      !e.includes('ResizeObserver') &&
      !e.includes('Failed to load resource'),
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

  // 本脚本会造 2 件商品（一件改名、一件下架）。不清理的话它们会永久留在库里：
  // 实测残留了「验收实时商品A-*（改名版）」「验收下架商品B-*」两件 ——
  // 既占着首页，也把后台的"在售 / 商品总数"顶高。
  const seeded = [published?.id, publishedB?.id].filter(Boolean)
  if (seeded.length) {
    console.log('')
    console.log('=== 清理本次造的商品 ===')
    try {
      execFileSync(
        'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
        ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '--default-character-set=utf8mb4', '-e',
          `use ershou;
           DELETE FROM chat_message WHERE session_id IN (SELECT id FROM chat_session WHERE item_id IN (${seeded.join(',')}));
           DELETE FROM chat_session WHERE item_id IN (${seeded.join(',')});
           DELETE FROM meetup_order WHERE item_id IN (${seeded.join(',')});
           DELETE FROM cart_item    WHERE item_id IN (${seeded.join(',')});
           DELETE FROM favorite     WHERE item_id IN (${seeded.join(',')});
           DELETE FROM item_review  WHERE item_id IN (${seeded.join(',')});
           DELETE FROM item         WHERE id IN (${seeded.join(',')});`],
        { stdio: 'ignore', env: { ...process.env, MYSQL_PWD: process.env.MYSQL_PASSWORD ?? '__REDACTED_DB_PASSWORD__' } },
      )
      execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'DEL', 'ershou:admin:dashboard'], { stdio: 'ignore' })
      console.log(`  已清理商品 ${seeded.join(', ')}`)
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
