<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { fetchAdminOrders } from '@/api/admin'
import type { AdminOrder } from '@/api/admin'
import { formatDateTime, formatPrice, formatRelativeTime, ORDER_STATUS_OPTIONS, orderStatusTagTypeByCode } from '@/utils/format'
import { useQuerySync } from '@/composables/useQuerySync'

/**
 * 面交单管理。
 *
 * 接口 `GET /admin/orders` 和后端 `AdminOrderService` 一直都有，但网页端从来没建过这个页面，
 * 运营遇到「买家说卖家没来」「这单到底成没成」只能直接查库。这里把它补上：
 * 支持按状态 / 单号与商品标题 / 买家 ID / 卖家 ID 检索，行内展开成交时间线。
 */

const PAGE_SIZES = [10, 20, 50]

const statusFilter = ref<number | ''>('')
const keyword = ref('')
const buyerId = ref('')
const sellerId = ref('')
const pageNum = ref(1)
const pageSize = ref(20)

const loading = ref(false)
const list = ref<AdminOrder[]>([])
const total = ref(0)

const detail = reactive({ visible: false, order: null as AdminOrder | null })

const hasFilter = computed(
  () => statusFilter.value !== '' || !!keyword.value.trim() || !!buyerId.value.trim() || !!sellerId.value.trim(),
)

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await fetchAdminOrders({
      status: statusFilter.value === '' ? undefined : statusFilter.value,
      keyword: keyword.value.trim() || undefined,
      buyerId: toId(buyerId.value),
      sellerId: toId(sellerId.value),
      pageNum: pageNum.value,
      pageSize: pageSize.value,
    })
    list.value = res.records ?? []
    total.value = res.total ?? 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** ID 输入框只接受正整数，其它一律当作「不筛」 */
function toId(raw: string): number | undefined {
  const trimmed = raw.trim()
  if (!trimmed) return undefined
  const num = Number(trimmed)
  return Number.isInteger(num) && num > 0 ? num : undefined
}

function search(): void {
  pageNum.value = 1
  load()
}

function reset(): void {
  statusFilter.value = ''
  keyword.value = ''
  buyerId.value = ''
  sellerId.value = ''
  pageNum.value = 1
  load()
}

function openDetail(order: AdminOrder): void {
  detail.order = order
  detail.visible = true
}

/** 成交时间线：把几个时间戳字段拼成一条按时间排序的轨迹 */
const timeline = computed(() => {
  const order = detail.order
  if (!order) return []
  const events: { time: string; label: string; type: 'primary' | 'success' | 'danger' | 'info' }[] = []
  if (order.createdAt) events.push({ time: order.createdAt, label: '买家下单', type: 'primary' })
  if (order.verifiedAt) events.push({ time: order.verifiedAt, label: '当面核销', type: 'success' })
  if (order.sellerConfirmAt) events.push({ time: order.sellerConfirmAt, label: '卖家确认', type: 'success' })
  if (order.buyerConfirmAt) events.push({ time: order.buyerConfirmAt, label: '买家确认', type: 'success' })
  events.sort((a, b) => a.time.localeCompare(b.time))
  if (order.cancelReason) {
    events.push({ time: order.createdAt ?? '', label: '取消原因：' + order.cancelReason, type: 'danger' })
  }
  return events
})

function timeSlotText(order: AdminOrder): string {
  if (!order.timeSlotStart) return '待协商'
  const start = formatDateTime(order.timeSlotStart)
  const end = order.timeSlotEnd ? formatDateTime(order.timeSlotEnd) : ''
  return end ? `${start} ~ ${end}` : start
}

useQuerySync(
  { statusFilter, keyword, buyerId, sellerId, pageNum, pageSize },
  {
    statusFilter: '' as number | '',
    keyword: '',
    buyerId: '',
    sellerId: '',
    pageNum: 1,
    pageSize: 20,
  },
  {
    onChange: load,
    // 概览页卡片用的是 ?status=1 这种短参数，别名保证深链能对上
    aliases: { statusFilter: 'status', pageNum: 'page', pageSize: 'size' },
    // statusFilter 的默认值是空串（联合类型），必须显式声明成数字
    numeric: ['statusFilter'],
  },
)

onMounted(load)
</script>

