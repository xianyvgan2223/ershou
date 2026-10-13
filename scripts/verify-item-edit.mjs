/**
 * 商品详情页「修改商品」验收。
 *
 * 用户诉求：*"网页端在商品页面增加修改按钮，让商家能修改商品信息"*。
 *
 * 覆盖（全部真实点击，不用 el.click()）：
 *   A. 属主才看得到按钮：卖家有、别人没有、未登录没有
 *   B. 点开弹窗 → 字段带出的是当前商品的值（不是空的）
 *   C. 真改一个价格并保存 → 接口里读回来确实变了
 *   D. 改标题后状态可能变「审核中」→ 页面要跟着更新（不能还显示旧标题）
 *   E. 「我的发布」页用的是同一个弹窗组件（不再有两份表单）
 *   F. 已售出的商品不给改（按钮不出现）
 *
 * 用法：node scripts/verify-item-edit.mjs
 * 跑完自动清理：把商品改回原值（标题/价格/描述），并同步重建索引。
 */
import { existsSync } from 'node:fs'
import { mkdir } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const OUT = path.join(ROOT, '.build', 'item-edit')
const API = process.env.API_BASE ?? 'http://127.0.0.1:8080/api/v1'
const WEB = process.env.WEB_BASE ?? 'http://localhost:5173'
const RP = process.env.REDIS_PASSWORD ?? '__REDACTED_DB_PASSWORD__'
const MYSQL_PWD = process.env.MYSQL_PASSWORD ?? '__REDACTED_DB_PASSWORD__'
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'
const PHONE_SELLER = '13800000006' // 干净号（id 117，无历史商品，用它造数据不污染演示）
const PHONE_BUYER = '13800000007' // 买家 id 9

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

function resetLimits(phone) {
  try {
    const keys = execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', RP, '--no-auth-warning', 'KEYS', `ershou:sms:limit:${phone}:*`], { encoding: 'utf8' })
      .split('\n').map((s) => s.trim()).filter((s) => s.startsWith('ershou:'))
    if (keys.length) execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', RP, '--no-auth-warning', 'DEL', ...keys], { stdio: 'ignore' })
  } catch {}
}

async function login(phone) {
  resetLimits(phone)
  await req('POST', `${API}/auth/sms-code`, { body: { phone } })
  const res = await req('POST', `${API}/auth/login`, { body: { phone, code: '123456', schoolId: 1, campusId: 1 } })
  if (res.code !== 0) throw new Error(`登录失败 ${phone}：${res.message}`)
  return { token: res.data.token, id: res.data.user.id }
}

let puppeteer = null
for (const rel of ['../ershou-web', '..', '.']) {
  try {
    puppeteer = await import(new URL(rel + '/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js', import.meta.url).href)
    break
  } catch {}
}
const CHROME = ['C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe'].find((p) => existsSync(p))
await mkdir(OUT, { recursive: true })
const browser = await puppeteer.default.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--disable-gpu'] })
const page = await browser.newPage()
await page.setViewport({ width: 1400, height: 1000 })
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(String(e)))

async function openAs(token, urlPath) {
  await page.goto(WEB, { waitUntil: 'domcontentloaded' })
  if (token) await page.evaluate((t) => localStorage.setItem('ershou_token', t), token)
  else await page.evaluate(() => localStorage.removeItem('ershou_token'))
  await page.goto(`${WEB}${urlPath}`, { waitUntil: 'networkidle2' })
  await sleep(2200)
}

/** 找可点元素的中心坐标（真实鼠标点击用） */
const centerOf = (selector, textFilter = null) =>
  page.evaluate(
    (sel, tf) => {
      let els = Array.from(document.querySelectorAll(sel))
      if (tf) els = els.filter((e) => (e.textContent || '').includes(tf))
      const el = els.find((e) => {
        const r = e.getBoundingClientRect()
        return r.width > 0 && r.height > 0
      })
      if (!el) return null
      el.scrollIntoView({ block: 'center' })
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2, text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 30) }
    },
    selector,
    textFilter,
  )

/**
 * 自己造一件商品来测（不依赖现有演示数据）。
 *
 * ⚠️ 一开始写的是"从库里挑一件卖家 8 的在售商品"，结果演示数据一重置就 FATAL ——
 * 测试不该依赖别人维护的数据。这里用干净账号现发一件，测完删掉。
 */
