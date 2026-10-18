<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  Coin,
  Goods,
  List,
  Money,
  Shop,
  Ticket,
  Timer,
  TrendCharts,
} from '@element-plus/icons-vue'
import {
  fetchConsoleOrders,
  fetchConsoleOverview,
} from '@/api/platform-console'
import type { ConsoleAdminOrderVO, ConsoleOverviewVO } from '@/api/platform-console'
import { DEFAULT_CAMPUS_ID } from '@/utils/constants'
import {
  formatDateTime,
  formatPrice,
  orderStatusLabel,
  orderStatusTagTypeByCode,
} from '@/utils/format'

/**
 * 运营台 · 运营概览（第 3 轮设计 4.4 Overview）。
 *
 * 首屏 6 个 KPI 大卡 → 第二行 2:1（最近平台订单 / 库存告警+倒计时+快捷操作）
 * → 底部 2 列（券领取进度 / 秒杀售卖进度）。
 * 数据只用两个接口：`/overview`（聚合统计，已含 GMV/库存/场次）与 `/orders`（最近 5 单）。
 */

const router = useRouter()
/** Shell 通过 provide 下发当前校区；切校区时本页 watch 重新拉数据 */
const campusId = inject<Ref<number>>('campusId', ref(DEFAULT_CAMPUS_ID))

const loading = ref(false)
const overview = ref<ConsoleOverviewVO | null>(null)
const recentOrders = ref<ConsoleAdminOrderVO[]>([])

/** 秒杀倒计时：先把"距结束小时数"换算成绝对截止时间，之后本地每秒递减（不重复请求） */
const flashDeadline = ref<number | null>(null)
const now = ref(Date.now())
let tickTimer: number | undefined

async function load(): Promise<void> {
  loading.value = true
  try {
    const [stats, orders] = await Promise.all([
      fetchConsoleOverview(campusId.value),
      fetchConsoleOrders({ pageNum: 1, pageSize: 5 }),
    ])
    overview.value = stats
    recentOrders.value = orders.records ?? []
    flashDeadline.value =
      stats.expiringFlashHours === null || stats.expiringFlashHours === undefined
        ? null
        : Date.now() + stats.expiringFlashHours * 3_600_000
  } catch {
    // 拦截器已统一提示；保留上一次数据不清空，避免闪屏
  } finally {
    loading.value = false
  }
}

watch(campusId, load)

onMounted(() => {
  void load()
  tickTimer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  if (tickTimer !== undefined) window.clearInterval(tickTimer)
})

/* ------------------------------------------------------------------ *
 * KPI 大卡（首屏 6 个，验收硬指标 ≥4）
 * ------------------------------------------------------------------ */

const kpis = computed(() => {
  const data = overview.value
  return [
    {
      label: '在售平台商品',
      value: data ? String(data.platformItemOnSale) : '--',
      sub: data ? `共 ${data.platformItemTotal} 件平台商品` : '加载中',
      icon: Goods,
      key: false,
      prefix: '',
    },
    {
      label: '今日平台订单',
      value: data ? String(data.orderToday) : '--',
      sub: data ? `累计 ${data.orderTotal} 单` : '加载中',
      icon: List,
      key: false,
      prefix: '',
    },
    {
      label: '今日 GMV',
      value: data ? formatPrice(data.gmvToday) : '--',
      sub: data ? `累计 ¥${formatPrice(data.gmvTotal)}` : '加载中',
      icon: Money,
      key: true,
      prefix: '¥',
    },
    {
      label: '待自提',
      value: data ? String(data.pendingPickup) : '--',
      sub: data ? `待确认 ${data.waitingConfirm} 单` : '加载中',
      icon: Shop,
      key: false,
      prefix: '',
    },
    {
      label: '券已领取',
      value: data ? String(data.couponClaimed) : '--',
      sub: data ? `总库存 ${data.couponTotalStock} 张` : '加载中',
      icon: Ticket,
      key: false,
      prefix: '',
    },
    {
      label: '秒杀剩余库存',
      value: data ? String(data.flashStockLeft) : '--',
      sub: data ? `已售 ${data.flashSold} / 总 ${data.flashStockTotal}` : '加载中',
      icon: Timer,
      key: false,
      prefix: '',
    },
  ]
})

