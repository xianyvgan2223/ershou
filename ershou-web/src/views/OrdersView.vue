<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Location, Refresh, Tickets } from '@element-plus/icons-vue'
import {
  acceptMeetupOrder,
  cancelMeetupOrder,
  confirmMeetupOrder,
  fetchMeetupOrders,
  rejectMeetupOrder,
  verifyMeetupOrder,
} from '@/api/order'
import type { OrderRole, OrderVO } from '@/api/order'
import { formatDateTime, formatPrice, orderStatusTagType } from '@/utils/format'

const router = useRouter()

const role = ref<OrderRole>('buyer')
const orders = ref<OrderVO[]>([])
const loading = ref(false)
const actionId = ref<number | null>(null)
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

const verifyVisible = ref(false)
const verifyCodeInput = ref('')
const currentOrder = ref<OrderVO | null>(null)
const codeVisible = ref(false)
const codeOrder = ref<OrderVO | null>(null)

/** 后端 statusText 已中文化，这里按其关键字决定可执行动作 */
const CLOSED_KEYS = ['取消', '拒绝', '关闭', '失效', '过期']

function isClosed(order: OrderVO): boolean {
  return CLOSED_KEYS.some((key) => (order.statusText || '').includes(key))
}

function isDone(order: OrderVO): boolean {
  return (order.statusText || '').includes('完成')
}

/** 待卖家确认：卖家可接受/拒绝，买家可取消 */
function isPendingSeller(order: OrderVO): boolean {
  return !isClosed(order) && !isDone(order) && (order.statusText || '').includes('待卖家')
}

/** 已核销待确认：双方可确认完成 */
function isVerified(order: OrderVO): boolean {
  return !isClosed(order) && !isDone(order) && (order.statusText || '').includes('核销')
}

/** 待面交 / 待到店：可以出示或输入核销码 */
function isWaiting(order: OrderVO): boolean {
  return !isClosed(order) && !isDone(order) && !isPendingSeller(order)
}

/** 核销之后（或状态里出现「确认」）才允许点确认完成 */
function canConfirm(order: OrderVO): boolean {
  return isVerified(order) || (isWaiting(order) && (order.statusText || '').includes('确认'))
}

const currentRoleText = computed(() => (role.value === 'buyer' ? '我买到的' : '我卖出的'))

async function loadOrders(): Promise<void> {
  loading.value = true
  try {
    const result = await fetchMeetupOrders({ role: role.value, pageNum: pageNum.value, pageSize: pageSize.value })
    orders.value = result.records ?? []
    total.value = result.total ?? 0
  } catch {
    orders.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleRoleChange(): void {
  pageNum.value = 1
  loadOrders()
}

function handlePageChange(page: number): void {
  pageNum.value = page
  loadOrders()
}

async function runAction(order: OrderVO, action: () => Promise<void>, successText: string): Promise<void> {
  actionId.value = order.id
  try {
    await action()
    ElMessage.success(successText)
    await loadOrders()
  } catch {
    // 响应拦截器已提示
  } finally {
    actionId.value = null
  }
}

function handleAccept(order: OrderVO): void {
  runAction(order, async () => { await acceptMeetupOrder(order.id) }, '已接受面交单')
}

async function handleReject(order: OrderVO): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('请填写拒绝原因，方便买家了解', '拒绝面交单', {
      confirmButtonText: '确认拒绝',
      cancelButtonText: '再想想',
      inputPlaceholder: '例如：商品已经卖出去了',
      inputValidator: (value: string) => (value && value.trim().length > 0 ? true : '请填写拒绝原因'),
    })
    reason = result.value
  } catch {
    return
  }
  await runAction(order, async () => { await rejectMeetupOrder(order.id, reason.trim()) }, '已拒绝面交单')
}