<template>
  <div class="admin-page admin-orders">
    <div class="panel">
      <div class="panel-title">面交单管理</div>
      <p class="text-muted admin-orders__intro">
        全部面交单的成交明细与时间线。遇到「买卖双方说法不一致」先在这里按单号或用户 ID 定位，
        再用「操作日志」对照后台有没有介入过。
      </p>

      <div class="admin-orders__toolbar">
        <el-select
          v-model="statusFilter"
          placeholder="全部状态"
          clearable
          style="width: 150px"
          @change="search"
        >
          <el-option v-for="opt in ORDER_STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="单号 / 商品标题"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-input
          v-model="buyerId"
          placeholder="买家 ID"
          clearable
          style="width: 120px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-input
          v-model="sellerId"
          placeholder="卖家 ID"
          clearable
          style="width: 120px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button v-if="hasFilter" text @click="reset">重置</el-button>
        <span class="text-muted admin-orders__count">共 {{ total }} 单</span>
      </div>

      <el-table
        v-loading="loading"
        :data="list"
        row-key="id"
        max-height="560"
        class="admin-orders__table admin-table"
      >
        <el-table-column label="单号 / 下单时间" width="180">
          <template #default="{ row }">
            <p class="admin-orders__no">{{ row.orderNo }}</p>
            <p class="text-muted admin-orders__sub" :title="formatDateTime(row.createdAt)">
              {{ formatRelativeTime(row.createdAt) }}
            </p>
          </template>
        </el-table-column>

        <el-table-column label="商品" min-width="230">
          <template #default="{ row }">
            <div class="admin-orders__item">
              <el-image :src="row.itemCover" fit="cover" class="admin-orders__cover">
                <template #error>
                  <div class="admin-orders__cover-empty">无图</div>
                </template>
              </el-image>
              <div class="admin-orders__item-info">
                <a
                  class="admin-orders__title"
                  :title="row.itemTitle"
                  :href="'/item/' + row.itemId"
                  target="_blank"
                  rel="noopener"
                >
                  {{ row.itemTitle }}
                </a>
                <p class="text-muted admin-orders__sub">
                  ×{{ row.quantity ?? 1 }} · ¥{{ formatPrice(row.dealPrice) }}
                </p>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="买卖双方" min-width="150">
          <template #default="{ row }">
            <p class="admin-orders__sub">
              买 <span class="admin-orders__strong">{{ row.buyerNickname || '—' }}</span>
              <span class="text-muted">#{{ row.buyerId }}</span>
            </p>
            <p class="admin-orders__sub">
              卖 <span class="admin-orders__strong">{{ row.sellerNickname || '—' }}</span>
              <span class="text-muted">#{{ row.sellerId }}</span>
            </p>
          </template>
        </el-table-column>

        <el-table-column label="面交点 / 时间窗" min-width="200">
          <template #default="{ row }">
            <p class="admin-orders__sub">{{ row.pointName || '未指定' }}</p>
            <p class="text-muted admin-orders__sub">{{ timeSlotText(row) }}</p>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="104" align="center">
          <template #default="{ row }">
            <el-tag :type="orderStatusTagTypeByCode(row.status)" effect="plain" class="admin-orders__tag">
              {{ row.statusText }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="92" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :description="hasFilter ? '没有符合条件的面交单' : '还没有任何面交单'" />
        </template>
      </el-table>

      <div v-if="total > 0" class="admin-orders__pager">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          background
          layout="prev, pager, next, sizes, total"
          :total="total"
          :page-sizes="PAGE_SIZES"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </div>

    <el-drawer v-model="detail.visible" title="面交单详情" size="480px">
      <template v-if="detail.order">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="单号">{{ detail.order.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="orderStatusTagTypeByCode(detail.order.status)" effect="plain">
              {{ detail.order.statusText }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.order.batchNo" label="合并行程号">
            {{ detail.order.batchNo }}
          </el-descriptions-item>
          <el-descriptions-item label="商品">
            {{ detail.order.itemTitle }}（#{{ detail.order.itemId }}）
          </el-descriptions-item>
          <el-descriptions-item label="成交价">
            ¥{{ formatPrice(detail.order.dealPrice) }} × {{ detail.order.quantity ?? 1 }}
          </el-descriptions-item>
          <el-descriptions-item label="买家">
            {{ detail.order.buyerNickname || '—' }}（#{{ detail.order.buyerId }}）
          </el-descriptions-item>
          <el-descriptions-item label="卖家">
            {{ detail.order.sellerNickname || '—' }}（#{{ detail.order.sellerId }}）
          </el-descriptions-item>
          <el-descriptions-item label="面交点">
            {{ detail.order.pointName || '未指定' }}
          </el-descriptions-item>
          <el-descriptions-item label="时间窗">
            {{ timeSlotText(detail.order) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.order.cancelReason" label="取消原因">
            {{ detail.order.cancelReason }}
          </el-descriptions-item>
        </el-descriptions>

        <p class="admin-orders__drawer-title">成交时间线</p>
        <el-timeline v-if="timeline.length">
          <el-timeline-item
            v-for="(event, index) in timeline"
            :key="index"
            :type="event.type"
            :timestamp="formatDateTime(event.time)"
          >
            {{ event.label }}
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="没有可展示的时间点" :image-size="60" />
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.admin-orders {
  &__intro {
    margin-bottom: 12px;
    font-size: 13px;
  }

  &__toolbar {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 14px;
    flex-wrap: wrap;
  }

  &__count {
    margin-left: auto;
    font-size: 13px;
  }

  &__table {
    width: 100%;
  }

  &__no {
    font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
    font-size: 12px;
    font-weight: 600;
  }

  &__sub {
    font-size: 12px;
    line-height: 1.5;
  }

  &__strong {
    font-weight: 600;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__cover {
    width: 40px;
    height: 40px;
    border-radius: 8px;
    flex-shrink: 0;
  }

  &__cover-empty {
    display: grid;
    width: 100%;
    height: 100%;
    place-items: center;
    background: var(--surface-2);
    color: var(--text-3);
    font-size: 10px;
  }

  &__item-info {
    min-width: 0;

    p {
      margin: 0;
      font-size: 12px;
    }
  }

  &__title {
    display: block;
    overflow: hidden;
    font-size: 13px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;

    &:hover {
      color: var(--brand-600);
    }
  }

  &__tag {
    white-space: nowrap;
  }

  &__pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 14px;
  }

  &__drawer-title {
    margin: 20px 0 12px;
    font-size: 14px;
    font-weight: 600;
  }
}
</style>
