<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Coin, Lock, Refresh, Timer } from '@element-plus/icons-vue'
import { fetchConsoleCacheStats, refreshConsoleCache } from '@/api/platform-console'
import type { ConsoleCacheKeyVO, ConsoleCacheStatsVO } from '@/api/platform-console'
import { showTip } from '@/utils/feedback'

/**
 * 平台运营台 · 缓存运维（第 3 轮，设计 4.4 Cache）。
 *
 * 只读展示 `ershou:platform:*` 的 Redis 快照：key 总数 / 预热锁 / 最快到期 key，
 * 并支持「立即重建缓存」（POST /platform/admin/cache/refresh，后端重建券+秒杀缓存）。
 *
 * 口径：
 * - `ConsoleCacheKeyVO.ttl`：秒，`-1` 永不过期、`-2` 已不存在（后端原样下发 Redis TTL）。
 * - `value` 是 `string | number | null`（String 给库存值 / Hash 给 field 数），**渲染只用 String()**，
 *   不要对 number 假设有 `.toFixed`（admin-member 在 TYPES 通知里特别强调过）。
 * - 自动刷新默认开、每 30s 静默拉一次（不闪 loading）；离开页面一定清定时器。
 */

const AUTO_REFRESH_MS = 30_000

const loading = ref(false)
const refreshing = ref(false)
const rebuilding = ref(false)
const stats = ref<ConsoleCacheStatsVO | null>(null)
const lastRefreshAt = ref('')
let timer: number | undefined

const keys = computed<ConsoleCacheKeyVO[]>(() => stats.value?.keys ?? [])

/* ------------------------------------------------------------------ *
 * 加载
 * ------------------------------------------------------------------ */
