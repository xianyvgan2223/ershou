/**
 * 上架审核实时推送探针（被 scripts/review-smoke-test.ps1 调用）。
 *
 * 做四件事：
 *   1) 用运营 token 连 ws://host/ws/admin/review，确认握手成功并收到 ready；
 *   2) 连上之后让卖家发一件新商品，量出"发布 → 推送到达"的毫秒数；
 *   3) 把这条标记已审核，确认收到 handled 事件；
 *   4) 用非运营账号连，确认被拒（403）。
 *
 * 输出：最后一行是 JSON（PS 侧解析），前面的都是人读的日志。
 * 为什么用 Node 而不是 PowerShell：PS 5.1 没有内置 WebSocket 客户端，
 * 而项目里所有 WS 探针（probe-ws*.mjs）都是这个套路，保持一致。
 */

const BASE = process.env.REVIEW_BASE || 'http://127.0.0.1:8080'
const ADMIN_TOKEN = process.env.REVIEW_TOKEN
const SELLER_TOKEN = process.env.REVIEW_SELLER_TOKEN
const BUYER_TOKEN = process.env.REVIEW_BUYER_TOKEN
const IMG = process.env.REVIEW_IMG

const WS_URL = BASE.replace(/^http/, 'ws') + '/ws/admin/review'
const API = BASE + '/api/v1'

const result = {
  ready: false,
  queuedMs: -1,
  queuedItemId: null,
  queuedPending: 0,
  publishedItemId: null,
  handled: false,
  nonAdminRejected: false,
  error: null,
}

const log = (...args) => console.log('[probe]', ...args)
/** 等待条件成立，超时抛错（比死等更早暴露问题） */
function waitFor(predicate, timeoutMs, label) {
  return new Promise((resolve, reject) => {
    const started = Date.now()
    const timer = setInterval(() => {
      if (predicate()) {
        clearInterval(timer)
        resolve(Date.now() - started)
      } else if (Date.now() - started > timeoutMs) {
        clearInterval(timer)
        reject(new Error('等待超时：' + label))
      }
    }, 50)
  })
}

async function main() {
  if (!ADMIN_TOKEN || !SELLER_TOKEN) {
    throw new Error('缺少 REVIEW_TOKEN / REVIEW_SELLER_TOKEN')
  }
  if (typeof WebSocket === 'undefined') {
    throw new Error('当前 node 没有内置 WebSocket（需要 node 22+）')
  }

  // ---------- 4) 先测非运营被拒：这条不依赖任何状态，放最前面最省事 ----------
  if (BUYER_TOKEN) {
    const rejected = await new Promise((resolve) => {
      const ws = new WebSocket(`${WS_URL}?token=${encodeURIComponent(BUYER_TOKEN)}`)
      const finish = (value) => {
        try {
          ws.close()
        } catch {}
        resolve(value)
      }
      ws.onopen = () => finish(false) // 居然连上了 = 鉴权失效
      ws.onerror = () => finish(true)
      ws.onclose = (ev) => finish(ev.code === 1006 || ev.code === 1008 || ev.code >= 4000)
      setTimeout(() => finish(false), 5000)
    })
    result.nonAdminRejected = rejected
    log('非运营账号连接被拒 =', rejected)
  }

  // ---------- 1) 运营连接 ----------
  const frames = []
  const ws = new WebSocket(`${WS_URL}?token=${encodeURIComponent(ADMIN_TOKEN)}&nickname=probe`)
  ws.onmessage = (event) => {
    try {
      frames.push(JSON.parse(event.data))
    } catch {
      /* 非 JSON 帧忽略 */
    }
  }
  await new Promise((resolve, reject) => {
    ws.onopen = resolve
    ws.onerror = () => reject(new Error('运营 WS 握手失败（token 或 admin 权限问题）'))
    setTimeout(() => reject(new Error('WS 连接超时')), 8000)
  })
  await waitFor(() => frames.some((f) => f.type === 'ready'), 5000, 'ready 帧')
  result.ready = true
  log('已连接，收到 ready')

  // 心跳，避免中间被判定离线（这轮测试的全过程只有几秒，但统一带上更稳）
  const heartbeat = setInterval(() => {
    try {
      ws.send(JSON.stringify({ type: 'ping' }))
    } catch {}
  }, 5000)

  // ---------- 2) 卖家发布 → 量推送延迟 ----------
  const publishAt = Date.now()
  const res = await fetch(`${API}/items`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8', Authorization: `Bearer ${SELLER_TOKEN}` },
    body: JSON.stringify({
      title: '上架审核WS探针商品',
      description: '描述正常',
      categoryId: 1,
      conditionLevel: 3,
      price: 6.6,
      images: IMG ? [IMG] : ['http://127.0.0.1:9010/ershou/test-item.png'],
      preferPointId: 1,
    }),
  })
  const published = await res.json()
  const itemId = published?.data?.itemId
  result.publishedItemId = itemId ?? null
  if (!itemId) throw new Error('发布失败：' + JSON.stringify(published))
  log('已发布商品 id =', itemId)

  await waitFor(() => frames.some((f) => f.type === 'queued' && f.data?.itemId === itemId), 5000, 'queued 帧')
  const queuedFrame = frames.find((f) => f.type === 'queued' && f.data?.itemId === itemId)
  result.queuedMs = Date.now() - publishAt
  result.queuedItemId = queuedFrame.data.itemId
  result.queuedPending = queuedFrame.data.pending
  log(`queued 推送到达，延迟约 ${result.queuedMs}ms，pending=${result.queuedPending}`)

  // ---------- 3) 标记已审核 → handled ----------
  const mark = await fetch(`${API}/admin/review/items/${itemId}/reviewed`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${ADMIN_TOKEN}` },
    body: '{}',
  })
  const markBody = await mark.json()
  if (markBody.code !== 0) throw new Error('标记已审核失败：' + JSON.stringify(markBody))
  await waitFor(() => frames.some((f) => f.type === 'handled' && f.data?.itemId === itemId), 5000, 'handled 帧')
  result.handled = true
  log('handled 推送到达')

  clearInterval(heartbeat)
  ws.close()
}

main()
  .catch((e) => {
    result.error = e.message
    log('失败：' + e.message)
  })
  .finally(() => {
    console.log(JSON.stringify(result))
    process.exit(0)
  })
