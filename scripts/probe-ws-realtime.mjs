// 端到端测「实时投递」：开两条 WebSocket（买家/卖家），
// 一方通过 socket 发消息，看另一方**是否立刻**收到 message 帧。
//
// 这才是"聊天不实时"的真正判据：能连上 ≠ 能实时投递。
//
// 用法： node scripts/probe-ws-realtime.mjs <buyerToken> <sellerToken> <sessionId> <peerId>
const [buyerToken, sellerToken, sessionIdArg, peerIdArg] = process.argv.slice(2)
const sessionId = Number(sessionIdArg)
const peerId = Number(peerIdArg)
if (!buyerToken || !sellerToken || !sessionId || !peerId) {
  console.error('用法: node scripts/probe-ws-realtime.mjs <buyerToken> <sellerToken> <sessionId> <peerId>')
  process.exit(2)
}

function open(name, token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:8080/ws/chat?token=${encodeURIComponent(token)}`)
    const seen = []
    ws.onmessage = (ev) => {
      const text = typeof ev.data === 'string' ? ev.data : String(ev.data)
      seen.push(text)
      if (text.includes('"ready"')) resolve({ name, ws, seen })
    }
    ws.onerror = (e) => reject(new Error(`${name} error ${e?.message ?? ''}`))
    setTimeout(() => reject(new Error(`${name} 握手超时`)), 6000)
  })
}

const buyer = await open('buyer', buyerToken)
const seller = await open('seller', sellerToken)
console.log('两条连接都就绪')
await new Promise((r) => setTimeout(r, 400))

// 清掉握手阶段的记录，只看接下来的
buyer.seen.length = 0
seller.seen.length = 0

const text = 'probe-' + Date.now()
console.log(`买家发送: ${text}`)
// ⚠️ 帧是**扁平**的：sessionId / content 必须放顶层。
// 服务端读的是 node.path("sessionId")，套一层 data 会解析成 0 → 70002 会话不存在
// （这个坑我自己先踩了一次，写探针时按 {type,data} 发，结果误判成"投递失败"）
buyer.ws.send(JSON.stringify({
  type: 'send',
  sessionId,
  msgType: 1,
  content: text,
  clientMsgId: 'probe-' + Date.now(),
}))

await new Promise((r) => setTimeout(r, 2500))

const sellerGot = seller.seen.filter((s) => s.includes(text))
const buyerAck = buyer.seen.filter((s) => s.includes(text))
console.log('')
console.log(`卖家收到 ${seller.seen.length} 帧，其中含本条 ${sellerGot.length} 条`)
seller.seen.slice(0, 4).forEach((s) => console.log('  seller ← ' + s.slice(0, 180)))
console.log(`买家收到 ${buyer.seen.length} 帧，其中含本条 ${buyerAck.length} 条`)
buyer.seen.slice(0, 4).forEach((s) => console.log('  buyer  ← ' + s.slice(0, 180)))

console.log('')
if (sellerGot.length > 0) {
  console.log('结论：**实时投递正常** —— 卖家立刻收到了 message 帧')
} else {
  console.log('结论：**实时投递失败** —— 卖家没收到（消息可能入库了但没推送）')
}
buyer.ws.close()
seller.ws.close()
process.exit(0)
