// 抓网页端聊天页的**运行时报错**，并在"收到推送消息"前后各测一次输入框是否还活着。
//
// 现象线索："输入框输入不了 + 网页看不到自己发的消息 + 收不到实时消息"，三者同时出现
// 很像**渲染报错把页面搞死了**（Vue 渲染抛错会让整棵子树停止更新，
// 表现就是"点了没反应、打字没反应、消息不出现"，刷新即恢复）。
//
// 用法： node scripts/probe-chat-errors.mjs <token> <peerToken> <sessionId>
const [token, peerToken, sessionIdArg] = process.argv.slice(2)
const sessionId = Number(sessionIdArg)
if (!token || !peerToken || !sessionId) {
  console.error('用法: node scripts/probe-chat-errors.mjs <token> <peerToken> <sessionId>')
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

const errors = []
page.on('pageerror', (e) => {
  errors.push('pageerror: ' + e.message)
  console.log('  [PAGE ERROR] ' + e.message.split('\n')[0])
})
page.on('console', (m) => {
  const t = m.text()
  if (m.type() === 'error' && !t.includes('[vite]')) {
    errors.push('console.error: ' + t)
    console.log('  [CONSOLE ERROR] ' + t.slice(0, 240))
  } else if (t.includes('Vue warn')) {
    errors.push('vue warn: ' + t)
    console.log('  [VUE WARN] ' + t.slice(0, 240))
  }
})

await page.goto(BASE, { waitUntil: 'domcontentloaded' })
await page.evaluate((t) => localStorage.setItem('ershou_token', t), token)
await page.goto(`${BASE}/chat?session=${sessionId}`, { waitUntil: 'networkidle2' })
await new Promise((r) => setTimeout(r, 3500))

async function inputAlive(label) {
  const ok = await page.evaluate(() => {
    const ta = document.querySelector('textarea')
    if (!ta) return { alive: false, why: '没有 textarea' }
    ta.focus()
    return { alive: true, focused: document.activeElement === ta }
  })
  let typed = ''
  if (ok.alive) {
    await page.type('textarea', 'x', { delay: 40 }).catch(() => {})
    typed = await page.evaluate(() => document.querySelector('textarea')?.value ?? '')
    await page.evaluate(() => {
      const ta = document.querySelector('textarea')
      if (ta) ta.value = ''
    })
  }
  console.log(`  [${label}] 输入框存活=${ok.alive} 能获得焦点=${ok.focused ?? '-'} 打字后值=${JSON.stringify(typed)}`)
  return ok.alive && typed.includes('x')
}

console.log('')
console.log('=== 收到推送「之前」 ===')
const before = await inputAlive('before')

// 从对端推一条真实消息（模拟移动端发消息）
const text = 'errprobe-' + Date.now()
await new Promise((resolve, reject) => {
  const ws = new WebSocket(`ws://127.0.0.1:8080/ws/chat?token=${encodeURIComponent(peerToken)}`)
  ws.onmessage = (ev) => {
    const s = String(ev.data)
    if (s.includes('"ready"')) ws.send(JSON.stringify({ type: 'send', sessionId, msgType: 1, content: text }))
    else if (s.includes('"ack"')) {
      ws.close()
      resolve()
    }
  }
  ws.onerror = (e) => reject(new Error('对端连接失败 ' + (e?.message ?? '')))
  setTimeout(() => reject(new Error('超时')), 10000)
})
console.log('  对端已推送: ' + text)
await new Promise((r) => setTimeout(r, 3000))

console.log('')
console.log('=== 收到推送「之后」 ===')
const after = await inputAlive('after')

const body = await page.evaluate(() => document.body.innerText)
const rendered = body.includes(text)
await page.screenshot({ path: 'E:\\ershou\\.build\\chat-errors.png' })

console.log('')
console.log('推送的消息渲染出来了吗: ' + (rendered ? '是 ✓' : '否 ✗'))
console.log('页面报错数: ' + errors.length)
if (errors.length) {
  console.log('--- 报错明细 ---')
  errors.slice(0, 8).forEach((e) => console.log('  ' + e.split('\n').slice(0, 4).join(' | ').slice(0, 320)))
}
console.log('')
console.log('输入框: 收消息前 ' + (before ? '活' : '死') + ' → 收消息后 ' + (after ? '活' : '死'))
await browser.close()
process.exit(errors.length ? 1 : 0)