function nowText(): string {
  const now = new Date()
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

/** silent=true 时不显示表格 loading（自动刷新用，避免每 30s 闪一下） */
async function load(silent = false): Promise<void> {
  if (!silent) loading.value = true
  try {
    stats.value = await fetchConsoleCacheStats()
    lastRefreshAt.value = nowText()
  } catch {
    stats.value = null
  } finally {
    if (!silent) loading.value = false
  }
}

async function handleRefresh(): Promise<void> {
  refreshing.value = true
  try {
    await load()
    showTip('缓存快照已刷新')
  } finally {
    refreshing.value = false
  }
}

async function handleRebuild(): Promise<void> {
  rebuilding.value = true
  try {
    const count = await refreshConsoleCache()
    showTip(count === null ? '缓存已重建' : `缓存已重建：${count} 项`)
    await load()
  } catch {
    // 响应拦截器已统一提示
  } finally {
    rebuilding.value = false
  }
}

/* ------------------------------------------------------------------ *
 * 30s 自动刷新
 * ------------------------------------------------------------------ */
const autoRefresh = ref(true)

function stopTimer(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

function startTimer(): void {
  stopTimer()
  timer = window.setInterval(() => {
    void load(true)
  }, AUTO_REFRESH_MS)
}

watch(autoRefresh, (enabled) => {
  if (enabled) {
    startTimer()
    showTip('已开启 30 秒自动刷新')
  } else {
    stopTimer()
    showTip('已关闭自动刷新', 'info')
  }
})

/* ------------------------------------------------------------------ *
 * 派生统计 + 展示
 * ------------------------------------------------------------------ */
/** TTL 最短（且未过期）的 key：运营最该关注的"快没了"的那把锁/库存 */
const shortestTtlKey = computed<ConsoleCacheKeyVO | null>(() => {
  const alive = keys.value.filter((row) => row.ttl > 0)
  if (!alive.length) return null
  return alive.reduce((min, row) => (row.ttl < min.ttl ? row : min), alive[0])
})

const shortestTtlText = computed(() => {
  const row = shortestTtlKey.value
  return row ? ttlText(row.ttl) : '—'
})

const shortestTtlKeyName = computed(() => {
  const row = shortestTtlKey.value
  if (!row) return '没有带 TTL 的 key'
  return row.key
})

function ttlText(ttl?: number | null): string {
  if (ttl === undefined || ttl === null) return '—'
  if (ttl === -1) return '永不过期'
  if (ttl === -2) return '已失效'
  if (ttl <= 0) return `${ttl}s`
  if (ttl < 60) return `${ttl}s`
  if (ttl < 3600) return `${Math.floor(ttl / 60)}m ${ttl % 60}s`
  return `${Math.floor(ttl / 3600)}h ${Math.floor((ttl % 3600) / 60)}m`
}

/** TTL 剩余不足 5 分钟给个警示色，方便一眼看到快过期的库存 key */
function ttlUrgent(ttl: number): boolean {
  return ttl >= 0 && ttl < 300
}

/** value 可能是 string | number | null：统一转字符串，长值交给表格 tooltip */
function valueText(row: ConsoleCacheKeyVO): string {
  if (row.value === null || row.value === undefined) return '—'
  return String(row.value)
}

function typeTagType(type: string): 'success' | 'info' | 'warning' {
  const normalized = (type || '').toLowerCase()
  if (normalized === 'string') return 'success'
  if (normalized === 'hash') return 'warning'
  return 'info'
}

onMounted(() => {
  void load()
  startTimer()
})

onBeforeUnmount(stopTimer)
</script>

<template>
  <div class="console-page">
    <!-- 工具条 -->
    <div class="cp-bar">
      <div class="cp-bar__title">缓存运维</div>
      <span class="cp-bar__count">
        只读快照 · {{ lastRefreshAt ? `最近刷新 ${lastRefreshAt}` : '尚未刷新' }}
      </span>
      <div class="cp-bar__spacer" />
      <div class="cp-auto">
        <span class="cp-sub">30 秒自动刷新</span>
        <el-switch v-model="autoRefresh" />
      </div>
      <el-button :icon="Refresh" :loading="refreshing" @click="handleRefresh">刷新快照</el-button>
      <el-button
        type="primary"
        size="large"
        :icon="Refresh"
        :loading="rebuilding"
        @click="handleRebuild"
      >
        立即重建缓存
      </el-button>
    </div>

    <!-- 3 个统计卡 -->
    <div class="cp-kpis">
      <div class="cp-kpi">
        <div class="cp-kpi__top">
          <span class="cp-kpi__label">平台缓存 key 总数</span>
          <span class="cp-kpi__icon"><el-icon><Coin /></el-icon></span>
        </div>
        <div class="cp-kpi__value">{{ stats?.totalKeys ?? keys.length }}</div>
        <div class="cp-kpi__foot">扫描前缀 <code>ershou:platform:*</code></div>
      </div>

      <div class="cp-kpi" :class="{ 'is-warn': stats?.warmupLockHeld }">
        <div class="cp-kpi__top">
          <span class="cp-kpi__label">预热分布式锁</span>
          <span class="cp-kpi__icon"><el-icon><Lock /></el-icon></span>
        </div>
        <div class="cp-kpi__value">{{ stats?.warmupLockHeld ? '持有中' : '空闲' }}</div>
        <div class="cp-kpi__foot">
          {{ stats?.warmupLockHeld ? '有实例正在预热，稍后再刷新' : '当前没有实例在预热（ershou:platform:lock:warmup）' }}
        </div>
      </div>

      <div class="cp-kpi">
        <div class="cp-kpi__top">
          <span class="cp-kpi__label">最快到期 key</span>
          <span class="cp-kpi__icon"><el-icon><Timer /></el-icon></span>
        </div>
        <div class="cp-kpi__value">{{ shortestTtlText }}</div>
        <div class="cp-kpi__foot cp-kpi__foot--key" :title="shortestTtlKeyName">
          {{ shortestTtlKeyName }}
        </div>
      </div>
    </div>

    <!-- key 明细 -->
    <div class="cp-card">
      <div class="cp-card__head">
        <div>
          <p class="cp-card__title">Redis key 明细</p>
          <p class="cp-sub">
            String 类型显示库存值，Hash 类型显示 field 数量；「立即重建缓存」会重算券与秒杀库存
          </p>
        </div>
        <span class="cp-sub">{{ keys.length }} 个 key</span>
      </div>

      <el-skeleton v-if="loading && !keys.length" :rows="6" animated class="cp-skeleton" />

      <el-table v-else :data="keys" row-key="key" class="cp-table">
        <el-table-column label="Key" min-width="360" show-overflow-tooltip>
          <template #default="{ row }">
            <code class="cp-key">{{ row.key }}</code>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="typeTagType(row.type)" effect="light" round size="small">
              {{ (row.type || 'unknown').toLowerCase() }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="TTL" width="140">
          <template #default="{ row }">
            <span class="cp-ttl" :class="{ 'cp-ttl--urgent': ttlUrgent(row.ttl) }">
              {{ ttlText(row.ttl) }}
            </span>
            <span v-if="row.ttl >= 0" class="cp-sub">（{{ row.ttl }}s）</span>
          </template>
        </el-table-column>
        <el-table-column label="值" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cp-value">{{ valueText(row) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="cp-empty">
            <div class="cp-empty__icon">🗄️</div>
            <p>还没有扫到 <code>ershou:platform:*</code> 的 key——点「立即重建缓存」让后端预热一次</p>
          </div>
        </template>
      </el-table>
    </div>
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

.cp-auto {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding-right: 6px;
}

// ---------- 3 个统计卡 ----------
.cp-kpis {
  display: grid;
  gap: 20px;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.cp-kpi {
  position: relative;
  padding: 20px 22px;
  border-radius: $radius-lg;
  background: #fff;
  box-shadow: $card-shadow;

  // 关键卡左侧 4px 品牌色条（设计第二节 KPI 卡规范）
  &::before {
    content: '';
    position: absolute;
    top: 20px;
    bottom: 20px;
    left: 0;
    width: 4px;
    border-radius: 0 4px 4px 0;
    background: var(--brand-500);
  }

  &.is-warn::before {
    background: var(--accent-500);
  }

  &__top {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 10px;
  }

  &__label {
    color: var(--text-3);
    font-size: 13px;
  }

  &__icon {
    display: grid;
    width: 40px;
    height: 40px;
    flex-shrink: 0;
    place-items: center;
    border-radius: 50%;
    background: var(--brand-50);
    color: var(--brand-600);
    font-size: 18px;
  }

  &__value {
    margin-top: 6px;
    color: var(--text-1);
    font-size: 32px;
    font-weight: 700;
    letter-spacing: -0.8px;
    line-height: 1.2;
    font-variant-numeric: tabular-nums;
  }

  &__foot {
    margin-top: 6px;
    color: var(--text-3);
    font-size: 12px;
    line-height: 1.5;

    &--key {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 11.5px;
    }

    code {
      padding: 0 4px;
      border-radius: 4px;
      background: var(--surface-2);
      font-size: 11px;
    }
  }
}

// ---------- 明细卡 ----------
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
    padding: 18px 22px;
    border-bottom: 1px solid var(--line);
  }

  &__title {
    color: var(--text-1);
    font-size: 17px;
    font-weight: 700;
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

  :deep(.cell) {
    line-height: 1.5;
  }
}

.cp-key {
  color: var(--text-1);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
}

.cp-sub {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}

.cp-ttl {
  color: var(--text-2);
  font-variant-numeric: tabular-nums;

  &--urgent {
    color: var(--accent-600);
    font-weight: 600;
  }
}

.cp-value {
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
  word-break: break-all;
}

.cp-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 56px 0;
  color: var(--text-3);
  font-size: 13px;

  code {
    padding: 0 4px;
    border-radius: 4px;
    background: var(--surface-2);
    font-size: 12px;
  }

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