async function handleCancel(order: OrderVO): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('请填写取消原因', '取消面交单', {
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
      inputPlaceholder: '例如：临时有事，改天再约',
      inputValidator: (value: string) => (value && value.trim().length > 0 ? true : '请填写取消原因'),
    })
    reason = result.value
  } catch {
    return
  }
  await runAction(order, async () => { await cancelMeetupOrder(order.id, reason.trim()) }, '已取消面交单')
}

function openVerifyDialog(order: OrderVO): void {
  currentOrder.value = order
  verifyCodeInput.value = ''
  verifyVisible.value = true
}

function openCodeDialog(order: OrderVO): void {
  codeOrder.value = order
  codeVisible.value = true
}

async function submitVerify(): Promise<void> {
  const order = currentOrder.value
  const code = verifyCodeInput.value.trim()
  if (!order) return
  if (code.length < 4) {
    ElMessage.warning('请输入完整的核销码')
    return
  }
  actionId.value = order.id
  try {
    const result = await verifyMeetupOrder(order.id, code)
    ElMessage.success(result.needConfirm ? '核销成功，请确认完成交易' : '核销成功')
    verifyVisible.value = false
    await loadOrders()
  } catch {
    // 响应拦截器已提示
  } finally {
    actionId.value = null
  }
}

function handleConfirm(order: OrderVO): void {
  runAction(order, async () => { await confirmMeetupOrder(order.id) }, '交易已完成，感谢使用校园二手')
}

async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning(`复制失败，请手动记录：${text}`)
  }
}

watch(role, handleRoleChange)

onMounted(loadOrders)
</script>

