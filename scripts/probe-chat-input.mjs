// 实测聊天输入框能不能**真的打字**（用真实键盘事件，不是程序化 set value）。
//
// 用户反馈："输入框输入不了，但里面输入东西后点发送能发出去" —— 这两个现象的组合
// 指向"DOM 与 v-model 不同步"。这里逐个排查：
//   ① 真实敲键盘，看 textarea.value 和 Vue 的 draft 有没有同步
//   ② 看有没有 el-dialog / 遮罩层压在输入框上吃掉键盘
//   ③ 看输入框的 disabled / readonly 状态
//
// 用法： node scripts/probe-chat-input.mjs <token> <sessionId>
const [token, sessionIdArg] = process.argv.slice(2)
const sessionId = Number(sessionIdArg)
if (!token || !sessionId) {
  console.error('用法: node scripts/probe-chat-input.mjs <token> <sessionId>')
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
page.on('console', (m) => {
  const t = m.text()
  if (!t.includes('[vite]')) console.log('  [console] ' + t.slice(0, 200))
})

await page.goto(BASE, { waitUntil: 'domcontentloaded' })
await page.evaluate((t) => localStorage.setItem('ershou_token', t), token)
await page.goto(`${BASE}/chat?session=${sessionId}`, { waitUntil: 'networkidle2' })
await new Promise((r) => setTimeout(r, 3500))

console.log('')
// ③ 输入框状态
const state = await page.evaluate(() => {
  const ta = document.querySelector('textarea')
  if (!ta) return { found: false }
  const cs = getComputedStyle(ta)
  const rect = ta.getBoundingClientRect()
  // 取输入框中心点，看那个坐标上最顶层的元素是谁（有没有被遮罩压住）
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 2
  const top = document.elementFromPoint(cx, cy)
  return {
    found: true,
    disabled: ta.disabled,
    readOnly: ta.readOnly,
    pointerEvents: cs.pointerEvents,
    rect: { x: Math.round(rect.x), y: Math.round(rect.y), w: Math.round(rect.width), h: Math.round(rect.height) },
    topElementAtCenter: top ? `${top.tagName}.${(top.className || '').toString().slice(0, 60)}` : null,
    isTextareaOnTop: top === ta,
    dialogs: [...document.querySelectorAll('.el-overlay, .el-dialog, .el-drawer')].map(
      (d) => `${d.className} visible=${getComputedStyle(d).display !== 'none'}`,
    ),
  }
})
console.log('输入框状态: ' + JSON.stringify(state, null, 2))

if (!state.found) {
  console.log('')
  console.log('结论：**页面上根本没有 textarea** —— 聊天窗没渲染出来')
  await browser.close()
  process.exit(1)
}

// ① 真实键盘输入（focus + type，走的是真实按键事件）
await page.click('textarea')
await new Promise((r) => setTimeout(r, 300))
const focused = await page.evaluate(() => document.activeElement?.tagName)
console.log('点击后获得焦点的是: ' + focused)

await page.type('textarea', 'hello-typed', { delay: 60 })
await new Promise((r) => setTimeout(r, 800))

const after = await page.evaluate(() => {
  const ta = document.querySelector('textarea')
  // 顺便看看 Vue 那边收到的值：el-input 会把值同步到 textarea.value
  return {
    domValue: ta?.value ?? '(无)',
    // 字数统计是 el-input 基于 modelValue 渲染的，能反推 v-model 是否收到
    wordLimit: [...document.querySelectorAll('.el-input__count')].map((e) => e.textContent?.trim()),
  }
})
console.log('真实打字后 textarea.value = ' + JSON.stringify(after.domValue))
console.log('字数统计（反推 v-model）= ' + JSON.stringify(after.wordLimit))

await page.screenshot({ path: 'E:\\ershou\\.build\\chat-input-probe.png' })

console.log('')
if (after.domValue.includes('hello-typed')) {
  console.log('结论：**输入框能打字**（真实键盘事件有效）')
  console.log('      那么"输入不了"可能是浏览器/输入法层面的问题，或是页面在别的状态（比如被遮罩压住）')
} else {
  console.log('结论：**真的打不进去** —— 键盘事件没进到 textarea，看上面的 topElementAtCenter 与 dialogs')
}
await browser.close()
process.exit(0)
