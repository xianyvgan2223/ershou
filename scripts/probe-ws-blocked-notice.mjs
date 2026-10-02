// 验证「实时断开」时界面有没有明确提示。
//
// 手法：用 CDP 把 WebSocket 请求全部 abort（模拟代理没转 /ws、或网络阻断），
// 打开 /chat 看正文区是否出现"实时连接已断开"的提示。
// 这一条很重要：断线是静默的，没有提示用户只能靠"刷新才看到"察觉。
//
// 用法： node scripts/probe-ws-blocked-notice.mjs <token> <sessionId>
const [token, sessionIdArg] = process.argv.slice(2)
const sessionId = Number(sessionIdArg)
if (!token || !sessionId) {
  console.error('用法: node scripts/probe-ws-blocked-notice.mjs <token> <sessionId>')
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
await page.evaluate((t) => localStorage.setItem('ershou_token', t), token)

// 让 WebSocket 构造直接抛错：等价于"部署环境没把 /ws 转给后端"
// （CDP 的 setBlockedURLs 挡不住 WS 升级请求，实测无效，所以用注入的方式）
await page.evaluateOnNewDocument(() => {
  const Orig = window.WebSocket
  // @ts-ignore
  window.WebSocket = function () {
    throw new Error('blocked by probe')
  }
  window.WebSocket.prototype = Orig.prototype
  window.WebSocket.OPEN = Orig.OPEN
})

console.log('')
console.log('已屏蔽 */ws/chat*，打开 /chat?sessionId=' + sessionId)
await page.goto(`${BASE}/chat?sessionId=${sessionId}`, { waitUntil: 'networkidle2' })
await new Promise((r) => setTimeout(r, 4000))

const body = await page.evaluate(() => document.body.innerText)
console.log('--- 页面正文前 600 字 ---')
console.log(body.slice(0, 600).replace(/\n{2,}/g, '\n'))
console.log('--- 结束 ---')
const wsUrl = await page.evaluate(() => window.__wsUrl ?? '(未捕获)')
console.log('WS URL: ' + wsUrl)
const hasNotice = body.includes('实时连接已断开')
const hasListNotice = body.includes('连接中断')
await page.screenshot({ path: 'E:\\ershou\\.build\\web-ws-blocked.png' })

console.log('')
console.log('正文区出现「实时连接已断开」: ' + (hasNotice ? '是 ✓' : '否 ✗'))
console.log('侧栏出现「连接中断」: ' + (hasListNotice ? '是' : '否'))
if (hasNotice) console.log('结论：**断线可见** —— 用户不会再以为"聊天坏了"而反复刷新')
else console.log('结论：断线仍然是隐形的，需要检查提示块的渲染条件')
await browser.close()
process.exit(hasNotice ? 0 : 1)