async function seedItem(seller) {
  // 标题带随机后缀，避免和别的测试撞名；把这个**实际用的**标题一起返回，
  // 断言必须拿它来比 —— 否则比的是"基础名"，而库里存的是带后缀的那个（测试自己写错过一次）。
  const seededTitle = `编辑功能测试商品 ${Date.now() % 100000}`
  const res = await req('POST', `${API}/items`, {
    token: seller.token,
    body: {
      title: seededTitle,
      description: '这是自动化测试创建的商品，跑完会删除。',
      categoryId: 1,
      conditionLevel: 2,
      price: 88.5,
      originalPrice: 199,
      negotiable: 1,
      images: ['http://127.0.0.1:9010/ershou/test-upload.jpg'],
    },
  })
  if (res.code !== 0) throw new Error(`造商品失败：${res.code} ${res.message}`)
  const id = res.data?.itemId ?? res.data?.id
  if (!id) throw new Error(`造商品返回里没有 id：${JSON.stringify(res.data)}`)
  return { id, title: seededTitle, price: 88.5, status: res.data?.status ?? 2 }
}

/** 删掉本次造的商品（连同收藏/购物车里的引用，避免留下悬空数据） */
async function dropItem(id) {
  try {
    execFileSync(
      MYSQL,
      ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '-e',
        `use ershou; DELETE FROM favorite WHERE item_id=${id}; DELETE FROM cart_item WHERE item_id=${id}; DELETE FROM item WHERE id=${id};`],
      { stdio: 'ignore', env: { ...process.env, MYSQL_PWD } },
    )
  } catch (e) {
    console.log(`  清理商品失败：${e.message}`)
  }
}

