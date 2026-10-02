// 实测网页端实时性：浏览器登录 B 并停在聊天页，
// 再从「后端 WebSocket」以 A 的身份发一条消息，看 B 的 DOM 有没有**自动**出现这条消息。
//
// 用途：区分"后端没推"和"前端没渲染"。
// 用法： node scripts/probe-web-realtime.mjs <bToken> <aToken> <sessionId>
const [bToken, aToken, sessionIdArg] = process.argv.slice(2)
const sessionId = Number(sessionIdArg)
if (!bToken || !aToken || !sessionId) {
  console.error('用法: node scripts/probe-web-realtime.mjs <bToken> <aToken> <sessionId>')
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

// 把浏览器的 WS 帧和 console 都打出来，好判断卡在哪一段
const wsLog = []
page.on('console', (m) => console.log('  [console] ' + m.text().slice(0, 160)))
await page.evaluateOnNewDocument(() => {
  const OrigWS = window.WebSocket
  window.__wsFrames = []
  // @ts-ignore
  window.WebSocket = function (...args) {
    const ws = new OrigWS(...args)
    window.__wsUrl = String(args[0])
    ws.addEventListener('message', (ev) => {
      window.__wsFrames.push(String(ev.data).slice(0, 200))
    })
    return ws
  }
  window.WebSocket.prototype = OrigWS.prototype
  window.WebSocket.OPEN = OrigWS.OPEN
})

await page.goto(BASE, { waitUntil: 'domcontentloaded' })
await page.evaluate((t) => localStorage.setItem('ershou_token', t), bToken)
await page.goto(`${BASE}/chat`, { waitUntil: 'networkidle2' })
await page.waitForFunction(() => document.querySelectorAll('.chat__session, .session-item, .el-menu-item').length > 0 || true, { timeout: 15000 })
await new Promise((r) => setTimeout(r, 3000))

const wsUrl = await page.evaluate(() => window.__wsUrl ?? null)
console.log('')
console.log('浏览器实际连接的 WS: ' + wsUrl)
const readyFrames = await page.evaluate(() => (window.__wsFrames ?? []).slice(0, 5))
console.log('握手阶段收到: ' + JSON.stringify(readyFrames))

// 打开目标会话（点一下会话项，或直接用 store —— 这里点列表第一项更接近真实操作）
const opened = await page.evaluate((sid) => {
  const items = [...document.querySelectorAll('[class*="session"]')]
  const hit = items.find((el) => el.textContent?.includes('同学'))
  if (hit) {
    hit.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    return hit.textContent?.slice(0, 30) ?? 'clicked'
  }
  return null
}, sessionId)
console.log('点开的会话项: ' + opened)
await new Promise((r) => setTimeout(r, 2000))

const before = await page.evaluate(() => document.body.innerText.length)

// 用 A 的身份通过后端 WS 发一条（绕过任何前端路径，纯看推送）
const text = 'push-' + Date.now()
await new Promise((resolve, reject) => {
  const ws = new WebSocket(`ws://127.0.0.1:8080/ws/chat?token=${encodeURIComponent(aToken)}`)
  ws.onmessage = (ev) => {
    const s = String(ev.data)
    if (s.includes('"ready"')) {
      ws.send(JSON.stringify({ type: 'send', sessionId, msgType: 1, content: text }))
    } else if (s.includes('"ack"')) {
      setTimeout(() => {
        ws.close()
        resolve()
      }, 300)
    }
  }
  ws.onerror = (e) => reject(new Error('A 连接失败 ' + (e?.message ?? '')))
  setTimeout(() => reject(new Error('A 发送超时')), 8000)
})
console.log('A 已发送: ' + text)

// 等前端渲染（正常应当 <1s）
await new Promise((r) => setTimeout(r, 3000))

const after = await page.evaluate(() => document.body.innerText)
const frames = await page.evaluate(() => (window.__wsFrames ?? []).slice(-5))
await page.screenshot({ path: 'E:\\ershou\\.build\\web-realtime.png' })

console.log('')
console.log('浏览器收到的最后几帧:')
frames.forEach((f) => console.log('  ← ' + f))
console.log('')
console.log('正文长度: ' + before + ' → ' + after.length)
if (after.includes(text)) console.log('结论：**前端实时渲染正常**（消息出现了）')
else console.log('结论：**前端没渲染** —— 帧到了但界面没更新（或帧根本没到，看上面）')

await browser.close()
process.exit(0)
