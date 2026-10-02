// 复现并验证「推送比历史先到」这个丢帧 bug。
//
// 手法：用 CDP 把网络限速（让 fetchChatMessages 慢下来），
// 在 B 打开会话、历史还在路上的窗口里，从 A 推一条消息过去。
//   - 修前：appendMessage 里 `if (!list) return` → 这条被静默丢掉
//   - 修后：会自动补历史再把这条合并进去
//
// 用法： node scripts/probe-web-race.mjs <bToken> <aToken> <sessionId>
const [bToken, aToken, sessionIdArg] = process.argv.slice(2)
const sessionId = Number(sessionIdArg)
if (!bToken || !aToken || !sessionId) {
  console.error('用法: node scripts/probe-web-race.mjs <bToken> <aToken> <sessionId>')
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

await page.goto(BASE, { waitUntil: 'domcontentloaded' })
await page.evaluate((t) => localStorage.setItem('ershou_token', t), bToken)

// 限速：把接口请求拖慢，制造"历史还在路上"的窗口
const cdp = await page.createCDPSession()
await cdp.send('Network.enable')
await cdp.send('Network.emulateNetworkConditions', {
  offline: false,
  latency: 1200,
  downloadThroughput: 60 * 1024,
  uploadThroughput: 60 * 1024,
})

console.log('')
console.log('打开 /chat（已限速 1.2s 延迟），历史会慢到')
await page.goto(`${BASE}/chat`, { waitUntil: 'domcontentloaded' })

// 等 WS 就绪（ready 帧），但**不等**历史加载完 —— 这正是要卡的窗口
await page.evaluateOnNewDocument(() => {})
await new Promise((r) => setTimeout(r, 2500))

// 直接指定会话：改 hash/query 让 ChatView 自动 openSession
await page.evaluate((sid) => {
  window.history.replaceState(null, '', `/chat?session=${sid}`)
  window.dispatchEvent(new PopStateEvent('popstate'))
}, sessionId)
console.log('已切到会话 ' + sessionId + '（历史还在加载中）')

// 立刻推送：这一刻 messages[sessionId] 很可能还不存在
const text = 'race-' + Date.now()
await new Promise((resolve, reject) => {
  const ws = new WebSocket(`ws://127.0.0.1:8080/ws/chat?token=${encodeURIComponent(aToken)}`)
  ws.onmessage = (ev) => {
    const s = String(ev.data)
    if (s.includes('"ready"')) ws.send(JSON.stringify({ type: 'send', sessionId, msgType: 1, content: text }))
    else if (s.includes('"ack"')) {
      ws.close()
      resolve()
    }
  }
  ws.onerror = (e) => reject(new Error('A 连接失败 ' + (e?.message ?? '')))
  setTimeout(() => reject(new Error('超时')), 10000)
})
console.log('A 已在"历史未就绪"时推送: ' + text)

// 恢复正常网速，等界面稳定
await cdp.send('Network.emulateNetworkConditions', {
  offline: false,
  latency: 0,
  downloadThroughput: -1,
  uploadThroughput: -1,
})
await new Promise((r) => setTimeout(r, 6000))

const body = await page.evaluate(() => document.body.innerText)
await page.screenshot({ path: 'E:\\ershou\\.build\\web-race.png' })

console.log('')
if (body.includes(text)) {
  console.log('结论：**推送没有丢** —— 即使历史还没就绪，这条消息也补上了 ✓')
} else {
  console.log('结论：**这条被丢了** —— 需要看 appendMessage 的兜底逻辑')
}
await browser.close()
process.exit(0)
