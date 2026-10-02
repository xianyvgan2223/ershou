// 精确复现用户的场景：**网页端先发一条，然后移动端（用 WS 冒充）再发一条**，
// 看网页端还能不能收到第二条。
//
// 同时记录浏览器**发出的每一帧**和**收到的每一帧**，卡在哪一段一目了然。
//
// 用法： node scripts/probe-web-send-then-receive.mjs <bToken> <aToken> <sessionId>
const [bToken, aToken, sessionIdArg] = process.argv.slice(2)
const sessionId = Number(sessionIdArg)
if (!bToken || !aToken || !sessionId) {
  console.error('用法: node scripts/probe-web-send-then-receive.mjs <bToken> <aToken> <sessionId>')
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

// 在页面里记录 WS 的双向帧
await page.evaluateOnNewDocument(() => {
  const Orig = window.WebSocket
  window.__wsSent = []
  window.__wsRecv = []
  window.__wsEvents = []
  // @ts-ignore
  window.WebSocket = function (url) {
    const ws = new Orig(url)
    window.__wsUrl = String(url)
    window.__wsEvents.push('open-constructed ' + Date.now())
    const origSend = ws.send.bind(ws)
    ws.send = (d) => {
      window.__wsSent.push(String(d).slice(0, 200))
      return origSend(d)
    }
    ws.addEventListener('message', (ev) => {
      window.__wsRecv.push(String(ev.data).slice(0, 200))
    })
    ws.addEventListener('close', (ev) => {
      window.__wsEvents.push(`close code=${ev.code} at ${Date.now()}`)
    })
    ws.addEventListener('error', () => {
      window.__wsEvents.push('error at ' + Date.now())
    })
    return ws
  }
  window.WebSocket.prototype = Orig.prototype
  window.WebSocket.OPEN = Orig.OPEN
})

await page.goto(BASE, { waitUntil: 'domcontentloaded' })
await page.evaluate((t) => localStorage.setItem('ershou_token', t), bToken)
await page.goto(`${BASE}/chat?session=${sessionId}`, { waitUntil: 'networkidle2' })
await new Promise((r) => setTimeout(r, 3500))

console.log('')
console.log('WS: ' + (await page.evaluate(() => window.__wsUrl ?? '(无)')))
const ready = await page.evaluate(() => window.__wsRecv.filter((f) => f.includes('ready')).length)
console.log('收到 ready 帧数: ' + ready)

// ① 网页端发一条（走真实 UI：填输入框 + 回车/点发送）
const webText = 'web-first-' + Date.now()
await page.evaluate((t) => {
  const ta = document.querySelector('textarea')
  if (!ta) return false
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set
  setter?.call(ta, t)
  ta.dispatchEvent(new Event('input', { bubbles: true }))
  return true
}, webText)
await new Promise((r) => setTimeout(r, 300))
const clicked = await page.evaluate(() => {
  const btns = [...document.querySelectorAll('button')]
  const send = btns.find((b) => /发送/.test(b.textContent ?? ''))
  if (send) {
    send.click()
    return true
  }
  return false
})
console.log('网页端发送: ' + webText + '（按钮点击=' + clicked + '）')
await new Promise((r) => setTimeout(r, 2000))

// ② 移动端（用 WS 冒充）发一条
const mobileText = 'mobile-second-' + Date.now()
await new Promise((resolve, reject) => {
  const ws = new WebSocket(`ws://127.0.0.1:8080/ws/chat?token=${encodeURIComponent(aToken)}`)
  ws.onmessage = (ev) => {
    const s = String(ev.data)
    if (s.includes('"ready"')) ws.send(JSON.stringify({ type: 'send', sessionId, msgType: 1, content: mobileText }))
    else if (s.includes('"ack"')) {
      ws.close()
      resolve()
    }
  }
  ws.onerror = (e) => reject(new Error('A 连接失败 ' + (e?.message ?? '')))
  setTimeout(() => reject(new Error('超时')), 10000)
})
console.log('移动端发送: ' + mobileText)
await new Promise((r) => setTimeout(r, 3500))

const sent = await page.evaluate(() => window.__wsSent ?? [])
const recv = await page.evaluate(() => window.__wsRecv ?? [])
const events = await page.evaluate(() => window.__wsEvents ?? [])
const bodyText = await page.evaluate(() => document.body.innerText)
await page.screenshot({ path: 'E:\\ershou\\.build\\web-send-then-recv.png' })

console.log('')
console.log('--- 浏览器发出的帧 (' + sent.length + ') ---')
sent.forEach((f, i) => console.log(`  ${i + 1}. ${f}`))
console.log('--- 浏览器收到的帧 (' + recv.length + ') ---')
recv.forEach((f, i) => console.log(`  ${i + 1}. ${f}`))
console.log('--- 连接事件 ---')
events.forEach((e) => console.log('  ' + e))

console.log('')
const gotMobile = bodyText.includes(mobileText)
const gotWeb = bodyText.includes(webText)
console.log(`网页端自己发的显示了吗: ${gotWeb ? '是' : '否'}`)
console.log(`移动端那条收到了吗:   ${gotMobile ? '是 ✓' : '否 ✗'}`)
console.log('')
if (gotMobile) console.log('结论：**这个流程是通的** —— 问题在别的前置条件上')
else console.log('结论：**复现了** —— 网页先发之后收不到对方的消息，看上面帧记录定位')
await browser.close()
process.exit(gotMobile ? 0 : 1)
