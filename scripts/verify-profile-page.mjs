/**
 * 网页端「个人中心」(/profile) 验收。
 *
 * 这个页面刚重做过（原来是纯静态入口页，现在要有真实数据），所以两件事都要验：
 *   A. **空数据状态**：新用户没有任何发布/收藏/待办时，不能出现空白区块或 0 的噪音
 *   B. **有数据状态**：有发布/收藏/购物车时，数字与列表要真的显示出来
 *
 * 用法：node scripts/verify-profile-page.mjs
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
const SHOT_DIR = path.join(ROOT, '.build', 'profile-verify')
const API = process.env.PROFILE_API ?? 'http://127.0.0.1:8080/api/v1'
const WEB = process.env.WEB_BASE ?? 'http://localhost:5173'
const REDIS_PASSWORD = process.env.REDIS_PASSWORD
const MYSQL_PWD = process.env.MYSQL_PASSWORD
/** 空数据账号：不带任何发布/收藏（13800000006 是干净的演示号） */
const EMPTY_PHONE = '13800000006'
/** 有数据账号：验收时会用它的身份造数据 */
const RICH_PHONE = '13800000007'

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

const browser = await puppeteer.default.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--disable-gpu', '--window-size=1500,1100'] })
const page = await browser.newPage()
await page.setViewport({ width: 1500, height: 1100 })
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(String(e)))
page.on('console', (m) => {
  if (m.type() === 'error') pageErrors.push('console: ' + m.text())
})
const shot = (name) => page.screenshot({ path: path.join(SHOT_DIR, name), fullPage: true })

/** 打开个人中心（注入 token 而不是走登录页，避免每次都要验证码） */
async function openProfile(token) {
  await page.goto(WEB, { waitUntil: 'domcontentloaded' })
  await page.evaluate((t) => localStorage.setItem('ershou_token', t), token)
  await page.goto(`${WEB}/profile`, { waitUntil: 'networkidle2' })
  await page.waitForSelector('.profile__hero', { timeout: 20000 })
  await sleep(1500)
}

/** 读页面上几个关键区域的状态 */
const readPage = () =>
  page.evaluate(() => {
    const text = (sel) => document.querySelector(sel)?.textContent?.replace(/\s+/g, ' ').trim() ?? null
    const statPairs = Array.from(document.querySelectorAll('.profile__stat')).map((el) => ({
      value: el.querySelector('.profile__stat-value')?.textContent?.trim(),
      label: el.querySelector('.profile__stat-label')?.textContent?.trim(),
    }))
    return {
      name: text('.profile__name'),
      creditLevel: text('.profile__credit-level'),
      creditScore: text('.profile__credit-score'),
      // 星级已按用户要求去掉（同一信息不说两遍）；这条断言防止它被加回来
      rateCount: document.querySelectorAll('.profile__credit .el-rate').length,
      hasTodoBlock: !!document.querySelector('.profile__todo'),
      todoIsClear: !!document.querySelector('.profile__todo--clear'),
      todoItems: Array.from(document.querySelectorAll('.profile__todo-item')).map((el) =>
        el.textContent.replace(/\s+/g, ' ').trim(),
      ),
      stats: statPairs,
      myItems: document.querySelectorAll('.profile__cols .panel:nth-child(1) .profile__item').length,
      myFavs: document.querySelectorAll('.profile__cols .panel:nth-child(2) .profile__item').length,
      emptyTexts: Array.from(document.querySelectorAll('.profile__empty')).map((el) =>
        el.textContent.replace(/\s+/g, ' ').trim(),
      ),
      archives: text('.profile__archive'),
      entryCount: document.querySelectorAll('.profile__entry').length,
    }
  })

let seeded = []
/** 加在别人商品上的收藏/购物车，清理时要单独删（见下方注释） */
let otherItemId = null
let richUserId = null

