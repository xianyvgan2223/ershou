<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { fetchConsoleOrders } from '@/api/platform-console'
import type { ConsoleAdminOrderVO } from '@/api/platform-console'
import { formatDateTime, formatPrice, orderStatusLabel, orderStatusTagTypeByCode } from '@/utils/format'

/**
 * 平台运营台 · 平台订单（第 3 轮，设计 4.4 Orders）。
 *
 * 只展示 `meetup_order.seller_id = 平台官方账号(1000)` 的订单，走**服务端分页**。
 * 状态 tabs：全部 / 待自提（status=2）/ 已完成（3）/ 已取消（4）。
 * 行点击打开抽屉看详情；本轮**不做**强制取消/退款（设计第一节非目标）。
 *
 * 口径：状态文案优先用后端 `statusText`（平台单里 2 是「待自提」而不是普通单的「待面交」），
 * 拿不到时回落 `utils/format` 的统一字典。
 * 订单接口不带 campusId（平台单是全局的），所以本页不读 Shell 的校 context。
 */

const PAGE_SIZES = [10, 20, 50]

/* ------------------------------------------------------------------ *
 * 列表（服务端分页）
 * ------------------------------------------------------------------ */
const loading = ref(false)
const rows = ref<ConsoleAdminOrderVO[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
/** 0 = 全部（不传 status），2 待自提 / 3 已完成 / 4 已取消 */
const statusTab = ref(0)
const keyword = ref('')
/** 已提交的搜索词（输入框回车/点查询才生效，避免边打字边请求） */
const activeKeyword = ref('')

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await fetchConsoleOrders({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      status: statusTab.value === 0 ? undefined : statusTab.value,
      keyword: activeKeyword.value.trim() || undefined,
    })
    rows.value = page.records ?? []
    total.value = page.total ?? 0
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search(): void {
  activeKeyword.value = keyword.value
  pageNum.value = 1
  void load()
}

function reset(): void {
  keyword.value = ''
  activeKeyword.value = ''
  statusTab.value = 0
  pageNum.value = 1
  void load()
}

function onTabChange(): void {
  pageNum.value = 1
  void load()
}

function onPageChange(page: number): void {
  pageNum.value = page
  void load()
}

function onSizeChange(size: number): void {
  pageSize.value = size
  pageNum.value = 1
  void load()
}

/* ------------------------------------------------------------------ *
 * 详情抽屉
 * ------------------------------------------------------------------ */
const drawerVisible = ref(false)
const current = ref<ConsoleAdminOrderVO | null>(null)

function openDetail(row: ConsoleAdminOrderVO): void {
  current.value = row
  drawerVisible.value = true
}

/* ------------------------------------------------------------------ *
 * 展示辅助
 * ------------------------------------------------------------------ */
function statusTextOf(row: ConsoleAdminOrderVO): string {
  return row.statusText || orderStatusLabel(row.status)
}

/** 金额 = 成交价 × 数量（平台单 dealPrice 是单价） */
function amountOf(row: ConsoleAdminOrderVO): number {
  return Number(row.dealPrice ?? 0) * Number(row.quantity ?? 1)
}

function buyerText(row: ConsoleAdminOrderVO): string {
  if (row.buyerNickname) return row.buyerNickname
  return row.buyerId ? `用户 #${row.buyerId}` : '—'
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="console-page">
    <!-- 工具条 -->
    <div class="cp-bar">
      <div class="cp-bar__title">平台订单</div>
      <span class="cp-bar__count">共 {{ total }} 单</span>
      <div class="cp-bar__spacer" />
      <el-input
        v-model="keyword"
        placeholder="搜索订单号 / 商品标题"
        clearable
        :prefix-icon="Search"
        style="width: 260px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button v-if="activeKeyword || statusTab !== 0" text @click="reset">重置</el-button>
    </div>

    <!-- 表格 -->
    <div class="cp-card">
      <div class="cp-card__head">
        <el-radio-group v-model="statusTab" @change="onTabChange">
          <el-radio-button :value="0">全部</el-radio-button>
          <el-radio-button :value="2">待自提</el-radio-button>
          <el-radio-button :value="3">已完成</el-radio-button>
          <el-radio-button :value="4">已取消</el-radio-button>
        </el-radio-group>
        <span class="cp-sub">点击任意行查看订单详情</span>
      </div>

      <el-skeleton v-if="loading && !rows.length" :rows="8" animated class="cp-skeleton" />

      <el-table
        v-else
        :data="rows"
        row-key="id"
        class="cp-table"
        @row-click="openDetail"
      >
        <el-table-column label="订单号" min-width="190">
          <template #default="{ row }">
            <p class="cp-order-no">{{ row.orderNo }}</p>
            <p class="cp-sub">#{{ row.id }}</p>
          </template>
        </el-table-column>
        <el-table-column label="商品" min-width="220">
          <template #default="{ row }">
            <div class="cp-goods">
              <el-image :src="row.itemCover || ''" fit="cover" class="cp-goods__img" lazy>
                <template #error>
                  <div class="cp-goods__placeholder">无图</div>
                </template>
              </el-image>
              <div class="cp-goods__main">
                <p class="cp-name">{{ row.itemTitle || `商品 #${row.itemId}` }}</p>
                <p class="cp-sub">商品 #{{ row.itemId }}</p>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="买家" width="110">
          <template #default="{ row }">{{ buyerText(row) }}</template>
        </el-table-column>
        <el-table-column label="数量" width="72" align="right">
          <template #default="{ row }">×{{ row.quantity ?? 1 }}</template>
        </el-table-column>
        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">
            <span class="cp-money">{{ formatPrice(amountOf(row)) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="自提点" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.pointName || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="94" align="center">
          <template #default="{ row }">
            <el-tag :type="orderStatusTagTypeByCode(row.status)" effect="light" round size="small">
              {{ statusTextOf(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="146">
          <template #default="{ row }">
            <span class="cp-time">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="cp-empty">
            <div class="cp-empty__icon">📦</div>
            <p>
              {{ activeKeyword || statusTab !== 0 ? '没有符合条件的平台订单' : '还没有平台订单：平台商品成交后会出现在这里' }}
            </p>
          </div>
        </template>
      </el-table>

      <div v-if="total" class="cp-card__foot">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="PAGE_SIZES"
          :total="total"
          :current-page="pageNum"
          :page-size="pageSize"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </div>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawerVisible" title="平台订单详情" size="480px" append-to-body>
      <template v-if="current">
        <div class="cp-detail__goods">
          <el-image :src="current.itemCover || ''" fit="cover" class="cp-detail__cover">
            <template #error>
              <div class="cp-goods__placeholder">无图</div>
            </template>
          </el-image>
          <div>
            <p class="cp-detail__title">{{ current.itemTitle || `商品 #${current.itemId}` }}</p>
            <p class="cp-sub">商品 #{{ current.itemId }}</p>
          </div>
        </div>

        <el-descriptions :column="1" border class="cp-detail__desc">
          <el-descriptions-item label="订单号">{{ current.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="订单 ID">#{{ current.id }}</el-descriptions-item>
          <el-descriptions-item label="买家">{{ buyerText(current) }}</el-descriptions-item>
          <el-descriptions-item label="数量">×{{ current.quantity ?? 1 }}</el-descriptions-item>
          <el-descriptions-item label="成交单价">¥{{ formatPrice(current.dealPrice) }}</el-descriptions-item>
          <el-descriptions-item label="订单金额">
            <span class="cp-money">{{ formatPrice(amountOf(current)) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="自提点">{{ current.pointName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="orderStatusTagTypeByCode(current.status)" effect="light" round size="small">
              {{ statusTextOf(current) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(current.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="核销时间">
            {{ current.verifiedAt ? formatDateTime(current.verifiedAt) : '未核销' }}
          </el-descriptions-item>
        </el-descriptions>

        <p class="cp-sub cp-detail__tip">
          本轮运营台只提供订单查询；强制取消 / 退款需要回收库存，列为下一轮能力。
        </p>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
$radius-lg: 16px;
$card-shadow: 0 1px 2px rgb(15 23 42 / 4%), 0 12px 32px rgb(15 23 42 / 6%);

.console-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.cp-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 16px 22px;
  border-radius: $radius-lg;
  background: #fff;
  box-shadow: $card-shadow;

  &__title {
    margin-right: 4px;
    color: var(--text-1);
    font-size: 19px;
    font-weight: 700;
    letter-spacing: -0.2px;
  }

  &__count {
    color: var(--text-3);
    font-size: 13px;
  }

  &__spacer {
    flex: 1;
  }
}

.cp-card {
  overflow: hidden;
  border-radius: $radius-lg;
  background: #fff;
  box-shadow: $card-shadow;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 14px 22px;
    border-bottom: 1px solid var(--line);
  }

  &__foot {
    display: flex;
    justify-content: flex-end;
    padding: 14px 22px;
    border-top: 1px solid var(--line);
  }
}

.cp-skeleton {
  padding: 22px;
}

.cp-table {
  width: 100%;

  :deep(.el-table) {
    --el-table-border-color: var(--line);
    --el-table-header-bg-color: #f8fafc;
    --el-table-row-hover-bg-color: #f8fafc;
    color: var(--text-2);
    font-size: 13.5px;
  }

  :deep(th.el-table__cell) {
    height: 48px;
    padding: 8px 0;
    background: #f8fafc;
    color: var(--text-2);
    font-weight: 600;
  }

  :deep(td.el-table__cell) {
    height: 56px;
    padding: 8px 0;
  }

  :deep(.el-table__row) {
    cursor: pointer;
  }

  :deep(.cell) {
    line-height: 1.5;
  }
}

.cp-order-no {
  color: var(--text-1);
  font-size: 13.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.2px;
}

.cp-name {
  overflow: hidden;
  color: var(--text-1);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cp-sub {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}

.cp-money {
  color: var(--accent-600);
  font-weight: 700;
  font-variant-numeric: tabular-nums;

  &::before {
    content: '¥';
    margin-right: 1px;
    font-size: 0.86em;
    font-weight: 500;
    opacity: 0.75;
  }
}

.cp-time {
  color: var(--text-2);
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
}

.cp-goods {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;

  &__img {
    width: 44px;
    height: 44px;
    flex-shrink: 0;
    border-radius: 8px;
    background: var(--surface-2);
  }

  &__placeholder {
    display: grid;
    width: 44px;
    height: 44px;
    place-items: center;
    border-radius: 8px;
    background: var(--surface-2);
    color: var(--text-3);
    font-size: 11px;
  }

  &__main {
    min-width: 0;
  }
}

// 抽屉
.cp-detail {
  &__goods {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 16px;
    border-radius: 14px;
    background: var(--surface-2);
  }

  &__cover {
    width: 72px;
    height: 72px;
    flex-shrink: 0;
    border-radius: 10px;
    background: #fff;
  }

  &__title {
    color: var(--text-1);
    font-size: 15px;
    font-weight: 700;
    line-height: 1.4;
  }

  &__desc {
    margin-top: 18px;
  }

  &__tip {
    margin-top: 16px;
  }
}

.cp-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 56px 0;
  color: var(--text-3);
  font-size: 13px;

  &__icon {
    display: grid;
    width: 56px;
    height: 56px;
    place-items: center;
    border-radius: 50%;
    background: var(--surface-2);
    font-size: 24px;
  }
}
</style>