let target = null
try {
  console.log('')
  console.log('=== 准备：卖家与买家各一个 ===')
  const seller = await login(PHONE_SELLER)
  const buyer = await login(PHONE_BUYER)
  console.log(`  卖家 id=${seller.id}  买家 id=${buyer.id}`)

  target = await seedItem(seller)
  console.log(`  现造商品 id=${target.id}「${target.title}」¥${target.price}`)

  // ---------------- A. 权限：只有属主看得到 ----------------
  console.log('')
  console.log('=== A. 只有卖家看得到「修改商品」 ===')
  await openAs(seller.token, `/item/${target.id}`)
  const sellerBtn = await centerOf('button', '修改商品')
  check('卖家能看到「修改商品」按钮', !!sellerBtn, JSON.stringify(sellerBtn))

  await openAs(buyer.token, `/item/${target.id}`)
  const buyerBtn = await centerOf('button', '修改商品')
  check('别的用户看不到该按钮', !buyerBtn)

  await openAs(null, `/item/${target.id}`)
  const anonBtn = await centerOf('button', '修改商品')
  check('未登录看不到该按钮', !anonBtn)

  // ---------------- B. 弹窗字段回显 ----------------
  console.log('')
  console.log('=== B. 点开后字段是当前值 ===')
  await openAs(seller.token, `/item/${target.id}`)
  const btn = await centerOf('button', '修改商品')
  await page.mouse.click(btn.x, btn.y)
  await sleep(1500)
  const fields = await page.evaluate(() => {
    const dlg = document.querySelector('.el-dialog')
    if (!dlg) return null
    const inputs = Array.from(dlg.querySelectorAll('input'))
    const val = (ph) => inputs.find((i) => (i.placeholder || '').includes(ph))?.value ?? null
    return {
      title: dlg.querySelector('.el-dialog__title')?.textContent?.trim(),
      titleValue: inputs[0]?.value ?? null,
      priceValue: inputs[1]?.value ?? null,
      textarea: dlg.querySelector('textarea')?.value?.slice(0, 20) ?? null,
      hasImageUploader: !!dlg.querySelector('.el-upload, [class*="upload"]'),
      hasCategorySelect: dlg.textContent.includes('分类'),
      hasPointSelect: dlg.textContent.includes('面交点'),
      warnText: dlg.textContent.includes('重新过内容审核') || dlg.textContent.includes('重新审核'),
    }
  })
  console.log('  弹窗：', JSON.stringify(fields))
  check('弹窗打开且标题为「编辑商品」', fields?.title === '编辑商品', JSON.stringify(fields?.title))
  check('标题带出当前值', fields?.titleValue === target.title, `${fields?.titleValue} vs ${target.title}`)
  check('价格带出当前值', String(fields?.priceValue).startsWith(String(target.price)), `${fields?.priceValue}`)
  check('支持改分类', fields?.hasCategorySelect === true)
  check('支持改面交点', fields?.hasPointSelect === true)
  check('支持改图片', fields?.hasImageUploader === true)
  check('提示了「改动会重新审核」', fields?.warnText === true)
  await page.screenshot({ path: path.join(OUT, '01-dialog.png') })

  // ---------------- C. 真改价格并保存 ----------------
  console.log('')
  console.log('=== C. 改价格并保存 → 接口确认落库 ===')
  const newPrice = target.price + 7
  const priceInput = await page.evaluate(() => {
    const dlg = document.querySelector('.el-dialog')
    const inp = Array.from(dlg.querySelectorAll('input'))[1]
    if (!inp) return null
    const r = inp.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
  })
  await page.mouse.click(priceInput.x, priceInput.y, { clickCount: 3 })
  await page.keyboard.down('Control')
  await page.keyboard.press('KeyA')
  await page.keyboard.up('Control')
  await page.keyboard.type(String(newPrice))
  await page.keyboard.press('Tab')
  await sleep(500)
  const saveBtn = await centerOf('.el-dialog__footer button, .el-dialog button', '保存')
  await page.mouse.click(saveBtn.x, saveBtn.y)
  await sleep(2600)

  const afterApi = await req('GET', `${API}/items/${target.id}`)
  check(
    '接口读回来的价格 = 新价',
    Number(afterApi.data?.price) === newPrice,
    `期望 ${newPrice}，实际 ${afterApi.data?.price}`,
  )
  const detailText = await page.evaluate(() => document.body.textContent.replace(/\s+/g, ' '))
  check('详情页上显示的是新价格', detailText.includes(String(newPrice)), `页面未出现 ${newPrice}`)
  await page.screenshot({ path: path.join(OUT, '02-after-save.png') })

  // ---------------- D. 改标题 → 状态与标题都要跟着变 ----------------
  console.log('')
  console.log('=== D. 改标题后页面同步（可能转「审核中」）===')
  const newTitle = `${target.title.replace(/（改）$/, '')}（改）`
  await openAs(seller.token, `/item/${target.id}`)
  const btn2 = await centerOf('button', '修改商品')
  if (btn2) {
    await page.mouse.click(btn2.x, btn2.y)
    await sleep(1500)
    const titleInput = await page.evaluate(() => {
      const dlg = document.querySelector('.el-dialog')
      const inp = dlg?.querySelectorAll('input')[0]
      if (!inp) return null
      const r = inp.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
    })
    await page.mouse.click(titleInput.x, titleInput.y, { clickCount: 3 })
    await page.keyboard.down('Control')
    await page.keyboard.press('KeyA')
    await page.keyboard.up('Control')
    await page.keyboard.type(newTitle)
    await page.keyboard.press('Tab')
    await sleep(400)
    const save2 = await centerOf('.el-dialog__footer button, .el-dialog button', '保存')
    await page.mouse.click(save2.x, save2.y)
    await sleep(3200)

    const api2 = await req('GET', `${API}/items/${target.id}`)
    check('接口里的标题已更新', api2.data?.title === newTitle, `${api2.data?.title}`)
    const body2 = await page.evaluate(() => document.body.textContent.replace(/\s+/g, ' '))
    check(
      '详情页不刷新也显示了新标题（页面自己重新拉了详情）',
      body2.includes(newTitle),
      `页面未见「${newTitle}」`,
    )
    console.log(`  改后状态 = ${api2.data?.status}（1=审核中，正常：改标题要重新过审）`)
    await page.screenshot({ path: path.join(OUT, '03-after-title.png') })
  } else {
    check('改标题后仍可再次编辑（在售或审核中都允许）', false, '按钮不见了')
  }

  // ---------------- E. 我的发布页用的是同一个组件 ----------------
  console.log('')
  console.log('=== E.「我的发布」页共用同一个弹窗 ===')
  await openAs(seller.token, '/publish-list')
  const editBtn = await centerOf('button', '编辑')
  check('我的发布页有「编辑」入口', !!editBtn)
  if (editBtn) {
    await page.mouse.click(editBtn.x, editBtn.y)
    await sleep(1500)
    const shared = await page.evaluate(() => {
      const dlg = document.querySelector('.el-dialog')
      return {
        title: dlg?.querySelector('.el-dialog__title')?.textContent?.trim(),
        // 共用组件的特征字段：分类 + 面交点
        hasCategory: (dlg?.textContent ?? '').includes('分类'),
        hasPoint: (dlg?.textContent ?? '').includes('面交点'),
      }
    })
    check('我的发布页打开的也是共用编辑弹窗（有分类/面交点字段）', shared.hasCategory && shared.hasPoint, JSON.stringify(shared))
    await page.screenshot({ path: path.join(OUT, '04-publish-list-dialog.png') })
  }

  // ---------------- F. 不可编辑状态 ----------------
  console.log('')
  console.log('=== F. 已售出的商品不给改 ===')
  execFileSync(
    MYSQL,
    ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '-e', `use ershou; UPDATE item SET status=4 WHERE id=${target.id};`],
    // ⚠️ MYSQL_PWD 必须传：漏了会认证失败，而 stdio:'ignore' 把报错吞掉 ——
    // 表现就是"SQL 写了但没生效"，然后断言失败指向无辜的前端（这次就是这么被骗了一轮）
    { stdio: 'ignore', env: { ...process.env, MYSQL_PWD } },
  )
  // 确认 DB 里真的改上了（别让"写失败"伪装成"前端 bug"）
  const soldStatus = execFileSync(
    MYSQL,
    ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '-N', '-B', '-e', `use ershou; SELECT status FROM item WHERE id=${target.id};`],
    { encoding: 'utf8', env: { ...process.env, MYSQL_PWD } },
  ).trim()
  check('（前置）DB 里状态已改成已售出 4', soldStatus === '4', `实际 ${soldStatus}`)

  // ⚠️ 必须手工删详情缓存。商品详情有 10 分钟 Redis 缓存，而缓存失效只挂在**后端写路径**
  // 的 afterCommit 上（CacheEvictor.itemChanged）。这里是裸 SQL 改状态、绕过了后端，
  // 缓存里仍是被测商品的旧值 → 接口一直返回 status=2 → 断言失败，看上去像前端按钮的 bug。
  // 这一步不是"为了让测试过"，而是还原真实情形：现实中"已售出"由下单流程写出，
  // 那条路径会正常删缓存（已用隔离脚本验证过：走接口的写路径状态始终一致）。
  execFileSync(
    'docker',
    ['exec', 'ershou-redis', 'redis-cli', '-a', RP, '--no-auth-warning', 'DEL', `ershou:item:detail:${target.id}`],
    { stdio: 'ignore' },
  )

  // 先确认接口确实报 4（否则失败会指向前端，实际是数据没改上/缓存没同步）
  const soldApi = await req('GET', `${API}/items/${target.id}`)
  console.log(`  接口返回的 status = ${soldApi.data?.status}（期望 4）`)
  check('（前置）接口也返回已售出 4（缓存已同步）', soldApi.data?.status === 4, `实际 ${soldApi.data?.status}`)
  await openAs(seller.token, `/item/${target.id}`)
  const soldBtn = await centerOf('button', '修改商品')
  const soldDiag = await page.evaluate(() => {
    const txt = document.body.textContent.replace(/\s+/g, ' ')
    return {
      hasEditBtn: Array.from(document.querySelectorAll('button')).some((b) => b.textContent.includes('修改商品')),
      hasEditLabel: txt.includes('已售出'),
      statusHint: (txt.match(/已售出|交易中|已下架/g) ?? []).slice(0, 3),
      // 页面上能看到的操作按钮（用来定位"为什么还有按钮"）
      buttons: Array.from(document.querySelectorAll('button'))
        .map((b) => b.textContent.replace(/\s+/g, ' ').trim())
        .filter((t) => t && t.length < 12)
        .slice(0, 12),
    }
  })
  console.log('  页面诊断：', JSON.stringify(soldDiag))
  check(
    '已售出时「修改商品」按钮消失',
    !soldBtn && !soldDiag.hasEditBtn,
    `接口 status=${soldApi.data?.status}，页面按钮=${JSON.stringify(soldDiag.buttons)}`,
  )
  await page.screenshot({ path: path.join(OUT, '05-sold-no-edit.png') })
} catch (err) {
  fail++
  console.log('')
  console.log(`  [FATAL] ${err.message}`)
  await page.screenshot({ path: path.join(OUT, '99-fatal.png') }).catch(() => {})
} finally {
  await browser.close()
  // 恢复现场：**整件删掉**（这次的商品是本脚本自己造的），再重建索引，
  // 否则首页/搜索会一直挂着这件测试商品。
  if (target) {
    console.log('')
    console.log('=== 清理：删除本次造的测试商品 ===')
    await dropItem(target.id)
    await req('POST', `${API}/search/reindex?sync=true`).catch(() => undefined)
    const left = execFileSync(
      MYSQL,
      ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '-N', '-B', '-e', `use ershou; SELECT COUNT(*) FROM item WHERE id=${target.id};`],
      { encoding: 'utf8', env: { ...process.env, MYSQL_PWD } },
    ).trim()
    console.log(`  已删除（库里残留 ${left} 条），索引已重建`)
  }
}

console.log('')
const real = pageErrors.filter((e) => !/favicon|Devtools|ResizeObserver/.test(e))
check('页面无 JS 报错', real.length === 0, real.slice(0, 2).join(' | '))
console.log(`截图目录：${OUT}`)
console.log(fail === 0 ? `全部通过：${pass} 项断言` : `通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