/** 券领取进度（0~100）：总库存为 0 时按 0 处理，避免 NaN 宽度 */
const couponPercent = computed(() => {
  const data = overview.value
  if (!data || data.couponTotalStock <= 0) return 0
  return Math.min(100, Math.round((data.couponClaimed / data.couponTotalStock) * 100))
})

/** 秒杀售卖进度（0~100） */
const flashSoldPercent = computed(() => {
  const data = overview.value
  if (!data || data.flashStockTotal <= 0) return 0
  return Math.min(100, Math.round((data.flashSold / data.flashStockTotal) * 100))
})

/** 场次倒计时文案：无进行中场次 / 已结束 / Xh Ym Zs */
const flashCountdown = computed(() => {
  if (!overview.value || overview.value.flashSceneId === null) return '当前无进行中的秒杀场次'
  const deadline = flashDeadline.value
  if (deadline === null) return '场次已结束'
  const remain = deadline - now.value
  if (remain <= 0) return '本场已结束'
  const totalSeconds = Math.floor(remain / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
})

const flashSceneTitle = computed(() => overview.value?.flashSceneTitle ?? '限时秒杀')

/** 快捷操作：发布商品带 ?publish=1 让商品页自动打开弹窗 */
const quickActions = [
  { label: '发布平台商品', desc: '新商品上架', icon: Goods, path: '/console/platform/items?publish=1' },
  { label: '维护代卖点', desc: '自提点信息', icon: Shop, path: '/console/platform/points' },
  { label: '新建优惠券', desc: '券模板', icon: Ticket, path: '/console/platform/coupons' },
  { label: '重建缓存', desc: 'Redis 预热', icon: Coin, path: '/console/platform/cache' },
]

function goOrders(): void {
  void router.push('/console/platform/orders')
}

function goItemsWithLowStock(): void {
  void router.push({ path: '/console/platform/items', query: { status: '2' } })
}

function goPath(path: string): void {
  void router.push(path)
}
</script>

<template>
  <div class="ov">
    <!-- ================= 首屏 6 个 KPI 大卡 ================= -->
    <el-skeleton v-if="loading && !overview" :rows="2" animated class="ov__skeleton" />
    <section v-else class="ov__kpis">
      <article v-for="kpi in kpis" :key="kpi.label" class="kpi" :class="{ 'is-key': kpi.key }">
        <span class="kpi__icon"><el-icon><component :is="kpi.icon" /></el-icon></span>
        <p class="kpi__label">{{ kpi.label }}</p>
        <p class="kpi__value">
          <span v-if="kpi.prefix" class="kpi__prefix">{{ kpi.prefix }}</span>{{ kpi.value }}
        </p>
        <p class="kpi__trend">{{ kpi.sub }}</p>
      </article>
    </section>

    <!-- ================= 第二行：2:1 ================= -->
    <section class="ov__row">
      <div class="card ov__orders">
        <div class="card__head">
          <div>
            <h3 class="card__title">最近平台订单</h3>
            <p class="card__desc">平台代卖自提单，点行进入订单页</p>
          </div>
          <el-button text type="primary" @click="goOrders">
            全部订单<el-icon class="ov__arrow"><ArrowRight /></el-icon>
          </el-button>
        </div>
        <el-table
          :data="recentOrders"
          row-key="id"
          class="ov__table pc-table"
          @row-click="goOrders"
        >
          <el-table-column label="订单号" min-width="140">
            <template #default="{ row }">
              <span class="ov__order-no">{{ row.orderNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="商品" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.itemTitle || '—' }}</template>
          </el-table-column>
          <el-table-column label="买家" width="90" show-overflow-tooltip>
            <template #default="{ row }">{{ row.buyerNickname || '—' }}</template>
          </el-table-column>
          <el-table-column label="金额" width="90" align="right">
            <template #default="{ row }">
              <span class="ov__money">¥{{ formatPrice(row.dealPrice) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="88" align="center">
            <template #default="{ row }">
              <el-tag :type="orderStatusTagTypeByCode(row.status)" effect="plain" size="small">
                {{ row.statusText || orderStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="下单时间" width="120">
            <template #default="{ row }">
              <span class="ov__time">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="还没有平台订单" :image-size="72" />
          </template>
        </el-table>
      </div>

      <div class="ov__side">
        <div class="card ov__panel">
          <div class="card__head">
            <h3 class="card__title">运营提醒</h3>
          </div>

          <div class="ov__alert" :class="{ 'is-warn': (overview?.lowStockItems ?? 0) > 0 }">
            <span class="ov__alert-icon"><el-icon><TrendCharts /></el-icon></span>
            <div class="ov__alert-body">
              <p class="ov__alert-label">库存告警</p>
              <p class="ov__alert-text">
                {{
                  (overview?.lowStockItems ?? 0) > 0
                    ? `${overview?.lowStockItems} 件在售商品库存 ≤ 2，建议补货`
                    : '在售商品库存充足'
                }}
              </p>
            </div>
            <el-button text type="primary" size="small" @click="goItemsWithLowStock">去补库存</el-button>
          </div>

          <div class="ov__flash">
            <div class="ov__flash-head">
              <p class="ov__alert-label">{{ flashSceneTitle }}</p>
              <el-tag size="small" effect="plain" type="warning">倒计时</el-tag>
            </div>
            <p class="ov__countdown">{{ flashCountdown }}</p>
          </div>

          <p class="ov__quick-title">快捷操作</p>
          <div class="ov__quick">
            <button
              v-for="action in quickActions"
              :key="action.label"
              class="ov__quick-item"
              @click="goPath(action.path)"
            >
              <el-icon class="ov__quick-icon"><component :is="action.icon" /></el-icon>
              <span class="ov__quick-label">{{ action.label }}</span>
              <span class="ov__quick-desc">{{ action.desc }}</span>
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- ================= 底部：券 / 秒杀 进度 ================= -->
    <section class="ov__row ov__row--progress">
      <div class="card">
        <div class="card__head">
          <div>
            <h3 class="card__title">券领取进度</h3>
            <p class="card__desc">已领 / 总库存</p>
          </div>
          <span class="ov__progress-num">
            {{ overview?.couponClaimed ?? 0 }} / {{ overview?.couponTotalStock ?? 0 }}
          </span>
        </div>
        <el-progress
          :percentage="couponPercent"
          :stroke-width="12"
          :show-text="false"
          color="var(--brand-500)"
        />
        <p class="ov__progress-hint">
          剩余可领 {{ Math.max(0, (overview?.couponTotalStock ?? 0) - (overview?.couponClaimed ?? 0)) }} 张
        </p>
      </div>

      <div class="card">
        <div class="card__head">
          <div>
            <h3 class="card__title">秒杀售卖进度</h3>
            <p class="card__desc">已售 / 总库存</p>
          </div>
          <span class="ov__progress-num">
            {{ overview?.flashSold ?? 0 }} / {{ overview?.flashStockTotal ?? 0 }}
          </span>
        </div>
        <el-progress
          :percentage="flashSoldPercent"
          :stroke-width="12"
          :show-text="false"
          color="var(--accent-500)"
        />
        <p class="ov__progress-hint">
          剩余秒杀库存 {{ overview?.flashStockLeft ?? 0 }} 件
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
// =============================================================
// 运营台 · 概览：KPI 大卡 + 2:1 双列 + 底部 2 列进度
// 视觉规范见第 3 轮设计第二节（卡片 16px 圆角 / KPI 数字 32px/700）
// =============================================================

.ov {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.ov__skeleton {
  padding: 24px;
  border-radius: 16px;
  background: #fff;
}

/* ---------------- KPI 大卡 ---------------- */
.ov__kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.kpi {
  position: relative;
  padding: 20px 22px;
  overflow: hidden;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%), 0 12px 32px rgb(15 23 42 / 6%);

  &.is-key::before {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
    width: 4px;
    background: linear-gradient(180deg, var(--brand-400), var(--brand-600));
    content: '';
  }
}

.kpi__icon {
  position: absolute;
  top: 18px;
  right: 18px;
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 50%;
  background: var(--surface-2);
  color: var(--text-3);
  font-size: 19px;
}

.kpi__label {
  color: var(--text-3);
  font-size: 13px;
}

.kpi__value {
  margin-top: 10px;
  color: var(--text-1);
  font-size: 32px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}

.kpi__prefix {
  margin-right: 2px;
  color: var(--text-3);
  font-size: 18px;
  font-weight: 600;
}

.kpi__trend {
  margin-top: 8px;
  color: var(--text-3);
  font-size: 12px;
}

/* ---------------- 通用卡片 ---------------- */
.card {
  padding: 20px 22px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%), 0 12px 32px rgb(15 23 42 / 6%);
}

.card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.card__title {
  color: var(--text-1);
  font-size: 15px;
  font-weight: 700;
}

.card__desc {
  margin-top: 3px;
  color: var(--text-3);
  font-size: 12px;
}

/* ---------------- 第二行 2:1 ---------------- */
.ov__row {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: 20px;
}

.ov__row--progress {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.ov__side {
  min-width: 0;
}

.ov__arrow {
  margin-left: 2px;
}

.ov__order-no {
  color: var(--text-1);
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.ov__money {
  color: var(--text-1);
  font-size: 13px;
  font-weight: 600;

  &::first-letter {
    color: var(--text-3);
  }
}

.ov__time {
  color: var(--text-3);
  font-size: 12px;
}

/* 通栏表格：行高 56px + 表头灰底（设计第二节） */
.ov__table {
  :deep(.el-table__header th.el-table__cell) {
    padding: 10px 0;
    background: #f8fafc;
    color: var(--text-3);
    font-size: 12px;
    font-weight: 500;
  }

  :deep(.el-table__body td.el-table__cell) {
    height: 56px;
    padding: 0;
    font-size: 13px;
  }

  :deep(.el-table__row) {
    cursor: pointer;
  }
}

/* ---------------- 运营提醒面板 ---------------- */
.ov__panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
}

.ov__alert {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--surface-2);

  &.is-warn {
    background: #fff8eb;

    .ov__alert-icon {
      background: #fef3c7;
      color: #b45309;
    }
  }
}

.ov__alert-icon {
  display: grid;
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 50%;
  background: var(--brand-50);
  color: var(--brand-600);
  font-size: 16px;
}

.ov__alert-body {
  flex: 1;
  min-width: 0;
}

.ov__alert-label {
  color: var(--text-1);
  font-size: 12.5px;
  font-weight: 600;
}

.ov__alert-text {
  margin-top: 2px;
  color: var(--text-2);
  font-size: 12px;
}

.ov__flash {
  padding: 12px 14px;
  border: 1px dashed var(--line-strong);
  border-radius: 12px;
}

.ov__flash-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ov__countdown {
  margin-top: 8px;
  color: var(--accent-600);
  font-size: 24px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
}

.ov__quick-title {
  color: var(--text-3);
  font-size: 12px;
}

.ov__quick {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.ov__quick-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;

  &:hover {
    border-color: var(--brand-300);
    box-shadow: var(--shadow-xs);
  }
}

.ov__quick-icon {
  color: var(--brand-600);
  font-size: 17px;
}

.ov__quick-label {
  color: var(--text-1);
  font-size: 12.5px;
  font-weight: 600;
}

.ov__quick-desc {
  color: var(--text-3);
  font-size: 11px;
}

/* ---------------- 底部进度 ---------------- */
.ov__progress-num {
  color: var(--text-2);
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.ov__progress-hint {
  margin-top: 10px;
  color: var(--text-3);
  font-size: 12px;
}

/* ---------------- 响应式 ---------------- */
@media (max-width: 1200px) {
  .ov__row {
    grid-template-columns: minmax(0, 1fr);
  }

  .ov__row--progress {
    grid-template-columns: minmax(0, 1fr);
  }

  .ov__quick {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .ov__kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ov__quick {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