try {
  console.log('')
  console.log(`=== A. 空数据状态（${EMPTY_PHONE}）===`)
  resetSmsLimit([EMPTY_PHONE])
  const empty = await login(EMPTY_PHONE)
  // 确保这个号真的没有数据（有就说明选错账号了，直接说明而不是假装通过）
  const emptyItems = await get(`${API}/items/mine?pageSize=1`, empty.token)
  const emptyFavs = await get(`${API}/favorites?pageSize=1`, empty.token)
  if ((emptyItems.data?.total ?? 0) > 0 || (emptyFavs.data?.total ?? 0) > 0) {
    throw new Error(`账号 ${EMPTY_PHONE} 不是空数据（发布 ${emptyItems.data?.total} / 收藏 ${emptyFavs.data?.total}），换一个账号再跑`)
  }

  await openProfile(empty.token)
  const e = await readPage()
  check('身份卡渲染出昵称', !!e.name, String(e.name))
  // 信用等级必须是中文，绝不能是裸数字（改造前就是裸的 "2"，用户看不懂）
  check('信用等级是中文而非裸数字', !!e.creditLevel && !/^\d+$/.test(e.creditLevel), String(e.creditLevel))
  check('信用分有单位（分）', !!e.creditScore, String(e.creditScore))
  check('信用区不再显示星级（分数已说明一切）', e.rateCount === 0, `el-rate=${e.rateCount}`)
  check('没有待办时显示"暂无待处理"而不是空白', e.todoIsClear, JSON.stringify({ hasTodoBlock: e.hasTodoBlock, todoIsClear: e.todoIsClear }))
  check('四个概览数字都渲染了', e.stats.length === 4, JSON.stringify(e.stats))
  check('概览里不再出现"可发布数量"这类配额', !e.stats.some((s) => (s.label ?? '').includes('可发布')), JSON.stringify(e.stats))
  check('"我发布的"空状态有文案 + 去发布按钮', e.emptyTexts.some((t) => t.includes('去发布')), JSON.stringify(e.emptyTexts))
  check('"我收藏的"空状态有文案 + 去逛逛按钮', e.emptyTexts.some((t) => t.includes('去逛逛')), JSON.stringify(e.emptyTexts))
  check('常用入口仍可用', e.entryCount >= 5, String(e.entryCount))
  check('档案行里有用户编号与配额说明', !!e.archives && e.archives.includes('用户编号'), String(e.archives))
  await shot('01-empty.png')

  console.log('')
  console.log(`=== B. 有数据状态（${RICH_PHONE}）===`)
  resetSmsLimit([RICH_PHONE])
  const rich = await login(RICH_PHONE)
  // 造数据：2 件商品 + 1 个收藏 + 1 个购物车
  const img = await (async () => {
    // 复用一件已有商品的封面，省掉一次上传
    const pool = await get(`${API}/items?pageSize=3`)
    return pool.data?.records?.[0]?.coverUrl ?? ''
  })()
  try {
    execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'DEL', `ershou:limit:post:${rich.id}:${new Date().toISOString().slice(0, 10).replace(/-/g, '')}`], { stdio: 'ignore' })
  } catch {
    /* 计数键不存在也无所谓 */
  }
  for (let i = 1; i <= 2; i++) {
    const pub = await post(`${API}/items`, {
      title: `个人页验收商品${i}-${Date.now().toString().slice(-5)}`,
      description: '用于个人中心验收',
      categoryId: 1, conditionLevel: 2, price: 30 + i,
      images: img ? [img] : [], preferPointId: 1,
    }, rich.token)
    if (pub.code === 0) seeded.push(pub.data.itemId)
  }
  // 收藏一件别人的
  const pool = await get(`${API}/items?pageSize=5`)
  const other = (pool.data?.records ?? []).find((r) => r.seller?.id !== rich.id)
  if (other) await post(`${API}/favorites/${other.id}`, undefined, rich.token)
  if (other) await post(`${API}/cart`, { itemId: other.id, quantity: 1 }, rich.token)
  // ⚠️ 这条收藏/加购是加在**别人的商品**上的，清理时只按 seeded（自己发的商品 id）删是删不掉的，
  //    所以单独记下来，否则每跑一次都会在演示账号里留一条收藏 + 一条购物车（实测留过）。
  otherItemId = other?.id ?? null
  richUserId = rich.id

  await openProfile(rich.token)
  const r = await readPage()
  const onSale = r.stats.find((s) => s.label === '在售中')
  check('在售中数量反映了刚发布的商品', Number(onSale?.value ?? 0) >= 2, JSON.stringify(r.stats))
  check('我的收藏数量 > 0', Number(r.stats.find((s) => s.label === '我的收藏')?.value ?? 0) > 0, JSON.stringify(r.stats))
  check('"我发布的"列出了商品', r.myItems > 0, `items=${r.myItems}`)
  check('"我收藏的"列出了商品', r.myFavs > 0, `favs=${r.myFavs}`)
  check('有待办时显示"需要你处理"区块', r.hasTodoBlock && !r.todoIsClear, JSON.stringify({ hasTodoBlock: r.hasTodoBlock, todoIsClear: r.todoIsClear }))
  check('待办里能看到购物车待结算', r.todoItems.some((t) => t.includes('购物车')), JSON.stringify(r.todoItems))
  check('有数据时不再显示空状态文案', r.emptyTexts.length === 0, JSON.stringify(r.emptyTexts))
  await shot('02-with-data.png')

  // 点一下待办，应该跳到购物车
  const todoBox = await page.evaluate(() => {
    const el = document.querySelector('.profile__todo-item')
    if (!el) return null
    const b = el.getBoundingClientRect()
    return { x: b.x + b.width / 2, y: b.y + b.height / 2 }
  })
  if (todoBox) {
    await page.mouse.click(todoBox.x, todoBox.y)
    await sleep(1800)
    check('点待办能跳到对应页面（购物车）', page.url().includes('/cart'), page.url())
  } else {
    check('点待办能跳到对应页面（购物车）', false, '没找到待办项')
  }

  console.log('')
  console.log('=== C. 页面运行时报错 ===')
  const real = pageErrors.filter((e) => !e.includes('favicon') && !e.includes('Vue Devtools') && !e.includes('ResizeObserver') && !e.includes('Failed to load resource'))
  check('页面无 JS 报错', real.length === 0, real.slice(0, 3).join(' | '))
} catch (err) {
  fail++
  console.log('')
  console.log(`  [FATAL] ${err.message}`)
  await shot('99-fatal.png').catch(() => {})
} finally {
  await browser.close()
  // 清掉本次造的商品 + 收藏 + 购物车
  if (seeded.length || otherItemId) {
    console.log('')
    console.log('=== 清理验收数据 ===')
    try {
      const parts = []
      if (seeded.length) {
        parts.push(`DELETE FROM cart_item   WHERE item_id IN (${seeded.join(',')});`)
        parts.push(`DELETE FROM favorite    WHERE item_id IN (${seeded.join(',')});`)
        parts.push(`DELETE FROM item_review WHERE item_id IN (${seeded.join(',')});`)
        parts.push(`DELETE FROM item        WHERE id IN (${seeded.join(',')});`)
      }
      // 加在别人商品上的收藏/购物车：按「用户 + 商品」精确删，
      // 不能按 item_id 删（那会把别人自己的收藏也删了）
      if (otherItemId && richUserId) {
        parts.push(`DELETE FROM favorite  WHERE user_id = ${richUserId} AND item_id = ${otherItemId};`)
        parts.push(`DELETE FROM cart_item WHERE user_id = ${richUserId} AND item_id = ${otherItemId};`)
      }
      execFileSync('C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
        ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '--default-character-set=utf8mb4', '-e', `use ershou;\n${parts.join('\n')}`],
        { stdio: 'ignore', env: { ...process.env, MYSQL_PWD } })
      console.log(`  已清理商品 ${seeded.join(', ') || '（无）'}；收藏/购物车记录一并清掉`)
      // 索引会因为这些直接 SQL 删除而变脏，触发一次同步重建
      try {
        resetSmsLimit(['13800000009'])
        await post(`${API}/auth/sms-code`, { phone: '13800000009' })
        const adm = await post(`${API}/auth/login`, { phone: '13800000009', code: '123456', schoolId: 1, campusId: 1 })
        if (adm.code === 0) await post(`${API}/search/reindex?sync=true`, {}, adm.data.token)
      } catch {
        /* 重建失败不影响断言，只是索引会稍脏 */
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
