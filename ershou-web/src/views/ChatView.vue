<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ChatDotRound, MoreFilled, Picture, Promotion, View } from '@element-plus/icons-vue'
import { fetchChatSession, fetchQuickReplies } from '@/api/chat'
import type { ChatMessageVO } from '@/api/chat'
import { blockUser, unblockUser } from '@/api/user'
import {
  acceptMeetupOrder,
  cancelMeetupOrder,
  confirmMeetupOrder,
  fetchMeetupOrders,
  rejectMeetupOrder,
  updateMeetupPoint,
} from '@/api/order'
import type { OrderVO } from '@/api/order'
import type { TradePoint } from '@/api/map'
import MeetupBookingDialog from '@/components/MeetupBookingDialog.vue'
import PointPickerDialog from '@/components/PointPickerDialog.vue'
import { useChatStore } from '@/stores/chat'
import { useMeetupStore } from '@/stores/meetup'
import { uploadFile } from '@/api/file'
import { formatDateTime, formatPrice, formatRelativeTime, nameInitial, orderStatusTagType } from '@/utils/format'
import { showTip } from '@/utils/feedback'

const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const meetupStore = useMeetupStore()

/** 撤回时限与后端 ershou.chat.recall-window 保持一致 */
const RECALL_WINDOW_MS = 2 * 60 * 1000

const draft = ref('')
const quickReplies = ref<string[]>([])
const sending = ref(false)
const loadingMore = ref(false)
const scroller = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const currentId = computed(() => chat.currentSessionId)
const currentMessages = computed<ChatMessageVO[]>(() =>
  currentId.value === null ? [] : (chat.messages[currentId.value] ?? []),
)
const session = computed(() => chat.currentSession)
const peerShortName = computed(() => session.value?.peerName || '对方')
const canTrade = computed(() => {
  const status = session.value?.itemStatus
  return status === 2 || status === 3
})
const canSend = computed(() => session.value?.canSend !== false)

/** 当前会话对应的"进行中"面交单（1 待确认 / 2 待面交）：聊天窗里直接把状态和下一步动作摆出来 */
const activeOrder = ref<OrderVO | null>(null)
const orderLoading = ref(false)
const orderActionId = ref(false)
const bookingVisible = ref(false)
const isSeller = computed(() => session.value?.myRole === 'seller')

/* ---------- 改面交地点（买卖双方都能改，改完对方收到私聊提醒） ---------- */
const changePointVisible = ref(false)

/** 会话里点"改面交地点"：对话框自己解析校区与底图，这里只管开 */
function openChangePoint(): void {
  if (!activeOrder.value) return
  changePointVisible.value = true
}

/** 选点对话框已把点位建好（同名复用），这里把它落到面交单上 */
async function handlePointPicked(point: TradePoint): Promise<void> {
  const order = activeOrder.value
  if (!order) return
  try {
    await updateMeetupPoint(order.id, { pointId: point.id })
    showTip(`面交地点已改为「${point.name}」，已通知对方`)
    await loadActiveOrder()
  } catch {
    // 响应拦截器已提示（例如状态已变 30003）
  }
}

async function loadActiveOrder(): Promise<void> {
  const current = session.value
  if (!current?.itemId || !current.myRole || !current.peerId) {
    activeOrder.value = null
    return
  }
  orderLoading.value = true
  try {
    const page = await fetchMeetupOrders({
      role: current.myRole,
      itemId: current.itemId,
      pageNum: 1,
      pageSize: 10,
    })
    const peerId = current.peerId
    // 后端按"我在这单里是买还是卖"过滤，这里再按会话对方收敛到具体那一单
    activeOrder.value =
      (page.records ?? []).find(
        (order) =>
          (order.status === 1 || order.status === 2) &&
          (current.myRole === 'seller' ? order.buyerId === peerId : order.sellerId === peerId),
      ) ?? null
  } catch {
    activeOrder.value = null
  } finally {
    orderLoading.value = false
  }
}

