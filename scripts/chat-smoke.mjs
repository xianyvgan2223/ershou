// 私聊端到端验证（REST + WebSocket）。
// 由 scripts/chat-smoke-test.ps1 调用并传入 CHAT_SMOKE_BASE；
// 也可直接跑： CHAT_SMOKE_BASE=http://127.0.0.1:8080/api/v1 node scripts/chat-smoke.mjs
import fs from 'node:fs';

const BASE = process.env.CHAT_SMOKE_BASE || 'http://127.0.0.1:8080/api/v1';
/** 上传用的测试图：与 admin 冒烟用的是同一张（.build/ 下，随项目脚本约定） */
const TEST_IMAGE = new URL('../.build/test-item.png', import.meta.url);
const WS = BASE.replace(/^http/, 'ws').replace(/\/api\/v1\/?$/, '') + '/ws/chat';

let pass = 0, fail = 0;
function check(name, actual, expected) {
  const ok = String(actual) === String(expected);
  console.log(`  [${ok ? 'PASS' : 'FAIL'}] ${name}${ok ? '' : `  期望=${expected} 实际=${actual}`}`);
  ok ? pass++ : fail++;
}
function checkTrue(name, cond, extra = '') {
  console.log(`  [${cond ? 'PASS' : 'FAIL'}] ${name}${cond ? '' : '  ' + extra}`);
  cond ? pass++ : fail++;
}

async function api(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  try { return JSON.parse(text); } catch { return { raw: text, httpStatus: res.status }; }
}

async function login(phone) {
  await api('/auth/sms-code', { method: 'POST', body: { phone } });
  const r = await api('/auth/login', { method: 'POST', body: { phone, code: '123456', schoolId: 1, campusId: 1 } });
  if (r.code !== 0) throw new Error('登录失败 ' + phone + ' => ' + JSON.stringify(r));
  return { token: r.data.token, id: r.data.user.id, nickname: r.data.user.nickname };
}

/** 极简 WS 客户端：收集所有帧，支持等待某个条件的帧 */
function connect(token, label) {
  const ws = new WebSocket(`${WS}?token=${token}`);
  const frames = [];
  const waiters = [];
  ws.addEventListener('message', (ev) => {
    const frame = JSON.parse(ev.data);
    frames.push(frame);
    for (let i = waiters.length - 1; i >= 0; i--) {
      if (waiters[i].predicate(frame)) { waiters[i].resolve(frame); waiters.splice(i, 1); }
    }
  });
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(label + ' WS 握手超时')), 5000);
    ws.addEventListener('open', () => { clearTimeout(timer); resolve(); });
    ws.addEventListener('error', (e) => { clearTimeout(timer); reject(new Error(label + ' WS 错误')); });
  });
  return {
    ws, frames,
    async send(obj) { await ready; ws.send(JSON.stringify(obj)); },
    next(predicate, timeout = 4000) {
      const found = frames.find(predicate);
      if (found) return Promise.resolve(found);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(label + ' 等待帧超时: ' + JSON.stringify(frames.map(f => f.type)))), timeout);
        waiters.push({ predicate, resolve: (f) => { clearTimeout(timer); resolve(f); } });
      });
    },
    async expectNone(predicate, ms = 900) {
      await new Promise((r) => setTimeout(r, ms));
      return frames.filter(predicate);
    },
    close() { try { ws.close(); } catch {} },
  };
}

