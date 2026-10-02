// 用 CDP 在网络层观察（不靠页面注入）：
//   - WebSocket 帧（Network.webSocketFrameSent / Received）
//   - 所有 XHR/fetch（Network.requestWillBeSent / responseReceived）
//
// 目的：定位"Enter 之后消息既没进库、也没发 WS"到底断在哪一步。
//
// 用法： node scripts/probe-chat-net.mjs <token> <sessionId>
const [token, sessionIdArg] = process.argv.slice(2)
const sessionId = Number(sessionIdArg)
if (!token || !sessionId) {
  console.error('用法: node scripts/probe-chat-net.mjs <token> <sessionId>')
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

const wsSent = []
const wsRecv = []
const httpLog = []

const cdp = await page.createCDPSession()
await cdp.send('Network.enable')
cdp.on('Network.webSocketFrameSent', (e) => wsSent.push(e.response.payloadData.slice(0, 200)))
cdp.on('Network.webSocketFrameReceived', (e) => wsRecv.push(e.response.payloadData.slice(0, 200)))
cdp.on('Network.webSocketCreated', (e) => console.log('  [WS 建立] ' + e.url))
cdp.on('Network.webSocketClosed', () => console.log('  [WS 关闭]'))
cdp.on('Network.webSocketFrameError', (e) => console.log('  [WS 帧错误] ' + e.errorMessage))
cdp.on('Network.requestWillBeSent', (e) => {
  if (e.request.url.includes('/api/')) httpLog.push(`→ ${e.request.method} ${e.request.url.replace(BASE, '')}`)
})
cdp.on('Network.responseReceived', (e) => {
  if (e.response.url.includes('/api/')) {
    httpLog.push(`← ${e.response.status} ${e.response.url.replace(BASE, '')}`)
  }
})
page.on('pageerror', (e) => console.log('  [PAGE ERROR] ' + e.message))

await page.goto(BASE, { waitUntil: 'domcontentloaded' })
await page.evaluate((t) => localStorage.setItem('ershou_token', t), token)
await page.goto(`${BASE}/chat?session=${sessionId}`, { waitUntil: 'networkidle2' })
await new Promise((r) => setTimeout(r, 3500))

console.log('')
console.log('=== 打开会话后的 HTTP ===')
httpLog.forEach((l) => console.log('  ' + l))
console.log('=== 握手 WS 帧 ===')
wsRecv.slice(0, 3).forEach((f) => console.log('  ← ' + f))

wsSent.length = 0
wsRecv.length = 0
httpLog.length = 0

const text = 'net-' + Date.now()
await page.click('textarea')
await page.type('textarea', text, { delay: 30 })
await page.keyboard.press('Enter')
await new Promise((r) => setTimeout(r, 3000))

console.log('')
console.log('=== Enter 之后的 WS 发出帧 ===')
if (!wsSent.length) console.log('  （无）')
wsSent.forEach((f) => console.log('  → ' + f))
console.log('=== Enter 之后的 WS 收到帧 ===')
if (!wsRecv.length) console.log('  （无）')
wsRecv.forEach((f) => console.log('  ← ' + f))
console.log('=== Enter 之后的 HTTP ===')
if (!httpLog.length) console.log('  （无）')
httpLog.forEach((l) => console.log('  ' + l))

const dom = await page.evaluate(() => {
  const bubbles = [...document.querySelectorAll('[class*="msg__body"], [class*="msg__meta"]')].map((e) =>
    e.textContent?.trim().slice(0, 60),
  )
  return { tail: bubbles.slice(-6), hasText: document.body.innerText.includes(window.__t ?? '') }
})
console.log('')
console.log('气泡尾部: ' + JSON.stringify(dom.tail))
await browser.close()
process.exit(0)