async function runOrderAction(action: () => Promise<unknown>, successText: string): Promise<void> {
  if (!activeOrder.value || orderActionId.value) return
  const current = session.value
  orderActionId.value = true
  try {
    await action()
    showTip(successText)
    await loadActiveOrder()
    // 商品可能刚被锁定/售出，会话头部的商品卡片要跟着变
    if (current) await chat.refreshSession(current.id)
    await meetupStore.loadTodo()
  } catch {
    // 响应拦截器已提示（例如已被别人锁定 30004）
  } finally {
    orderActionId.value = false
  }
}

/** 卖家接受 = 锁定商品 */
function handleOrderAccept(): void {
  runOrderAction(() => acceptMeetupOrder(activeOrder.value!.id), '已接受，商品已给买家锁定')
}

async function handleOrderReject(): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('拒绝后买家会收到私聊提示，写清原因更好沟通', '拒绝这次约面交', {
      confirmButtonText: '确认拒绝',
      cancelButtonText: '再想想',
      inputPlaceholder: '例如：商品已经卖掉了 / 这两天没空',
      inputValidator: (value: string) => (value && value.trim().length > 0 ? true : '请填写拒绝原因'),
    })
    reason = result.value
  } catch {
    return
  }
  await runOrderAction(() => rejectMeetupOrder(activeOrder.value!.id, reason.trim()), '已拒绝，买家已收到提示')
}

async function handleOrderCancel(): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('取消后对方会收到私聊提示', '取消这次面交', {
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
      inputPlaceholder: '例如：临时有事，改天再约',
      inputValidator: (value: string) => (value && value.trim().length > 0 ? true : '请填写取消原因'),
    })
    reason = result.value
  } catch {
    return
  }
  await runOrderAction(() => cancelMeetupOrder(activeOrder.value!.id, reason.trim()), '已取消这次面交')
}

/** 卖家：已卖出 = 成交 */
function handleOrderSold(): void {
  runOrderAction(() => confirmMeetupOrder(activeOrder.value!.id), '交易已完成，信用分已加分')
}

/** 买家：我收到了（留痕，成交仍由卖家确认） */
function handleOrderReceived(): void {
  runOrderAction(() => confirmMeetupOrder(activeOrder.value!.id), '已记下「我收到了」')
}

/** 约面交：直接在聊天里开预约弹窗（原先只是跳回商品页，等于没作用） */
function openBooking(): void {
  if (!session.value?.itemId) return
  bookingVisible.value = true
}

function handleBooked(): void {
  showTip('面交申请已提交，卖家会收到私聊提醒')
  void loadActiveOrder()
  void meetupStore.loadTodo()
  const current = session.value
  if (current) void chat.refreshSession(current.id)
}

/** 角色文案：同一笔交易里谁是买家谁是卖家，列表和气泡上都要能看出来 */
function roleLabel(role: 'buyer' | 'seller' | null | undefined): string {
  if (role === 'seller') return '卖家'
  if (role === 'buyer') return '买家'
  return '同学'
}