const run = async () => {
  console.log('=== 1. 登录买卖双方 ===');
  const buyer = await login('13800000001');
  const seller = await login('13800000002');
  console.log(`  买家 id=${buyer.id}  卖家 id=${seller.id}`);

  console.log('=== 2. 卖家 WS 上线（未鉴权 vs 鉴权）===');
  const bad = new WebSocket(`${WS}?token=not-a-real-token`);
  const badResult = await new Promise((resolve) => {
    bad.addEventListener('error', () => resolve('rejected'));
    bad.addEventListener('open', () => resolve('opened'));
    setTimeout(() => resolve('timeout'), 4000);
  });
  check('无效 token 握手被拒', badResult, 'rejected');
  const s = connect(seller.token, '卖家');
  const b = connect(buyer.token, '买家');
  const readyFrame = await s.next((f) => f.type === 'ready');
  check('卖家收到 ready 帧', readyFrame.data.userId, seller.id);

  console.log('=== 3. 买家从商品发起会话 ===');
  const items = await api('/items?pageNum=1&pageSize=20');
  // 必须挑"卖家账号自己发布的商品"：后面的自己发自己(自聊拦截)、WS 实时投递都依赖这对买家↔卖家关系。
  // 不能只取列表第一条——用户手动发布的商品会排在最前面，卖家就不是测试账号了。
  const mine = await api(`/items?pageNum=1&pageSize=20&sellerId=${seller.id}`);
  const target = (mine.data?.records ?? [])[0];
  checkTrue('卖家名下有在售商品', Boolean(target), '先跑 scripts/smoke-test.ps1 生成卖家商品');
  if (!target) { s.close(); b.close(); return; }
  const opened = await api('/chat/sessions', { method: 'POST', token: buyer.token, body: { itemId: target.id } });
  check('发起会话', opened.code, 0);
  const sessionId = opened.data.id;
  check('会话带商品卡片', opened.data.itemTitle, target.title);
  check('会话对方是商品卖家', opened.data.peerId, seller.id);
  const again = await api('/chat/sessions', { method: 'POST', token: buyer.token, body: { itemId: target.id } });
  check('重复发起复用同一会话', again.data.id, sessionId);

  console.log('=== 4. 卖家给自己发？(自聊拦截) ===');
  const self = await api('/chat/sessions', { method: 'POST', token: seller.token, body: { itemId: target.id } });
  check('卖家和自己私聊被拒', self.code, 70001);

  console.log('=== 5. 买家 REST 发消息 → 卖家 WS 实时收到 ===');
  const m1 = await api('/chat/messages', { method: 'POST', token: buyer.token, body: { sessionId, content: '同学你好，这个还在吗？' } });
  check('REST 发送成功', m1.code, 0);
  check('消息未加密返回明文', m1.data.content, '同学你好，这个还在吗？');
  const pushed = await s.next((f) => f.type === 'message' && f.data?.content === '同学你好，这个还在吗？');
  checkTrue('卖家实时收到推送', Boolean(pushed), JSON.stringify(s.frames));
  check('推送里标识不是自己发的', pushed.data.mine, false);

  console.log('=== 6. 买家 WS 发消息 → 收到 ACK ===');
  await b.send({ type: 'send', sessionId, content: '支持当面验货吗', clientMsgId: 'c-1' });
  const ack = await b.next((f) => f.type === 'ack' && f.data?.clientMsgId === 'c-1');
  check('ACK 回带 clientMsgId', ack.data.clientMsgId, 'c-1');
  check('ACK 内容正确', ack.data.content, '支持当面验货吗');
  check('ACK mine=true', ack.data.mine, true);

  console.log('=== 7. 未读数（卖家 2 条未读）===');
  const sellerSessions = await api('/chat/sessions', { token: seller.token });
  check('卖家会话未读=2', sellerSessions.data[0].unread, 2);
  const sellerUnread = await api('/chat/unread', { token: seller.token });
  check('未读汇总 total=2', sellerUnread.data.total, 2);
  const buyerUnread = await api('/chat/unread', { token: buyer.token });
  check('买家未读=0', buyerUnread.data.total, 0);

  console.log('=== 8. 卖家标记已读 → 买家 WS 收到 read 事件 ===');
  const readResp = await api(`/chat/sessions/${sessionId}/read`, { method: 'POST', token: seller.token });
  check('标记已读成功', readResp.code, 0);
  const readFrame = await b.next((f) => f.type === 'read');
  check('买家收到已读回执', readFrame.data.sessionId, sessionId);
  const afterRead = await api('/chat/unread', { token: seller.token });
  check('已读后未读=0', afterRead.data.total, 0);

  console.log('=== 9. 聊天风控：可疑词弱提醒放行 / 高危词拦截留痕 ===');
  const review = await api('/chat/messages', { method: 'POST', token: buyer.token, body: { sessionId, content: '要不加微信转账吧' } });
  check('可疑词放行', review.code, 0);
  checkTrue('可疑词带弱提醒', Boolean(review.data.riskWarning), JSON.stringify(review.data));
  check('可疑词 riskLevel=2', review.data.riskLevel, 2);
  const blocked = await api('/chat/messages', { method: 'POST', token: buyer.token, body: { sessionId, content: '我这有管制刀具你要吗' } });
  check('高危词被拦截', blocked.code, 50002);
  const leaked = await s.expectNone((f) => f.type === 'message' && String(f.data?.content ?? '').includes('管制刀具'));
  check('被拦截消息没有推给卖家', leaked.length, 0);

  console.log('=== 10. 历史消息（游标 + 拦截消息只对发送方可见）===');
  const buyerHistory = await api(`/chat/sessions/${sessionId}/messages?size=50`, { token: buyer.token });
  const sellerHistory = await api(`/chat/sessions/${sessionId}/messages?size=50`, { token: seller.token });
  check('买家能看到自己发的 4 条（含被拦的）', buyerHistory.data.length, 4);
  check('卖家只看到 3 条（拦截的不投递）', sellerHistory.data.length, 3);
  checkTrue('买家历史里含被拦截消息', buyerHistory.data.some((m) => m.riskLevel === 3));
  checkTrue('卖家历史里没有被拦截消息', !sellerHistory.data.some((m) => m.riskLevel === 3));
  check('历史按时间正序', buyerHistory.data[0].content, '同学你好，这个还在吗？');
  const page2 = await api(`/chat/sessions/${sessionId}/messages?beforeId=${buyerHistory.data[1].id}&size=50`, { token: buyer.token });
  check('游标翻页只剩第 1 条', page2.data.length, 1);

  console.log('=== 11. 越权与参数校验 ===');
  const stranger = await api(`/chat/sessions/${sessionId}/messages`, { token: 'invalid-token' });
  check('无 token 访问被拒', stranger.code, 10002);
  const empty = await api('/chat/messages', { method: 'POST', token: buyer.token, body: { sessionId, content: '   ' } });
  check('空消息被拒', empty.code, 70004);
  const tooLong = await api('/chat/messages', { method: 'POST', token: buyer.token, body: { sessionId, content: 'x'.repeat(501) } });
  checkTrue('超长消息被拒', tooLong.code === 70004 || tooLong.code === 10001, JSON.stringify(tooLong));
  const noItem = await api('/chat/sessions', { method: 'POST', token: buyer.token, body: { itemId: 999999 } });
  check('商品不存在时不能建会话', noItem.code, 30001);

  console.log('=== 12. 快捷话术 / 通道状态 ===');
  const quick = await api('/chat/quick-replies', { token: buyer.token });
  checkTrue('快捷话术非空', Array.isArray(quick.data) && quick.data.length >= 5, JSON.stringify(quick));
  const status = await api('/chat/status', { token: buyer.token });
  check('买家本机连接数=1', status.data.localSessions, 1);

  console.log('=== 13. 图片消息（biz=chat 上传 + msgType=2 实时投递）===');
  // 这一步是回归防线：曾经因为 uploadFile() 把绝对路径又交给 axios 拼 baseURL，
  // 实际请求变成 /api/v1/api/v1/files/upload 而失败（前端表现为"图片发不出去"）
  checkTrue('测试图存在', fs.existsSync(TEST_IMAGE), TEST_IMAGE.pathname);
  const form = new FormData();
  form.append('file', new Blob([fs.readFileSync(TEST_IMAGE)], { type: 'image/png' }), 'chat-smoke.png');
  const uploaded = await fetch(`${BASE}/files/upload?biz=chat`, {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + buyer.token },
    body: form,
  }).then((r) => r.json());
  check('上传图片（biz=chat）', uploaded.code, 0);
  checkTrue('返回可访问 URL', /^https?:\/\//.test(uploaded.data?.url ?? ''), JSON.stringify(uploaded));
  const image = await api('/chat/messages', {
    method: 'POST', token: buyer.token,
    body: { sessionId, msgType: 2, content: uploaded.data.url },
  });
  check('发送图片消息', image.code, 0);
  check('消息类型 = 2', image.data.msgType, 2);
  const pushedImage = await s.next((f) => f.type === 'message' && f.data?.msgType === 2, 5000).catch(() => null);
  checkTrue('卖家实时收到图片消息', pushedImage?.data?.content === uploaded.data.url, JSON.stringify(pushedImage));

  console.log('=== 14. 双方身份（买卖角色）与用户主页 ===');
  const buyerSide = (await api('/chat/sessions', { token: buyer.token })).data.find((x) => x.id === sessionId);
  check('买家视角 myRole=buyer', buyerSide.myRole, 'buyer');
  check('买家视角 peerRole=seller', buyerSide.peerRole, 'seller');
  const sellerSide = (await api('/chat/sessions', { token: seller.token })).data.find((x) => x.id === sessionId);
  check('卖家视角 myRole=seller', sellerSide.myRole, 'seller');
  check('卖家视角 peerRole=buyer', sellerSide.peerRole, 'buyer');
  const profile = await api(`/users/${seller.id}/profile`, { token: buyer.token });
  check('用户主页可访问', profile.code, 0);
  check('主页昵称正确', profile.data.nickname, seller.nickname);
  checkTrue('主页不带手机号等联系方式', !('phone' in profile.data), JSON.stringify(Object.keys(profile.data)));
  check('主页 blocked=false', profile.data.blocked, false);
  check('主页 self=false', profile.data.self, false);
  const anonProfile = await api(`/users/${seller.id}/profile`);
  check('未登录也能看主页', anonProfile.code, 0);

  console.log('=== 15. 撤回消息 ===');
  const toRecall = await api('/chat/messages', {
    method: 'POST', token: buyer.token, body: { sessionId, content: '这条待会儿撤回' },
  });
  check('发送待撤回消息', toRecall.code, 0);
  const recalled = await api(`/chat/messages/${toRecall.data.id}/recall`, { method: 'POST', token: buyer.token });
  check('撤回成功', recalled.code, 0);
  check('撤回后 recalled=true', recalled.data.recalled, true);
  check('撤回后内容不再下发', recalled.data.content, '');
  const recallFrame = await s
    .next((f) => f.type === 'recall' && f.data?.id === toRecall.data.id, 5000)
    .catch(() => null);
  checkTrue('对方实时收到撤回事件', Boolean(recallFrame));
  const recallAgain = await api(`/chat/messages/${toRecall.data.id}/recall`, { method: 'POST', token: buyer.token });
  check('重复撤回幂等', recallAgain.code, 0);
  const recallOthers = await api(`/chat/messages/${toRecall.data.id}/recall`, { method: 'POST', token: seller.token });
  check('不能撤回别人的消息', recallOthers.code, 70007);

  console.log('=== 16. 拉黑（双向禁止私聊）===');
  const blockSelf = await api(`/users/${buyer.id}/block`, { method: 'POST', token: buyer.token });
  checkTrue('不能拉黑自己', blockSelf.code !== 0, JSON.stringify(blockSelf));
  check('买家拉黑卖家', (await api(`/users/${seller.id}/block`, { method: 'POST', token: buyer.token })).code, 0);
  check('重复拉黑幂等', (await api(`/users/${seller.id}/block`, { method: 'POST', token: buyer.token })).code, 0);
  const blockedSend = await api('/chat/messages', {
    method: 'POST', token: buyer.token, body: { sessionId, content: '拉黑后我自己还能发吗' },
  });
  check('拉黑后自己发不出去', blockedSend.code, 70006);
  const blockedSend2 = await api('/chat/messages', {
    method: 'POST', token: seller.token, body: { sessionId, content: '被拉黑方也发不出去' },
  });
  check('被拉黑方也发不出去', blockedSend2.code, 70006);
  const blockedSession = (await api('/chat/sessions', { token: buyer.token })).data.find((x) => x.id === sessionId);
  check('会话标记 blockedByMe', blockedSession.blockedByMe, true);
  check('会话 canSend=false', blockedSession.canSend, false);
  const blockList = await api('/users/blocks', { token: buyer.token });
  checkTrue('拉黑列表包含对方', blockList.data.some((b) => b.blockedUserId === seller.id), JSON.stringify(blockList.data));
  const blockedProfile = await api(`/users/${seller.id}/profile`, { token: buyer.token });
  check('主页显示已被我拉黑', blockedProfile.data.blocked, true);
  check('解除拉黑', (await api(`/users/${seller.id}/block`, { method: 'DELETE', token: buyer.token })).code, 0);
  const afterUnblock = await api('/chat/messages', {
    method: 'POST', token: buyer.token, body: { sessionId, content: '解除拉黑后恢复正常' },
  });
  check('解除后可以继续发', afterUnblock.code, 0);

  s.close(); b.close();
  console.log(`\n===== 私聊冒烟：PASS=${pass} FAIL=${fail} =====`);
  process.exit(fail === 0 ? 0 : 1);
};

run().catch((e) => { console.error('脚本异常：', e.message); process.exit(2); });
