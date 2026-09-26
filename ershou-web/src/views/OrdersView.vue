<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Location, Refresh } from '@element-plus/icons-vue'
import {
  acceptMeetupOrder,
  cancelMeetupOrder,
  confirmMeetupOrder,
  fetchMeetupOrders,
  rejectMeetupOrder,
} from '@/api/order'
import type { OrderRole, OrderVO } from '@/api/order'
import { useMeetupStore } from '@/stores/meetup'
import { formatDateTime, formatPrice, orderStatusTagType } from '@/utils/format'

/** 面交单状态：1 待卖家确认 / 2 待面交 / 3 已完成 / 4 已取消 / 5 已失效 */
const STATUS_PENDING = 1
const STATUS_WAITING = 2

const router = useRouter()
const meetupStore = useMeetupStore()

const role = ref<OrderRole>('buyer')
const orders = ref<OrderVO[]>([])
const loading = ref(false)
const actionId = ref<number | null>(null)
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

/**
 * 状态判断一律用 status 码。
 * 原先按 statusText 关键字（"待卖家"/"核销"…）匹配，文案一改动作按钮就集体失效 ——
 * 去掉核销码后更是完全对不上，索性改成状态码。
 */
function isPending(order: OrderVO): boolean {
  return order.status === STATUS_PENDING
}

/** 卖家已接受：商品已锁定，等见面 */
function isWaiting(order: OrderVO): boolean {
  return order.status === STATUS_WAITING
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
    // 处理完立刻更新顶栏红点，不用等 30 秒轮询
    await meetupStore.loadTodo()
  } catch {
    // 响应拦截器已提示
  } finally {
    actionId.value = null
  }
}

function handleAccept(order: OrderVO): void {
  runAction(order, async () => { await acceptMeetupOrder(order.id) }, '已接受，商品已为你锁定')
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
  await runAction(order, async () => { await rejectMeetupOrder(order.id, reason.trim()) }, '已拒绝这次约面交')
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
  await runAction(order, async () => { await cancelMeetupOrder(order.id, reason.trim()) }, '已取消这次约面交')
}

/** 卖家：已卖出 = 成交（商品置已售出 + 双方信用分 +2） */
function handleSold(order: OrderVO): void {
  runAction(order, async () => { await confirmMeetupOrder(order.id) }, '交易已完成，双方信用分 +2')
}

/** 买家：我收到了 —— 只留痕，成交仍由卖家确认（买家能单方面下架卖家商品是不合理的） */
function handleReceived(order: OrderVO): void {
  runAction(order, async () => { await confirmMeetupOrder(order.id) }, '已记下「我收到了」，等卖家点「已卖出」')
}

watch(role, handleRoleChange)

onMounted(loadOrders)
</script>

<template>
  <div class="page orders">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的面交单</h2>
        <p class="page-subtitle">约面交只是意向，卖家确认后才锁定商品；见面验货后由卖家点「已卖出」完成交易</p>
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
          <span class="orders__tags">
            <el-tag v-if="role === 'seller' && isWaiting(order) && order.buyerConfirmed" type="success" effect="plain" size="small">
              买家已确认收到
            </el-tag>
            <el-tag :type="orderStatusTagType(order.statusText)" effect="dark" size="small">
              {{ order.statusText }}
            </el-tag>
          </span>
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
              {{ order.pointName || '地点待协商（私聊里定）' }}
              <span v-if="order.pointGuide" class="text-muted">（{{ order.pointGuide }}）</span>
            </p>
            <p class="text-muted">
              <template v-if="order.timeSlotStart && order.timeSlotEnd">
                时间窗：{{ formatDateTime(order.timeSlotStart) }} ~ {{ formatDateTime(order.timeSlotEnd) }}
              </template>
              <template v-else>时间待协商（私聊里定）</template>
            </p>
            <p v-if="order.remark" class="text-muted">留言：{{ order.remark }}</p>
          </div>
        </div>

        <div class="orders__actions">
          <el-button size="small" @click="router.push(`/item/${order.itemId}`)">查看商品</el-button>

          <!-- 买家：还没确认的意向可以取消 -->
          <el-button
            v-if="role === 'buyer' && (isPending(order) || isWaiting(order))"
            size="small"
            plain
            :loading="actionId === order.id"
            @click="handleCancel(order)"
          >
            取消
          </el-button>

          <!-- 买家：到点收到货，点一下留痕（成交仍由卖家确认） -->
          <el-button
            v-if="role === 'buyer' && isWaiting(order) && !order.buyerConfirmed"
            size="small"
            type="success"
            plain
            :loading="actionId === order.id"
            @click="handleReceived(order)"
          >
            我收到了
          </el-button>
          <span v-else-if="role === 'buyer' && isWaiting(order)" class="text-muted orders__hint">
            已确认收到，等卖家完成交易
          </span>

          <!-- 卖家：接受才锁商品；拒绝/已卖出 收尾 -->
          <template v-if="role === 'seller' && isPending(order)">
            <el-button size="small" type="primary" :loading="actionId === order.id" @click="handleAccept(order)">
              接受约面交
            </el-button>
            <el-button size="small" type="danger" plain :loading="actionId === order.id" @click="handleReject(order)">
              拒绝
            </el-button>
          </template>
          <el-button
            v-if="role === 'seller' && isWaiting(order)"
            size="small"
            type="success"
            :loading="actionId === order.id"
            @click="handleSold(order)"
          >
            已卖出（完成交易）
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

  &__tags {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 8px;
    padding-top: 10px;
    border-top: 1px dashed #ebeef5;
    flex-wrap: wrap;
  }

  &__hint {
    font-size: 12.5px;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 12px;
  }
}
</style>