function scrollToBottom(): void {
  void nextTick(() => {
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

async function openSession(id: number): Promise<void> {
  await chat.openSession(id)
  scrollToBottom()
  void loadActiveOrder()
}

async function loadMore(): Promise<void> {
  if (currentId.value === null) return
  loadingMore.value = true
  try {
    const added = await chat.loadMore(currentId.value)
    if (!added) showTip('没有更早的消息了', 'info')
  } finally {
    loadingMore.value = false
  }
}

async function submit(): Promise<void> {
  if (currentId.value === null || sending.value || !canSend.value) return
  const content = draft.value.trim()
  if (!content) return
  sending.value = true
  try {
    await chat.sendMessage(currentId.value, content, 1)
    draft.value = ''
    scrollToBottom()
  } finally {
    sending.value = false
  }
}

function useQuickReply(text: string): void {
  draft.value = text
}

function pickImage(): void {
  fileInput.value?.click()
}

async function onImagePicked(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || currentId.value === null) return
  try {
    const uploaded = await uploadFile(file, 'chat')
    await chat.sendMessage(currentId.value, uploaded.url, 2)
    scrollToBottom()
  } catch {
    // 上传失败已由响应拦截器提示
  }
}

function goItem(): void {
  if (session.value?.itemId) router.push(`/item/${session.value.itemId}`)
}

/** 看对方主页（买卖双方都能从会话头部进去） */
function goPeer(): void {
  const peerId = session.value?.peerId
  if (peerId) router.push(`/user/${peerId}`)
}

async function onHeadCommand(command: string): Promise<void> {
  const current = session.value
  if (!current?.peerId) return
  if (command === 'profile') {
    goPeer()
    return
  }
  if (command === 'block') {
    try {
      await ElMessageBox.confirm(
        '拉黑后你们将无法互相私聊，也看不到对方的会话消息。确定拉黑吗？',
        `拉黑 ${current.peerName || 'TA'}`,
        { confirmButtonText: '拉黑', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
    await blockUser(current.peerId)
    await chat.refreshSession(current.id)
    showTip('已拉黑，双方不能再私聊')
    return
  }
  if (command === 'unblock') {
    await unblockUser(current.peerId)
    await chat.refreshSession(current.id)
    showTip('已解除拉黑')
  }
}

/** 连续多条同一人的消息只在第一条显示"谁说的"，避免刷屏 */
function showWho(index: number): boolean {
  const list = currentMessages.value
  if (index === 0) return true
  const prev = list[index - 1]
  return !prev || prev.recalled === true || prev.senderId !== list[index].senderId
}

function canRecall(msg: ChatMessageVO): boolean {
  if (!msg.mine || msg.id <= 0 || msg.localStatus) return false
  const time = new Date(msg.createdAt.replace(' ', 'T')).getTime()
  return Number.isFinite(time) && Date.now() - time < RECALL_WINDOW_MS
}

async function recallMessage(msg: ChatMessageVO): Promise<void> {
  try {
    await ElMessageBox.confirm('撤回后对方将看不到这条消息', '撤回消息', {
      confirmButtonText: '撤回',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await chat.recall(msg.id)
  } catch {
    // 超过时限等由响应拦截器提示
  }
}

watch(
  () => route.query.session,
  (value) => {
    const id = Number(value)
    if (id && id !== chat.currentSessionId) void openSession(id)
  },
)

watch(currentMessages, () => scrollToBottom(), { deep: true })

onMounted(async () => {
  chat.connect()
  await chat.loadSessions()
  quickReplies.value = await fetchQuickReplies().catch(() => [])
  const fromQuery = Number(route.query.session)
  if (fromQuery) {
    await openSession(fromQuery)
  } else if (chat.sessions.length) {
    await openSession(chat.sessions[0].id)
  }
  // 直接刷新 /chat 时 store 里没有会话详情，补一次
  if (currentId.value && !chat.currentSession) {
    try {
      const detail = await fetchChatSession(currentId.value)
      chat.sessions.unshift(detail)
    } catch {
      // 会话可能已被清理，忽略
    }
  }
})
</script>

<template>
  <div class="chat" :class="{ 'chat--window': currentId !== null }">
    <!-- 左：会话列表 -->
    <aside class="chat__list">
      <header class="chat__list-head">
        <h2>消息</h2>
        <span class="chat__conn" :class="{ 'is-on': chat.connected }">
          {{ chat.connected ? '实时连接中' : '连接中断，重连中…' }}
        </span>
      </header>

      <div v-if="chat.loadingSessions && !chat.sessions.length" class="chat__list-empty">加载中…</div>
      <div v-else-if="!chat.sessions.length" class="chat__list-empty">
        <el-icon><ChatDotRound /></el-icon>
        <p>还没有会话</p>
        <p class="chat__hint">去商品详情点「私聊卖家」开始沟通</p>
      </div>

      <ul v-else class="chat__sessions">
        <li
          v-for="item in chat.sessions"
          :key="item.id"
          class="session"
          :class="{ 'is-active': item.id === currentId }"
          @click="openSession(item.id)"
        >
          <el-avatar :size="40" :src="item.peerAvatar || undefined">
            {{ nameInitial(item.peerName) }}
          </el-avatar>
          <div class="session__body">
            <div class="session__top">
              <span class="session__name">
                {{ item.peerName || '同学' }}
                <!-- 我在这一单里是买家还是卖家，一眼可见 -->
                <em v-if="item.myRole" class="role-chip" :class="`role-chip--${item.myRole}`">
                  {{ item.myRole === 'seller' ? '我卖' : '我买' }}
                </em>
                <em v-if="item.peerIdentityFlag === 1" class="session__badge">商家</em>
                <i v-if="item.peerOnline" class="session__online" title="在线" />
              </span>
              <span class="session__time">{{ formatRelativeTime(item.lastMsgAt) }}</span>
            </div>
            <div class="session__bottom">
              <span class="session__preview">{{ item.lastMsgText || '打个招呼吧' }}</span>
              <em v-if="item.unread > 0" class="session__unread">
                {{ item.unread > 99 ? '99+' : item.unread }}
              </em>
            </div>
            <p v-if="item.itemTitle" class="session__item">
              {{ item.itemTitle }}
              <span v-if="item.blockedByMe || item.blockedByPeer" class="session__blocked">· 已拉黑</span>
            </p>
          </div>
        </li>
      </ul>
    </aside>

    <!-- 右：聊天窗 -->
    <section class="chat__window">
      <template v-if="currentId !== null && session">
        <header class="chat__head">
          <el-avatar
            :size="34"
            :src="session.peerAvatar || undefined"
            class="chat__head-avatar"
            @click="goPeer"
          >
            {{ nameInitial(session.peerName) }}
          </el-avatar>
          <div class="chat__head-info">
            <strong>
              <a class="chat__peer" @click="goPeer">{{ session.peerName || '同学' }}</a>
              <em v-if="session.peerRole" class="role-chip" :class="`role-chip--${session.peerRole}`">
                {{ roleLabel(session.peerRole) }}
              </em>
              <em v-if="session.peerIdentityFlag === 1" class="session__badge">商家</em>
            </strong>
            <span>
              {{ session.peerOnline ? '在线' : '离线' }} · 信用 {{ session.peerCreditScore ?? '—' }}
              <template v-if="session.myRole"> · 我是{{ roleLabel(session.myRole) }}</template>
            </span>
          </div>

          <div v-if="session.itemId" class="chat__item" @click="goItem">
            <el-image :src="session.itemCover || ''" fit="cover" class="chat__item-cover" />
            <div class="chat__item-info">
              <span class="chat__item-title">{{ session.itemTitle }}</span>
              <span class="chat__item-price">
                ¥{{ formatPrice(session.itemPrice) }}
                <em>{{ session.itemStatusText }}</em>
              </span>
            </div>
          </div>

          <div class="chat__head-actions">
            <el-button v-if="session.itemId" size="small" :icon="View" round @click="goItem">
              查看商品
            </el-button>
            <el-button
              v-if="canTrade"
              size="small"
              type="primary"
              :icon="Promotion"
              round
              :disabled="Boolean(activeOrder)"
              :title="activeOrder ? '已有进行中的面交单，见下方' : ''"
              @click="openBooking"
            >
              约面交
            </el-button>
            <el-dropdown trigger="click" @command="onHeadCommand">
              <el-button size="small" circle :icon="MoreFilled" title="更多" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="profile">查看 TA 的主页</el-dropdown-item>
                  <el-dropdown-item v-if="session.blockedByMe" command="unblock" divided>
                    解除拉黑
                  </el-dropdown-item>
                  <el-dropdown-item v-else command="block" divided>拉黑 TA</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </header>

        <!-- 交易状态卡片：卖家收到申请能直接在这里锁定商品，买家也能看到进展 -->
        <div v-if="activeOrder" class="trade" v-loading="orderLoading">
          <div class="trade__head">
            <el-tag :type="orderStatusTagType(activeOrder.statusText)" size="small" effect="dark">
              {{ activeOrder.statusText }}
            </el-tag>
            <span class="trade__meta">
              面交点：{{ activeOrder.pointName || '待协商' }}
              · 时间：{{ activeOrder.timeSlotStart ? `${formatDateTime(activeOrder.timeSlotStart)} ~ ${formatDateTime(activeOrder.timeSlotEnd)}` : '待协商' }}
            </span>
            <!-- **只有卖家能改面交地点与时间**（2026-10-01 产品决定）：
                 商品在卖家手上，见面的地点与时间由他定更合理；买家想改就在私聊里商量。
                 后端 updatePoint 也做了同样的校验，这里只是把入口藏掉。 -->
            <el-button
              v-if="isSeller && (activeOrder.status === 1 || activeOrder.status === 2)"
              class="trade__edit"
              size="small"
              text
              type="primary"
              :disabled="orderActionId"
              @click="openChangePoint"
            >
              {{ activeOrder.pointName ? '改面交地点' : '约定面交地点' }}
            </el-button>
          </div>
          <p v-if="activeOrder.pointGuide" class="trade__guide">指引：{{ activeOrder.pointGuide }}</p>
          <p v-if="activeOrder.remark" class="trade__remark">买家留言：{{ activeOrder.remark }}</p>

          <div class="trade__actions">
            <!-- 卖家：待确认 → 接受（锁定商品）/ 拒绝 -->
            <template v-if="isSeller && activeOrder.status === 1">
              <el-button size="small" type="primary" :loading="orderActionId" @click="handleOrderAccept">
                接受并锁定商品
              </el-button>
              <el-button size="small" type="danger" plain :loading="orderActionId" @click="handleOrderReject">
                拒绝
              </el-button>
            </template>
            <!-- 卖家：待面交 → 已卖出（成交） -->
            <el-button
              v-if="isSeller && activeOrder.status === 2"
              size="small"
              type="success"
              :loading="orderActionId"
              @click="handleOrderSold"
            >
              已卖出（完成交易）
            </el-button>

            <!-- 买家：等我确认收货 -->
            <el-button
              v-if="!isSeller && activeOrder.status === 2 && !activeOrder.buyerConfirmed"
              size="small"
              type="success"
              plain
              :loading="orderActionId"
              @click="handleOrderReceived"
            >
              我收到了
            </el-button>
            <span v-else-if="!isSeller && activeOrder.status === 1" class="trade__hint">
              等待卖家确认，确认后商品会为你锁定
            </span>
            <span v-else-if="!isSeller && activeOrder.status === 2" class="trade__hint">
              已确认收到，等卖家完成交易
            </span>

            <el-button size="small" plain :loading="orderActionId" @click="handleOrderCancel">取消</el-button>
            <el-button size="small" text type="primary" @click="router.push('/orders')">查看全部面交单</el-button>
          </div>
        </div>

        <div ref="scroller" class="chat__messages">
          <div class="chat__more">
            <el-button link size="small" :loading="loadingMore" @click="loadMore">
              加载更早的消息
            </el-button>
          </div>

          <div
            v-for="(msg, index) in currentMessages"
            :key="msg.id"
            class="msg"
            :class="{ 'msg--mine': msg.mine, 'msg--recalled': msg.recalled }"
          >
            <p v-if="msg.recalled" class="msg__recalled">
              {{ msg.mine ? '你' : peerShortName }}撤回了一条消息
            </p>

            <template v-else>
              <!-- 对方消息带头像与"谁说的"，自己的一律绿色气泡，双方不再混淆 -->
              <el-avatar
                v-if="!msg.mine"
                :size="30"
                :src="session.peerAvatar || undefined"
                class="msg__avatar"
              >
                {{ nameInitial(session.peerName) }}
              </el-avatar>

              <div class="msg__main">
                <span v-if="!msg.mine && showWho(index)" class="msg__who">
                  {{ peerShortName }}（{{ roleLabel(session.peerRole) }}）
                </span>
                <div
                  class="msg__bubble"
                  :class="{
                    'is-image': msg.msgType === 2,
                    'is-risk': msg.riskLevel > 0,
                    'is-failed': msg.localStatus === 'failed',
                  }"
                >
                  <template v-if="msg.msgType === 2">
                    <el-image
                      :src="msg.content"
                      fit="cover"
                      class="msg__image"
                      :preview-src-list="[msg.content]"
                    />
                  </template>
                  <template v-else>{{ msg.content }}</template>
                </div>
                <div class="msg__meta">
                  <span>{{ formatRelativeTime(msg.createdAt) }}</span>
                  <span v-if="msg.mine && msg.localStatus === 'sending'">· 发送中</span>
                  <span v-else-if="msg.mine && msg.localStatus === 'failed'" class="msg__failed">
                    · 发送失败
                  </span>
                  <span v-else-if="msg.mine && chat.peerReadSessionId === msg.sessionId">· 已读</span>
                  <span v-if="msg.riskLevel === 3" class="msg__blocked">· 已被平台拦截，对方看不到</span>
                  <span v-else-if="msg.riskLevel > 0" class="msg__warn">· 含敏感词，请勿站外交易</span>
                </div>
              </div>

              <button
                v-if="canRecall(msg)"
                class="msg__recall"
                type="button"
                title="撤回这条消息（2 分钟内）"
                @click.stop="recallMessage(msg)"
              >
                撤回
              </button>
            </template>
          </div>
        </div>

        <footer class="chat__composer">
          <div v-if="!canSend" class="chat__blocked">
            {{ session.blockedByMe ? '你已拉黑 TA，解除后才能继续私聊' : '对方已将你拉黑，消息发不出去' }}
          </div>
          <div v-if="quickReplies.length && canSend" class="chat__quick">
            <span
              v-for="text in quickReplies"
              :key="text"
              class="chat__quick-item"
              @click="useQuickReply(text)"
            >
              {{ text }}
            </span>
          </div>
          <div class="chat__input">
            <el-input
              v-model="draft"
              type="textarea"
              :rows="2"
              resize="none"
              maxlength="500"
              show-word-limit
              :disabled="!canSend"
              :placeholder="
                canSend ? '想问问细节？Enter 发送，Shift+Enter 换行' : '当前无法发送消息'
              "
              @keydown.enter.exact.prevent="submit"
            />
            <div class="chat__input-actions">
              <el-button :icon="Picture" circle title="发送图片" :disabled="!canSend" @click="pickImage" />
              <el-button type="primary" round :loading="sending" :disabled="!canSend" @click="submit">
                发送
              </el-button>
            </div>
          </div>
          <input ref="fileInput" type="file" accept="image/*" hidden @change="onImagePicked" />
        </footer>
      </template>

      <div v-else class="chat__placeholder">
        <el-icon><ChatDotRound /></el-icon>
        <p>选择左侧会话开始聊天</p>
        <p class="chat__hint">平台只做信息撮合，请勿站外转账；建议在校内点位当面交易</p>
      </div>
    </section>

    <MeetupBookingDialog
      v-if="session?.itemId"
      v-model="bookingVisible"
      :item-id="session.itemId"
      @success="handleBooked"
    />

    <!-- 改面交地址：地图选点（可新建地点，免审核）→ 立刻落到面交单并通知对方 -->
    <PointPickerDialog v-model="changePointVisible" @created="handlePointPicked" />
  </div>
</template>

<style scoped lang="scss">
.chat {
  display: grid;
  overflow: hidden;
  height: calc(100vh - 132px);
  min-height: 480px;
  grid-template-columns: 300px 1fr;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: #fff;
  box-shadow: var(--shadow-xs);

  &__list {
    display: flex;
    overflow: hidden;
    flex-direction: column;
    border-right: 1px solid var(--line);
  }

  &__list-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    padding: 14px 16px 10px;

    h2 {
      margin: 0;
      color: var(--text-1);
      font-size: 16px;
    }
  }

  &__conn {
    color: var(--text-3);
    font-size: 11px;

    &.is-on {
      color: #16a34a;
    }
  }

  &__list-empty,
  &__placeholder {
    display: grid;
    flex: 1;
    place-content: center;
    gap: 4px;
    padding: 32px;
    color: var(--text-3);
    text-align: center;

    .el-icon {
      margin: 0 auto 6px;
      font-size: 30px;
    }

    p {
      margin: 0;
      font-size: 13px;
    }
  }

  &__hint {
    color: var(--text-3);
    font-size: 12px !important;
  }

  &__sessions {
    overflow-y: auto;
    flex: 1;
    margin: 0;
    padding: 0 8px 12px;
    list-style: none;
  }

  &__window {
    display: flex;
    overflow: hidden;
    flex-direction: column;
  }

  &__head {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 16px;
    border-bottom: 1px solid var(--line);
  }

  &__head-avatar {
    flex-shrink: 0;
    cursor: pointer;
  }

  &__head-info {
    display: flex;
    min-width: 0;
    flex-direction: column;

    strong {
      display: flex;
      align-items: center;
      gap: 5px;
      color: var(--text-1);
      font-size: 14px;
    }

    span {
      color: var(--text-3);
      font-size: 11.5px;
    }
  }

  &__peer {
    color: var(--text-1);
    cursor: pointer;

    &:hover {
      color: var(--brand-600);
      text-decoration: underline;
    }
  }

  &__item {
    display: flex;
    overflow: hidden;
    max-width: 320px;
    align-items: center;
    gap: 8px;
    margin-left: auto;
    padding: 6px 10px;
    border: 1px solid var(--line);
    border-radius: 10px;
    cursor: pointer;

    &:hover {
      border-color: var(--brand-200);
    }
  }

  &__item-cover {
    width: 32px;
    height: 32px;
    flex-shrink: 0;
    border-radius: 6px;
  }

  &__item-info {
    display: flex;
    min-width: 0;
    flex-direction: column;
  }

  &__item-title {
    overflow: hidden;
    color: var(--text-1);
    font-size: 12.5px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__item-price {
    color: var(--accent-500);
    font-size: 12px;
    font-weight: 600;

    em {
      margin-left: 6px;
      color: var(--text-3);
      font-size: 11px;
      font-style: normal;
      font-weight: 400;
    }
  }

  &__head-actions {
    display: flex;
    flex-shrink: 0;
    gap: 6px;
    margin-left: 8px;
  }

  &__messages {
    overflow-y: auto;
    flex: 1;
    padding: 12px 16px 4px;
    background: var(--surface-2);
  }

  &__more {
    text-align: center;
  }

  &__composer {
    padding: 8px 12px 12px;
    border-top: 1px solid var(--line);
  }

  &__blocked {
    margin-bottom: 8px;
    padding: 7px 10px;
    border-radius: 8px;
    background: #fef2f2;
    color: #b91c1c;
    font-size: 12.5px;
  }

  &__quick {
    display: flex;
    overflow-x: auto;
    gap: 6px;
    padding-bottom: 8px;
  }

  &__quick-item {
    flex-shrink: 0;
    padding: 3px 10px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: #fff;
    color: var(--text-2);
    font-size: 12px;
    cursor: pointer;

    &:hover {
      border-color: var(--brand-200);
      color: var(--brand-700);
    }
  }

  &__input {
    display: flex;
    align-items: flex-end;
    gap: 8px;

    :deep(.el-textarea__inner) {
      border-radius: 12px;
    }
  }

  &__input-actions {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    gap: 6px;
  }
}

// 角色徽章：买家 / 卖家 / 我买 / 我卖
.role-chip {
  padding: 1px 6px;
  border-radius: 5px;
  font-size: 10.5px;
  font-style: normal;
  font-weight: 600;

  &--seller {
    background: #fff7ed;
    color: #c2410c;
  }

  &--buyer {
    background: #eff6ff;
    color: #1d4ed8;
  }
}

.session {
  display: flex;
  gap: 10px;
  padding: 10px;
  border-radius: 10px;
  cursor: pointer;

  &:hover {
    background: var(--surface-2);
  }

  &.is-active {
    background: var(--brand-50);
  }

  &__body {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
    gap: 2px;
  }

  &__top,
  &__bottom {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;
  }

  &__name {
    display: flex;
    overflow: hidden;
    align-items: center;
    gap: 4px;
    color: var(--text-1);
    font-size: 13.5px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__badge {
    padding: 1px 5px;
    border-radius: 5px;
    background: var(--brand-50);
    color: var(--brand-700);
    font-size: 10px;
    font-style: normal;
    font-weight: 600;
  }

  &__online {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #22c55e;
  }

  &__time,
  &__item {
    color: var(--text-3);
    font-size: 11px;
  }

  &__item {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__blocked {
    color: #dc2626;
  }

  &__preview {
    overflow: hidden;
    color: var(--text-2);
    font-size: 12.5px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__unread {
    flex-shrink: 0;
    min-width: 18px;
    padding: 0 5px;
    border-radius: 999px;
    background: #ef4444;
    color: #fff;
    font-size: 11px;
    font-style: normal;
    text-align: center;
    line-height: 18px;
  }
}

.msg {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 12px;

  // 自己的消息整行右对齐（含头像位），对方靠左
  &--mine {
    flex-direction: row-reverse;
  }

  &--recalled {
    justify-content: center;
  }

  &__recalled {
    margin: 2px 0;
    color: var(--text-3);
    font-size: 12px;
  }

  &__avatar {
    flex-shrink: 0;
  }

  &__main {
    display: flex;
    max-width: min(620px, 74%);
    flex-direction: column;
    align-items: flex-start;
    gap: 3px;
  }

  &--mine &__main {
    align-items: flex-end;
  }

  &__who {
    color: var(--text-3);
    font-size: 11.5px;
  }

  &__bubble {
    padding: 8px 12px;
    border-radius: 12px;
    background: #fff;
    color: var(--text-1);
    font-size: 13.5px;
    line-height: 20px;
    word-break: break-word;
    box-shadow: var(--shadow-xs);

    .msg--mine & {
      background: var(--brand-500);
      color: #fff;
    }

    // 图片消息：本身有底色/内边距会变成一圈粗边框，这里只留圆角
    &.is-image {
      padding: 3px;
      background: #fff;
      box-shadow: var(--shadow-xs);
      line-height: 0;
    }

    &.is-risk {
      border: 1px dashed #f59e0b;
    }

    &.is-failed {
      opacity: 0.6;
    }
  }

  &__image {
    max-width: 200px;
    border-radius: 8px;
  }

  &__meta {
    display: flex;
    gap: 4px;
    color: var(--text-3);
    font-size: 11px;
  }

  &__recall {
    align-self: center;
    padding: 2px 8px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: #fff;
    color: var(--text-3);
    cursor: pointer;
    font-size: 11px;
    opacity: 0;
    transition: opacity 0.15s ease, color 0.15s ease;

    &:hover {
      border-color: #fca5a5;
      color: #dc2626;
    }
  }

  &:hover &__recall {
    opacity: 1;
  }

  &__warn {
    color: #b45309;
  }

  &__blocked,
  &__failed {
    color: #dc2626;
  }

  // 触屏没有 hover，撤回按钮常显
  @media (hover: none) {
    &__recall {
      opacity: 1;
    }
  }
}

@media (width <= 900px) {
  .chat {
    grid-template-columns: 1fr;
    height: calc(100vh - 112px);

    &__list {
      border-right: none;
    }

    &--window &__list {
      display: none;
    }

    &:not(.chat--window) &__window {
      display: none;
    }

    &__item {
      display: none;
    }
  }
}
.trade {
  margin: 10px 14px 0;
  padding: 10px 14px;
  border: 1px solid var(--brand-100);
  border-radius: var(--r-md);
  background: var(--brand-50);

  &__head {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  &__meta {
    color: var(--text-2);
    font-size: 13px;
  }

  &__edit {
    margin-left: auto;
  }

  &__guide {
    margin: 6px 0 0;
    color: var(--brand-700);
    font-size: 12.5px;
  }

  &__remark {
    margin: 6px 0 0;
    color: var(--text-3);
    font-size: 12.5px;
  }

  &__hint {
    color: var(--text-3);
    font-size: 12.5px;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 10px;
    flex-wrap: wrap;
  }
}
</style>