<template>
  <div class="page orders">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的面交单</h2>
        <p class="page-subtitle">面交时当面验货，核销码确认后再完成交易</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadOrders">刷新</el-button>
    </div>

    <el-tabs v-model="role" class="orders__tabs">
      <el-tab-pane label="我买到的" name="buyer" />
      <el-tab-pane label="我卖出的" name="seller" />
    </el-tabs>

    <div v-loading="loading" class="orders__body">
      <el-card v-for="order in orders" :key="order.id" class="section-card" shadow="never">
        <div class="orders__head">
          <span class="orders__no">面交单号：{{ order.orderNo }}</span>
          <el-tag :type="orderStatusTagType(order.statusText)" effect="dark" size="small">
            {{ order.statusText }}
          </el-tag>
        </div>

        <div class="orders__main">
          <el-image :src="order.itemCover" fit="cover" class="orders__cover" @click="router.push(`/item/${order.itemId}`)" />
          <div class="orders__info">
            <p class="orders__title" @click="router.push(`/item/${order.itemId}`)">{{ order.itemTitle }}</p>
            <p class="text-muted">
              成交价 ¥{{ formatPrice(order.dealPrice) }} × {{ order.quantity }}
              <template v-if="role === 'buyer'"> · 卖家：{{ order.sellerName }}</template>
              <template v-else> · 买家：{{ order.buyerName }}</template>
            </p>
            <p class="orders__point">
              <el-icon><Location /></el-icon>
              {{ order.pointName || '待协商点位' }}
              <span v-if="order.pointGuide" class="text-muted">（{{ order.pointGuide }}）</span>
            </p>
            <p class="text-muted">
              时间窗：{{ formatDateTime(order.timeSlotStart) }} ~ {{ formatDateTime(order.timeSlotEnd) }}
            </p>
            <p v-if="order.remark" class="text-muted">留言：{{ order.remark }}</p>
          </div>
          <div class="orders__code">
            <template v-if="order.verifyCode && !isDone(order) && !isClosed(order)">
              <p class="text-muted">核销码</p>
              <p class="orders__code-value">{{ order.verifyCode }}</p>
              <el-button size="small" text type="primary" @click="copyText(order.verifyCode)">复制</el-button>
            </template>
          </div>
        </div>

        <div class="orders__actions">
          <el-button size="small" @click="router.push(`/item/${order.itemId}`)">查看商品</el-button>

          <template v-if="role === 'seller' && isPendingSeller(order)">
            <el-button size="small" type="primary" :loading="actionId === order.id" @click="handleAccept(order)">
              接受
            </el-button>
            <el-button size="small" type="danger" plain :loading="actionId === order.id" @click="handleReject(order)">
              拒绝
            </el-button>
          </template>

          <template v-if="role === 'buyer' && (isPendingSeller(order) || isWaiting(order))">
            <el-button size="small" plain :loading="actionId === order.id" @click="handleCancel(order)">
              取消面交单
            </el-button>
          </template>

          <template v-if="isWaiting(order) || isVerified(order)">
            <el-button
              v-if="order.verifyCode"
              size="small"
              type="success"
              :icon="Tickets"
              @click="openCodeDialog(order)"
            >
              出示核销码
            </el-button>
            <el-button size="small" type="primary" plain @click="openVerifyDialog(order)">输入核销码核销</el-button>
          </template>

          <el-button
            v-if="canConfirm(order)"
            size="small"
            type="success"
            :loading="actionId === order.id"
            @click="handleConfirm(order)"
          >
            确认完成
          </el-button>
        </div>
      </el-card>

      <el-empty v-if="!orders.length && !loading" class="empty-block" :description="`还没有${currentRoleText}的面交单`">
        <el-button type="primary" @click="router.push('/')">去逛逛</el-button>
      </el-empty>
    </div>

    <div v-if="total > 0" class="orders__pager">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="pageSize"
        :current-page="pageNum"
        @current-change="handlePageChange"
      />
    </div>

    <el-dialog v-model="verifyVisible" title="输入核销码" width="420px">
      <p class="text-muted">请与同学当面核对商品后，输入对方出示的 6 位核销码。</p>
      <el-input v-model="verifyCodeInput" maxlength="8" placeholder="请输入核销码" size="large" />
      <template #footer>
        <el-button @click="verifyVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionId === currentOrder?.id" @click="submitVerify">确认核销</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="codeVisible" title="我的核销码" width="420px">
      <div class="orders__code-dialog">
        <p class="text-muted">把这串数字出示给同学，核销后即可完成交易</p>
        <p class="orders__code-big">{{ codeOrder?.verifyCode || '——' }}</p>
        <el-tag v-if="codeOrder?.pointName" type="success" effect="plain">{{ codeOrder.pointName }}</el-tag>
      </div>
      <template #footer>
        <el-button type="primary" @click="codeVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.orders {
  &__tabs {
    margin-bottom: 8px;
  }

  &__body {
    min-height: 200px;
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-bottom: 10px;
    border-bottom: 1px dashed #ebeef5;
  }

  &__no {
    color: var(--ershou-text-light);
    font-size: 13px;
  }

  &__main {
    display: flex;
    gap: 14px;
    padding: 14px 0;
  }

  &__cover {
    width: 92px;
    height: 92px;
    border-radius: 8px;
    cursor: pointer;
  }

  &__info {
    flex: 1;
    min-width: 0;

    p {
      margin: 0 0 6px;
      font-size: 13px;
    }
  }

  &__title {
    font-size: 16px !important;
    font-weight: 600;
    cursor: pointer;

    &:hover {
      color: var(--ershou-primary);
    }
  }

  &__point {
    display: flex;
    align-items: center;
    gap: 4px;
    color: #529b2e;
  }

  &__code {
    width: 120px;
    text-align: center;
  }

  &__code-value {
    margin: 4px 0;
    color: #f56c6c;
    font-size: 20px;
    font-weight: 700;
    letter-spacing: 2px;
  }

  &__code-dialog {
    text-align: center;
  }

  &__code-big {
    margin: 12px 0;
    color: #f56c6c;
    font-size: 34px;
    font-weight: 700;
    letter-spacing: 6px;
  }

  &__actions {
    display: flex;
    gap: 8px;
    padding-top: 10px;
    border-top: 1px dashed #ebeef5;
    flex-wrap: wrap;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 12px;
  }
}
</style>
