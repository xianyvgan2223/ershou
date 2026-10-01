// 直连测 WebSocket：分别打「Vite 代理 (5173)」和「后端直连 (8080)」，
// 看握手是否成功、能不能收到 ready 帧。用来定位"聊天不实时"是卡在哪一段。
//
// 用法： node scripts/probe-ws.mjs <token> [sessionId]
const token = process.argv[2]
const sessionId = process.argv[3] ? Number(process.argv[3]) : null
if (!token) {
  console.error('用法: node scripts/probe-ws.mjs <token> [sessionId]')
  process.exit(2)
}

function probe(name, url, { sendMsg = false } = {}) {
  return new Promise((resolve) => {
    const frames = []
    let settled = false
    const done = (result) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      try {
        ws.close()
      } catch {
        /* ignore */
      }
      resolve({ name, ...result, frames })
    }
    const timer = setTimeout(() => done({ ok: false, reason: '超时(8s) 没收到任何帧' }), 8000)

    let ws
    try {
      ws = new WebSocket(url)
    } catch (e) {
      return done({ ok: false, reason: '构造失败: ' + e.message })
    }
    ws.onopen = () => {
      console.log(`  [${name}] 握手成功`)
      if (sendMsg && sessionId) {
        ws.send(JSON.stringify({ type: 'send', data: { sessionId, msgType: 1, content: 'ws-probe' } }))
      }
    }
    ws.onmessage = (ev) => {
      const text = typeof ev.data === 'string' ? ev.data : String(ev.data)
      frames.push(text.slice(0, 200))
      console.log(`  [${name}] 收到帧: ${text.slice(0, 160)}`)
      // ready/hello 就算连通；再等一小会儿收集后续帧
      setTimeout(() => done({ ok: true, reason: '收到帧' }), 800)
    }
    ws.onerror = (e) => {
      console.log(`  [${name}] error: ${e?.message ?? e?.type ?? 'unknown'}`)
    }
    ws.onclose = (ev) => {
      done({ ok: false, reason: `关闭 code=${ev.code} reason=${ev.reason || '(空)'}` })
    }
  })
}

const q = `token=${encodeURIComponent(token)}`
console.log('')
console.log('=== A. 经 Vite 代理（前端实际走的就是这条）===')
const a = await probe('proxy:5173', `ws://localhost:5173/ws/chat?${q}`)
console.log('  结果: ' + (a.ok ? '连通' : '失败 → ' + a.reason))

console.log('')
console.log('=== B. 直连后端 8080 ===')
const b = await probe('direct:8080', `ws://127.0.0.1:8080/ws/chat?${q}`)
console.log('  结果: ' + (b.ok ? '连通' : '失败 → ' + b.reason))

console.log('')
if (a.ok && b.ok) console.log('结论：两端都能连 → 实时不通要在「发消息路径」或「前端帧处理」里找')
else if (!a.ok && b.ok) console.log('结论：**Vite 代理这一段坏了** —— 前端连不上，只能靠轮询兜底')
else if (a.ok && !b.ok) console.log('结论：代理能连、直连不行，检查代理 target 与后端地址')
else console.log('结论：**后端 WebSocket 就不通** —— 看后端日志与 ChatProperties.enabled')

process.exit(0)
