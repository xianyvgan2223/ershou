// 实测「Enter 发送」：按真实的 Enter 键，看
//   ① 消息有没有发出去（WS 发了什么帧）
//   ② 输入框有没有清空
//   ③ 发送按钮/输入框有没有被 disabled 锁死（"卡住锁定"的具体含义）
//   ④ 有没有报错
//
// 用法： node scripts/probe-chat-enter.mjs <token> <sessionId>
const [token, sessionIdArg] = process.argv.slice(2)
const sessionId = Number(sessionIdArg)
if (!token || !sessionId) {
  console.error('用法: node scripts/probe-chat-enter.mjs <token> <sessionId>')
  process.exit(2)
}
const BASE = process.env.WEB_BASE ?? 'http://localhost:5173'
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const puppeteer = await import(
  new URL('../ershou-web/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js', import.meta.url).href
)

const browser = await puppeteer.default.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-gpu', '--window-size=1280,900'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1280, height: 900 })
page.on('pageerror', (e) => {
  console.log('  [PAGE ERROR] ' + e.message)
  console.log('  [STACK] ' + String(e.stack ?? '').split('\n').slice(0, 6).join('\n          '))
})
page.on('console', (m) => {
  if (m.type() === 'error' && !m.text().includes('[vite]')) console.log('  [ERR] ' + m.text().slice(0, 200))
})

// 记录 WS 发出的帧
await page.evaluateOnNewDocument(() => {
  const Orig = window.WebSocket
  window.__sent = []
  window.__recv = []
  // @ts-ignore
  window.WebSocket = function (url) {
    const ws = new Orig(url)
    // 拦 prototype 上的 send：实例属性在部分打包/代理下不生效
    const s = Orig.prototype.send
    Orig.prototype.send = function (d) {
      window.__sent.push(String(d).slice(0, 160))
      return s.call(this, d)
    }
    ws.addEventListener('message', (ev) => {
      window.__recv.push(String(ev.data).slice(0, 600))
    })
    return ws
  }
  window.WebSocket.prototype = Orig.prototype
  window.WebSocket.OPEN = Orig.OPEN
})

await page.goto(BASE, { waitUntil: 'domcontentloaded' })
await page.evaluate((t) => localStorage.setItem('ershou_token', t), token)
await page.goto(`${BASE}/chat?session=${sessionId}`, { waitUntil: 'networkidle2' })
await new Promise((r) => setTimeout(r, 3500))

/** 把输入框、发送按钮的状态抓出来（"卡住锁定"就是这些量） */
async function snapshot(label) {
  const s = await page.evaluate(() => {
    const ta = document.querySelector('textarea')
    const btns = [...document.querySelectorAll('button')]
    const send = btns.find((b) => /发送/.test(b.textContent ?? ''))
    return {
      textareaDisabled: ta?.disabled ?? null,
      textareaValue: ta?.value ?? null,
      sendDisabled: send?.disabled ?? null,
      sendLoading: send ? !!send.querySelector('.is-loading, .el-icon.is-loading') || send.className.includes('is-loading') : null,
      sendClass: send?.className ?? null,
      // 有没有"重试/发送中"这类气泡状态
      pendingBubbles: [...document.querySelectorAll('[class*="msg"]')]
        .map((e) => e.textContent?.trim() ?? '')
        .filter((t) => /发送中|重试|失败/.test(t))
        .slice(0, 3),
    }
  })
  console.log(`  [${label}] ` + JSON.stringify(s))
  return s
}

console.log('')
console.log('=== 打字前 ===')
await snapshot('初始')

const text = 'enter-' + Date.now()
await page.click('textarea')
await page.type('textarea', text, { delay: 30 })
await snapshot('打字后')

console.log('')
console.log('=== 按 Enter ===')
await page.keyboard.press('Enter')
await new Promise((r) => setTimeout(r, 1800))
const afterEnter = await snapshot('Enter 后')

// 再等一会，看是否有异步补发（REST 兜底）
await new Promise((r) => setTimeout(r, 2500))
const settled = await snapshot('再等 2.5s')

const sent = await page.evaluate(() => window.__sent ?? [])
const body = await page.evaluate(() => document.body.innerText)
await page.screenshot({ path: 'E:\\ershou\\.build\\chat-enter.png' })

console.log('')
console.log('--- WS 发出的帧 ---')
sent.forEach((f, i) => console.log(`  ${i + 1}. ${f}`))
const recv = await page.evaluate(() => window.__recv ?? [])
console.log('--- WS 收到的帧 ---')
recv.slice(-4).forEach((f, i) => console.log(`  ${i + 1}. ${f}`))

console.log('')
console.log('消息出现在界面上了吗: ' + (body.includes(text) ? '是' : '否'))
console.log('')
const locked = settled.textareaDisabled || settled.sendDisabled
console.log('是否被锁死: ' + (locked ? '**是（textareaDisabled/sendDisabled 为 true）**' : '否'))
if (afterEnter.textareaValue === text) {
  console.log('输入框内容没被清空 —— Enter 可能没触发发送（值还在输入框里）')
} else if (afterEnter.textareaValue === '') {
  console.log('输入框已清空 —— Enter 触发了发送')
}
await browser.close()
process.exit(locked ? 1 : 0)
